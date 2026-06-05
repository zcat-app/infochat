package app.zcat.infochat.collector.notify;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the V32 {@code approve_quarantine} /
 * {@code reject_quarantine} procedure bodies — the
 * {@code quarantine_review} NOTIFY the V21/V25 bodies never fired,
 * the {@code jsonb_build_object} payload format, the re-added
 * {@code actor_contact_id}/{@code actor_adapter} audit
 * denormalization, and the carried-forward V25 hardening
 * (actor-admin check, {@code SET search_path} pin).
 *
 * <p>JDBC LISTEN fixture: same shape as {@link
 * app.zcat.infochat.collector.eval.ready.ReadyPromoterIT} — a
 * dedicated listen connection, {@code LISTEN <channel>}, bounded
 * {@code PGConnection.getNotifications} polling. Real Postgres
 * NOTIFY end-to-end, not an in-process mock.</p>
 */
@QuarkusTest
class QuarantineProcedureNotifyIT {

    private static final Instant FETCHED_AT = Instant.parse("2026-05-16T11:00:00Z");
    private static final String UID_PREFIX = "proc-notify-it/";
    private static final String ADAPTER = "test";
    private static final String ADMIN_CONTACT = "proc-notify-it-admin";
    private static final String NON_ADMIN_CONTACT = "proc-notify-it-user";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    private UUID adminUserId;
    private UUID nonAdminUserId;

    @BeforeEach
    void setup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            adminUserId = seedUser(conn, ADMIN_CONTACT, true);
            nonAdminUserId = seedUser(conn, NON_ADMIN_CONTACT, false);
            // quarantine rows first (they reference our posts by uid),
            // then the posts themselves.
            exec(conn, "DELETE FROM quarantine WHERE post_uid LIKE ?", UID_PREFIX + "%");
            exec(conn, "DELETE FROM post WHERE uid LIKE ?", UID_PREFIX + "%");
        }
    }

    // ---------- acceptance item 1: the missing quarantine_review NOTIFY ----------

    @Test
    void approveEmitsQuarantineReviewNotifyApproved() throws Exception {
        Fixture fixture = seedFixture("approve-notify");

        try (Connection listenConn = dataSource.getConnection()) {
            PGConnection pg = listenTo(listenConn, "quarantine_review");

            callProcedure("approve_quarantine", fixture.quarantineId, adminUserId);

            PGNotification[] notifications = awaitNotifications(pg, 1);
            assertNotNull(notifications, "approve must fire one quarantine_review NOTIFY");
            assertEquals(1, notifications.length, "exactly one NOTIFY per approve");
            String payload = notifications[0].getParameter();
            assertTrue(payload.matches(".*\"target_kind\"\\s*:\\s*\"quarantine\".*"),
                "payload must carry target_kind=quarantine: " + payload);
            assertTrue(payload.matches(".*\"target_id\"\\s*:\\s*\"" + fixture.quarantineId + "\".*"),
                "payload must carry the quarantine id: " + payload);
            assertTrue(payload.matches(".*\"new_status\"\\s*:\\s*\"APPROVED\".*"),
                "payload must carry new_status=APPROVED: " + payload);
        }
    }

    @Test
    void rejectEmitsQuarantineReviewNotifyRejected() throws Exception {
        Fixture fixture = seedFixture("reject-notify");

        try (Connection listenConn = dataSource.getConnection()) {
            PGConnection pg = listenTo(listenConn, "quarantine_review");

            callProcedure("reject_quarantine", fixture.quarantineId, adminUserId);

            PGNotification[] notifications = awaitNotifications(pg, 1);
            assertNotNull(notifications, "reject must fire one quarantine_review NOTIFY");
            assertEquals(1, notifications.length, "exactly one NOTIFY per reject");
            String payload = notifications[0].getParameter();
            assertTrue(payload.matches(".*\"target_kind\"\\s*:\\s*\"quarantine\".*"),
                "payload must carry target_kind=quarantine: " + payload);
            assertTrue(payload.matches(".*\"target_id\"\\s*:\\s*\"" + fixture.quarantineId + "\".*"),
                "payload must carry the quarantine id: " + payload);
            assertTrue(payload.matches(".*\"new_status\"\\s*:\\s*\"REJECTED\".*"),
                "payload must carry new_status=REJECTED: " + payload);
        }
    }

    // ---------- acceptance item 4: jsonb payload renders a parseable ready_at ----------

    @Test
    void approveNewPostPayloadParsesAsIso8601Instant() throws Exception {
        Fixture fixture = seedFixture("approve-iso8601");

        try (Connection listenConn = dataSource.getConnection()) {
            PGConnection pg = listenTo(listenConn, "new_post");

            callProcedure("approve_quarantine", fixture.quarantineId, adminUserId);

            PGNotification[] notifications = awaitNotifications(pg, 1);
            assertNotNull(notifications, "approve must fire one new_post NOTIFY");
            String payload = notifications[0].getParameter();
            Matcher readyAt = Pattern.compile("\"ready_at\"\\s*:\\s*\"([^\"]+)\"").matcher(payload);
            assertTrue(readyAt.find(), "payload must carry a ready_at field: " + payload);
            // The V25 v_ready_at::TEXT payload rendered the Postgres
            // timestamp format, which Instant.parse rejects — the
            // Provider's NewPostListener dropped every procedure-fired
            // event. to_jsonb(timestamptz) renders ISO-8601.
            Instant parsed = Instant.parse(readyAt.group(1));
            assertNotNull(parsed, "ready_at must be Instant.parse-able: " + readyAt.group(1));
        }
    }

    // ---------- acceptance item 4: re-added actor audit denormalization ----------

    @Test
    void approveAuditRowCarriesActorContactIdAndAdapter() throws Exception {
        Fixture fixture = seedFixture("approve-audit");

        callProcedure("approve_quarantine", fixture.quarantineId, adminUserId);

        assertAuditActorColumns("APPROVE_QUARANTINE", fixture.quarantineId);
    }

    @Test
    void rejectAuditRowCarriesActorContactIdAndAdapter() throws Exception {
        Fixture fixture = seedFixture("reject-audit");

        callProcedure("reject_quarantine", fixture.quarantineId, adminUserId);

        assertAuditActorColumns("REJECT_QUARANTINE", fixture.quarantineId);
    }

    // ---------- acceptance item 6: V25 hardening carried forward ----------

    @Test
    void nonAdminActorIsRejected() throws Exception {
        Fixture fixture = seedFixture("non-admin");

        assertThrows(SQLException.class,
            () -> callProcedure("approve_quarantine", fixture.quarantineId, nonAdminUserId),
            "non-admin actor must be rejected by the carried-forward actor-admin check");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM quarantine WHERE id = ?")) {
            ps.setObject(1, fixture.quarantineId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "quarantine row must still exist");
                assertEquals("PENDING", rs.getString("status"),
                    "rejected call must not transition the quarantine row");
            }
        }
    }

    @Test
    void proceduresKeepSearchPathPin() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT proname, proconfig::text AS config FROM pg_proc "
                     + "WHERE proname IN ('approve_quarantine', 'reject_quarantine')")) {
            try (ResultSet rs = ps.executeQuery()) {
                int found = 0;
                while (rs.next()) {
                    found++;
                    String config = rs.getString("config");
                    assertNotNull(config,
                        rs.getString("proname") + " must carry a proconfig");
                    assertTrue(config.contains("search_path=pg_catalog, public"),
                        rs.getString("proname") + " must keep the V25 search_path pin — got: "
                            + config);
                }
                assertEquals(2, found, "both procedures must exist exactly once "
                    + "(a changed parameter list would have CREATEd an overload)");
            }
        }
    }

    // ---------- helpers ----------

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

    private void assertAuditActorColumns(String action, UUID quarantineId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT actor_contact_id, actor_adapter FROM audit_log "
                     + "WHERE action = ? AND target_id = ?")) {
            ps.setString(1, action);
            ps.setString(2, quarantineId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), action + " audit row must exist for " + quarantineId);
                assertEquals(ADMIN_CONTACT, rs.getString("actor_contact_id"),
                    "audit row must denormalize the actor's contact_id");
                assertEquals(ADAPTER, rs.getString("actor_adapter"),
                    "audit row must denormalize the actor's adapter");
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
     * matching PENDING quarantine row — the state the procedures
     * operate on.
     */
    private Fixture seedFixture(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID sourceId = seedRssSource(conn, slug);
            String uid = UID_PREFIX + slug;
            String placeholderId = "ph-" + slug;
            UUID postId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO post (uid, source_id, upstream_identifier, title, body, "
                        + "fetched_at, status, stage1_done, stage1_flagged, tags) "
                        + "VALUES (?, ?, ?, ?, "
                        + "'safe prefix [REDACTED:' || ? || '] safe suffix', "
                        + "?, 'QUARANTINED', TRUE, TRUE, '{}') "
                        + "RETURNING id")) {
                ps.setString(1, uid);
                ps.setObject(2, sourceId);
                ps.setString(3, "proc-notify-upstream-" + slug);
                ps.setString(4, "Procedure notify IT " + slug);
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
            ps.setString(1, "https://proc-notify-it.example.test/" + slug + "/feed.xml");
            ps.setString(2, "Procedure notify IT source " + slug);
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

    /**
     * LISTEN on a clean slate. Pooled connections keep their LISTEN
     * registrations across pool check-ins and accumulate notifications
     * from other tests' commits, so reset the registrations and drain
     * anything already delivered before the test acts.
     */
    private static PGConnection listenTo(Connection conn, String channel) throws Exception {
        conn.setAutoCommit(true);
        try (Statement s = conn.createStatement()) {
            s.execute("UNLISTEN *");
            s.execute("LISTEN " + channel);
        }
        PGConnection pg = conn.unwrap(PGConnection.class);
        pg.getNotifications();
        return pg;
    }

    /**
     * Poll {@code getNotifications} until at least {@code minimum}
     * notifications arrive OR the bounded wait elapses. Returns the
     * accumulated array (possibly more than {@code minimum} elements)
     * or null when nothing arrived — same shape as ReadyPromoterIT.
     */
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

    private record Fixture(UUID postId, UUID quarantineId) {
    }
}
