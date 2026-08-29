package app.zcat.infochat.provider.digest;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.testing.TestLlmProvider;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The full-mode digest topics footer: one capped ranked line on the
 * last section, byte-identity, persist + replay, ranking, caps. */
@QuarkusTest
@TestProfile(DigestTopicsFooterIT.Profile.class)
class DigestTopicsFooterIT {

    static final UUID GROUP = UUID.fromString("d3660001-0001-4000-8000-000000000001");
    static final UUID ADMIN = UUID.fromString("d3660002-0002-4000-8000-000000000002");
    static final UUID SOURCE = UUID.fromString("d3660003-0003-4000-8000-000000000003");
    static final UUID SOURCE2 = UUID.fromString("d3660004-0004-4000-8000-000000000004");
    static final UUID SOURCE3 = UUID.fromString("d3660005-0005-4000-8000-000000000005");
    static final String UPSTREAM_GROUP = "topics-it-g1";
    static final String ADMIN_CONTACT = "topics-it-admin";
    static final String UID_PREFIX = "ftopic-";
    static final Instant FETCHED_AT = Instant.parse("2026-05-22T12:00:00Z");

    /** Every render stubs the same benign prose so section bytes are exact literals. */
    static final String PROSE = "Footer IT prose.";
    static final String AFFORDANCE =
            "/summary <tag> to drill into a topic, or @mention me to go deeper on any story "
                    + "or ask about one you don't see here.";

    @Inject @SeedDataSource DataSource dataSource;
    @Inject DigestWorker worker;
    @Inject DigestRetryService retryService;
    @Inject TestLlmProvider testLlmProvider;
    @Inject InMemoryAdapter adapter;
    @Inject BundleLoader bundleLoader;

    record SectionRow(String slug, String content) {}

    @BeforeEach
    void setUp() throws Exception {
        adapter.reset();
        testLlmProvider.reset();
        try (Connection conn = dataSource.getConnection()) {
            cleanTestData(conn);
            seedTestData(conn);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        adapter.reset();
        try (Connection conn = dataSource.getConnection()) {
            cleanTestData(conn);
        }
    }

    @Test
    void fullModeDigestWithFreeTagsAppendsOneTopicsLineToLastSection() throws Exception {
        testLlmProvider.setResponseText(PROSE);
        Instant base = Instant.now().minusSeconds(120);
        // search_tags: czechia on 4 posts, prague on 2 — one source, so
        // corroboration is flat and display counts equal post counts.
        seedPost("a1", "AI 1", "ai", base, List.of("czechia"));
        seedPost("a2", "AI 2", "ai", base, List.of("czechia"));
        seedPost("a3", "AI 3", "ai", base, List.of("prague"));
        seedPost("s1", "Sec 1", "security", base, List.of("czechia"));
        seedPost("s2", "Sec 2", "security", base, List.of("czechia"));
        seedPost("s3", "Sec 3", "security", base, List.of("prague"));

        Instant slotStart = Instant.now().minusSeconds(3600);
        worker.execute(new DigestSlot(GROUP, "UTC", "morning", slotStart,
                Instant.now().plusSeconds(600)));

        List<SectionRow> sections = readSections(GROUP, slotStart);
        assertEquals(List.of("ai", "security"), sections.stream().map(SectionRow::slug).toList(),
                "section count and order are untouched by the footer");
        String footer = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_DIGEST_TOPICS_FOOTER, "en"),
                "czechia (4), prague (2)");
        assertEquals("AI NEWS\n\n" + PROSE + "\n\n" + PROSE + "\n\n" + PROSE,
                sections.get(0).content(),
                "only the LAST section carries the footer");
        assertEquals("SECURITY NEWS\n\n" + PROSE + "\n\n" + PROSE + "\n\n" + PROSE
                        + "\n\n" + footer + "\n\n" + AFFORDANCE,
                sections.get(1).content(),
                "the footer rides the last section after the body and BEFORE the closing affordance");
        long occurrences = sections.stream()
                .filter(s -> s.content().contains("Topics this period")).count();
        assertEquals(1, occurrences, "exactly ONE topics line in the whole digest");
        assertEquals(2, adapter.sentMessages().size(),
                "no new message: one outbound per section, the footer rides existing bytes");
    }

    @Test
    void noFreeTagsRendersByteIdentically() throws Exception {
        testLlmProvider.setResponseText(PROSE);
        Instant base = Instant.now().minusSeconds(120);
        seedPost("a1", "AI 1", "ai", base, List.of());
        seedPost("a2", "AI 2", "ai", base, List.of());
        seedPost("a3", "AI 3", "ai", base, List.of());
        seedPost("s1", "Sec 1", "security", base, List.of());
        seedPost("s2", "Sec 2", "security", base, List.of());
        seedPost("s3", "Sec 3", "security", base, List.of());

        Instant slotStart = Instant.now().minusSeconds(3600);
        worker.execute(new DigestSlot(GROUP, "UTC", "morning", slotStart,
                Instant.now().plusSeconds(600)));

        List<SectionRow> sections = readSections(GROUP, slotStart);
        assertEquals(2, sections.size());
        // Golden bytes: the pre-footer shape (the DigestRendererSectionsTest
        // fixture shape — 3+3 posts, two qualifying categories, stub prose,
        // no lead, no overflow) renders byte-identically when no post
        // carries a free tag (the acceptance-2 fence).
        assertEquals("AI NEWS\n\n" + PROSE + "\n\n" + PROSE + "\n\n" + PROSE,
                sections.get(0).content());
        assertEquals("SECURITY NEWS\n\n" + PROSE + "\n\n" + PROSE + "\n\n" + PROSE
                        + "\n\n" + AFFORDANCE,
                sections.get(1).content());
        for (SectionRow section : sections) {
            assertFalse(section.content().contains("Topics this period"),
                    "zero free tags append no footer line");
        }
    }

    @Test
    void persistsAndReplaysWithTheFooter() throws Exception {
        testLlmProvider.setResponseText(PROSE);
        Instant base = Instant.now().minusSeconds(120);
        for (int i = 1; i <= 3; i++) {
            seedPost("a" + i, "AI " + i, "ai", base, List.of("czechia"));
            seedPost("s" + i, "Sec " + i, "security", base, List.of("czechia"));
        }
        Instant slotStart = Instant.now().minusSeconds(3600);
        worker.execute(new DigestSlot(GROUP, "UTC", "morning", slotStart,
                Instant.now().plusSeconds(600)));

        String footer = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_DIGEST_TOPICS_FOOTER, "en"), "czechia (6)");
        assertTrue(readSections(GROUP, slotStart).getLast().content().contains(footer),
                "the persisted digest_section bytes carry the line (computed inside the render pass)");

        // An interrupted delivery: the delivery bookkeeping is gone, the
        // rendered bytes remain. /retry --digest replays the stored bytes.
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM digest_category_delivery WHERE group_id = ?", GROUP);
        }

        adapter.reset();
        int callsBeforeRetry = testLlmProvider.callCount();
        DigestRetryService.RetryResult result = retryService.retryDigest(GROUP);
        assertEquals(DigestRetryService.RetryResult.REPLAYED_MISSING, result);
        assertEquals(callsBeforeRetry, testLlmProvider.callCount(),
                "replay re-delivers persisted bytes with ZERO recomputation");
        assertTrue(adapter.sentMessages().stream().map(OutboundMessage::text)
                        .anyMatch(text -> text.contains(footer)),
                "the replayed message carries the footer line byte-identically");
    }

    @Test
    void rankingFollowsWeightedCountNotAlphabet() throws Exception {
        testLlmProvider.setResponseText(PROSE);
        Instant base = Instant.now().minusSeconds(120);
        // alpha: 5 posts/1 source (38); zeta: 3 posts/3 sources (103)
        // under 3 active sources — corroboration flips the order, so both
        // alphabetical-primary and bare post-count orders fail.
        for (int i = 1; i <= 5; i++) {
            seedPost("al" + i, "Alpha " + i, "security", base, List.of("alpha"));
        }
        seedPostOn(SOURCE2, "ze1", "Zeta 1", "ai", base, List.of("zeta"));
        seedPostOn(SOURCE3, "ze2", "Zeta 2", "ai", base, List.of("zeta"));
        seedPostOn(SOURCE, "ze3", "Zeta 3", "ai", base, List.of("zeta"));

        Instant slotStart = Instant.now().minusSeconds(3600);
        worker.execute(new DigestSlot(GROUP, "UTC", "morning", slotStart,
                Instant.now().plusSeconds(600)));

        String last = readSections(GROUP, slotStart).getLast().content();
        String footer = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_DIGEST_TOPICS_FOOTER, "en"),
                "zeta (3), alpha (5)");
        assertTrue(last.contains(footer),
                "weightedCount ranks zeta (3 posts, 3 sources) above alpha (5 posts, 1 source)");
    }

    @Test
    void footerCapsAtSevenTopicsWithOverflowCount() throws Exception {
        testLlmProvider.setResponseText(PROSE);
        Instant base = Instant.now().minusSeconds(120);
        for (int i = 1; i <= 9; i++) {
            seedPost("t" + i, "Topic " + i, "security", base,
                    List.of(String.format("t%02d", i)));
        }
        Instant slotStart = Instant.now().minusSeconds(3600);
        worker.execute(new DigestSlot(GROUP, "UTC", "morning", slotStart,
                Instant.now().plusSeconds(600)));

        String last = readSections(GROUP, slotStart).getLast().content();
        String tokens = "t01 (1), t02 (1), t03 (1), t04 (1), t05 (1), t06 (1), t07 (1)";
        String footer = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_DIGEST_TOPICS_FOOTER, "en"),
                tokens + " " + MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_DIGEST_TOPICS_FOOTER_MORE, "en"), 2));
        assertTrue(last.contains(footer),
                "exactly 7 topics render, the remaining 2 collapse into the overflow count");
        assertFalse(last.contains("t08"), "topics beyond the cap never render");
    }

    // -- helpers ----------------------------------------------------------------

    private void seedPost(String slug, String title, String tag, Instant readyAt,
                          List<String> searchTags) throws Exception {
        seedPostOn(SOURCE, slug, title, tag, readyAt, searchTags);
    }

    private void seedPostOn(UUID sourceId, String slug, String title, String tag,
                            Instant readyAt, List<String> searchTags) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (id, uid, source_id, title, body, status,"
                             + " published_at, fetched_at, ready_at, tags, search_tags,"
                             + " stage1_done, stage2_done, tagger_done, embedding_done,"
                             + " upstream_identifier)"
                             + " VALUES (?, ?, ?, ?, ?, 'READY', ?, ?, ?, ARRAY[?]::TEXT[],"
                             + " ?::TEXT[], TRUE, TRUE, TRUE, TRUE, ?)")) {
            ps.setObject(1, UUID.nameUUIDFromBytes(("ftopic-post-" + slug).getBytes()));
            ps.setString(2, UID_PREFIX + slug);
            ps.setObject(3, sourceId);
            ps.setString(4, title);
            ps.setString(5, title + " body.");
            ps.setTimestamp(6, Timestamp.from(readyAt));
            ps.setTimestamp(7, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(8, Timestamp.from(readyAt));
            ps.setString(9, tag);
            ps.setArray(10, conn.createArrayOf("TEXT", searchTags.toArray(new String[0])));
            ps.setString(11, UID_PREFIX + slug);
            ps.executeUpdate();
        }
    }

    private List<SectionRow> readSections(UUID groupId, Instant windowStart) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT category_slug, content FROM digest_section"
                             + " WHERE group_id = ? AND window_start = ? ORDER BY position")) {
            ps.setObject(1, groupId);
            ps.setTimestamp(2, Timestamp.from(windowStart));
            try (ResultSet rs = ps.executeQuery()) {
                List<SectionRow> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new SectionRow(rs.getString(1), rs.getString(2)));
                }
                return out;
            }
        }
    }

    private static void exec(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    private void seedTestData(Connection conn) throws SQLException {
        for (UUID sourceId : List.of(SOURCE, SOURCE2, SOURCE3)) {
            exec(conn,
                    "INSERT INTO source (id, kind, identifier, display_name, category, status)"
                            + " VALUES (?, 'rss', 'http://topics-it-" + sourceId + "/feed',"
                            + " 'Topics IT Source', 'news', 'active')"
                            + " ON CONFLICT (kind, identifier) DO UPDATE SET id = EXCLUDED.id",
                    sourceId);
        }
        exec(conn,
                "INSERT INTO users (id, adapter, contact_id, display_name, is_admin, registration_state)"
                        + " VALUES (?, 'inmemory', ?, 'Topics IT Admin', TRUE, 'vouched')"
                        + " ON CONFLICT (adapter, contact_id)"
                        + " DO UPDATE SET id = EXCLUDED.id, is_admin = TRUE, is_banned = FALSE",
                ADMIN, ADMIN_CONTACT);
        exec(conn,
                "INSERT INTO groups (id, adapter, upstream_group_id, display_name, timezone,"
                        + " approval_status, digest_mode)"
                        + " VALUES (?, 'inmemory', ?, 'Topics IT Group', 'UTC', 'approved', 'full')"
                        + " ON CONFLICT (adapter, upstream_group_id)"
                        + " DO UPDATE SET id = EXCLUDED.id, removed_at = NULL,"
                        + " approval_status = 'approved', digest_mode = 'full'",
                GROUP, UPSTREAM_GROUP);
        exec(conn,
                "INSERT INTO group_membership (group_id, user_id, is_group_admin)"
                        + " VALUES (?, ?, TRUE) ON CONFLICT (group_id, user_id)"
                        + " DO UPDATE SET is_group_admin = TRUE, removed_at = NULL",
                GROUP, ADMIN);
        exec(conn,
                "INSERT INTO scope_preferences (scope_kind, scope_id, tag_mode,"
                        + " tag_subscription_version, source_subscription_version)"
                        + " VALUES ('group', ?, 'ALL', 1, 1)"
                        + " ON CONFLICT (scope_kind, scope_id)"
                        + " DO UPDATE SET tag_mode = 'ALL', tag_subscription_version = 1,"
                        + " source_subscription_version = 1",
                GROUP);
        for (UUID sourceId : List.of(SOURCE, SOURCE2, SOURCE3)) {
            exec(conn,
                    "INSERT INTO source_subscription (scope_kind, scope_id, source_id)"
                            + " VALUES ('group', ?, ?) ON CONFLICT DO NOTHING",
                    GROUP, sourceId);
        }
    }

    private void cleanTestData(Connection conn) throws SQLException {
        exec(conn, "DELETE FROM digest_section WHERE group_id = ?", GROUP);
        exec(conn, "DELETE FROM digest_category_delivery WHERE group_id = ?", GROUP);
        exec(conn, "DELETE FROM summary_cache WHERE group_id = ?", GROUP);
        exec(conn, "DELETE FROM source_subscription WHERE scope_id = ?", GROUP);
        exec(conn, "DELETE FROM scope_preferences WHERE scope_id = ?", GROUP);
        exec(conn, "DELETE FROM audit_log WHERE scope_id = ?", GROUP);
        exec(conn, "DELETE FROM group_membership WHERE group_id = ?", GROUP);
        exec(conn, "DELETE FROM post WHERE uid LIKE '" + UID_PREFIX + "%'");
        exec(conn, "DELETE FROM source WHERE id IN (?, ?, ?)", SOURCE, SOURCE2, SOURCE3);
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.digest.lead-minimum", "100",
                    "infochat.digest.retry-cooldown", "PT0S");
        }
    }
}
