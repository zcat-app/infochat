package app.zcat.infochat.collector.eval.stage1;

import app.zcat.infochat.collector.eval.stage2.Stage2VerdictHandler;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static app.zcat.infochat.collector.testsupport.PgNotifyFixture.awaitNotifications;
import static app.zcat.infochat.collector.testsupport.PgNotifyFixture.listenTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the PENDING {@code quarantine_review} NOTIFY
 * placement — emitted at {@link QuarantineDao#insert} inside the
 * Stage-1 transaction (the spec's "fires on PENDING insert"), NOT
 * deferred to the Stage-2 verdict. The Stage-2 cases pin the removal
 * of the old per-verdict re-fire: an unsafe verdict fires nothing
 * (PENDING already fired at insert) and a BENIGN verdict fires
 * BENIGN_CLOSED only.
 *
 * <p>JDBC LISTEN fixture: same shape as {@link
 * app.zcat.infochat.collector.eval.ready.ReadyPromoterIT}.</p>
 */
@QuarkusTest
class QuarantinePendingNotifyIT {

    private static final Instant FETCHED_AT = Instant.parse("2026-05-16T11:00:00Z");
    private static final String UID_PREFIX = "pending-notify-it/";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    QuarantineDao quarantineDao;

    @Inject
    Stage2VerdictHandler stage2VerdictHandler;

    @BeforeEach
    void setup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM quarantine WHERE post_uid LIKE ?", UID_PREFIX + "%");
            exec(conn, "DELETE FROM post WHERE uid LIKE ?", UID_PREFIX + "%");
        }
    }

    // ---------- acceptance item 2: PENDING fires at insert, same tx ----------

    @Test
    void insertEmitsOnePendingNotifyPerRow() throws Exception {
        SeededPost post = seedRawPost("per-row");

        try (Connection listenConn = dataSource.getConnection()) {
            PGConnection pg = listenTo(listenConn, "quarantine_review");

            try (Connection txConn = dataSource.getConnection()) {
                txConn.setAutoCommit(false);
                quarantineDao.insert(txConn, quarantineRow(post, "rule-per-row-1", "ph-per-row-1"));
                quarantineDao.insert(txConn, quarantineRow(post, "rule-per-row-2", "ph-per-row-2"));
                txConn.commit();
            }

            PGNotification[] notifications = awaitNotifications(pg, 2);
            assertNotNull(notifications, "two PENDING NOTIFYs must arrive after commit");
            assertEquals(2, notifications.length, "exactly one NOTIFY per inserted row");
            List<UUID> quarantineIds = quarantineIdsForPost(post.id);
            assertEquals(2, quarantineIds.size(), "both rows must be inserted");
            for (PGNotification n : notifications) {
                String payload = n.getParameter();
                assertTrue(payload.matches(".*\"target_kind\"\\s*:\\s*\"quarantine\".*"),
                    "payload must carry target_kind=quarantine: " + payload);
                assertTrue(payload.matches(".*\"new_status\"\\s*:\\s*\"PENDING\".*"),
                    "payload must carry new_status=PENDING: " + payload);
                assertTrue(quarantineIds.stream()
                        .anyMatch(id -> payload.contains("\"target_id\":\"" + id + "\"")),
                    "payload target_id must be one of the inserted rows: " + payload);
            }
        }
    }

    @Test
    void insertRollbackEmitsNothing() throws Exception {
        SeededPost post = seedRawPost("rollback");

        try (Connection listenConn = dataSource.getConnection()) {
            PGConnection pg = listenTo(listenConn, "quarantine_review");

            try (Connection txConn = dataSource.getConnection()) {
                txConn.setAutoCommit(false);
                quarantineDao.insert(txConn, quarantineRow(post, "rule-rollback", "ph-rollback"));
                txConn.rollback();
            }

            // NOTIFY is transactional: a rolled-back insert must fire
            // nothing. Bounded wait gives Postgres a window to deliver
            // any phantom notification.
            PGNotification[] notifications = pg.getNotifications(500);
            assertTrue(notifications == null || notifications.length == 0,
                "no NOTIFY may be observable for a rolled-back insert; got: "
                    + java.util.Arrays.toString(notifications));
        }

        assertEquals(0, quarantineIdsForPost(post.id).size(),
            "rollback must discard the quarantine row itself");
    }

    // ---------- acceptance item 2: the per-verdict re-fire is gone ----------

    @Test
    void quarantineVerdictDoesNotReFirePending() throws Exception {
        SeededPost post = seedRawPost("no-refire");

        try (Connection listenConn = dataSource.getConnection()) {
            PGConnection pg = listenTo(listenConn, "quarantine_review");

            insertCommitted(post, "rule-no-refire", "ph-no-refire");
            PGNotification[] pending = awaitNotifications(pg, 1);
            assertNotNull(pending, "the insert-time PENDING NOTIFY must arrive");

            stage2VerdictHandler.apply(post.id, post.fetchedAt,
                Stage2VerdictHandler.Verdict.INJECTION);

            PGNotification[] afterVerdict = pg.getNotifications(500);
            assertTrue(afterVerdict == null || afterVerdict.length == 0,
                "an unsafe verdict must not re-fire PENDING (it already fired at insert); got: "
                    + java.util.Arrays.toString(afterVerdict));
        }
    }

    @Test
    void benignVerdictEmitsBenignClosedNotPending() throws Exception {
        SeededPost post = seedRawPost("benign");

        try (Connection listenConn = dataSource.getConnection()) {
            PGConnection pg = listenTo(listenConn, "quarantine_review");

            insertCommitted(post, "rule-benign", "ph-benign");
            PGNotification[] pending = awaitNotifications(pg, 1);
            assertNotNull(pending, "the insert-time PENDING NOTIFY must arrive");

            stage2VerdictHandler.apply(post.id, post.fetchedAt,
                Stage2VerdictHandler.Verdict.BENIGN);

            PGNotification[] afterVerdict = awaitNotifications(pg, 1);
            assertNotNull(afterVerdict, "the BENIGN verdict must fire one NOTIFY");
            assertEquals(1, afterVerdict.length, "exactly one NOTIFY for the closed row");
            String payload = afterVerdict[0].getParameter();
            assertTrue(payload.matches(".*\"new_status\"\\s*:\\s*\"BENIGN_CLOSED\".*"),
                "the verdict-time NOTIFY must carry BENIGN_CLOSED: " + payload);
            assertFalse(payload.contains("PENDING"),
                "the BENIGN fast-path must NOT fire PENDING at verdict time: " + payload);
        }
    }

    // ---------- helpers ----------

    private void insertCommitted(SeededPost post, String ruleId, String placeholderId)
            throws Exception {
        try (Connection txConn = dataSource.getConnection()) {
            txConn.setAutoCommit(false);
            quarantineDao.insert(txConn, quarantineRow(post, ruleId, placeholderId));
            txConn.commit();
        }
    }

    private QuarantineDao.QuarantineRow quarantineRow(SeededPost post, String ruleId,
                                                      String placeholderId) {
        return new QuarantineDao.QuarantineRow(
            post.id, post.uid, post.fetchedAt, ruleId, 0, 10, "<b>span</b>", placeholderId);
    }

    private List<UUID> quarantineIdsForPost(UUID postId) throws Exception {
        List<UUID> ids = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id FROM quarantine WHERE post_id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add((UUID) rs.getObject(1));
                }
            }
        }
        return ids;
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
                ps.setString(3, "pending-notify-upstream-" + slug);
                ps.setString(4, "Pending notify IT " + slug);
                ps.setString(5, "Pending notify IT body " + slug);
                ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    return new SeededPost((UUID) rs.getObject(1), uid,
                        rs.getTimestamp(2).toInstant());
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
            ps.setString(1, "https://pending-notify-it.example.test/" + slug + "/feed.xml");
            ps.setString(2, "Pending notify IT source " + slug);
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
