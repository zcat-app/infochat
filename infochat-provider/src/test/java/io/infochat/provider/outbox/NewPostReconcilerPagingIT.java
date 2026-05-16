package io.infochat.provider.outbox;

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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the M1-030 advisory #2 hardening on
 * {@link NewPostReconciler}: the catch-up scan must page so a long
 * backlog does not block {@code @Startup} arbitrarily. Seeds more
 * READY {@code post} rows than the default page size and asserts:
 *
 * <ul>
 *   <li>Every seeded row is processed across multiple pages.</li>
 *   <li>{@link NewPostReconciler#pagesProcessed()} reports {@code > 1}
 *       — the loop actually paged rather than serving the entire
 *       backlog in one un-paged SELECT.</li>
 *   <li>The final {@code provider_state} cursor matches the
 *       chronologically last seeded row.</li>
 *   <li>Re-running the reconciler with the cursor at the last row is
 *       a no-op (zero handler invocations, zero pages of work — only
 *       the empty terminator page).</li>
 * </ul>
 *
 * <p>Uses the production default page size of 500 (the
 * {@code infochat.provider.catchup.page-size} property's
 * {@code @ConfigProperty(defaultValue = "500")} on
 * {@code NewPostReconciler.pageSize}). Seeding {@code ROWS_SEEDED}
 * = 1200 forces three full pages (500, 500, 200).
 */
@QuarkusTest
class NewPostReconcilerPagingIT {

    private static final String TEST_UID_PREFIX = "paging-it/";
    private static final Instant FETCHED_AT = Instant.parse("2026-05-15T12:00:00Z");
    private static final Instant READY_AT_BASE = Instant.parse("2026-05-15T20:00:00Z");

    /**
     * Rows seeded for each paging test. Chosen to exceed the production
     * default page size (500) by enough that the catch-up loop must
     * page at least twice — proving paging happens — without making
     * each test wall-clock-dominant.
     */
    private static final int ROWS_SEEDED = 1200;

    @Inject
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
        // inflate caughtUpCount() above the locally-seeded count. Each
        // of the M1-030 ITs and the M1-027 NewPostReconcilerIT use the
        // `<name>-it/` uid convention so a single LIKE is sufficient.
        clearAllItPosts();
        resetNewPostCursor();
        testSourceId = ensureTestSource();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Narrow cleanup: leave the DB clean for the next test class so
        // NewPostReconcilerIT (M1-027, narrow @BeforeEach prefix) is not
        // contaminated by this IT's 1200-row paging fixture.
        clearTestPosts();
    }

    @Test
    void scanProcessesEverySeededRowAcrossMultiplePages() throws Exception {
        seedReadyRows(ROWS_SEEDED);

        newPostReconciler.runCatchUp();

        assertEquals(ROWS_SEEDED, newPostReconciler.caughtUpCount(),
            "every seeded READY row must be processed exactly once across pages");
    }

    @Test
    void cursorAdvancesAcrossPageBoundaries() throws Exception {
        seedReadyRows(ROWS_SEEDED);

        newPostReconciler.runCatchUp();

        // 1200 rows with the default pageSize=500 yields three pages
        // (500, 500, 200). >=2 captures the "paged vs single-scan"
        // distinction without brittleness if ROWS_SEEDED is later
        // tuned to a different multiple.
        assertTrue(newPostReconciler.pagesProcessed() >= 2,
            "catch-up must page (pagesProcessed observed=" + newPostReconciler.pagesProcessed() + ")");
    }

    @Test
    void finalCursorMatchesLastSeededRowByReadyAt() throws Exception {
        SeededRow last = seedReadyRows(ROWS_SEEDED);

        newPostReconciler.runCatchUp();

        ProviderStateDao.Cursor cursor = providerStateDao
            .readCursor(NewPostHandler.CHANNEL_NEW_POST).orElseThrow();
        assertEquals(last.readyAt(), cursor.cursorHigh(),
            "final cursor_high must equal the last seeded row's ready_at — proves the scan ran to completion");
        assertEquals(last.id().toString(), cursor.cursorLowId(),
            "final cursor_low_id must equal the last seeded row's id");
        assertEquals(NewPostHandler.CURSOR_LOW_KIND_POST, cursor.cursorLowKind(),
            "cursor_low_kind must be 'post' after the catch-up consumed real events");
    }

    @Test
    void reRunWithCursorAtLastRowIsAnIdempotentNoOp() throws Exception {
        seedReadyRows(ROWS_SEEDED);

        newPostReconciler.runCatchUp();
        assertEquals(ROWS_SEEDED, newPostReconciler.caughtUpCount(),
            "first run processes every seeded row");

        // Second run: cursor is at the last row's (ready_at, id); the
        // paging SELECT's `(ready_at, id) > cursor` predicate matches
        // zero rows, so the loop issues one empty page and exits. The
        // handler is never called.
        newPostReconciler.runCatchUp();
        assertEquals(0, newPostReconciler.caughtUpCount(),
            "second run must process zero rows — cursor already at the last seeded row");
    }

    private SeededRow seedReadyRows(int n) throws Exception {
        SeededRow last = null;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (uid, source_id, title, status, fetched_at, ready_at) "
                     + "VALUES (?, ?, ?, 'READY', ?, ?) RETURNING id")) {
            for (int i = 0; i < n; i++) {
                Instant readyAt = READY_AT_BASE.plus(i, ChronoUnit.SECONDS);
                ps.setString(1, TEST_UID_PREFIX + i + "-" + UUID.randomUUID());
                ps.setObject(2, testSourceId);
                ps.setString(3, "paging-it post " + i);
                ps.setTimestamp(4, Timestamp.from(FETCHED_AT));
                ps.setTimestamp(5, Timestamp.from(readyAt));
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "INSERT … RETURNING must yield the new id");
                    UUID id = rs.getObject("id", UUID.class);
                    last = new SeededRow(id, readyAt);
                }
            }
        }
        return last;
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
                     + "VALUES ('rss', 'paging-it://test', 'paging-it', 'news') "
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
