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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the retrieval-provenance notice on {@link ChatAgent.ChatTurnResult}
 * (M1-617, D58): grounded turns carry the DISTINCT turn-wide feed-post
 * count (pre-fetch plus model-initiated post-corpus tool calls), empty
 * retrieval carries the general-knowledge signal — including the
 * breaker-open pre-fetch skip, which was previously silent — and every
 * degrade/rejection path carries {@code null}. The rig mirrors
 * {@link ChatAgentTest}: stub dispatcher/LLM/bundle, no JDBC, audit
 * overridden. The bundle stub returns a template with a {0} token for the
 * grounded key so the MessageFormat count interpolation is observable, and
 * records the language it resolved — the notice must take the bundle path
 * in the scope language, never the translator (D43 two-path rule).
 */
class ChatAgentProvenanceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SCOPE_ID = UUID.randomUUID();
    private static final String SCOPE_KIND = "dm";

    private static final String UID_A =
            "[{\"uid\":\"post-a\",\"title\":\"A\",\"url\":\"https://e.x/a\",\"similarity\":0.9}]";
    private static final String UID_A_AND_B =
            "[{\"uid\":\"post-a\",\"title\":\"A\",\"url\":\"https://e.x/a\",\"similarity\":0.9},"
                    + "{\"uid\":\"post-b\",\"title\":\"B\",\"url\":\"https://e.x/b\",\"similarity\":null}]";

    private InFlightTracker inFlightTracker;
    private StubLlmProvider llmProvider;
    private String semanticSearchResult;
    private final Map<String, String> loopToolResults = new HashMap<>();
    private String bundleLastLanguage;
    private boolean ceilingGated;
    private boolean chatBreakerOpen;
    private TestChatAgent agent;

    @BeforeEach
    void setUp() {
        inFlightTracker = new InFlightTracker();
        llmProvider = new StubLlmProvider();
        semanticSearchResult = "[]";
        loopToolResults.clear();
        bundleLastLanguage = null;
        ceilingGated = false;
        chatBreakerOpen = false;

        agent = buildAgent("en");
    }

    @Test
    void groundedNoticeCarriesDistinctPostCountFromPreFetch() {
        semanticSearchResult = UID_A_AND_B;
        llmProvider.responses.add(new LlmResponse("Grounded answer."));

        ChatAgent.ChatTurnResult result = agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");

        assertEquals("Grounded answer.", result.reply());
        assertEquals("grounded(2)", result.provenanceNotice(),
                "a non-empty pre-fetch must yield the grounded notice with the "
                        + "distinct post count");
    }

    @Test
    void emptyRetrievalCarriesGeneralKnowledgeNotice() {
        // Default pre-fetch result "[]" — nothing under the threshold.
        llmProvider.responses.add(new LlmResponse("General-knowledge answer."));

        ChatAgent.ChatTurnResult result = agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");

        assertEquals("General-knowledge answer.", result.reply(),
                "the reply itself is untouched — the notice is a separate component");
        assertEquals("general-knowledge", result.provenanceNotice(),
                "an empty retrieval turn must carry the general-knowledge signal "
                        + "instead of staying silent");
    }

    @Test
    void breakerOpenPreFetchSkipStillCarriesGeneralKnowledgeNotice() {
        chatBreakerOpen = true;
        llmProvider.responses.add(new LlmResponse("Answer despite outage."));

        ChatAgent.ChatTurnResult result = agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");

        assertEquals("general-knowledge", result.provenanceNotice(),
                "the breaker-open pre-fetch skip (M1-606) lands on the same "
                        + "general-knowledge signal — the wording claims non-grounding, "
                        + "not a failed search");
    }

    @Test
    void modelInitiatedPostCorpusCallGroundsTheTurnWideUnion() {
        // Pre-fetch is empty, but the model calls searchPosts and gets two
        // posts back: the union over the WHOLE turn decides grounding.
        loopToolResults.put("searchPosts", UID_A_AND_B);
        llmProvider.responses.add(
                new LlmResponse("TOOL_CALL: searchPosts {\"tags\": [\"security\"]}"));
        llmProvider.responses.add(new LlmResponse("Found them."));

        ChatAgent.ChatTurnResult result =
                agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "any tenda news?");

        assertEquals("grounded(2)", result.provenanceNotice(),
                "a model-initiated post-corpus hit must ground the turn even "
                        + "when the pre-fetch was empty");
    }

    @Test
    void duplicateUidsAcrossPreFetchAndLoopCountOnce() {
        semanticSearchResult = UID_A;
        loopToolResults.put("searchPosts", UID_A_AND_B);
        llmProvider.responses.add(
                new LlmResponse("TOOL_CALL: searchPosts {\"tags\": [\"security\"]}"));
        llmProvider.responses.add(new LlmResponse("Done."));

        ChatAgent.ChatTurnResult result = agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");

        assertEquals("grounded(2)", result.provenanceNotice(),
                "post-a arrives via BOTH the pre-fetch and the loop call and "
                        + "must count once (DISTINCT union)");
    }

    @Test
    void memoryToolResultsDoNotGroundTheTurn() {
        // recallMemory output can carry uid-shaped fields, but a memory is
        // user-scoped state, not feed grounding — it must not flip the
        // signal.
        loopToolResults.put("recallMemory", UID_A);
        llmProvider.responses.add(
                new LlmResponse("TOOL_CALL: recallMemory {\"keywords\": [\"tenda\"]}"));
        llmProvider.responses.add(new LlmResponse("Recalled."));

        ChatAgent.ChatTurnResult result = agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");

        assertEquals("general-knowledge", result.provenanceNotice(),
                "memory/saves tools are excluded from the post-corpus union");
    }

    @Test
    void nonEnScopeResolvesBundleInScopeLanguageWithoutTranslator() {
        agent = buildAgent("cs");
        semanticSearchResult = UID_A;
        llmProvider.responses.add(new LlmResponse("Odpověď."));

        ChatAgent.ChatTurnResult result = agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "ahoj");

        assertEquals("cs", bundleLastLanguage,
                "the notice must resolve from the bundle in the scope language (D43)");
        assertEquals("grounded(1)", result.provenanceNotice(),
                "the notice is the pre-localized bundle string — never routed "
                        + "through the translator (the reply is: "
                        + result.reply() + ")");
    }

    @Test
    void llmFailureTurnCarriesNullNotice() {
        llmProvider.throwOnGenerate = true;

        ChatAgent.ChatTurnResult result = agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");

        assertEquals(BundleKeys.ERROR_CHAT_UNAVAILABLE, result.reply());
        assertNull(result.provenanceNotice(),
                "a degrade notice is not an answer; it carries no provenance claim");
    }

    @Test
    void inFlightRejectionCarriesNullNotice() {
        inFlightTracker.tryAcquire(USER_ID, SCOPE_KIND, SCOPE_ID);

        ChatAgent.ChatTurnResult result = agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");

        assertEquals(BundleKeys.ERROR_CHAT_IN_FLIGHT, result.reply());
        assertNull(result.provenanceNotice());
    }

    @Test
    void ceilingGatedRejectionCarriesNullNotice() {
        ceilingGated = true;

        ChatAgent.ChatTurnResult result = agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");

        assertEquals(BundleKeys.ERROR_COMPRESS_FAILED, result.reply());
        assertNull(result.provenanceNotice());
    }

    @Test
    void cancelledTurnCarriesNullNotice() {
        llmProvider.beforeGenerate = () ->
                inFlightTracker.getCancellationHandle(USER_ID, SCOPE_KIND, SCOPE_ID)
                        .ifPresent(InFlightTracker.CancellationHandle::markCancelled);
        llmProvider.responses.add(new LlmResponse("Completed anyway."));

        ChatAgent.ChatTurnResult result = agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");

        assertNull(result.reply(), "a cancelled turn yields no content reply");
        assertNull(result.provenanceNotice());
    }

    // --- rig (ChatAgentTest's pattern, trimmed to what provenance needs) ---

    private ChatToolDispatcher stubDispatcher() {
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
                if ("semanticSearch".equals(toolName)) {
                    return new ToolResult.Success(semanticSearchResult);
                }
                return new ToolResult.Success(
                        loopToolResults.getOrDefault(toolName, "[]"));
            }
        };
    }

    private TestChatAgent buildAgent(String language) {
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

        ChatSessionRepository sessionRepo = new ChatSessionRepository(null) {
            @Override
            public int persistTurn(UUID u, String sk, UUID si,
                                    String role, String content, int tokens) {
                return 0;
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
                return "translated:" + text;
            }
        };

        // The grounded key returns a {0} template so the MessageFormat
        // count interpolation is observable; the language is recorded to
        // pin the D43 bundle-path routing.
        BundleLoader bundle = new BundleLoader() {
            @Override public String get(String key) { return get(key, "en"); }
            @Override public String get(String key, String langCode) {
                if (BundleKeys.CHAT_PROVENANCE_GROUNDED.equals(key)
                        || BundleKeys.CHAT_PROVENANCE_GENERAL_KNOWLEDGE.equals(key)) {
                    bundleLastLanguage = langCode;
                }
                if (BundleKeys.CHAT_PROVENANCE_GROUNDED.equals(key)) {
                    return "grounded({0})";
                }
                if (BundleKeys.CHAT_PROVENANCE_GENERAL_KNOWLEDGE.equals(key)) {
                    return "general-knowledge";
                }
                return key;
            }
        };

        AutoCompressTrigger noopTrigger = new AutoCompressTrigger(
                Integer.MAX_VALUE, bundle, null, null) {
            @Override
            public Optional<String> checkAndCompress(
                    UUID u, String sk, UUID si, String sl) {
                return Optional.empty();
            }

            @Override
            public boolean isCeilingGated(UUID u, String sk, UUID si) {
                return ceilingGated;
            }
        };

        LlmCircuitBreakerRegistry breakerRegistry = new LlmCircuitBreakerRegistry(
                3, 30_000, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                key -> Optional.empty()) {
            @Override
            public boolean wouldShortCircuit(ModelTask task) {
                return chatBreakerOpen;
            }
        };

        InboundContext context = new InboundContext();
        context.setEffectiveLanguage(language);

        return new TestChatAgent(
                inFlightTracker, promptBuilder, stubDispatcher(), sessionRepo,
                router, sanitizer, pipeline, bundle, noopTrigger, context,
                breakerRegistry);
    }

    // Overrides writeAuditRow so no JDBC is needed (ChatAgentTest's seam).
    static class TestChatAgent extends ChatAgent {

        TestChatAgent(InFlightTracker tracker, ChatPromptBuilder builder,
                      ChatToolDispatcher dispatcher, ChatSessionRepository repo,
                      LlmRouter router, LlmOutputSanitizer sanitizer,
                      TranslationPipeline pipeline, BundleLoader bundle,
                      AutoCompressTrigger autoCompressTrigger,
                      InboundContext context, LlmCircuitBreakerRegistry breakerRegistry) {
            super(tracker, builder, dispatcher, repo, router,
                    sanitizer, pipeline, bundle, autoCompressTrigger, null, null,
                    context, breakerRegistry, null, null, null);
        }

        @Override
        void writeAuditRow(UUID userId, String scopeKind, UUID scopeId) {
            // no-op: provenance tests assert the notice, not the audit row
        }

        // M1-665 deterministic delivery trigger: the provenance tests do not
        // exercise delivery, so the trigger returns empty here. The null
        // EmbeddingProvider/HelpCommandHandler passed above are therefore
        // never dereferenced.
        @Override
        Optional<String> lookupIntentForDelivery(String userMessage, UUID userId,
                                                 String scopeKind, UUID scopeId) {
            return Optional.empty();
        }
    }

    static class StubLlmProvider implements LlmProvider {
        final List<LlmResponse> responses = new ArrayList<>();
        int callCount;
        boolean throwOnGenerate;
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
            if (callCount <= responses.size()) {
                return responses.get(callCount - 1);
            }
            return new LlmResponse("default response");
        }
    }
}
