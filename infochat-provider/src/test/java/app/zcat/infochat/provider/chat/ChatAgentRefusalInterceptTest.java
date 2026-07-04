package app.zcat.infochat.provider.chat;

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

    // Subclass that overrides writeAuditRow so no JDBC is needed.
    static class TestChatAgent extends ChatAgent {

        TestChatAgent(InFlightTracker tracker, ChatPromptBuilder builder,
                      ChatToolDispatcher dispatcher, ChatSessionRepository repo,
                      LlmRouter router, LlmOutputSanitizer sanitizer,
                      TranslationPipeline pipeline, BundleLoader bundle,
                      AutoCompressTrigger autoCompressTrigger) {
            super(tracker, builder, dispatcher, repo, router,
                    sanitizer, pipeline, bundle, autoCompressTrigger, null, null,
                    inboundContextEn());
        }

        @Override
        void writeAuditRow(UUID userId, String scopeKind, UUID scopeId) {
            // no-op: the audit path is exercised by ChatAgentTest
        }
    }

    static class StubLlmProvider implements LlmProvider {
        LlmResponse response = new LlmResponse("default response");

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            return response;
        }
    }
}
