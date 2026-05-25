package app.zcat.infochat.provider.chat;

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

    static final int MAX_TOOL_ITERATIONS = 10;

    static final String TOOL_INSTRUCTIONS =
            "\n\nYou have the following tools. To call a tool, output EXACTLY "
          + "one line per call in this format (no surrounding prose on the same line):\n"
          + "TOOL_CALL: toolName {\"param\": \"value\"}\n\n"
          + "Available tools:\n"
          + "- searchPosts {\"query\": \"keyword\", \"limit\": 10} — search posts by keyword\n"
          + "- getPost {\"uid\": \"post-uid\"} — retrieve a single post by UID\n"
          + "- getReferences {\"uid\": \"post-uid\"} — get references for a post\n"
          + "- recallMemory {\"query\": \"keyword\"} — recall conversation memories\n"
          + "- listSaves {\"limit\": 10} — list the user's saved posts\n\n"
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
                     @NonNull DataSource dataSource) {
        this.inFlightTracker = inFlightTracker;
        this.promptBuilder = promptBuilder;
        this.toolDispatcher = toolDispatcher;
        this.sessionRepository = sessionRepository;
        this.llmRouter = llmRouter;
        this.outputSanitizer = outputSanitizer;
        this.translationPipeline = translationPipeline;
        this.bundleLoader = bundleLoader;
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
            log.error("ChatAgent.handle failed for userId={}", userId, e);
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

        // 2. Resolve LLM provider for chat task
        LlmProvider provider = llmRouter.forTask(ModelTask.CHAT_AGENT, scopeLanguage);

        // 3. Run multi-turn tool loop
        String systemPrompt = prompt.systemPrompt() + TOOL_INSTRUCTIONS;
        String finalText = runToolLoop(provider, systemPrompt, prompt.userPrompt(),
                userId, scopeKind, scopeId);

        // 4. Persist both turns (user + assistant)
        int userTokens = ChatSessionRepository.estimateTokens(userMessage);
        sessionRepository.persistTurn(userId, scopeKind, scopeId, "user", userMessage, userTokens);
        int assistantTokens = ChatSessionRepository.estimateTokens(finalText);
        sessionRepository.persistTurn(userId, scopeKind, scopeId, "assistant", finalText, assistantTokens);

        // 5. Sanitize output
        String sanitized = outputSanitizer.sanitize(finalText);

        // 6. Translate if scope language is non-en
        if (!"en".equals(scopeLanguage)) {
            return translationPipeline.run(sanitized, scopeLanguage);
        }
        return sanitized;
    }

    /**
     * Multi-turn tool loop. Calls the LLM, parses for tool calls, executes
     * tools, feeds results back, repeats until no tool calls remain or the
     * iteration cap is reached.
     */
    String runToolLoop(LlmProvider provider, String systemPrompt, String userPrompt,
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

            // Append the assistant's tool call and the result to the
            // conversation so the next LLM call sees the full history
            conversation.append("\n\nAssistant: ").append(text);
            conversation.append("\n\nTool result for ").append(toolName).append(":\n");
            conversation.append(resultText);
            conversation.append("\n\nPlease provide your response based on the tool result above.");
        }

        // Exceeded iteration cap — run one final call without tool instructions
        // to get a text-only response
        LlmResponse finalResponse = provider.generate(
                ModelTask.CHAT_AGENT, systemPrompt, conversation.toString());
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
