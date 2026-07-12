package app.zcat.infochat.provider.chat;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerRegistry;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The chat-path receiving end of the D21 structured refusal marker
 * (F-live-9): a terminal tool-loop text that IS the marker must never be
 * delivered or persisted — the user gets the deterministic bundle string
 * instead — while text that merely contains the substring mid-prose passes
 * through unchanged (the predicate is anchored, not a substring scan).
 * Since M1-561 the intercept runs prefix-only on the post-stripToolCalls
 * text, so a marker mixed with a tool-call fragment is intercepted too —
 * including one whose closing bracket the strip's unbalanced-fragment
 * drop-through eats, and an unterminated marker with no bracket at all.
 */
class ChatAgentRefusalInterceptTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SCOPE_ID = UUID.randomUUID();
    private static final String SCOPE_KIND = "dm";

    private InFlightTracker inFlightTracker;
    private StubLlmProvider llmProvider;
    private int sessionPersistCalls;
    private TestChatAgent agent;

    @BeforeEach
    void setUp() {
        inFlightTracker = new InFlightTracker();
        llmProvider = new StubLlmProvider();
        sessionPersistCalls = 0;
        agent = buildAgent();
    }

    @Test
    void refusalMarkerReplacedWithBundleStringAndNothingPersisted() {
        llmProvider.response = new LlmResponse("[REFUSAL: because-reasons]");

        ChatAgent.ChatTurnResult result =
                agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "tell me about the advisory");

        assertEquals(BundleKeys.ERROR_CHAT_REFUSED, result.reply(),
                "a refused turn must deliver the deterministic bundle string");
        assertFalse(result.reply().contains("[REFUSAL:"),
                "no part of the refusal marker may reach the delivered text");
        assertFalse(result.reply().contains("because-reasons"),
                "the LLM-authored refusal reason may not reach the delivered text");
        assertNull(result.pendingCommit(),
                "a refused turn is degraded like ERROR_CHAT_UNAVAILABLE — no turn to commit");
        assertEquals(0, sessionPersistCalls,
                "no user/assistant chat_message rows may be persisted for a refused turn");
    }

    @Test
    void midProseRefusalSubstringDeliveredUnchanged() {
        String midProse = "Reports quote a bot reply of [REFUSAL: quoted] in the "
                + "thread, which is expected behaviour.";
        llmProvider.response = new LlmResponse(midProse);

        ChatAgent.ChatTurnResult result =
                agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "what happened in the thread?");

        assertEquals(midProse, result.reply(),
                "a reply merely containing the substring mid-prose is delivered unchanged");
        assertNotNull(result.pendingCommit(),
                "a non-refused turn carries its normal deferred commit");
        result.pendingCommit().commit();
        assertEquals(2, sessionPersistCalls,
                "a non-refused turn persists the user + assistant rows as usual");
    }

    @Test
    void markerWithBracelessFragmentInterceptedAfterStrip() {
        // TOOL_CALL_PATTERN requires an opening brace, so a brace-less
        // fragment does not dispatch — this is ordinary in-loop terminal
        // text. It evades the pre-strip anchor (text no longer ends with
        // ']') but strips down to the bare marker.
        llmProvider.response = new LlmResponse("[REFUSAL: x]\nTOOL_CALL: searchPosts");

        ChatAgent.ChatTurnResult result =
                agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "tell me about the advisory");

        assertEquals(1, llmProvider.generateCalls,
                "a brace-less fragment must not dispatch — the mixed text is in-loop terminal text");
        assertRefusedTurnDegraded(result);
    }

    @Test
    void postCapMarkerThenBalancedFragmentIntercepted() {
        // A balanced fragment dispatches every loop iteration, so this text
        // comes back as the post-cap response, which skips tool-call parsing.
        llmProvider.response = new LlmResponse(
                "[REFUSAL: x]\nTOOL_CALL: getPost {\"uid\": \"u-1\"}");

        ChatAgent.ChatTurnResult result =
                agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "tell me about the advisory");

        assertEquals(ChatAgent.MAX_TOOL_ITERATIONS + 1, llmProvider.generateCalls,
                "the mixed text must be the post-iteration-cap response");
        assertRefusedTurnDegraded(result);
    }

    @Test
    void postCapBalancedFragmentThenMarkerIntercepted() {
        llmProvider.response = new LlmResponse(
                "TOOL_CALL: getPost {\"uid\": \"u-1\"}\n[REFUSAL: x]");

        ChatAgent.ChatTurnResult result =
                agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "tell me about the advisory");

        assertEquals(ChatAgent.MAX_TOOL_ITERATIONS + 1, llmProvider.generateCalls,
                "the mixed text must be the post-iteration-cap response");
        assertRefusedTurnDegraded(result);
    }

    @Test
    void embeddedFragmentInsideMarkerIntercepted() {
        // The redteam repro (M1-561 audit): strip's unbalanced-fragment
        // drop-through eats the marker's closing ']', so only a
        // prefix-anchored check catches the stripped "[REFUSAL: ". No
        // brace → in-loop terminal text.
        llmProvider.response = new LlmResponse("[REFUSAL: TOOL_CALL: foo]");

        ChatAgent.ChatTurnResult result =
                agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "tell me about the advisory");

        assertEquals(1, llmProvider.generateCalls,
                "a brace-less fragment must not dispatch — the mixed text is in-loop terminal text");
        assertRefusedTurnDegraded(result);
    }

    @Test
    void unterminatedMarkerIntercepted() {
        // No closing bracket at all — this evaded the two-sided anchor
        // both before and after M1-559; the prefix-only predicate closes it.
        llmProvider.response = new LlmResponse("[REFUSAL: x");

        ChatAgent.ChatTurnResult result =
                agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "tell me about the advisory");

        assertRefusedTurnDegraded(result);
    }

    private void assertRefusedTurnDegraded(ChatAgent.ChatTurnResult result) {
        assertEquals(BundleKeys.ERROR_CHAT_REFUSED, result.reply(),
                "a refused turn must deliver the deterministic bundle string");
        assertFalse(result.reply().contains("[REFUSAL:"),
                "no part of the refusal marker may reach the delivered text");
        assertNull(result.pendingCommit(),
                "a refused turn is degraded like ERROR_CHAT_UNAVAILABLE — no turn to commit");
        assertEquals(0, sessionPersistCalls,
                "no user/assistant chat_message rows may be persisted for a refused turn");
    }

    // --- factory + test subclass (mirrors ChatAgentTest's harness) ---

    private TestChatAgent buildAgent() {
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
                return new BuiltPrompt("system", msg, "marker");
            }
        };

        Map<String, ChatToolRegistry.ChatTool> noOpTools = new HashMap<>();
        for (String name : new ChatToolRegistry().toolNames()) {
            noOpTools.put(name, (u, sk, si, a) -> "[]");
        }
        ChatToolDispatcher dispatcher = new ChatToolDispatcher(
                new ChatToolRegistry(), noOpTools, 500, 200, 20);

        ChatSessionRepository sessionRepo = new ChatSessionRepository(null) {
            @Override
            public int persistTurn(UUID u, String sk, UUID si,
                                    String role, String content, int tokens) {
                sessionPersistCalls++;
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

        // Pass-through sanitize: the no-op collaborators exist only to
        // satisfy the constructor — they are never invoked.
        LlmOutputSanitizer sanitizer = new LlmOutputSanitizer(
                SanitizerTestDoubles.noOpAuditLogWriter(), SanitizerTestDoubles.noOpDataSource()) {
            @Override
            public String sanitize(String input) {
                return input;
            }
        };

        TranslationPipeline pipeline = new TranslationPipeline() {
            @Override
            public String run(String text, String scopeLanguage) {
                return text;
            }
        };

        BundleLoader bundle = new BundleLoader() {
            @Override public String get(String key) { return key; }
            @Override public String get(String key, String langCode) { return key; }
        };

        AutoCompressTrigger noopTrigger = new AutoCompressTrigger(
                Integer.MAX_VALUE, bundle, null, null) {
            @Override
            public Optional<String> checkAndCompress(
                    UUID u, String sk, UUID si, String sl) {
                return Optional.empty();
            }
        };

        return new TestChatAgent(
                inFlightTracker, promptBuilder, dispatcher, sessionRepo,
                router, sanitizer, pipeline, bundle, noopTrigger);
    }

    private static InboundContext inboundContextEn() {
        InboundContext context = new InboundContext();
        context.setEffectiveLanguage("en");
        return context;
    }

    // Inert seam-constructed breaker registry (fixed clock, empty config →
    // no endpoint → never open): satisfies the M1-606 constructor param
    // without touching the refusal-intercept behaviour under test.
    private static LlmCircuitBreakerRegistry closedBreakerRegistry() {
        return new LlmCircuitBreakerRegistry(3, 30_000,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), key -> Optional.empty());
    }

    // Subclass that overrides writeAuditRow so no JDBC is needed.
    static class TestChatAgent extends ChatAgent {

        TestChatAgent(InFlightTracker tracker, ChatPromptBuilder builder,
                      ChatToolDispatcher dispatcher, ChatSessionRepository repo,
                      LlmRouter router, LlmOutputSanitizer sanitizer,
                      TranslationPipeline pipeline, BundleLoader bundle,
                      AutoCompressTrigger autoCompressTrigger) {
            super(tracker, builder, dispatcher, repo, router,
                    sanitizer, pipeline, bundle, autoCompressTrigger, null, null,
                    inboundContextEn(), closedBreakerRegistry());
        }

        @Override
        void writeAuditRow(UUID userId, String scopeKind, UUID scopeId) {
            // no-op: the audit path is exercised by ChatAgentTest
        }
    }

    static class StubLlmProvider implements LlmProvider {
        LlmResponse response = new LlmResponse("default response");
        int generateCalls;

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            generateCalls++;
            return response;
        }
    }
}
