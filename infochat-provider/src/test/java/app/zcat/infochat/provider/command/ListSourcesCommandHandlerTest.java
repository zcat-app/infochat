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
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ListSourcesCommandHandler}. Per the outline §Risks
 * #3: although acceptance item 3 names plain JUnit, six of the nine
 * scenarios assert on real SQL behavior ({@code JOIN source_subscription},
 * {@code deleted_at IS [NOT] NULL} partition, status-column rendering),
 * so this file follows the Shape B pattern matching the other three
 * handler tests in this ticket. The deviation is documented in
 * {@code target/m1-tick-outline-M1-053.md} §Risks #3.
 *
 * <p>Test isolation: every fixture row carries the
 * {@code m1-053-list-} prefix; {@link #cleanup()} deletes only rows
 * under that prefix. No audit assertions — /list-sources is read-only.</p>
 */
@QuarkusTest
class ListSourcesCommandHandlerTest {

    private static final String PREFIX = "m1-053-list-";
    private static final String ADAPTER = "inmemory";

    @Inject ListSourcesCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            // Permanent guardian admin so the V5 last-admin-protection
            // trigger does not refuse the per-test DELETE on admin
            // rows (the BanCommandHandlerTest precedent at line 85-90).
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                            + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, "guardian-m1-053-list-permanent");
            // audit_log is append-only via V5 triggers; disable them
            // for the per-test cleanup so previous-run rows under this
            // class's contact-id prefix do not accumulate (mirrors the
            // RemoveSourceCommandHandlerTest precedent at line 57-71).
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
            exec(conn,
                    "DELETE FROM group_membership WHERE group_id IN ("
                            + "  SELECT id FROM groups WHERE upstream_group_id LIKE ?)",
                    PREFIX + "%");
            exec(conn, "DELETE FROM groups WHERE upstream_group_id LIKE ?", PREFIX + "%");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE ?", PREFIX + "%");
        }
    }

    // A bootstrap-origin fixture is visible to EVERY scope under the D59
    // world predicate, so it must not outlive this class — the @BeforeEach
    // prefix cleanup alone would leave it polluting other classes' runs.
    @AfterEach
    void cleanupBootstrapFixtures() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
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

    // ----- (M1-198) privileged flags in group scope → command_dm_only ------

    @Test
    void listSourcesAdminFlagsInGroupScopeReturnCommandDmOnly() throws Exception {
        // The privileged --all / --include-deleted listing is DM-only (it
        // enumerates deployment-wide source URLs); in group scope it
        // returns the accurate scope error, NOT the admin_only_flag error.
        // The un-flagged /list-sources stays available in group scope
        // (covered by listSourcesGroupReturnsGroupSubscriptionsForEveryMember).
        String expected = bundleLoader.get(BundleKeys.ERROR_COMMAND_DM_ONLY);
        assertEquals(expected, handler.handle(
                        new ScopeRef.Group(PREFIX + "grp-dm-only"), "/list-sources --all").text(),
                "/list-sources --all in group scope must return error.command_dm_only");
        assertEquals(expected, handler.handle(
                        new ScopeRef.Group(PREFIX + "grp-dm-only"),
                        "/list-sources --include-deleted").text(),
                "/list-sources --include-deleted in group scope must return error.command_dm_only");
    }

    // ----- Permission-gate branches (admin-only flag rejections) -----------

    @Test
    void listSourcesNonAdminWithAllFlagReturnsAdminOnlyError() throws Exception {
        String actor = PREFIX + "nonAdminAll-actor";
        seedUser(actor, false);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor),
                "/list-sources --all");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_LIST_SOURCES_ADMIN_ONLY_FLAG), reply.text(),
                "non-admin /list-sources --all must surface admin_only_flag");
    }

    @Test
    void listSourcesNonAdminWithIncludeDeletedFlagReturnsAdminOnlyError() throws Exception {
        String actor = PREFIX + "nonAdminInc-actor";
        seedUser(actor, false);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor),
                "/list-sources --include-deleted");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_LIST_SOURCES_ADMIN_ONLY_FLAG), reply.text(),
                "non-admin /list-sources --include-deleted must surface admin_only_flag "
                        + "(the flag is part of command identity — NOT silently stripped)");
    }

    @Test
    void listSourcesIncludeDeletedWithoutAllReturnsRequiresAllError() throws Exception {
        String actor = PREFIX + "adminIncWoAll-actor";
        seedUser(actor, true);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor),
                "/list-sources --include-deleted");

        assertEquals(
                bundleLoader.get(BundleKeys.ERROR_LIST_SOURCES_INCLUDE_DELETED_REQUIRES_ALL),
                reply.text(),
                "/list-sources --include-deleted without --all must surface requires_all "
                        + "(even for an admin caller)");
    }

    // ----- Happy paths -----------------------------------------------------

    @Test
    void listSourcesDmReturnsCallerSubscriptionsOnly() throws Exception {
        String actor = PREFIX + "dmCaller";
        String other = PREFIX + "dmOther";
        UUID actorId = seedUser(actor, false);
        UUID otherId = seedUser(other, false);

        UUID mineSourceId = seedSource("dmCaller-mine", "active", false);
        UUID theirsSourceId = seedSource("dmCaller-theirs", "active", false);
        seedSubscription("dm", actorId, mineSourceId);
        seedSubscription("dm", otherId, theirsSourceId);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/list-sources");
        String body = reply.text();

        assertTrue(body.contains(PREFIX + "dmCaller-mine-name"),
                "DM listing must include the caller's subscribed source — got: " + body);
        assertFalse(body.contains(PREFIX + "dmCaller-theirs-name"),
                "DM listing must NOT include another user's subscribed source — got: " + body);
    }

    @Test
    void listSourcesBareShowsBootstrapCatalogueToSubscriptionlessCaller() throws Exception {
        // Acceptance item 4 (M1-621): the bare listing is the caller's D59
        // world catalogue — every live bootstrap source appears even with
        // zero subscriptions, while another scope's custom ('user'-origin)
        // source stays hidden (privacy).
        String actor = PREFIX + "worldCaller";
        String other = PREFIX + "worldOther";
        seedUser(actor, false);
        UUID otherId = seedUser(other, false);
        seedBootstrapSource("world-boot");
        UUID theirsSourceId = seedSource("world-theirs", "active", false);
        seedSubscription("dm", otherId, theirsSourceId);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/list-sources");
        String body = reply.text();

        assertTrue(body.contains(PREFIX + "world-boot-name"),
                "a subscription-less caller must see the bootstrap catalogue — got: " + body);
        assertFalse(body.contains(PREFIX + "world-theirs-name"),
                "another scope's custom source must stay hidden — got: " + body);
    }

    @Test
    void listSourcesDoesNotDoubleListSubscribedBootstrapSource() throws Exception {
        // A legacy (pre-V59 bulk-subscribe) or re-added subscription to a
        // bootstrap source must not render the source twice: the world
        // catalogue is one row per source.
        String actor = PREFIX + "dedupCaller";
        UUID actorId = seedUser(actor, false);
        UUID bootSrcId = seedBootstrapSource("dedup-boot");
        seedSubscription("dm", actorId, bootSrcId);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/list-sources");
        String body = reply.text();

        int first = body.indexOf(PREFIX + "dedup-boot-name");
        assertTrue(first >= 0, "the subscribed bootstrap source must list — got: " + body);
        assertEquals(-1, body.indexOf(PREFIX + "dedup-boot-name", first + 1),
                "a subscribed bootstrap source must list exactly once — got: " + body);
    }

    @Test
    void listSourcesLineIncludesSourceUuid() throws Exception {
        // M1-422: each /list-sources row carries the source UUID (rendered
        // as inline code) so a user can copy it into /unfollow-source <id>
        // or /remove-source <id> — the only in-band way to discover the id
        // those commands require.
        String actor = PREFIX + "uuidCaller";
        UUID actorId = seedUser(actor, false);
        UUID srcId = seedSource("uuidSrc", "active", false);
        seedSubscription("dm", actorId, srcId);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/list-sources");
        String body = reply.text();

        assertTrue(body.contains(srcId.toString()),
                "list-sources line must include the source UUID for copy into "
                        + "/unfollow-source <id> — got: " + body);
    }

    @Test
    void listSourcesAbovePageLimitShowsPageIndicatorAndReachesEverySource() throws Exception {
        // M1-625 (acceptance items 1 + 3): a source count above the per-page
        // limit must render a completeness indicator (page N/M), not silently
        // truncate, AND every source must be reachable by paging. 25 caller
        // subscriptions comfortably exceed the 20-per-page limit
        // (ListSourcesCommandHandler.PAGE_SIZE). The caller's D59 world
        // catalogue also folds in any live bootstrap rows, so the total page
        // count is read back from the reply rather than hard-coded — the
        // assertions hold regardless of how many bootstrap rows the DB carries.
        String actor = PREFIX + "pagCaller";
        UUID actorId = seedUser(actor, false);
        int seeded = 25;
        for (int i = 0; i < seeded; i++) {
            UUID srcId = seedSource(String.format("pag-%02d", i), "active", false);
            seedSubscription("dm", actorId, srcId);
        }

        String firstBody = handler.handle(new ScopeRef.Dm(actor), "/list-sources").text();
        Matcher indicator = Pattern.compile("page 1/(\\d+)").matcher(firstBody);
        assertTrue(indicator.find(),
                "a listing above the per-page limit must render a `page 1/M` indicator "
                        + "(not a silent cut) — got: " + firstBody);
        int totalPages = Integer.parseInt(indicator.group(1));
        assertTrue(totalPages >= 2,
                "25 caller sources exceed the 20-per-page limit, so the listing must span "
                        + ">1 page — got totalPages=" + totalPages);

        // Walk every page; the union of caller sources seen must be all 25 —
        // none stranded beyond the truncation point.
        Set<String> reached = new HashSet<>();
        for (int page = 1; page <= totalPages; page++) {
            String body = handler.handle(new ScopeRef.Dm(actor),
                    "/list-sources --page " + page).text();
            assertTrue(body.contains("page " + page + "/" + totalPages),
                    "page " + page + " must carry the `page " + page + "/" + totalPages
                            + "` indicator — got: " + body);
            for (int i = 0; i < seeded; i++) {
                if (body.contains(PREFIX + String.format("pag-%02d", i) + "-name")) {
                    reached.add(String.format("pag-%02d", i));
                }
            }
        }
        assertEquals(seeded, reached.size(),
                "every caller source must be reachable across the pages (no source stranded "
                        + "beyond the first page's truncation) — reached " + reached.size()
                        + " of " + seeded);
    }

    @Test
    void listSourcesGroupReturnsGroupSubscriptionsForEveryMember() throws Exception {
        // Decision D7: group subscriptions are visible to every group
        // member. The handler keys on group scope (not caller id), so
        // the listing is identical regardless of which group member
        // issued the command.
        String upstreamGroupId = PREFIX + "gid-1";
        UUID groupId = seedGroup(upstreamGroupId);
        UUID groupSrcId = seedSource("groupVis-src", "active", false);
        seedSubscription("group", groupId, groupSrcId);

        // Two members of the same group; each gets the same listing.
        // The handler does NOT consult group_membership (the SPI
        // lookup keys on the group's scope id directly) — the
        // membership rows exist only for cleanup-time scoping.
        String memberA = PREFIX + "groupMemA";
        String memberB = PREFIX + "groupMemB";
        UUID memberAId = seedUser(memberA, false);
        UUID memberBId = seedUser(memberB, false);
        seedGroupMembership(groupId, memberAId, false);
        seedGroupMembership(groupId, memberBId, false);

        OutboundMessage replyA = handler.handle(
                new ScopeRef.Group(upstreamGroupId), "/list-sources");
        OutboundMessage replyB = handler.handle(
                new ScopeRef.Group(upstreamGroupId), "/list-sources");

        assertTrue(replyA.text().contains(PREFIX + "groupVis-src-name"),
                "group listing for member A must include the group subscription — got: "
                        + replyA.text());
        assertTrue(replyB.text().contains(PREFIX + "groupVis-src-name"),
                "group listing for member B must include the same subscription — got: "
                        + replyB.text());
    }

    @Test
    void listSourcesAllReturnsEveryNonDeletedSourceGlobally() throws Exception {
        String actor = PREFIX + "allAdmin-actor";
        seedUser(actor, true);
        UUID a = seedSource("allA", "active", false);
        UUID b = seedSource("allB", "active", false);
        UUID deleted = seedSource("allDel", "active", true);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/list-sources --all");
        String body = reply.text();

        assertTrue(body.contains(PREFIX + "allA-name"),
                "--all listing must include source A — got: " + body);
        assertTrue(body.contains(PREFIX + "allB-name"),
                "--all listing must include source B — got: " + body);
        assertFalse(body.contains(PREFIX + "allDel-name"),
                "--all (without --include-deleted) must NOT include soft-deleted sources — got: "
                        + body);
        // Suppress unused-warning lint without changing the assertion intent.
        assertFalse(a.equals(b) || b.equals(deleted));
    }

    @Test
    void listSourcesAllFlagsFailedAndDisabledStatusesInline() throws Exception {
        String actor = PREFIX + "statusAdmin-actor";
        seedUser(actor, true);
        seedSource("statusActive", "active", false);
        seedSource("statusFailed", "failed", false);
        seedSource("statusDisabled", "disabled", false);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/list-sources --all");
        String body = reply.text();

        assertTrue(body.contains("status=active"),
                "--all listing must flag 'active' status inline — got: " + body);
        assertTrue(body.contains("status=failed"),
                "--all listing must flag 'failed' status inline — got: " + body);
        assertTrue(body.contains("status=disabled"),
                "--all listing must flag 'disabled' status inline — got: " + body);
    }

    @Test
    void listSourcesAllIncludeDeletedAdditionallyReturnsSoftDeletedRows() throws Exception {
        String actor = PREFIX + "deletedAdmin-actor";
        seedUser(actor, true);
        seedSource("delActive", "active", false);
        seedSource("delDeleted", "active", true);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor),
                "/list-sources --all --include-deleted");
        String body = reply.text();

        assertTrue(body.contains(PREFIX + "delActive-name"),
                "--all --include-deleted must include active sources — got: " + body);
        assertTrue(body.contains(PREFIX + "delDeleted-name"),
                "--all --include-deleted must include soft-deleted sources — got: " + body);
        assertTrue(body.contains("status=deleted"),
                "--all --include-deleted must flag soft-deleted rows with status=deleted "
                        + "— got: " + body);
    }

    @Test
    void listSourcesAllReplyIncludesUrlVisibilityCaveat() throws Exception {
        String actor = PREFIX + "caveatAdmin-actor";
        seedUser(actor, true);
        seedSource("caveat", "active", false);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/list-sources --all");

        assertTrue(reply.text().contains("visible to bot admins"),
                "--all reply MUST include the URL-visibility caveat literal "
                        + "(spec §Source URL visibility) — got: " + reply.text());
    }

    // ----- Audit-on-privileged-read branches -------------------------------
    // Spec §Authorization model step 8 ("Audit-log the intent") + §Source URL
    // visibility (deployment-wide URL disclosure under --all). The four
    // scenarios pin: (1) --all writes one LIST_SOURCES_ALL row with
    // include_deleted=false; (2) --all --include-deleted encodes
    // include_deleted=true; (3) non-admin --all path leaves zero audit rows
    // (admin gate fails before audit fires); (4) unprivileged DM (no flags)
    // leaves zero audit rows (matches the read-only-doesn't-audit pattern).

    @Test
    void listSourcesAllWritesPrivilegedReadAuditRow() throws Exception {
        String actor = PREFIX + "allAudit-actor";
        UUID actorId = seedUser(actor, true);
        seedSource("allAuditSrc", "active", false);
        long auditBefore = countAuditByActionForActor("LIST_SOURCES_ALL", actorId);

        handler.handle(new ScopeRef.Dm(actor), "/list-sources --all");

        assertEquals(auditBefore + 1,
                countAuditByActionForActor("LIST_SOURCES_ALL", actorId),
                "/list-sources --all must write exactly one LIST_SOURCES_ALL audit row");
        AuditRow row = readLatestAuditByActorAndAction(actorId, "LIST_SOURCES_ALL");
        assertNotNull(row, "LIST_SOURCES_ALL row must be readable");
        assertEquals("source", row.targetKind(),
                "target_kind is constrained to the V5 closed set; uses 'source' "
                        + "(entity-kind) with sentinel target_id='all' for the "
                        + "deployment-wide enumeration");
        assertEquals("all", row.targetId(),
                "target_id sentinel literal 'all' for the deployment-wide enumeration");
        assertTrue(row.detailsJson().contains("\"include_deleted\": false")
                        || row.detailsJson().contains("\"include_deleted\":false"),
                "details_json must encode include_deleted=false for --all without "
                        + "--include-deleted — got: " + row.detailsJson());
    }

    @Test
    void listSourcesAllIncludeDeletedAuditRowEncodesIncludeDeletedTrue() throws Exception {
        String actor = PREFIX + "incAudit-actor";
        UUID actorId = seedUser(actor, true);
        seedSource("incAuditSrc", "active", false);

        handler.handle(new ScopeRef.Dm(actor), "/list-sources --all --include-deleted");

        AuditRow row = readLatestAuditByActorAndAction(actorId, "LIST_SOURCES_ALL");
        assertNotNull(row,
                "/list-sources --all --include-deleted must write one LIST_SOURCES_ALL row");
        assertTrue(row.detailsJson().contains("\"include_deleted\": true")
                        || row.detailsJson().contains("\"include_deleted\":true"),
                "details_json must encode include_deleted=true for --include-deleted "
                        + "— got: " + row.detailsJson());
    }

    @Test
    void listSourcesNonAdminWithAllFlagWritesNoAuditRow() throws Exception {
        String actor = PREFIX + "nonAdminAuditCheck-actor";
        UUID actorId = seedUser(actor, false);

        handler.handle(new ScopeRef.Dm(actor), "/list-sources --all");

        assertEquals(0L, countAuditByActionForActor("LIST_SOURCES_ALL", actorId),
                "non-admin --all must NOT write an audit row — admin gate fails before "
                        + "audit fires");
    }

    @Test
    void listSourcesDmReturnsCallerSubscriptionsOnlyWritesNoAuditRow() throws Exception {
        String actor = PREFIX + "dmAuditCheck-actor";
        UUID actorId = seedUser(actor, false);
        UUID srcId = seedSource("dmAuditSrc", "active", false);
        seedSubscription("dm", actorId, srcId);

        handler.handle(new ScopeRef.Dm(actor), "/list-sources");

        assertEquals(0L, countAuditByActionForActor("LIST_SOURCES_ALL", actorId),
                "unprivileged DM /list-sources (no flags) must NOT write an audit row — "
                        + "matches the read-only-doesn't-audit pattern (the gap closed by "
                        + "LIST_SOURCES_ALL is specifically the privileged --all enumeration)");
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

    /**
     * A live bootstrap-origin source — implicitly in every scope's world
     * catalogue (D59). The prefix cleanup removes it; it must not outlive
     * this class (a leftover would enter every other class's world).
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

    private UUID seedSource(String slug, String status, boolean softDeleted) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "  bootstrap_tags, status, deleted_at) "
                             + "VALUES ('rss', ?, ?, 'news', '{}', ?, ?) RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            ps.setString(3, status);
            if (softDeleted) {
                ps.setObject(4, OffsetDateTime.now());
            } else {
                ps.setObject(4, null);
            }
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

    private long countAuditByActionForActor(String action, UUID actorId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = ? AND actor_user_id = ?")) {
            ps.setString(1, action);
            ps.setObject(2, actorId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private AuditRow readLatestAuditByActorAndAction(UUID actorId, String action) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT target_kind, target_id, details_json::TEXT AS details_json "
                             + "FROM audit_log WHERE actor_user_id = ? AND action = ? "
                             + "ORDER BY created_at DESC LIMIT 1")) {
            ps.setObject(1, actorId);
            ps.setString(2, action);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new AuditRow(
                        rs.getString("target_kind"),
                        rs.getString("target_id"),
                        rs.getString("details_json"));
            }
        }
    }

    private record AuditRow(String targetKind, String targetId, String detailsJson) {}
}
