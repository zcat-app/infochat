package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link SaveCommandHandler} against the
 * DevServices Postgres container (V5 users, V6 source, V7 post, V15
 * saved_post). One {@code @Test} per acceptance scenario in M1-052
 * acceptance item 3.
 *
 * <p>Test isolation: per-test sub-prefix within the class-wide
 * {@code PREFIX} ({@code m1-052-save-}); the {@link #cleanup()}
 * {@code @BeforeEach} deletes rows under the class-wide prefix. The
 * test profile sets {@code infochat.save.cap=3} so cap-saturation
 * scenarios run cheaply.</p>
 *
 * @implNote Shape B (Thin-SQL) per
 *     {@code docs/process/test-pyramid.md} §Handler unit tests —
 *     the handler's behavior IS the lock-protected DB interaction
 *     (≥2 real-DB-dependent statements: {@code SELECT ... FOR UPDATE}
 *     on users for the atomic cap; {@code INSERT} against the
 *     {@code (user_id, post_uid)} PK; trigger-driven save_count).
 */
@QuarkusTest
class SaveCommandHandlerTest {

    private static final String PREFIX = "m1-052-save-";
    private static final String ADAPTER = "inmemory";

    @Inject SaveCommandHandler handler;
    @Inject DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;

    @Inject
    @ConfigProperty(name = "infochat.save.cap")
    int saveCap;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            // saved_post rows for prefix-matched users (the AFTER-DELETE
            // trigger decrements users.save_count; the subsequent
            // DELETE FROM users removes those rows entirely).
            exec(conn,
                    "DELETE FROM saved_post WHERE user_id IN ("
                            + "SELECT id FROM users WHERE contact_id LIKE ?)",
                    PREFIX + "%");
            // post rows under the prefix's sources.
            exec(conn,
                    "DELETE FROM post WHERE source_id IN ("
                            + "SELECT id FROM source WHERE identifier LIKE ?)",
                    PREFIX + "%");
            // source rows under the prefix.
            exec(conn,
                    "DELETE FROM source WHERE identifier LIKE ?",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM users WHERE contact_id LIKE ?",
                    PREFIX + "%");
        }
    }

    @Test
    void saveHappyPathReturnsSuccessAndWritesSnapshotRow() throws Exception {
        String contactId = PREFIX + "happy-actor";
        seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "happy-source", new String[] { "news", "tech" });
        String uid = PREFIX + "happy-uid";
        seedPost(sourceId, uid, "READY", "Title H", "Body H", "https://example.com/h", "Alice", Instant.parse("2026-05-01T00:00:00Z"));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);

        assertEquals(MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_SAVE_SUCCESS), uid),
                reply.text(),
                "/save success reply must interpolate the UID");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT post_uid FROM saved_post WHERE post_uid = ?")) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "saved_post row must exist after /save");
            }
        }
        assertEquals(1, readSaveCount(contactId), "users.save_count must increment to 1 after /save");
    }

    @Test
    void saveAgainstQuarantinedPostReturnsUnknownUid() throws Exception {
        String contactId = PREFIX + "qrnt-actor";
        seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "qrnt-source", new String[] {});
        String uid = PREFIX + "qrnt-uid";
        seedPost(sourceId, uid, "QUARANTINED", "Title Q", "Body Q", null, null, null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SAVE_UNKNOWN_UID), reply.text(),
                "QUARANTINED post must surface error.save.unknown_uid (visibility-of-target)");
        assertEquals(0L, countSavedPosts(contactId),
                "no saved_post row may be written for a QUARANTINED post");
    }

    @Test
    void saveAgainstNeedsReviewPostReturnsUnknownUid() throws Exception {
        String contactId = PREFIX + "need-actor";
        seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "need-source", new String[] {});
        String uid = PREFIX + "need-uid";
        seedPost(sourceId, uid, "NEEDS_REVIEW", "Title N", "Body N", null, null, null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SAVE_UNKNOWN_UID), reply.text(),
                "NEEDS_REVIEW post must surface error.save.unknown_uid (visibility-of-target)");
        assertEquals(0L, countSavedPosts(contactId),
                "no saved_post row may be written for a NEEDS_REVIEW post");
    }

    @Test
    void saveAgainstUnknownUidReturnsUnknownUid() throws Exception {
        String contactId = PREFIX + "unkn-actor";
        seedUser(contactId);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(contactId), "/save " + PREFIX + "no-such-uid");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SAVE_UNKNOWN_UID), reply.text(),
                "an unknown UID must surface error.save.unknown_uid");
        assertEquals(0L, countSavedPosts(contactId),
                "no saved_post row may be written for an unknown UID");
    }

    @Test
    void saveAgainstAlreadySavedPostReturnsAlreadySaved() throws Exception {
        String contactId = PREFIX + "dup-actor";
        seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "dup-source", new String[] {});
        String uid = PREFIX + "dup-uid";
        seedPost(sourceId, uid, "READY", "Title D", "Body D", null, null, null);

        OutboundMessage first = handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);
        assertEquals(MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_SAVE_SUCCESS), uid),
                first.text(), "first /save must succeed");

        OutboundMessage second = handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SAVE_ALREADY_SAVED), second.text(),
                "duplicate /save must surface error.save.already_saved");
        assertEquals(1L, countSavedPosts(contactId),
                "only one saved_post row may exist for the duplicate /save case");
        assertEquals(1, readSaveCount(contactId),
                "users.save_count must remain at 1 after the duplicate /save");
    }

    @Test
    void saveAtCapReturnsCapMetAndWritesNoRow() throws Exception {
        String contactId = PREFIX + "cap-actor";
        seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "cap-source", new String[] {});
        // Saturate to the cap with cap distinct UIDs.
        for (int i = 0; i < saveCap; i++) {
            String uid = PREFIX + "cap-uid-" + i;
            seedPost(sourceId, uid, "READY", "T" + i, "B" + i, null, null, null);
            OutboundMessage r = handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);
            assertEquals(MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_SAVE_SUCCESS), uid),
                    r.text(), "seed save must succeed at index " + i);
        }
        assertEquals(saveCap, readSaveCount(contactId),
                "users.save_count must equal cap after saturation");

        // One more /save against a new READY post must surface cap_met
        // and write no row.
        String overflowUid = PREFIX + "cap-overflow";
        seedPost(sourceId, overflowUid, "READY", "TO", "BO", null, null, null);
        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/save " + overflowUid);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SAVE_CAP_MET), reply.text(),
                "at-cap /save must surface error.save.cap_met");
        assertEquals(saveCap, readSaveCount(contactId),
                "users.save_count must remain at cap after the cap-met reject");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM saved_post WHERE post_uid = ?")) {
            ps.setString(1, overflowUid);
            try (ResultSet rs = ps.executeQuery()) {
                assertFalse(rs.next(),
                        "no saved_post row may exist for the cap-met UID");
            }
        }
    }

    @Test
    void saveWithPersonalTagsPopulatesPersonalTagsColumn() throws Exception {
        String contactId = PREFIX + "ptag-actor";
        seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "ptag-source", new String[] { "news" });
        String uid = PREFIX + "ptag-uid";
        seedPost(sourceId, uid, "READY", "Title P", "Body P", null, null, null);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(contactId), "/save " + uid + " -t read-later,interesting");

        assertEquals(MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_SAVE_SUCCESS), uid),
                reply.text(), "/save with -t must succeed");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT personal_tags FROM saved_post WHERE post_uid = ?")) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "saved_post row must exist");
                Array arr = rs.getArray("personal_tags");
                assertArrayEquals(new String[] { "read-later", "interesting" },
                        (String[]) arr.getArray(),
                        "personal_tags must carry the comma-split -t values verbatim");
            }
        }
    }

    @Test
    void saveSnapshotsBodyTitleUrlAuthorPublishedAtAndSourceId() throws Exception {
        String contactId = PREFIX + "snap-actor";
        seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "snap-source", new String[] { "news", "tech" });
        String uid = PREFIX + "snap-uid";
        Instant publishedAt = Instant.parse("2026-04-15T12:34:56Z");
        seedPost(sourceId, uid, "READY", "Snapshot Title", "Snapshot Body",
                "https://example.com/snap", "Bob Author", publishedAt);

        handler.handle(new ScopeRef.Dm(contactId), "/save " + uid);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT title, body, url, author, published_at, source_id, snapshot_tags "
                             + "FROM saved_post WHERE post_uid = ?")) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "saved_post row must exist");
                assertEquals("Snapshot Title", rs.getString("title"));
                assertEquals("Snapshot Body", rs.getString("body"));
                assertEquals("https://example.com/snap", rs.getString("url"));
                assertEquals("Bob Author", rs.getString("author"));
                Timestamp ts = rs.getTimestamp("published_at");
                assertNotNull(ts, "published_at must be snapshotted");
                assertEquals(publishedAt, ts.toInstant(),
                        "published_at snapshot must match the source post");
                assertEquals(sourceId, rs.getObject("source_id"),
                        "source_id snapshot must match the source post's source_id");
                Array snapArr = rs.getArray("snapshot_tags");
                assertEquals(List.of("news", "tech"),
                        Arrays.asList((String[]) snapArr.getArray()),
                        "snapshot_tags must carry the source's bootstrap_tags at /save time");
            }
        }
    }

    @Test
    void save_succeedsInGroupScope() throws Exception {
        String contactId = PREFIX + "group-actor";
        inboundContext.setSenderContactId(contactId);
        seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "group-source", new String[] { "news" });
        String uid = PREFIX + "group-uid";
        seedPost(sourceId, uid, "READY", "Title G", "Body G", "https://example.com/g", null, null);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Group("adapter-group-id"), "/save " + uid);

        assertEquals(MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_SAVE_SUCCESS), uid),
                reply.text(),
                "/save in group scope must succeed for any active group member");
        assertEquals(1, readSaveCount(contactId),
                "users.save_count must increment after group-scope /save");
    }

    // ----- helpers --------------------------------------------------------

    private UUID seedUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state) VALUES (?, ?, FALSE, FALSE, 'vouched') "
                             + "RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID seedSource(String identifier, String[] bootstrapTags) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags) VALUES ('rss', ?, ?, 'news', ?) "
                             + "RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, "Test Source " + identifier);
            ps.setArray(3, conn.createArrayOf("TEXT", bootstrapTags));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedPost(UUID sourceId, String uid, String status, String title,
                          String body, String url, String author, Instant publishedAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (source_id, uid, title, body, url, author, "
                             + "published_at, fetched_at, status) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, sourceId);
            ps.setString(2, uid);
            ps.setString(3, title);
            ps.setString(4, body);
            ps.setString(5, url);
            ps.setString(6, author);
            if (publishedAt == null) {
                ps.setObject(7, null);
            } else {
                ps.setObject(7, OffsetDateTime.ofInstant(publishedAt, java.time.ZoneOffset.UTC));
            }
            // fetched_at must fall inside the V7 bootstrap partition
            // (2026-05-01 .. 2026-06-01).
            ps.setObject(8, OffsetDateTime.parse("2026-05-15T00:00:00Z"));
            ps.setString(9, status);
            ps.executeUpdate();
        }
    }

    private long countSavedPosts(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM saved_post sp "
                             + "JOIN users u ON u.id = sp.user_id "
                             + "WHERE u.contact_id = ?")) {
            ps.setString(1, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private int readSaveCount(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT save_count FROM users WHERE contact_id = ?")) {
            ps.setString(1, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "users row must exist for contact_id=" + contactId);
                return rs.getInt("save_count");
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
}
