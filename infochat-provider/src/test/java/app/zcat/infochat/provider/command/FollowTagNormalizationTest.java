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
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for M1-489: {@code /follow-tag} and
 * {@code /unfollow-tag} must apply the controlled-vocabulary
 * normalization pipeline ({@link TagNormalizer}: trim → NFC → lowercase
 * → char-class) before the {@code WHERE name = ?} lookup, so a
 * case/Unicode variant of a vocabulary tag resolves instead of silently
 * missing. Controlled-vocabulary tag names are themselves
 * char-class-constrained (lowercase ASCII), so the demonstrable
 * resolvable variant is a mixed-case input.
 *
 * <p>Test isolation mirrors {@link FollowTagCommandHandlerTest}:
 * per-class {@code PREFIX}, {@code @BeforeEach} prefix-scoped cleanup,
 * append-only {@code audit_log} triggers temporarily disabled in a
 * try/finally.</p>
 */
@QuarkusTest
class FollowTagNormalizationTest {

    private static final String PREFIX = "m1-489-";
    private static final String ADAPTER = "inmemory";

    @Inject FollowTagCommandHandler followHandler;
    @Inject UnfollowTagCommandHandler unfollowHandler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
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
            exec(conn, "DELETE FROM source WHERE identifier LIKE ?", PREFIX + "%");
            exec(conn, "DELETE FROM tag WHERE name LIKE ?", PREFIX + "%");
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
                exec(conn, "DELETE FROM users WHERE contact_id LIKE ?", PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
        }
    }

    @Test
    void followTagResolvesMixedCaseVariantOfControlledTag() throws Exception {
        String actor = PREFIX + "follow-actor";
        UUID actorId = seedUser(actor);
        UUID tagAi = seedTag(PREFIX + "ai");
        seedScopePreferences(actorId, "ALL");

        // Mixed-case input must normalize to the canonical "m1-489-ai"
        // and resolve — pre-M1-489 the raw "M1-489-AI" missed the exact
        // WHERE name = ? match and produced an unknown-tag error.
        OutboundMessage reply = followHandler.handle(
                new ScopeRef.Dm(actor),
                "/follow-tag " + PREFIX.toUpperCase(Locale.ROOT) + "AI");

        assertEquals(expectedFollowSuccessFromAll(PREFIX + "ai"), reply.text(),
                "mixed-case follow must resolve and surface the canonical tag name");
        assertEquals("EXPLICIT", tagModeOf(actorId),
                "resolved follow must flip tag_mode ALL → EXPLICIT");
        assertEquals(1L, countScopeTag(actorId),
                "resolved follow must seed exactly the one followed tag");
        assertTrue(scopeTagContains(actorId, tagAi),
                "resolved follow must seed the canonical vocabulary tag id");
    }

    @Test
    void unfollowTagResolvesMixedCaseVariantOfControlledTag() throws Exception {
        String actor = PREFIX + "unfollow-actor";
        UUID actorId = seedUser(actor);
        UUID tagAi = seedTag(PREFIX + "ai");
        UUID tagSec = seedTag(PREFIX + "sec");
        seedScopePreferences(actorId, "EXPLICIT");
        seedScopeTag(actorId, tagAi);
        seedScopeTag(actorId, tagSec);

        OutboundMessage reply = unfollowHandler.handle(
                new ScopeRef.Dm(actor),
                "/unfollow-tag " + PREFIX.toUpperCase(Locale.ROOT) + "AI");

        assertEquals(expectedUnfollowSuccessInPlace(PREFIX + "ai"), reply.text(),
                "mixed-case unfollow must resolve and surface the canonical tag name");
        assertEquals("EXPLICIT", tagModeOf(actorId),
                "one remaining tag keeps tag_mode EXPLICIT");
        assertFalse(scopeTagContains(actorId, tagAi),
                "resolved unfollow must remove the canonical vocabulary tag id");
        assertTrue(scopeTagContains(actorId, tagSec),
                "resolved unfollow must leave the untouched tag in place");
    }

    // ----- expected-reply helpers -----------------------------------------

    private String expectedFollowSuccessFromAll(String tagName) {
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_FOLLOW_TAG_SUCCESS_FROM_ALL),
                tagName);
    }

    private String expectedUnfollowSuccessInPlace(String tagName) {
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_UNFOLLOW_TAG_SUCCESS_IN_PLACE),
                tagName);
    }

    // ----- seed / read helpers --------------------------------------------

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
}
