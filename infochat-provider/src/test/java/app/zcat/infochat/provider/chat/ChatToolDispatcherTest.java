package app.zcat.infochat.provider.chat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatToolDispatcherTest {

    private static final UUID USER_A = UUID.randomUUID();
    private static final UUID USER_B = UUID.randomUUID();
    private static final UUID SCOPE_A = UUID.randomUUID();
    private static final UUID SCOPE_B = UUID.randomUUID();

    private static final ChatToolRegistry.ChatTool NO_OP =
            (userId, scopeKind, scopeId, args) -> "[]";

    // Builds a dispatcher where every tool defaults to NO_OP unless
    // overridden by the entries in the provided map.
    private static ChatToolDispatcher dispatcher(
            Map<String, ChatToolRegistry.ChatTool> overrides,
            int inputMaxLength, int limitCap) {
        Map<String, ChatToolRegistry.ChatTool> allTools = new HashMap<>();
        ChatToolRegistry registry = new ChatToolRegistry();
        for (String name : registry.toolNames()) {
            allTools.put(name, overrides.getOrDefault(name, NO_OP));
        }
        return new ChatToolDispatcher(registry, allTools, inputMaxLength, limitCap, 20);
    }

    private static ChatToolDispatcher dispatcher(
            Map<String, ChatToolRegistry.ChatTool> overrides) {
        return dispatcher(overrides, 500, 200);
    }

    private static Map<String, ChatToolRegistry.ChatTool> allToolsNoOp() {
        Map<String, ChatToolRegistry.ChatTool> tools = new HashMap<>();
        for (String name : new ChatToolRegistry().toolNames()) {
            tools.put(name, NO_OP);
        }
        return tools;
    }

    // --- Construction-time registry completeness ---

    @Test
    void constructionFailsWhenAdvertisedToolLacksHandler() {
        Map<String, ChatToolRegistry.ChatTool> tools = allToolsNoOp();
        tools.remove("recallMemory");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ChatToolDispatcher(
                        new ChatToolRegistry(), tools, 500, 200, 20));
        assertTrue(e.getMessage().contains("recallMemory"),
                "the exception must name the unhandled tool. Got: " + e.getMessage());
    }

    // --- Acceptance item 2: unknown tool name ---

    @Test
    void rejectsUnknownToolName() {
        ChatToolDispatcher d = dispatcher(Map.of());

        ChatToolDispatcher.ToolResult result =
                d.dispatch("unknownTool", Map.of(), USER_A, "dm", SCOPE_A);

        assertInstanceOf(ChatToolDispatcher.ToolResult.ValidationError.class, result);
        String reason = ((ChatToolDispatcher.ToolResult.ValidationError) result).reason();
        assertTrue(reason.contains("unknownTool"));
    }

    // --- Acceptance item 3: oversized input ---

    @Test
    void rejectsOversizedInput() {
        ChatToolDispatcher d = dispatcher(Map.of(), 10, 200);

        Map<String, Object> args = new HashMap<>();
        args.put("tags", List.of("a".repeat(11)));

        ChatToolDispatcher.ToolResult result =
                d.dispatch("searchPosts", args, USER_A, "dm", SCOPE_A);

        assertInstanceOf(ChatToolDispatcher.ToolResult.ValidationError.class, result);
    }

    // --- Redteam finding 1: oversized list ---

    @Test
    void rejectsOversizedList() {
        ChatToolDispatcher d = new ChatToolDispatcher(
                new ChatToolRegistry(),
                allToolsNoOp(), 500, 200, 3);

        Map<String, Object> args = new HashMap<>();
        args.put("tags", List.of("a", "b", "c", "d"));

        ChatToolDispatcher.ToolResult result =
                d.dispatch("searchPosts", args, USER_A, "dm", SCOPE_A);

        assertInstanceOf(ChatToolDispatcher.ToolResult.ValidationError.class, result);
        assertTrue(((ChatToolDispatcher.ToolResult.ValidationError) result)
                .reason().contains("maximum size"));
    }

    // --- Redteam finding 4: per-turn cap and cache ---

    @Test
    void turnCallCapEnforced() {
        ChatToolDispatcher d = dispatcher(Map.of());
        ChatToolDispatcher.TurnContext turn = new ChatToolDispatcher.TurnContext(2);

        d.dispatch("searchPosts", new HashMap<>(Map.of("q", "a")),
                USER_A, "dm", SCOPE_A, turn);
        d.dispatch("getPost", new HashMap<>(Map.of("uid", "x")),
                USER_A, "dm", SCOPE_A, turn);

        ChatToolDispatcher.ToolResult third = d.dispatch(
                "listSaves", new HashMap<>(), USER_A, "dm", SCOPE_A, turn);

        assertInstanceOf(ChatToolDispatcher.ToolResult.ValidationError.class, third);
        assertTrue(((ChatToolDispatcher.ToolResult.ValidationError) third)
                .reason().contains("limit exceeded"));
    }

    @Test
    void turnCacheReturnsIdenticalResult() {
        ChatToolRegistry.ChatTool counter = new ChatToolRegistry.ChatTool() {
            int calls;
            @Override
            public String execute(UUID u, String sk, UUID si, Map<String, Object> a) {
                calls++;
                return "[{\"call\":" + calls + "}]";
            }
        };
        ChatToolDispatcher d = dispatcher(Map.of("searchPosts", counter));
        ChatToolDispatcher.TurnContext turn = new ChatToolDispatcher.TurnContext();

        Map<String, Object> args = new HashMap<>();
        ChatToolDispatcher.ToolResult first =
                d.dispatch("searchPosts", args, USER_A, "dm", SCOPE_A, turn);
        ChatToolDispatcher.ToolResult second =
                d.dispatch("searchPosts", args, USER_A, "dm", SCOPE_A, turn);

        assertInstanceOf(ChatToolDispatcher.ToolResult.Success.class, first);
        assertInstanceOf(ChatToolDispatcher.ToolResult.Success.class, second);
        assertEquals(
                ((ChatToolDispatcher.ToolResult.Success) first).content(),
                ((ChatToolDispatcher.ToolResult.Success) second).content(),
                "Identical calls within the same turn must return the cached result");
    }

    // --- M1-335: the per-turn cache key is deterministic across nested-map
    // insertion order. Two logically-equal arg maps that differ only in the
    // insertion order of a nested map must produce the SAME cache key, so the
    // second dispatch is a cache hit. Pre-fix, new TreeMap<>(args).toString()
    // sorted only the top-level keys, leaving the nested map's order-dependent
    // toString() in the key and causing a silent miss. ---

    @Test
    void cacheKeyIsDeterministicAcrossNestedMapOrder() {
        ChatToolRegistry.ChatTool counter = new ChatToolRegistry.ChatTool() {
            int calls;
            @Override
            public String execute(UUID u, String sk, UUID si, Map<String, Object> a) {
                calls++;
                return "[{\"call\":" + calls + "}]";
            }
        };
        ChatToolDispatcher d = dispatcher(Map.of("searchPosts", counter));
        ChatToolDispatcher.TurnContext turn = new ChatToolDispatcher.TurnContext();

        // LinkedHashMap preserves insertion order, so the two nested maps below
        // iterate (and toString) in different orders despite being logically
        // equal — the exact shape that produced distinct keys before the fix.
        Map<String, Object> nestedXY = new LinkedHashMap<>();
        nestedXY.put("x", 1);
        nestedXY.put("y", 2);
        Map<String, Object> firstArgs = new HashMap<>();
        firstArgs.put("filter", nestedXY);

        Map<String, Object> nestedYX = new LinkedHashMap<>();
        nestedYX.put("y", 2);
        nestedYX.put("x", 1);
        Map<String, Object> secondArgs = new HashMap<>();
        secondArgs.put("filter", nestedYX);

        ChatToolDispatcher.ToolResult first =
                d.dispatch("searchPosts", firstArgs, USER_A, "dm", SCOPE_A, turn);
        ChatToolDispatcher.ToolResult second =
                d.dispatch("searchPosts", secondArgs, USER_A, "dm", SCOPE_A, turn);

        assertInstanceOf(ChatToolDispatcher.ToolResult.Success.class, first);
        assertInstanceOf(ChatToolDispatcher.ToolResult.Success.class, second);
        assertEquals(
                ((ChatToolDispatcher.ToolResult.Success) first).content(),
                ((ChatToolDispatcher.ToolResult.Success) second).content(),
                "Logically-equal args differing only in nested-map order must "
                        + "share a cache key (a hit on the second dispatch)");
    }

    // --- Acceptance item 4: scope-filtered search ---

    @Test
    void searchPostsScopeFiltered() {
        ChatToolRegistry.ChatTool scopedSearch =
                (userId, scopeKind, scopeId, args) -> {
                    if (SCOPE_A.equals(scopeId)) {
                        return "[{\"uid\":\"visible-post\",\"title\":\"Visible\"}]";
                    }
                    return "[]";
                };
        ChatToolDispatcher d = dispatcher(Map.of("searchPosts", scopedSearch));

        ChatToolDispatcher.ToolResult inScope =
                d.dispatch("searchPosts", new HashMap<>(), USER_A, "dm", SCOPE_A);

        assertInstanceOf(ChatToolDispatcher.ToolResult.Success.class, inScope);
        assertTrue(((ChatToolDispatcher.ToolResult.Success) inScope)
                .content().contains("visible-post"));

        ChatToolDispatcher.ToolResult outOfScope =
                d.dispatch("searchPosts", new HashMap<>(), USER_A, "dm", SCOPE_B);

        assertInstanceOf(ChatToolDispatcher.ToolResult.Success.class, outOfScope);
        assertEquals("[]",
                ((ChatToolDispatcher.ToolResult.Success) outOfScope).content());
    }

    // --- Acceptance item 5: null for invisible UID ---

    @Test
    void getPostReturnsNullForInvisibleUid() {
        ChatToolRegistry.ChatTool scopedGet =
                (userId, scopeKind, scopeId, args) -> {
                    if (SCOPE_A.equals(scopeId)) {
                        return "{\"uid\":\"test-uid\",\"title\":\"Visible\"}";
                    }
                    return "null";
                };
        ChatToolDispatcher d = dispatcher(Map.of("getPost", scopedGet));

        Map<String, Object> args = new HashMap<>();
        args.put("uid", "test-uid");

        ChatToolDispatcher.ToolResult visible =
                d.dispatch("getPost", args, USER_A, "dm", SCOPE_A);

        assertInstanceOf(ChatToolDispatcher.ToolResult.Success.class, visible);
        assertTrue(((ChatToolDispatcher.ToolResult.Success) visible)
                .content().contains("test-uid"));

        ChatToolDispatcher.ToolResult invisible =
                d.dispatch("getPost", new HashMap<>(args), USER_A, "dm", SCOPE_B);

        assertInstanceOf(ChatToolDispatcher.ToolResult.Success.class, invisible);
        assertEquals("null",
                ((ChatToolDispatcher.ToolResult.Success) invisible).content());
    }

    // --- Acceptance item 6: recall memory scope isolation ---

    @Test
    void recallMemoryNeverCrossesScope() {
        ChatToolRegistry.ChatTool scopedRecall =
                (userId, scopeKind, scopeId, args) -> {
                    if (SCOPE_A.equals(scopeId)) {
                        return "[{\"summary\":\"scope-a-memory\"}]";
                    }
                    return "[]";
                };
        ChatToolDispatcher d = dispatcher(Map.of("recallMemory", scopedRecall));

        Map<String, Object> argsA = new HashMap<>();
        argsA.put("keywords", List.of("test"));

        ChatToolDispatcher.ToolResult inScope =
                d.dispatch("recallMemory", argsA, USER_A, "dm", SCOPE_A);

        assertInstanceOf(ChatToolDispatcher.ToolResult.Success.class, inScope);
        assertTrue(((ChatToolDispatcher.ToolResult.Success) inScope)
                .content().contains("scope-a-memory"));

        Map<String, Object> argsB = new HashMap<>();
        argsB.put("keywords", List.of("test"));

        ChatToolDispatcher.ToolResult outOfScope =
                d.dispatch("recallMemory", argsB, USER_A, "dm", SCOPE_B);

        assertInstanceOf(ChatToolDispatcher.ToolResult.Success.class, outOfScope);
        assertEquals("[]",
                ((ChatToolDispatcher.ToolResult.Success) outOfScope).content());
    }

    // --- Acceptance item 7: list saves user isolation ---

    @Test
    void listSavesNeverReturnsOtherUserRows() {
        ChatToolRegistry.ChatTool userFilteredSaves =
                (userId, scopeKind, scopeId, args) -> {
                    if (USER_A.equals(userId)) {
                        return "[{\"uid\":\"user-a-save\",\"snapshot_title\":\"My Save\"}]";
                    }
                    return "[]";
                };
        ChatToolDispatcher d = dispatcher(Map.of("listSaves", userFilteredSaves));

        ChatToolDispatcher.ToolResult userASaves =
                d.dispatch("listSaves", new HashMap<>(), USER_A, "dm", SCOPE_A);

        assertInstanceOf(ChatToolDispatcher.ToolResult.Success.class, userASaves);
        assertTrue(((ChatToolDispatcher.ToolResult.Success) userASaves)
                .content().contains("user-a-save"));

        ChatToolDispatcher.ToolResult userBSaves =
                d.dispatch("listSaves", new HashMap<>(), USER_B, "dm", SCOPE_A);

        assertInstanceOf(ChatToolDispatcher.ToolResult.Success.class, userBSaves);
        assertEquals("[]",
                ((ChatToolDispatcher.ToolResult.Success) userBSaves).content());
    }

    // --- M1-131: type/parse failures become ValidationError, not an
    // escaped exception that surfaces as the generic chat-unavailable error ---

    @Test
    void typeMismatchBecomesValidationError() {
        ChatToolDispatcher d = dispatcher(Map.of("searchPosts",
                (u, sk, si, a) -> { throw new ClassCastException("wrong runtime type"); }));

        ChatToolDispatcher.ToolResult result =
                d.dispatch("searchPosts", new HashMap<>(), USER_A, "dm", SCOPE_A);

        assertInstanceOf(ChatToolDispatcher.ToolResult.ValidationError.class, result);
    }

    @Test
    void durationParseFailureBecomesValidationError() {
        // The real source: SearchPostsTool does Duration.parse((String) window).
        ChatToolDispatcher d = dispatcher(Map.of("searchPosts",
                (u, sk, si, a) -> { Duration.parse("not-a-duration"); return "[]"; }));

        ChatToolDispatcher.ToolResult result =
                d.dispatch("searchPosts", new HashMap<>(), USER_A, "dm", SCOPE_A);

        assertInstanceOf(ChatToolDispatcher.ToolResult.ValidationError.class, result);
    }

    @Test
    void numberFormatFailureBecomesValidationError() {
        ChatToolDispatcher d = dispatcher(Map.of("searchPosts",
                (u, sk, si, a) -> { Integer.parseInt("not-a-number"); return "[]"; }));

        ChatToolDispatcher.ToolResult result =
                d.dispatch("searchPosts", new HashMap<>(), USER_A, "dm", SCOPE_A);

        assertInstanceOf(ChatToolDispatcher.ToolResult.ValidationError.class, result);
    }

    @Test
    void nonNumericLimitBecomesValidationError() {
        // clampLimit's `(Number) args.get("limit")` cast runs before the tool;
        // a non-numeric limit must surface as ValidationError, not escape.
        ChatToolDispatcher d = dispatcher(Map.of());

        Map<String, Object> args = new HashMap<>();
        args.put("limit", "ten");

        ChatToolDispatcher.ToolResult result =
                d.dispatch("searchPosts", args, USER_A, "dm", SCOPE_A);

        assertInstanceOf(ChatToolDispatcher.ToolResult.ValidationError.class, result);
    }

    // --- M1-131 (round 2, redteam DOS finding): length/size caps must be
    // enforced at the dispatch boundary for the nested value shapes the Jackson
    // parser can now produce, not incidentally via a downstream cast failure ---

    @Test
    void nestedMapOversizedStringBecomesValidationError() {
        // inputMaxLength = 10; an oversized String buried inside a nested Map
        // value must be rejected by the dispatcher before the tool runs.
        ChatToolDispatcher d = dispatcher(Map.of(), 10, 200);

        Map<String, Object> args = new HashMap<>();
        args.put("filter", new HashMap<>(Map.of("k", "a".repeat(11))));

        ChatToolDispatcher.ToolResult result =
                d.dispatch("searchPosts", args, USER_A, "dm", SCOPE_A);

        assertInstanceOf(ChatToolDispatcher.ToolResult.ValidationError.class, result);
        assertTrue(((ChatToolDispatcher.ToolResult.ValidationError) result)
                .reason().contains("maximum length"));
    }

    @Test
    void deeplyNestedMapOversizedStringBecomesValidationError() {
        ChatToolDispatcher d = dispatcher(Map.of(), 10, 200);

        Map<String, Object> args = new HashMap<>();
        args.put("a", new HashMap<>(Map.of(
                "b", new HashMap<>(Map.of("c", "a".repeat(11))))));

        ChatToolDispatcher.ToolResult result =
                d.dispatch("searchPosts", args, USER_A, "dm", SCOPE_A);

        assertInstanceOf(ChatToolDispatcher.ToolResult.ValidationError.class, result);
    }

    @Test
    void nestedMapWithinBoundsReachesTool() {
        // Recursive validation must not reject a nested map whose values are
        // within the caps — it should pass through to the tool.
        ChatToolDispatcher d = dispatcher(
                Map.of("searchPosts", (u, sk, si, a) -> "[{\"ok\":1}]"), 10, 200);

        Map<String, Object> args = new HashMap<>();
        args.put("filter", new HashMap<>(Map.of("k", "short")));

        ChatToolDispatcher.ToolResult result =
                d.dispatch("searchPosts", args, USER_A, "dm", SCOPE_A);

        assertInstanceOf(ChatToolDispatcher.ToolResult.Success.class, result);
    }
}
