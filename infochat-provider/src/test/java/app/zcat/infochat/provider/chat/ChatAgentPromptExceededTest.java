package app.zcat.infochat.provider.chat;

import app.zcat.infochat.core.notifier.NotifyOutcome;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.impl.LlmCallFailedException;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerRegistry;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.AnchorTranslatorDoubles;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import app.zcat.infochat.provider.translation.TranslationPipeline;
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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the named prompt-exceeds-context degrade (gated 400 + own-estimate-over-budget), its negative gate, discard contract, headers-phase streaming finalization, operator notification, and admitted-entries grounding account; rig mirrors {@link ChatAgentProvenanceTest}. */
class ChatAgentPromptExceededTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SCOPE_ID = UUID.randomUUID();
    private static final String SCOPE_KIND = "dm";
    private static final String ERROR_CLASS = "chat-prompt-exceeded";

    @Test
    void promptExceededTurnGetsTheNamedNotice() {
        RecordingAdminNotifier notifier = new RecordingAdminNotifier();
        TestChatAgent agent = buildAgent("en", builderWithBudget(1800), notifier);
        // The typed shape llama-server answers on a context overflow: an
        // answered 400, not a transport failure.
        agent.llmProvider.throwException = new LlmCallFailedException.ProviderRequestRejectedException(
                "test: non-2xx status 400 from localhost", 400);

        ChatAgent.ChatTurnResult result =
                agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "hello there");

        assertEquals(BundleKeys.ERROR_CHAT_PROMPT_EXCEEDED, result.reply(),
                "a gated context-rejection must surface the NAMED notice, not "
                        + "error.chat.unavailable");
        assertNull(result.pendingCommit(),
                "the turn is discarded: no chat_session advance, no chat_memory write");
        assertNull(result.provenanceNotice(),
                "the degrade carries no provenance notice (the router ships it verbatim)");
        assertEquals(0, persistCalls,
                "nothing may be persisted on the named degrade path");
    }

    @Test
    void underBudgetEstimateKeepsTheGenericNotice() {
        // Leg A: the same typed 400 on a turn whose own estimate never
        // exceeded the budget — the notice must not claim "too large" on
        // evidence the turn does not carry.
        RecordingAdminNotifier notifierA = new RecordingAdminNotifier();
        TestChatAgent underBudgetAgent =
                buildAgent("en", hugeBudgetBuilder(), notifierA);
        underBudgetAgent.llmProvider.throwException =
                new LlmCallFailedException.ProviderRequestRejectedException(
                        "test: non-2xx status 400 from localhost", 400);

        ChatAgent.ChatTurnResult underBudget =
                underBudgetAgent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "hello there");

        assertEquals(BundleKeys.ERROR_CHAT_UNAVAILABLE, underBudget.reply(),
                "an under-budget 400 keeps the generic unavailable notice");
        assertTrue(notifierA.calls.isEmpty(),
                "only the named path notifies the operator");

        // Leg B: a non-400 typed rejection stays generic regardless of the
        // estimate — only a context-shaped 400 names the cause.
        RecordingAdminNotifier notifierB = new RecordingAdminNotifier();
        TestChatAgent serverErrorAgent =
                buildAgent("en", builderWithBudget(1800), notifierB);
        serverErrorAgent.llmProvider.throwException =
                new LlmCallFailedException.ProviderRequestRejectedException(
                        "test: non-2xx status 500 from localhost", 500);

        ChatAgent.ChatTurnResult serverError =
                serverErrorAgent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "hello there");

        assertEquals(BundleKeys.ERROR_CHAT_UNAVAILABLE, serverError.reply(),
                "a non-400 typed rejection keeps the generic path regardless of estimate");
        assertTrue(notifierB.calls.isEmpty(),
                "only the named path notifies the operator");
    }

    @Test
    void promptExceededNotifiesOperatorOnce() {
        // Exact-evidence leg: budget pinned one token BELOW the probe-measured
        // estimate, so the named path reports exactly the probe's numbers;
        // probe and drive share the prose-bearing message (D37 non-vacuity).
        int probeEstimate = probeEstimateBefore("hello there secret-prose");
        int probeBudget = probeEstimate - 1;
        RecordingAdminNotifier notifier = new RecordingAdminNotifier();
        TestChatAgent agent =
                buildAgent("en", noMemoryBuilderWithBudget(probeBudget), notifier);
        agent.llmProvider.throwException =
                new LlmCallFailedException.ProviderRequestRejectedException(
                        "test: non-2xx status 400 from localhost", 400);

        agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "hello there secret-prose");

        assertEquals(1, notifier.calls.size(),
                "the named path emits exactly one throttled notification per turn");
        RecordingAdminNotifier.Notified call = notifier.calls.get(0);
        assertEquals(ERROR_CLASS, call.errorClass(),
                "the notification rides the chat-prompt-exceeded error class");
        assertTrue(call.message().contains(String.valueOf(probeEstimate)),
                "the operator message must name the estimated prompt tokens ("
                        + probeEstimate + "); got: " + call.message());
        assertTrue(call.message().contains(String.valueOf(probeBudget)),
                "the operator message must name the configured budget; got: "
                        + call.message());
        assertFalse(call.message().contains("secret-prose"),
                "no user prose reaches the notification (D37); got: " + call.message());
    }

    @Test
    void streamingHeaders400FinalizesWithTheNamedString() {
        RecordingAdminNotifier notifier = new RecordingAdminNotifier();
        TestChatAgent agent = buildStreamingAgent("en", builderWithBudget(1800), notifier);
        // Headers-phase rejection: the streaming call throws BEFORE any
        // chunk is handed to the reveal — no live text was published.
        agent.llmProvider.streamingThrowException =
                new LlmCallFailedException.ProviderRequestRejectedException(
                        "test: non-2xx status 400 from localhost", 400);

        ChatAgent.ChatTurnResult result = agent.handleTurn(
                USER_ID, SCOPE_KIND, SCOPE_ID, "hello there",
                new ScopeRef.Dm("test-contact"));

        assertEquals(BundleKeys.ERROR_CHAT_PROMPT_EXCEEDED, result.reply(),
                "the named string flows the same handleTurn catch → finalize "
                        + "machinery the unavailable path uses");
        assertNull(result.pendingCommit(), "the streamed turn is discarded too");
        assertNull(result.provenanceNotice(), "no notice on the degrade");
        assertEquals(0, persistCalls, "nothing persisted on the streaming degrade");
    }

    @Test
    void truncatedToZeroFoldBackClaimsNoGrounding() {
        // Leg A: the fit admits ZERO entries of the oversized result — the
        // provenance account must claim no grounding. +40 covers estimate
        // drift; one entry is ~110 tokens, so zero entries still fail the fit.
        int tightBudget = probeEstimateBefore("summarize") + 40;
        TestChatAgent zeroAdmission = buildAgent("en",
                noMemoryBuilderWithBudget(tightBudget), new RecordingAdminNotifier());
        loopToolResults.put("searchPosts", thirtyEntryArray());
        zeroAdmission.llmProvider.responses.add(
                new LlmResponse("TOOL_CALL: searchPosts {\"tags\": [\"security\"]}"));
        zeroAdmission.llmProvider.responses.add(new LlmResponse("Final answer."));

        ChatAgent.ChatTurnResult zeroResult =
                zeroAdmission.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "summarize");

        assertEquals("general-knowledge", zeroResult.provenanceNotice(),
                "zero admitted entries must yield the ungrounded wording, never "
                        + "a grounding claim");
        String foldedPrompt = zeroAdmission.llmProvider.allUserPrompts.get(1);
        assertFalse(foldedPrompt.contains("https://e.x/entry-"),
                "the fixture must admit zero entries for this leg to be honest");

        // Leg B: partial admission names ONLY the admitted count.
        TestChatAgent partial = buildAgent("en",
                noMemoryBuilderWithBudget(tightBudget + 600), new RecordingAdminNotifier());
        loopToolResults.put("searchPosts", thirtyEntryArray());
        partial.llmProvider.responses.add(
                new LlmResponse("TOOL_CALL: searchPosts {\"tags\": [\"security\"]}"));
        partial.llmProvider.responses.add(new LlmResponse("Final answer."));

        ChatAgent.ChatTurnResult partialResult =
                partial.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "summarize");

        int keptEnd = highestSurvivingEntry(partial.llmProvider.allUserPrompts.get(1));
        assertTrue(keptEnd >= 0 && keptEnd < 29,
                "the fixture must admit SOME but not all thirty entries; highest "
                        + "surviving index was " + keptEnd);
        assertEquals("grounded(" + (keptEnd + 1) + ")", partialResult.provenanceNotice(),
                "partial admission grounds exactly the admitted prefix");
    }

    // --- rig (ChatAgentProvenanceTest's pattern, plus the recording
    // notifier and REAL budgeted assembly) ---

    private static final Instant MEMORY_STAMP = Instant.parse("2026-08-01T10:00:00Z");

    /** A real budgeted builder whose memory hit forces compaction. */
    private ChatPromptBuilder builderWithBudget(int promptBudget) {
        return new ChatPromptBuilder(
                new ChatMemoryPreFetcher() {
                    @Override
                    public List<ChatMemoryPreFetcher.MemoryHit> preFetch(
                            UUID u, String sk, UUID si, String q) {
                        return List.of(new ChatMemoryPreFetcher.MemoryHit(
                                MEMORY_STAMP, padded("memory summary ", 6000), List.of()));
                    }
                },
                emptyTurnRepo(), 16384, 1024, promptBudget);
    }

    private ChatPromptBuilder hugeBudgetBuilder() {
        return noMemoryBuilderWithBudget(Integer.MAX_VALUE / 4);
    }

    /** A real budgeted builder with no memory hits — exact admission math. */
    private ChatPromptBuilder noMemoryBuilderWithBudget(int promptBudget) {
        return new ChatPromptBuilder(
                new ChatMemoryPreFetcher() {
                    @Override
                    public List<ChatMemoryPreFetcher.MemoryHit> preFetch(
                            UUID u, String sk, UUID si, String q) {
                        return List.of();
                    }
                },
                emptyTurnRepo(), 16384, 1024, promptBudget);
    }

    /** No DataSource wired: the rig's sessions always start empty. */
    private static ChatSessionRepository emptyTurnRepo() {
        return new ChatSessionRepository(null) {
            @Override
            public List<ChatSessionRepository.Turn> readTurns(
                    UUID u, String sk, UUID si) {
                return List.of();
            }
        };
    }

    /** First-call assembled estimate a real build reports for this fixture — pins exact admission budgets and the notification's reported numbers. */
    private int probeEstimateBefore(String query) {
        int suffixTokens = ChatSessionRepository.estimateTokens(
                ChatAgent.REPLY_LANGUAGE_DIRECTIVE + ChatAgent.TOOL_INSTRUCTIONS);
        return noMemoryBuilderWithBudget(Integer.MAX_VALUE / 4)
                .build(USER_ID, SCOPE_KIND, SCOPE_ID, query, "", "",
                        suffixTokens).compaction().estimateBefore();
    }

    private static String padded(String prefix, int chars) {
        return prefix + "x".repeat(Math.max(0, chars - prefix.length()));
    }

    /** A 30-entry searchPosts result (~110 estimated tokens per entry). */
    private static String thirtyEntryArray() {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            if (i > 0) {
                entries.append(',');
            }
            entries.append("{\"uid\": \"entry-").append(i)
                    .append("\", \"url\": \"https://e.x/entry-").append(i)
                    .append("\", \"title\": \"").append(padded("t", 400)).append("\"}");
        }
        return "[" + entries + "]";
    }

    private static int highestSurvivingEntry(String prompt) {
        int highest = -1;
        for (int i = 0; i < 30; i++) {
            if (prompt.contains("https://e.x/entry-" + i)) {
                highest = i;
            }
        }
        return highest;
    }

    private TestChatAgent buildAgent(String language, ChatPromptBuilder promptBuilder,
                                     RecordingAdminNotifier notifier) {
        return build(language, promptBuilder, notifier, false);
    }

    private TestChatAgent buildStreamingAgent(String language,
                                              ChatPromptBuilder promptBuilder,
                                              RecordingAdminNotifier notifier) {
        return build(language, promptBuilder, notifier, true);
    }

    private TestChatAgent build(String language, ChatPromptBuilder promptBuilder,
                                RecordingAdminNotifier notifier, boolean streaming) {
        StubLlmProvider llmProvider = new StubLlmProvider();

        ChatSessionRepository sessionRepo = new ChatSessionRepository(null) {
            @Override
            public int persistTurn(UUID u, String sk, UUID si,
                                    String role, String content, int tokens) {
                persistCalls++;
                return persistCalls - 1;
            }
        };

        LlmRouter router = new LlmRouter(
                List.of(new LlmRouter.Entry("test", llmProvider, Set.of("en"))),
                key -> Optional.empty()) {
            @Override
            public LlmProvider forTask(ModelTask task, String lang) {
                return llmProvider;
            }

            @Override
            public boolean streamingSupportedFor(ModelTask task, String scopeLanguage) {
                return streaming;
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

        BundleLoader bundle = new BundleLoader() {
            @Override public String get(String key) { return get(key, "en"); }
            @Override public String get(String key, String langCode) {
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

        ChatLiveTextStreamer liveTextStreamer = streaming
                ? new ChatLiveTextStreamer() {
                    @Override
                    public boolean eligible(ScopeRef scope, String scopeKind,
                                            String scopeLanguage, ChatReplyMode replyMode) {
                        return true;
                    }

                    @Override
                    public LiveTextReveal newReveal(ScopeRef scope, String scopeLanguage) {
                        // No chunk ever arrives (headers-phase rejection), so
                        // the null StageProgressNotifier is never dereferenced.
                        return new LiveTextReveal(null, bundle, scope, scopeLanguage);
                    }
                }
                : null;

        return new TestChatAgent(
                new InFlightTracker(), promptBuilder, stubDispatcher(), sessionRepo,
                router, sanitizer, pipeline, bundle, noopTrigger, context,
                breakerRegistry, llmProvider, liveTextStreamer, notifier);
    }

    private int persistCalls;

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
                    return new ToolResult.Success("[]");
                }
                return new ToolResult.Success(loopToolResults.getOrDefault(toolName, "[]"));
            }
        };
    }

    private final Map<String, String> loopToolResults = new HashMap<>();

    /** Captures notifyOnce arguments at the seam the agent owns. */
    static final class RecordingAdminNotifier extends ThrottledAdminNotifier {

        record Notified(String key, String errorClass, String message) {}

        final List<Notified> calls = new ArrayList<>();

        @Override
        public NotifyOutcome notifyOnce(String key, String errorClass, String message) {
            calls.add(new Notified(key, errorClass, message));
            return NotifyOutcome.EMITTED;
        }
    }

    static class StubLlmProvider implements LlmProvider {
        final List<LlmResponse> responses = new ArrayList<>();
        int callCount;
        RuntimeException throwException;
        RuntimeException streamingThrowException;
        final List<String> allUserPrompts = new ArrayList<>();

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            if (throwException != null) {
                throw throwException;
            }
            callCount++;
            allUserPrompts.add(userPrompt);
            if (callCount <= responses.size()) {
                return responses.get(callCount - 1);
            }
            return new LlmResponse("default response");
        }

        @Override
        public LlmResponse generateStreaming(ModelTask task, String systemPrompt,
                                             String userPrompt, Consumer<String> chunkConsumer) {
            // Throws before handing ANY chunk to the consumer — the
            // headers-phase rejection shape.
            if (streamingThrowException != null) {
                throw streamingThrowException;
            }
            return generate(task, systemPrompt, userPrompt);
        }
    }

    static class TestChatAgent extends ChatAgent {

        final StubLlmProvider llmProvider;

        TestChatAgent(InFlightTracker tracker, ChatPromptBuilder builder,
                      ChatToolDispatcher dispatcher, ChatSessionRepository repo,
                      LlmRouter router, LlmOutputSanitizer sanitizer,
                      TranslationPipeline pipeline, BundleLoader bundle,
                      AutoCompressTrigger autoCompressTrigger,
                      InboundContext context, LlmCircuitBreakerRegistry breakerRegistry,
                      StubLlmProvider llmProvider, ChatLiveTextStreamer liveTextStreamer,
                      RecordingAdminNotifier notifier) {
            super(tracker, builder, dispatcher, repo, router,
                    sanitizer, pipeline, bundle, autoCompressTrigger, null, null,
                    context, breakerRegistry, null, null, null, liveTextStreamer,
                    notifier, AnchorTranslatorDoubles.passthrough());
            this.llmProvider = llmProvider;
        }

        @Override
        void writeAuditRow(UUID userId, String scopeKind, UUID scopeId) {
            // no-op: these tests assert the degrade surface, not the audit row
        }

        @Override
        Optional<String> lookupIntentForDelivery(String userMessage, UUID userId,
                                                 String scopeKind, UUID scopeId) {
            return Optional.empty();
        }
    }
}
