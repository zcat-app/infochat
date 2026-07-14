package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape B (Thin-SQL) tests for {@link UnfollowSourceCommandHandler}
 * against the DevServices Postgres container.
 *
 * <p>The command's behavior IS the scoped {@code DELETE} on
 * {@code source_subscription} plus the audit-before-effect write, so the
 * assertions run over real rows (mirroring
 * {@code RemoveSourceCommandHandlerTest}).</p>
 *
 * <p>Test isolation: every fixture row (contact ids, source identifiers,
 * group ids) carries the class-wide {@code m1-419-} prefix; the
 * {@link #cleanup()} {@code @BeforeEach} deletes only rows under that
 * prefix.</p>
 */
@QuarkusTest
class UnfollowSourceCommandHandlerTest {

    private static final String PREFIX = "m1-419-";
    private static final String ADAPTER = "inmemory";

    @Inject UnfollowSourceCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject CommandPermissions commandPermissions;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        inboundContext.setSenderContactId(null);
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE target_kind = 'source' AND target_id IN ("
                                + "  SELECT id::TEXT FROM source WHERE identifier LIKE ?)",
                        "https://example.com/" + PREFIX + "%");
                exec(conn,
                        "DELETE FROM audit_log WHERE actor_user_id IN ("
                                + "  SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
            exec(conn,
                    "DELETE FROM source_subscription WHERE source_id IN ("
                            + "  SELECT id FROM source WHERE identifier LIKE ?)",
                    "https://example.com/" + PREFIX + "%");
            exec(conn,
                    "DELETE FROM source_exclusion WHERE source_id IN ("
                            + "  SELECT id FROM source WHERE identifier LIKE ?)",
                    "https://example.com/" + PREFIX + "%");
            exec(conn, "DELETE FROM source WHERE identifier LIKE ?",
                    "https://example.com/" + PREFIX + "%");
            exec(conn,
                    "DELETE FROM group_membership WHERE group_id IN ("
                            + "  SELECT id FROM groups WHERE upstream_group_id LIKE ?)",
                    PREFIX + "%");
            exec(conn, "DELETE FROM groups WHERE upstream_group_id LIKE ?", PREFIX + "%");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE ?", PREFIX + "%");
        }
    }

    @Test
    void dmCallerUnsubscribesOwnSubscriptionAndSourceRowSurvives() throws Exception {
        String actor = PREFIX + "dm-actor";
        UUID actorId = seedUser(actor, false);
        UUID sourceId = seedSource("dm");
        seedSubscription("dm", actorId, sourceId);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor),
                "/unfollow-source " + sourceId);

        assertTrue(reply.text().contains(PREFIX + "dm-name"),
                "success reply must name the unfollowed source — got: " + reply.text());
        assertFalse(isSubscribed("dm", actorId, sourceId),
                "the caller's own subscription row must be deleted");
        assertTrue(isSourcePresent(sourceId),
                "the global source row must survive — /unfollow-source is per-scope only");
    }

    @Test
    void unfollowDeletesOnlyCallerScopeSubscriptionNotOtherScopes() throws Exception {
        String actorA = PREFIX + "isoA-actor";
        String actorB = PREFIX + "isoB-actor";
        UUID actorAId = seedUser(actorA, false);
        UUID actorBId = seedUser(actorB, false);
        UUID sourceId = seedSource("iso");
        // Both A and B subscribe (in their own DM scopes) to the SAME source.
        seedSubscription("dm", actorAId, sourceId);
        seedSubscription("dm", actorBId, sourceId);

        handler.handle(new ScopeRef.Dm(actorA), "/unfollow-source " + sourceId);

        assertFalse(isSubscribed("dm", actorAId, sourceId),
                "actor A's subscription must be deleted");
        assertTrue(isSubscribed("dm", actorBId, sourceId),
                "actor B's subscription to the same source must be untouched "
                        + "(per-(user, scope) isolation)");
    }

    @Test
    void groupPlainMemberCannotUnfollowReturnsAdminOnlyError() throws Exception {
        String upstreamGroupId = PREFIX + "grp-plain";
        UUID groupId = seedGroup(upstreamGroupId);
        String member = PREFIX + "plain-member";
        UUID memberId = seedUser(member, false);
        seedGroupMembership(groupId, memberId, false);
        UUID sourceId = seedSource("grpPlain");
        seedSubscription("group", groupId, sourceId);
        inboundContext.setSenderContactId(member);

        OutboundMessage reply = handler.handle(new ScopeRef.Group(upstreamGroupId),
                "/unfollow-source " + sourceId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_UNFOLLOW_SOURCE_GROUP_ADMIN_ONLY),
                reply.text(),
                "a plain group member must be refused with the group-admin-only error");
        assertTrue(isSubscribed("group", groupId, sourceId),
                "a refused unfollow must not delete the group subscription");
        assertEquals(0L, countAuditByActionForTarget("UNFOLLOW_SOURCE", sourceId),
                "a refused unfollow must write no audit row");
    }

    @Test
    void groupAdminUnfollowsGroupSubscription() throws Exception {
        String upstreamGroupId = PREFIX + "grp-admin";
        UUID groupId = seedGroup(upstreamGroupId);
        String admin = PREFIX + "grp-admin-user";
        UUID adminId = seedUser(admin, false);
        seedGroupMembership(groupId, adminId, true);
        UUID sourceId = seedSource("grpAdmin");
        seedSubscription("group", groupId, sourceId);
        inboundContext.setSenderContactId(admin);

        OutboundMessage reply = handler.handle(new ScopeRef.Group(upstreamGroupId),
                "/unfollow-source " + sourceId);

        assertTrue(reply.text().contains(PREFIX + "grpAdmin-name"),
                "group admin's success reply must name the unfollowed source — got: "
                        + reply.text());
        assertFalse(isSubscribed("group", groupId, sourceId),
                "the group subscription must be deleted by the group admin");
    }

    // ----- bootstrap-origin branch: per-scope exclusion (D59, M1-621) ------

    @Test
    void bootstrapUnfollowRecordsExclusionForCallerScopeOnlyAndAudits() throws Exception {
        String actor = PREFIX + "boot-actor";
        String other = PREFIX + "boot-other";
        UUID actorId = seedUser(actor, false);
        UUID otherId = seedUser(other, false);
        UUID sourceId = seedBootstrapSource("boot");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor),
                "/unfollow-source " + sourceId);

        assertTrue(reply.text().contains(PREFIX + "boot-name"),
                "success reply must name the excluded source — got: " + reply.text());
        assertTrue(isExcluded("dm", actorId, sourceId),
                "a source_exclusion row must exist for the caller's scope");
        assertFalse(isExcluded("dm", otherId, sourceId),
                "no other scope gains an exclusion (per-scope opt-out)");
        assertTrue(isSourcePresent(sourceId),
                "the global source row must survive — exclusion is per-scope only");
        assertEquals(1L, countAuditByActionForTarget("UNFOLLOW_SOURCE", sourceId),
                "the exclusion write must be audited (audit-before-effect)");
    }

    @Test
    void bootstrapUnfollowAlsoDeletesLegacySubscriptionRow() throws Exception {
        // A pre-V59 scope that bulk-subscribed holds a subscription row for
        // every bootstrap source; the world predicate's OR arm would keep
        // the source visible through it, so the exclusion and the
        // subscription delete must land together.
        String actor = PREFIX + "legacy-actor";
        UUID actorId = seedUser(actor, false);
        UUID sourceId = seedBootstrapSource("legacy");
        seedSubscription("dm", actorId, sourceId);

        handler.handle(new ScopeRef.Dm(actor), "/unfollow-source " + sourceId);

        assertTrue(isExcluded("dm", actorId, sourceId),
                "the exclusion row must be recorded");
        assertFalse(isSubscribed("dm", actorId, sourceId),
                "the legacy subscription row must be deleted in the same transaction");
    }

    @Test
    void bootstrapUnfollowAlreadyExcludedIsFriendlyNoOpWithoutAudit() throws Exception {
        String actor = PREFIX + "reexcl-actor";
        UUID actorId = seedUser(actor, false);
        UUID sourceId = seedBootstrapSource("reexcl");
        seedExclusion("dm", actorId, sourceId);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor),
                "/unfollow-source " + sourceId);

        assertEquals(bundleLoader.get(BundleKeys.REPLY_UNFOLLOW_SOURCE_NOT_SUBSCRIBED),
                reply.text(),
                "already-excluded and not re-subscribed → friendly no-op");
        assertEquals(0L, countAuditByActionForTarget("UNFOLLOW_SOURCE", sourceId),
                "a no-effect call must not write an audit row");
    }

    @Test
    void bootstrapUnfollowAfterReAddReExcludesDespiteExistingExclusionRow() throws Exception {
        // Exclude → /add-source re-subscribes past the exclusion → unfollow
        // again. The no-op check must NOT fire while a subscription row
        // survives, or the source could never be re-hidden.
        String actor = PREFIX + "readd-actor";
        UUID actorId = seedUser(actor, false);
        UUID sourceId = seedBootstrapSource("readd");
        seedExclusion("dm", actorId, sourceId);
        seedSubscription("dm", actorId, sourceId);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor),
                "/unfollow-source " + sourceId);

        assertTrue(reply.text().contains(PREFIX + "readd-name"),
                "the re-exclude must succeed, not no-op — got: " + reply.text());
        assertFalse(isSubscribed("dm", actorId, sourceId),
                "the re-added subscription row must be deleted");
        assertTrue(isExcluded("dm", actorId, sourceId),
                "the exclusion row remains in force");
        assertEquals(1L, countAuditByActionForTarget("UNFOLLOW_SOURCE", sourceId),
                "the effective re-exclude is audited once");
    }

    @Test
    void unsubscribedCustomSourceIsIndistinguishableFromUnknownId() throws Exception {
        // Existence-vs-no-access collapse (red-team 2026-07-14): another
        // scope's private ('user'-origin) custom must answer with the SAME
        // unknown-id reply as a nonexistent id — the former not-subscribed
        // reply confirmed the private source id existed.
        String actor = PREFIX + "notSub-actor";
        String owner = PREFIX + "notSub-owner";
        seedUser(actor, false);
        UUID ownerId = seedUser(owner, false);
        // Custom source in ANOTHER scope's world only.
        UUID sourceId = seedSource("notSub");
        seedSubscription("dm", ownerId, sourceId);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor),
                "/unfollow-source " + sourceId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_UNFOLLOW_SOURCE_UNKNOWN_ID),
                reply.text(),
                "an out-of-world custom must be indistinguishable from an unknown id");
        assertEquals(0L, countAuditByActionForTarget("UNFOLLOW_SOURCE", sourceId),
                "a no-effect call must write no audit row");
        assertTrue(isSubscribed("dm", ownerId, sourceId),
                "the owner's subscription is untouched by another scope's probe");
    }

    @Test
    void malformedOrUnknownIdReturnsError() throws Exception {
        String actor = PREFIX + "badId-actor";
        seedUser(actor, false);

        OutboundMessage malformed = handler.handle(new ScopeRef.Dm(actor),
                "/unfollow-source not-a-uuid");
        assertEquals(bundleLoader.get(BundleKeys.ERROR_UNFOLLOW_SOURCE_UNKNOWN_ID),
                malformed.text(),
                "a non-UUID <id> must surface the unknown-id error");

        OutboundMessage unknown = handler.handle(new ScopeRef.Dm(actor),
                "/unfollow-source " + UUID.randomUUID());
        assertEquals(bundleLoader.get(BundleKeys.ERROR_UNFOLLOW_SOURCE_UNKNOWN_ID),
                unknown.text(),
                "a well-formed UUID that names no source must surface the unknown-id error");
    }

    @Test
    void successWritesAuditRowTaggedUnfollowSource() throws Exception {
        String actor = PREFIX + "audit-actor";
        UUID actorId = seedUser(actor, false);
        UUID sourceId = seedSource("audit");
        seedSubscription("dm", actorId, sourceId);

        handler.handle(new ScopeRef.Dm(actor), "/unfollow-source " + sourceId);

        assertEquals(1L, countAuditByActionForTarget("UNFOLLOW_SOURCE", sourceId),
                "a real unfollow must write exactly one UNFOLLOW_SOURCE audit row "
                        + "for the source target");
    }

    @Test
    void inboundRouterDispatchesUnfollowSourceToHandlerExactlyOnce() throws Exception {
        // The router (InboundRouter#dispatch) keys on CommandHandler#name();
        // a single dispatch must delete exactly one subscription row.
        assertEquals("unfollow-source", handler.name(),
                "the router dispatches /unfollow-source by matching this name()");

        String actor = PREFIX + "disp-actor";
        UUID actorId = seedUser(actor, false);
        UUID sourceId = seedSource("disp");
        seedSubscription("dm", actorId, sourceId);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor),
                "/unfollow-source " + sourceId);

        assertFalse(reply.text().isEmpty(), "dispatch must produce a non-empty reply");
        assertFalse(isSubscribed("dm", actorId, sourceId),
                "one dispatch must delete the single matching subscription row exactly once");
    }

    @Test
    void unfollowSourceIsNotAllowedDuringProbation() {
        // /unfollow-source is a WRITE and stays OUT of the probation
        // closed-set (CommandPermissions.ALLOWED). InboundRouter's
        // step-5 probation gate therefore replies error.probation.blocked
        // to a probation caller before this handler ever runs.
        assertFalse(commandPermissions.allowedDuringProbation("unfollow-source"),
                "/unfollow-source must not be in the probation allowed-set");
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedUser(String contactId, boolean isAdmin) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                             + "VALUES (?, ?, ?, 'vouched') RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID seedSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "  bootstrap_tags, status) "
                             + "VALUES ('rss', ?, ?, 'news', '{}', 'active') RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    /**
     * A live bootstrap-origin source — implicitly in every scope's world
     * (D59). The prefix cleanup and the {@code @AfterEach} below remove
     * it; a leftover would enter every other class's world.
     */
    private UUID seedBootstrapSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "  bootstrap_tags, status, source_origin) "
                             + "VALUES ('rss', ?, ?, 'news', '{}', 'active', 'bootstrap') "
                             + "RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedExclusion(String scopeKind, UUID scopeId, UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source_exclusion (scope_kind, scope_id, source_id) "
                             + "VALUES (?, ?, ?)")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, sourceId);
            ps.executeUpdate();
        }
    }

    private boolean isExcluded(String scopeKind, UUID scopeId, UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM source_exclusion "
                             + "WHERE scope_kind = ? AND scope_id = ? AND source_id = ?")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @AfterEach
    void cleanupBootstrapFixtures() throws Exception {
        // Bootstrap-origin fixtures must not outlive this class (they are
        // visible to EVERY scope under the D59 world predicate).
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "DELETE FROM source_exclusion WHERE source_id IN ("
                            + "  SELECT id FROM source WHERE identifier LIKE ?)",
                    "https://example.com/" + PREFIX + "%");
            exec(conn,
                    "DELETE FROM source_subscription WHERE source_id IN ("
                            + "  SELECT id FROM source WHERE identifier LIKE ? "
                            + "  AND source_origin = 'bootstrap')",
                    "https://example.com/" + PREFIX + "%");
            exec(conn,
                    "DELETE FROM source WHERE identifier LIKE ? "
                            + "AND source_origin = 'bootstrap'",
                    "https://example.com/" + PREFIX + "%");
        }
    }

    private UUID seedGroup(String upstreamGroupId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO groups (adapter, upstream_group_id, display_name) "
                             + "VALUES (?, ?, ?) RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, upstreamGroupId);
            ps.setString(3, upstreamGroupId + "-name");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedGroupMembership(UUID groupId, UUID userId, boolean isGroupAdmin)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO group_membership (group_id, user_id, is_group_admin) "
                             + "VALUES (?, ?, ?)")) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            ps.setBoolean(3, isGroupAdmin);
            ps.executeUpdate();
        }
    }

    private void seedSubscription(String scopeKind, UUID scopeId, UUID sourceId)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                             + "VALUES (?, ?, ?)")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, sourceId);
            ps.executeUpdate();
        }
    }

    private boolean isSubscribed(String scopeKind, UUID scopeId, UUID sourceId)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM source_subscription "
                             + "WHERE scope_kind = ? AND scope_id = ? AND source_id = ?")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean isSourcePresent(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private long countAuditByActionForTarget(String action, UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = ? "
                             + "AND target_kind = 'source' AND target_id = ?")) {
            ps.setString(1, action);
            ps.setString(2, sourceId.toString());
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
