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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link UnsaveCommandHandler} against the
 * DevServices Postgres container (V15 saved_post + trigger). One
 * {@code @Test} per acceptance scenario in M1-052 acceptance item 7.
 *
 * <p>Test isolation: per-test sub-prefix within the class-wide
 * {@code PREFIX} ({@code m1-052-unsave-}); the {@link #cleanup()}
 * {@code @BeforeEach} deletes rows under the class-wide prefix.</p>
 *
 * @implNote Shape B (Thin-SQL) per
 *     {@code docs/process/test-pyramid.md} §Handler unit tests —
 *     the handler issues a single DELETE but the trigger-driven
 *     users.save_count decrement is load-bearing (the cap-reset
 *     mechanism — saturate / unsave / save-readmits — depends on
 *     the trigger firing on every DELETE), so real-DB observation
 *     against the {@code unsaveAfterSaveAtCapAllowsSubsequentSave}
 *     scenario is the only honest verification.
 */
@QuarkusTest
class UnsaveCommandHandlerTest {

    private static final String PREFIX = "m1-052-unsave-";
    private static final String ADAPTER = "inmemory";

    @Inject UnsaveCommandHandler unsaveHandler;
    @Inject SaveCommandHandler saveHandler;
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
            exec(conn,
                    "DELETE FROM saved_post WHERE user_id IN ("
                            + "SELECT id FROM users WHERE contact_id LIKE ?)",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM post WHERE source_id IN ("
                            + "SELECT id FROM source WHERE identifier LIKE ?)",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM source WHERE identifier LIKE ?",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM users WHERE contact_id LIKE ?",
                    PREFIX + "%");
        }
    }

    @Test
    void unsaveHappyPathRemovesRowAndDecrementsSaveCount() throws Exception {
        String contactId = PREFIX + "happy-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "happy-source");
        String uid = PREFIX + "happy-uid";
        seedSavedPost(userId, sourceId, uid);
        // The trigger fired on the seed INSERT.
        assertEquals(1, readSaveCount(contactId),
                "users.save_count must be 1 after the seed INSERT (trigger increment)");

        OutboundMessage reply = unsaveHandler.handle(new ScopeRef.Dm(contactId), "/unsave " + uid);

        assertEquals(MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_UNSAVE_SUCCESS), uid),
                reply.text(),
                "/unsave success reply must interpolate the UID");
        assertEquals(0L, countSavedPostsForUser(userId),
                "saved_post row must be removed after /unsave");
        assertEquals(0, readSaveCount(contactId),
                "users.save_count must decrement to 0 after /unsave (trigger decrement)");
    }

    @Test
    void unsaveUnknownUidReturnsUnknownUidAndLeavesSaveCountUnchanged() throws Exception {
        String contactId = PREFIX + "unkn-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "unkn-source");
        seedSavedPost(userId, sourceId, PREFIX + "unkn-other-uid");
        assertEquals(1, readSaveCount(contactId),
                "seed save must put save_count at 1");

        OutboundMessage reply = unsaveHandler.handle(
                new ScopeRef.Dm(contactId), "/unsave " + PREFIX + "no-such-uid");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_UNSAVE_UNKNOWN_UID), reply.text(),
                "unknown UID must surface error.unsave.unknown_uid");
        assertEquals(1L, countSavedPostsForUser(userId),
                "the unrelated saved_post row must remain");
        assertEquals(1, readSaveCount(contactId),
                "users.save_count must remain unchanged after the unknown-uid reject");
    }

    @Test
    void unsaveAfterSaveAtCapAllowsSubsequentSave() throws Exception {
        // Saturate at cap via SaveCommandHandler (which exercises the
        // real cap mechanism); /unsave one row; assert the trigger-
        // driven decrement lets a subsequent /save admit.
        String contactId = PREFIX + "reset-actor";
        seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "reset-source");
        String[] capUids = new String[saveCap];
        for (int i = 0; i < saveCap; i++) {
            capUids[i] = PREFIX + "reset-uid-" + i;
            seedReadyPost(sourceId, capUids[i]);
            OutboundMessage r = saveHandler.handle(
                    new ScopeRef.Dm(contactId), "/save " + capUids[i]);
            assertEquals(MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_SAVE_SUCCESS), capUids[i]),
                    r.text(), "seed /save must succeed at index " + i);
        }
        assertEquals(saveCap, readSaveCount(contactId),
                "save_count must equal cap after saturation");

        // Sanity: a /save while at-cap must reject.
        String overflowUid = PREFIX + "reset-overflow";
        seedReadyPost(sourceId, overflowUid);
        OutboundMessage atCap = saveHandler.handle(
                new ScopeRef.Dm(contactId), "/save " + overflowUid);
        assertEquals(bundleLoader.get(BundleKeys.ERROR_SAVE_CAP_MET), atCap.text(),
                "at-cap /save must reject before /unsave runs");

        // /unsave one — the trigger must decrement save_count to cap-1.
        unsaveHandler.handle(new ScopeRef.Dm(contactId), "/unsave " + capUids[0]);
        assertEquals(saveCap - 1, readSaveCount(contactId),
                "trigger must decrement save_count after /unsave");

        // Now /save the overflow UID admits.
        OutboundMessage afterReset = saveHandler.handle(
                new ScopeRef.Dm(contactId), "/save " + overflowUid);
        assertEquals(MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_SAVE_SUCCESS), overflowUid),
                afterReset.text(),
                "post-/unsave /save must admit — trigger-driven cap reset");
        assertEquals(saveCap, readSaveCount(contactId),
                "save_count must equal cap after the readmit");
    }

    @Test
    void unsaveFromGroupScopeReturnsGroupNotInV1() throws Exception {
        OutboundMessage reply = unsaveHandler.handle(
                new ScopeRef.Group("adapter-group-id"), "/unsave abc");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_UNSAVE_GROUP_NOT_IN_V1), reply.text(),
                "group-scope /unsave must short-circuit with error.unsave.group_not_in_v1");
        // No actor seeded for this branch, so no saved_post row could exist.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM saved_post WHERE post_uid = 'abc'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertFalse(rs.next(),
                        "group-scope short-circuit must not touch saved_post");
            }
        }
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

    private UUID seedSource(String identifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags) VALUES ('rss', ?, ?, 'news', '{}') "
                             + "RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, "Test Source " + identifier);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedSavedPost(UUID userId, UUID sourceId, String postUid) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO saved_post (user_id, post_uid, source_id, title, "
                             + "snapshot_tags, personal_tags, saved_at) "
                             + "VALUES (?, ?, ?, 'seed', '{}', '{}', NOW())")) {
            ps.setObject(1, userId);
            ps.setString(2, postUid);
            ps.setObject(3, sourceId);
            ps.executeUpdate();
        }
    }

    private void seedReadyPost(UUID sourceId, String uid) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (source_id, uid, title, body, fetched_at, status) "
                             + "VALUES (?, ?, ?, 'b', ?, 'READY')")) {
            ps.setObject(1, sourceId);
            ps.setString(2, uid);
            ps.setString(3, "title-" + uid);
            // fetched_at must fall inside the V7 bootstrap partition
            // (2026-05-01 .. 2026-06-01).
            ps.setObject(4, OffsetDateTime.parse("2026-05-15T00:00:00Z"));
            ps.executeUpdate();
        }
    }

    private long countSavedPostsForUser(UUID userId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM saved_post WHERE user_id = ?")) {
            ps.setObject(1, userId);
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
