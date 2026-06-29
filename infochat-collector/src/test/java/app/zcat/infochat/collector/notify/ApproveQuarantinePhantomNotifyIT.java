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
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the V53 {@code approve_quarantine} guard: the
 * {@code new_post} NOTIFY must fire only when the {@code UPDATE post}
 * actually matched a row. quarantine has no FK to post (V10:27-32 — a
 * quarantine row must survive its source partition's TTL drop), so a
 * quarantine row can outlive its post; the V50 body fired a phantom
 * {@code new_post} NOTIFY in that case, sending the Provider's
 * NewPostListener chasing a post that no longer exists (deep-review
 * 19#F2; M1-516).
 *
 * <p>JDBC LISTEN fixture: same shape as {@link QuarantineProcedureNotifyIT}
 * — a dedicated listen connection, {@code LISTEN <channel>}, bounded
 * {@code PGConnection.getNotifications} polling. Real Postgres NOTIFY
 * end-to-end. Both NOTIFYs commit atomically with {@code quarantine_review}
 * emitted last, so waiting for {@code quarantine_review} guarantees any
 * {@code new_post} has already been delivered.</p>
 */
@QuarkusTest
class ApproveQuarantinePhantomNotifyIT {

    private static final Instant FETCHED_AT = Instant.parse("2026-05-16T11:00:00Z");
    private static final String UID_PREFIX = "phantom-notify-it/";
    private static final String ADAPTER = "test";
    private static final String ADMIN_CONTACT = "phantom-notify-it-admin";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    private UUID adminUserId;

    @BeforeEach
    void setup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            adminUserId = seedAdmin(conn);
            // quarantine rows first (they reference our posts by uid),
            // then the posts themselves.
            exec(conn, "DELETE FROM quarantine WHERE post_uid LIKE ?", UID_PREFIX + "%");
            exec(conn, "DELETE FROM post WHERE uid LIKE ?", UID_PREFIX + "%");
        }
    }

    // ---------- acceptance item 2: phantom NOTIFY suppressed ----------

    @Test
    void phantomNewPostNotifyIsSuppressedWhenPostMissing() throws Exception {
        Fixture fixture = seedFixture("phantom");
        // Simulate the TTL drop: the post is gone but the quarantine row
        // survives (no FK), so approve_quarantine's UPDATE post matches
        // zero rows.
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM post WHERE id = ? AND fetched_at = ?",
                fixture.postId, Timestamp.from(FETCHED_AT));
        }

        try (Connection listenConn = dataSource.getConnection()) {
            PGConnection pg = listenTo(listenConn, "new_post", "quarantine_review");

            // Must complete successfully despite the missing post.
            callProcedure("approve_quarantine", fixture.quarantineId, adminUserId);

            List<PGNotification> notifications = awaitUntilSeen(pg, "quarantine_review");
            assertEquals(0, countOn(notifications, "new_post"),
                "no phantom new_post NOTIFY when the post was TTL-dropped: " + notifications);
            assertEquals(1, countOn(notifications, "quarantine_review"),
                "quarantine_review NOTIFY must still fire: " + notifications);
        }

        assertQuarantineStatus(fixture.quarantineId, "APPROVED");
    }

    // ---------- control: post present → both NOTIFYs fire ----------

    @Test
    void bothNotifiesFireWhenPostPresent() throws Exception {
        Fixture fixture = seedFixture("control");

        try (Connection listenConn = dataSource.getConnection()) {
            PGConnection pg = listenTo(listenConn, "new_post", "quarantine_review");

            callProcedure("approve_quarantine", fixture.quarantineId, adminUserId);

            List<PGNotification> notifications = awaitUntilSeen(pg, "quarantine_review");
            assertEquals(1, countOn(notifications, "new_post"),
                "new_post NOTIFY must fire when the post is present: " + notifications);
            assertEquals(1, countOn(notifications, "quarantine_review"),
                "quarantine_review NOTIFY must fire: " + notifications);
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

    private void assertQuarantineStatus(UUID quarantineId, String expected) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM quarantine WHERE id = ?")) {
            ps.setObject(1, quarantineId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "quarantine row must still exist");
                assertEquals(expected, rs.getString("status"),
                    "quarantine row must transition to " + expected);
            }
        }
    }

    private UUID seedAdmin(Connection conn) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                    + "VALUES (?, ?, TRUE, 'vouched') "
                    + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                    + "SET is_admin = EXCLUDED.is_admin "
                    + "RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, ADMIN_CONTACT);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return (UUID) rs.getObject(1);
            }
        }
    }

    /**
     * Seeds a QUARANTINED post with a placeholder in the body and a
     * matching PENDING quarantine row — the state approve_quarantine
     * operates on.
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
                ps.setString(3, "phantom-notify-upstream-" + slug);
                ps.setString(4, "Phantom notify IT " + slug);
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
            ps.setString(1, "https://phantom-notify-it.example.test/" + slug + "/feed.xml");
            ps.setString(2, "Phantom notify IT source " + slug);
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
     * LISTEN on a clean slate for several channels. Pooled connections
     * keep their LISTEN registrations across pool check-ins and accumulate
     * notifications from other tests' commits, so reset the registrations
     * and drain anything already delivered before the test acts.
     */
    private static PGConnection listenTo(Connection conn, String... channels) throws Exception {
        conn.setAutoCommit(true);
        try (Statement s = conn.createStatement()) {
            s.execute("UNLISTEN *");
            for (String channel : channels) {
                s.execute("LISTEN " + channel);
            }
        }
        PGConnection pg = conn.unwrap(PGConnection.class);
        pg.getNotifications();
        return pg;
    }

    /**
     * Poll until a notification on {@code requiredChannel} arrives (or the
     * bounded wait elapses), then drain once more to catch any straggler
     * delivered in a separate batch. {@code requiredChannel} is the LAST
     * NOTIFY the procedure emits, so by the time it arrives every earlier
     * NOTIFY from the same atomic commit has already been delivered — which
     * is what lets the caller assert the absence of an earlier channel.
     */
    private List<PGNotification> awaitUntilSeen(PGConnection pg, String requiredChannel) throws Exception {
        long deadlineNanos = System.nanoTime() + 10_000_000_000L;
        List<PGNotification> collected = new ArrayList<>();
        while (System.nanoTime() < deadlineNanos) {
            PGNotification[] batch = pg.getNotifications(500);
            if (batch != null) {
                for (PGNotification n : batch) {
                    collected.add(n);
                }
            }
            if (countOn(collected, requiredChannel) > 0) {
                PGNotification[] stragglers = pg.getNotifications(200);
                if (stragglers != null) {
                    for (PGNotification n : stragglers) {
                        collected.add(n);
                    }
                }
                return collected;
            }
        }
        return collected;
    }

    private static int countOn(List<PGNotification> notifications, String channel) {
        int count = 0;
        for (PGNotification n : notifications) {
            if (channel.equals(n.getName())) {
                count++;
            }
        }
        return count;
    }

    private record Fixture(UUID postId, UUID quarantineId) {
    }
}
