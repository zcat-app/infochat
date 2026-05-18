package app.zcat.infochat.provider.outbox;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration test for the M1-030 advisory #3 hardening on
 * {@link NewPostListener}: a closed/severed listen connection must
 * trigger a reconnect + re-issue of {@code LISTEN new_post}, not a
 * silent stall.
 *
 * <p>Flow:
 *
 * <ol>
 *   <li>Verify the worker is alive at test start.</li>
 *   <li>Force-close the LISTEN connection via
 *       {@link NewPostListener#closeListenConnectionForTest()} — the
 *       package-private test hook that simulates a Postgres restart
 *       or network blip without needing DevServices container
 *       control.</li>
 *   <li>Wait briefly for the worker to detect the closure and
 *       reconnect (the runLoop's initial backoff is 1s, so 3s leaves
 *       generous slack).</li>
 *   <li>Seed a real READY {@code post} row (so advisory #1's
 *       existence check accepts the post-reconnect NOTIFY).</li>
 *   <li>Emit a fresh NOTIFY from a SEPARATE JDBC connection — a NOTIFY
 *       fired during the reconnect window would have been dropped by
 *       Postgres because no session was subscribed, so the test must
 *       wait until after reconnect before emitting.</li>
 *   <li>Await the cursor advance to the post-reconnect payload within
 *       a 30s Awaitility window — proves the listener received the
 *       notification on the new session, which is only possible if
 *       {@code LISTEN new_post} was re-issued.</li>
 * </ol>
 */
@QuarkusTest
class NewPostListenerReconnectIT {

    private static final String TEST_UID_PREFIX = "reconnect-it/";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration AWAIT_POLL = Duration.ofMillis(100);

    /**
     * Window for the worker to detect the forced disconnect and
     * reconnect. The runLoop's initial backoff is 1s and a healthy
     * reconnect takes well under a second; 3s leaves slack for the
     * one-cycle getNotifications timeout that may fire before the
     * disconnect is observed.
     */
    private static final long RECONNECT_WAIT_MS = 3000;

    @Inject
    DataSource dataSource;

    @Inject
    NewPostListener newPostListener;

    @Inject
    ProviderStateDao providerStateDao;

    private UUID testSourceId;

    @BeforeEach
    void setUp() throws Exception {
        // Broad cleanup so the live listener's catch-up cursor reset is
        // deterministic regardless of which IT seeded rows previously.
        clearAllItPosts();
        resetNewPostCursor();
        testSourceId = ensureTestSource();
    }

    @AfterEach
    void tearDown() throws Exception {
        clearTestPosts();
    }

    @Test
    void listenerReconnectsAndReceivesPostReconnectNotify() throws Exception {
        assertTrue(newPostListener.isWorkerAlive(),
            "worker must be alive at test start");

        newPostListener.closeListenConnectionForTest();
        Thread.sleep(RECONNECT_WAIT_MS);

        assertTrue(newPostListener.isWorkerAlive(),
            "worker must remain alive after the forced disconnect — reconnect path should be transparent");

        UUID postId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        Instant readyAt = Instant.parse("2026-05-15T22:00:00Z");
        seedReadyRow(postId, readyAt);

        emitNewPostNotify(readyAt, postId);

        awaitCursor(
            c -> readyAt.equals(c.cursorHigh()) && postId.toString().equals(c.cursorLowId()),
            "cursor must advance to the post-reconnect NOTIFY's payload within " + AWAIT_TIMEOUT
                + " — if it never advances, the reconnect did not re-issue LISTEN new_post");
    }

    private void seedReadyRow(UUID postId, Instant readyAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (id, uid, source_id, title, status, fetched_at, ready_at) "
                     + "VALUES (?, ?, ?, ?, 'READY', ?, ?)")) {
            ps.setObject(1, postId);
            ps.setString(2, TEST_UID_PREFIX + postId);
            ps.setObject(3, testSourceId);
            ps.setString(4, "reconnect-it post");
            ps.setTimestamp(5, Timestamp.from(Instant.parse("2026-05-15T12:00:00Z")));
            ps.setTimestamp(6, Timestamp.from(readyAt));
            ps.executeUpdate();
        }
    }

    private void emitNewPostNotify(Instant readyAt, UUID postId) throws Exception {
        String payload = "{\"ready_at\":\"" + readyAt.toString()
            + "\",\"post_id\":\"" + postId + "\"}";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT pg_notify('new_post', ?)")) {
            ps.setString(1, payload);
            ps.executeQuery().close();
        }
    }

    private void awaitCursor(Predicate<ProviderStateDao.Cursor> condition, String failureMessage)
            throws Exception {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<ProviderStateDao.Cursor> c =
                providerStateDao.readCursor(NewPostHandler.CHANNEL_NEW_POST);
            assertNotNull(c);
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
                     + "VALUES ('rss', 'reconnect-it://test', 'reconnect-it', 'news') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE "
                     + "SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "test source upsert must yield an id");
                return rs.getObject("id", UUID.class);
            }
        }
    }
}
