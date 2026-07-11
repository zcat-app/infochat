package app.zcat.infochat.provider.chat;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatAgentTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SCOPE_ID = UUID.randomUUID();
    private static final String SCOPE_KIND = "dm";

    private InFlightTracker inFlightTracker;
    private StubLlmProvider llmProvider;
    private int promptBuilderCalls;
    private int sanitizerCalls;
    private String sanitizerLastInput;
    private String sanitizerOutput;
    private int translationCalls;
    private String translationLastLanguage;
    private int sessionPersistCalls;
    private final List<String> persistedRoles = new ArrayList<>();
    private int dispatcherCalls;
    private String dispatcherLastToolName;
    private int semanticSearchCalls;
    private String semanticSearchLastQuery;
    private String semanticSearchResult;
    private boolean semanticSearchThrow;
    private int auditCalls;
    private AuditAction lastAuditAction;
    private final List<String> persistedTexts = new ArrayList<>();
    private boolean ceilingGated;
    private TestChatAgent agent;

    @BeforeEach
    void setUp() {
        inFlightTracker = new InFlightTracker();
        llmProvider = new StubLlmProvider();
        promptBuilderCalls = 0;
        sanitizerCalls = 0;
        sanitizerOutput = null;
        translationCalls = 0;
        sessionPersistCalls = 0;
        persistedRoles.clear();
        persistedTexts.clear();
        dispatcherCalls = 0;
        semanticSearchCalls = 0;
        semanticSearchLastQuery = null;
        semanticSearchResult = "[]";
        semanticSearchThrow = false;
        auditCalls = 0;
        lastAuditAction = null;
        ceilingGated = false;

        agent = buildAgent("en");
    }

    @Test
    void orchestrationSequenceIsCorrect() {
        llmProvider.responses.add(new LlmResponse("Hello, how can I help?"));

        ChatAgent.ChatTurnResult result = agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");
        // Persistence is deferred to a post-delivery commit; the router runs
        // it after a successful send. Drive that commit here to assert the
        // turn-persistence behaviour the reorder preserves.
        result.pendingCommit().commit();

        assertEquals(1, promptBuilderCalls, "prompt builder should be called once");
        assertEquals(1, auditCalls, "audit row should be written once before LLM call");
        assertEquals(AuditAction.CHAT_MODE, lastAuditAction);
        assertEquals(1, llmProvider.callCount, "LLM should be called once");
        assertEquals(1, dispatcherCalls,
                "exactly one dispatch: the deterministic semanticSearch pre-fetch "
                        + "(no tool calls in the LLM response)");
        assertEquals(1, semanticSearchCalls,
                "the turn must always dispatch semanticSearch (M1-589)");
        assertEquals(1, sanitizerCalls, "sanitizer should be called once (before persist)");
        assertEquals(2, sessionPersistCalls, "user + assistant turns persisted");
        assertEquals("user", persistedRoles.get(0));
        assertEquals("assistant", persistedRoles.get(1));
        assertEquals(0, translationCalls, "no translation for en scope");
        assertEquals("Hello, how can I help?", result.reply());
    }

    @Test
    void llmUnreachableReturnsFriendlyError() {
        llmProvider.throwOnGenerate = true;

        String reply = agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");

        assertEquals(BundleKeys.ERROR_CHAT_UNAVAILABLE, reply);
        assertEquals(0, sessionPersistCalls, "no session persistence on LLM failure");
        assertEquals(1, dispatcherCalls,
                "only the deterministic semanticSearch pre-fetch (which runs before "
                        + "the LLM call) — no loop tool dispatch on LLM failure");
        assertFalse(inFlightTracker.isInFlight(USER_ID, SCOPE_KIND, SCOPE_ID),
                "in-flight slot must be released");
    }

    @Test
    void cancelledRequestWithCompletedResultIsDiscarded() {
        // Race a completed result against /stop: the worker finishes (the LLM
        // returns a normal answer) but /stop marked the request cancelled
        // before the delivery boundary — a missed interrupt. The result must
        // be discarded (no content reply), not delivered as if /stop never
        // happened.
        llmProvider.beforeGenerate = () ->
                inFlightTracker.getCancellationHandle(USER_ID, SCOPE_KIND, SCOPE_ID)
                        .ifPresent(InFlightTracker.CancellationHandle::markCancelled);
        llmProvider.responses.add(new LlmResponse("Here is your answer."));

        String reply = agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");

        assertNull(reply,
                "a cancelled request must yield no content reply even when the "
                        + "worker completed (missed interrupt)");
        assertFalse(inFlightTracker.isInFlight(USER_ID, SCOPE_KIND, SCOPE_ID),
                "the in-flight slot must be released");
    }

    @Test
    void cancelledChatRequestDoesNotDoubleReply() {
        // A landed cancellation interrupt surfaces as an exception out of the
        // LLM call. Because /stop already marked the request (and its handler
        // replied "Cancelled..."), the chat path must return no content — not
        // the "unavailable" notice — so the user sees exactly one reply.
        llmProvider.beforeGenerate = () ->
                inFlightTracker.getCancellationHandle(USER_ID, SCOPE_KIND, SCOPE_ID)
                        .ifPresent(InFlightTracker.CancellationHandle::markCancelled);
        llmProvider.throwOnGenerate = true;

        String reply = agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");

        assertNull(reply,
                "a cancelled chat request must yield no second reply (the /stop "
                        + "handler already replied)");
        assertFalse(inFlightTracker.isInFlight(USER_ID, SCOPE_KIND, SCOPE_ID),
                "the in-flight slot must be released");
    }

    @Test
    void outputPassesThroughSanitizer() {
        sanitizerOutput = "[redacted command]";
        llmProvider.responses.add(new LlmResponse("Try /ban user123"));

        String reply = agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "help me");

        assertEquals(1, sanitizerCalls);
        assertEquals("Try /ban user123", sanitizerLastInput);
        assertEquals("[redacted command]", reply);
    }

    @Test
    void translationRunsWhenScopeLanguageIsNonEn() {
        agent = buildAgent("cs");
        llmProvider.responses.add(new LlmResponse("Hello"));

        String reply = agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "ahoj");

        assertEquals(1, translationCalls);
        assertEquals("cs", translationLastLanguage);
        assertEquals("translated:Hello", reply);
    }

    @Test
    void translationSkippedWhenScopeLanguageIsEn() {
        llmProvider.responses.add(new LlmResponse("Hello"));

        agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");

        assertEquals(0, translationCalls);
    }

    @Test
    void inFlightRejectionReturnsBundleError() {
        inFlightTracker.tryAcquire(USER_ID, SCOPE_KIND, SCOPE_ID);

        String reply = agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");

        assertEquals(BundleKeys.ERROR_CHAT_IN_FLIGHT, reply);
        assertEquals(0, llmProvider.callCount);
    }

    @Test
    void toolCallDispatchesAndLoops() {
        llmProvider.responses.add(
                new LlmResponse("TOOL_CALL: searchPosts {\"query\": \"bitcoin\", \"limit\": 5}"));
        llmProvider.responses.add(
                new LlmResponse("I found 3 posts about bitcoin."));

        String reply = agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "find bitcoin posts");

        assertEquals(2, dispatcherCalls,
                "the deterministic semanticSearch pre-fetch plus the model's searchPosts call");
        assertEquals("searchPosts", dispatcherLastToolName);
        assertEquals(2, llmProvider.callCount);
        assertEquals("I found 3 posts about bitcoin.", reply);
    }

    @Test
    void toolResultsWrappedInDelimiters() {
        // First LLM call returns a tool call; second returns the final answer
        llmProvider.responses.add(
                new LlmResponse("TOOL_CALL: searchPosts {\"query\": \"test\"}"));
        llmProvider.responses.add(
                new LlmResponse("Here are the results."));

        agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "search for test");

        // The second LLM call's userPrompt should contain UNTRUSTED_CONTENT
        // delimiters wrapping the tool result
        assertTrue(llmProvider.lastUserPrompt.contains("<<<UNTRUSTED_CONTENT id=\""),
                "Tool result must be wrapped in UNTRUSTED_CONTENT open delimiter");
        assertTrue(llmProvider.lastUserPrompt.contains("<<<END id=\""),
                "Tool result must be wrapped in UNTRUSTED_CONTENT close delimiter");
    }

    // --- M1-589: digest-first semantic retrieval, dispatched
    // deterministically on EVERY turn (the D28 pre-fetch pattern) — never
    // left to the model to choose. ---

    @Test
    void everyTurnDispatchesSemanticSearchWithTheUserMessage() {
        llmProvider.responses.add(new LlmResponse("Plain answer, no tool calls."));

        agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "what happened with zcash?");

        assertEquals(1, semanticSearchCalls,
                "semanticSearch must be dispatched exactly once per turn, "
                        + "deterministically — not left to the model");
        assertEquals("what happened with zcash?", semanticSearchLastQuery,
                "the deterministic pre-fetch must embed the user's message as the query");
        assertEquals("semanticSearch", dispatcherLastToolName);
    }

    @Test
    void semanticResultIsFoldedIntoPromptInsideUntrustedWrapper() {
        semanticSearchResult =
                "[{\"uid\":\"sem-post-1\",\"title\":\"T\",\"url\":\"https://e.x/1\",\"similarity\":0.9}]";
        llmProvider.responses.add(new LlmResponse("Grounded answer."));

        agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "zcash news?");

        String up = llmProvider.lastUserPrompt;
        int open = up.indexOf("<<<UNTRUSTED_CONTENT id=\"");
        int hit = up.indexOf("sem-post-1");
        int close = up.indexOf("<<<END id=\"");
        assertTrue(hit >= 0, "the retrieved posts must be folded into the prompt");
        assertTrue(open >= 0 && open < hit && hit < close,
                "retrieved posts are attacker-influenced content and must sit inside "
                        + "the UNTRUSTED_CONTENT wrapper");
    }

    @Test
    void emptySemanticResultFoldsNoRetrievalBlock() {
        // Default stub result "[]" = nothing under the distance threshold.
        llmProvider.responses.add(new LlmResponse("General-knowledge answer."));

        String reply = agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "python vs rust?");

        assertEquals("General-knowledge answer.", reply);
        assertFalse(llmProvider.lastUserPrompt.contains("subscribed feed"),
                "an empty retrieval must fold nothing into the prompt "
                        + "(the general-knowledge path)");
    }

    @Test
    void semanticSearchFailureDegradesToGeneralKnowledgeTurn() {
        semanticSearchThrow = true;
        llmProvider.responses.add(new LlmResponse("Still a fine answer."));

        String reply = agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "hello there");

        assertEquals("Still a fine answer.", reply,
                "an embedding/retrieval failure must degrade to a normal "
                        + "general-knowledge turn, not abort it");
        assertEquals(1, llmProvider.callCount, "the LLM turn still runs");
    }

    // redteam 2026-07-11 (low DOS) remediation pin: the deterministic
    // pre-fetch and the model's tool loop share ONE TurnContext, so an
    // identical model-initiated semanticSearch call is a cache hit — the
    // tool executes exactly once per turn (no duplicate embed + probe) and
    // the per-turn call budget covers pre-fetch + loop. Uses a REAL
    // dispatcher (the counting stub bypasses the cache logic) with a
    // counting semanticSearch ChatTool.
    @Test
    void identicalModelSemanticCallServedFromSharedPerTurnCache() {
        int[] executions = {0};
        Map<String, ChatToolRegistry.ChatTool> tools = new HashMap<>();
        for (String name : new ChatToolRegistry().toolNames()) {
            tools.put(name, (u, sk, si, a) -> "[]");
        }
        tools.put("semanticSearch", (u, sk, si, a) -> {
            executions[0]++;
            return "[{\"uid\":\"sem-1\",\"title\":\"T\",\"url\":\"https://e.x/1\",\"similarity\":0.9}]";
        });
        ChatToolDispatcher realDispatcher = new ChatToolDispatcher(
                new ChatToolRegistry(), tools, 500, 200, 20);
        agent = buildAgent("en", realDispatcher);

        llmProvider.responses.add(
                new LlmResponse("TOOL_CALL: semanticSearch {\"query\": \"hi\"}"));
        llmProvider.responses.add(new LlmResponse("done"));

        String reply = agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");

        assertEquals("done", reply);
        assertEquals(1, executions[0],
                "the model's identical semanticSearch call must be served from the "
                        + "per-turn cache shared with the deterministic pre-fetch — "
                        + "one execution per turn, not two");
    }

    @Test
    void longMessageIsTruncatedToTheSemanticQueryCap() {
        llmProvider.responses.add(new LlmResponse("ok"));
        String longMessage = "z".repeat(ChatAgent.SEMANTIC_QUERY_MAX_CHARS + 100);

        agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, longMessage);

        assertEquals(1, semanticSearchCalls,
                "retrieval must still run for messages over the tool input cap");
        assertEquals(ChatAgent.SEMANTIC_QUERY_MAX_CHARS, semanticSearchLastQuery.length(),
                "the query must be truncated to the cap the dispatcher would "
                        + "otherwise reject");
    }

    @Test
    void chatModeIntentIsAuditLogged() {
        llmProvider.responses.add(new LlmResponse("Hello"));

        agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");

        assertEquals(1, auditCalls, "exactly one audit row per chat-mode request");
        assertEquals(AuditAction.CHAT_MODE, lastAuditAction);
    }

    @Test
    void finalResponseStripsToolCallPatterns() {
        // Simulate hitting the iteration cap: the LLM keeps returning tool
        // calls on every iteration, and the final forced-response still
        // contains a TOOL_CALL pattern that must be stripped
        for (int i = 0; i < ChatAgent.MAX_TOOL_ITERATIONS; i++) {
            llmProvider.responses.add(
                    new LlmResponse("TOOL_CALL: searchPosts {\"query\": \"x\"}"));
        }
        // Final response after cap still contains TOOL_CALL
        llmProvider.responses.add(
                new LlmResponse("Here is the answer. TOOL_CALL: getPost {\"uid\": \"abc\"}"));

        String reply = agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "test");

        assertFalse(reply.contains("TOOL_CALL:"),
                "TOOL_CALL patterns must be stripped from the final response");
        assertTrue(reply.contains("Here is the answer."),
                "Non-tool-call text must be preserved");
    }

    @Test
    void partialToolCallPatternStripped() {
        // Partial TOOL_CALL (no JSON body) must also be stripped
        for (int i = 0; i < ChatAgent.MAX_TOOL_ITERATIONS; i++) {
            llmProvider.responses.add(
                    new LlmResponse("TOOL_CALL: searchPosts {\"query\": \"x\"}"));
        }
        llmProvider.responses.add(
                new LlmResponse("Answer here.\nTOOL_CALL: searchPosts"));

        String reply = agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "test");

        assertFalse(reply.contains("TOOL_CALL:"),
                "Partial TOOL_CALL (no JSON body) must be stripped");
        assertTrue(reply.contains("Answer here."));
    }

    @Test
    void persistsSanitizedOutput() {
        sanitizerOutput = "[redacted command]";
        llmProvider.responses.add(new LlmResponse("Try /ban user123"));

        ChatAgent.ChatTurnResult result = agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "help");
        // Run the deferred post-delivery commit to drive persistence.
        result.pendingCommit().commit();

        // The persisted assistant text should be the sanitized version
        assertEquals(2, persistedTexts.size());
        assertEquals("help", persistedTexts.get(0), "user turn is the raw message");
        assertEquals("[redacted command]", persistedTexts.get(1),
                "assistant turn must be the sanitized output, not the raw LLM text");
    }

    @Test
    void distinctGroupsProduceDistinctSessions() {
        UUID groupA = UUID.randomUUID();
        UUID groupB = UUID.randomUUID();
        llmProvider.responses.add(new LlmResponse("Reply A"));
        llmProvider.responses.add(new LlmResponse("Reply B"));

        ChatAgent.ChatTurnResult resultA = agent.handleTurn(USER_ID, "group", groupA, "hello group A");
        resultA.pendingCommit().commit();
        ChatAgent.ChatTurnResult resultB = agent.handleTurn(USER_ID, "group", groupB, "hello group B");
        resultB.pendingCommit().commit();

        assertEquals("Reply A", resultA.reply());
        assertEquals("Reply B", resultB.reply());
        // 4 persisted turns: user-A, assistant-A, user-B, assistant-B
        assertEquals(4, sessionPersistCalls,
                "distinct scopes must produce independent session persists");
        assertEquals(2, llmProvider.callCount,
                "each scope must get its own LLM call");
        assertTrue(persistedTexts.contains("hello group A"),
                "group A message should be persisted");
        assertTrue(persistedTexts.contains("hello group B"),
                "group B message should be persisted");
    }

    @Test
    void parseToolArgsHandlesSimpleJson() {
        var args = ChatAgent.parseToolArgs("{\"query\": \"test\", \"limit\": 10}");
        assertEquals("test", args.get("query"));
        assertEquals(10, args.get("limit"));
    }

    @Test
    void parseToolArgsHandlesEmptyJson() {
        var args = ChatAgent.parseToolArgs("{}");
        assertTrue(args.isEmpty());
    }

    @Test
    void toolInstructionsMatchSearchPostsParams() {
        String instructions = ChatAgent.TOOL_INSTRUCTIONS;
        assertTrue(instructions.contains("searchPosts"), "must mention searchPosts");
        assertTrue(instructions.contains("\"tags\""), "searchPosts must document tags param");
        assertTrue(instructions.contains("\"window\""), "searchPosts must document window param");
        assertTrue(instructions.contains("\"limit\""), "searchPosts must document limit param");
    }

    @Test
    void toolInstructionsMatchSemanticSearchParams() {
        String instructions = ChatAgent.TOOL_INSTRUCTIONS;
        assertTrue(instructions.contains("semanticSearch"), "must mention semanticSearch");
        int idx = instructions.indexOf("semanticSearch");
        int lineEnd = instructions.indexOf("\n", idx);
        String line = instructions.substring(idx,
                lineEnd > 0 ? lineEnd : instructions.length());
        assertTrue(line.contains("\"query\""), "semanticSearch must document the query param");
    }

    @Test
    void toolInstructionsMatchRecallMemoryParams() {
        String instructions = ChatAgent.TOOL_INSTRUCTIONS;
        assertTrue(instructions.contains("recallMemory"), "must mention recallMemory");
        assertTrue(instructions.contains("\"keywords\""), "recallMemory must document keywords param");
        assertFalse(instructions.contains("recallMemory") && instructions.contains("\"query\"")
                && instructions.indexOf("\"query\"") > instructions.indexOf("recallMemory")
                && instructions.indexOf("\"query\"") < instructions.indexOf("recallMemory") + 80,
                "recallMemory must not use the wrong param name 'query'");
    }

    @Test
    void toolInstructionsMatchListSavesParams() {
        String instructions = ChatAgent.TOOL_INSTRUCTIONS;
        assertTrue(instructions.contains("listSaves"), "must mention listSaves");
        // listSaves line must contain tags and window
        int listSavesIdx = instructions.indexOf("listSaves");
        int nextToolIdx = instructions.indexOf("\n", listSavesIdx);
        String listSavesLine = instructions.substring(listSavesIdx,
                nextToolIdx > 0 ? nextToolIdx : instructions.length());
        assertTrue(listSavesLine.contains("\"tags\""), "listSaves must document tags param");
        assertTrue(listSavesLine.contains("\"window\""), "listSaves must document window param");
    }

    @Test
    void finalCallOmitsToolInstructions() {
        // Fill MAX_TOOL_ITERATIONS with tool calls to hit the cap
        for (int i = 0; i < ChatAgent.MAX_TOOL_ITERATIONS; i++) {
            llmProvider.responses.add(
                    new LlmResponse("TOOL_CALL: getPost {\"uid\": \"abc\"}"));
        }
        // Final response after cap
        llmProvider.responses.add(new LlmResponse("Here is the summary."));

        agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "summarize");

        // The last LLM call is the final call (iteration cap + 1)
        assertEquals(ChatAgent.MAX_TOOL_ITERATIONS + 1, llmProvider.callCount);
        assertFalse(llmProvider.lastSystemPrompt.contains("TOOL_CALL:"),
                "final call system prompt must not contain tool call format instructions");
        assertFalse(llmProvider.lastSystemPrompt.contains("Available tools:"),
                "final call system prompt must not contain tool list");
    }

    @Test
    void turnOnCeilingStuckSessionRejectedWithFailureNoticeUntilCompressSucceeds() {
        ceilingGated = true;
        llmProvider.responses.add(new LlmResponse("recovered reply"));

        ChatAgent.ChatTurnResult gated = agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");

        assertEquals(BundleKeys.ERROR_COMPRESS_FAILED, gated.reply(),
                "a ceiling-stuck session must reject the turn with the failure notice");
        assertNull(gated.pendingCommit(),
                "a ceiling-gated rejection carries no turn to commit");
        assertEquals(0, sessionPersistCalls,
                "the rejected turn must not be silently appended to the session");
        assertEquals(0, llmProvider.callCount,
                "the rejected turn must not reach the LLM");
        assertFalse(inFlightTracker.isInFlight(USER_ID, SCOPE_KIND, SCOPE_ID),
                "in-flight slot must be released after a gated rejection");

        // Once a compress succeeds the gate clears and turns flow again.
        ceilingGated = false;
        ChatAgent.ChatTurnResult recovered = agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "hi again");
        recovered.pendingCommit().commit();

        assertEquals("recovered reply", recovered.reply());
        assertEquals(2, sessionPersistCalls,
                "after the gate clears, turns persist normally");
    }

    // --- factory + test subclass ---

    private TestChatAgent buildAgent(String language) {
        return buildAgent(language, countingStubDispatcher());
    }

    // The counting stub OVERRIDES dispatch outright, so it bypasses the
    // real per-turn cache/budget logic — right for orchestration-sequence
    // assertions, wrong for cache-behaviour ones (those build a real
    // dispatcher via the buildAgent(language, dispatcher) overload).
    private ChatToolDispatcher countingStubDispatcher() {
        Map<String, ChatToolRegistry.ChatTool> noOpTools = new HashMap<>();
        for (String name : new ChatToolRegistry().toolNames()) {
            noOpTools.put(name, (u, sk, si, a) -> "[]");
        }
        return new ChatToolDispatcher(
                new ChatToolRegistry(), noOpTools, 500, 200, 20) {
            @Override
            public ToolResult dispatch(String toolName, Map<String, Object> args,
                                        UUID userId, String scopeKind, UUID scopeId,
                                        TurnContext turn) {
                dispatcherCalls++;
                dispatcherLastToolName = toolName;
                // The deterministic per-turn semanticSearch pre-fetch
                // (M1-589) routes through this dispatcher too; give it
                // its own observable seam. Default result "[]" = the
                // general-knowledge path, so pre-M1-589 tests observe an
                // unchanged prompt.
                if ("semanticSearch".equals(toolName)) {
                    semanticSearchCalls++;
                    semanticSearchLastQuery = (String) args.get("query");
                    if (semanticSearchThrow) {
                        throw new RuntimeException("embedding backend down");
                    }
                    return new ToolResult.Success(semanticSearchResult);
                }
                return new ToolResult.Success("[{\"title\": \"test\"}]");
            }
        };
    }

    private TestChatAgent buildAgent(String language, ChatToolDispatcher dispatcher) {
        ChatPromptBuilder promptBuilder = new ChatPromptBuilder(
                new ChatMemoryPreFetcher() {
                    @Override
                    public List<ChatMemoryPreFetcher.MemoryHit> preFetch(
                            UUID u, String sk, UUID si, String q) {
                        return List.of();
                    }
                },
                new ChatSessionRepository(null),
                16384,
                1024) {
            @Override
            public BuiltPrompt build(UUID u, String sk, UUID si, String msg) {
                promptBuilderCalls++;
                return new BuiltPrompt("system", msg, "marker");
            }
        };

        ChatSessionRepository sessionRepo = new ChatSessionRepository(null) {
            @Override
            public int persistTurn(UUID u, String sk, UUID si,
                                    String role, String content, int tokens) {
                sessionPersistCalls++;
                persistedRoles.add(role);
                persistedTexts.add(content);
                return sessionPersistCalls - 1;
            }
        };

        LlmRouter router = new LlmRouter(
                List.of(new LlmRouter.Entry("test", llmProvider, Set.of("en"))),
                key -> Optional.empty()) {
            @Override
            public LlmProvider forTask(ModelTask task, String lang) {
                return llmProvider;
            }
        };

        // Overrides sanitize() outright, so the no-op collaborators exist only
        // to satisfy the (now mandatory) constructor — they are never invoked.
        LlmOutputSanitizer sanitizer = new LlmOutputSanitizer(
                SanitizerTestDoubles.noOpAuditLogWriter(), SanitizerTestDoubles.noOpDataSource()) {
            @Override
            public String sanitize(String input) {
                sanitizerCalls++;
                sanitizerLastInput = input;
                return sanitizerOutput != null ? sanitizerOutput : input;
            }
        };

        TranslationPipeline pipeline = new TranslationPipeline() {
            @Override
            public String run(String text, String scopeLanguage) {
                translationCalls++;
                translationLastLanguage = scopeLanguage;
                return "translated:" + text;
            }
        };

        BundleLoader bundle = new BundleLoader() {
            @Override public String get(String key) { return key; }
            @Override public String get(String key, String langCode) { return key; }
        };

        // No-op trigger that never fires (threshold unreachable); the
        // ceiling gate is driven by the test's ceilingGated field.
        AutoCompressTrigger noopTrigger = new AutoCompressTrigger(
                Integer.MAX_VALUE, bundle, null, null) {
            @Override
            public java.util.Optional<String> checkAndCompress(
                    UUID u, String sk, UUID si, String sl) {
                return java.util.Optional.empty();
            }

            @Override
            public boolean isCeilingGated(UUID u, String sk, UUID si) {
                return ceilingGated;
            }
        };

        return new TestChatAgent(
                inFlightTracker, promptBuilder, dispatcher, sessionRepo,
                router, sanitizer, pipeline, bundle, noopTrigger, language);
    }

    // Builds a test-scoped InboundContext carrying the scope language the
    // chat turn should localize to — the same seam
    // InboundRouterChatPersistFailureTest uses, replacing the former
    // readScopeLanguage(...) override now that ChatAgent reads the language
    // from the request-scoped InboundContext (M1-333 / D43).
    private static InboundContext inboundContextWith(String language) {
        InboundContext context = new InboundContext();
        context.setEffectiveLanguage(language);
        return context;
    }

    // Subclass that overrides writeAuditRow so no JDBC is needed; the scope
    // language is supplied via the test-scoped InboundContext above.
    class TestChatAgent extends ChatAgent {

        TestChatAgent(InFlightTracker tracker, ChatPromptBuilder builder,
                      ChatToolDispatcher dispatcher, ChatSessionRepository repo,
                      LlmRouter router, LlmOutputSanitizer sanitizer,
                      TranslationPipeline pipeline, BundleLoader bundle,
                      AutoCompressTrigger autoCompressTrigger,
                      String language) {
            super(tracker, builder, dispatcher, repo, router,
                    sanitizer, pipeline, bundle, autoCompressTrigger, null, null,
                    inboundContextWith(language));
        }

        @Override
        void writeAuditRow(UUID userId, String scopeKind, UUID scopeId) {
            auditCalls++;
            lastAuditAction = AuditAction.CHAT_MODE;
        }
    }

    static class StubLlmProvider implements LlmProvider {
        final List<LlmResponse> responses = new ArrayList<>();
        int callCount;
        boolean throwOnGenerate;
        String lastUserPrompt;
        String lastSystemPrompt;
        // Runs at the top of generate() (before the throw path) so a test can
        // model /stop landing mid-generation — e.g. marking the in-flight
        // handle cancelled. Null by default, so existing tests are unaffected.
        Runnable beforeGenerate;

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            if (beforeGenerate != null) {
                beforeGenerate.run();
            }
            if (throwOnGenerate) {
                throw new RuntimeException("LLM unreachable");
            }
            callCount++;
            lastUserPrompt = userPrompt;
            lastSystemPrompt = systemPrompt;
            if (callCount <= responses.size()) {
                return responses.get(callCount - 1);
            }
            return new LlmResponse("default response");
        }
    }
}
