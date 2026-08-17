package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerRegistry;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.AutoCompressTrigger;
import app.zcat.infochat.provider.chat.ChatAgent;
import app.zcat.infochat.provider.chat.ChatLiveTextStreamer;
import app.zcat.infochat.provider.chat.ChatPromptBuilder;
import app.zcat.infochat.provider.chat.ChatReplyMode;
import app.zcat.infochat.provider.chat.ChatSessionRepository;
import app.zcat.infochat.provider.chat.ChatToolDispatcher;
import app.zcat.infochat.provider.chat.ChatToolRegistry;
import app.zcat.infochat.provider.chat.InFlightTracker;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newRealBundleLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live-text publisher mode (M1-846 amendment, messaging.md
 * §Progress notifications): a live-eligible DM turn on a
 * {@code supportsLiveText} adapter reveals the SANITIZED generated
 * prefix in the placeholder — every transmitted update is the
 * sanitizer's output over the FULL generated prefix (security.md
 * §Streamed surfaces: a token assembled across chunk boundaries is
 * seen whole by the closed-list pass before the update ships) — and
 * the terminal finalize carries the full post-pipeline text
 * byte-identical to the non-streaming path for the same generated
 * text. Drives the real {@link ChatAgent} against the real
 * {@link StageProgressNotifier} and an {@link InMemoryAdapter}
 * constructed with the live-text capability on.
 */
class StageProgressNotifierLiveTextTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SCOPE_ID = UUID.randomUUID();
    private static final ScopeRef DM = new ScopeRef.Dm("live-text-contact");

    /**
     * REPRODUCTION (M1-849): a live-eligible turn (flag on + SimpleX-shaped
     * capability + DM scope + generated language == delivered language)
     * streams full-prefix-sanitized updates at the coalescing cadence and
     * finalizes with the full post-pipeline text, BYTE-IDENTICAL to the
     * non-streaming path for the same generated text (P1, P5). The
     * closed-list token {@code /grant-admin} is split across two stream
     * chunks: no transmitted update may ever contain the raw token.
     */
    @Test
    void aLiveTextTurnStreamsSanitizedPrefixesThenFinalizes() throws Exception {
        LiveTextRig streamed = newRig(/* liveTextEnabled */ true, /* language */ "en",
                ChatReplyMode.TRANSLATE);
        streamed.provider.chunks = List.of("Try /grant", "-admin for help.", " More prose.");

        ChatAgent.ChatTurnResult result =
                streamed.agent.handleTurn(USER_ID, "dm", SCOPE_ID, "hello", DM);

        assertNotNull(result.reply(), "a live-eligible turn computes a reply");
        assertTrue(streamed.provider.streamingCalls > 0,
                "the streaming SPI served the eligible turn");
        assertEquals(0, streamed.provider.singleStringCalls,
                "an eligible turn never falls back to the single-string call");

        // Placeholder acquired once; three chunk-driven updates at a zero
        // floor; one finalize. Every update is sanitizer output over the
        // full prefix — the raw split token never transmits (P1).
        assertEquals(1, streamed.adapter.sentMessages().size(),
                "exactly one placeholder send");
        List<InMemoryAdapter.UpdateEvent> history =
                streamed.adapter.updateHistory(handleOf(streamed.adapter));
        List<String> updateBodies = history.stream()
                .filter(e -> !e.isFinal()).map(InMemoryAdapter.UpdateEvent::body).toList();
        assertEquals(3, updateBodies.size(),
                "a zero floor transmits one update per chunk (coalescing cadence)");
        for (String body : updateBodies) {
            assertFalse(body.contains("/grant-admin"),
                    "no transmitted update carries the raw closed-list token: " + body);
        }
        assertEquals("Try [redacted command] for help. More prose.",
                updateBodies.get(updateBodies.size() - 1),
                "the last update is the sanitizer output over the full prefix");

        // The finalize carries the full post-pipeline text composed exactly
        // as the router composes it (reply + provenance), byte-identical to
        // the non-streaming path for the same generated text (P5).
        String streamedComposed = composeAsRouter(result);
        assertTrue(streamed.notifier.completeDelivered(DM, streamedComposed),
                "the finalize reaches the adapter");
        assertEquals(List.of(streamedComposed), streamed.adapter.finalizedBodies(),
                "the finalize body is the full post-pipeline reply, never the last prefix");

        LiveTextRig batch = newRig(/* liveTextEnabled */ false, /* language */ "en",
                ChatReplyMode.TRANSLATE);
        batch.provider.singleStringResponse = "Try /grant-admin for help. More prose.";

        ChatAgent.ChatTurnResult batchResult =
                batch.agent.handleTurn(USER_ID, "dm", SCOPE_ID, "hello", DM);

        assertEquals(1, batch.provider.singleStringCalls,
                "the flag-off turn uses the single-string path");
        assertEquals(0, batch.provider.streamingCalls,
                "the flag-off turn never streams");
        assertEquals(composeAsRouter(batchResult), streamedComposed,
                "the streamed finalize is byte-identical to the batch path for the same text");
        assertTrue(batch.adapter.sentMessages().isEmpty(),
                "the flag-off turn publishes nothing live — today's behavior exactly");
        assertTrue(batch.adapter.finalizedBodies().isEmpty(),
                "the batch rig's terminal is not driven by this assertion leg");
    }

    @Test
    void ineligibleTurnsKeepTheSingleStringStagePath() throws Exception {
        assertCollapsed(newRig(false, true, "en", ChatReplyMode.TRANSLATE), DM);
        assertCollapsed(newRig(true, false, "en", ChatReplyMode.TRANSLATE), DM);
        assertCollapsed(newRig(true, true, "en", ChatReplyMode.TRANSLATE),
                new ScopeRef.Group("live-text-group"));
        assertCollapsed(newRig(true, true, "cs", ChatReplyMode.TRANSLATE), DM);
    }

    @Test
    void aRefusalPrefixedStreamPublishesNothing() throws Exception {
        LiveTextRig refusal = newRig(true, true, "en", ChatReplyMode.TRANSLATE);
        refusal.provider.chunks = List.of("[REFUSAL:", " unsafe request]");

        ChatAgent.ChatTurnResult result =
                refusal.agent.handleTurn(USER_ID, "dm", SCOPE_ID, "hello", DM);

        assertEquals(newRealBundleLoader().get(
                        app.zcat.infochat.provider.bundle.BundleKeys.ERROR_CHAT_REFUSED, "en"),
                result.reply(), "the refusal stream takes the existing refusal degrade");
        assertTrue(refusal.adapter.sentMessages().isEmpty(),
                "a refusal-prefixed stream never acquires a live placeholder");
        assertTrue(refusal.adapter.finalizedBodies().isEmpty(),
                "the router owns the refusal terminal, not the compute helper");

        LiveTextRig prose = newRig(true, true, "en", ChatReplyMode.TRANSLATE);
        prose.provider.chunks = List.of("Answer starts", " with prose.");
        prose.agent.handleTurn(USER_ID, "dm", SCOPE_ID, "hello", DM);
        assertEquals(1, prose.adapter.sentMessages().size(),
                "a prose-prefixed stream releases the hold-back once decidable");
        assertEquals(2, prose.adapter.updateHistory(handleOf(prose.adapter)).stream()
                        .filter(event -> !event.isFinal()).count(),
                "each prose chunk reaches the live update path");
    }

    @Test
    void aSanitizedAssembledRefusalPrefixPublishesNothing() throws Exception {
        LiveTextRig rig = newRig(true, true, "en", ChatReplyMode.TRANSLATE);
        rig.provider.chunks = List.of("[REFUS*", "AL*: unsafe]");

        ChatAgent.ChatTurnResult result =
                rig.agent.handleTurn(USER_ID, "dm", SCOPE_ID, "hello", DM);

        assertEquals(newRealBundleLoader().get(
                        app.zcat.infochat.provider.bundle.BundleKeys.ERROR_CHAT_REFUSED, "en"),
                result.reply(), "the sanitized assembled refusal takes the degrade path");
        assertTrue(rig.adapter.sentMessages().isEmpty(),
                "a refusal assembled by sanitization never acquires a live placeholder");
    }

    @Test
    void aSanitizedAssembledToolOpenerNeverShipsOnTheWire() throws Exception {
        LiveTextRig rig = newRig(true, true, "en", ChatReplyMode.TRANSLATE);
        rig.provider.chunks = List.of(
                "I will use TOOL_*",
                "CALL*: searchPosts {\"tags\": []}");

        rig.agent.handleTurn(USER_ID, "dm", SCOPE_ID, "hello", DM);

        List<String> bodies = rig.adapter.updateHistory(handleOf(rig.adapter)).stream()
                .filter(event -> !event.isFinal())
                .map(InMemoryAdapter.UpdateEvent::body)
                .toList();
        String generating = newRealBundleLoader().get(
                app.zcat.infochat.provider.bundle.BundleKeys.PROGRESS_GENERATING, "en");
        assertTrue(bodies.contains(generating),
                "an opener assembled by sanitization reverts to the generating label");
        assertTrue(bodies.stream().noneMatch(body -> body.contains("TOOL_CALL:")),
                "the sanitized assembled opener never ships as live text");
    }

    @Test
    void aToolCallIterationRevertsToTheStageLabel() throws Exception {
        LiveTextRig rig = newRig(true, true, "en", ChatReplyMode.TRANSLATE);
        rig.provider.streamingSequences = List.of(
                List.of("I will search. TOOL_CALL: searchPosts {\"tags\": []}"),
                List.of("Final answer."));

        ChatAgent.ChatTurnResult result =
                rig.agent.handleTurn(USER_ID, "dm", SCOPE_ID, "hello", DM);
        rig.notifier.completeDelivered(DM, result.reply());

        List<InMemoryAdapter.UpdateEvent> history =
                rig.adapter.updateHistory(handleOf(rig.adapter));
        List<String> bodies = history.stream().map(InMemoryAdapter.UpdateEvent::body).toList();
        String generating = newRealBundleLoader().get(
                app.zcat.infochat.provider.bundle.BundleKeys.PROGRESS_GENERATING, "en");
        assertTrue(bodies.stream().anyMatch(generating::equals),
                "a tool iteration restores the localized generating label: expected "
                        + generating + ", bodies=" + bodies);
        assertTrue(bodies.stream().anyMatch(body -> body.equals("Final answer.")),
                "the answer iteration resumes live updates after the tool call");
        assertTrue(bodies.stream().noneMatch(body -> body.contains("TOOL_CALL:")),
                "tool protocol text is never transmitted");
        assertTrue(bodies.stream().noneMatch(body -> body.contains("I will search.")),
                "prose preceding a tool opener is fail-closed for display");
    }

    @Test
    void aLiveUpdateBreaksLinkAdjacencyAtDelivery() throws Exception {
        LiveTextRig rig = newRig(true, true, "en", ChatReplyMode.TRANSLATE);
        rig.provider.chunks = List.of("See ](", "https://example.test");

        rig.agent.handleTurn(USER_ID, "dm", SCOPE_ID, "hello", DM);

        List<String> updates = rig.adapter.updateHistory(handleOf(rig.adapter)).stream()
                .filter(event -> !event.isFinal())
                .map(InMemoryAdapter.UpdateEvent::body)
                .toList();
        assertTrue(updates.stream().allMatch(body -> !body.contains("](")),
                "every live update passes through the outbound link-adjacency guard");
        assertTrue(updates.stream().anyMatch(body -> body.contains("] (")),
                "the live update carries the broken adjacency on the wire");
    }

    private static void assertCollapsed(LiveTextRig rig, ScopeRef scope) {
        rig.provider.singleStringResponse = "batch response";
        ChatAgent.ChatTurnResult result =
                rig.agent.handleTurn(USER_ID, scope instanceof ScopeRef.Group ? "group" : "dm",
                        SCOPE_ID, "hello", scope);

        assertEquals(1, rig.provider.singleStringCalls,
                "an ineligible turn uses the established single-string call");
        assertEquals(0, rig.provider.streamingCalls,
                "an ineligible turn never enters the streaming SPI");
        assertTrue(rig.adapter.sentMessages().isEmpty(),
                "an ineligible direct compute has no live placeholder");
        assertNotNull(result.reply(), "the existing batch path still computes a reply");
    }

    // ----- rig ------------------------------------------------------------

    private static String composeAsRouter(ChatAgent.ChatTurnResult result) {
        return result.provenanceNotice() == null
                ? result.reply()
                : result.reply() + "\n\n" + result.provenanceNotice();
    }

    private static app.zcat.infochat.messaging.MessageHandle handleOf(InMemoryAdapter adapter) {
        return adapter.sentMessages().isEmpty()
                ? null
                : new app.zcat.infochat.messaging.MessageHandle(
                        "inmem-" + adapter.sentMessages().size());
    }

    record LiveTextRig(InMemoryAdapter adapter, StageProgressNotifier notifier,
                       ChatAgent agent, StubStreamingLlmProvider provider) {}

    static LiveTextRig newRig(boolean liveTextEnabled, String language,
                              ChatReplyMode replyMode) throws Exception {
        return newRig(liveTextEnabled, liveTextEnabled, language, replyMode,
                SanitizerTestDoubles.noAuditSanitizer());
    }

    static LiveTextRig newRig(boolean liveTextEnabled, boolean capabilityEnabled,
                              String language, ChatReplyMode replyMode) throws Exception {
        return newRig(liveTextEnabled, capabilityEnabled, language, replyMode,
                SanitizerTestDoubles.noAuditSanitizer());
    }

    static LiveTextRig newRig(boolean liveTextEnabled, boolean capabilityEnabled,
                              String language, ChatReplyMode replyMode,
                              LlmOutputSanitizer sanitizer) throws Exception {
        return newRig(liveTextEnabled, capabilityEnabled, language, replyMode,
                sanitizer, SanitizerTestDoubles.noOpAuditLogWriter());
    }

    static LiveTextRig newRig(boolean liveTextEnabled, boolean capabilityEnabled,
                              String language, ChatReplyMode replyMode,
                              app.zcat.infochat.core.audit.AuditLogWriter auditWriter)
            throws Exception {
        return newRig(liveTextEnabled, capabilityEnabled, language, replyMode,
                new LlmOutputSanitizer(auditWriter, SanitizerTestDoubles.noOpDataSource()),
                SanitizerTestDoubles.noOpAuditLogWriter());
    }

    private static LiveTextRig newRig(boolean liveTextEnabled, boolean capabilityEnabled,
                                      String language, ChatReplyMode replyMode,
                                      LlmOutputSanitizer sanitizer,
                                      app.zcat.infochat.core.audit.AuditLogWriter auditWriter)
            throws Exception {
        InMemoryAdapter adapter = new InMemoryAdapter(new CapabilityFlags(
                /* supportsMentionByContactId */ true,
                /* supportsMembershipEvents   */ true,
                /* supportsCodeFormatting     */ true,
                /* supportsMarkdownLinks      */ false,
                /* maxInboundMessageBytes     */ 100_000,
                /* maxSendsPerSecond          */ 10_000,
                /* supportsMessageEdit        */ true,
                /* supportsLiveText           */ capabilityEnabled,
                /* supportsTypingIndicator    */ true,
                /* minEditInterval            */ Duration.ZERO,
                /* supportsOutboundAttachments */ true,
                /* maxOutboundAttachmentBytes  */ 1_048_576));

        BundleLoader bundle = newRealBundleLoader();
        StageProgressNotifier notifier = new StageProgressNotifier();
        AdapterRegistry registry = new AdapterRegistry() {
            @Override
            public List<app.zcat.infochat.messaging.MessagingAdapter> activatedAdapters() {
                return List.of(adapter);
            }
        };
        InboundContext context = new InboundContext();
        context.setAdapterName("inmemory");
        context.setEffectiveLanguage(language);
        context.setReplyMode(replyMode);
        notifier.adapterRegistry = registry;
        notifier.inboundContext = context;
        notifier.bundleLoader = bundle;
        notifier.minEditIntervalMs = 0;
        notifier.outboundDelivery = TestOutboundDelivery.passThrough();

        StubStreamingLlmProvider provider = new StubStreamingLlmProvider();
        LlmRouter router = new LlmRouter(
                List.of(new LlmRouter.Entry("test", provider, Set.of("en"))),
                key -> Optional.empty()) {
            @Override
            public LlmProvider forTask(ModelTask task, String lang) {
                return provider;
            }
        };

        ChatLiveTextStreamer streamer = new ChatLiveTextStreamer();
        setField(streamer, "progressNotifier", notifier);
        setField(streamer, "adapterRegistry", registry);
        setField(streamer, "inboundContext", context);
        setField(streamer, "bundleLoader", bundle);
        setField(streamer, "enabled", liveTextEnabled);

        // The overridden build() below never consults the collaborators,
        // so null/empty stand-ins suffice (the ChatAgentReplyModeTest
        // shape, minus its same-package anonymous classes).
        ChatPromptBuilder promptBuilder = new ChatPromptBuilder(
                null, new ChatSessionRepository(null), 16384, 1024) {
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

        TranslationPipeline pipeline = new TranslationPipeline() {
            @Override
            public String run(String text, String scopeLanguage) {
                return "translated:" + text;
            }
        };

        Map<String, ChatToolRegistry.ChatTool> noOpTools = new HashMap<>();
        for (String name : new ChatToolRegistry().toolNames()) {
            noOpTools.put(name, (u, sk, si, a) -> "[]");
        }
        ChatToolDispatcher dispatcher = newDispatcher(noOpTools);

        // A failing embed makes step 3c's help-delivery probes degrade to
        // empty (the production failure posture), keeping this rig off the
        // JDBC lookups whose statement-timeout arming needs collaborators
        // the live-text path under test does not exercise.
        EmbeddingProvider embeddingProvider = texts -> {
            throw new IllegalStateException("no embedding in the live-text rig");
        };

        AutoCompressTrigger noopTrigger = new AutoCompressTrigger(
                Integer.MAX_VALUE, bundle, null, null) {
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
                key -> Optional.empty());

        ChatAgent agent = new ChatAgent(
                new InFlightTracker(), promptBuilder, dispatcher, sessionRepo,
                router, sanitizer, pipeline, bundle, noopTrigger,
                auditWriter,
                SanitizerTestDoubles.noOpDataSource(),
                context, breakerRegistry, embeddingProvider, null, null, streamer);
        return new LiveTextRig(adapter, notifier, agent, provider);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("rig wiring failed on " + name, e);
        }
    }

    /**
     * The fake-tools {@link ChatToolDispatcher} constructor is
     * package-private in {@code provider.chat} (fake implementations are
     * a same-package test convenience); this rig lives in
     * {@code provider.messaging} to sit next to the notifier it drives,
     * so it reaches that constructor reflectively.
     */
    private static ChatToolDispatcher newDispatcher(
            Map<String, ChatToolRegistry.ChatTool> noOpTools) {
        try {
            var ctor = ChatToolDispatcher.class.getDeclaredConstructor(
                    ChatToolRegistry.class, Map.class, int.class, int.class, int.class);
            ctor.setAccessible(true);
            return ctor.newInstance(new ChatToolRegistry(), noOpTools, 500, 200, 20);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("dispatcher rig wiring failed", e);
        }
    }

    /** Serves both shapes: single-string responses and a chunked stream. */
    static final class StubStreamingLlmProvider implements LlmProvider {
        List<String> chunks = new ArrayList<>();
        String singleStringResponse = "default response";
        int singleStringCalls;
        int streamingCalls;
        List<List<String>> streamingSequences = new ArrayList<>();

        @Override
        public String providerName() {
            return "stub-streaming";
        }

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            singleStringCalls++;
            return new LlmResponse(singleStringResponse);
        }

        @Override
        public boolean supportsStreaming(ModelTask task) {
            return true;
        }

        @Override
        public LlmResponse generateStreaming(ModelTask task, String systemPrompt,
                                              String userPrompt, Consumer<String> chunkConsumer) {
            streamingCalls++;
            StringBuilder assembled = new StringBuilder();
            List<String> responseChunks = streamingSequences.isEmpty()
                    ? chunks
                    : streamingSequences.get(Math.min(streamingCalls - 1,
                            streamingSequences.size() - 1));
            for (String chunk : responseChunks) {
                assembled.append(chunk);
                chunkConsumer.accept(chunk);
            }
            return new LlmResponse(assembled.toString());
        }
    }
}
