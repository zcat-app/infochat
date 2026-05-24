package app.zcat.infochat.provider.chat;

import app.zcat.infochat.provider.chat.tool.GetPostTool;
import app.zcat.infochat.provider.chat.tool.GetReferencesTool;
import app.zcat.infochat.provider.chat.tool.ListSavesTool;
import app.zcat.infochat.provider.chat.tool.RecallMemoryTool;
import app.zcat.infochat.provider.chat.tool.SearchPostsTool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.NonNull;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

// Routes a tool call by name to the matching implementation; rejects
// unknown names and oversized inputs before any SQL runs
// (security.md §Prompt-injection defenses).
@ApplicationScoped
public class ChatToolDispatcher {

    public sealed interface ToolResult {
        record Success(@NonNull String content) implements ToolResult {}
        record ValidationError(@NonNull String reason) implements ToolResult {}
    }

    // Per-turn state: tracks call count and caches results so identical
    // calls within the same turn don't re-query. The caller (M1-063
    // session dispatch) creates one per turn.
    public static class TurnContext {
        private static final int DEFAULT_CALL_CAP = 25;

        private final int callCap;
        private int callCount;
        private final Map<String, String> cache = new HashMap<>();

        public TurnContext() { this(DEFAULT_CALL_CAP); }

        public TurnContext(int callCap) { this.callCap = callCap; }
    }

    private final ChatToolRegistry registry;
    private final Map<String, ChatToolRegistry.ChatTool> tools;
    private final int inputMaxLength;
    private final int limitCap;
    private final int listMaxSize;

    @Inject
    public ChatToolDispatcher(@NonNull ChatToolRegistry registry,
                               @NonNull SearchPostsTool searchPostsTool,
                               @NonNull GetPostTool getPostTool,
                               @NonNull GetReferencesTool getReferencesTool,
                               @NonNull RecallMemoryTool recallMemoryTool,
                               @NonNull ListSavesTool listSavesTool,
                               @ConfigProperty(name = "infochat.chat.tool.input-max-length",
                                       defaultValue = "500") int inputMaxLength,
                               @ConfigProperty(name = "infochat.chat.tool.limit-cap",
                                       defaultValue = "200") int limitCap,
                               @ConfigProperty(name = "infochat.chat.tool.list-max-size",
                                       defaultValue = "20") int listMaxSize) {
        this.registry = registry;
        this.inputMaxLength = inputMaxLength;
        this.limitCap = limitCap;
        this.listMaxSize = listMaxSize;
        this.tools = Map.of(
                "searchPosts", searchPostsTool,
                "getPost", getPostTool,
                "getReferences", getReferencesTool,
                "recallMemory", recallMemoryTool,
                "listSaves", listSavesTool
        );
    }

    // Package-private for testing with fake tool implementations
    ChatToolDispatcher(@NonNull ChatToolRegistry registry,
                       @NonNull Map<String, ChatToolRegistry.ChatTool> tools,
                       int inputMaxLength, int limitCap, int listMaxSize) {
        this.registry = registry;
        this.tools = Map.copyOf(tools);
        this.inputMaxLength = inputMaxLength;
        this.limitCap = limitCap;
        this.listMaxSize = listMaxSize;
        for (String name : registry.toolNames()) {
            if (!this.tools.containsKey(name)) {
                throw new IllegalStateException("Missing tool implementation: " + name);
            }
        }
    }

    // Convenience overload: creates a fresh TurnContext per call (no
    // cross-call cap or caching). M1-063 session dispatch should use the
    // TurnContext-aware overload with a shared context per turn.
    public @NonNull ToolResult dispatch(@NonNull String toolName,
                                         @NonNull Map<String, Object> args,
                                         @NonNull UUID userId,
                                         @NonNull String scopeKind,
                                         @NonNull UUID scopeId) {
        return dispatch(toolName, args, userId, scopeKind, scopeId, new TurnContext());
    }

    public @NonNull ToolResult dispatch(@NonNull String toolName,
                                         @NonNull Map<String, Object> args,
                                         @NonNull UUID userId,
                                         @NonNull String scopeKind,
                                         @NonNull UUID scopeId,
                                         @NonNull TurnContext turn) {
        if (!registry.toolNames().contains(toolName)) {
            return new ToolResult.ValidationError("Unknown tool: " + toolName);
        }

        // Per-turn cache: identical calls return the cached result
        String cacheKey = toolName + "|" + userId + "|" + scopeKind
                        + "|" + scopeId + "|" + new TreeMap<>(args);
        String cached = turn.cache.get(cacheKey);
        if (cached != null) {
            return new ToolResult.Success(cached);
        }

        // Per-turn call cap (counts only non-cached executions)
        turn.callCount++;
        if (turn.callCount > turn.callCap) {
            return new ToolResult.ValidationError(
                    "Tool call limit exceeded for this turn");
        }

        ToolResult lengthCheck = validateInputLengths(args);
        if (lengthCheck != null) return lengthCheck;

        Map<String, Object> validatedArgs = new HashMap<>(args);
        clampLimit(validatedArgs);

        ChatToolRegistry.ChatTool tool = tools.get(toolName);
        try {
            String result = tool.execute(userId, scopeKind, scopeId, validatedArgs);
            turn.cache.put(cacheKey, result);
            return new ToolResult.Success(result);
        } catch (IllegalArgumentException e) {
            return new ToolResult.ValidationError(e.getMessage());
        } catch (SQLException e) {
            throw new IllegalStateException("Tool execution failed: " + toolName, e);
        }
    }

    private ToolResult validateInputLengths(Map<String, Object> args) {
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s && s.length() > inputMaxLength) {
                return new ToolResult.ValidationError(
                        "Input '" + entry.getKey()
                      + "' exceeds maximum length of " + inputMaxLength);
            }
            if (value instanceof List<?> list) {
                if (list.size() > listMaxSize) {
                    return new ToolResult.ValidationError(
                            "List '" + entry.getKey()
                          + "' exceeds maximum size of " + listMaxSize);
                }
                for (Object item : list) {
                    if (item instanceof String s && s.length() > inputMaxLength) {
                        return new ToolResult.ValidationError(
                                "List item in '" + entry.getKey()
                              + "' exceeds maximum length of " + inputMaxLength);
                    }
                }
            }
        }
        return null;
    }

    private void clampLimit(Map<String, Object> args) {
        if (!args.containsKey("limit")) return;
        int limit = ((Number) args.get("limit")).intValue();
        if (limit > limitCap) args.put("limit", limitCap);
        if (limit < 1) args.put("limit", 1);
    }
}
