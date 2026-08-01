package app.zcat.infochat.collector.eval.stage2;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static app.zcat.infochat.collector.testsupport.PgNotifyFixture.awaitNotifications;
import static app.zcat.infochat.collector.testsupport.PgNotifyFixture.listenTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-739: a first-pass non-BENIGN verdict (INJECTION / MALWARE /
 * UNKNOWN) on a post with NO PENDING quarantine row inserts one
 * whole-body {@code flagged_by='stage2'} PENDING row in the same
 * transaction as the post UPDATE, with the same
 * {@code quarantine_review} NOTIFY the Stage 1 insert emits — so the
 * QUARANTINED post enters {@code quarantine_review_view} and the
 * {@code /quarantine list} admin queue instead of sitting invisible.
 * Reachable when an admin approves/rejects the Stage 1 rows while the
 * judge call is in flight (the rows are PENDING from the Stage 1
 * commit; the verdict write lands later).
 *
 * <p>The dedup predicate is "no PENDING row", not "no row": a post
 * already carrying PENDING Stage 1 rows gets nothing (those rows
 * already place it in the queue), and a BENIGN_CLOSED row is closed
 * history that does NOT suppress the insert.
 */
@QuarkusTest
class Stage2FirstPassQuarantineRowIT {

    private static final Instant FETCHED_AT = Instant.parse("2026-08-01T10:00:00Z");
    private static final String UID_PREFIX = "stage2-firstpass-row-it/";
    private static final String JUDGED_BODY = "the exact body the judge saw <script>alert(1)</script>";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    Stage2VerdictHandler stage2VerdictHandler;

    @BeforeEach
    void setup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM post WHERE uid LIKE ?", UID_PREFIX + "%");
            // No FK from quarantine to post (denormalized by design) —
            // rows from a prior run must not leak into the counts below.
            exec(conn, "DELETE FROM quarantine WHERE post_uid LIKE ?", UID_PREFIX + "%");
        }
    }

    @Test
    void injectionOnRowlessPostInsertsStage2PendingRowWithNotify() throws Exception {
        SeededPost post = seedRawPost("rowless-insert");

        try (Connection listenConn = dataSource.getConnection()) {
            PGConnection pg = listenTo(listenConn, "quarantine_review");

            stage2VerdictHandler.apply(post.id(), post.fetchedAt(),
                Stage2VerdictHandler.Verdict.INJECTION, JUDGED_BODY);

            assertEquals("QUARANTINED", readPostStatus(post), "the verdict still quarantines the post");
            QuarantineRow row = readSoleRow(post);
            assertEquals("stage2", row.flaggedBy(), "the inserted row is the Stage 2 writer's");
            assertEquals("PENDING", row.status(), "the inserted row awaits admin review");
            assertEquals("stage2_injection", row.ruleId(),
                "first-pass rule_id convention stage2_<verdict> (reeval_<verdict> analogue)");
            assertEquals(0, row.spanStart(), "whole-body span starts at 0");
            assertEquals(JUDGED_BODY.length(), row.spanEnd(), "whole-body span covers the judged body");
            assertEquals(JUDGED_BODY, row.originalHtml(),
                "original_html holds the exact body the judge saw");
            assertNotNull(row.placeholderId(), "placeholder_id satisfies the NOT NULL column");
            assertEquals(post.uid(), row.postUid(), "post_uid denormalized for partition-drop survival");
            assertTrue(visibleInReviewView(row.id()),
                "the row must be projected by quarantine_review_view (the /quarantine list source)");

            PGNotification[] notifications = awaitNotifications(pg, 1);
            assertNotNull(notifications, "the insert must fire the PENDING quarantine_review NOTIFY");
            assertEquals(1, notifications.length, "exactly one NOTIFY for the inserted row");
            String payload = notifications[0].getParameter();
            assertTrue(payload.matches(".*\"new_status\"\\s*:\\s*\"PENDING\".*"),
                "payload must carry new_status=PENDING: " + payload);
            assertTrue(payload.contains("\"target_id\":\"" + row.id() + "\""),
                "payload target_id must be the inserted row: " + payload);
        }
    }

    @Test
    void malwareWithPendingStage1RowInsertsNothing() throws Exception {
        SeededPost post = seedRawPost("dedup-pending");
        UUID stage1RowId = seedQuarantineRow(post, "stage1");

        try (Connection listenConn = dataSource.getConnection()) {
            PGConnection pg = listenTo(listenConn, "quarantine_review");

            stage2VerdictHandler.apply(post.id(), post.fetchedAt(),
                Stage2VerdictHandler.Verdict.MALWARE, JUDGED_BODY);

            assertEquals("QUARANTINED", readPostStatus(post), "the verdict still quarantines the post");
            assertEquals(1, countRows(post),
                "a PENDING Stage 1 row already places the post in the queue — no second row");
            assertEquals("PENDING", readRowStatus(stage1RowId),
                "the Stage 1 row is untouched (no state-machine move on an unsafe verdict)");

            PGNotification[] notifications = pg.getNotifications(500);
            assertTrue(notifications == null || notifications.length == 0,
                "no insert means no NOTIFY: " + java.util.Arrays.toString(notifications));
        }
    }

    @Test
    void closedStage1RowDoesNotSuppressInsert() throws Exception {
        SeededPost post = seedRawPost("closed-history");
        UUID closedRowId = seedQuarantineRow(post, "BENIGN_CLOSED", "stage1");

        stage2VerdictHandler.apply(post.id(), post.fetchedAt(),
            Stage2VerdictHandler.Verdict.UNKNOWN, JUDGED_BODY);

        assertEquals(2, countRows(post),
            "a BENIGN_CLOSED row is closed history — a fresh judgment needs a fresh review row");
        assertEquals("BENIGN_CLOSED", readRowStatus(closedRowId), "the closed row is untouched");
        QuarantineRow inserted = readRowByFlaggedBy(post, "stage2");
        assertEquals("PENDING", inserted.status());
        assertEquals("stage2_unknown", inserted.ruleId(),
            "first-pass rule_id convention stage2_<verdict>");
    }

    @Test
    void rejectCommittingBeforeTheCheckCannotSuppressInsert() throws Exception {
        // Redteam M1-739-2026-08-01 (low): the dedup check is serialized
        // against a concurrent admin review by its FOR UPDATE row lock.
        // Pin the finding's interleaving: the admin tx takes the row lock
        // first, the verdict tx blocks on it, the reject commits, and the
        // unblocked verdict tx must then read zero PENDING rows and insert
        // — it must NOT commit QUARANTINED with no review row.
        SeededPost post = seedRawPost("toctou-reject");
        UUID stage1RowId = seedQuarantineRow(post, "stage1");

        try (Connection adminTx = dataSource.getConnection()) {
            adminTx.setAutoCommit(false);
            lockRowForUpdate(adminTx, stage1RowId);

            CountDownLatch verdictDone = new CountDownLatch(1);
            AtomicReference<Throwable> verdictError = new AtomicReference<>();
            Thread verdictThread = new Thread(() -> {
                try {
                    stage2VerdictHandler.apply(post.id(), post.fetchedAt(),
                        Stage2VerdictHandler.Verdict.INJECTION, JUDGED_BODY);
                } catch (Throwable t) {
                    verdictError.set(t);
                } finally {
                    verdictDone.countDown();
                }
            });
            verdictThread.start();

            // Not load-bearing (a slow thread start passes it vacuously);
            // the final-state assertions below are the pin.
            assertFalse(verdictDone.await(2, TimeUnit.SECONDS),
                "the verdict tx must not commit while the admin holds the row lock");
            exec(adminTx,
                "UPDATE quarantine SET status = 'REJECTED', updated_at = now() WHERE id = ?",
                stage1RowId);
            adminTx.commit();

            assertTrue(verdictDone.await(15, TimeUnit.SECONDS),
                "the verdict tx completes once the admin tx releases the row lock");
            verdictThread.join();
            if (verdictError.get() != null) {
                throw new AssertionError("the verdict tx failed", verdictError.get());
            }
        }

        assertEquals("QUARANTINED", readPostStatus(post));
        assertEquals("REJECTED", readRowStatus(stage1RowId), "the admin reject stands");
        QuarantineRow inserted = readRowByFlaggedBy(post, "stage2");
        assertEquals("PENDING", inserted.status(),
            "the reject committed before the dedup check — the stage2 row must be inserted");
        assertEquals("stage2_injection", inserted.ruleId());
    }

    // ---------- helpers ----------

    private static void lockRowForUpdate(Connection conn, UUID rowId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM quarantine WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, rowId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "seeded row present");
            }
        }
    }

    private @Nullable String readPostStatus(SeededPost post) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM post WHERE id = ? AND fetched_at = ?")) {
            ps.setObject(1, post.id());
            ps.setTimestamp(2, Timestamp.from(post.fetchedAt()));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "seeded post present");
                return rs.getString(1);
            }
        }
    }

    private QuarantineRow readSoleRow(SeededPost post) throws Exception {
        assertEquals(1, countRows(post), "exactly one quarantine row for the post");
        return readRowByFlaggedBy(post, "stage2");
    }

    private QuarantineRow readRowByFlaggedBy(SeededPost post, String flaggedBy) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, post_uid, flagged_by, rule_id, span_start, span_end,"
                     + " original_html, placeholder_id, status FROM quarantine"
                     + " WHERE post_id = ? AND flagged_by = ?")) {
            ps.setObject(1, post.id());
            ps.setString(2, flaggedBy);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "a flagged_by=" + flaggedBy + " row exists");
                return new QuarantineRow(
                    (UUID) rs.getObject("id"),
                    rs.getString("post_uid"),
                    rs.getString("flagged_by"),
                    rs.getString("rule_id"),
                    rs.getInt("span_start"),
                    rs.getInt("span_end"),
                    rs.getString("original_html"),
                    rs.getString("placeholder_id"),
                    rs.getString("status"));
            }
        }
    }

    private int countRows(SeededPost post) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count(*) FROM quarantine WHERE post_id = ?")) {
            ps.setObject(1, post.id());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private @Nullable String readRowStatus(UUID rowId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM quarantine WHERE id = ?")) {
            ps.setObject(1, rowId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "seeded row present");
                return rs.getString(1);
            }
        }
    }

    private boolean visibleInReviewView(UUID rowId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT 1 FROM quarantine_review_view WHERE id = ? AND status = 'PENDING'")) {
            ps.setObject(1, rowId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private UUID seedQuarantineRow(SeededPost post, String flaggedBy) throws Exception {
        return seedQuarantineRow(post, "PENDING", flaggedBy);
    }

    private UUID seedQuarantineRow(SeededPost post, String status, String flaggedBy) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO quarantine (post_id, post_uid, post_fetched_at,"
                     + " flagged_by, rule_id, span_start, span_end, original_html, placeholder_id, status)"
                     + " VALUES (?, ?, ?, ?, 'seed_rule', 0, 1, 'x', ?, ?) RETURNING id")) {
            ps.setObject(1, post.id());
            ps.setString(2, post.uid());
            ps.setTimestamp(3, Timestamp.from(post.fetchedAt()));
            ps.setString(4, flaggedBy);
            ps.setString(5, "seed-ph-" + flaggedBy + "-" + status);
            ps.setString(6, status);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return (UUID) rs.getObject(1);
            }
        }
    }

    private SeededPost seedRawPost(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID sourceId = seedRssSource(conn, slug);
            String uid = UID_PREFIX + slug;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO post (uid, source_id, upstream_identifier, title, body, "
                        + "fetched_at, status, stage1_done, stage1_flagged, tags) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'RAW', TRUE, TRUE, '{}') "
                        + "RETURNING id, fetched_at")) {
                ps.setString(1, uid);
                ps.setObject(2, sourceId);
                ps.setString(3, "firstpass-row-upstream-" + slug);
                ps.setString(4, "First-pass row IT " + slug);
                ps.setString(5, "First-pass row IT body " + slug);
                ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    return new SeededPost((UUID) rs.getObject(1), rs.getTimestamp(2).toInstant(), uid);
                }
            }
        }
    }

    private UUID seedRssSource(Connection conn, String slug) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                    + "VALUES ('rss', ?, ?, 'news', '{ai}') "
                    + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                    + "RETURNING id")) {
            ps.setString(1, "https://firstpass-row-it.example.test/" + slug + "/feed.xml");
            ps.setString(2, "First-pass row IT source " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return (UUID) rs.getObject(1);
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

    private record SeededPost(UUID id, Instant fetchedAt, String uid) {
    }

    private record QuarantineRow(UUID id, String postUid, String flaggedBy, @Nullable String ruleId,
                                 int spanStart, int spanEnd, String originalHtml,
                                 @Nullable String placeholderId, String status) {
    }
}
