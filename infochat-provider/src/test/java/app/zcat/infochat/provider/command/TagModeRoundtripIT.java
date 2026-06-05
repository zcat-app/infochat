package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-054 umbrella IT — the {@code /follow-tag} / {@code /unfollow-tag}
 * tag-mode state machine roundtrip through the full intake + dispatch
 * chain.
 *
 * <p>{@link FollowTagCommandHandlerTest} and
 * {@link UnfollowTagCommandHandlerTest} pin the per-handler Shape B
 * behaviour against a real DevServices Postgres. This IT walks the
 * end-to-end roundtrip through the real
 * {@link app.zcat.infochat.provider.messaging.InboundRouter} +
 * {@link InMemoryAdapter} so the M1-044b intake step ordering,
 * the M1-051 confirm-shape sweep, and the M1-054 state-machine
 * transitions all participate in the same narrative — the surface
 * unit-only tests cannot pin.</p>
 *
 * <p>Per the {@code InviteIntakeRoundtripIT} precedent the bot-admin
 * row is seeded via raw JDBC at {@link BeforeEach} (the
 * {@code @Startup} bootstrap-admin bean is deferred per T1-E). A
 * permanent {@code guardian} admin row remains across tests so the
 * V5 last-admin-protection trigger does not refuse per-test DELETEs
 * on admin rows.</p>
 */
@QuarkusTest
@TestProfile(TagModeRoundtripIT.RoundtripProfile.class)
class TagModeRoundtripIT {

    private static final String ADAPTER = "inmemory";
    private static final String PREFIX = "m1-054-roundtrip-";
    private static final String GUARDIAN = "guardian-m1-054-roundtrip-permanent";

    @Inject InMemoryAdapter adapter;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject ConfirmStateService confirmStateService;

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
        try (Connection conn = dataSource.getConnection()) {
            // Permanent guardian admin (mirrors the
            // InviteIntakeRoundtripIT pattern) so V5 last-admin
            // protection cannot refuse our test admin DELETEs.
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                            + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, GUARDIAN);

            exec(conn,
                    "DELETE FROM scope_tag WHERE scope_id IN "
                            + "(SELECT id FROM users WHERE contact_id LIKE ?)",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM scope_preferences WHERE scope_id IN "
                            + "(SELECT id FROM users WHERE contact_id LIKE ?)",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM source_subscription WHERE scope_id IN "
                            + "(SELECT id FROM users WHERE contact_id LIKE ?)",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM source WHERE identifier LIKE ?",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM tag WHERE name LIKE ?",
                    PREFIX + "%");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE actor_user_id IN "
                                + "(SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%");
                exec(conn,
                        "UPDATE users SET banned_by = NULL "
                                + "WHERE banned_by IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%");
                exec(conn,
                        "DELETE FROM users WHERE contact_id LIKE ?",
                        PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
        }
    }

    // ----- ALL → EXPLICIT → ALL roundtrip through the full chain ---------

    @Test
    void tagModeRoundtripAllExplicitAll() throws Exception {
        String actor = PREFIX + "u-1";
        UUID actorId = seedRegisteredUser(actor);
        UUID tagAi = seedTag(PREFIX + "ai");
        UUID tagSec = seedTag(PREFIX + "security");
        UUID tagJava = seedTag(PREFIX + "java");
        UUID sourceId = seedSourceWithBootstrapTags(PREFIX + "src-1",
                PREFIX + "ai", PREFIX + "security", PREFIX + "java");
        seedSourceSubscription(actorId, sourceId);
        seedScopePreferences(actorId, "ALL");
        long versionBefore = tagSubscriptionVersionOf(actorId);

        assertEquals("ALL", tagModeOf(actorId),
                "precondition: scope starts in tag_mode='ALL'");

        // (1) /follow-tag ai — ALL → EXPLICIT, seed single tag
        adapter.deliverDm(actor, "/follow-tag " + PREFIX + "ai");
        List<OutboundMessage> after1 = adapter.sentMessages();
        assertEquals(1, after1.size(),
                "/follow-tag must produce exactly one outbound");
        assertEquals("EXPLICIT", tagModeOf(actorId),
                "/follow-tag ai in ALL mode must flip tag_mode to EXPLICIT");
        assertEquals(1L, countScopeTag(actorId),
                "scope_tag must contain exactly one row after /follow-tag");
        assertTrue(scopeTagContains(actorId, tagAi),
                "scope_tag must contain the followed tag (ai)");
        adapter.reset();

        // (2) /follow-tag java — EXPLICIT add in place
        adapter.deliverDm(actor, "/follow-tag " + PREFIX + "java");
        assertEquals("EXPLICIT", tagModeOf(actorId),
                "tag_mode must remain EXPLICIT after a second /follow-tag");
        assertEquals(2L, countScopeTag(actorId),
                "scope_tag must contain {ai, java} after the second /follow-tag");
        assertTrue(scopeTagContains(actorId, tagAi));
        assertTrue(scopeTagContains(actorId, tagJava));
        assertFalse(scopeTagContains(actorId, tagSec),
                "scope_tag must NOT yet contain 'security' (not /follow-tag'd)");
        adapter.reset();

        // (3) /unfollow-tag ai — EXPLICIT remove-in-place
        adapter.deliverDm(actor, "/unfollow-tag " + PREFIX + "ai");
        assertEquals("EXPLICIT", tagModeOf(actorId),
                "tag_mode must remain EXPLICIT — followed set non-empty");
        assertEquals(1L, countScopeTag(actorId),
                "scope_tag must contain {java} after /unfollow-tag ai");
        assertTrue(scopeTagContains(actorId, tagJava));
        assertFalse(scopeTagContains(actorId, tagAi));
        adapter.reset();

        // (4) /unfollow-tag java — EXPLICIT flip-back-to-ALL on empty set
        adapter.deliverDm(actor, "/unfollow-tag " + PREFIX + "java");
        assertEquals("ALL", tagModeOf(actorId),
                "tag_mode must flip back to ALL when the followed set empties");
        assertEquals(0L, countScopeTag(actorId),
                "scope_tag must be empty after the flip-back");

        // version bump assertion — every state-machine transition bumps
        // tag_subscription_version exactly once.
        assertEquals(versionBefore + 4L, tagSubscriptionVersionOf(actorId),
                "tag_subscription_version must have incremented exactly four times "
                        + "across the round-trip (one bump per /follow-tag / /unfollow-tag call)");
    }

    // ----- /unfollow-tag --all confirm-gated bulk-reset roundtrip --------

    @Test
    void unfollowTagAllConfirmRoundtrip() throws Exception {
        String actor = PREFIX + "u-2";
        UUID actorId = seedRegisteredUser(actor);
        UUID tagAi = seedTag(PREFIX + "ai");
        UUID tagSec = seedTag(PREFIX + "security");
        seedScopePreferences(actorId, "EXPLICIT");
        seedScopeTag(actorId, tagAi);
        seedScopeTag(actorId, tagSec);
        long versionBefore = tagSubscriptionVersionOf(actorId);

        // (1) /unfollow-tag --all — prompt + no state change
        adapter.deliverDm(actor, "/unfollow-tag --all");
        List<OutboundMessage> after1 = adapter.sentMessages();
        assertEquals(1, after1.size(),
                "/unfollow-tag --all first call must produce exactly one outbound (prompt)");
        String promptBody = after1.get(0).text();
        assertTrue(promptBody.contains("--all confirm"),
                "prompt must instruct the user to type the confirm form — got: "
                        + promptBody);
        assertTrue(promptBody.contains("2"),
                "prompt must echo the current row count (2) — got: " + promptBody);
        assertEquals("EXPLICIT", tagModeOf(actorId),
                "first call must NOT flip tag_mode");
        assertEquals(2L, countScopeTag(actorId),
                "first call must NOT delete any scope_tag row");
        assertEquals(versionBefore, tagSubscriptionVersionOf(actorId),
                "first call must NOT bump tag_subscription_version");
        adapter.reset();

        // (2) /unfollow-tag --all confirm — bulk wipe + flip to ALL
        adapter.deliverDm(actor, "/unfollow-tag --all confirm");
        List<OutboundMessage> after2 = adapter.sentMessages();
        assertEquals(1, after2.size(),
                "/unfollow-tag --all confirm must produce exactly one outbound");
        String confirmBody = after2.get(0).text();
        String expectedConfirmReply = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_UNFOLLOW_TAG_ALL_SUCCESS),
                Long.toString(2L));
        assertEquals(expectedConfirmReply, confirmBody,
                "confirm reply must surface reply.unfollow_tag_all.success with the "
                        + "deletion count");
        assertEquals(0L, countScopeTag(actorId),
                "scope_tag must be empty after the bulk reset");
        assertEquals("ALL", tagModeOf(actorId),
                "tag_mode must flip back to ALL after the bulk reset");
        assertEquals(versionBefore + 1L, tagSubscriptionVersionOf(actorId),
                "bulk reset must bump tag_subscription_version exactly once");
    }

    // ----- helpers --------------------------------------------------------

    private UUID seedRegisteredUser(String contactId) throws Exception {
        // Already past invite-consume (registration_state='vouched'),
        // past probation (probation_until NULL), not banned. The
        // intake step ordering's steps 2/3/4/4.5/4.7/5 all pass-through
        // for this shape.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state, probation_until) "
                             + "VALUES (?, ?, FALSE, FALSE, 'vouched', NULL) "
                             + "RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID seedTag(String tagName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tag (name, display) VALUES (?, ?) RETURNING id")) {
            ps.setString(1, tagName);
            ps.setString(2, tagName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID seedSourceWithBootstrapTags(String identifier, String... bootstrapTags)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags) VALUES ('rss', ?, ?, 'news', ?) RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, identifier);
            Array tagsArray = conn.createArrayOf("TEXT", bootstrapTags);
            ps.setArray(3, tagsArray);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedSourceSubscription(UUID scopeId, UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                             + "VALUES ('dm', ?, ?)")) {
            ps.setObject(1, scopeId);
            ps.setObject(2, sourceId);
            ps.executeUpdate();
        }
    }

    private void seedScopePreferences(UUID scopeId, String tagMode) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO scope_preferences (scope_kind, scope_id, tag_mode) "
                             + "VALUES ('dm', ?, ?)")) {
            ps.setObject(1, scopeId);
            ps.setString(2, tagMode);
            ps.executeUpdate();
        }
    }

    private void seedScopeTag(UUID scopeId, UUID tagId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO scope_tag (scope_kind, scope_id, tag_id) "
                             + "VALUES ('dm', ?, ?)")) {
            ps.setObject(1, scopeId);
            ps.setObject(2, tagId);
            ps.executeUpdate();
        }
    }

    private String tagModeOf(UUID scopeId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT tag_mode FROM scope_preferences "
                             + "WHERE scope_kind = 'dm' AND scope_id = ?")) {
            ps.setObject(1, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(),
                        "scope_preferences row must exist for scope_id=" + scopeId);
                return rs.getString("tag_mode");
            }
        }
    }

    private long tagSubscriptionVersionOf(UUID scopeId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT tag_subscription_version FROM scope_preferences "
                             + "WHERE scope_kind = 'dm' AND scope_id = ?")) {
            ps.setObject(1, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(),
                        "scope_preferences row must exist for scope_id=" + scopeId);
                return rs.getLong(1);
            }
        }
    }

    private long countScopeTag(UUID scopeId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM scope_tag "
                             + "WHERE scope_kind = 'dm' AND scope_id = ?")) {
            ps.setObject(1, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private boolean scopeTagContains(UUID scopeId, UUID tagId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM scope_tag "
                             + "WHERE scope_kind = 'dm' AND scope_id = ? AND tag_id = ?")) {
            ps.setObject(1, scopeId);
            ps.setObject(2, tagId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
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
     * Mirrors the {@code InviteIntakeRoundtripIT.RoundtripProfile}
     * shape: pins the {@code inmemory} adapter as the sole enabled
     * adapter and opts into low-trust delivery so the IT runs against
     * the same single-adapter config the other umbrella ITs use.
     */
    public static final class RoundtripProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true");
        }
    }
}
