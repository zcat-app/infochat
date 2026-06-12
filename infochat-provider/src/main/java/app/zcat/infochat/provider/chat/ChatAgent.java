package app.zcat.infochat.provider.chat;

import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.TargetKind;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
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
    // group(1) is the tool name and group(2) is the opening brace of the
    // JSON args. The args body is delimited by scanning for the brace's
    // balanced match (matchBrace) rather than a reluctant `\{.*?\}`, which
    // would truncate nested objects at the first inner '}'.
    static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
            "TOOL_CALL:\\s*(\\w+)\\s*(\\{)");

    private static final ObjectMapper TOOL_ARGS_MAPPER = new ObjectMapper();

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
    public ChatAgent(InFlightTracker inFlightTracker,
                     ChatPromptBuilder promptBuilder,
                     ChatToolDispatcher toolDispatcher,
                     ChatSessionRepository sessionRepository,
                     LlmRouter llmRouter,
                     LlmOutputSanitizer outputSanitizer,
                     TranslationPipeline translationPipeline,
                     BundleLoader bundleLoader,
                     AutoCompressTrigger autoCompressTrigger,
                     AuditLogWriter auditLogWriter,
                     DataSource dataSource) {
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
     * the user, or {@code null} when {@code /stop} cancelled the request —
     * the {@code /stop} handler already replied, so the router must send
     * nothing further. The in-flight slot is acquired and released within
     * this method; callers need not manage it.
     */
    public @Nullable String handle(UUID userId, String scopeKind,
                                   UUID scopeId, String userMessage) {
        // Resolved ahead of the in-flight gate so the contention notice
        // and the catch-all unavailable reply localize too (D43).
        String scopeLanguage = readScopeLanguage(scopeKind, scopeId);
        InFlightTracker.CancellationHandle slot =
                inFlightTracker.tryAcquire(userId, scopeKind, scopeId);
        if (slot == null) {
            return bundleLoader.get(BundleKeys.ERROR_CHAT_IN_FLIGHT, scopeLanguage);
        }
        try {
            String reply = doHandle(userId, scopeKind, scopeId, userMessage, scopeLanguage);
            // Delivery boundary: /stop may have marked this request cancelled
            // even though the work completed — the interrupt landed after the
            // last interruptible point, or it never landed at all (a "missed
            // interrupt"). Discard the result so it is not delivered as a
            // second, stale reply alongside the /stop acknowledgement.
            if (slot.isCancelled()) {
                return null;
            }
            return reply;
        } catch (Exception e) {
            // A landed cancellation interrupt surfaces here as an exception.
            // When /stop marked this request the /stop handler already
            // replied — return null (no content) rather than double-replying
            // with the unavailable notice.
            if (slot.isCancelled()) {
                return null;
            }
            // LLM unreachable or any other failure → friendly error.
            // No session advance, no memory write, no tool invocation
            // beyond what already ran before the failure.
            SafeLog.error(log, "ChatAgent.handle failed for userId=" + userId, e);
            return bundleLoader.get(BundleKeys.ERROR_CHAT_UNAVAILABLE, scopeLanguage);
        } finally {
            inFlightTracker.release(userId, scopeKind, scopeId, slot);
        }
    }

    private String doHandle(UUID userId, String scopeKind, UUID scopeId,
                            String userMessage, String scopeLanguage) {
        // Ceiling gate: a failed auto-compress left this session at its
        // token ceiling — reject the turn outright (no LLM call, no
        // persist) instead of silently growing past the ceiling. Clears
        // when a compress succeeds or /clear empties the session.
        if (autoCompressTrigger.isCeilingGated(userId, scopeKind, scopeId)) {
            return bundleLoader.get(BundleKeys.ERROR_COMPRESS_FAILED, scopeLanguage);
        }

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

        // 5. Strip any residual TOOL_CALL fragments that leaked past the
        // iteration cap — they are internal protocol, not user-visible.
        // A fragment with balanced braces (possibly nested / multi-line) is
        // removed whole; a partial or unbalanced fragment is removed through
        // end-of-text so a malformed multi-line call cannot leak.
        finalText = stripToolCalls(finalText);

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

            // Extract and execute the tool call. group(2) is the opening
            // brace; scan for its balanced match so nested objects survive
            // intact. An unbalanced fragment falls back to the tail of the
            // text, which Jackson then rejects (→ empty args).
            String toolName = matcher.group(1);
            int braceStart = matcher.start(2);
            int braceEnd = matchBrace(text, braceStart);
            String argsJson = braceEnd >= 0
                    ? text.substring(braceStart, braceEnd + 1)
                    : text.substring(braceStart);
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
     * Parses the JSON args of a text-based tool call into a map of plain
     * JDK values. Array values become {@code List<String>}, nested objects
     * become {@code Map<String, Object>}, integers in {@code int} range
     * become {@code Integer}, and string values stay {@code String} — the
     * runtime types every consuming tool casts to. Malformed JSON yields an
     * empty map (no throw): the loop continues and the tool runs with no
     * args rather than aborting the whole turn. The signature is kept
     * {@code static Map<String, Object>(String)} so callers and the
     * existing unit tests are unaffected by the Jackson rewrite.
     */
    static Map<String, Object> parseToolArgs(String json) {
        Map<String, Object> args = new HashMap<>();
        if (json == null || json.isBlank()) return args;

        JsonNode root;
        try {
            root = TOOL_ARGS_MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return args;
        }
        if (root == null || !root.isObject()) return args;

        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            args.put(field.getKey(), toJavaValue(field.getValue()));
        }
        return args;
    }

    // Converts a JsonNode to the plain JDK type the tool consumers cast to:
    // arrays → List<String>, objects → Map<String, Object>, in-range
    // integers → Integer (so `(Number) args.get("limit")` and the
    // `assertEquals(10, ...)` in tests both hold), other scalars → their
    // natural Java value.
    private static Object toJavaValue(JsonNode node) {
        return switch (node.getNodeType()) {
            case ARRAY -> {
                List<String> list = new ArrayList<>(node.size());
                for (JsonNode element : node) {
                    list.add(element.asText());
                }
                yield list;
            }
            case OBJECT -> {
                Map<String, Object> map = new HashMap<>();
                Iterator<Map.Entry<String, JsonNode>> it = node.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    map.put(entry.getKey(), toJavaValue(entry.getValue()));
                }
                yield map;
            }
            case NUMBER -> node.canConvertToInt()
                    ? (Object) node.intValue()
                    : node.isIntegralNumber()
                            ? (Object) node.longValue()
                            : (Object) node.doubleValue();
            case BOOLEAN -> node.booleanValue();
            default -> node.asText();
        };
    }

    /**
     * Strips every residual TOOL_CALL fragment from final text. A fragment
     * whose JSON args have balanced braces is removed exactly (text before
     * and after it is preserved); a fragment with no brace or unbalanced
     * braces is removed through end-of-text, because a malformed multi-line
     * call has no reliable terminator and must not leak the internal
     * protocol to the user.
     */
    static String stripToolCalls(String text) {
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        while (cursor < text.length()) {
            int marker = text.indexOf("TOOL_CALL:", cursor);
            if (marker < 0) {
                result.append(text, cursor, text.length());
                return result.toString();
            }
            result.append(text, cursor, marker);

            int brace = text.indexOf('{', marker);
            int lineEnd = text.indexOf('\n', marker);
            if (brace >= 0 && (lineEnd < 0 || brace < lineEnd)) {
                int close = matchBrace(text, brace);
                if (close >= 0) {
                    cursor = close + 1;
                    continue;
                }
            }
            // Partial or unbalanced fragment: drop through end-of-text.
            return result.toString();
        }
        return result.toString();
    }

    // Returns the index of the '}' that balances the '{' at openIndex, or
    // -1 if the braces never balance. Quoted strings (and their escaped
    // characters) are skipped so braces inside a JSON string value and an
    // escaped quote (\") do not corrupt the depth count.
    private static int matchBrace(String text, int openIndex) {
        int depth = 0;
        boolean inQuote = false;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuote) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inQuote = false;
                }
            } else if (c == '"') {
                inQuote = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
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
                    .targetKind(TargetKind.USER)
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
