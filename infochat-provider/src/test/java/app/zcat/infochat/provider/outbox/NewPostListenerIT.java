package app.zcat.infochat.provider.outbox;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration test for {@link NewPostListener}: emits real
 * {@code pg_notify('new_post', …)} from a separate JDBC connection and
 * asserts the live listener wakes, dispatches to {@link NewPostHandler},
 * and advances the cursor — exercising the LISTEN side and the JDBC
 * notification surface end-to-end (no in-process mocks).
 *
 * <ul>
 *   <li>NOTIFY → handler dispatch → cursor advances to the payload's
 *       {@code (ready_at, post_id)}.</li>
 *   <li>The payload's parsed values flow through to the cursor verbatim
 *       (round-trip integrity).</li>
 *   <li>A duplicate NOTIFY whose cursor is {@code <=} the stored cursor
 *       produces NO additional handler side effect — the CAS no-op
 *       rejects the duplicate at the cursor level, satisfying the
 *       idempotency promise from docs/spec/architecture.md
 *       §Inter-service communication §Catch-up.</li>
 * </ul>
 */
@QuarkusTest
class NewPostListenerIT {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration AWAIT_POLL = Duration.ofMillis(50);

    /**
     * Marker prefix for test-seeded {@code post} rows so each test
     * starts from a clean slate. The fixture rows exist solely to
     * satisfy advisory #1's existence check on
     * {@link NewPostHandler#handle} — without them the check correctly
     * rejects every NOTIFY this IT emits and the cursor never advances.
     * Per M1-030 Authorized test changes, this seeding is the
     * fixture-only modification authorized for this IT.
     */
    private static final String TEST_UID_PREFIX = "listener-it/";

    private static final Instant FETCHED_AT = Instant.parse("2026-05-15T12:00:00Z");

    @Inject
    DataSource dataSource;

    @Inject
    NewPostListener newPostListener;

    @Inject
    ProviderStateDao providerStateDao;

    private UUID testSourceId;

    @BeforeEach
    void resetCursor() throws Exception {
        assertTrue(newPostListener.isWorkerAlive(),
            "the production @Startup listener worker thread must be running before each test");
        // Broad cleanup so live-listener cursor advances are deterministic
        // regardless of which IT in this module seeded READY rows previously.
        clearAllItPosts();
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
        testSourceId = ensureTestSource();
    }

    @AfterEach
    void clearListenerItPosts() throws Exception {
        clearTestPosts();
    }

    @Test
    void notifyWakesListenerAndCursorAdvancesToPayload() throws Exception {
        UUID postId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        Instant readyAt = Instant.parse("2026-05-15T14:00:00Z");
        seedReadyRow(postId, readyAt);

        emitNewPostNotify(readyAt, postId);

        awaitCursor(c -> readyAt.equals(c.cursorHigh()) && postId.toString().equals(c.cursorLowId()),
            "cursor must advance to the payload's (ready_at, post_id) within " + AWAIT_TIMEOUT);

        Optional<ProviderStateDao.Cursor> cursor =
            providerStateDao.readCursor(NewPostHandler.CHANNEL_NEW_POST);
        assertTrue(cursor.isPresent(), "cursor row must exist");
        assertEquals(readyAt, cursor.get().cursorHigh());
        assertEquals(postId.toString(), cursor.get().cursorLowId());
        assertEquals(NewPostHandler.CURSOR_LOW_KIND_POST, cursor.get().cursorLowKind(),
            "cursor_low_kind must upgrade from '' to 'post' on the first real event");
    }

    @Test
    void duplicateNotifyIsRejectedByCasNoOpAtCursorLevel() throws Exception {
        UUID postId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        Instant readyAt = Instant.parse("2026-05-15T15:00:00Z");
        seedReadyRow(postId, readyAt);

        emitNewPostNotify(readyAt, postId);
        awaitCursor(c -> readyAt.equals(c.cursorHigh()) && postId.toString().equals(c.cursorLowId()),
            "cursor must advance after the first NOTIFY");

        Instant firstUpdatedAt = readUpdatedAt();

        // Re-emit the SAME payload. The CAS UPDATE's strict-< predicate must
        // be a no-op — cursor is already at this exact (ready_at, post_id).
        emitNewPostNotify(readyAt, postId);

        // Give the listener a chance to receive the duplicate and either no-op
        // or (incorrectly) mutate the cursor. Poll for stability over a short
        // window — if the cursor stayed at the same value AND updated_at did
        // not change, the CAS no-op fired as expected.
        Thread.sleep(500);

        Optional<ProviderStateDao.Cursor> cursor =
            providerStateDao.readCursor(NewPostHandler.CHANNEL_NEW_POST);
        assertTrue(cursor.isPresent());
        assertEquals(readyAt, cursor.get().cursorHigh(),
            "cursor_high must be unchanged after duplicate NOTIFY");
        assertEquals(postId.toString(), cursor.get().cursorLowId(),
            "cursor_low_id must be unchanged after duplicate NOTIFY");

        Instant secondUpdatedAt = readUpdatedAt();
        assertEquals(firstUpdatedAt, secondUpdatedAt,
            "updated_at must NOT advance on a CAS no-op — proves the UPDATE matched zero rows");
    }

    @Test
    void earlierNotifyAfterAdvanceIsAlsoCasNoOp() throws Exception {
        UUID firstPostId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        Instant firstReadyAt = Instant.parse("2026-05-15T16:00:00Z");
        seedReadyRow(firstPostId, firstReadyAt);

        emitNewPostNotify(firstReadyAt, firstPostId);
        awaitCursor(c -> firstReadyAt.equals(c.cursorHigh()),
            "cursor must advance after the first NOTIFY");

        // Out-of-order arrival: an earlier ready_at must be rejected by the
        // CAS predicate (strict-< on the compound tuple). This guards against
        // the failure mode where NOTIFY delivery order diverges from
        // ready_at order (e.g. two ingest workers committing in non-monotonic
        // order).
        UUID earlierPostId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        Instant earlierReadyAt = firstReadyAt.minus(5, ChronoUnit.SECONDS);
        seedReadyRow(earlierPostId, earlierReadyAt);

        emitNewPostNotify(earlierReadyAt, earlierPostId);
        Thread.sleep(500);

        Optional<ProviderStateDao.Cursor> cursor =
            providerStateDao.readCursor(NewPostHandler.CHANNEL_NEW_POST);
        assertTrue(cursor.isPresent());
        assertEquals(firstReadyAt, cursor.get().cursorHigh(),
            "earlier-ready_at NOTIFY must NOT roll the cursor back");
        assertEquals(firstPostId.toString(), cursor.get().cursorLowId(),
            "cursor_low_id must still point at the first post — the second NOTIFY was a CAS no-op");
    }

    /**
     * Seeds a real READY {@code post} row matching the supplied
     * {@code (postId, readyAt)} pair so advisory #1's existence check
     * on {@link NewPostHandler#handle} accepts the NOTIFY this test
     * subsequently emits. Without the seeded row, the handler
     * (correctly) rejects the payload and the cursor never advances.
     * The fixture rows are deleted in {@link #resetCursor()}'s
     * {@code clearTestPosts} call between tests.
     */
    private void seedReadyRow(UUID postId, Instant readyAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (id, uid, source_id, title, status, fetched_at, ready_at) "
                     + "VALUES (?, ?, ?, ?, 'READY', ?, ?)")) {
            ps.setObject(1, postId);
            ps.setString(2, TEST_UID_PREFIX + postId);
            ps.setObject(3, testSourceId);
            ps.setString(4, "listener-it post " + postId);
            ps.setTimestamp(5, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(6, Timestamp.from(readyAt));
            ps.executeUpdate();
        }
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

    private UUID ensureTestSource() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category) "
                     + "VALUES ('rss', 'listener-it://test', 'listener-it', 'news') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE "
                     + "SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "test source upsert must yield an id");
                return rs.getObject("id", UUID.class);
            }
        }
    }

    private void emitNewPostNotify(Instant readyAt, UUID postId) throws Exception {
        // pg_notify(channel, payload) is the SQL-callable form. The JDBC
        // backend session this query runs on is DIFFERENT from the
        // listener's dedicated session, so the notification flows through
        // the Postgres backend to the listener exactly as a Collector-side
        // emit would in production.
        String payload = "{\"ready_at\":\"" + readyAt.toString()
            + "\",\"post_id\":\"" + postId + "\"}";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT pg_notify('new_post', ?)")) {
            ps.setString(1, payload);
            ps.executeQuery().close();
        }
    }

    private Instant readUpdatedAt() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT updated_at FROM provider_state WHERE channel = 'new_post'");
             var rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getTimestamp("updated_at").toInstant();
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
}
