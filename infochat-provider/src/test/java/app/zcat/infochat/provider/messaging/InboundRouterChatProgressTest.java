package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerRegistry;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.LlmRateCap;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import app.zcat.infochat.provider.group.GroupAutoPromoteService;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newRealBundleLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Named acceptance tests for the M1-607 chat progress lifecycle: the
 * chat-mode dispatch self-delivers through a real
 * {@link StageProgressNotifier} — placeholder ({@code STARTED}), stage
 * edits, terminal finalize — with the {@link InboundRouter#dispatchChat}
 * seam standing in for ChatAgent (same hand-wired rig as
 * {@link InboundRouterChatPersistFailureTest}). The notifier's coalescing
 * floor is pinned to 0 so every published stage lands as an adapter
 * update and the full sequence is observable.
 */
class InboundRouterChatProgressTest {

    private static final String ADAPTER = "inmemory";
    private static final String CONTACT = "chat-progress-contact";
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID GROUP_DB_ID = UUID.randomUUID();

    /** Distinctive user-authored text — must never appear in a stage string. */
    private static final String USER_MESSAGE = "USERTEXT-do-not-render-4f9a tell me things";
    /**
     * Stands in for a retrieved post title: the fake compute embeds it in
     * the REPLY (where sanitized retrieved content legitimately appears)
     * so the test can pin that stage strings never carry it.
     */
    private static final String RETRIEVED_TITLE = "TITLE-do-not-render-7c2e";
    /** The answer a /stop-cancelled turn computed but must discard. */
    private static final String STALE_REPLY = "STALE-cancelled-answer-91bd";

    private final BundleLoader bundle = realBundle();

    private static BundleLoader realBundle() {
        try {
            return newRealBundleLoader();
        } catch (Exception e) {
            throw new IllegalStateException("test bundle fixture failed to load", e);
        }
    }

    @Test
    void normalTurnPublishesStageSequenceAndFinalizeCarriesReply() {
        RecordingMessagingAdapter target = new RecordingMessagingAdapter();
        AtomicInteger commitRuns = new AtomicInteger();
        InboundRouter router = newChatRouter(target, closedBreaker(),
                "reply about " + RETRIEVED_TITLE, commitRuns);

        router.onMessage(groupInbound(USER_MESSAGE), ADAPTER);

        assertEquals(List.of(bundle.get(BundleKeys.PROGRESS_STARTED, "en")), target.sends,
                "the turn must acquire exactly one placeholder carrying the STARTED stage string");
        assertEquals(List.of(
                        bundle.get(BundleKeys.PROGRESS_RETRIEVING, "en"),
                        bundle.get(BundleKeys.PROGRESS_GENERATING, "en"),
                        bundle.get(BundleKeys.PROGRESS_FINALIZING, "en")),
                target.updates,
                "a normal turn publishes RETRIEVING -> GENERATING -> FINALIZING in order");
        assertEquals(List.of("reply about " + RETRIEVED_TITLE), target.finalizes,
                "finalize must REPLACE the placeholder with the computed reply");
        assertEquals(1, commitRuns.get(),
                "the deferred commit must run exactly once after the delivered finalize");
    }

    @Test
    void breakerOpenTurnSkipsRetrievingStage() {
        RecordingMessagingAdapter target = new RecordingMessagingAdapter();
        InboundRouter router = newChatRouter(target, openChatBreaker(),
                "degraded reply", new AtomicInteger());

        router.onMessage(groupInbound(USER_MESSAGE), ADAPTER);

        assertEquals(List.of(
                        bundle.get(BundleKeys.PROGRESS_GENERATING, "en"),
                        bundle.get(BundleKeys.PROGRESS_FINALIZING, "en")),
                target.updates,
                "with the chat breaker OPEN the M1-589 pre-fetch is skipped, so RETRIEVING"
                        + " must not be advertised");
    }

    @Test
    void noPublishedStageStringContainsUserMessageOrRetrievedTitle() {
        RecordingMessagingAdapter target = new RecordingMessagingAdapter();
        InboundRouter router = newChatRouter(target, closedBreaker(),
                "reply quoting " + RETRIEVED_TITLE, new AtomicInteger());

        router.onMessage(groupInbound(USER_MESSAGE), ADAPTER);

        // Stage strings are the placeholder send plus every update — the
        // finalize is the reply itself, which MAY carry retrieved content.
        List<String> stageStrings = new ArrayList<>(target.sends);
        stageStrings.addAll(target.updates);
        assertFalse(stageStrings.isEmpty(), "expected published stage strings");
        for (String stage : stageStrings) {
            assertFalse(stage.contains(USER_MESSAGE),
                    "stage string must not contain the user's message; got: " + stage);
            assertFalse(stage.contains("USERTEXT-do-not-render-4f9a"),
                    "stage string must not contain any user-message substring; got: " + stage);
            assertFalse(stage.contains(RETRIEVED_TITLE),
                    "stage string must not contain a retrieved title; got: " + stage);
        }
        // The strings are bundle constants — enum-keyed lookups with no
        // interpolation surface at all (messaging.md §Progress notifications).
        List<String> allowedStageStrings = List.of(
                bundle.get(BundleKeys.PROGRESS_STARTED, "en"),
                bundle.get(BundleKeys.PROGRESS_RETRIEVING, "en"),
                bundle.get(BundleKeys.PROGRESS_GENERATING, "en"),
                bundle.get(BundleKeys.PROGRESS_FINALIZING, "en"));
        for (String stage : stageStrings) {
            assertTrue(allowedStageStrings.contains(stage),
                    "every stage string must be exactly an enum-keyed bundle constant; got: "
                            + stage);
        }
    }

    @Test
    void messageEditUnsupportedAdapterCollapsesToSingleFinalSend() {
        RecordingMessagingAdapter target =
                new RecordingMessagingAdapter().withSupportsMessageEdit(false);
        AtomicInteger commitRuns = new AtomicInteger();
        InboundRouter router = newChatRouter(target, closedBreaker(),
                "collapsed reply", commitRuns);

        router.onMessage(groupInbound(USER_MESSAGE), ADAPTER);

        assertEquals(List.of("collapsed reply"), target.sends,
                "a supportsMessageEdit=false adapter must receive exactly one final send"
                        + " of the completed reply — no placeholder");
        assertEquals(List.of(), target.updates, "no intermediate edits on the degraded path");
        assertEquals(List.of(), target.finalizes, "no finalize edit on the degraded path");
        assertEquals(List.of(), target.typing, "typing is never toggled without a placeholder");
        assertEquals(1, commitRuns.get(),
                "business logic is unchanged on the degraded path — the delivered send"
                        + " still gates the commit exactly once");
    }

    @Test
    void stopCancelledTurnFinalizesStoppedTerminalNotStaleReply() {
        RecordingMessagingAdapter target = new RecordingMessagingAdapter();
        AtomicInteger commitRuns = new AtomicInteger();
        // A null dispatchChat reply is the /stop-cancelled contract: the
        // discarded answer must never reach the notifier.
        InboundRouter router = newChatRouter(target, closedBreaker(), null, commitRuns);

        router.onMessage(groupInbound(USER_MESSAGE), ADAPTER);

        assertEquals(List.of(bundle.get(BundleKeys.PROGRESS_STOPPED, "en")), target.finalizes,
                "a /stop-cancelled turn finalizes the D35 stopped terminal");
        List<String> everything = new ArrayList<>(target.sends);
        everything.addAll(target.updates);
        everything.addAll(target.finalizes);
        for (String outbound : everything) {
            assertFalse(outbound.contains(STALE_REPLY),
                    "no outbound may carry the stale cancelled reply; got: " + outbound);
        }
        assertEquals(0, commitRuns.get(), "a cancelled turn carries no commit to run");
    }

    /**
     * Hand-wired router (the {@link InboundRouterChatPersistFailureTest}
     * rig) whose {@code dispatchChat} seam returns {@code cannedReply}
     * ({@code null} = /stop-cancelled) and stashes a counting commit for
     * non-null replies, mirroring the production ChatAgent contract.
     */
    private InboundRouter newChatRouter(RecordingMessagingAdapter target,
                                        LlmCircuitBreakerRegistry breaker,
                                        @Nullable String cannedReply,
                                        AtomicInteger commitRuns) {
        CountingDispatchDataSource counting = new CountingDispatchDataSource(ACTOR_ID);
        InboundRouter router = new InboundRouter() {
            @Override
            @Nullable String dispatchChat(UUID actorId, String scopeKind, UUID scopeId,
                                          String normalized) {
                if (cannedReply == null) {
                    // /stop-cancelled contract: the computed answer
                    // (STALE_REPLY) is discarded and no commit is stashed
                    // (ChatAgent leaves it unset for cancelled turns).
                    return null;
                }
                inboundContext.setPendingChatCommit(() -> {
                    commitRuns.incrementAndGet();
                    return Optional.empty();
                });
                return cannedReply;
            }
        };
        InboundContext context = new InboundContext();
        router.outboundDelivery = TestOutboundDelivery.passThrough();
        router.dataSource = counting;
        router.inboundContext = context;
        router.rateCapBucket = new AdmitAllRateCapBucket();
        router.registeredContactSet = new NoopRegisteredContactSet();
        router.groupApprovalCheck = new NoopGroupApprovalCheck(GROUP_DB_ID);
        router.inviteCodeConsumer = new NoopInviteCodeConsumer();
        router.bundleLoader = bundle;
        router.commandHandlers = new SingletonInstance<>();
        router.confirmStateService = new NoopConfirmStateService();
        router.commandPermissions = new NoopCommandPermissions();
        router.probationCheck = new NoopProbationCheck();
        router.groupAutoPromoteService =
                new GroupAutoPromoteService(counting, new AuditLogWriter(row -> row)) {
                    @Override
                    public boolean tryAutoPromote(UUID groupId, UUID userId,
                                                  String adapter, String contactId) {
                        return false;
                    }
                };
        router.summaryAnchorRepository = new SummaryAnchorRepository() {
            @Override public void clear(UUID userId, String scopeKind, UUID scopeId) {}
        };
        router.llmRateCap = new LlmRateCap(10);
        router.maxInboundBodyBytes = 65536;
        router.chatBodyCap = 65536;
        router.commandBodyCap = 65536;
        router.progressNotifier = newNotifier(target, context);
        router.breakerRegistry = breaker;
        router.setReplyTarget(target);
        return router;
    }

    /**
     * A real {@link StageProgressNotifier} over the recording adapter with
     * a 0ms coalescing floor, so every published stage lands as an adapter
     * update and the sequence is directly observable. Real bundle strings
     * (D43) back the no-user-input assertions.
     */
    private StageProgressNotifier newNotifier(RecordingMessagingAdapter adapter,
                                              InboundContext context) {
        StageProgressNotifier notifier = new StageProgressNotifier();
        notifier.adapterRegistry = new AdapterRegistry() {
            @Override
            public List<MessagingAdapter> activatedAdapters() {
                return List.of(adapter);
            }
        };
        notifier.inboundContext = context;
        notifier.bundleLoader = bundle;
        notifier.minEditIntervalMs = 0;
        notifier.outboundDelivery = TestOutboundDelivery.passThrough();
        return notifier;
    }

    /**
     * Closed-breaker idiom (ChatAgentRefusalInterceptTest): no endpoint
     * resolves, so wouldShortCircuit is false and the clock is never read.
     */
    private static LlmCircuitBreakerRegistry closedBreaker() {
        return new LlmCircuitBreakerRegistry(3, 30_000,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), key -> Optional.empty());
    }

    /**
     * A registry whose chat-task breaker is OPEN under the fixed clock:
     * threshold 1, one recorded transport failure, cooldown far in the
     * future relative to the pinned instant.
     */
    private static LlmCircuitBreakerRegistry openChatBreaker() {
        LlmCircuitBreakerRegistry registry = new LlmCircuitBreakerRegistry(1, 30_000,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                key -> Optional.of("http://chat-breaker.test:1/v1"));
        registry.recordUnreachableForTask(ModelTask.CHAT_AGENT);
        return registry;
    }

    private static InboundMessage groupInbound(String body) {
        return new InboundMessage(
                new Identity(CONTACT, "Alice", Instant.now()),
                new ScopeRef.Group("chat-progress-group"),
                body,
                Instant.now(),
                "msg-1");
    }
}
