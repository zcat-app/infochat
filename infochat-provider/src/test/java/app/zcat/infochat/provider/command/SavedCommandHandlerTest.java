package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link SavedCommandHandler} against the
 * DevServices Postgres container (V15 saved_post). One {@code @Test}
 * per acceptance scenario in M1-052 acceptance item 5.
 *
 * <p>Test isolation: per-test sub-prefix within the class-wide
 * {@code PREFIX} ({@code m1-052-saved-}); the {@link #cleanup()}
 * {@code @BeforeEach} deletes rows under the class-wide prefix.</p>
 *
 * @implNote Shape B (Thin-SQL) per
 *     {@code docs/process/test-pyramid.md} §Handler unit tests —
 *     the handler's behavioral contract IS the SQL predicate
 *     (per-user-global with no scope discriminator clause; optional
 *     personal_tags filter; optional saved_at window;
 *     {@code ORDER BY saved_at DESC LIMIT/OFFSET} pagination), so
 *     observation against seeded rows is the only honest verification.
 */
@QuarkusTest
class SavedCommandHandlerTest {

    private static final String PREFIX = "m1-052-saved-";
    private static final String ADAPTER = "inmemory";

    @Inject SavedCommandHandler handler;
    @Inject DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "DELETE FROM saved_post WHERE user_id IN ("
                            + "SELECT id FROM users WHERE contact_id LIKE ?)",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM source WHERE identifier LIKE ?",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM users WHERE contact_id LIKE ?",
                    PREFIX + "%");
        }
    }

    @Test
    void savedReturnsEmptyHeaderWhenLibraryEmpty() throws Exception {
        String contactId = PREFIX + "empty-actor";
        seedUser(contactId);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/saved");

        assertEquals(bundleLoader.get(BundleKeys.REPLY_SAVED_EMPTY), reply.text(),
                "empty library must surface reply.saved.empty");
    }

    @Test
    void savedReplyHeaderDisclosesGlobalScope() throws Exception {
        String contactId = PREFIX + "hdr-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "hdr-source");
        seedSavedPost(userId, sourceId, PREFIX + "hdr-uid-1", "Title", new String[] {}, new String[] {},
                Instant.now().minus(2, ChronoUnit.HOURS));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/saved");

        // The disclosure-header letter — per spec §Content and
        // design 03 §/saved — MUST mention that saves are global
        // across DM and groups so the user is not surprised by DM-only
        // saves appearing in a group context.
        assertTrue(reply.text().contains("global across DM and groups"),
                "/saved reply header must disclose cross-scope visibility; got: " + reply.text());
    }

    @Test
    void savedListsAllRowsForActorRegardlessOfScopeOfOrigin() throws Exception {
        // Per spec §Per-user state (D13): saved_post carries user_id
        // only — no scope discriminator. Two saves under the same
        // actor with different snapshot_tags both appear under /saved
        // because the SQL predicate has no scope filter.
        String contactId = PREFIX + "noscope-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "noscope-source");
        seedSavedPost(userId, sourceId, PREFIX + "noscope-uid-a", "Title A",
                new String[] { "scope-a" }, new String[] {}, Instant.now().minus(2, ChronoUnit.HOURS));
        seedSavedPost(userId, sourceId, PREFIX + "noscope-uid-b", "Title B",
                new String[] { "scope-b" }, new String[] {}, Instant.now().minus(1, ChronoUnit.HOURS));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(contactId), "/saved");

        // Both rows appear regardless of which scope they were saved
        // from — proves the SQL has no WHERE scope_kind = ? clause.
        assertTrue(reply.text().contains(PREFIX + "noscope-uid-a"),
                "row A must appear in /saved listing");
        assertTrue(reply.text().contains(PREFIX + "noscope-uid-b"),
                "row B must appear in /saved listing");
    }

    @Test
    void savedFiltersByPersonalTag() throws Exception {
        String contactId = PREFIX + "ptag-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "ptag-source");
        seedSavedPost(userId, sourceId, PREFIX + "ptag-uid-ai-1", "AI 1",
                new String[] {}, new String[] { "ai" }, Instant.now().minus(3, ChronoUnit.HOURS));
        seedSavedPost(userId, sourceId, PREFIX + "ptag-uid-ai-2", "AI 2",
                new String[] {}, new String[] { "ai", "read-later" }, Instant.now().minus(2, ChronoUnit.HOURS));
        seedSavedPost(userId, sourceId, PREFIX + "ptag-uid-other", "Other",
                new String[] {}, new String[] { "read-later" }, Instant.now().minus(1, ChronoUnit.HOURS));

        OutboundMessage filtered = handler.handle(new ScopeRef.Dm(contactId), "/saved ai");

        assertTrue(filtered.text().contains(PREFIX + "ptag-uid-ai-1"),
                "personal-tag-filtered listing must include ai-1");
        assertTrue(filtered.text().contains(PREFIX + "ptag-uid-ai-2"),
                "personal-tag-filtered listing must include ai-2");
        assertFalse(filtered.text().contains(PREFIX + "ptag-uid-other"),
                "personal-tag-filtered listing must NOT include rows lacking the tag");
    }

    @Test
    void savedFiltersByWindow() throws Exception {
        String contactId = PREFIX + "win-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "win-source");
        seedSavedPost(userId, sourceId, PREFIX + "win-uid-old", "Old",
                new String[] {}, new String[] {}, Instant.now().minus(30, ChronoUnit.DAYS));
        seedSavedPost(userId, sourceId, PREFIX + "win-uid-recent", "Recent",
                new String[] {}, new String[] {}, Instant.now().minus(2, ChronoUnit.DAYS));

        OutboundMessage windowed = handler.handle(new ScopeRef.Dm(contactId), "/saved -w 7d");

        assertTrue(windowed.text().contains(PREFIX + "win-uid-recent"),
                "window-filtered listing must include the recent save");
        assertFalse(windowed.text().contains(PREFIX + "win-uid-old"),
                "window-filtered listing must NOT include the old save");
    }

    @Test
    void savedPaginatesByPageFlag() throws Exception {
        String contactId = PREFIX + "page-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "page-source");
        // 25 saves so page 1 = 20 rows, page 2 = 5 rows (PAGE_SIZE=20).
        for (int i = 0; i < 25; i++) {
            seedSavedPost(userId, sourceId, PREFIX + "page-uid-" + i, "P" + i,
                    new String[] {}, new String[] {},
                    Instant.now().minus(i, ChronoUnit.MINUTES));
        }

        OutboundMessage page1 = handler.handle(new ScopeRef.Dm(contactId), "/saved");
        OutboundMessage page2 = handler.handle(new ScopeRef.Dm(contactId), "/saved --page 2");

        // Page 1 shows the 20 most recent (i = 0..19); page 2 shows
        // the next 5 (i = 20..24).
        int onPage1 = countOccurrences(page1.text(), PREFIX + "page-uid-");
        int onPage2 = countOccurrences(page2.text(), PREFIX + "page-uid-");
        assertEquals(SavedCommandHandler.PAGE_SIZE, onPage1,
                "page 1 must contain exactly PAGE_SIZE rows");
        assertEquals(25 - SavedCommandHandler.PAGE_SIZE, onPage2,
                "page 2 must contain the remaining rows");
        // The newest (i=0) is on page 1; the oldest (i=24) is on page 2.
        assertTrue(page1.text().contains(PREFIX + "page-uid-0"),
                "newest save (i=0) must appear on page 1");
        assertTrue(page2.text().contains(PREFIX + "page-uid-24"),
                "oldest save (i=24) must appear on page 2");
    }

    @Test
    void savedFromGroupScopeReturnsGroupNotInV1() throws Exception {
        // No actor seed — short-circuit returns BEFORE any DB read.
        OutboundMessage reply = handler.handle(
                new ScopeRef.Group("adapter-group-id"), "/saved");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SAVED_GROUP_NOT_IN_V1), reply.text(),
                "group-scope /saved must short-circuit with error.saved.group_not_in_v1");
    }

    // ----- helpers --------------------------------------------------------

    private UUID seedUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state) VALUES (?, ?, FALSE, FALSE, 'vouched') "
                             + "RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID seedSource(String identifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags) VALUES ('rss', ?, ?, 'news', '{}') "
                             + "RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, "Test Source " + identifier);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedSavedPost(UUID userId, UUID sourceId, String postUid, String title,
                               String[] snapshotTags, String[] personalTags,
                               Instant savedAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO saved_post (user_id, post_uid, source_id, title, "
                             + "snapshot_tags, personal_tags, saved_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, userId);
            ps.setString(2, postUid);
            ps.setObject(3, sourceId);
            ps.setString(4, title);
            ps.setArray(5, conn.createArrayOf("TEXT", snapshotTags));
            ps.setArray(6, conn.createArrayOf("TEXT", personalTags));
            ps.setObject(7, OffsetDateTime.ofInstant(savedAt, java.time.ZoneOffset.UTC));
            ps.executeUpdate();
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        assertNotNull(haystack);
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
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
