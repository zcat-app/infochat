package app.zcat.infochat.provider.chat;

import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Orchestrates the chat-mode dispatch loop: in-flight gate, prompt build,
// LLM multi-turn tool loop, session persistence, output sanitize, translate.
@ApplicationScoped
public class ChatAgent {

    private static final Logger log = LoggerFactory.getLogger(ChatAgent.class);

    // Text-based tool call protocol for v1's single-string LLM SPI.
    // The system prompt instructs the LLM to emit exactly this format;
    // the parser below extracts tool name and JSON args.
    static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
            "TOOL_CALL:\\s*(\\w+)\\s+(\\{.*?\\})", Pattern.DOTALL);

    // Broader pattern for the strip pass: catches partial tool calls
    // (no JSON body, broken JSON) that the parsing pattern above misses.
    // LLM is instructed to put tool calls on their own line, so
    // stripping to end-of-line is safe.
    private static final Pattern TOOL_CALL_STRIP_PATTERN = Pattern.compile(
            "TOOL_CALL:.*");

    static final int MAX_TOOL_ITERATIONS = 10;

    static final String TOOL_INSTRUCTIONS =
            "\n\nYou have the following tools. To call a tool, output EXACTLY "
          + "one line per call in this format (no surrounding prose on the same line):\n"
          + "TOOL_CALL: toolName {\"param\": \"value\"}\n\n"
          + "Available tools:\n"
          + "- searchPosts {\"tags\": [\"tag1\"], \"window\": \"P7D\", \"limit\": 10}"
          + " — search posts by tags within a time window\n"
          + "- getPost {\"uid\": \"post-uid\"} — retrieve a single post by UID\n"
          + "- getReferences {\"uid\": \"post-uid\"} — get references for a post\n"
          + "- recallMemory {\"keywords\": [\"keyword1\", \"keyword2\"]}"
          + " — recall conversation memories by keyword\n"
          + "- listSaves {\"tags\": [\"tag1\"], \"window\": \"P7D\"}"
          + " — list saved posts filtered by personal tags within a time window\n\n"
          + "After receiving a tool result, you may call another tool or provide "
          + "your final answer as plain text. Do NOT call a tool and provide a "
          + "final answer in the same response.";

    private static final String SELECT_SCOPE_LANGUAGE =
            "SELECT language FROM scope_preferences WHERE scope_kind = ? AND scope_id = ?";

    private final InFlightTracker inFlightTracker;
    private final ChatPromptBuilder promptBuilder;
    private final ChatToolDispatcher toolDispatcher;
    private final ChatSessionRepository sessionRepository;
    private final LlmRouter llmRouter;
    private final LlmOutputSanitizer outputSanitizer;
    private final TranslationPipeline translationPipeline;
    private final BundleLoader bundleLoader;
    private final AutoCompressTrigger autoCompressTrigger;
    private final AuditLogWriter auditLogWriter;
    private final DataSource dataSource;

    @Inject
    public ChatAgent(@NonNull InFlightTracker inFlightTracker,
                     @NonNull ChatPromptBuilder promptBuilder,
                     @NonNull ChatToolDispatcher toolDispatcher,
                     @NonNull ChatSessionRepository sessionRepository,
                     @NonNull LlmRouter llmRouter,
                     @NonNull LlmOutputSanitizer outputSanitizer,
                     @NonNull TranslationPipeline translationPipeline,
                     @NonNull BundleLoader bundleLoader,
                     @NonNull AutoCompressTrigger autoCompressTrigger,
                     @NonNull AuditLogWriter auditLogWriter,
                     @NonNull DataSource dataSource) {
        this.inFlightTracker = inFlightTracker;
        this.promptBuilder = promptBuilder;
        this.toolDispatcher = toolDispatcher;
        this.sessionRepository = sessionRepository;
        this.llmRouter = llmRouter;
        this.outputSanitizer = outputSanitizer;
        this.translationPipeline = translationPipeline;
        this.bundleLoader = bundleLoader;
        this.autoCompressTrigger = autoCompressTrigger;
        this.auditLogWriter = auditLogWriter;
        this.dataSource = dataSource;
    }

    /**
     * Handle a chat-mode message. Returns the reply text to send back to
     * the user. The in-flight slot is acquired and released within this
     * method; callers need not manage it.
     */
    public @NonNull String handle(@NonNull UUID userId, @NonNull String scopeKind,
                                   @NonNull UUID scopeId, @NonNull String userMessage) {
        if (!inFlightTracker.tryAcquire(userId, scopeKind, scopeId)) {
            return bundleLoader.get(BundleKeys.ERROR_CHAT_IN_FLIGHT);
        }
        try {
            return doHandle(userId, scopeKind, scopeId, userMessage);
        } catch (Exception e) {
            // LLM unreachable or any other failure → friendly error.
            // No session advance, no memory write, no tool invocation
            // beyond what already ran before the failure.
            SafeLog.error(log, "ChatAgent.handle failed for userId=" + userId, e);
            return bundleLoader.get(BundleKeys.ERROR_CHAT_UNAVAILABLE);
        } finally {
            inFlightTracker.release(userId, scopeKind, scopeId);
        }
    }

    private String doHandle(UUID userId, String scopeKind, UUID scopeId, String userMessage) {
        String scopeLanguage = readScopeLanguage(scopeKind, scopeId);

        // 1. Build prompt (pre-fetches memory internally)
        ChatPromptBuilder.BuiltPrompt prompt =
                promptBuilder.build(userId, scopeKind, scopeId, userMessage);

        // 2. Audit the chat-mode intent before the LLM call.
        // No user-authored prose in the audit row — only actor + scope.
        writeAuditRow(userId, scopeKind, scopeId);

        // 3. Resolve LLM provider for chat task
        LlmProvider provider = llmRouter.forTask(ModelTask.CHAT_AGENT, scopeLanguage);

        // 4. Run multi-turn tool loop
        String baseSystemPrompt = prompt.systemPrompt();
        String augmentedSystemPrompt = baseSystemPrompt + TOOL_INSTRUCTIONS;
        String finalText = runToolLoop(provider, augmentedSystemPrompt, baseSystemPrompt,
                prompt.userPrompt(), userId, scopeKind, scopeId);

        // 5. Strip any residual TOOL_CALL patterns that leaked past
        // the iteration cap — they are internal protocol, not user-visible.
        // Two passes: the specific pattern for full calls, then the broad
        // pattern for partials (no JSON body, broken JSON)
        finalText = TOOL_CALL_PATTERN.matcher(finalText).replaceAll("");
        finalText = TOOL_CALL_STRIP_PATTERN.matcher(finalText).replaceAll("");

        // 6. Sanitize BEFORE persist so admin commands never enter the DB
        String sanitized = outputSanitizer.sanitize(finalText);

        // 7. Persist both turns (user + sanitized assistant)
        int userTokens = ChatSessionRepository.estimateTokens(userMessage);
        sessionRepository.persistTurn(userId, scopeKind, scopeId, "user", userMessage, userTokens);
        int assistantTokens = ChatSessionRepository.estimateTokens(sanitized);
        sessionRepository.persistTurn(userId, scopeKind, scopeId, "assistant", sanitized, assistantTokens);

        // 8. Translate if scope language is non-en
        String reply;
        if (!"en".equals(scopeLanguage)) {
            reply = translationPipeline.run(sanitized, scopeLanguage);
        } else {
            reply = sanitized;
        }

        // 9. Auto-compress: fires between turns (after reply computed,
        // before next message). Notification appended to the reply.
        Optional<String> autoCompressNotice =
                autoCompressTrigger.checkAndCompress(userId, scopeKind, scopeId, scopeLanguage);
        if (autoCompressNotice.isPresent()) {
            return reply + "\n\n" + autoCompressNotice.get();
        }
        return reply;
    }

    /**
     * Multi-turn tool loop. Calls the LLM, parses for tool calls, executes
     * tools, feeds results back, repeats until no tool calls remain or the
     * iteration cap is reached.
     */
    String runToolLoop(LlmProvider provider, String systemPrompt,
                       String baseSystemPrompt, String userPrompt,
                       UUID userId, String scopeKind, UUID scopeId) {
        StringBuilder conversation = new StringBuilder(userPrompt);
        ChatToolDispatcher.TurnContext turnContext = new ChatToolDispatcher.TurnContext();

        for (int i = 0; i < MAX_TOOL_ITERATIONS; i++) {
            LlmResponse response = provider.generate(
                    ModelTask.CHAT_AGENT, systemPrompt, conversation.toString());
            String text = response.text();

            Matcher matcher = TOOL_CALL_PATTERN.matcher(text);
            if (!matcher.find()) {
                return text;
            }

            // Extract and execute the tool call
            String toolName = matcher.group(1);
            String argsJson = matcher.group(2);
            Map<String, Object> args = parseToolArgs(argsJson);

            ChatToolDispatcher.ToolResult result =
                    toolDispatcher.dispatch(toolName, args, userId, scopeKind, scopeId, turnContext);

            String resultText = switch (result) {
                case ChatToolDispatcher.ToolResult.Success s -> s.content();
                case ChatToolDispatcher.ToolResult.ValidationError v -> "Error: " + v.reason();
            };

            // Wrap tool results in UNTRUSTED_CONTENT delimiters — tool
            // output is external data and gets the same injection defense
            // as user messages and memory hits in ChatPromptBuilder
            String resultMarker = UUID.randomUUID().toString();
            String wrappedResult =
                    String.format(ChatPromptBuilder.UNTRUSTED_CONTENT_OPEN_FORMAT, resultMarker)
                    + "\n" + resultText + "\n"
                    + String.format(ChatPromptBuilder.UNTRUSTED_CONTENT_CLOSE_FORMAT, resultMarker);

            conversation.append("\n\nAssistant: ").append(text);
            conversation.append("\n\nTool result for ").append(toolName).append(":\n");
            conversation.append(wrappedResult);
            conversation.append("\n\nPlease provide your response based on the tool result above.");
        }

        // Exceeded iteration cap — final call uses base system prompt (without
        // tool instructions) so the LLM cannot emit tool-call patterns
        LlmResponse finalResponse = provider.generate(
                ModelTask.CHAT_AGENT, baseSystemPrompt, conversation.toString());
        return finalResponse.text();
    }

    /**
     * Minimal JSON arg parser for the v1 text-based tool call protocol.
     * Handles flat key-value objects with string and integer values.
     * The LLM is instructed to emit simple JSON; nested objects are
     * outside the v1 tool schema.
     */
    static Map<String, Object> parseToolArgs(String json) {
        Map<String, Object> args = new HashMap<>();
        if (json == null || json.isBlank()) return args;

        String trimmed = json.trim();
        if (trimmed.equals("{}")) return args;

        // Strip outer braces
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }

        // Split on commas (outside quotes)
        for (String pair : splitTopLevel(trimmed)) {
            String[] kv = pair.split(":", 2);
            if (kv.length != 2) continue;
            String key = kv[0].trim().replaceAll("^\"|\"$", "");
            String value = kv[1].trim();

            if (value.startsWith("\"") && value.endsWith("\"")) {
                args.put(key, value.substring(1, value.length() - 1));
            } else {
                try {
                    args.put(key, Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    args.put(key, value);
                }
            }
        }
        return args;
    }

    private static String[] splitTopLevel(String s) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        int depth = 0;
        boolean inQuote = false;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
            } else if (!inQuote) {
                if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') depth--;
                else if (c == ',' && depth == 0) {
                    parts.add(s.substring(start, i).trim());
                    start = i + 1;
                }
            }
        }
        if (start < s.length()) {
            parts.add(s.substring(start).trim());
        }
        return parts.toArray(new String[0]);
    }

    // Package-private so ChatAgentTest can override with a no-op.
    // target_kind is "user" (the actor); scope_kind ("dm"/"group") goes
    // into details_json so the audit row passes the V5 CHECK constraint
    // (allowed: user, group, source, post, invite, quarantine, asset,
    // memory, system).
    void writeAuditRow(UUID userId, String scopeKind, UUID scopeId) {
        try (Connection conn = dataSource.getConnection()) {
            RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                    .actorUserId(userId)
                    .action(AuditAction.CHAT_MODE)
                    .targetKind("user")
                    .targetId(userId.toString())
                    .detailsJson("{\"scope_kind\":\"" + scopeKind
                            + "\",\"scope_id\":\"" + scopeId + "\"}")
                    .build();
            auditLogWriter.write(conn, row);
        } catch (SQLException e) {
            throw new IllegalStateException("ChatAgent.writeAuditRow failed", e);
        }
    }

    // Package-private so ChatAgentTest can override with a fixed value
    // (same pattern as InboundRouter.lookupUser).
    String readScopeLanguage(String scopeKind, UUID scopeId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_SCOPE_LANGUAGE)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "en";
                return rs.getString("language");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("ChatAgent.readScopeLanguage failed", e);
        }
    }
}
