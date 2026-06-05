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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link ListGroupsCommandHandler} against
 * DevServices Postgres. One {@code @Test} per acceptance scenario
 * (a)..(d) in M1-113.
 *
 * <p>The handler is read-only; cleanup deletes rows under
 * {@code PREFIX} / {@code UPSTREAM_PREFIX} so a test's seeded rows
 * are visible to its own assertions and not to subsequent tests. The
 * tests do NOT assert against a globally-empty {@code groups} table
 * (other tests' rows may persist); instead test (b) exercises the
 * "page out of range" empty path, which surfaces the same friendly
 * reply.</p>
 */
@QuarkusTest
class ListGroupsCommandHandlerTest {

    private static final String PREFIX = "m1-113-list-";
    private static final String UPSTREAM_PREFIX = "m1-113-list-grp-";
    private static final String ADAPTER = "inmemory";

    @Inject ListGroupsCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;

    @BeforeEach
    @AfterEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            // No permanent guardian admin — see the matching comment in
            // ApproveGroupCommandHandlerTest.cleanup. Disable
            // trg_users_last_admin_delete for the cleanup pass; the
            // finally block restores it so the invariant always holds
            // outside this cleanup.
            // audit_log is append-only via V5 triggers; disable them for
            // the per-test cleanup so previous-run LIST_GROUPS rows under
            // this class's contact-id prefix do not accumulate
            // (ListSourcesCommandHandlerTest precedent).
            exec(conn, "ALTER TABLE users DISABLE TRIGGER trg_users_last_admin_delete");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE actor_user_id IN ("
                                + "  SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%");
                exec(conn,
                        "DELETE FROM group_membership WHERE group_id IN "
                                + "(SELECT id FROM groups WHERE upstream_group_id LIKE ?)",
                        UPSTREAM_PREFIX + "%");
                exec(conn,
                        "DELETE FROM groups WHERE upstream_group_id LIKE ?",
                        UPSTREAM_PREFIX + "%");
                exec(conn,
                        "DELETE FROM users WHERE contact_id LIKE ?",
                        PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE users ENABLE TRIGGER trg_users_last_admin_delete");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
        }
    }

    // ----- (a) Non-admin → error.admin_only --------------------------------

    @Test
    void listByNonAdminReturnsAdminOnly() throws Exception {
        String actor = PREFIX + "nonAdmin-actor";
        seedUser(ADAPTER, actor, false);
        inboundContext.setSenderContactId(actor);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/list-groups");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), reply.text(),
                "non-admin /list-groups must surface error.admin_only");
    }

    // ----- (b) Empty page → friendly empty reply ---------------------------

    @Test
    void listEmptyPageReturnsEmptyMessage() throws Exception {
        String actor = PREFIX + "empty-actor";
        seedUser(ADAPTER, actor, true);
        inboundContext.setSenderContactId(actor);

        // Request a page far beyond any plausible row count. The handler
        // surfaces REPLY_LIST_GROUPS_EMPTY both for "table is empty" and
        // for "this page is out of range" — the assertion exercises the
        // friendly-reply contract without requiring a globally-empty
        // groups table (other tests' rows may persist).
        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/list-groups --page 99999");

        assertEquals(bundleLoader.get(BundleKeys.REPLY_LIST_GROUPS_EMPTY), reply.text(),
                "out-of-range page must surface reply.list_groups.empty");
    }

    // ----- (c) Mixed approval states → all shown with correct labels -------

    @Test
    void listMixedStatesShowsAllRowsWithCorrectLabels() throws Exception {
        String actor = PREFIX + "mixed-actor";
        seedUser(ADAPTER, actor, true);
        inboundContext.setSenderContactId(actor);

        UUID pendingId = seedGroup(ADAPTER, UPSTREAM_PREFIX + "mixed-pending", "pending");
        UUID approvedId = seedGroup(ADAPTER, UPSTREAM_PREFIX + "mixed-approved", "approved");
        UUID rejectedId = seedGroup(ADAPTER, UPSTREAM_PREFIX + "mixed-rejected", "rejected");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/list-groups");

        String text = reply.text();
        // Each seeded row appears with its id and approval_status label.
        assertTrue(text.contains(pendingId.toString()),
                "reply must contain the pending group's id, got: " + text);
        assertTrue(text.contains(approvedId.toString()),
                "reply must contain the approved group's id");
        assertTrue(text.contains(rejectedId.toString()),
                "reply must contain the rejected group's id");
        assertTrue(text.contains("pending"),
                "reply must contain the literal `pending` label");
        assertTrue(text.contains("approved"),
                "reply must contain the literal `approved` label");
        assertTrue(text.contains("rejected"),
                "reply must contain the literal `rejected` label");
        // Header line present, with page 1 of N format.
        assertTrue(text.startsWith("Groups (") || text.contains("page 1/"),
                "reply must begin with the rendered header, got: " + text);
    }

    // ----- (d) Pagination — page 2 returns disjoint rows from page 1 -------

    @Test
    void listPaginationReturnsDisjointPages() throws Exception {
        String actor = PREFIX + "pagination-actor";
        seedUser(ADAPTER, actor, true);
        inboundContext.setSenderContactId(actor);

        // Seed 25 groups under this test's UPSTREAM_PREFIX. PAGE_SIZE is
        // 20; page 1 surfaces 20 rows, page 2 surfaces the remaining 5
        // plus any other tests' rows that happen to land on that page.
        // The disjointness assertion: at least one M1-113 id appears on
        // page 1 but not page 2, OR vice versa — proves the offset works.
        UUID[] seeded = new UUID[25];
        for (int i = 0; i < seeded.length; i++) {
            seeded[i] = seedGroup(ADAPTER,
                    UPSTREAM_PREFIX + "page-" + String.format("%02d", i),
                    "pending");
        }

        OutboundMessage page1 = handler.handle(
                new ScopeRef.Dm(actor),
                "/list-groups --page 1");
        OutboundMessage page2 = handler.handle(
                new ScopeRef.Dm(actor),
                "/list-groups --page 2");

        String page1Text = page1.text();
        String page2Text = page2.text();
        assertFalse(page1Text.equals(page2Text),
                "page 1 and page 2 must surface different output");
        // Count how many seeded ids appear on each page. With 25 seeded
        // rows ordered by created_at DESC and PAGE_SIZE=20, the newest
        // 20 land on page 1; the oldest 5 land on page 2 (alongside
        // whatever other tests' rows still exist).
        int page1Count = countOccurrences(page1Text, seeded);
        int page2Count = countOccurrences(page2Text, seeded);
        assertEquals(seeded.length, page1Count + page2Count,
                "every seeded row must appear on exactly one of page 1 or page 2");
    }

    // ----- Audit-on-privileged-read branches -------------------------------
    // Spec §Authorization model step 8 ("Audit-log the intent") + the
    // §Source URL visibility-shaped disclosure that /list-groups produces
    // (every group's id, approval_status, redacted activator contact id,
    // member count, timezone — bot-admin-only deployment-wide read).
    // Three scenarios: (1) admin /list-groups writes one LIST_GROUPS row
    // with target_kind='group', target_id='all', details_json encoding
    // the requested page; (2) non-admin /list-groups writes zero rows
    // (admin gate fails before audit fires); (3) admin /list-groups on
    // an out-of-range page still writes the row (the privileged-read
    // intent was expressed and the deployment-wide query path was
    // entered — empty result does not retract the disclosure intent).
    // Mirrors ListSourcesCommandHandlerTest's audit-on-privileged-read
    // suite.

    @Test
    void listByAdminWritesPrivilegedReadAuditRow() throws Exception {
        String actor = PREFIX + "auditAdmin-actor";
        UUID actorId = seedUser(ADAPTER, actor, true);
        inboundContext.setSenderContactId(actor);
        long auditBefore = countAuditByActionForActor("LIST_GROUPS", actorId);

        handler.handle(new ScopeRef.Dm(actor), "/list-groups --page 3");

        assertEquals(auditBefore + 1,
                countAuditByActionForActor("LIST_GROUPS", actorId),
                "admin /list-groups must write exactly one LIST_GROUPS audit row");
        AuditRow row = readLatestAuditByActorAndAction(actorId, "LIST_GROUPS");
        assertNotNull(row, "LIST_GROUPS row must be readable");
        assertEquals("group", row.targetKind(),
                "target_kind is constrained to the V5 closed set; uses 'group' "
                        + "(entity-kind) with sentinel target_id='all' for the "
                        + "deployment-wide enumeration (mirrors LIST_SOURCES_ALL's "
                        + "'source'/'all' pattern)");
        assertEquals("all", row.targetId(),
                "target_id sentinel literal 'all' for the deployment-wide enumeration");
        assertTrue(row.detailsJson().contains("\"page\": 3")
                        || row.detailsJson().contains("\"page\":3"),
                "details_json must encode the requested page so forensics can "
                        + "reconstruct which slice was enumerated — got: " + row.detailsJson());
    }

    @Test
    void listByNonAdminWritesNoAuditRow() throws Exception {
        String actor = PREFIX + "auditNonAdmin-actor";
        UUID actorId = seedUser(ADAPTER, actor, false);
        inboundContext.setSenderContactId(actor);

        handler.handle(new ScopeRef.Dm(actor), "/list-groups");

        assertEquals(0L, countAuditByActionForActor("LIST_GROUPS", actorId),
                "non-admin /list-groups must NOT write an audit row — admin gate "
                        + "fails before audit fires");
    }

    @Test
    void listByAdminOnEmptyPageStillWritesAuditRow() throws Exception {
        String actor = PREFIX + "auditEmpty-actor";
        UUID actorId = seedUser(ADAPTER, actor, true);
        inboundContext.setSenderContactId(actor);

        handler.handle(new ScopeRef.Dm(actor), "/list-groups --page 99999");

        assertEquals(1L, countAuditByActionForActor("LIST_GROUPS", actorId),
                "out-of-range page is still a privileged-read attempt — the audit "
                        + "row records the intent regardless of result-set size");
    }

    private static int countOccurrences(String haystack, UUID[] needles) {
        int n = 0;
        for (UUID needle : needles) {
            if (haystack.contains(needle.toString())) {
                n++;
            }
        }
        return n;
    }

    // ----- DB helpers (inlined to keep file count at the M1-113 budget) ----

    private UUID seedUser(String adapter, String contactId, boolean isAdmin) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                             + "VALUES (?, ?, ?, 'vouched') RETURNING id")) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID seedGroup(String adapter, String upstreamGroupId, String approvalStatus) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO groups (adapter, upstream_group_id, approval_status) "
                             + "VALUES (?, ?, ?) RETURNING id")) {
            ps.setString(1, adapter);
            ps.setString(2, upstreamGroupId);
            ps.setString(3, approvalStatus);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private static void exec(Connection conn, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.execute();
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
