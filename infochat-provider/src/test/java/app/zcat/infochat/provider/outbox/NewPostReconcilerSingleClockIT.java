package app.zcat.infochat.provider.outbox;

import app.zcat.infochat.provider.testsupport.SeedDataSource;
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
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the single-clock {@code ready_at} timeline the
 * {@code new_post} cursor depends on. Every production {@code ready_at}
 * writer (the Collector's {@code ReadyPromoter} UPDATE and the
 * {@code approve_quarantine} SQL function) stamps from the DB's
 * {@code now()}, so the {@code (ready_at, id)} values the cursor and the
 * catch-up scan compare all come from ONE ordered timeline.
 *
 * <p><b>The skip scenario.</b> With two clocks, a Collector promotion
 * stamped by a skewed JVM clock could land a row whose {@code ready_at}
 * is BELOW the cursor the Provider had already advanced from a
 * DB-clocked event; the catch-up scan's strict
 * {@code (ready_at, id) > cursor} lower bound would then never select
 * it — a permanently skipped event. The first test pins the property
 * that closes this: a writer that stamps after the cursor's source
 * event necessarily lands at-or-above the cursor on the single
 * timeline, so the row stays findable.
 *
 * <p><b>The residual.</b> {@code now()} is transaction-START time, so
 * commit-order inversion remains possible on a single clock: a writer
 * transaction that begins before another's but commits after it
 * publishes a {@code ready_at} below an already-advanced cursor. The
 * second test pins that residual as KNOWN behavior so the ticket that
 * builds the real {@code new_post} consumer inherits the analysis
 * (mitigations: a lag-window scan lower bound and/or per-post dedupe)
 * and consciously flips the assertion.
 */
@QuarkusTest
class NewPostReconcilerSingleClockIT {

    /** Marker prefix so test-seeded post rows are deletable between runs. */
    private static final String TEST_UID_PREFIX = "single-clock-it/";

    /**
     * Fetched-at value chosen to land in V7's bootstrap partition
     * ({@code post_202605}) — same constant shape as the sibling
     * reconciler ITs. {@code fetched_at} is the partition key;
     * {@code ready_at} is not, so the DB-clocked {@code now()} values
     * the tests seed are unconstrained by the partition window.
     */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-15T12:00:00Z");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    NewPostReconciler newPostReconciler;

    @Inject
    ProviderStateDao providerStateDao;

    private UUID testSourceId;

    @BeforeEach
    void setUp() throws Exception {
        // Broad cleanup: catch-up scans every READY row in the DB, so
        // leftover fixture rows from any prior IT in this module would
        // inflate caughtUpCount() above the locally-seeded count. All
        // outbox ITs use the `<name>-it/` uid convention.
        clearAllItPosts();
        resetNewPostCursor();
        testSourceId = ensureTestSource();
    }

    @AfterEach
    void tearDown() throws Exception {
        clearTestPosts();
    }

    @Test
    void rowPromotedAfterCursorAdvanceLandsAboveTheCursorAndIsFound() throws Exception {
        // Advance the cursor from a first DB-clocked event — the role the
        // approve_quarantine path played in the skip scenario.
        SeededRow cursorSource = seedDbClockedReadyRow("cursor-source");
        newPostReconciler.runCatchUp();
        ProviderStateDao.Cursor cursor =
            providerStateDao.readCursor(NewPostHandler.CHANNEL_NEW_POST).orElseThrow();
        assertEquals(cursorSource.readyAt(), cursor.cursorHigh(),
            "cursor must be advanced to the first DB-clocked row before the scenario row is written");

        // Second DB-clocked writer in a strictly later transaction — the
        // row that a skewed JVM clock could previously have stamped below
        // the cursor. The two autocommit INSERTs are separated by full
        // JDBC round-trips, so their transaction-start times differ by far
        // more than now()'s microsecond resolution.
        SeededRow laterWriter = seedDbClockedReadyRow("later-writer");
        assertTrue(laterWriter.readyAt().isAfter(cursor.cursorHigh()),
            "single clock: a later writer's DB-assigned ready_at must land above the advanced cursor");

        newPostReconciler.runCatchUp();
        assertEquals(1, newPostReconciler.caughtUpCount(),
            "the later DB-clocked row must be findable by the catch-up scan");
        ProviderStateDao.Cursor after =
            providerStateDao.readCursor(NewPostHandler.CHANNEL_NEW_POST).orElseThrow();
        assertEquals(laterWriter.readyAt(), after.cursorHigh(),
            "cursor_high must advance to the found row's ready_at");
        assertEquals(laterWriter.id().toString(), after.cursorLowId(),
            "cursor_low_id must advance to the found row's id");
    }

    @Test
    void commitOrderInversionRemainsADocumentedResidualSkip() throws Exception {
        SeededRow cursorSource = seedDbClockedReadyRow("advanced");
        newPostReconciler.runCatchUp();
        assertEquals(1, newPostReconciler.caughtUpCount(),
            "cursor must be advanced from the first row before the inverted row is written");

        // Simulate the inverted commit order: a row published with a
        // ready_at BELOW the already-advanced cursor (its transaction
        // started before the cursor source's but committed after).
        seedReadyRowAt("inverted", cursorSource.readyAt().minusSeconds(1));

        newPostReconciler.runCatchUp();
        assertEquals(0, newPostReconciler.caughtUpCount(),
            "a below-cursor row is invisible to the strict (ready_at, id) > cursor lower bound — "
                + "the documented residual the future consumer ticket must close");
        ProviderStateDao.Cursor after =
            providerStateDao.readCursor(NewPostHandler.CHANNEL_NEW_POST).orElseThrow();
        assertEquals(cursorSource.readyAt(), after.cursorHigh(),
            "cursor must be unchanged by the skipped below-cursor row");
    }

    /**
     * Seeds a READY row whose {@code ready_at} is assigned by the DB's
     * {@code now()} inside the INSERT — the same clock source both
     * production writers use.
     */
    private SeededRow seedDbClockedReadyRow(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (uid, source_id, title, status, fetched_at, ready_at) "
                     + "VALUES (?, ?, ?, 'READY', ?, now()) "
                     + "RETURNING id, ready_at")) {
            ps.setString(1, TEST_UID_PREFIX + slug + "-" + UUID.randomUUID());
            ps.setObject(2, testSourceId);
            ps.setString(3, "single-clock-it post " + slug);
            ps.setTimestamp(4, Timestamp.from(FETCHED_AT));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "INSERT … RETURNING must yield the new row");
                return new SeededRow(
                    rs.getObject("id", UUID.class),
                    rs.getTimestamp("ready_at").toInstant());
            }
        }
    }

    private void seedReadyRowAt(String slug, Instant readyAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (uid, source_id, title, status, fetched_at, ready_at) "
                     + "VALUES (?, ?, ?, 'READY', ?, ?)")) {
            ps.setString(1, TEST_UID_PREFIX + slug + "-" + UUID.randomUUID());
            ps.setObject(2, testSourceId);
            ps.setString(3, "single-clock-it post " + slug);
            ps.setTimestamp(4, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(5, Timestamp.from(readyAt));
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
                     + "VALUES ('rss', 'single-clock-it://test', 'single-clock-it', 'news') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE "
                     + "SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "test source upsert must yield an id");
                return rs.getObject("id", UUID.class);
            }
        }
    }

    private record SeededRow(UUID id, Instant readyAt) {}
}
