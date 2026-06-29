package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
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
 * Concurrency IT for the in-transaction {@code FOR UPDATE} actor-row
 * re-gate M1-480 adds to {@link UnbanCommandHandler} — the sibling of
 * {@link DemoteRevokeConcurrencyIT} / {@link PromoteRevokeConcurrencyIT},
 * mirroring that two-thread row-lock pattern for {@code /unban}.
 *
 * <p>The race under test: a concurrent {@code /revoke-admin} UPDATE
 * lands on the caller's {@code users} row first (its transaction holds
 * the row lock), then the same caller's {@code /unban} dispatches.
 * Before M1-480 the unban's mutation committed on the dispatch-time
 * non-locking admin read (pre-revoke {@code is_admin=TRUE}) and would
 * clear the target's ban despite the revoke landing first. With the
 * M1-046 closure the unban's in-tx actor read blocks on the row lock
 * until the revoke transaction commits, then observes
 * {@code is_admin=FALSE} and is refused with {@code error.admin_only},
 * leaving the target banned.</p>
 */
@QuarkusTest
class UnbanCommandHandlerToctouIT {

    private static final String ADAPTER = "inmemory";
    private static final String PREFIX = "m1-480-unban-race-";

    private static final String REVOKE_ADMIN_UPDATE_SQL =
            "UPDATE users SET is_admin = FALSE WHERE adapter = ? AND contact_id = ?";

    @Inject UnbanCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;

    private String actorContactId;
    private String targetContactId;

    @BeforeEach
    void setup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        actorContactId = PREFIX + "actor-" + UUID.randomUUID();
        targetContactId = PREFIX + "target-" + UUID.randomUUID();

        try (Connection conn = dataSource.getConnection()) {
            // Guardian admin so the concurrent revoke on the caller passes
            // the V5 last-admin-protection trigger (cannot leave zero admins).
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, "guardian-" + PREFIX + "permanent");

            // Caller: bot admin whose admin bit the concurrent revoke clears.
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE SET is_admin = TRUE",
                    ADAPTER, actorContactId);

            // Target: a registered, currently-banned user whose ban the
            // refused /unban must leave intact (the non-preban Step-5 leg).
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, is_banned, registration_state, banned_at) "
                            + "VALUES (?, ?, FALSE, TRUE, 'vouched', NOW()) "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE SET is_banned = TRUE",
                    ADAPTER, targetContactId);
        }
    }

    @Test
    void unbanActorReadBlocksOnRevokeLockAndIsRefusedAfterCommit() throws Exception {
        try (Connection revokeTx = dataSource.getConnection()) {
            revokeTx.setAutoCommit(false);
            ExecutorService workers = Executors.newSingleThreadExecutor();
            try {
                // The revoke lands first: its UPDATE takes the row lock on
                // the caller's users row and holds it (transaction open).
                exec(revokeTx, REVOKE_ADMIN_UPDATE_SQL, ADAPTER, actorContactId);

                CountDownLatch dispatching = new CountDownLatch(1);
                Future<OutboundMessage> unban =
                        workers.submit(() -> dispatchUnbanOnWorker(dispatching));
                assertTrue(dispatching.await(5, TimeUnit.SECONDS),
                        "worker thread must reach the /unban dispatch within 5s");

                // Grace for the worker to reach the in-tx actor read. With
                // the FOR UPDATE re-gate in place the dispatch CANNOT
                // complete while the revoke transaction holds the row — a
                // done future here means the in-tx actor read did not lock
                // (the pre-M1-480 TOCTOU regression).
                Thread.sleep(500);
                assertFalse(unban.isDone(),
                        "/unban must block on the FOR UPDATE actor-row lock "
                                + "while the revoke transaction holds it");

                revokeTx.commit();

                OutboundMessage reply = unban.get(15, TimeUnit.SECONDS);
                assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), reply.text(),
                        "/unban must be refused once the committed revoke is visible");
                assertTrue(isBanned(targetContactId),
                        "the refused /unban must not have cleared the target's ban");
            } catch (Throwable t) {
                revokeTx.rollback();
                throw t;
            } finally {
                workers.shutdownNow();
            }
        }
    }

    /**
     * Worker-thread dispatch mirroring {@link DemoteRevokeConcurrencyIT}:
     * activates a CDI request context (worker threads carry no ambient
     * request scope from the test thread), sets the per-request inbound
     * identity, fires {@code /unban} through the production handler, and
     * tears the context down. The DM scope's {@code contactId} is the
     * caller identity the handler's admin gate resolves.
     */
    private OutboundMessage dispatchUnbanOnWorker(CountDownLatch dispatching) {
        ManagedContext requestContext = Arc.container().requestContext();
        requestContext.activate();
        try {
            inboundContext.setAdapterName(ADAPTER);
            inboundContext.setSenderContactId(actorContactId);
            dispatching.countDown();
            return handler.handle(new ScopeRef.Dm(actorContactId),
                    "/unban " + targetContactId);
        } finally {
            requestContext.terminate();
        }
    }

    private boolean isBanned(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_banned FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean("is_banned");
            }
        }
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
