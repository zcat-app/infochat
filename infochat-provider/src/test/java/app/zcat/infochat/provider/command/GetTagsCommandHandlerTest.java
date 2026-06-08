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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link GetTagsCommandHandler} against the
 * DevServices Postgres container (V6 {@code tag}, V7
 * {@code scope_preferences} + {@code scope_tag}). Canonical thin-SQL
 * handler test per {@code docs/process/test-pyramid.md} §Shape B: the
 * handler's behavior is the SQL marking of the controlled vocabulary
 * against {@code tag_mode} / {@code scope_tag}, so the assertions run
 * over real rows.
 *
 * <p>Test isolation: every fixture row (tag names, contact ids, group
 * ids) carries the class-wide {@code m1-231-tags-} prefix; the
 * {@link #cleanup()} {@code @BeforeEach} deletes only rows under that
 * prefix. The {@code tag} table is the globally-shared controlled
 * vocabulary — other suites' tags may be present — so every assertion
 * is a substring check on this class's prefixed tag names, never an
 * assertion on the full vocabulary set.</p>
 */
@QuarkusTest
class GetTagsCommandHandlerTest {

    private static final String PREFIX = "m1-231-tags-";
    private static final String ADAPTER = "inmemory";

    /** The render marker on a followed tag (mirrors the handler's literal). */
    private static final String FOLLOWED = "* ";

    @Inject GetTagsCommandHandler handler;
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
                    "DELETE FROM scope_tag WHERE scope_id IN "
                            + "(SELECT id FROM groups WHERE upstream_group_id LIKE ?)",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM scope_preferences WHERE scope_id IN "
                            + "(SELECT id FROM users WHERE contact_id LIKE ?)",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM scope_preferences WHERE scope_id IN "
                            + "(SELECT id FROM groups WHERE upstream_group_id LIKE ?)",
                    PREFIX + "%");
            exec(conn, "DELETE FROM tag WHERE name LIKE ?", PREFIX + "%");
            exec(conn,
                    "DELETE FROM group_membership WHERE group_id IN "
                            + "(SELECT id FROM groups WHERE upstream_group_id LIKE ?)",
                    PREFIX + "%");
            exec(conn, "DELETE FROM groups WHERE upstream_group_id LIKE ?", PREFIX + "%");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE ?", PREFIX + "%");
        }
    }

    // ----- (a) DM EXPLICIT mode: only the followed tags carry the marker --

    @Test
    void getTagsDmExplicitModeMarksOnlyFollowedTags() throws Exception {
        String actor = PREFIX + "explicit-actor";
        UUID actorId = seedUser(actor);
        UUID aiId = seedTag(PREFIX + "ai");
        seedTag(PREFIX + "security");
        seedTag(PREFIX + "crypto");
        seedScopePreferences("dm", actorId, "EXPLICIT");
        seedScopeTag("dm", actorId, aiId);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/get-tags");
        String body = reply.text();

        assertTrue(body.contains(FOLLOWED + PREFIX + "ai"),
                "EXPLICIT-mode /get-tags must mark the followed tag with a leading '* ' — got: "
                        + body);
        assertTrue(body.contains(PREFIX + "security"),
                "every vocabulary tag must be listed, followed or not — got: " + body);
        assertFalse(body.contains(FOLLOWED + PREFIX + "security"),
                "an unfollowed tag must NOT carry the followed marker — got: " + body);
        assertFalse(body.contains(FOLLOWED + PREFIX + "crypto"),
                "an unfollowed tag must NOT carry the followed marker — got: " + body);
    }

    // ----- (b) DM ALL mode: every vocabulary tag is marked followed --------

    @Test
    void getTagsDmAllModeMarksEveryTag() throws Exception {
        String actor = PREFIX + "all-actor";
        UUID actorId = seedUser(actor);
        seedTag(PREFIX + "ai");
        seedTag(PREFIX + "security");
        seedScopePreferences("dm", actorId, "ALL");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/get-tags");
        String body = reply.text();

        assertTrue(body.contains(FOLLOWED + PREFIX + "ai"),
                "ALL-mode /get-tags must mark every vocabulary tag as followed — got: " + body);
        assertTrue(body.contains(FOLLOWED + PREFIX + "security"),
                "ALL-mode /get-tags must mark every vocabulary tag as followed — got: " + body);
    }

    // ----- (c) per-(user, scope) isolation: no leak of another's follow set

    @Test
    void getTagsIsScopedPerUserAndDoesNotLeakAnotherUsersFollowSet() throws Exception {
        String actorA = PREFIX + "isolationA-actor";
        String actorB = PREFIX + "isolationB-actor";
        UUID actorAId = seedUser(actorA);
        UUID actorBId = seedUser(actorB);
        UUID aiId = seedTag(PREFIX + "ai");
        UUID securityId = seedTag(PREFIX + "security");
        // A follows only ai; B follows only security — both EXPLICIT.
        seedScopePreferences("dm", actorAId, "EXPLICIT");
        seedScopeTag("dm", actorAId, aiId);
        seedScopePreferences("dm", actorBId, "EXPLICIT");
        seedScopeTag("dm", actorBId, securityId);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actorA), "/get-tags");
        String body = reply.text();

        assertTrue(body.contains(FOLLOWED + PREFIX + "ai"),
                "actor A must see their own followed tag marked — got: " + body);
        assertFalse(body.contains(FOLLOWED + PREFIX + "security"),
                "actor A must NOT see actor B's followed tag marked (per-(user, scope) "
                        + "isolation) — got: " + body);
    }

    // ----- (d) group scope marks the group's followed set ------------------

    @Test
    void getTagsGroupScopeMarksGroupFollowedSet() throws Exception {
        String upstreamGroupId = PREFIX + "grp-1";
        UUID groupId = seedGroup(upstreamGroupId);
        UUID aiId = seedTag(PREFIX + "ai");
        seedTag(PREFIX + "security");
        seedScopePreferences("group", groupId, "EXPLICIT");
        seedScopeTag("group", groupId, aiId);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Group(upstreamGroupId), "/get-tags");
        String body = reply.text();

        assertTrue(body.contains(FOLLOWED + PREFIX + "ai"),
                "group-scope /get-tags must mark the group's followed tag — got: " + body);
        assertFalse(body.contains(FOLLOWED + PREFIX + "security"),
                "a tag the group does not follow must NOT carry the marker — got: " + body);
    }

    // ----- helpers ---------------------------------------------------------

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

    private void seedScopePreferences(String scopeKind, UUID scopeId, String tagMode)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO scope_preferences (scope_kind, scope_id, tag_mode) "
                             + "VALUES (?, ?, ?)")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setString(3, tagMode);
            ps.executeUpdate();
        }
    }

    private void seedScopeTag(String scopeKind, UUID scopeId, UUID tagId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO scope_tag (scope_kind, scope_id, tag_id) "
                             + "VALUES (?, ?, ?)")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, tagId);
            ps.executeUpdate();
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
