package app.zcat.infochat.provider.chat;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Covers the Jackson tool-arg parse, the balanced-brace TOOL_CALL strip,
// and the parse→dispatch array path that previously threw ClassCastException
// because parseToolArgs stored array values as a raw String.
class ChatAgentToolArgsTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SCOPE = UUID.randomUUID();

    // --- parseToolArgs: typed values (acceptance item 1) ---

    @Test
    void parseToolArgsBuildsStringListForArrayArg() {
        Map<String, Object> args = ChatAgent.parseToolArgs("{\"tags\": [\"bitcoin\", \"zcash\"]}");

        Object tags = args.get("tags");
        assertInstanceOf(List.class, tags, "array value must become a List, not a raw String");
        assertEquals(List.of("bitcoin", "zcash"), tags);
    }

    @Test
    void parseToolArgsKeepsLimitAsNumber() {
        Map<String, Object> args = ChatAgent.parseToolArgs("{\"limit\": 10}");

        Object limit = args.get("limit");
        assertInstanceOf(Number.class, limit, "numeric value must be a Number for the (Number) cast");
        assertEquals(10, ((Number) limit).intValue());
    }

    @Test
    void parseToolArgsHandlesNestedObject() {
        // The reluctant `\{.*?\}` truncated at the first inner '}'; Jackson
        // round-trips the whole nested object.
        Map<String, Object> args = ChatAgent.parseToolArgs("{\"a\": {\"b\": \"c\"}}");

        Object nested = args.get("a");
        assertInstanceOf(Map.class, nested);
        assertEquals("c", ((Map<?, ?>) nested).get("b"));
    }

    @Test
    void parseToolArgsHandlesEscapedQuotes() {
        // A value containing an escaped quote, and one containing an escaped
        // backslash followed by a quote — the hand-rolled inQuote tracker
        // mis-flipped on `\\\"`.
        Map<String, Object> args = ChatAgent.parseToolArgs("{\"k\": \"a\\\"b\"}");
        assertEquals("a\"b", args.get("k"));

        Map<String, Object> args2 = ChatAgent.parseToolArgs("{\"k\": \"a\\\\\", \"j\": \"x\"}");
        assertEquals("a\\", args2.get("k"));
        assertEquals("x", args2.get("j"));
    }

    @Test
    void parseToolArgsRejectsMalformedJson() {
        // Documented failure mode: malformed JSON yields an empty map (no
        // throw), so the dispatch loop continues rather than aborting the turn.
        assertTrue(ChatAgent.parseToolArgs("{not valid json").isEmpty());
        assertTrue(ChatAgent.parseToolArgs("[1, 2, 3]").isEmpty(),
                "a non-object top-level value yields an empty map");
    }

    @Test
    void parseToolArgsHandlesEmptyAndBlank() {
        assertTrue(ChatAgent.parseToolArgs("{}").isEmpty());
        assertTrue(ChatAgent.parseToolArgs("").isEmpty());
        assertTrue(ChatAgent.parseToolArgs("   ").isEmpty());
    }

    // --- stripToolCalls: TOOL-LEAK (acceptance item 4) ---

    @Test
    void stripToolCallsRemovesMalformedMultiLineFragment() {
        // The previous two-pass strip (reluctant pattern + single-line
        // `TOOL_CALL:.*`) left the trailing lines of an unbalanced multi-line
        // call visible to the user.
        String text = "Here is the answer.\nTOOL_CALL: searchPosts {\n  \"tags\": [\"x\"]\n";

        String stripped = ChatAgent.stripToolCalls(text);

        assertFalse(stripped.contains("TOOL_CALL:"), "no protocol marker may leak");
        assertFalse(stripped.contains("\"tags\""), "the trailing JSON line must not leak");
        assertTrue(stripped.contains("Here is the answer."), "prose before the call is preserved");
    }

    @Test
    void stripToolCallsRemovesBalancedCallButKeepsSurroundingText() {
        String text = "Before. TOOL_CALL: getPost {\"uid\": \"abc\"} After.";

        String stripped = ChatAgent.stripToolCalls(text);

        assertFalse(stripped.contains("TOOL_CALL:"));
        assertTrue(stripped.contains("Before."));
        assertTrue(stripped.contains("After."),
                "text after a balanced call is preserved");
    }

    @Test
    void stripToolCallsRemovesBalancedNestedMultiLineCall() {
        String text = "Done.\nTOOL_CALL: searchPosts {\n  \"filter\": {\"tags\": [\"x\"]}\n}\n";

        String stripped = ChatAgent.stripToolCalls(text);

        assertFalse(stripped.contains("TOOL_CALL:"));
        assertFalse(stripped.contains("\"filter\""));
        assertTrue(stripped.contains("Done."));
    }

    // --- parse → dispatch array path (acceptance item 2) ---

    @Test
    void recallMemoryWithKeywordsArrayDispatchesWithoutClassCast() {
        ChatToolDispatcher dispatcher = dispatcherWith(
                "recallMemory", ChatAgentToolArgsTest::castKeywords);

        Map<String, Object> args = ChatAgent.parseToolArgs("{\"keywords\": [\"x\", \"y\"]}");
        ChatToolDispatcher.ToolResult result =
                dispatcher.dispatch("recallMemory", args, USER, "dm", SCOPE);

        assertInstanceOf(ChatToolDispatcher.ToolResult.Success.class, result);
        assertEquals("[{\"count\":2}]",
                ((ChatToolDispatcher.ToolResult.Success) result).content());
    }

    @Test
    void searchPostsWithTagsArrayDispatchesWithoutClassCast() {
        ChatToolDispatcher dispatcher = dispatcherWith(
                "searchPosts", ChatAgentToolArgsTest::castTags);

        Map<String, Object> args = ChatAgent.parseToolArgs("{\"tags\": [\"bitcoin\"]}");
        ChatToolDispatcher.ToolResult result =
                dispatcher.dispatch("searchPosts", args, USER, "dm", SCOPE);

        assertInstanceOf(ChatToolDispatcher.ToolResult.Success.class, result);
        assertEquals("[{\"tags\":1}]",
                ((ChatToolDispatcher.ToolResult.Success) result).content());
    }

    @Test
    void listSavesWithTagsArrayDispatchesWithoutClassCast() {
        ChatToolDispatcher dispatcher = dispatcherWith(
                "listSaves", ChatAgentToolArgsTest::castTags);

        Map<String, Object> args = ChatAgent.parseToolArgs("{\"tags\": [\"a\", \"b\", \"c\"]}");
        ChatToolDispatcher.ToolResult result =
                dispatcher.dispatch("listSaves", args, USER, "dm", SCOPE);

        assertInstanceOf(ChatToolDispatcher.ToolResult.Success.class, result);
        assertEquals("[{\"tags\":3}]",
                ((ChatToolDispatcher.ToolResult.Success) result).content());
    }

    // --- helpers ---

    // Mirrors RecallMemoryTool's real (List<String>) keywords cast.
    @SuppressWarnings("unchecked")
    private static String castKeywords(UUID userId, String scopeKind, UUID scopeId,
                                       Map<String, Object> args) {
        List<String> keywords = (List<String>) args.get("keywords");
        return "[{\"count\":" + keywords.size() + "}]";
    }

    // Mirrors SearchPostsTool / ListSavesTool's real (List<String>) tags cast.
    @SuppressWarnings("unchecked")
    private static String castTags(UUID userId, String scopeKind, UUID scopeId,
                                   Map<String, Object> args) {
        List<String> tags = (List<String>) args.get("tags");
        return "[{\"tags\":" + tags.size() + "}]";
    }

    private static ChatToolDispatcher dispatcherWith(
            String name, ChatToolRegistry.ChatTool tool) {
        ChatToolRegistry registry = new ChatToolRegistry();
        Map<String, ChatToolRegistry.ChatTool> tools = new HashMap<>();
        for (String registered : registry.toolNames()) {
            tools.put(registered, (u, sk, si, a) -> "[]");
        }
        tools.put(name, tool);
        return new ChatToolDispatcher(registry, tools, 500, 200, 20);
    }
}
