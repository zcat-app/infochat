package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerRegistry;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.chat.ChatAgent;
import app.zcat.infochat.provider.chat.LlmRateCap;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import app.zcat.infochat.provider.group.GroupAutoPromoteService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pin for the send-ok-but-persist-fails fork introduced by the M1-313
 * reorder: once chat-turn persistence runs AFTER the reply is delivered, a
 * persist failure cannot un-send the already-delivered reply. The router
 * must log and move on — never re-enter the send path — so the user sees
 * exactly one reply, not a duplicate. Wires the router by hand (no Quarkus
 * boot) like {@link InboundRouterAcquisitionCountTest}: the dispatchChat
 * seam stands in for ChatAgent, returning a reply and stashing a pending
 * commit that throws.
 */
class InboundRouterChatPersistFailureTest {

    private static final String ADAPTER = "inmemory";
    private static final String CONTACT = "persist-fail-contact";
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID GROUP_DB_ID = UUID.randomUUID();

    @Test
    void persistFailureAfterDeliveredReplyDoesNotResend() {
        CountingDispatchDataSource counting =
                new CountingDispatchDataSource(ACTOR_ID);
        AtomicInteger commitAttempts = new AtomicInteger();

        InboundRouter router = new InboundRouter() {
            @Override
            String dispatchChat(UUID actorId, String scopeKind, UUID scopeId, String normalized,
                                app.zcat.infochat.messaging.ScopeRef scope) {
                // Production shape: stash the deferred commit, return the reply.
                // Here the commit fails AFTER the reply will have been delivered.
                inboundContext.setPendingChatCommit(() -> {
                    commitAttempts.incrementAndGet();
                    throw new IllegalStateException("simulated persist failure after delivery");
                });
                return "chat-reply";
            }
        };
        InboundContext context = new InboundContext();
        RecordingMessagingAdapter target = new RecordingMessagingAdapter();
        router.outboundDelivery = TestOutboundDelivery.passThrough();
        router.dataSource = counting;
        router.inboundContext = context;
        // M1-607 self-delivery wiring: the chat reply ships placeholder →
        // finalize through the notifier; the delivery-gated commit then runs
        // (and fails) exactly once after the successful finalize.
        router.progressNotifier = newNotifier(target, context);
        // Closed-breaker idiom (ChatAgentRefusalInterceptTest): no endpoint
        // resolves, so wouldShortCircuit is false and the clock is never read
        // — Clock.fixed pins that this test has no wall-clock dependence.
        router.breakerRegistry = new LlmCircuitBreakerRegistry(
                3, 30_000, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), key -> Optional.empty());
        router.rateCapBucket = new AdmitAllRateCapBucket();
        router.registeredContactSet = new NoopRegisteredContactSet();
        router.groupApprovalCheck = new NoopGroupApprovalCheck(GROUP_DB_ID);
        router.inviteCodeConsumer = new NoopInviteCodeConsumer();
        router.bundleLoader = new NoopBundleLoader();
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
        router.setReplyTarget(target);

        router.onMessage(groupInbound("trigger a chat reply"), ADAPTER);

        assertEquals(1, commitAttempts.get(),
                "the deferred commit must be attempted once after a successful delivery");
        assertEquals(List.of("chat-reply"), target.finalizes,
                "the delivered reply is the notifier's single finalize; a persist "
                        + "failure after delivery must NOT redeliver it. Finalized: "
                        + target.finalizes);
        assertEquals(1, target.sends.size(),
                "a persist failure after delivery must NOT re-enter the send path; "
                        + "the only send is the placeholder. Sent: " + target.sends);
    }

    private static InboundMessage groupInbound(String body) {
        return new InboundMessage(
                new Identity(CONTACT, "Alice", Instant.now()),
                new ScopeRef.Group("persist-fail-group"),
                body,
                Instant.now(),
                "msg-1");
    }

    /**
     * A real {@link StageProgressNotifier} over the recording adapter,
     * sharing the router's {@link InboundContext} so {@code resolveAdapter}
     * finds the adapter under the dispatch's adapterName (same wiring as
     * {@link InboundRouterAcquisitionCountTest}).
     */
    private static StageProgressNotifier newNotifier(RecordingMessagingAdapter adapter,
                                                     InboundContext context) {
        StageProgressNotifier notifier = new StageProgressNotifier();
        notifier.adapterRegistry = new AdapterRegistry() {
            @Override
            public List<MessagingAdapter> activatedAdapters() {
                return List.of(adapter);
            }
        };
        notifier.inboundContext = context;
        notifier.bundleLoader = new NoopBundleLoader();
        notifier.minEditIntervalMs = 0;
        notifier.outboundDelivery = TestOutboundDelivery.passThrough();
        return notifier;
    }
}
