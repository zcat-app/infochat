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

/** Tree-aware digest retrieval over the seeded tag tree (M1-867). */
@QuarkusTest
@TestProfile(FollowTopDigestIT.Profile.class)
class FollowTopDigestIT {

    static final UUID GROUP = UUID.fromString("d1e60001-0001-4000-8000-000000000001");
    static final UUID ADMIN = UUID.fromString("d1e60002-0002-4000-8000-000000000002");
    static final UUID SOURCE = UUID.fromString("d1e60003-0003-4000-8000-000000000003");
    static final UUID UNFOLLOW_GROUP = UUID.fromString("d1e60004-0004-4000-8000-000000000004");
    static final UUID MIXED_GROUP = UUID.fromString("d1e60005-0005-4000-8000-000000000005");
    static final UUID OTHERS_GROUP = UUID.fromString("d1e60006-0006-4000-8000-000000000006");
    static final UUID SOURCE2 = UUID.fromString("d1e60007-0007-4000-8000-000000000007");
    static final String UPSTREAM_GROUP = "followtop-it-g1";
    static final String UPSTREAM_UNFOLLOW = "followtop-it-g2";
    static final String UPSTREAM_MIXED = "followtop-it-g3";
    static final String UPSTREAM_OTHERS = "followtop-it-g4";
    static final String ADMIN_CONTACT = "followtop-it-admin";
    static final String UID_PREFIX = "ftop-";
    /** All fixtures share one fetched_at inside the existing May 2026 post partition. */
    static final Instant FETCHED_AT = Instant.parse("2026-05-22T12:00:00Z");

    @Inject @SeedDataSource DataSource dataSource;
    @Inject DigestWorker worker;
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
        // The reconciler-style exact-count ITs that run later in the same JVM
        // scan ALL now-stamped READY rows: nothing may outlive this class.
        adapter.reset();
        try (Connection conn = dataSource.getConnection()) {
            cleanTestData(conn);
        }
    }

    @Test
    void followTopRendersOneAggregatedSectionAndIncludesFutureLeaves() throws Exception {
        testLlmProvider.setResponseText("Aggregated tech roll-up.");

        Instant slot1Start = Instant.now().minusSeconds(3600);
        DigestSlot slot1 = new DigestSlot(GROUP, "UTC", "morning", slot1Start, Instant.now().plusSeconds(600));
        worker.execute(slot1);

        List<SectionRow> slot1Categories = readCategories(GROUP, slot1Start);
        assertEquals(1, slot1Categories.size(), "top follow renders exactly one category section, got: " + slot1Categories);
        assertEquals("tech", slot1Categories.get(0).slug());
        assertTrue(slot1Categories.get(0).content().contains("FTAI"),
                "aggregated tech section carries ai-leaf clusters");
        assertTrue(slot1Categories.get(0).content().contains("FTCYB"),
                "aggregated tech section carries cybersecurity-leaf clusters");

        // A leaf ADDED under the followed top after the follow appears in the
        // next digest with no re-follow (read-time expansion): scope_tag still
        // holds exactly the one tech row.
        addLeafUnderTech("ftop-new-leaf");
        Instant baseTime = Instant.now().minusSeconds(120);
        seedReadyPost("f1", "FTNEW 1", "ftop-new-leaf", baseTime);
        seedReadyPost("f2", "FTNEW 2", "ftop-new-leaf", baseTime);
        seedReadyPost("f3", "FTNEW 3", "ftop-new-leaf", baseTime);

        Instant slot2Start = Instant.now().plusSeconds(7200);
        DigestSlot slot2 = new DigestSlot(GROUP, "UTC", "evening", slot2Start, slot2Start.plusSeconds(3600));
        worker.execute(slot2);

        List<SectionRow> slot2Categories = readCategories(GROUP, slot2Start);
        assertEquals(1, slot2Categories.size(), "future-leaf digest keeps ONE aggregated category section");
        assertEquals("tech", slot2Categories.get(0).slug());
        assertTrue(slot2Categories.get(0).content().contains("FTNEW"),
                "the leaf added after the follow joins the aggregated section");
        assertEquals(1, countScopeTag(GROUP),
                "read-time expansion: no re-follow, scope_tag still holds the one top row");
    }

    @Test
    void mixedFollowKeysMostSpecificFollowedNodeAndKeepsTheExplicitUniverse() throws Exception {
        testLlmProvider.setResponseText("Mixed-follow roll-up.");
        Instant base = Instant.now().minusSeconds(120);
        for (int i = 1; i <= 3; i++) {
            seedReadyPost("mmai" + i, "FMM AI " + i, "ai", base);
            seedReadyPost("mmcyb" + i, "FMM CYB " + i, "cybersecurity", base);
            seedReadyPost("mmfoot" + i, "FMM FOOT " + i, "football", base);
        }

        Instant slotStart = Instant.now().minusSeconds(3600);
        DigestSlot slot = new DigestSlot(MIXED_GROUP, "UTC", "morning", slotStart,
                Instant.now().plusSeconds(600));
        worker.execute(slot);

        List<SectionRow> categories = readCategories(MIXED_GROUP, slotStart);
        assertEquals(List.of("ai", "tech"), categories.stream().map(SectionRow::slug).toList(),
                "one section per followed node: the ai leaf granular, the tech top aggregated — "
                        + "got: " + categories);
        assertTrue(categories.get(0).content().contains("FMM AI"),
                "ai-leaf clusters key to the most specific followed node — the ai leaf");
        assertTrue(categories.get(1).content().contains("FMM CYB"),
                "cybersecurity clusters key to the most specific followed node — the tech top");
        assertFalse(categories.get(1).content().contains("FMM AI"),
                "ai clusters never land in the tech aggregate");
        for (SectionRow section : categories) {
            assertFalse(section.content().contains("FMM FOOT"),
                    "the EXPLICIT qualifying universe is the followed leaf set — football posts "
                            + "under the unfollowed sport top never enter the digest");
        }
    }

    @Test
    void othersTopSectionNeverCollidesWithTheNullOtherBucket() throws Exception {
        testLlmProvider.setResponseText("Others roll-up.");
        Instant base = Instant.now().minusSeconds(120);
        for (int i = 1; i <= 3; i++) {
            seedClassificationPost("oa" + i, "FOA " + i, "personal", List.of("factual"), base);
            seedClassificationPost("ob" + i, "FOB " + i, "opinion", List.of("factual"), base);
        }
        seedClassificationPost("oc1", "FOC 1", "misc", List.of("personal"), base);
        seedClassificationPost("oc2", "FOC 2", "misc", List.of("personal"), base);

        Instant slotStart = Instant.now().minusSeconds(3600);
        DigestSlot slot = new DigestSlot(OTHERS_GROUP, "UTC", "morning", slotStart,
                Instant.now().plusSeconds(600));
        worker.execute(slot);

        List<SectionRow> categories = readCategories(OTHERS_GROUP, slotStart);
        assertEquals(List.of("others", "other"), categories.stream().map(SectionRow::slug).toList(),
                "the followed-others section renders its own slug, never colliding with the "
                        + "D62 null-tag Other bucket's literal 'other' — got: " + categories);
        String othersContent = categories.get(0).content();
        assertTrue(othersContent.contains("FOA"), "personal-leaf clusters render in the others section");
        assertTrue(othersContent.contains("FOB"), "opinion-leaf clusters render in the others section");
        assertFalse(othersContent.contains("FOC"),
                "all-personal clusters never enter the others section (M1-727)");
        String otherContent = categories.get(1).content();
        assertTrue(otherContent.contains("FOC"),
                "the all-personal cluster lands in the null-tag Other bucket — the classification "
                        + "axis stays distinct from the tag leaf axis");
        assertFalse(otherContent.contains("FOA"),
                "the followed-others section's content never leaks into the Other bucket");
    }

    @Test
    void unfollowSeedNodesFlowThroughExplicitDigest() throws Exception {
        testLlmProvider.setResponseText("Unfollow-seed roll-up.");
        Instant base = Instant.now().minusSeconds(120);
        for (int i = 1; i <= 3; i++) {
            seedReadyPostOn(SOURCE2, "ufa" + i, "UFA " + i, "ai", base);
            seedReadyPostOn(SOURCE2, "ufb" + i, "UFB " + i, "football", base);
        }

        adapter.deliverGroupMention(UPSTREAM_UNFOLLOW, ADMIN_CONTACT, "/unfollow-tag ai");

        String successReply = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_UNFOLLOW_TAG_SUCCESS_FROM_ALL, "en"), "ai");
        assertTrue(adapter.sentMessages().stream().map(OutboundMessage::text)
                        .anyMatch(text -> text.contains(successReply)),
                "the ALL→EXPLICIT flip must surface the success_from_all reply");
        assertEquals("EXPLICIT", tagModeOf(UNFOLLOW_GROUP),
                "tag_mode must flip to EXPLICIT");
        assertEquals(1, countScopeTag(UNFOLLOW_GROUP),
                "the seed joins the source's bootstrap_tags NODE rows minus the unfollowed one");
        assertTrue(scopeTagNames(UNFOLLOW_GROUP).contains("football"),
                "the remaining node row is the seeded football leaf");
        assertFalse(scopeTagNames(UNFOLLOW_GROUP).contains("ai"),
                "the unfollowed ai node is not re-seeded");

        Instant slotStart = Instant.now().minusSeconds(3600);
        DigestSlot slot = new DigestSlot(UNFOLLOW_GROUP, "UTC", "morning", slotStart,
                Instant.now().plusSeconds(600));
        worker.execute(slot);

        List<SectionRow> categories = readCategories(UNFOLLOW_GROUP, slotStart);
        assertEquals(1, categories.size(),
                "the seeded nodes flow through the expanding EXPLICIT digest — got: " + categories);
        assertEquals("football", categories.get(0).slug());
        assertTrue(categories.get(0).content().contains("UFB"),
                "posts under the seeded node are delivered");
        assertFalse(categories.get(0).content().contains("UFA"),
                "posts under the unfollowed node never reach the digest");
    }

    @Test
    void unfollowTopFromAllExcludesDescendantLeaves() throws Exception {
        testLlmProvider.setResponseText("Unfollow-top roll-up.");
        Instant base = Instant.now().minusSeconds(120);
        for (int i = 1; i <= 3; i++) {
            seedReadyPostOn(SOURCE2, "uta" + i, "UTA " + i, "ai", base);
            seedReadyPostOn(SOURCE2, "utf" + i, "UTF " + i, "football", base);
        }

        adapter.deliverGroupMention(UPSTREAM_UNFOLLOW, ADMIN_CONTACT, "/unfollow-tag tech");

        String successReply = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_UNFOLLOW_TAG_SUCCESS_FROM_ALL, "en"), "tech");
        assertTrue(adapter.sentMessages().stream().map(OutboundMessage::text)
                        .anyMatch(text -> text.contains(successReply)),
                "the ALL→EXPLICIT flip must surface the success_from_all reply");
        assertEquals("EXPLICIT", tagModeOf(UNFOLLOW_GROUP),
                "tag_mode must flip to EXPLICIT");
        assertEquals(1, countScopeTag(UNFOLLOW_GROUP),
                "the seed must drop every tech-subtree leaf, keeping only football — got: "
                        + scopeTagNames(UNFOLLOW_GROUP));
        assertTrue(scopeTagNames(UNFOLLOW_GROUP).contains("football"),
                "the unrelated sport leaf remains followed");
        assertFalse(scopeTagNames(UNFOLLOW_GROUP).contains("ai"),
                "the ai leaf under the unfollowed tech top is excluded from the seed");

        Instant slotStart = Instant.now().minusSeconds(3600);
        DigestSlot slot = new DigestSlot(UNFOLLOW_GROUP, "UTC", "morning", slotStart,
                Instant.now().plusSeconds(600));
        worker.execute(slot);

        List<SectionRow> categories = readCategories(UNFOLLOW_GROUP, slotStart);
        assertEquals(1, categories.size(),
                "only the surviving football leaf renders a section — got: " + categories);
        assertEquals("football", categories.get(0).slug());
        assertTrue(categories.get(0).content().contains("UTF"),
                "posts under the surviving leaf are delivered");
        assertFalse(categories.get(0).content().contains("UTA"),
                "AI content under the excluded tech subtree never reaches the digest");
    }

    // -- helpers ----------------------------------------------------------------

    private List<String> scopeTagNames(UUID groupId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT t.name FROM scope_tag st JOIN tag t ON t.id = st.tag_id"
                             + " WHERE st.scope_kind = 'group' AND st.scope_id = ? ORDER BY t.name")) {
            ps.setObject(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return out;
            }
        }
    }

    private String tagModeOf(UUID groupId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT tag_mode FROM scope_preferences WHERE scope_kind = 'group' AND scope_id = ?")) {
            ps.setObject(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private void seedClassificationPost(String slug, String title, String tag,
                                        List<String> classification, Instant readyAt)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (id, uid, source_id, title, body, status,"
                             + " published_at, fetched_at, ready_at, tags, classification,"
                             + " stage1_done, stage2_done, tagger_done, embedding_done,"
                             + " upstream_identifier)"
                             + " VALUES (?, ?, ?, ?, ?, 'READY', ?, ?, ?, ?,"
                             + " ?, TRUE, TRUE, TRUE, TRUE, ?)")) {
            ps.setObject(1, UUID.nameUUIDFromBytes(("ftop-post-" + slug).getBytes()));
            ps.setString(2, UID_PREFIX + slug);
            ps.setObject(3, SOURCE);
            ps.setString(4, title);
            ps.setString(5, title + " body.");
            ps.setTimestamp(6, Timestamp.from(readyAt));
            ps.setTimestamp(7, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(8, Timestamp.from(readyAt));
            ps.setArray(9, conn.createArrayOf("TEXT", new String[] { tag }));
            ps.setArray(10, conn.createArrayOf("TEXT", classification.toArray(new String[0])));
            ps.setString(11, UID_PREFIX + slug);
            ps.executeUpdate();
        }
    }

    private void addLeafUnderTech(String leafName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tag (name, display, source_origin, node_kind, parent_name)"
                             + " VALUES (?, ?, 'user', 'leaf', 'tech')")) {
            ps.setString(1, leafName);
            ps.setString(2, leafName);
            ps.executeUpdate();
        }
    }

    private int countScopeTag(UUID groupId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM scope_tag WHERE scope_kind = 'group' AND scope_id = ?")) {
            ps.setObject(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
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

    private List<SectionRow> readCategories(UUID groupId, Instant windowStart) throws Exception {
        return readSections(groupId, windowStart).stream()
                .filter(s -> !"LEAD".equals(s.slug()))
                .toList();
    }

    private void seedReadyPost(String slug, String title, String tag, Instant readyAt) throws Exception {
        seedReadyPostOn(SOURCE, slug, title, tag, readyAt);
    }

    private void seedReadyPostOn(UUID sourceId, String slug, String title, String tag,
                                 Instant readyAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (id, uid, source_id, title, body, status,"
                             + " published_at, fetched_at, ready_at, tags,"
                             + " stage1_done, stage2_done, tagger_done, embedding_done,"
                             + " upstream_identifier)"
                             + " VALUES (?, ?, ?, ?, ?, 'READY', ?, ?, ?, ARRAY[?]::TEXT[],"
                             + " TRUE, TRUE, TRUE, TRUE, ?)")) {
            ps.setObject(1, UUID.nameUUIDFromBytes(("ftop-post-" + slug).getBytes()));
            ps.setString(2, UID_PREFIX + slug);
            ps.setObject(3, sourceId);
            ps.setString(4, title);
            ps.setString(5, title + " body.");
            ps.setTimestamp(6, Timestamp.from(readyAt));
            ps.setTimestamp(7, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(8, Timestamp.from(readyAt));
            ps.setString(9, tag);
            ps.setString(10, UID_PREFIX + slug);
            ps.executeUpdate();
        }
    }

    private void seedTestData(Connection conn) throws SQLException {
        Instant base = Instant.now().minusSeconds(120);
        exec(conn,
                "INSERT INTO source (id, kind, identifier, display_name, category, status)"
                        + " VALUES (?, 'rss', 'http://followtop-it.example.com/feed',"
                        + " 'FollowTop IT Source', 'news', 'active')"
                        + " ON CONFLICT (kind, identifier) DO UPDATE SET id = EXCLUDED.id",
                SOURCE);
        exec(conn,
                "INSERT INTO source (id, kind, identifier, display_name, category, status, bootstrap_tags)"
                        + " VALUES (?, 'rss', 'http://followtop-it2.example.com/feed',"
                        + " 'FollowTop IT Seed Source', 'news', 'active', ARRAY['ai','football']::TEXT[])"
                        + " ON CONFLICT (kind, identifier)"
                        + " DO UPDATE SET id = EXCLUDED.id, bootstrap_tags = EXCLUDED.bootstrap_tags",
                SOURCE2);
        exec(conn,
                "INSERT INTO users (id, adapter, contact_id, display_name, is_admin, registration_state)"
                        + " VALUES (?, 'inmemory', ?, 'FollowTop IT Admin', TRUE, 'vouched')"
                        + " ON CONFLICT (adapter, contact_id)"
                        + " DO UPDATE SET id = EXCLUDED.id, is_admin = TRUE, is_banned = FALSE",
                ADMIN, ADMIN_CONTACT);
        exec(conn,
                "INSERT INTO groups (id, adapter, upstream_group_id, display_name, timezone, approval_status)"
                        + " VALUES (?, 'inmemory', ?, 'FollowTop IT Group', 'UTC', 'approved')"
                        + " ON CONFLICT (adapter, upstream_group_id)"
                        + " DO UPDATE SET id = EXCLUDED.id, removed_at = NULL, approval_status = 'approved'",
                GROUP, UPSTREAM_GROUP);
        exec(conn,
                "INSERT INTO groups (id, adapter, upstream_group_id, display_name, timezone, approval_status)"
                        + " VALUES (?, 'inmemory', ?, 'FollowTop IT Unfollow Group', 'UTC', 'approved')"
                        + " ON CONFLICT (adapter, upstream_group_id)"
                        + " DO UPDATE SET id = EXCLUDED.id, removed_at = NULL, approval_status = 'approved'",
                UNFOLLOW_GROUP, UPSTREAM_UNFOLLOW);
        exec(conn,
                "INSERT INTO groups (id, adapter, upstream_group_id, display_name, timezone, approval_status)"
                        + " VALUES (?, 'inmemory', ?, 'FollowTop IT Mixed Group', 'UTC', 'approved')"
                        + " ON CONFLICT (adapter, upstream_group_id)"
                        + " DO UPDATE SET id = EXCLUDED.id, removed_at = NULL, approval_status = 'approved'",
                MIXED_GROUP, UPSTREAM_MIXED);
        exec(conn,
                "INSERT INTO groups (id, adapter, upstream_group_id, display_name, timezone, approval_status)"
                        + " VALUES (?, 'inmemory', ?, 'FollowTop IT Others Group', 'UTC', 'approved')"
                        + " ON CONFLICT (adapter, upstream_group_id)"
                        + " DO UPDATE SET id = EXCLUDED.id, removed_at = NULL, approval_status = 'approved'",
                OTHERS_GROUP, UPSTREAM_OTHERS);
        exec(conn,
                "INSERT INTO group_membership (group_id, user_id, is_group_admin)"
                        + " VALUES (?, ?, TRUE) ON CONFLICT (group_id, user_id)"
                        + " DO UPDATE SET is_group_admin = TRUE, removed_at = NULL",
                GROUP, ADMIN);
        exec(conn,
                "INSERT INTO group_membership (group_id, user_id, is_group_admin)"
                        + " VALUES (?, ?, TRUE) ON CONFLICT (group_id, user_id)"
                        + " DO UPDATE SET is_group_admin = TRUE, removed_at = NULL",
                UNFOLLOW_GROUP, ADMIN);
        exec(conn,
                "INSERT INTO group_membership (group_id, user_id, is_group_admin)"
                        + " VALUES (?, ?, TRUE) ON CONFLICT (group_id, user_id)"
                        + " DO UPDATE SET is_group_admin = TRUE, removed_at = NULL",
                MIXED_GROUP, ADMIN);
        exec(conn,
                "INSERT INTO group_membership (group_id, user_id, is_group_admin)"
                        + " VALUES (?, ?, TRUE) ON CONFLICT (group_id, user_id)"
                        + " DO UPDATE SET is_group_admin = TRUE, removed_at = NULL",
                OTHERS_GROUP, ADMIN);
        for (UUID groupId : List.of(GROUP, MIXED_GROUP, OTHERS_GROUP)) {
            exec(conn,
                    "INSERT INTO scope_preferences (scope_kind, scope_id, tag_mode,"
                            + " tag_subscription_version, source_subscription_version)"
                            + " VALUES ('group', ?, 'EXPLICIT', 1, 1)"
                            + " ON CONFLICT (scope_kind, scope_id)"
                            + " DO UPDATE SET tag_mode = 'EXPLICIT', tag_subscription_version = 1,"
                            + " source_subscription_version = 1",
                    groupId);
        }
        exec(conn,
                "INSERT INTO scope_preferences (scope_kind, scope_id, tag_mode,"
                        + " tag_subscription_version, source_subscription_version)"
                        + " VALUES ('group', ?, 'ALL', 1, 1)"
                        + " ON CONFLICT (scope_kind, scope_id)"
                        + " DO UPDATE SET tag_mode = 'ALL', tag_subscription_version = 1,"
                        + " source_subscription_version = 1",
                UNFOLLOW_GROUP);
        exec(conn,
                "INSERT INTO scope_tag (scope_kind, scope_id, tag_id)"
                        + " SELECT 'group', ?, id FROM tag WHERE name = 'tech'",
                GROUP);
        exec(conn,
                "INSERT INTO scope_tag (scope_kind, scope_id, tag_id)"
                        + " SELECT 'group', ?, id FROM tag WHERE name IN ('tech', 'ai')",
                MIXED_GROUP);
        exec(conn,
                "INSERT INTO scope_tag (scope_kind, scope_id, tag_id)"
                        + " SELECT 'group', ?, id FROM tag WHERE name = 'others'",
                OTHERS_GROUP);
        exec(conn,
                "INSERT INTO source_subscription (scope_kind, scope_id, source_id)"
                        + " VALUES ('group', ?, ?) ON CONFLICT DO NOTHING",
                GROUP, SOURCE);
        exec(conn,
                "INSERT INTO source_subscription (scope_kind, scope_id, source_id)"
                        + " VALUES ('group', ?, ?) ON CONFLICT DO NOTHING",
                MIXED_GROUP, SOURCE);
        exec(conn,
                "INSERT INTO source_subscription (scope_kind, scope_id, source_id)"
                        + " VALUES ('group', ?, ?) ON CONFLICT DO NOTHING",
                OTHERS_GROUP, SOURCE);
        exec(conn,
                "INSERT INTO source_subscription (scope_kind, scope_id, source_id)"
                        + " VALUES ('group', ?, ?) ON CONFLICT DO NOTHING",
                UNFOLLOW_GROUP, SOURCE2);
        for (int i = 1; i <= 3; i++) {
            exec(conn, postSql("a" + i, "FTAI " + i, "ai", base));
            exec(conn, postSql("c" + i, "FTCYB " + i, "cybersecurity", base));
        }
    }

    private static String postSql(String slug, String title, String tag, Instant at) {
        return "INSERT INTO post (id, uid, source_id, title, body, status,"
                + " published_at, fetched_at, ready_at, tags,"
                + " stage1_done, stage2_done, tagger_done, embedding_done, upstream_identifier)"
                + " VALUES ('" + UUID.nameUUIDFromBytes(("ftop-post-" + slug).getBytes()) + "', '"
                + UID_PREFIX + slug + "', '" + SOURCE + "', '" + title + "', '" + title + " body.',"
                + " 'READY', '" + Timestamp.from(at) + "', '" + Timestamp.from(FETCHED_AT)
                + "', '" + Timestamp.from(at)
                + "', ARRAY['" + tag + "']::TEXT[], TRUE, TRUE, TRUE, TRUE, '" + UID_PREFIX + slug + "')";
    }

    private void cleanTestData(Connection conn) throws SQLException {
        exec(conn, "DELETE FROM digest_section WHERE group_id IN (?, ?, ?, ?)",
                GROUP, UNFOLLOW_GROUP, MIXED_GROUP, OTHERS_GROUP);
        exec(conn, "DELETE FROM digest_category_delivery WHERE group_id IN (?, ?, ?, ?)",
                GROUP, UNFOLLOW_GROUP, MIXED_GROUP, OTHERS_GROUP);
        exec(conn, "DELETE FROM summary_cache WHERE group_id IN (?, ?, ?, ?)",
                GROUP, UNFOLLOW_GROUP, MIXED_GROUP, OTHERS_GROUP);
        exec(conn, "DELETE FROM source_subscription WHERE scope_id IN (?, ?, ?, ?)",
                GROUP, UNFOLLOW_GROUP, MIXED_GROUP, OTHERS_GROUP);
        exec(conn, "DELETE FROM scope_tag WHERE scope_id IN (?, ?, ?, ?)",
                GROUP, UNFOLLOW_GROUP, MIXED_GROUP, OTHERS_GROUP);
        exec(conn, "DELETE FROM scope_preferences WHERE scope_id IN (?, ?, ?, ?)",
                GROUP, UNFOLLOW_GROUP, MIXED_GROUP, OTHERS_GROUP);
        exec(conn, "DELETE FROM audit_log WHERE scope_id IN (?, ?, ?, ?)",
                GROUP, UNFOLLOW_GROUP, MIXED_GROUP, OTHERS_GROUP);
        exec(conn, "DELETE FROM group_membership WHERE group_id IN (?, ?, ?, ?)",
                GROUP, UNFOLLOW_GROUP, MIXED_GROUP, OTHERS_GROUP);
        exec(conn, "DELETE FROM post WHERE uid LIKE '" + UID_PREFIX + "%'");
        exec(conn, "DELETE FROM source WHERE id = ?", SOURCE2);
        exec(conn, "DELETE FROM tag WHERE name LIKE 'ftop-%'");
    }

    private static void exec(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.digest.lead-minimum", "100");
        }
    }
}
