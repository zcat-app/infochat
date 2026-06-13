package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.chat.ChatAgent;
import app.zcat.infochat.provider.chat.LlmRateCap;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import app.zcat.infochat.provider.group.GroupAutoPromoteService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
            String dispatchChat(UUID actorId, String scopeKind, UUID scopeId, String normalized) {
                // Production shape: stash the deferred commit, return the reply.
                // Here the commit fails AFTER the reply will have been delivered.
                inboundContext.setPendingChatCommit(() -> {
                    commitAttempts.incrementAndGet();
                    throw new IllegalStateException("simulated persist failure after delivery");
                });
                return "chat-reply";
            }
        };
        router.outboundDelivery = TestOutboundDelivery.passThrough();
        router.dataSource = counting;
        router.inboundContext = new InboundContext();
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
        RecordingMessagingAdapter target = new RecordingMessagingAdapter();
        router.setReplyTarget(target);

        router.onMessage(groupInbound("trigger a chat reply"), ADAPTER);

        assertEquals(1, commitAttempts.get(),
                "the deferred commit must be attempted once after a successful delivery");
        assertEquals(1, target.sends.size(),
                "a persist failure after delivery must NOT resend the reply; "
                        + "the user already received it. Sent: " + target.sends);
    }

    private static InboundMessage groupInbound(String body) {
        return new InboundMessage(
                new Identity(CONTACT, "Alice", Instant.now()),
                new ScopeRef.Group("persist-fail-group"),
                body,
                Instant.now(),
                "msg-1");
    }
}
