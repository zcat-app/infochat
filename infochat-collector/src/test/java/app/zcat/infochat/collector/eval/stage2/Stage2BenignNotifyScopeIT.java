package app.zcat.infochat.collector.eval.stage2;

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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static app.zcat.infochat.collector.testsupport.PgNotifyFixture.awaitNotifications;
import static app.zcat.infochat.collector.testsupport.PgNotifyFixture.listenTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the BENIGN-verdict NOTIFY scoping: the
 * quarantine_review emission covers exactly the quarantine rows THIS
 * verdict transitioned PENDING→BENIGN_CLOSED, never rows closed by an
 * earlier verdict. A re-emit for long-closed rows would make every
 * subsequent BENIGN verdict on the same post re-announce history the
 * Provider already consumed.
 *
 * <p>JDBC LISTEN fixture: same shape as {@link
 * app.zcat.infochat.collector.eval.stage1.QuarantinePendingNotifyIT}.</p>
 */
@QuarkusTest
class Stage2BenignNotifyScopeIT {

    private static final Instant FETCHED_AT = Instant.parse("2026-06-07T09:00:00Z");
    private static final String UID_PREFIX = "benign-scope-it/";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    Stage2VerdictHandler stage2VerdictHandler;

    @BeforeEach
    void setup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM quarantine WHERE post_uid LIKE ?", UID_PREFIX + "%");
            exec(conn, "DELETE FROM post WHERE uid LIKE ?", UID_PREFIX + "%");
        }
    }

    @Test
    void benignVerdictEmitsOnlyRowsClosedByThisVerdict() throws Exception {
        SeededPost post = seedRawPost("scoped");
        UUID preClosedRowId = seedQuarantineRow(post, "ph-pre-closed", "BENIGN_CLOSED");
        UUID pendingRowId = seedQuarantineRow(post, "ph-pending", "PENDING");

        try (Connection listenConn = dataSource.getConnection()) {
            PGConnection pg = listenTo(listenConn, "quarantine_review");

            stage2VerdictHandler.apply(post.id, post.fetchedAt,
                Stage2VerdictHandler.Verdict.BENIGN);

            PGNotification[] notifications = awaitNotifications(pg, 1);
            assertNotNull(notifications, "the BENIGN verdict must fire one NOTIFY");
            assertEquals(1, notifications.length,
                "exactly one NOTIFY — only the row THIS verdict closed, not the pre-closed one");
            String payload = notifications[0].getParameter();
            assertTrue(payload.matches(".*\"new_status\"\\s*:\\s*\"BENIGN_CLOSED\".*"),
                "payload must carry new_status=BENIGN_CLOSED: " + payload);
            assertTrue(payload.contains("\"target_id\":\"" + pendingRowId + "\""),
                "payload target_id must be the formerly-PENDING row: " + payload);
            assertFalse(payload.contains(preClosedRowId.toString()),
                "no payload may carry the pre-closed row's id: " + payload);
        }
    }

    // ---------- helpers ----------

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
                ps.setString(3, "benign-scope-upstream-" + slug);
                ps.setString(4, "Benign scope IT " + slug);
                ps.setString(5, "Benign scope IT body " + slug);
                ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    return new SeededPost((UUID) rs.getObject(1), uid,
                        rs.getTimestamp(2).toInstant());
                }
            }
        }
    }

    private UUID seedQuarantineRow(SeededPost post, String placeholderId, String status)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO quarantine ("
                     + "  id, post_id, post_uid, post_fetched_at, flagged_at, flagged_by,"
                     + "  rule_id, placeholder_id, original_html, status"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, now(), 'stage1',"
                     + "  'regex-test', ?, '<b>span</b>', ?"
                     + ") RETURNING id")) {
            ps.setObject(1, post.id);
            ps.setString(2, post.uid);
            ps.setTimestamp(3, Timestamp.from(post.fetchedAt));
            ps.setString(4, placeholderId);
            ps.setString(5, status);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID seedRssSource(Connection conn, String slug) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                    + "VALUES ('rss', ?, ?, 'news', '{ai}') "
                    + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                    + "RETURNING id")) {
            ps.setString(1, "https://benign-scope-it.example.test/" + slug + "/feed.xml");
            ps.setString(2, "Benign scope IT source " + slug);
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

    private record SeededPost(UUID id, String uid, Instant fetchedAt) {
    }
}
