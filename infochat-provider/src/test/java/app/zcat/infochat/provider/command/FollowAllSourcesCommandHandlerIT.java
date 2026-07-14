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
import java.text.MessageFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Shape B (Thin-SQL) tests for {@link FollowAllSourcesCommandHandler}
 * against the DevServices Postgres container (M1-576; repurposed by
 * M1-621/D59 from bulk-subscribe to "re-include all bootstrap sources").
 *
 * <p>The command's behavior IS the scoped set-based {@code DELETE} over
 * {@code source_exclusion}, so the assertions run over real rows
 * (mirroring {@code UnfollowSourceCommandHandlerTest}).</p>
 *
 * <p>Test isolation: every fixture row carries the class-wide
 * {@code m1-576-} prefix; {@link #cleanup()} deletes only rows under that
 * prefix. Bootstrap-origin fixture sources are additionally removed
 * {@code @AfterEach} — under the D59 world predicate a leftover would
 * enter every other class's world.</p>
 */
@QuarkusTest
class FollowAllSourcesCommandHandlerIT {

    private static final String PREFIX = "m1-576-";
    private static final String ADAPTER = "inmemory";

    @Inject FollowAllSourcesCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject CommandPermissions commandPermissions;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        inboundContext.setSenderContactId(null);
        cleanupFixtures();
    }

    @AfterEach
    void tearDown() throws Exception {
        cleanupFixtures();
    }

    private void cleanupFixtures() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // audit_log is append-only via V5 triggers and actor_user_id
            // FK-references users, so the effective-clear audit rows must
            // go (via the established disable-trigger dance) before the
            // prefix users can be deleted.
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE actor_user_id IN ("
                                + "  SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
            // Exclusion rows by test scope AND by prefixed source: both
            // directions, so neither a foreign-source exclusion for a test
            // scope nor a test-source exclusion for a foreign scope strands.
            exec(conn,
                    "DELETE FROM source_exclusion WHERE scope_id IN ("
                            + "  SELECT id FROM users WHERE contact_id LIKE ?"
                            + "  UNION ALL"
                            + "  SELECT id FROM groups WHERE upstream_group_id LIKE ?)",
                    PREFIX + "%", PREFIX + "%");
            exec(conn,
                    "DELETE FROM source_exclusion WHERE source_id IN ("
                            + "  SELECT id FROM source WHERE identifier LIKE ?)",
                    "https://example.com/" + PREFIX + "%");
            exec(conn,
                    "DELETE FROM source_subscription WHERE source_id IN ("
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
    void clearingExclusionsReIncludesForCallerScopeOnlyAndReportsCount() throws Exception {
        String actor = PREFIX + "dm-actor";
        String other = PREFIX + "dm-other";
        UUID actorId = seedUser(actor, false);
        UUID otherId = seedUser(other, false);
        UUID sourceA = seedBootstrapSource("dm-a");
        UUID sourceB = seedBootstrapSource("dm-b");
        seedExclusion("dm", actorId, sourceA);
        seedExclusion("dm", actorId, sourceB);
        seedExclusion("dm", otherId, sourceA);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/follow-all-sources");

        assertEquals(0L, countExclusions("dm", actorId),
                "the calling scope's exclusions must all be cleared");
        assertEquals(1L, countExclusions("dm", otherId),
                "another scope's exclusions must survive (per-scope privacy boundary)");
        assertEquals(expectedDoneReply(2), reply.text(),
                "reply must report the re-included (cleared) count");
        assertEquals(1L, countFollowAllAudits(actorId),
                "an effective clear writes exactly one FOLLOW_ALL_SOURCES audit row "
                        + "(audit-before-effect; red-team 2026-07-14)");
    }

    @Test
    void reRunIsIdempotentAndReportsZero() throws Exception {
        String actor = PREFIX + "rerun-actor";
        UUID actorId = seedUser(actor, false);
        UUID sourceId = seedBootstrapSource("rerun-a");
        seedExclusion("dm", actorId, sourceId);

        handler.handle(new ScopeRef.Dm(actor), "/follow-all-sources");
        OutboundMessage noopReply = handler.handle(new ScopeRef.Dm(actor), "/follow-all-sources");

        assertEquals(0L, countExclusions("dm", actorId));
        assertEquals(expectedDoneReply(0), noopReply.text(),
                "a re-run with nothing excluded must report 0 re-included");
        assertEquals(1L, countFollowAllAudits(actorId),
                "only the effective clear audits — the zero-clear no-op writes no row "
                        + "(the no-effect-doesn't-audit pattern)");
    }

    @Test
    void groupPlainMemberIsRefusedAndExclusionsSurvive() throws Exception {
        String upstreamGroupId = PREFIX + "grp-plain";
        UUID groupId = seedGroup(upstreamGroupId);
        String member = PREFIX + "plain-member";
        UUID memberId = seedUser(member, false);
        seedGroupMembership(groupId, memberId, false);
        UUID sourceId = seedBootstrapSource("grpPlain");
        seedExclusion("group", groupId, sourceId);
        inboundContext.setSenderContactId(member);

        OutboundMessage reply = handler.handle(new ScopeRef.Group(upstreamGroupId),
                "/follow-all-sources");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_FOLLOW_ALL_SOURCES_GROUP_ADMIN_ONLY),
                reply.text(),
                "a plain group member must be refused with the group-admin-only error");
        assertEquals(1L, countExclusions("group", groupId),
                "a refused call must clear nothing");
    }

    @Test
    void groupAdminClearsTheGroupScopeExclusionsOnly() throws Exception {
        String upstreamGroupId = PREFIX + "grp-admin";
        UUID groupId = seedGroup(upstreamGroupId);
        String admin = PREFIX + "grp-admin-user";
        UUID adminId = seedUser(admin, false);
        seedGroupMembership(groupId, adminId, true);
        UUID sourceId = seedBootstrapSource("grpAdmin");
        seedExclusion("group", groupId, sourceId);
        seedExclusion("dm", adminId, sourceId);
        inboundContext.setSenderContactId(admin);

        OutboundMessage reply = handler.handle(new ScopeRef.Group(upstreamGroupId),
                "/follow-all-sources");

        assertEquals(0L, countExclusions("group", groupId),
                "the group admin's call must clear the GROUP scope's exclusions");
        assertEquals(expectedDoneReply(1), reply.text());
        assertEquals(1L, countExclusions("dm", adminId),
                "the group-scope call must not touch the admin's own DM scope "
                        + "(per-scope isolation)");
    }

    @Test
    void followAllSourcesIsNotAllowedDuringProbation() {
        // /follow-all-sources is a WRITE and stays OUT of the probation
        // closed-set (CommandPermissions.ALLOWED). InboundRouter's step-5
        // probation gate therefore replies error.probation.blocked to a
        // probation caller before this handler ever runs — the handler
        // deliberately carries no probation check of its own.
        assertFalse(commandPermissions.allowedDuringProbation("follow-all-sources"),
                "/follow-all-sources must not be in the probation allowed-set");
    }

    @Test
    void inboundRouterDispatchesFollowAllSourcesByName() {
        assertEquals("follow-all-sources", handler.name(),
                "the router dispatches /follow-all-sources by matching this name()");
    }

    // ----- helpers ---------------------------------------------------------

    private String expectedDoneReply(int reIncluded) {
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_FOLLOW_ALL_SOURCES_DONE),
                reIncluded);
    }

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

    /** A live bootstrap-origin source — the only kind exclusions target. */
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

    private long countFollowAllAudits(UUID actorUserId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log "
                             + "WHERE action = 'FOLLOW_ALL_SOURCES' AND actor_user_id = ?")) {
            ps.setObject(1, actorUserId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countExclusions(String scopeKind, UUID scopeId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM source_exclusion "
                             + "WHERE scope_kind = ? AND scope_id = ?")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
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
