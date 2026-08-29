package app.zcat.infochat.provider.chat;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerRegistry;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.messaging.TranslationProvider;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.AnchorTranslatorDoubles;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import app.zcat.infochat.provider.testsupport.TranslationFixtures;
import app.zcat.infochat.provider.translation.TranslationCache;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code cs} direction of M1-778: a chat reply reached a {@code /lang cs}
 * scope in English while every bundle-localized part of the same message was
 * Czech.
 *
 * <p>Two independent things had to hold for that to be repairable, and both
 * are pinned here.
 *
 * <ul>
 *   <li><b>The generator is contracted to English.</b> {@code ChatAgent}
 *       hands its prose to the two-argument {@code TranslationPipeline.run},
 *       which DECLARES the input English on the caller's behalf, and
 *       persists the same text as English-canonical chat memory. Nothing
 *       verified that claim — D29 forbids inferring a language from text —
 *       so the only thing that can make it truthful is a contracted
 *       channel.</li>
 *   <li><b>A translator that does not translate is caught.</b> A reply
 *       byte-identical to the English input already produced the note. One
 *       added character defeated that test, and the target-script check is
 *       blind to a Latin target, so the reader got English with no note at
 *       all — and it was cached for the whole TTL.</li>
 * </ul>
 *
 * <p><b>Why the failure is stubbed, never observed.</b> The live defect was
 * intermittent — a later turn answered correctly in Czech — so a test that
 * watched a real translator would pass on the broken code most of the time.
 * The generator's text and the translator's reply are both deterministic
 * functions of their input here.
 *
 * <p>Rig: {@link ChatAgentProvenanceTest}'s, with one deliberate difference
 * — the {@link TranslationPipeline} is REAL rather than stubbed, since the
 * behaviour under test lives inside it.
 */
class ChatAgentReplyLanguageTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SCOPE_ID = UUID.randomUUID();
    private static final String SCOPE_KIND = "dm";

    private static final String ENGLISH_REPLY =
            "Canonical introduced the Enterprise Store, available through Ubuntu Pro.";
    private static final String CZECH_REPLY =
            "Canonical představil Enterprise Store, dostupný přes Ubuntu Pro.";

    private InFlightTracker inFlightTracker;
    private StubLlmProvider llmProvider;
    private BundleLoader bundleLoader;

    @BeforeEach
    void setUp() throws Exception {
        inFlightTracker = new InFlightTracker();
        llmProvider = new StubLlmProvider();
        bundleLoader = TranslationFixtures.newRealBundleLoader();
    }

    @Test
    void nearEchoFromTheTranslatorStillTellsTheReaderTheReplyIsEnglish() throws Exception {
        llmProvider.responses.add(new LlmResponse(ENGLISH_REPLY));
        // The whole failure in one character: the translator hands the
        // English back with a trailing period. Byte identity no longer
        // matches, and cs is a Latin target so the script check is blind.
        ChatAgent agent = buildAgent("cs", (text, from, to) -> text + ".");

        String reply = agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "co je nového?").reply();

        assertTrue(reply.contains(bundleLoader.get(BundleKeys.REPLY_TRANSLATION_UNAVAILABLE, "cs")),
                "an untranslated reply must say so, in the reader's language; got: " + reply);
        assertTrue(reply.startsWith(ENGLISH_REPLY),
                "and the English prose is still delivered beneath it; got: " + reply);
    }

    @Test
    void genuineTranslationCarriesNoNote() throws Exception {
        // The guard on the guard: the new check must not turn into a
        // blanket note-emitter over every successful translation.
        llmProvider.responses.add(new LlmResponse(ENGLISH_REPLY));
        ChatAgent agent = buildAgent("cs", (text, from, to) -> CZECH_REPLY);

        String reply = agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "co je nového?").reply();

        assertEquals(CZECH_REPLY, reply,
                "a real Czech translation is delivered bare; got: " + reply);
        assertFalse(reply.contains(bundleLoader.get(BundleKeys.REPLY_TRANSLATION_UNAVAILABLE, "cs")),
                "no note over a reply that IS in the reader's language; got: " + reply);
    }

    @Test
    void replyLanguageDirectiveRidesTheOrdinaryTurn() throws Exception {
        llmProvider.responses.add(new LlmResponse(ENGLISH_REPLY));
        ChatAgent agent = buildAgent("cs", (text, from, to) -> CZECH_REPLY);

        agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "co je nového?");

        assertTrue(llmProvider.lastSystemPrompt.contains(ChatAgent.REPLY_LANGUAGE_DIRECTIVE),
                "the model is told which language to answer in; got: "
                        + llmProvider.lastSystemPrompt);
    }

    @Test
    void replyLanguageDirectiveRidesTheIterationCapFinalCall() throws Exception {
        // The final call is handed the BASE system prompt alone, so a pin
        // appended only to the tool-augmented form would be lost exactly on
        // the turn that already went wrong enough to exhaust the loop.
        for (int i = 0; i < ChatAgent.MAX_TOOL_ITERATIONS; i++) {
            llmProvider.responses.add(new LlmResponse("TOOL_CALL: getPost {\"uid\": \"abc\"}"));
        }
        llmProvider.responses.add(new LlmResponse(ENGLISH_REPLY));
        ChatAgent agent = buildAgent("en", (text, from, to) -> text);

        agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "summarize");

        assertEquals(ChatAgent.MAX_TOOL_ITERATIONS + 1, llmProvider.callCount,
                "the loop reached its cap and made the final call");
        assertTrue(llmProvider.lastSystemPrompt.contains(ChatAgent.REPLY_LANGUAGE_DIRECTIVE),
                "the pin survives onto the final call; got: " + llmProvider.lastSystemPrompt);
        assertFalse(llmProvider.lastSystemPrompt.contains("Available tools:"),
                "and it did not drag the tool instructions along with it; got: "
                        + llmProvider.lastSystemPrompt);
    }

    // --- rig (ChatAgentProvenanceTest's, with a real TranslationPipeline) ---

    /**
     * A real {@link TranslationPipeline} with a caller-supplied translator.
     * {@code TranslationFixtures.newEnShortCircuitPipeline} hardcodes an
     * identity translator, which is the one behaviour these tests must vary.
     */
    private TranslationPipeline realPipelineWith(TranslationProvider translationProvider)
            throws Exception {
        TranslationPipeline pipeline = new TranslationPipeline();
        setField(pipeline, "translationCache", new TranslationCache());
        setField(pipeline, "translationProvider", translationProvider);
        setField(pipeline, "llmOutputSanitizer", SanitizerTestDoubles.noAuditSanitizer());
        setField(pipeline, "bundleLoader", bundleLoader);
        return pipeline;
    }

    private static void setField(TranslationPipeline pipeline, String name, Object value)
            throws Exception {
        Field field = TranslationPipeline.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(pipeline, value);
    }

    private ChatToolDispatcher stubDispatcher() {
        Map<String, ChatToolRegistry.ChatTool> noOpTools = new HashMap<>();
        for (String name : new ChatToolRegistry().toolNames()) {
            noOpTools.put(name, (u, sk, si, a) -> "[]");
        }
        return new ChatToolDispatcher(new ChatToolRegistry(), noOpTools, 500, 200, 20) {
            @Override
            public ToolResult dispatch(String toolName, Map<String, Object> args,
                                       UUID userId, String scopeKind, UUID scopeId,
                                       TurnContext turn) {
                return new ToolResult.Success("[]");
            }
        };
    }

    private ChatAgent buildAgent(String language, TranslationProvider translationProvider)
            throws Exception {
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
                1024,
                6144) {
            @Override
            public BuiltPrompt build(UUID u, String sk, UUID si, String msg,
                                     String semanticBlock, String turnDirective,
                                     int systemSuffixTokens) {
                return new BuiltPrompt("system", msg, "marker",
                        new CompactionReport(6144, 0, 0, 0, 0, false, false));
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

        // Pass-through: the pipeline runs its OWN sanitizer, and these tests
        // assert on language, not on redaction.
        LlmOutputSanitizer sanitizer = new LlmOutputSanitizer(
                SanitizerTestDoubles.noOpAuditLogWriter(), SanitizerTestDoubles.noOpDataSource()) {
            @Override
            public String sanitize(String input) {
                return input;
            }
        };

        AutoCompressTrigger noopTrigger = new AutoCompressTrigger(
                Integer.MAX_VALUE, bundleLoader, null, null) {
            @Override
            public Optional<String> checkAndCompress(UUID u, String sk, UUID si, String sl) {
                return Optional.empty();
            }

            @Override
            public boolean isCeilingGated(UUID u, String sk, UUID si) {
                return false;
            }
        };

        LlmCircuitBreakerRegistry breakerRegistry = new LlmCircuitBreakerRegistry(
                3, 30_000, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                key -> Optional.empty()) {
            @Override
            public boolean wouldShortCircuit(ModelTask task) {
                return false;
            }
        };

        InboundContext context = new InboundContext();
        context.setEffectiveLanguage(language);

        return new TestChatAgent(
                inFlightTracker, promptBuilder, stubDispatcher(), sessionRepo,
                router, sanitizer, realPipelineWith(translationProvider), bundleLoader,
                noopTrigger, context, breakerRegistry);
    }

    /** Overrides writeAuditRow so no JDBC is needed (ChatAgentTest's seam). */
    static class TestChatAgent extends ChatAgent {

        TestChatAgent(InFlightTracker tracker, ChatPromptBuilder builder,
                      ChatToolDispatcher dispatcher, ChatSessionRepository repo,
                      LlmRouter router, LlmOutputSanitizer sanitizer,
                      TranslationPipeline pipeline, BundleLoader bundle,
                      AutoCompressTrigger autoCompressTrigger,
                      InboundContext context, LlmCircuitBreakerRegistry breakerRegistry) {
            super(tracker, builder, dispatcher, repo, router,
                    sanitizer, pipeline, bundle, autoCompressTrigger, null, null,
                    context, breakerRegistry, null, null, null, null, null,
                    AnchorTranslatorDoubles.passthrough());
        }

        @Override
        void writeAuditRow(UUID userId, String scopeKind, UUID scopeId) {
            // no-op: these tests assert the reply's language, not the audit row
        }

        @Override
        Optional<String> lookupIntentForDelivery(String userMessage, UUID userId,
                                                 String scopeKind, UUID scopeId) {
            return Optional.empty();
        }
    }

    static class StubLlmProvider implements LlmProvider {
        final List<LlmResponse> responses = new ArrayList<>();
        int callCount;
        String lastSystemPrompt = "";

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            callCount++;
            lastSystemPrompt = systemPrompt;
            if (callCount <= responses.size()) {
                return responses.get(callCount - 1);
            }
            return new LlmResponse("default response");
        }
    }
}
