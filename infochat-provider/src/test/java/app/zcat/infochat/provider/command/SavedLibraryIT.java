package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end roundtrip for the saved-post library via the
 * InMemoryAdapter — mirrors the M1-036 {@link AddSourceIT} pattern. One
 * scenario drives {@code /save} → {@code /saved} → {@code /unsave} from
 * a DM actor and asserts the expected outbound reply at each step plus
 * the {@code users.save_count} denormalization across the roundtrip.
 *
 * <p>The cross-scope INVOCATION branch (a Group-scope {@code /saved}
 * reading DM-saved rows) is T2-F territory — the v1 frozen
 * {@code ScopeRef.Group} record carries no actor contact id. The spec
 * §Content disclosure-header letter is satisfied in v1 by the
 * {@code reply.saved.header.global} text always being present on the
 * DM /saved reply, which this IT asserts.</p>
 */
@QuarkusTest
class SavedLibraryIT {

    private static final String PREFIX = "m1-052-it-";
    private static final String ACTOR = PREFIX + "actor";
    private static final String UID = PREFIX + "uid";

    @Inject InMemoryAdapter adapter;
    @Inject DataSource dataSource;

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
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
            // Pre-seed the actor in the post-invite-consume state
            // (registration_state='vouched', no probation) so the
            // intake-step splice routes the slash command through to
            // the handler. /save and /unsave are NOT in the slow-start
            // allowed set per CommandPermissions, so probation must be
            // cleared for those steps to dispatch.
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                            + "registration_state, probation_until) "
                            + "VALUES ('inmemory', ?, FALSE, FALSE, 'vouched', NULL)")) {
                ps.setString(1, ACTOR);
                ps.executeUpdate();
            }
            UUID sourceId = seedSource(conn, PREFIX + "source");
            seedReadyPost(conn, sourceId, UID);
        }
    }

    @Test
    void saveListUnsaveRoundtripWithDisclosureHeader() throws Exception {
        // (a) /save <uid> — expect reply.save.success.
        adapter.deliverDm(ACTOR, "/save " + UID);
        List<OutboundMessage> afterSave = adapter.sentMessages();
        assertEquals(1, afterSave.size(),
                "exactly one outbound reply expected after /save");
        assertTrue(afterSave.get(0).text().startsWith("Saved "),
                "outbound /save reply must surface the success literal; got: "
                        + afterSave.get(0).text());
        assertEquals(1, readSaveCount(),
                "users.save_count must be 1 after /save (trigger increment)");

        // (b) /saved — expect the disclosure header + the row.
        adapter.deliverDm(ACTOR, "/saved");
        List<OutboundMessage> afterList = adapter.sentMessages();
        assertEquals(2, afterList.size(),
                "exactly one additional outbound reply expected after /saved");
        String savedBody = afterList.get(1).text();
        assertTrue(savedBody.contains("global across DM and groups"),
                "/saved reply header MUST disclose cross-scope visibility "
                        + "(spec §Content disclosure-header letter); got: " + savedBody);
        assertTrue(savedBody.contains(UID),
                "/saved listing must include the saved UID; got: " + savedBody);

        // (c) /unsave <uid> — expect reply.unsave.success and zero counter.
        adapter.deliverDm(ACTOR, "/unsave " + UID);
        List<OutboundMessage> afterUnsave = adapter.sentMessages();
        assertEquals(3, afterUnsave.size(),
                "exactly one additional outbound reply expected after /unsave");
        assertTrue(afterUnsave.get(2).text().startsWith("Removed "),
                "outbound /unsave reply must surface the success literal; got: "
                        + afterUnsave.get(2).text());
        assertEquals(0L, countSavedPostRows(),
                "saved_post row must be removed after /unsave");
        assertEquals(0, readSaveCount(),
                "users.save_count must be 0 after /unsave (trigger decrement)");
    }

    private UUID seedSource(Connection conn, String identifier) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
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

    private void seedReadyPost(Connection conn, UUID sourceId, String uid) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO post (source_id, uid, title, body, fetched_at, status) "
                        + "VALUES (?, ?, 'Roundtrip Title', 'Roundtrip Body', ?, 'READY')")) {
            ps.setObject(1, sourceId);
            ps.setString(2, uid);
            ps.setObject(3, OffsetDateTime.parse("2026-05-15T00:00:00Z"));
            ps.executeUpdate();
        }
    }

    private long countSavedPostRows() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM saved_post sp "
                             + "JOIN users u ON u.id = sp.user_id WHERE u.contact_id = ?")) {
            ps.setString(1, ACTOR);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private int readSaveCount() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT save_count FROM users WHERE contact_id = ?")) {
            ps.setString(1, ACTOR);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "actor row must exist for contact_id=" + ACTOR);
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
