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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape B (Thin-SQL) tests for {@link FollowAllSourcesCommandHandler}
 * against the DevServices Postgres container (M1-576).
 *
 * <p>The command's behavior IS the set-based idempotent INSERT over
 * {@code source_subscription}, so the assertions run over real rows
 * (mirroring {@code UnfollowSourceCommandHandlerTest}).</p>
 *
 * <p><b>Shared-DB counting.</b> The bulk subscribe targets EVERY live
 * {@code source} row in the container — including rows other test classes
 * seeded — so expected counts are queried ({@code countLiveSources()}) at
 * assertion time, never hardcoded, and cleanup deletes
 * {@code source_subscription} rows by this class's scope ids (the command
 * subscribes the test scopes to non-prefixed sources too, which a
 * source-prefix delete would miss).</p>
 *
 * <p>Test isolation: every fixture row carries the class-wide
 * {@code m1-576-} prefix; {@link #cleanup()} deletes only rows under that
 * prefix plus the test scopes' subscription rows.</p>
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
        try (Connection conn = dataSource.getConnection()) {
            // Scope-id delete first: the bulk command subscribes the test
            // scopes to ALL live sources (prefixed and not), so a
            // source-prefix delete alone would strand rows and break the
            // 0→N precondition on the next test.
            exec(conn,
                    "DELETE FROM source_subscription WHERE scope_id IN ("
                            + "  SELECT id FROM users WHERE contact_id LIKE ?"
                            + "  UNION ALL"
                            + "  SELECT id FROM groups WHERE upstream_group_id LIKE ?)",
                    PREFIX + "%", PREFIX + "%");
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
    void freshDmScopeGoesFromZeroToEveryLiveSourceWithMatchingReplyCounts() throws Exception {
        String actor = PREFIX + "dm-actor";
        UUID actorId = seedUser(actor, false);
        seedSource("dm-a");
        seedSource("dm-b");
        seedSource("dm-c");
        long liveSources = countLiveSources();
        assertEquals(0L, countSubscriptions("dm", actorId),
                "precondition: a fresh scope must start with zero subscriptions");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/follow-all-sources");

        assertEquals(liveSources, countSubscriptions("dm", actorId),
                "one call must subscribe the scope to every live source");
        // Subscription completeness: no live source is missing from the
        // scope's subscription set. This is the precondition /summary's
        // eligibility filter (source_id IN scope subscriptions) needs to
        // surface newly-followed sources — acceptance item 3's mechanism.
        assertEquals(0L, countLiveSourcesNotSubscribed("dm", actorId),
                "every live source must have a subscription row for the scope");
        assertEquals(expectedDoneReply((int) liveSources, liveSources), reply.text(),
                "reply must report the newly-subscribed count and the scope total");
    }

    @Test
    void reRunIsIdempotentAndReportsOnlyTheDelta() throws Exception {
        String actor = PREFIX + "rerun-actor";
        UUID actorId = seedUser(actor, false);
        seedSource("rerun-a");
        seedSource("rerun-b");
        long liveBefore = countLiveSources();

        handler.handle(new ScopeRef.Dm(actor), "/follow-all-sources");
        assertEquals(liveBefore, countSubscriptions("dm", actorId));

        // Re-run with nothing new: a pure no-op — zero newly subscribed,
        // total unchanged, no duplicate rows (the PK upsert guarantees it,
        // but the count proves no other row shape slipped in).
        OutboundMessage noopReply = handler.handle(new ScopeRef.Dm(actor), "/follow-all-sources");
        assertEquals(liveBefore, countSubscriptions("dm", actorId),
                "a re-run must not change the subscription count");
        assertEquals(expectedDoneReply(0, liveBefore), noopReply.text(),
                "a no-op re-run must report 0 newly subscribed and the unchanged total");

        // A source added after the first run: the next run subscribes ONLY
        // the missing one (the acceptance's "adds only the sources not
        // already followed").
        seedSource("rerun-late");
        OutboundMessage deltaReply = handler.handle(new ScopeRef.Dm(actor), "/follow-all-sources");
        assertEquals(liveBefore + 1, countSubscriptions("dm", actorId),
                "the delta run must add exactly the one not-yet-followed source");
        assertEquals(expectedDoneReply(1, liveBefore + 1), deltaReply.text(),
                "the delta run must report exactly 1 newly subscribed");
    }

    @Test
    void softDeletedSourceIsNeverSubscribed() throws Exception {
        String actor = PREFIX + "softdel-actor";
        UUID actorId = seedUser(actor, false);
        UUID deletedSourceId = seedSoftDeletedSource("softdel");

        handler.handle(new ScopeRef.Dm(actor), "/follow-all-sources");

        assertFalse(isSubscribed("dm", actorId, deletedSourceId),
                "a soft-deleted source (deleted_at IS NOT NULL) must be excluded "
                        + "from the bulk subscribe");
    }

    @Test
    void groupPlainMemberIsRefusedAndNoGroupSubscriptionIsWritten() throws Exception {
        String upstreamGroupId = PREFIX + "grp-plain";
        UUID groupId = seedGroup(upstreamGroupId);
        String member = PREFIX + "plain-member";
        UUID memberId = seedUser(member, false);
        seedGroupMembership(groupId, memberId, false);
        seedSource("grpPlain");
        inboundContext.setSenderContactId(member);

        OutboundMessage reply = handler.handle(new ScopeRef.Group(upstreamGroupId),
                "/follow-all-sources");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_FOLLOW_ALL_SOURCES_GROUP_ADMIN_ONLY),
                reply.text(),
                "a plain group member must be refused with the group-admin-only error");
        assertEquals(0L, countSubscriptions("group", groupId),
                "a refused call must write no group subscription rows");
    }

    @Test
    void groupAdminBulkSubscribesTheGroupScope() throws Exception {
        String upstreamGroupId = PREFIX + "grp-admin";
        UUID groupId = seedGroup(upstreamGroupId);
        String admin = PREFIX + "grp-admin-user";
        UUID adminId = seedUser(admin, false);
        seedGroupMembership(groupId, adminId, true);
        seedSource("grpAdmin");
        long liveSources = countLiveSources();
        inboundContext.setSenderContactId(admin);

        OutboundMessage reply = handler.handle(new ScopeRef.Group(upstreamGroupId),
                "/follow-all-sources");

        assertEquals(liveSources, countSubscriptions("group", groupId),
                "the group admin's call must subscribe the GROUP scope to every live source");
        assertEquals(expectedDoneReply((int) liveSources, liveSources), reply.text());
        assertEquals(0L, countSubscriptions("dm", adminId),
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

    private String expectedDoneReply(int newlySubscribed, long totalFollowed) {
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_FOLLOW_ALL_SOURCES_DONE),
                newlySubscribed, totalFollowed);
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

    private UUID seedSoftDeletedSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "  bootstrap_tags, status, deleted_at) "
                             + "VALUES ('rss', ?, ?, 'news', '{}', 'active', now()) "
                             + "RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
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

    private long countLiveSources() throws Exception {
        return queryLong("SELECT count(*) FROM source WHERE deleted_at IS NULL");
    }

    private long countSubscriptions(String scopeKind, UUID scopeId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM source_subscription "
                             + "WHERE scope_kind = ? AND scope_id = ?")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countLiveSourcesNotSubscribed(String scopeKind, UUID scopeId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM source s WHERE s.deleted_at IS NULL "
                             + "AND NOT EXISTS (SELECT 1 FROM source_subscription ss "
                             + "  WHERE ss.scope_kind = ? AND ss.scope_id = ? "
                             + "    AND ss.source_id = s.id)")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
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

    private long queryLong(String sql) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
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
