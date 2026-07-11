package app.zcat.infochat.provider.chat;

import app.zcat.infochat.provider.chat.tool.GetPostTool;
import app.zcat.infochat.provider.chat.tool.GetReferencesTool;
import app.zcat.infochat.provider.chat.tool.ListSavesTool;
import app.zcat.infochat.provider.chat.tool.RecallMemoryTool;
import app.zcat.infochat.provider.chat.tool.SearchPostsTool;
import app.zcat.infochat.provider.chat.tool.SemanticSearchTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import java.sql.SQLException;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

// Routes a tool call by name to the matching implementation; rejects
// unknown names and oversized inputs before any SQL runs
// (security.md §Prompt-injection defenses).
@ApplicationScoped
public class ChatToolDispatcher {

    public sealed interface ToolResult {
        record Success(String content) implements ToolResult {}
        record ValidationError(String reason) implements ToolResult {}
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

    // Canonical JSON serialization of tool args for the per-turn cache key.
    // ORDER_MAP_ENTRIES_BY_KEYS sorts map keys at EVERY nesting depth, so two
    // logically-equal arg maps that differ only in nested-map insertion order
    // serialize identically and hit the cache. The Jackson tool-arg parser
    // materializes nested objects as HashMap (and arrays as List), whose
    // iteration order is implementation-defined; the prior new TreeMap<>(args)
    // sorted only the top-level keys, so nested differences produced distinct
    // keys and silent cache misses (M1-335).
    private static final ObjectMapper CACHE_KEY_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final ChatToolRegistry registry;
    private final Map<String, ChatToolRegistry.ChatTool> tools;
    private final int inputMaxLength;
    private final int limitCap;
    private final int listMaxSize;

    @Inject
    public ChatToolDispatcher(ChatToolRegistry registry,
                               SearchPostsTool searchPostsTool,
                               SemanticSearchTool semanticSearchTool,
                               GetPostTool getPostTool,
                               GetReferencesTool getReferencesTool,
                               RecallMemoryTool recallMemoryTool,
                               ListSavesTool listSavesTool,
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
                "semanticSearch", semanticSearchTool,
                "getPost", getPostTool,
                "getReferences", getReferencesTool,
                "recallMemory", recallMemoryTool,
                "listSaves", listSavesTool
        );
        requireHandlerForEveryAdvertisedTool(registry, this.tools);
    }

    // Package-private for testing with fake tool implementations
    ChatToolDispatcher(ChatToolRegistry registry,
                       Map<String, ChatToolRegistry.ChatTool> tools,
                       int inputMaxLength, int limitCap, int listMaxSize) {
        this.registry = registry;
        this.tools = Map.copyOf(tools);
        this.inputMaxLength = inputMaxLength;
        this.limitCap = limitCap;
        this.listMaxSize = listMaxSize;
        requireHandlerForEveryAdvertisedTool(registry, this.tools);
    }

    // Construction-time completeness check: every tool the system prompt
    // advertises (the ChatToolRegistry allowlist, mirrored by the prompt's
    // tool instructions) must have a registered handler, so a
    // registry-vs-wiring drift fails at startup rather than as a
    // mid-conversation dispatch miss.
    private static void requireHandlerForEveryAdvertisedTool(
            ChatToolRegistry registry, Map<String, ChatToolRegistry.ChatTool> tools) {
        for (String name : registry.toolNames()) {
            if (!tools.containsKey(name)) {
                throw new IllegalStateException("Missing tool implementation: " + name);
            }
        }
    }

    // Convenience overload: creates a fresh TurnContext per call (no
    // cross-call cap or caching). M1-063 session dispatch should use the
    // TurnContext-aware overload with a shared context per turn.
    public ToolResult dispatch(String toolName,
                                         Map<String, Object> args,
                                         UUID userId,
                                         String scopeKind,
                                         UUID scopeId) {
        return dispatch(toolName, args, userId, scopeKind, scopeId, new TurnContext());
    }

    public ToolResult dispatch(String toolName,
                                         Map<String, Object> args,
                                         UUID userId,
                                         String scopeKind,
                                         UUID scopeId,
                                         TurnContext turn) {
        if (!registry.toolNames().contains(toolName)) {
            return new ToolResult.ValidationError("Unknown tool: " + toolName);
        }

        // Clamp before the cache key is built so two calls differing only in an
        // over-cap value (e.g. limit=200 vs limit=500, both clamping to limitCap)
        // canonicalize to one key, hit the cache, and charge turn.callCount once
        // instead of each consuming a call-budget slot (M1-375). clampLimit's
        // `(Number) args.get("limit")` cast throws ClassCastException when the
        // model emits a non-numeric limit (e.g. {"limit":"ten"}); that must
        // surface as a typed validation error the LLM can self-correct on, not
        // escape the turn.
        Map<String, Object> validatedArgs = new HashMap<>(args);
        try {
            clampLimit(validatedArgs);
        } catch (ClassCastException e) {
            return new ToolResult.ValidationError(
                    "Invalid argument type or format for tool: " + toolName);
        }

        // Validate lengths on validatedArgs (the same map cached and dispatched)
        // and before the cache/cap block, so an over-length call is rejected
        // without consuming a per-turn call-budget slot — the budget counts
        // executions, and a length-rejected call never executes (M1-405).
        ToolResult lengthCheck = validateInputLengths(validatedArgs);
        if (lengthCheck != null) return lengthCheck;

        // Per-turn cache: calls identical after clamping return the cached result
        String cacheKey = toolName + "|" + userId + "|" + scopeKind
                        + "|" + scopeId + "|" + canonicalArgs(validatedArgs);
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

        // Non-null by construction: the constructor asserts tools covers every
        // registry tool name, and line above rejects names not in the registry.
        ChatToolRegistry.ChatTool tool = Objects.requireNonNull(tools.get(toolName));
        try {
            String result = tool.execute(userId, scopeKind, scopeId, validatedArgs);
            turn.cache.put(cacheKey, result);
            return new ToolResult.Success(result);
        } catch (IllegalArgumentException e) {
            // NumberFormatException (a subclass) lands here too.
            return new ToolResult.ValidationError(
                    Objects.requireNonNullElse(e.getMessage(), "Invalid argument"));
        } catch (ClassCastException | DateTimeParseException e) {
            // Wrong runtime type or unparseable window (Duration.parse) →
            // a self-correctable signal, not the opaque chat-unavailable error.
            // The Java exception text is not surfaced; it would leak internals.
            return new ToolResult.ValidationError(
                    "Invalid argument type or format for tool: " + toolName);
        } catch (SQLException e) {
            throw new IllegalStateException("Tool execution failed: " + toolName, e);
        }
    }

    private @Nullable ToolResult validateInputLengths(Map<String, Object> args) {
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            ToolResult error = validateValue(entry.getKey(), entry.getValue());
            if (error != null) return error;
        }
        return null;
    }

    // Recurses into nested List and Map values so the length/size caps apply to
    // every shape the Jackson tool-arg parser can produce (string, list, nested
    // object), not just the top-level String/List<String> the flat v1 parser
    // emitted. The bound must be enforced here, at the dispatch boundary before
    // any SQL or tool execution — not incidentally by a downstream (List<String>)
    // cast failure in the consuming tool.
    private @Nullable ToolResult validateValue(String key, Object value) {
        if (value instanceof String s && s.length() > inputMaxLength) {
            return new ToolResult.ValidationError(
                    "Input '" + key + "' exceeds maximum length of " + inputMaxLength);
        }
        if (value instanceof List<?> list) {
            if (list.size() > listMaxSize) {
                return new ToolResult.ValidationError(
                        "List '" + key + "' exceeds maximum size of " + listMaxSize);
            }
            for (Object item : list) {
                // A list element must itself be one of the accepted shapes
                // (string, nested list, or map). A model-supplied scalar like
                // {"tags":[123]} would otherwise pass this boundary untyped and
                // surface downstream as an ArrayStoreException out of
                // SearchPostsTool's createArrayOf — an internal error the model
                // cannot self-correct on, rather than a ValidationError it can.
                if (!(item instanceof String
                        || item instanceof List<?>
                        || item instanceof Map<?, ?>)) {
                    return new ToolResult.ValidationError(
                            "List '" + key + "' contains an unsupported element type");
                }
                ToolResult error = validateValue(key, item);
                if (error != null) return error;
            }
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                ToolResult error = validateValue(key, item);
                if (error != null) return error;
            }
        }
        return null;
    }

    private static String canonicalArgs(Map<String, Object> args) {
        try {
            return CACHE_KEY_MAPPER.writeValueAsString(args);
        } catch (JsonProcessingException e) {
            // args originate from the Jackson tool-arg parser (JSON text →
            // Map/List/scalar), so they are always JSON-serializable; this
            // branch is unreachable. Rethrow rather than swallow so a future
            // arg shape that breaks the invariant fails loudly (M1-335).
            throw new IllegalStateException(
                    "Tool args not serializable for cache key", e);
        }
    }

    private void clampLimit(Map<String, Object> args) {
        if (!args.containsKey("limit")) return;
        int limit = ((Number) args.get("limit")).intValue();
        if (limit > limitCap) args.put("limit", limitCap);
        if (limit < 1) args.put("limit", 1);
    }
}
