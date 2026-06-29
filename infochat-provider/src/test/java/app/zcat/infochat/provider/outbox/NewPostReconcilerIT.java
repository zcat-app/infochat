package app.zcat.infochat.provider.outbox;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.testsupport.OutboxItFixtures;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link NewPostReconciler}: seeds READY {@code post}
 * rows with controlled {@code (ready_at, id)} values, invokes the catch-up
 * scan, and verifies:
 *
 * <ul>
 *   <li>Every seeded READY row is dispatched to {@link NewPostHandler}
 *       exactly once — the cursor monotonically advances through the
 *       seeded values in {@code (ready_at, id)} order (per the catch-up
 *       {@code SELECT … ORDER BY ready_at, id} from
 *       docs/design/02-schema.md §2.9.2).</li>
 *   <li>The final {@code provider_state} cursor matches the highest
 *       seeded {@code (ready_at, id)} pair.</li>
 *   <li>Re-running the reconciler against the now-advanced cursor is a
 *       no-op: zero additional rows are processed — the idempotency
 *       promise from docs/spec/architecture.md §Inter-service
 *       communication §Catch-up ("a duplicate NOTIFY or a repeated
 *       catch-up pass for the same row produces no additional side
 *       effect").</li>
 * </ul>
 */
@QuarkusTest
class NewPostReconcilerIT {

    /** Marker prefix so test-seeded post rows are deletable between runs. */
    private static final String TEST_UID_PREFIX = "reconciler-it/";

    /**
     * Fetched-at value chosen to land in V7's bootstrap partition
     * ({@code post_202605}). Holding it constant keeps the test
     * deterministic regardless of wall-clock drift inside the partition
     * window.
     */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-15T12:00:00Z");

    /**
     * Baseline {@code ready_at} for the seeded rows. Subsequent rows
     * advance this by {@code i} seconds to give the catch-up scan a
     * deterministic order on the {@code (ready_at, id)} tuple.
     */
    private static final Instant READY_AT_BASE = Instant.parse("2026-05-15T13:00:00Z");

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
        clearTestPosts();
        resetNewPostCursor();
        testSourceId = ensureTestSource();
    }

    @Test
    void catchUpProcessesEveryReadyRowAndCursorAdvancesToLast() throws Exception {
        List<SeededRow> seeded = seedReadyRows(5);

        newPostReconciler.runCatchUp();

        assertEquals(5, newPostReconciler.caughtUpCount(),
            "reconciler must process every seeded READY row exactly once");

        SeededRow last = seeded.get(seeded.size() - 1);
        Optional<ProviderStateDao.Cursor> cursor =
            providerStateDao.readCursor(NewPostHandler.CHANNEL_NEW_POST);
        assertTrue(cursor.isPresent(), "cursor row must exist after catch-up");
        assertEquals(last.readyAt(), cursor.get().cursorHigh(),
            "cursor_high must advance to the last seeded row's ready_at");
        assertEquals(last.id().toString(), cursor.get().cursorLowId(),
            "cursor_low_id must advance to the last seeded row's post.id");
        assertEquals(NewPostHandler.CURSOR_LOW_KIND_POST, cursor.get().cursorLowKind(),
            "cursor_low_kind must be 'post' after the first real event");
    }

    @Test
    void cursorAdvancesMonotonicallyEvenWhenSeededOutOfOrder() throws Exception {
        // Insert rows in REVERSE ready_at order so the catch-up's ORDER BY
        // is exercised: if the scan returned rows in insertion order, the
        // per-row CAS would reject the later rows as "earlier" and the
        // cursor would land at the first seeded row's value, not the last.
        List<SeededRow> seeded = seedReadyRowsReversed(4);

        newPostReconciler.runCatchUp();

        // The final cursor must reflect the chronologically LAST row (highest
        // ready_at), regardless of insertion order — the catch-up SQL's
        // ORDER BY ready_at, id is what guarantees this.
        SeededRow latest = seeded.stream()
            .max((a, b) -> a.readyAt().compareTo(b.readyAt()))
            .orElseThrow();
        Optional<ProviderStateDao.Cursor> cursor =
            providerStateDao.readCursor(NewPostHandler.CHANNEL_NEW_POST);
        assertTrue(cursor.isPresent());
        assertEquals(latest.readyAt(), cursor.get().cursorHigh(),
            "cursor_high must end at the chronologically latest ready_at, not at insertion order");
        assertEquals(4, newPostReconciler.caughtUpCount(),
            "every seeded row must still be processed exactly once");
    }

    @Test
    void reRunningTheReconcilerIsAnIdempotentNoOp() throws Exception {
        List<SeededRow> seeded = seedReadyRows(3);

        newPostReconciler.runCatchUp();
        assertEquals(3, newPostReconciler.caughtUpCount(), "first run processes the seeded rows");

        Optional<ProviderStateDao.Cursor> afterFirst =
            providerStateDao.readCursor(NewPostHandler.CHANNEL_NEW_POST);
        assertTrue(afterFirst.isPresent());

        // Second invocation — no new rows have been seeded; the cursor is at
        // the last seeded row; the catch-up SELECT's `(ready_at, id) >` clause
        // matches zero rows, so the handler is never called.
        newPostReconciler.runCatchUp();
        assertEquals(0, newPostReconciler.caughtUpCount(),
            "re-run must process zero rows — the cursor is already at the last seeded row");

        // Cursor unchanged across the second run.
        Optional<ProviderStateDao.Cursor> afterSecond =
            providerStateDao.readCursor(NewPostHandler.CHANNEL_NEW_POST);
        assertTrue(afterSecond.isPresent());
        assertEquals(afterFirst.get().cursorHigh(), afterSecond.get().cursorHigh(),
            "cursor_high must be unchanged across the idempotent re-run");
        assertEquals(afterFirst.get().cursorLowId(), afterSecond.get().cursorLowId(),
            "cursor_low_id must be unchanged across the idempotent re-run");

        SeededRow last = seeded.get(seeded.size() - 1);
        assertEquals(last.readyAt(), afterSecond.get().cursorHigh(),
            "cursor still pins to the highest seeded row after the no-op re-run");
    }

    private List<SeededRow> seedReadyRows(int n) throws Exception {
        List<SeededRow> result = new ArrayList<>(n);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (uid, source_id, title, status, fetched_at, ready_at, "
                     + "upstream_identifier) "
                     + "VALUES (?, ?, ?, 'READY', ?, ?, ?) "
                     + "RETURNING id")) {
            for (int i = 0; i < n; i++) {
                Instant readyAt = READY_AT_BASE.plus(i, ChronoUnit.SECONDS);
                String uid = TEST_UID_PREFIX + i + "-" + UUID.randomUUID();
                ps.setString(1, uid);
                ps.setObject(2, testSourceId);
                ps.setString(3, "reconciler-it post " + i);
                ps.setTimestamp(4, Timestamp.from(FETCHED_AT));
                ps.setTimestamp(5, Timestamp.from(readyAt));
                ps.setString(6, uid);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "INSERT … RETURNING must yield the new id");
                    UUID id = rs.getObject("id", UUID.class);
                    result.add(new SeededRow(id, readyAt));
                }
            }
        }
        return result;
    }

    private List<SeededRow> seedReadyRowsReversed(int n) throws Exception {
        List<SeededRow> result = new ArrayList<>(n);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (uid, source_id, title, status, fetched_at, ready_at, "
                     + "upstream_identifier) "
                     + "VALUES (?, ?, ?, 'READY', ?, ?, ?) "
                     + "RETURNING id")) {
            for (int i = n - 1; i >= 0; i--) {
                Instant readyAt = READY_AT_BASE.plus(i, ChronoUnit.SECONDS);
                String uid = TEST_UID_PREFIX + "rev-" + i + "-" + UUID.randomUUID();
                ps.setString(1, uid);
                ps.setObject(2, testSourceId);
                ps.setString(3, "reconciler-it reversed post " + i);
                ps.setTimestamp(4, Timestamp.from(FETCHED_AT));
                ps.setTimestamp(5, Timestamp.from(readyAt));
                ps.setString(6, uid);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    UUID id = rs.getObject("id", UUID.class);
                    result.add(new SeededRow(id, readyAt));
                }
            }
        }
        return result;
    }

    private void clearTestPosts() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM post WHERE uid LIKE ?")) {
            ps.setString(1, TEST_UID_PREFIX + "%");
            ps.executeUpdate();
        }
    }

    private void resetNewPostCursor() throws Exception {
        OutboxItFixtures.resetNewPostCursor(dataSource);
    }

    private UUID ensureTestSource() throws Exception {
        return OutboxItFixtures.ensureTestSource(dataSource, "reconciler-it://test", "reconciler-it");
    }

    private record SeededRow(UUID id, Instant readyAt) {}
}
