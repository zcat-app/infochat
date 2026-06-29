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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.MessageFormat;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency IT for the atomic per-user save cap in
 * {@link SaveCommandHandler}. The {@code SELECT ... FOR UPDATE} row
 * lock on the actor's {@code users} row is the only atomic guarantee;
 * a single-threaded handler unit test cannot exercise it. Two threads
 * issue {@code /save} simultaneously against two different READY posts
 * for the same actor at {@code save_count = cap - 1}; exactly one
 * admits.
 *
 * <p>Per acceptance item 8 of M1-052. The IT runs under
 * {@code @QuarkusTest} against the DevServices container (same Postgres
 * the handler tests use); no Testcontainers wiring beyond what
 * DevServices already provides.</p>
 */
@QuarkusTest
class SaveCapConcurrencyIT {

    private static final String PREFIX = "m1-052-cap-it-";
    private static final String ADAPTER = "inmemory";

    @Inject SaveCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;

    @Inject
    @ConfigProperty(name = "infochat.save.cap")
    int saveCap;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "DELETE FROM saved_post WHERE user_id IN ("
                            + "SELECT id FROM users WHERE contact_id LIKE ?)",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM post WHERE source_id IN ("
                            + "SELECT id FROM source WHERE identifier LIKE ?)",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM source_subscription WHERE source_id IN ("
                            + "SELECT id FROM source WHERE identifier LIKE ?)",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM source WHERE identifier LIKE ?",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM users WHERE contact_id LIKE ?",
                    PREFIX + "%");
        }
    }

    @Test
    void concurrentSavesAtCapMinusOneAdmitExactlyOne() throws Exception {
        String contactId = PREFIX + "actor";
        seedUserAtSaveCount(contactId, saveCap - 1);
        UUID sourceId = seedSource(PREFIX + "source");
        seedDmSubscription(contactId, sourceId);
        String uidA = PREFIX + "uid-a";
        String uidB = PREFIX + "uid-b";
        seedReadyPost(sourceId, uidA);
        seedReadyPost(sourceId, uidB);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            Future<OutboundMessage> futA = exec.submit(() -> dispatchOnWorker(
                    ready, start, contactId, uidA));
            Future<OutboundMessage> futB = exec.submit(() -> dispatchOnWorker(
                    ready, start, contactId, uidB));
            assertTrue(ready.await(5, TimeUnit.SECONDS),
                    "both worker threads must reach the start gate within 5s");
            // Release both simultaneously — the FOR UPDATE row lock
            // serializes them at the SELECT step.
            start.countDown();

            OutboundMessage replyA = futA.get(15, TimeUnit.SECONDS);
            OutboundMessage replyB = futB.get(15, TimeUnit.SECONDS);

            String successA = MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_SAVE_SUCCESS), uidA);
            String successB = MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_SAVE_SUCCESS), uidB);
            String capMet = bundleLoader.get(BundleKeys.ERROR_SAVE_CAP_MET);

            int successCount = 0;
            int capMetCount = 0;
            for (OutboundMessage reply : new OutboundMessage[] { replyA, replyB }) {
                String text = reply.text();
                if (text.equals(successA) || text.equals(successB)) {
                    successCount++;
                } else if (text.equals(capMet)) {
                    capMetCount++;
                } else {
                    throw new AssertionError(
                            "unexpected /save reply at cap-1: " + text);
                }
            }
            assertEquals(1, successCount,
                    "exactly one /save must admit at cap-1");
            assertEquals(1, capMetCount,
                    "exactly one /save must surface error.save.cap_met at cap-1");
            assertEquals(saveCap, readSaveCount(contactId),
                    "users.save_count must equal cap after the contention");
        } finally {
            exec.shutdownNow();
        }
    }

    /**
     * Worker-thread dispatch: activates a CDI request context (worker
     * threads carry no ambient request scope from the @QuarkusTest
     * test-thread context), sets the per-request adapter name, fires
     * /save through the production handler, and tears the context down.
     * Mirrors the InboundRouter's {@code @ActivateRequestContext}
     * shape — programmatic instead of annotation-driven so it works
     * inside a {@link java.util.concurrent.Callable} lambda.
     */
    private OutboundMessage dispatchOnWorker(CountDownLatch ready, CountDownLatch start,
                                             String contactId, String uid) throws Exception {
        ManagedContext requestContext = Arc.container().requestContext();
        requestContext.activate();
        try {
            inboundContext.setAdapterName(ADAPTER);
            ready.countDown();
            start.await();
            return handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);
        } finally {
            requestContext.terminate();
        }
    }

    private void seedUserAtSaveCount(String contactId, int saveCount) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state, save_count) "
                             + "VALUES (?, ?, FALSE, FALSE, 'vouched', ?)")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setInt(3, saveCount);
            ps.executeUpdate();
        }
    }

    private UUID seedSource(String identifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags) VALUES ('rss', ?, ?, 'news', '{}') "
                             + "RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, "Test Source " + identifier);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedDmSubscription(String contactId, UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // DM scope_id is the user's own users.id (schema V7); resolved
            // by subquery because seedUserAtSaveCount does not return it.
            exec(conn,
                    "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                            + "SELECT 'dm', id, ? FROM users WHERE contact_id = ?",
                    sourceId, contactId);
        }
    }

    private void seedReadyPost(UUID sourceId, String uid) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (source_id, uid, title, body, fetched_at, status, "
                             + "upstream_identifier) "
                             + "VALUES (?, ?, ?, 'b', ?, 'READY', ?)")) {
            ps.setObject(1, sourceId);
            ps.setString(2, uid);
            ps.setString(3, "title-" + uid);
            ps.setObject(4, OffsetDateTime.parse("2026-05-15T00:00:00Z"));
            ps.setString(5, uid);
            ps.executeUpdate();
        }
    }

    private int readSaveCount(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT save_count FROM users WHERE contact_id = ?")) {
            ps.setString(1, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "users row must exist for contact_id=" + contactId);
                return rs.getInt("save_count");
            }
        }
    }

    private static void exec(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }
}
