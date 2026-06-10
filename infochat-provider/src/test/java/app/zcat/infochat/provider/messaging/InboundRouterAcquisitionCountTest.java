package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.chat.LlmRateCap;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import app.zcat.infochat.provider.group.GroupAutoPromoteService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance pin for the per-dispatch connection budget: a group
 * chat-mode dispatch borrows exactly ONE router-owned pool connection
 * for the whole pre-LLM read phase (users-row snapshot, groups-row
 * resolution, membership upsert), the groups.id resolved at step 4.1
 * is carried forward to the chat scope, and the connection is back in
 * the pool before the dispatch crosses the LLM boundary. Collaborators
 * with their own connections (approval check, auto-promote internals,
 * probation, anchor repository) are stubbed log-silent Noops — the
 * count under test is the router's own budget.
 */
class InboundRouterAcquisitionCountTest {

    private static final String ADAPTER = "inmemory";
    private static final String CONTACT = "acq-count-contact";
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID GROUP_DB_ID = UUID.randomUUID();

    @Test
    void groupChatDispatchBorrowsOneConnectionAndNoneSpansTheLlmCall() {
        CountingDispatchDataSource counting =
                new CountingDispatchDataSource(ACTOR_ID, GROUP_DB_ID);
        AtomicInteger openAtLlmBoundary = new AtomicInteger(-1);
        AtomicReference<UUID> llmScopeId = new AtomicReference<>();

        InboundRouter router = new InboundRouter() {
            @Override
            String dispatchChat(UUID actorId, String scopeKind, UUID scopeId, String normalized) {
                openAtLlmBoundary.set(counting.openConnections());
                llmScopeId.set(scopeId);
                return "chat-reply";
            }
        };
        router.dataSource = counting;
        router.inboundContext = new InboundContext();
        router.rateCapBucket = new AdmitAllRateCapBucket();
        // §7a wiring: both doubles are JDBC-free, so the router-owned
        // acquisition count under test is unchanged.
        router.registeredContactSet = new NoopRegisteredContactSet();
        router.groupApprovalCheck = new NoopGroupApprovalCheck();
        router.inviteCodeConsumer = new NoopInviteCodeConsumer();
        router.bundleLoader = new NoopBundleLoader();
        router.commandHandlers = new SingletonInstance<>();
        router.confirmStateService = new NoopConfirmStateService();
        router.commandPermissions = new NoopCommandPermissions();
        router.probationCheck = new NoopProbationCheck();
        // The real ensureGroupMembership must run on the shared dispatch
        // connection; only the auto-promote side-trip (a service-owned
        // connection outside the router's budget) is stubbed out.
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
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(groupInbound("hello acquisition count"), ADAPTER);

        assertEquals(1, target.captured.size(),
                "the chat dispatch must produce exactly one reply; got: " + target.captured);
        assertEquals("chat-reply", target.captured.get(0).text(),
                "the reply must come from the chat dispatch seam");
        assertEquals(1, counting.connectionCount(),
                "a group chat dispatch must borrow exactly one router-owned"
                        + " connection for the pre-LLM read phase; executed: "
                        + counting.executedSql());
        assertEquals(0, openAtLlmBoundary.get(),
                "no router connection may be open when the dispatch crosses"
                        + " the LLM boundary");
        assertEquals(0, counting.openConnections(),
                "the dispatch connection must be returned to the pool");
        assertEquals(GROUP_DB_ID, llmScopeId.get(),
                "the chat scope id must be the groups.id carried forward from"
                        + " step 4.1, not a re-lookup");
        assertTrue(counting.executedSql().stream()
                        .anyMatch(sql -> sql.contains("group_membership")),
                "the membership upsert must ride the shared dispatch"
                        + " connection; executed: " + counting.executedSql());
    }

    private static InboundMessage groupInbound(String body) {
        return new InboundMessage(
                new Identity(CONTACT, "Alice", Instant.now()),
                new ScopeRef.Group("acq-count-group"),
                body,
                Instant.now(),
                "msg-1");
    }
}
