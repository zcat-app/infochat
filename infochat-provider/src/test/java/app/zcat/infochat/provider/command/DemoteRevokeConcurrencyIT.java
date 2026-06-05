package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.group.GroupRepository;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency IT for the {@code FOR UPDATE} actor-row lock in
 * {@link DemoteCommandHandler} — the sibling of
 * {@link PromoteRevokeConcurrencyIT}, mirroring
 * {@link SaveCapConcurrencyIT}'s two-thread row-lock pattern.
 *
 * <p>The race under test: a concurrent {@code /revoke-admin} UPDATE
 * lands on the actor's {@code users} row first (its transaction holds
 * the row lock), then the same actor's {@code /demote} dispatches.
 * Without {@code FOR UPDATE} the demote's plain admin-gate SELECT would
 * read the pre-revoke committed snapshot ({@code is_admin=TRUE}) and
 * clear the target's {@code is_group_admin} despite the revoke landing
 * first. With the M1-046 PERM-ESCAL closure pattern, the demote's actor
 * read blocks on the row lock until the revoke transaction commits, then
 * observes {@code is_admin=FALSE} and is refused with
 * {@code error.admin_only}, leaving the target's group-admin bit set.</p>
 */
@QuarkusTest
class DemoteRevokeConcurrencyIT {

    private static final String ADAPTER = "inmemory";
    private static final String PREFIX = "m1-144-demote-race-";
    private static final String UPSTREAM_GROUP_ID = PREFIX + "group-" + UUID.randomUUID();

    private static final String REVOKE_ADMIN_UPDATE_SQL =
            "UPDATE users SET is_admin = FALSE WHERE adapter = ? AND contact_id = ?";

    @Inject DemoteCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject GroupRepository groupRepository;

    private UUID groupId;
    private String actorContactId;
    private String targetContactId;
    private UUID targetUserId;

    @BeforeEach
    void setup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);

        actorContactId = PREFIX + "actor-" + UUID.randomUUID();
        targetContactId = PREFIX + "target-" + UUID.randomUUID();

        try (Connection conn = dataSource.getConnection()) {
            cleanTestData(conn);

            // Guardian admin so the revoke UPDATE on the actor passes the
            // V5 last-admin-protection trigger (cannot leave zero admins).
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, "guardian-" + PREFIX + "permanent");

            // Actor: bot admin whose admin bit the concurrent revoke clears.
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE SET is_admin = TRUE",
                    ADAPTER, actorContactId);

            targetUserId = seedUserReturningId(conn, targetContactId);
        }

        groupId = groupRepository.findOrCreateByAdapterAndUpstreamId(ADAPTER, UPSTREAM_GROUP_ID);

        // The target starts as the group admin: /demote's precondition is
        // that the target currently holds is_group_admin, and the refused
        // demote must leave that bit intact.
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO group_membership (group_id, user_id, is_group_admin) VALUES (?, ?, TRUE) "
                            + "ON CONFLICT (group_id, user_id) DO UPDATE SET is_group_admin = TRUE",
                    groupId, targetUserId);
        }
    }

    @Test
    void demoteActorReadBlocksOnRevokeLockAndIsRefusedAfterCommit() throws Exception {
        try (Connection revokeTx = dataSource.getConnection()) {
            revokeTx.setAutoCommit(false);
            ExecutorService workers = Executors.newSingleThreadExecutor();
            try {
                // The revoke lands first: its UPDATE takes the row lock on
                // the actor's users row and holds it (transaction open).
                exec(revokeTx, REVOKE_ADMIN_UPDATE_SQL, ADAPTER, actorContactId);

                CountDownLatch dispatching = new CountDownLatch(1);
                Future<OutboundMessage> demote =
                        workers.submit(() -> dispatchDemoteOnWorker(dispatching));
                assertTrue(dispatching.await(5, TimeUnit.SECONDS),
                        "worker thread must reach the /demote dispatch within 5s");

                // Grace for the worker to reach the actor read. With the
                // FOR UPDATE lock in place the dispatch CANNOT complete
                // while the revoke transaction holds the row — a done
                // future here means the actor read did not lock (the
                // pre-M1-144 TOCTOU regression).
                Thread.sleep(500);
                assertFalse(demote.isDone(),
                        "/demote must block on the FOR UPDATE actor-row lock "
                                + "while the revoke transaction holds it");

                revokeTx.commit();

                OutboundMessage reply = demote.get(15, TimeUnit.SECONDS);
                assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), reply.text(),
                        "/demote must be refused once the committed revoke is visible");
                assertTrue(isGroupAdmin(groupId, targetUserId),
                        "the refused /demote must not have cleared group_membership.is_group_admin");
            } catch (Throwable t) {
                revokeTx.rollback();
                throw t;
            } finally {
                workers.shutdownNow();
            }
        }
    }

    /**
     * Worker-thread dispatch mirroring {@link PromoteRevokeConcurrencyIT}:
     * activates a CDI request context (worker threads carry no ambient
     * request scope from the test thread), sets the per-request inbound
     * identity, fires /demote through the production handler, and tears
     * the context down.
     */
    private OutboundMessage dispatchDemoteOnWorker(CountDownLatch dispatching) {
        ManagedContext requestContext = Arc.container().requestContext();
        requestContext.activate();
        try {
            inboundContext.setAdapterName(ADAPTER);
            inboundContext.setSenderContactId(actorContactId);
            dispatching.countDown();
            return handler.handle(new ScopeRef.Group(UPSTREAM_GROUP_ID),
                    "/demote " + targetContactId);
        } finally {
            requestContext.terminate();
        }
    }

    private boolean isGroupAdmin(UUID gId, UUID uId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_group_admin FROM group_membership "
                             + "WHERE group_id = ? AND user_id = ? AND removed_at IS NULL")) {
            ps.setObject(1, gId);
            ps.setObject(2, uId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean("is_group_admin");
            }
        }
    }

    private UUID seedUserReturningId(Connection conn, String contactId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (id, adapter, contact_id, is_admin, is_banned, registration_state) "
                        + "VALUES (?, ?, ?, FALSE, FALSE, 'vouched') "
                        + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                        + "SET is_banned = EXCLUDED.is_banned RETURNING id")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, ADAPTER);
            ps.setString(3, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void cleanTestData(Connection conn) throws Exception {
        exec(conn,
                "DELETE FROM group_membership WHERE group_id IN "
                        + "(SELECT id FROM groups WHERE upstream_group_id = ?)",
                UPSTREAM_GROUP_ID);
        exec(conn, "DELETE FROM groups WHERE upstream_group_id = ?", UPSTREAM_GROUP_ID);
    }

    private static void exec(Connection conn, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }
}
