package app.zcat.infochat.collector.eval.reeval;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AdminReviewTtlJobTest {

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    AdminReviewTtlJob ttlJob;

    @Test
    void pendingPastTtl_rejectsAndTransitionsPost() throws Exception {
        // A PENDING quarantine row aged past the TTL (24h) transitions to
        // REJECTED; the attached NEEDS_REVIEW post transitions to QUARANTINED.
        SeededData data = seedPendingQuarantinePastTtl("ttl-reject");

        ttlJob.rejectExpired(new AdminReviewTtlJob.TtlCandidate(
            data.quarantineId, data.postId, data.fetchedAt));

        assertQuarantineStatus(data.quarantineId, "REJECTED");
        assertPostStatus(data.postId, "QUARANTINED");
        assertAuditRowExists(data.quarantineId, "QUARANTINE_TTL_REJECT", "admin_review_ttl_job");
    }

    @Test
    void benignClosedPastTtl_noTransition() throws Exception {
        // A BENIGN_CLOSED row aged past the TTL stays BENIGN_CLOSED.
        SeededData data = seedBenignClosedPastTtl("ttl-benign-closed");

        // Enumerate: should NOT find BENIGN_CLOSED rows.
        var candidates = ttlJob.enumerateExpired();
        boolean found = candidates.stream()
            .anyMatch(c -> c.quarantineId().equals(data.quarantineId));

        assertEquals(false, found,
            "BENIGN_CLOSED row must NOT be picked up by TTL job");
        assertQuarantineStatus(data.quarantineId, "BENIGN_CLOSED");
    }

    @Test
    void pendingPastTtl_postPartitionDropped_stillEnumeratedAndRejected() throws Exception {
        // A PENDING quarantine row whose post row no longer exists
        // (partition dropped) must still be enumerated — via the
        // denormalized post_fetched_at, not a join on post — and must
        // still transition to REJECTED. The post-side UPDATE inside
        // rejectExpired legitimately no-ops.
        UUID orphanPostId = UUID.randomUUID();
        Instant fetchedAt = Instant.parse("2026-05-18T12:00:00Z");
        Instant flaggedAt = Instant.now().minus(Duration.ofHours(48));
        UUID quarantineId = seedQuarantineRowWithoutPost(orphanPostId, fetchedAt, flaggedAt);

        var candidates = ttlJob.enumerateExpired();
        var match = candidates.stream()
            .filter(c -> c.quarantineId().equals(quarantineId))
            .findFirst();
        assertTrue(match.isPresent(),
            "a PENDING row past TTL must be enumerated even when its post row is gone");
        assertEquals(fetchedAt, match.get().postFetchedAt(),
            "postFetchedAt must come from the denormalized quarantine column");

        ttlJob.rejectExpired(match.get());

        assertQuarantineStatus(quarantineId, "REJECTED");
        assertAuditRowExists(quarantineId, "QUARANTINE_TTL_REJECT", "admin_review_ttl_job");
    }

    @Test
    void quarantineReviewNotify_emittedOnTtlReject() throws Exception {
        // TTL auto-reject emits NOTIFY quarantine_review with payload
        // ('quarantine', quarantine_id, 'REJECTED'). A live LISTEN connection
        // captures the emission so a regression that drops the NOTIFY fails
        // here, not just on the status transition.
        SeededData data = seedPendingQuarantinePastTtl("ttl-notify");

        try (Connection listenConn = dataSource.getConnection()) {
            PGConnection pg = listenTo(listenConn, "quarantine_review");

            ttlJob.rejectExpired(new AdminReviewTtlJob.TtlCandidate(
                data.quarantineId, data.postId, data.fetchedAt));

            assertQuarantineStatus(data.quarantineId, "REJECTED");
            assertQuarantineReviewNotify(pg, "quarantine", data.quarantineId, "REJECTED");
        }
    }

    // ---------- helpers ----------

    /**
     * Establish a clean LISTEN on {@code channel} for the supplied
     * connection. PostgreSQL only delivers a NOTIFY to connections that
     * were already LISTENing when the emitting transaction commits, so the
     * caller must hold this connection open across the job invocation. The
     * {@code UNLISTEN *} + drain resets any registration the pooled
     * connection carried from a prior test. Mirrors ReEvalVerdictNotifyIT.
     */
    private PGConnection listenTo(Connection conn, String channel) throws Exception {
        conn.setAutoCommit(true);
        try (Statement s = conn.createStatement()) {
            s.execute("UNLISTEN *");
            s.execute("LISTEN " + channel);
        }
        PGConnection pg = conn.unwrap(PGConnection.class);
        pg.getNotifications();
        return pg;
    }

    /** Poll until at least {@code minimum} notifications arrive or the 10s deadline lapses. */
    private PGNotification[] awaitNotifications(PGConnection pg, int minimum) throws Exception {
        long deadlineNanos = System.nanoTime() + 10_000_000_000L;
        List<PGNotification> collected = new ArrayList<>();
        while (System.nanoTime() < deadlineNanos) {
            PGNotification[] batch = pg.getNotifications(500);
            if (batch != null) {
                for (PGNotification n : batch) {
                    collected.add(n);
                }
                if (collected.size() >= minimum) {
                    return collected.toArray(new PGNotification[0]);
                }
            }
        }
        return collected.isEmpty() ? null : collected.toArray(new PGNotification[0]);
    }

    /**
     * Assert that exactly one quarantine_review NOTIFY arrived carrying the
     * expected target_kind/target_id/new_status (the QuarantineNotifyEmitter
     * JSON payload). A regression that drops the emission yields zero
     * notifications and fails on assertNotNull; a wrong status/target fails
     * the payload check.
     */
    private void assertQuarantineReviewNotify(PGConnection pg, String targetKind,
                                              UUID targetId, String newStatus) throws Exception {
        PGNotification[] notifications = awaitNotifications(pg, 1);
        assertNotNull(notifications,
            "expected a quarantine_review NOTIFY for " + targetKind + " " + targetId);
        assertEquals(1, notifications.length,
            "exactly one quarantine_review NOTIFY for the transition");
        String payload = notifications[0].getParameter();
        assertTrue(payload.contains("\"target_kind\":\"" + targetKind + "\""),
            "NOTIFY payload target_kind must be " + targetKind + "; got: " + payload);
        assertTrue(payload.contains("\"target_id\":\"" + targetId + "\""),
            "NOTIFY payload target_id must be " + targetId + "; got: " + payload);
        assertTrue(payload.contains("\"new_status\":\"" + newStatus + "\""),
            "NOTIFY payload new_status must be " + newStatus + "; got: " + payload);
    }

    private SeededData seedPendingQuarantinePastTtl(String slug) throws Exception {
        UUID sourceId = seedSource(slug);
        Instant fetchedAt = Instant.parse("2026-05-18T10:00:00Z");
        // flagged_at more than 24h ago
        Instant flaggedAt = Instant.now().minus(Duration.ofHours(48));

        UUID postId;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status, status_changed_at,"
                     + "  stage1_done, stage1_flagged, stage2_done, stage2_failed,"
                     + "  tagger_done, tagger_fallback, embedding_done, tags, re_eval_attempts"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, 'title', 'body [REDACTED:ttl-ph]',"
                     + "  ?, 'NEEDS_REVIEW', now(),"
                     + "  TRUE, TRUE, TRUE, FALSE,"
                     + "  FALSE, FALSE, FALSE, '{}', 0"
                     + ") RETURNING id")) {
            ps.setString(1, "ttl-" + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "upstream-ttl-" + slug);
            ps.setTimestamp(4, Timestamp.from(fetchedAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                postId = (UUID) rs.getObject(1);
            }
        }

        UUID quarantineId;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO quarantine ("
                     + "  id, post_id, post_uid, post_fetched_at, flagged_at, flagged_by,"
                     + "  rule_id, placeholder_id, original_html, status"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, 'uid', ?, ?, 'stage1',"
                     + "  'regex-test', 'ttl-ph', 'original', 'PENDING'"
                     + ") RETURNING id")) {
            ps.setObject(1, postId);
            ps.setTimestamp(2, Timestamp.from(fetchedAt));
            ps.setTimestamp(3, Timestamp.from(flaggedAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                quarantineId = (UUID) rs.getObject(1);
            }
        }

        return new SeededData(postId, quarantineId, fetchedAt);
    }

    private SeededData seedBenignClosedPastTtl(String slug) throws Exception {
        UUID sourceId = seedSource(slug);
        Instant fetchedAt = Instant.parse("2026-05-18T11:00:00Z");
        Instant flaggedAt = Instant.now().minus(Duration.ofHours(48));

        UUID postId;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status,"
                     + "  stage1_done, stage1_flagged, stage2_done, stage2_failed,"
                     + "  tagger_done, tagger_fallback, embedding_done, tags, re_eval_attempts"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, 'title', 'body',"
                     + "  ?, 'RAW',"
                     + "  TRUE, TRUE, TRUE, FALSE,"
                     + "  FALSE, FALSE, FALSE, '{}', 0"
                     + ") RETURNING id")) {
            ps.setString(1, "ttl-bc-" + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "upstream-ttl-bc-" + slug);
            ps.setTimestamp(4, Timestamp.from(fetchedAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                postId = (UUID) rs.getObject(1);
            }
        }

        UUID quarantineId;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO quarantine ("
                     + "  id, post_id, post_uid, post_fetched_at, flagged_at, flagged_by,"
                     + "  rule_id, placeholder_id, original_html, status"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, 'uid', ?, ?, 'stage1',"
                     + "  'regex-test', 'bc-ph', 'original', 'BENIGN_CLOSED'"
                     + ") RETURNING id")) {
            ps.setObject(1, postId);
            ps.setTimestamp(2, Timestamp.from(fetchedAt));
            ps.setTimestamp(3, Timestamp.from(flaggedAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                quarantineId = (UUID) rs.getObject(1);
            }
        }

        return new SeededData(postId, quarantineId, fetchedAt);
    }

    /**
     * Insert a PENDING quarantine row pointing at a post id with NO
     * matching post row — the on-disk shape left behind by a dropped
     * post partition (quarantine carries no FK to post by design).
     */
    private UUID seedQuarantineRowWithoutPost(UUID postId, Instant fetchedAt, Instant flaggedAt)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO quarantine ("
                     + "  id, post_id, post_uid, post_fetched_at, flagged_at, flagged_by,"
                     + "  rule_id, placeholder_id, original_html, status"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, 'uid', ?, ?, 'stage1',"
                     + "  'regex-test', 'ttl-drop-ph', 'original', 'PENDING'"
                     + ") RETURNING id")) {
            ps.setObject(1, postId);
            ps.setTimestamp(2, Timestamp.from(fetchedAt));
            ps.setTimestamp(3, Timestamp.from(flaggedAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID seedSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', '{}'::text[]) "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            ps.setString(1, "https://ttl-test.example/" + slug);
            ps.setString(2, "TTL " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void assertQuarantineStatus(UUID quarantineId, String expected) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM quarantine WHERE id = ?")) {
            ps.setObject(1, quarantineId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expected, rs.getString(1));
            }
        }
    }

    private void assertPostStatus(UUID postId, String expected) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expected, rs.getString(1));
            }
        }
    }

    private void assertAuditRowExists(UUID targetId, String action, String actorContactId)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT 1 FROM audit_log WHERE target_id = ? AND action = ? "
                     + "AND actor_contact_id = ?")) {
            ps.setString(1, targetId.toString());
            ps.setString(2, action);
            ps.setString(3, actorContactId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(),
                    "Expected audit row: action=" + action + " target=" + targetId);
            }
        }
    }

    record SeededData(UUID postId, UUID quarantineId, Instant fetchedAt) {
    }
}
