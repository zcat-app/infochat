package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.ScopeRef;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@link GetSourcesCommandHandler} is the spec'd alias of
 * {@code /list-sources} accepting the same flags except {@code --all}
 * ({@code docs/spec/commands.md} §Discovery). Two integration scenarios
 * run against the DevServices Postgres container (the alias delegates to
 * the real {@link ListSourcesCommandHandler} SQL) plus one unit
 * assertion on the flag-stripping helper.
 *
 * <p>Test isolation follows the {@link ListSourcesCommandHandlerTest}
 * pattern: every fixture row carries the {@code m1-231-getsrc-} prefix
 * and {@link #cleanup()} deletes only rows under it. A permanent
 * guardian admin keeps the V5 last-admin-protection trigger from
 * refusing the per-test admin-row DELETE.</p>
 */
@QuarkusTest
class GetSourcesAliasTest {

    private static final String PREFIX = "m1-231-getsrc-";
    private static final String ADAPTER = "inmemory";

    @Inject GetSourcesCommandHandler getSourcesHandler;
    @Inject ListSourcesCommandHandler listSourcesHandler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                            + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, "guardian-m1-231-getsrc-permanent");
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
            exec(conn,
                    "DELETE FROM source_subscription WHERE source_id IN ("
                            + "  SELECT id FROM source WHERE identifier LIKE ?)",
                    "https://example.com/" + PREFIX + "%");
            exec(conn, "DELETE FROM source WHERE identifier LIKE ?",
                    "https://example.com/" + PREFIX + "%");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE ?", PREFIX + "%");
        }
    }

    // ----- (a) alias returns the same result as /list-sources --------------

    @Test
    void getSourcesReturnsSameResultAsListSourcesForEquivalentInvocation() throws Exception {
        String actor = PREFIX + "aliasCaller";
        UUID actorId = seedUser(actor, false);
        UUID srcId = seedSource("aliasSrc", "active");
        seedSubscription("dm", actorId, srcId);

        String viaGetSources =
                getSourcesHandler.handle(new ScopeRef.Dm(actor), "/get-sources").text();
        String viaListSources =
                listSourcesHandler.handle(new ScopeRef.Dm(actor), "/list-sources").text();

        assertEquals(viaListSources, viaGetSources,
                "/get-sources must return the same body as /list-sources for an equivalent "
                        + "(no-admin-flag) invocation");
        assertTrue(viaGetSources.contains(PREFIX + "aliasSrc-name"),
                "/get-sources must list the caller's subscribed source — got: " + viaGetSources);
    }

    // ----- (a2) alias paginates identically above the per-page limit -------

    @Test
    void getSourcesPaginatesIdenticallyToListSourcesAbovePageLimit() throws Exception {
        // M1-625 acceptance item 2: the /get-sources alias must behave
        // identically to /list-sources for a >page-limit scope — same page
        // indicator, same rows per page. Both delegate to the same SQL, so a
        // page-for-page full-body equality is the tightest pin.
        String actor = PREFIX + "pagAlias";
        UUID actorId = seedUser(actor, false);
        for (int i = 0; i < 25; i++) {
            UUID srcId = seedSource(String.format("pag-%02d", i), "active");
            seedSubscription("dm", actorId, srcId);
        }

        String firstViaList =
                listSourcesHandler.handle(new ScopeRef.Dm(actor), "/list-sources").text();
        assertTrue(firstViaList.contains("page 1/"),
                "precondition: /list-sources over 25 sources must render a page indicator "
                        + "— got: " + firstViaList);

        String p1Get =
                getSourcesHandler.handle(new ScopeRef.Dm(actor), "/get-sources --page 1").text();
        String p1List =
                listSourcesHandler.handle(new ScopeRef.Dm(actor), "/list-sources --page 1").text();
        assertEquals(p1List, p1Get,
                "/get-sources --page 1 must render identically to /list-sources --page 1 "
                        + "(same indicator, same rows)");
        String p2Get =
                getSourcesHandler.handle(new ScopeRef.Dm(actor), "/get-sources --page 2").text();
        String p2List =
                listSourcesHandler.handle(new ScopeRef.Dm(actor), "/list-sources --page 2").text();
        assertEquals(p2List, p2Get,
                "/get-sources --page 2 must render identically to /list-sources --page 2");
    }

    // ----- (b) --all is ignored: the privileged enumeration is unreachable -

    @Test
    void getSourcesIgnoresAllFlagAndStaysScoped() throws Exception {
        // An admin caller: were --all honored, /list-sources --all would
        // return the deployment-wide listing with the URL-visibility
        // caveat. /get-sources must strip --all and return only the
        // caller's own subscriptions — never another user's source.
        String admin = PREFIX + "ignoreAll-admin";
        String other = PREFIX + "ignoreAll-other";
        UUID adminId = seedUser(admin, true);
        UUID otherId = seedUser(other, false);
        UUID mineId = seedSource("ignoreAll-mine", "active");
        UUID theirsId = seedSource("ignoreAll-theirs", "active");
        seedSubscription("dm", adminId, mineId);
        seedSubscription("dm", otherId, theirsId);

        String withAll =
                getSourcesHandler.handle(new ScopeRef.Dm(admin), "/get-sources --all").text();
        String withoutFlag =
                getSourcesHandler.handle(new ScopeRef.Dm(admin), "/get-sources").text();

        assertEquals(withoutFlag, withAll,
                "/get-sources --all must behave identically to /get-sources (the --all flag "
                        + "is stripped, not part of this command's identity)");
        assertFalse(withAll.contains("visible to bot admins"),
                "/get-sources --all must NOT reach the privileged --all enumeration "
                        + "(no URL-visibility caveat) — got: " + withAll);
        assertFalse(withAll.contains(PREFIX + "ignoreAll-theirs-name"),
                "/get-sources --all must NOT surface another user's source — got: " + withAll);
        assertTrue(withAll.contains(PREFIX + "ignoreAll-mine-name"),
                "/get-sources --all must still list the caller's own source — got: " + withAll);
    }

    // ----- (c) flag-stripping helper: drops --all/--include-deleted --------

    @Test
    void stripAdminFlagsDropsAllAndIncludeDeletedAndKeepsPage() {
        assertEquals("/get-sources --page 2",
                GetSourcesCommandHandler.stripAdminFlags(
                        "/get-sources --all --include-deleted --page 2"),
                "stripAdminFlags must drop --all and --include-deleted while preserving "
                        + "the command name and the --page flag/value");
        assertEquals("/get-sources",
                GetSourcesCommandHandler.stripAdminFlags("/get-sources --all"),
                "stripAdminFlags must drop a lone --all");
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

    private UUID seedSource(String slug, String status) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "  bootstrap_tags, status, deleted_at) "
                             + "VALUES ('rss', ?, ?, 'news', '{}', ?, NULL) RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            ps.setString(3, status);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
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

    private static void exec(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }
}
