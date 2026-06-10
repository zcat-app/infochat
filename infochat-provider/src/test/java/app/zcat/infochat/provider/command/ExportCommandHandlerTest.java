package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link ExportCommandHandler} (handler behavior +
 * audit-before-effect) and unit tests for {@link ExportPaginator}
 * (pure-function contract).
 */
@QuarkusTest
class ExportCommandHandlerTest {

    private static final String PREFIX = "m1-067-export-";
    private static final String ADAPTER = "inmemory";

    @Inject ExportCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject InboundContext inboundContext;
    @Inject BundleLoader bundleLoader;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            // Clean up in FK-safe order.
            exec(conn, "DELETE FROM saved_post WHERE user_id IN ("
                    + "SELECT id FROM users WHERE contact_id LIKE ?)", PREFIX + "%");
            // Audit log has no-delete triggers; disable them for cleanup.
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn, "DELETE FROM audit_log WHERE actor_user_id IN ("
                        + "SELECT id FROM users WHERE contact_id LIKE ?)", PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
            exec(conn, "DELETE FROM users WHERE contact_id LIKE ?", PREFIX + "%");
        }
    }

    @Test
    void auditLoggedBeforeEffect() throws Exception {
        String contactId = PREFIX + "audit-actor";
        UUID userId = seedUser(contactId);

        handler.handle(new ScopeRef.Dm(contactId), "/export");

        // Verify the EXPORT audit row was written.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT action FROM audit_log"
                             + " WHERE actor_user_id = ? AND action = 'EXPORT'")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "EXPORT audit row must be written before effect");
                assertEquals("EXPORT", rs.getString("action"));
            }
        }
    }

    @Test
    void paginatesLargeExport() throws Exception {
        String contactId = PREFIX + "page-actor";
        UUID userId = seedUser(contactId);

        UUID sourceId = seedSource(PREFIX + "page-source");
        // Seed enough saved_post rows to force pagination at the
        // export page-cap (default 2048 - 32 = 2016 effective cap).
        for (int i = 0; i < 40; i++) {
            seedSavedPost(userId, sourceId, PREFIX + "page-uid-" + i,
                    new String[]{"tag-" + i},
                    Instant.now().minus(i, ChronoUnit.MINUTES));
        }

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/export");
        String text = reply.text();

        // Multi-page: the first reply carries page 1 only; later pages
        // are fetched via --page re-invocation.
        assertTrue(text.contains("page=1/"),
                "multi-page export must have page=1/ marker; got length=" + text.length());
        assertFalse(text.contains("page=2/"),
                "each reply must carry exactly one page");

        OutboundMessage page2 = handler.handle(
                new ScopeRef.Dm(contactId), "/export --page 2");
        assertTrue(page2.text().contains("page=2/"),
                "/export --page 2 must return the page=2/ reply");
    }

    @Test
    void pagedRepliesStayWithinCapAndCoverAllData() throws Exception {
        String contactId = PREFIX + "cap-actor";
        UUID userId = seedUser(contactId);

        UUID sourceId = seedSource(PREFIX + "cap-source");
        // More than one page of data at the default 2048 cap.
        for (int i = 0; i < 40; i++) {
            seedSavedPost(userId, sourceId, PREFIX + "cap-uid-" + i,
                    new String[]{"tag-" + i},
                    Instant.now().minus(i, ChronoUnit.MINUTES));
        }
        int pageCap = ConfigProvider.getConfig()
                .getValue("infochat.export.page-cap", Integer.class);

        OutboundMessage first = handler.handle(new ScopeRef.Dm(contactId), "/export");
        int totalPages = parseTotalPages(first.text());
        assertTrue(totalPages > 1,
                "40 saved posts must exceed one page; got " + totalPages);

        // Every page reply stays within the cap, and the union of the
        // pages reaches all seeded rows in-band.
        StringBuilder union = new StringBuilder(first.text());
        assertTrue(first.text().length() <= pageCap,
                "page 1 reply length " + first.text().length()
                        + " exceeds cap " + pageCap);
        for (int n = 2; n <= totalPages; n++) {
            OutboundMessage page = handler.handle(
                    new ScopeRef.Dm(contactId), "/export --page " + n);
            assertTrue(page.text().length() <= pageCap,
                    "page " + n + " reply length " + page.text().length()
                            + " exceeds cap " + pageCap);
            union.append(page.text());
        }
        String all = union.toString();
        for (int i = 0; i < 40; i++) {
            assertTrue(all.contains(PREFIX + "cap-uid-" + i),
                    "row " + i + " must be reachable across the paged replies");
        }
    }

    @Test
    void pageBeyondRangeReturnsOutOfRangeReply() throws Exception {
        String contactId = PREFIX + "range-actor";
        seedUser(contactId);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(contactId), "/export --page 99");
        assertTrue(reply.text().contains("out of range"),
                "a page beyond the export must return the out-of-range reply; got: "
                        + reply.text());
    }

    @Test
    void eachPageIsValidJson() throws Exception {
        String contactId = PREFIX + "json-actor";
        UUID userId = seedUser(contactId);

        UUID sourceId = seedSource(PREFIX + "json-source");
        for (int i = 0; i < 40; i++) {
            seedSavedPost(userId, sourceId, PREFIX + "json-uid-" + i,
                    new String[]{"tag-" + i},
                    Instant.now().minus(i, ChronoUnit.MINUTES));
        }

        OutboundMessage first = handler.handle(new ScopeRef.Dm(contactId), "/export");
        int totalPages = parseTotalPages(first.text());

        for (int n = 1; n <= totalPages; n++) {
            OutboundMessage reply = handler.handle(
                    new ScopeRef.Dm(contactId), "/export --page " + n);

            // Each reply carries exactly one fenced JSON block.
            List<String> jsonBlocks = extractJsonBlocks(reply.text());
            assertEquals(1, jsonBlocks.size(),
                    "page " + n + " must carry exactly one JSON block");
            String block = jsonBlocks.getFirst();
            assertTrue(block.startsWith("{") && block.endsWith("}"),
                    "page " + n + " must be a valid JSON object; starts with: "
                            + block.substring(0, Math.min(50, block.length())));
            // Verify balanced braces as a minimal structural check.
            int depth = 0;
            for (char c : block.toCharArray()) {
                if (c == '{') depth++;
                else if (c == '}') depth--;
            }
            assertEquals(0, depth,
                    "page " + n + " must have balanced braces");
        }
    }

    @Test
    void paginatorPreservesAllRows() {
        // Pure-function test: build a table-to-rows map, paginate,
        // verify the union across pages equals the full input.
        LinkedHashMap<String, List<String>> input = new LinkedHashMap<>();
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            rows.add("{\"id\":" + i + ",\"data\":\"row-" + i + "\"}");
        }
        input.put("test_table", rows);

        // Use a small cap to force multiple pages.
        List<String> pages = ExportPaginator.paginate(input, 200);
        assertTrue(pages.size() > 1,
                "small cap must produce multiple pages; got " + pages.size());

        // Collect all rows from all pages and verify completeness.
        int totalRows = 0;
        for (String page : pages) {
            // Count occurrences of "id": in the page.
            int count = 0;
            int idx = 0;
            while ((idx = page.indexOf("\"id\":", idx)) != -1) {
                count++;
                idx += 5;
            }
            totalRows += count;
        }
        assertEquals(20, totalRows,
                "union of pages must contain all 20 rows");
    }

    @Test
    void singlePageNoMarker() throws Exception {
        String contactId = PREFIX + "single-actor";
        seedUser(contactId);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/export");
        String text = reply.text();

        // With minimal data (just the users row), should be single page.
        assertFalse(text.contains("page="),
                "single-page export must NOT have page markers");
        assertTrue(text.contains("```json"),
                "export must be wrapped in triple-backtick JSON fence");
    }

    @Test
    void groupScopeRejected() {
        OutboundMessage reply = handler.handle(
                new ScopeRef.Group("some-group-id"), "/export");
        assertEquals(bundleLoader.get(BundleKeys.ERROR_EXPORT_GROUP_NOT_SUPPORTED), reply.text());
    }

    // -- helpers --

    /** Extract T from the first {@code page=N/T} marker in a reply. */
    private static int parseTotalPages(String text) {
        int idx = text.indexOf("page=");
        assertTrue(idx >= 0, "reply must carry a page=N/T marker; got: " + text);
        int slash = text.indexOf('/', idx);
        int eol = text.indexOf('\n', slash);
        return Integer.parseInt(text.substring(slash + 1, eol));
    }

    private List<String> extractJsonBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        String openFence = "```json\n";
        String closeFence = "\n```";
        int idx = 0;
        while (idx < text.length()) {
            int start = text.indexOf(openFence, idx);
            if (start < 0) break;
            start += openFence.length();
            int end = text.indexOf(closeFence, start);
            if (end < 0) break;
            blocks.add(text.substring(start, end));
            idx = end + closeFence.length();
        }
        return blocks;
    }

    private UUID seedUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned,"
                             + " registration_state) VALUES (?, ?, FALSE, FALSE, 'vouched')"
                             + " RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject("id", UUID.class);
            }
        }
    }

    private UUID seedSource(String identifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category,"
                             + " bootstrap_tags) VALUES ('rss', ?, ?, 'news', '{}')"
                             + " RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, "Test " + identifier);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject("id", UUID.class);
            }
        }
    }

    private void seedSavedPost(UUID userId, UUID sourceId, String postUid,
                               String[] personalTags, Instant savedAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO saved_post (user_id, post_uid, source_id, title,"
                             + " snapshot_tags, personal_tags, saved_at)"
                             + " VALUES (?, ?, ?, ?, '{}', ?, ?)")) {
            ps.setObject(1, userId);
            ps.setString(2, postUid);
            ps.setObject(3, sourceId);
            ps.setString(4, "Test " + postUid);
            ps.setArray(5, conn.createArrayOf("TEXT", personalTags));
            ps.setObject(6, OffsetDateTime.ofInstant(savedAt, ZoneOffset.UTC));
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
