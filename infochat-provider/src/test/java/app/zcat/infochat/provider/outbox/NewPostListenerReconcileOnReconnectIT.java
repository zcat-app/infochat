package app.zcat.infochat.provider.outbox;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration test for the M1-142 hardening on {@link NewPostListener}:
 * a reconnect must run the {@link NewPostReconciler} catch-up so NOTIFYs
 * lost during a transient PG blip are recovered without a process
 * restart.
 *
 * <p>Race-free design: the READY {@code post} row is seeded BEFORE the
 * forced disconnect and its NOTIFY is never emitted — the simulated
 * "NOTIFY lost during the blip". Whenever the worker's reconnect fires,
 * the row already exists, so the test does not depend on winning a
 * timing window. Because no NOTIFY is ever sent for the row and the
 * startup reconciler ran long before the cursor reset, the post-reconnect
 * reconcile is the only mechanism that can advance the cursor to it.
 *
 * <p>Idempotency (the clarity-check-confirmed contract behind wiring the
 * post-reconnect call): the second test invokes {@code runCatchUp()}
 * again after the reconnect-driven run has caught up and asserts zero
 * additional rows and an unchanged cursor — reconcile delivered the row
 * exactly once.
 */
@QuarkusTest
class NewPostListenerReconcileOnReconnectIT {

    private static final String TEST_UID_PREFIX = "reconcile-reconnect-it/";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration AWAIT_POLL = Duration.ofMillis(100);

    /**
     * Fetched-at value chosen to land in V7's bootstrap partition
     * ({@code post_202605}), keeping the test deterministic regardless of
     * wall-clock drift inside the partition window.
     */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-15T12:00:00Z");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    NewPostListener newPostListener;

    @Inject
    NewPostReconciler newPostReconciler;

    @Inject
    ProviderStateDao providerStateDao;

    private UUID testSourceId;

    @BeforeEach
    void setUp() throws Exception {
        // Broad cleanup so the post-reconnect catch-up scan (which starts
        // from the reset epoch cursor) sees exactly the rows this test
        // seeds, regardless of which IT seeded rows previously.
        clearAllItPosts();
        resetNewPostCursor();
        testSourceId = ensureTestSource();
    }

    @AfterEach
    void tearDown() throws Exception {
        clearTestPosts();
    }

    @Test
    void reconnectTriggersReconcileAndCursorCatchesUp() throws Exception {
        assertTrue(newPostListener.isWorkerAlive(),
            "worker must be alive at test start");

        UUID postId = UUID.randomUUID();
        Instant readyAt = Instant.parse("2026-05-15T23:00:00Z");
        seedReadyRow(postId, readyAt);

        newPostListener.closeListenConnectionForTest();

        awaitCursor(
            c -> readyAt.equals(c.cursorHigh()) && postId.toString().equals(c.cursorLowId()),
            "cursor must catch up to the un-NOTIFYed READY row within " + AWAIT_TIMEOUT
                + " — no NOTIFY was ever emitted for it, so only the post-reconnect"
                + " reconcile can deliver it; if the cursor never advances, the"
                + " reconnect did not run the reconciler");

        assertTrue(newPostListener.isWorkerAlive(),
            "worker must remain alive after the reconnect-plus-reconcile sequence");
    }

    @Test
    void reconcileReRunAfterReconnectCatchUpIsIdempotentNoOp() throws Exception {
        assertTrue(newPostListener.isWorkerAlive(),
            "worker must be alive at test start");

        UUID postId = UUID.randomUUID();
        Instant readyAt = Instant.parse("2026-05-15T23:30:00Z");
        seedReadyRow(postId, readyAt);

        newPostListener.closeListenConnectionForTest();

        // First reconcile invocation: the wired post-reconnect call.
        awaitCursor(
            c -> readyAt.equals(c.cursorHigh()) && postId.toString().equals(c.cursorLowId()),
            "the reconnect-driven reconcile must catch the cursor up before the"
                + " idempotency re-run is meaningful");

        // Second invocation over the same state: zero additional rows, cursor
        // unchanged — the row was delivered exactly once despite reconcile
        // running twice.
        newPostReconciler.runCatchUp();
        assertEquals(0, newPostReconciler.caughtUpCount(),
            "re-running reconcile after the post-reconnect catch-up must process"
                + " zero rows — no duplicate delivery");

        Optional<ProviderStateDao.Cursor> cursor =
            providerStateDao.readCursor(NewPostHandler.CHANNEL_NEW_POST);
        assertTrue(cursor.isPresent(), "cursor row must exist after catch-up");
        assertEquals(readyAt, cursor.get().cursorHigh(),
            "cursor_high must be unchanged across the idempotent re-run");
        assertEquals(postId.toString(), cursor.get().cursorLowId(),
            "cursor_low_id must be unchanged across the idempotent re-run");
    }

    private void seedReadyRow(UUID postId, Instant readyAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (id, uid, source_id, title, status, fetched_at, ready_at) "
                     + "VALUES (?, ?, ?, ?, 'READY', ?, ?)")) {
            ps.setObject(1, postId);
            ps.setString(2, TEST_UID_PREFIX + postId);
            ps.setObject(3, testSourceId);
            ps.setString(4, "reconcile-reconnect-it post");
            ps.setTimestamp(5, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(6, Timestamp.from(readyAt));
            ps.executeUpdate();
        }
    }

    private void awaitCursor(Predicate<ProviderStateDao.Cursor> condition, String failureMessage)
            throws Exception {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<ProviderStateDao.Cursor> c =
                providerStateDao.readCursor(NewPostHandler.CHANNEL_NEW_POST);
            if (c.isPresent() && condition.test(c.get())) {
                return;
            }
            Thread.sleep(AWAIT_POLL.toMillis());
        }
        fail(failureMessage);
    }

    private void clearTestPosts() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM post WHERE uid LIKE ?")) {
            ps.setString(1, TEST_UID_PREFIX + "%");
            ps.executeUpdate();
        }
    }

    private void clearAllItPosts() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM post WHERE uid LIKE '%-it/%'")) {
            ps.executeUpdate();
        }
    }

    private void resetNewPostCursor() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE provider_state "
                     + "   SET cursor_high = 'epoch'::TIMESTAMPTZ, "
                     + "       cursor_low_kind = '', "
                     + "       cursor_low_id = '', "
                     + "       updated_at = now() "
                     + " WHERE channel = 'new_post'")) {
            ps.executeUpdate();
        }
    }

    private UUID ensureTestSource() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category) "
                     + "VALUES ('rss', 'reconcile-reconnect-it://test', 'reconcile-reconnect-it', 'news') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE "
                     + "SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "test source upsert must yield an id");
                return rs.getObject("id", UUID.class);
            }
        }
    }
}
