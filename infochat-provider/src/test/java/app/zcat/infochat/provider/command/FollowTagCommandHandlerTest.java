package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.MessageFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link FollowTagCommandHandler} against the
 * DevServices Postgres container (V7 scope_preferences + scope_tag,
 * V6 tag). One {@code @Test} per acceptance item 2 scenario in
 * M1-054.
 *
 * <p>Test isolation: per-test sub-prefix within the class-wide
 * {@code PREFIX} ({@code m1-054-follow-}); the {@link #cleanup()}
 * {@code @BeforeEach} deletes test rows by the class-wide prefix.
 * {@code audit_log} is append-only (V5 {@code trg_audit_log_*}
 * triggers); cleanup temporarily disables those triggers in a
 * try/finally so the table cannot be left without its invariant —
 * the same pattern M1-046 GrantAdminCommandHandlerTest established.</p>
 *
 * @implNote Canonical thin-SQL handler test per
 *     {@code docs/process/test-pyramid.md} §Shape B: Thin-SQL.
 */
@QuarkusTest
class FollowTagCommandHandlerTest {

    private static final String PREFIX = "m1-054-follow-";
    private static final String ADAPTER = "inmemory";

    @Inject FollowTagCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            // Delete dependent rows first so the users DELETE below
            // does not violate the FK chain. scope_preferences /
            // scope_tag / source_subscription all key on scope_id =
            // users.id (DM scope); identify the rows to delete by
            // selecting users.id under our PREFIX.
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

    // ----- (a) ALL → EXPLICIT: flip + seed the single followed tag --------

    @Test
    void followTagDmInAllModeFlipsToExplicitAndSeedsSingleTag() throws Exception {
        String actor = PREFIX + "allFlip-actor";
        UUID actorId = seedUser(actor);
        UUID tagAi = seedTag(PREFIX + "ai");
        seedScopePreferences(actorId, "ALL");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/follow-tag " + PREFIX + "ai");

        assertEquals(expectedSuccessFromAll(PREFIX + "ai"), reply.text(),
                "ALL → EXPLICIT flip must surface reply.follow_tag.success_from_all");
        assertEquals("EXPLICIT", tagModeOf(actorId),
                "tag_mode must flip to EXPLICIT");
        assertEquals(1L, countScopeTag(actorId),
                "scope_tag must contain exactly one row (the followed tag)");
        assertTrue(scopeTagContains(actorId, tagAi),
                "scope_tag must contain the followed tag");
    }

    // ----- (b) EXPLICIT mode: idempotent add in place ---------------------

    @Test
    void followTagDmInExplicitModeAddsRowInPlace() throws Exception {
        String actor = PREFIX + "explicitAdd-actor";
        UUID actorId = seedUser(actor);
        UUID tagAi = seedTag(PREFIX + "ai");
        UUID tagSec = seedTag(PREFIX + "security");
        seedScopePreferences(actorId, "EXPLICIT");
        seedScopeTag(actorId, tagAi);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/follow-tag " + PREFIX + "security");

        assertEquals(expectedSuccessInPlace(PREFIX + "security"), reply.text(),
                "EXPLICIT add must surface reply.follow_tag.success_in_place");
        assertEquals("EXPLICIT", tagModeOf(actorId),
                "tag_mode must remain EXPLICIT");
        assertEquals(2L, countScopeTag(actorId),
                "scope_tag must now contain both the prior and the new tag");
        assertTrue(scopeTagContains(actorId, tagAi));
        assertTrue(scopeTagContains(actorId, tagSec));
    }

    // ----- (c) EXPLICIT mode: duplicate add is idempotent -----------------

    @Test
    void followTagDmInExplicitModeIdempotentOnDuplicateAdd() throws Exception {
        String actor = PREFIX + "dup-actor";
        UUID actorId = seedUser(actor);
        UUID tagAi = seedTag(PREFIX + "ai");
        seedScopePreferences(actorId, "EXPLICIT");
        seedScopeTag(actorId, tagAi);
        long versionBefore = tagSubscriptionVersionOf(actorId);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/follow-tag " + PREFIX + "ai");

        assertEquals(expectedSuccessInPlace(PREFIX + "ai"), reply.text(),
                "duplicate /follow-tag must surface the same success-in-place reply");
        assertEquals(1L, countScopeTag(actorId),
                "scope_tag row count must NOT double on duplicate add");
        assertEquals(versionBefore + 1L, tagSubscriptionVersionOf(actorId),
                "tag_subscription_version must still increment on a duplicate add — the "
                        + "version counter is the digest-cache key, not a uniqueness counter");
    }

    // ----- (d) Unknown tag → fuzzy-suggestion error -----------------------

    @Test
    void followTagDmUnknownTagReturnsFuzzySuggestionError() throws Exception {
        String actor = PREFIX + "unknown-actor";
        UUID actorId = seedUser(actor);
        seedTag(PREFIX + "ai");
        seedScopePreferences(actorId, "ALL");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/follow-tag " + PREFIX + "notavocab");

        assertTrue(reply.text().contains(PREFIX + "notavocab"),
                "unknown-tag reply must echo the supplied tag — got: " + reply.text());
        assertTrue(reply.text().contains("Did you mean"),
                "unknown-tag reply must carry the fuzzy-suggestion footer — got: "
                        + reply.text());
        assertEquals("ALL", tagModeOf(actorId),
                "unknown-tag reject must not flip tag_mode");
        assertEquals(0L, countScopeTag(actorId),
                "unknown-tag reject must not seed any scope_tag row");
    }

    // ----- (e) Group scope short-circuits ---------------------------------

    @Test
    void followTagGroupScopeShortCircuitsToGroupAdminOnly() throws Exception {
        // Group scope short-circuits without an actor lookup or
        // scope_preferences row — no DB seeding needed for this
        // scenario. The frozen CommandHandler SPI does not carry the
        // group-scope caller's contact id, so v1 cannot distinguish
        // admin from non-admin and rejects ALL group calls. T2-F
        // lands the actor seam + the group-admin proceed path test.
        OutboundMessage reply = handler.handle(
                new ScopeRef.Group("some-group-id-" + UUID.randomUUID()),
                "/follow-tag " + PREFIX + "anytag");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_FOLLOW_TAG_GROUP_ADMIN_ONLY),
                reply.text(),
                "group-scope /follow-tag must surface error.follow_tag.group_admin_only");
    }

    // ----- (f) tag_subscription_version bumps on every mutation -----------

    @Test
    void followTagIncrementsTagSubscriptionVersion() throws Exception {
        String actor = PREFIX + "version-actor";
        UUID actorId = seedUser(actor);
        UUID tagAi = seedTag(PREFIX + "ai");
        seedTag(PREFIX + "security");
        seedScopePreferences(actorId, "ALL");
        long versionBefore = tagSubscriptionVersionOf(actorId);

        handler.handle(new ScopeRef.Dm(actor), "/follow-tag " + PREFIX + "ai");
        long versionAfterFlip = tagSubscriptionVersionOf(actorId);
        assertEquals(versionBefore + 1L, versionAfterFlip,
                "ALL → EXPLICIT flip must bump tag_subscription_version exactly once");

        handler.handle(new ScopeRef.Dm(actor), "/follow-tag " + PREFIX + "security");
        long versionAfterAdd = tagSubscriptionVersionOf(actorId);
        assertEquals(versionAfterFlip + 1L, versionAfterAdd,
                "EXPLICIT add must bump tag_subscription_version exactly once");
    }

    // ----- (g) Handler writes zero rows to audit_log ----------------------

    @Test
    void followTagWritesNoAuditRow() throws Exception {
        String actor = PREFIX + "noAudit-actor";
        UUID actorId = seedUser(actor);
        seedTag(PREFIX + "ai");
        seedScopePreferences(actorId, "ALL");

        long auditBefore = countAuditByActor(actorId);
        handler.handle(new ScopeRef.Dm(actor), "/follow-tag " + PREFIX + "ai");
        long auditAfter = countAuditByActor(actorId);

        assertEquals(auditBefore, auditAfter,
                "/follow-tag must write zero rows to audit_log — tag-preference "
                        + "mutations are user-preference, not privileged action");
    }

    // ----- helpers --------------------------------------------------------

    private String expectedSuccessFromAll(String tagName) {
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_FOLLOW_TAG_SUCCESS_FROM_ALL),
                tagName);
    }

    private String expectedSuccessInPlace(String tagName) {
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_FOLLOW_TAG_SUCCESS_IN_PLACE),
                tagName);
    }

    private UUID seedUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, registration_state) "
                             + "VALUES (?, ?, 'vouched') RETURNING id")) {
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
                assertTrue(rs.next(), "scope_preferences row must exist for scope_id=" + scopeId);
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

    private long countAuditByActor(UUID actorId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE actor_user_id = ?")) {
            ps.setObject(1, actorId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
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
