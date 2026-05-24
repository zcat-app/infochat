package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.MessageFormat;
import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link UnfollowTagCommandHandler} against the
 * DevServices Postgres container (V7 scope_preferences + scope_tag +
 * source_subscription, V6 source + tag). One {@code @Test} per
 * acceptance item 4 scenario in M1-054.
 *
 * <p>Test isolation: per-test sub-prefix within the class-wide
 * {@code PREFIX} ({@code m1-054-unfollow-}); the {@link #cleanup()}
 * {@code @BeforeEach} deletes test rows by the class-wide prefix.
 * {@code audit_log} is append-only; cleanup disables the
 * append-only triggers around the per-test DELETE in a try/finally —
 * the same M1-046 GrantAdminCommandHandlerTest / M1-044c
 * BanCommandHandlerTest precedent.</p>
 *
 * <p>{@link #restoreClock()} {@code @AfterEach} restores the production
 * {@link Clock} on the shared {@link ConfirmStateService} bean —
 * matches the M1-044c BanCommandHandlerTest pattern so subsequent
 * tests in the same Quarkus boot start with a non-warped clock.</p>
 *
 * @implNote Canonical thin-SQL handler test per
 *     {@code docs/process/test-pyramid.md} §Shape B: Thin-SQL.
 */
@QuarkusTest
class UnfollowTagCommandHandlerTest {

    private static final String PREFIX = "m1-054-unfollow-";
    private static final String ADAPTER = "inmemory";

    @Inject UnfollowTagCommandHandler handler;
    @Inject DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject ConfirmStateService confirmStateService;

    @AfterEach
    void restoreClock() {
        // ConfirmStateService is @ApplicationScoped and lives across
        // @Test methods within the same Quarkus boot; restore the
        // production clock so any future test method starts clean.
        confirmStateService.setClock(Clock.systemUTC());
    }

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

    // ----- (a) ALL → EXPLICIT: seed `all_bootstrap_tags - unfollowed` -----

    @Test
    void unfollowTagDmInAllModeFlipsToExplicitAndSeedsAllMinusOne() throws Exception {
        String actor = PREFIX + "allMinusOne-actor";
        UUID actorId = seedUser(actor);
        UUID tagA = seedTag(PREFIX + "a");
        UUID tagB = seedTag(PREFIX + "b");
        UUID tagC = seedTag(PREFIX + "c");
        // Seed one source with bootstrap_tags = {a, b, c} and subscribe
        // the actor's DM scope to it; the ALL → EXPLICIT seed-minus-one
        // join must yield {a, c} after unfollowing b.
        UUID sourceId = seedSourceWithBootstrapTags(PREFIX + "src-1",
                PREFIX + "a", PREFIX + "b", PREFIX + "c");
        seedSourceSubscription(actorId, sourceId);
        seedScopePreferences(actorId, "ALL");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/unfollow-tag " + PREFIX + "b");

        assertEquals(expectedSuccessFromAll(PREFIX + "b"), reply.text(),
                "ALL → EXPLICIT must surface reply.unfollow_tag.success_from_all");
        assertEquals("EXPLICIT", tagModeOf(actorId),
                "tag_mode must flip to EXPLICIT");
        assertEquals(2L, countScopeTag(actorId),
                "scope_tag must contain exactly two rows (all bootstrap tags minus the unfollowed)");
        assertTrue(scopeTagContains(actorId, tagA), "scope_tag must contain tag A");
        assertTrue(scopeTagContains(actorId, tagC), "scope_tag must contain tag C");
        assertFalse(scopeTagContains(actorId, tagB),
                "scope_tag must NOT contain the unfollowed tag B");
    }

    // ----- (b) EXPLICIT mode: remove in place (followed set non-empty) ----

    @Test
    void unfollowTagDmInExplicitModeRemovesRowInPlace() throws Exception {
        String actor = PREFIX + "explicitRm-actor";
        UUID actorId = seedUser(actor);
        UUID tagA = seedTag(PREFIX + "a");
        UUID tagB = seedTag(PREFIX + "b");
        seedScopePreferences(actorId, "EXPLICIT");
        seedScopeTag(actorId, tagA);
        seedScopeTag(actorId, tagB);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/unfollow-tag " + PREFIX + "a");

        assertEquals(expectedSuccessInPlace(PREFIX + "a"), reply.text(),
                "in-place removal must surface reply.unfollow_tag.success_in_place");
        assertEquals("EXPLICIT", tagModeOf(actorId),
                "tag_mode must remain EXPLICIT — followed set is still non-empty");
        assertEquals(1L, countScopeTag(actorId));
        assertFalse(scopeTagContains(actorId, tagA));
        assertTrue(scopeTagContains(actorId, tagB));
    }

    // ----- (c) EXPLICIT mode: post-delete count zero flips back to ALL ----

    @Test
    void unfollowTagDmInExplicitModeRowCountToZeroFlipsBackToAll() throws Exception {
        String actor = PREFIX + "flipBack-actor";
        UUID actorId = seedUser(actor);
        UUID tagA = seedTag(PREFIX + "a");
        seedScopePreferences(actorId, "EXPLICIT");
        seedScopeTag(actorId, tagA);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/unfollow-tag " + PREFIX + "a");

        assertEquals(expectedFlipsBackToAll(PREFIX + "a"), reply.text(),
                "the empty-set transition must surface reply.unfollow_tag.flips_back_to_all");
        assertEquals("ALL", tagModeOf(actorId),
                "tag_mode must flip back to ALL when the followed set empties");
        assertEquals(0L, countScopeTag(actorId),
                "scope_tag must be empty after the flip-back");
    }

    // ----- (d) Unknown tag → fuzzy-suggestion error -----------------------

    @Test
    void unfollowTagDmUnknownTagReturnsFuzzySuggestionError() throws Exception {
        String actor = PREFIX + "unknown-actor";
        UUID actorId = seedUser(actor);
        seedTag(PREFIX + "a");
        seedScopePreferences(actorId, "EXPLICIT");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/unfollow-tag " + PREFIX + "notavocab");

        assertTrue(reply.text().contains(PREFIX + "notavocab"),
                "unknown-tag reply must echo the supplied tag — got: " + reply.text());
        assertTrue(reply.text().contains("Did you mean"),
                "unknown-tag reply must carry the fuzzy-suggestion footer — got: "
                        + reply.text());
        assertEquals("EXPLICIT", tagModeOf(actorId),
                "unknown-tag reject must not flip tag_mode");
        assertEquals(0L, countScopeTag(actorId),
                "unknown-tag reject must not touch scope_tag");
    }

    // ----- (e) Group scope short-circuits ---------------------------------

    @Test
    void unfollowTagGroupScopeShortCircuitsToGroupAdminOnly() throws Exception {
        OutboundMessage reply = handler.handle(
                new ScopeRef.Group("some-group-id-" + UUID.randomUUID()),
                "/unfollow-tag " + PREFIX + "anytag");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_UNFOLLOW_TAG_GROUP_ADMIN_ONLY),
                reply.text(),
                "group-scope /unfollow-tag must surface error.unfollow_tag.group_admin_only");
    }

    // ----- (f) --all first call → prompt + no state change ----------------

    @Test
    void unfollowTagAllFirstCallReturnsPromptAndNoStateChange() throws Exception {
        String actor = PREFIX + "allFirst-actor";
        UUID actorId = seedUser(actor);
        UUID tagA = seedTag(PREFIX + "a");
        seedScopePreferences(actorId, "EXPLICIT");
        seedScopeTag(actorId, tagA);
        long versionBefore = tagSubscriptionVersionOf(actorId);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/unfollow-tag --all");

        assertEquals(expectedConfirmPrompt(1L), reply.text(),
                "first /unfollow-tag --all must return the confirm prompt with the current row count");
        assertEquals(1L, countScopeTag(actorId),
                "first /unfollow-tag --all must not delete any scope_tag row");
        assertEquals("EXPLICIT", tagModeOf(actorId),
                "first /unfollow-tag --all must not flip tag_mode");
        assertEquals(versionBefore, tagSubscriptionVersionOf(actorId),
                "first /unfollow-tag --all must not bump tag_subscription_version");

        assertTrue(confirmStateService.peek(actorId, new ScopeRef.Dm(actor)).isPresent(),
                "ConfirmStateService.peek must show a pending entry for the actor + scope");
        assertEquals("unfollow-tag-all",
                confirmStateService.peek(actorId, new ScopeRef.Dm(actor)).get().commandName(),
                "pending entry's commandName must be \"unfollow-tag-all\"");
    }

    // ----- (g) --all confirm: bulk wipe + flip to ALL ---------------------

    @Test
    void unfollowTagAllConfirmWithinWindowDeletesAllRowsAndFlipsToAll() throws Exception {
        String actor = PREFIX + "allConfirm-actor";
        UUID actorId = seedUser(actor);
        UUID tagA = seedTag(PREFIX + "a");
        UUID tagB = seedTag(PREFIX + "b");
        seedScopePreferences(actorId, "EXPLICIT");
        seedScopeTag(actorId, tagA);
        seedScopeTag(actorId, tagB);

        // First call — register pending.
        handler.handle(new ScopeRef.Dm(actor), "/unfollow-tag --all");

        // Second call — bulk reset.
        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/unfollow-tag --all confirm");

        assertEquals(expectedAllSuccess(2L), reply.text(),
                "/unfollow-tag --all confirm must surface reply.unfollow_tag_all.success");
        assertEquals(0L, countScopeTag(actorId),
                "scope_tag must be empty after the bulk reset");
        assertEquals("ALL", tagModeOf(actorId),
                "tag_mode must flip back to ALL after the bulk reset");
        assertFalse(confirmStateService.peek(actorId, new ScopeRef.Dm(actor)).isPresent(),
                "pending entry must be cleared after the confirm consumes it");
    }

    // ----- (h) --all confirm without pending → no_pending error -----------

    @Test
    void unfollowTagAllConfirmWithoutPendingReturnsNoPending() throws Exception {
        String actor = PREFIX + "noPending-actor";
        UUID actorId = seedUser(actor);
        UUID tagA = seedTag(PREFIX + "a");
        seedScopePreferences(actorId, "EXPLICIT");
        seedScopeTag(actorId, tagA);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/unfollow-tag --all confirm");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_CONFIRM_NO_PENDING), reply.text(),
                "/unfollow-tag --all confirm with no prior /unfollow-tag --all must "
                        + "surface error.confirm.no_pending");
        assertEquals(1L, countScopeTag(actorId),
                "/unfollow-tag --all confirm without pending must not touch scope_tag");
        assertEquals("EXPLICIT", tagModeOf(actorId),
                "/unfollow-tag --all confirm without pending must not flip tag_mode");
    }

    // ----- (i) positional + --all → mutually-exclusive error --------------

    @Test
    void unfollowTagMutuallyExclusivePositionalAndAllFlagReturnsError() throws Exception {
        String actor = PREFIX + "mutex-actor";
        UUID actorId = seedUser(actor);
        seedTag(PREFIX + "a");
        seedScopePreferences(actorId, "EXPLICIT");
        long versionBefore = tagSubscriptionVersionOf(actorId);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/unfollow-tag " + PREFIX + "a --all");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_UNFOLLOW_TAG_MUTUALLY_EXCLUSIVE),
                reply.text(),
                "positional tag + --all must surface error.unfollow_tag.mutually_exclusive");
        assertEquals(versionBefore, tagSubscriptionVersionOf(actorId),
                "mutex reject must not touch scope_preferences");
        assertEquals(0L, countScopeTag(actorId),
                "mutex reject must not touch scope_tag");
    }

    // ----- (j) Every mutation bumps tag_subscription_version --------------

    @Test
    void unfollowTagIncrementsTagSubscriptionVersionOnEveryMutation() throws Exception {
        String actor = PREFIX + "version-actor";
        UUID actorId = seedUser(actor);
        UUID tagA = seedTag(PREFIX + "a");
        UUID tagB = seedTag(PREFIX + "b");
        seedScopePreferences(actorId, "EXPLICIT");
        seedScopeTag(actorId, tagA);
        seedScopeTag(actorId, tagB);

        long v0 = tagSubscriptionVersionOf(actorId);

        // (1) EXPLICIT remove-in-place
        handler.handle(new ScopeRef.Dm(actor), "/unfollow-tag " + PREFIX + "a");
        long v1 = tagSubscriptionVersionOf(actorId);
        assertEquals(v0 + 1L, v1,
                "EXPLICIT remove-in-place must bump tag_subscription_version exactly once");

        // (2) EXPLICIT remove that flips back to ALL
        handler.handle(new ScopeRef.Dm(actor), "/unfollow-tag " + PREFIX + "b");
        long v2 = tagSubscriptionVersionOf(actorId);
        assertEquals(v1 + 1L, v2,
                "EXPLICIT → ALL flip-back must bump tag_subscription_version exactly once");

        // (3) --all bulk reset (no rows, but still bumps the version)
        handler.handle(new ScopeRef.Dm(actor), "/unfollow-tag --all");
        handler.handle(new ScopeRef.Dm(actor), "/unfollow-tag --all confirm");
        long v3 = tagSubscriptionVersionOf(actorId);
        assertEquals(v2 + 1L, v3,
                "bulk reset must bump tag_subscription_version exactly once");
    }

    // ----- (k) --all confirm leg writes zero audit rows -------------------

    @Test
    void unfollowTagAllWritesNoAuditRow() throws Exception {
        String actor = PREFIX + "noAudit-actor";
        UUID actorId = seedUser(actor);
        UUID tagA = seedTag(PREFIX + "a");
        seedScopePreferences(actorId, "EXPLICIT");
        seedScopeTag(actorId, tagA);

        long auditBefore = countAuditByActor(actorId);
        handler.handle(new ScopeRef.Dm(actor), "/unfollow-tag --all");
        handler.handle(new ScopeRef.Dm(actor), "/unfollow-tag --all confirm");
        long auditAfter = countAuditByActor(actorId);

        assertEquals(auditBefore, auditAfter,
                "/unfollow-tag --all (prompt + confirm) must write zero rows to audit_log");
    }

    // ----- helpers --------------------------------------------------------

    private String expectedSuccessFromAll(String tagName) {
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_UNFOLLOW_TAG_SUCCESS_FROM_ALL),
                tagName);
    }

    private String expectedSuccessInPlace(String tagName) {
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_UNFOLLOW_TAG_SUCCESS_IN_PLACE),
                tagName);
    }

    private String expectedFlipsBackToAll(String tagName) {
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_UNFOLLOW_TAG_FLIPS_BACK_TO_ALL),
                tagName);
    }

    private String expectedConfirmPrompt(long rowCount) {
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_CONFIRM_PROMPT_UNFOLLOW_TAG_ALL),
                Long.toString(confirmStateService.timeoutSeconds()),
                Long.toString(rowCount));
    }

    private String expectedAllSuccess(long deletedCount) {
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_UNFOLLOW_TAG_ALL_SUCCESS),
                Long.toString(deletedCount));
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
