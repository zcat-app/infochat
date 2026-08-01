package app.zcat.infochat.collector.eval.reeval;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the V48 audit-before-effect reorder of
 * {@code approve_quarantine} (V41 body) and {@code reject_quarantine}
 * (V32 body). The reorder moves the {@code INSERT INTO audit_log} ahead
 * of the {@code UPDATE quarantine}/{@code UPDATE post} mutations to
 * restore Invariant 7 (schema.md §Invariants). It must be
 * behavior-preserving: the audit row carries the same {@code details_json}
 * as before ({@code post_id} captured at the {@code FOR UPDATE}, so the
 * payload is byte-identical), and the quarantine/post state transitions
 * are unchanged.
 *
 * <p>Seed fixture and procedure-call helpers mirror {@link
 * app.zcat.infochat.collector.notify.QuarantineProcedureNotifyIT} — real
 * Postgres, the V48 migration applied by Flyway.</p>
 */
@QuarkusTest
class QuarantineAuditBeforeEffectIT {

    private static final Instant FETCHED_AT = Instant.parse("2026-05-16T11:00:00Z");
    private static final String UID_PREFIX = "audit-before-it/";
    private static final String ADAPTER = "test";
    private static final String ADMIN_CONTACT = "audit-before-it-admin";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    private UUID adminUserId;

    @BeforeEach
    void setup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            adminUserId = seedUser(conn, ADMIN_CONTACT, true);
            // quarantine rows first (they reference our posts by uid),
            // then the posts themselves.
            exec(conn, "DELETE FROM quarantine WHERE post_uid LIKE ?", UID_PREFIX + "%");
            exec(conn, "DELETE FROM post WHERE uid LIKE ?", UID_PREFIX + "%");
        }
    }

    @Test
    void approveWritesAuditRowAndPreservesStateTransitions() throws Exception {
        Fixture fixture = seedFixture("approve");

        callProcedure("approve_quarantine", fixture.quarantineId, adminUserId);

        // The audit row exists and its details_json is the same payload as
        // before the reorder: post_id captured at the FOR UPDATE.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT details_json->>'post_id' AS post_id FROM audit_log "
                     + "WHERE action = 'APPROVE_QUARANTINE' AND target_id = ?")) {
            ps.setString(1, fixture.quarantineId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "APPROVE_QUARANTINE audit row must exist");
                assertEquals(fixture.postId.toString(), rs.getString("post_id"),
                    "details_json.post_id must be the post captured at the FOR UPDATE");
            }
        }

        // The quarantine transition is unchanged: APPROVED, reviewed_by set.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status, reviewed_by FROM quarantine WHERE id = ?")) {
            ps.setObject(1, fixture.quarantineId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "quarantine row must exist");
                assertEquals("APPROVED", rs.getString("status"),
                    "approve must transition the quarantine row to APPROVED");
                assertEquals(adminUserId, rs.getObject("reviewed_by"),
                    "approve must record the reviewing admin");
            }
        }

        // The post transition is unchanged: READY, placeholder restored,
        // stage2_failed cleared (the V41 behavior carried forward).
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status, body, stage2_failed FROM post WHERE id = ? AND fetched_at = ?")) {
            ps.setObject(1, fixture.postId);
            ps.setTimestamp(2, Timestamp.from(FETCHED_AT));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "post row must exist");
                assertEquals("READY", rs.getString("status"),
                    "approve must transition the post to READY");
                assertEquals("safe prefix <b>quarantined</b> safe suffix", rs.getString("body"),
                    "approve must restore the redacted span from original_html");
                assertFalse(rs.getBoolean("stage2_failed"),
                    "approve must clear stage2_failed (V41 behavior preserved)");
            }
        }
    }

    @Test
    void rejectWritesAuditRowAndPreservesStateTransitions() throws Exception {
        Fixture fixture = seedFixture("reject");

        callProcedure("reject_quarantine", fixture.quarantineId, adminUserId);

        // The reject audit row exists and carries no details_json — the
        // V32 body never wrote one, and the reorder does not add one.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT details_json FROM audit_log "
                     + "WHERE action = 'REJECT_QUARANTINE' AND target_id = ?")) {
            ps.setString(1, fixture.quarantineId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "REJECT_QUARANTINE audit row must exist");
                assertNull(rs.getString("details_json"),
                    "reject audit row must carry no details_json (unchanged by the reorder)");
            }
        }

        // The quarantine transition is unchanged: REJECTED, reviewed_by set.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status, reviewed_by FROM quarantine WHERE id = ?")) {
            ps.setObject(1, fixture.quarantineId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "quarantine row must exist");
                assertEquals("REJECTED", rs.getString("status"),
                    "reject must transition the quarantine row to REJECTED");
                assertEquals(adminUserId, rs.getObject("reviewed_by"),
                    "reject must record the reviewing admin");
            }
        }
    }

    // ---------- helpers (mirroring QuarantineProcedureNotifyIT) ----------

    private void callProcedure(String procedure, UUID quarantineId, UUID actorId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + procedure + "(?, ?)")) {
            ps.setObject(1, quarantineId);
            ps.setObject(2, actorId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }

    private UUID seedUser(Connection conn, String contactId, boolean isAdmin) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                    + "VALUES (?, ?, ?, 'vouched') "
                    + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                    + "SET is_admin = EXCLUDED.is_admin "
                    + "RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return (UUID) rs.getObject(1);
            }
        }
    }

    /**
     * Seeds a QUARANTINED post with a placeholder in the body and a
     * matching PENDING quarantine row — the state the procedures operate
     * on. The post starts with {@code stage2_failed = TRUE} so the approve
     * path's clear is observable, and {@code stage2_done = TRUE} with a
     * recorded BENIGN verdict (a verdict recorded before a later re-eval
     * infra-failed — COALESCE preserves it) so the V69 verdict-owed guard
     * (M1-741) does not block the approve.
     */
    private Fixture seedFixture(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID sourceId = seedRssSource(conn, slug);
            String uid = UID_PREFIX + slug;
            String placeholderId = "ph-" + slug;
            UUID postId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO post (uid, source_id, upstream_identifier, title, body, "
                        + "fetched_at, status, stage1_done, stage1_flagged, stage2_done, stage2_verdict, stage2_failed, tags) "
                        + "VALUES (?, ?, ?, ?, "
                        + "'safe prefix [REDACTED:' || ? || '] safe suffix', "
                        + "?, 'QUARANTINED', TRUE, TRUE, TRUE, 'BENIGN', TRUE, '{}') "
                        + "RETURNING id")) {
                ps.setString(1, uid);
                ps.setObject(2, sourceId);
                ps.setString(3, "audit-before-upstream-" + slug);
                ps.setString(4, "Audit before-effect IT " + slug);
                ps.setString(5, placeholderId);
                ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    postId = (UUID) rs.getObject(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO quarantine (post_id, post_uid, post_fetched_at, flagged_by, "
                        + "rule_id, span_start, span_end, placeholder_id, original_html, status) "
                        + "VALUES (?, ?, ?, 'stage1', ?, 12, 24, ?, '<b>quarantined</b>', 'PENDING') "
                        + "RETURNING id")) {
                ps.setObject(1, postId);
                ps.setString(2, uid);
                ps.setTimestamp(3, Timestamp.from(FETCHED_AT));
                ps.setString(4, "rule-" + slug);
                ps.setString(5, placeholderId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    return new Fixture(postId, (UUID) rs.getObject(1));
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
            ps.setString(1, "https://audit-before-it.example.test/" + slug + "/feed.xml");
            ps.setString(2, "Audit before-effect IT source " + slug);
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

    private record Fixture(UUID postId, UUID quarantineId) {
    }
}
