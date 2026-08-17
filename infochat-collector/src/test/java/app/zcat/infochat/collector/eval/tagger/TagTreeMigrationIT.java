package app.zcat.infochat.collector.eval.tagger;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.util.TagNormalizer;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the M1-866 V84 migration: the seed, the deterministic zero-LLM lookup
 * over the flat operator profile, the tag_candidates entity-continuity writes,
 * the scope_tag remap, the retirement, and the loud failure on unmapped
 * leftovers (nostr/video). Each migration-driven test re-executes V84's own
 * statements read from the classpath resource (the M1-861 shape — the test
 * cannot drift from the SQL), skipping the ALTER the boot-time migration
 * already applied.
 */
@QuarkusTest
class TagTreeMigrationIT {

    /** The M1-864 record's frozen list (verbatim) plus the seven per-top residual leaves added by product ruling at start — top -> leaves. */
    static final Map<String, List<String>> TOPS_TO_LEAVES = new LinkedHashMap<>() {{
        put("sport", List.of("football", "basketball", "hockey", "tennis", "motorsport",
            "athletics", "esports", "other-sports"));
        put("health", List.of("medicine", "nutrition", "fitness", "mental-health",
            "public-health", "other-health"));
        put("fashion", List.of("style", "beauty", "luxury", "other-fashion"));
        put("culture", List.of("art", "movies", "music", "tv", "books", "gaming", "other-culture"));
        put("science", List.of("space", "environment", "biology", "physics", "research", "other-science"));
        put("tech", List.of("ai", "software-development", "cybersecurity", "robotics",
            "hardware", "internet", "other-tech"));
        put("business", List.of("markets", "economy", "crypto", "startups",
            "personal-finance", "other-business"));
        put("news", List.of("world", "africa", "americas", "asia", "europe", "middle-east"));
        put("others", List.of("personal", "opinion", "misc"));
    }};

    /** The fallback-marked leaves: the News residual (world) + the seven per-top residuals. */
    static final Set<String> FALLBACK_LEAVES = Set.of(
        "world", "other-sports", "other-health", "other-fashion", "other-culture",
        "other-science", "other-tech", "other-business");

    /** The flat profile's mapped-away v1 names (retired by the migration), plus comfyui. */
    private static final List<String> RETIRED_V1 = List.of(
        "claude", "openai", "anthropic", "qwen", "google", "zcash", "malware", "privacy",
        "security", "quarkus", "java", "spring-io", "langchain4j", "oracle", "development",
        "comfyui", "news", "glmai", "kimiai");

    /** The entity names (decision 6): mapped-away vendor/model/product names that additionally land in tag_candidates. */
    private static final Set<String> ENTITY_NAMES = Set.of(
        "claude", "openai", "anthropic", "qwen", "google", "zcash", "quarkus", "spring-io",
        "langchain4j", "oracle", "comfyui", "glmai", "kimiai");

    private static final Instant PINNED_NOW = Instant.parse("2026-05-15T14:30:00Z");
    private static final Instant FETCHED_AT = Instant.parse("2026-05-15T13:00:00Z");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    TagVocabulary tagVocabulary;

    @Inject
    TaggerWorker taggerWorker;

    @Inject
    app.zcat.infochat.llm.LlmProvider llmProvider;

    private final List<UUID> seededPostIds = new ArrayList<>();
    private final List<UUID> seededSourceIds = new ArrayList<>();
    private final List<UUID> seededScopeIds = new ArrayList<>();

    @AfterEach
    void cleanupSeededRows() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (UUID scopeId : seededScopeIds) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM scope_tag WHERE scope_id = ?")) {
                    ps.setObject(1, scopeId);
                    ps.executeUpdate();
                }
            }
            for (UUID postId : seededPostIds) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM post WHERE id = ?")) {
                    ps.setObject(1, postId);
                    ps.executeUpdate();
                }
            }
            for (UUID sourceId : seededSourceIds) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM source WHERE id = ?")) {
                    ps.setObject(1, sourceId);
                    ps.executeUpdate();
                }
            }
            // Retire any v1 leftovers this test seeded that the migration did
            // not consume (the loud-failure rollbacks) — tops excluded: the
            // promoted 'news' top is the seed's, not the test's.
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM tag WHERE node_kind = 'leaf' AND parent_name IS NULL "
                        + "AND name = ANY(?)")) {
                List<String> v1 = new ArrayList<>(RETIRED_V1);
                v1.addAll(List.of("nostr", "video"));
                ps.setArray(1, conn.createArrayOf("TEXT", v1.toArray(new String[0])));
                ps.executeUpdate();
            }
        }
        seededPostIds.clear();
        seededSourceIds.clear();
        seededScopeIds.clear();
    }

    // ---------- the converted reproduction (acceptance 1) ----------

    @Test
    void legacyVocabularyIsMappedOntoTreeLeaves() throws Exception {
        seedFlatVocabulary();
        UUID postA = seedPost("mig-a", List.of("claude", "ai", "development"));
        UUID postB = seedPost("mig-b", List.of("news", "glmai"));
        UUID postC = seedPost("mig-c", List.of("security", "java", "research"));
        UUID source = seedSource("mig-src", List.of("development", "security"));
        UUID scopeId = UUID.randomUUID();
        seedFollow(scopeId, "ai");
        seedFollow(scopeId, "claude");
        seedFollow(scopeId, "kimiai");

        runMigration();

        // Every frozen leaf + the seven residuals present, parented to its
        // recorded top, bootstrap origin, display = name; fallback marks only
        // on world + the residuals.
        for (Map.Entry<String, List<String>> top : TOPS_TO_LEAVES.entrySet()) {
            for (String leaf : top.getValue()) {
                assertTagRow(leaf, "leaf", top.getKey(), "bootstrap", leaf,
                    FALLBACK_LEAVES.contains(leaf));
            }
            assertTagRow(top.getKey(), "top", null, "bootstrap", top.getKey(), false);
        }

        // post.tags rewritten deterministically; entity names additionally in
        // tag_candidates (claude, glmai yes; development/security/java no).
        assertPostState(postA, Set.of("ai", "software-development"), Set.of("claude"));
        assertPostState(postB, Set.of("world", "misc"), Set.of("glmai"));
        assertPostState(postC, Set.of("cybersecurity", "software-development", "research"),
            Set.of());

        // source.bootstrap_tags rewritten via the same lookup.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT bootstrap_tags FROM source WHERE id = ?")) {
            ps.setObject(1, source);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(Set.of("software-development", "cybersecurity"),
                    new LinkedHashSet<>(Arrays.asList((String[]) rs.getArray(1).getArray())),
                    "bootstrap_tags must be mapped leaves");
            }
        }

        // scope_tag remap: the ai follow keeps resolving, the claude follow is
        // re-pointed at ai, the kimiai follow at misc, zero orphans, and the
        // superseded v1 rows are retired.
        try (Connection conn = dataSource.getConnection()) {
            assertEquals(Set.of("ai", "misc"), followedTagNames(conn, scopeId),
                "follows must resolve to the mapped nodes");
            try (Statement st = conn.createStatement()) {
                try (ResultSet rs = st.executeQuery(
                        "SELECT count(*) FROM scope_tag st LEFT JOIN tag t ON t.id = st.tag_id "
                            + "WHERE t.id IS NULL")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "no scope_tag row may reference a retired row");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM tag WHERE node_kind = 'leaf' AND name = ANY(?)")) {
                ps.setArray(1, conn.createArrayOf("TEXT", RETIRED_V1.toArray(new String[0])));
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1),
                        "superseded v1 rows must be retired (the promoted 'news' top stays)");
                }
            }
            // Identity names stay as seeded leaves.
            assertTagRow("ai", "leaf", "tech", "bootstrap", "ai", false);
            assertTagRow("crypto", "leaf", "business", "bootstrap", "crypto", false);
            assertTagRow("research", "leaf", "science", "bootstrap", "research", false);
        }
    }

    // ---------- loud failures (acceptance 1 tail) ----------

    @Test
    void unmappedTagRowFailsMigrationLoudly() throws Exception {
        insertV1TagRow("nostr");
        SQLException ex = assertThrows(SQLException.class, this::runMigration);
        assertTrue(ex.getMessage().contains("nostr"),
            "the failure must name the unmapped tag; got: " + ex.getMessage());
    }

    @Test
    void unmappedArrayElementFailsMigrationLoudly() throws Exception {
        seedPost("mig-video", List.of("video"));
        SQLException ex = assertThrows(SQLException.class, this::runMigration);
        assertTrue(ex.getMessage().contains("video"),
            "the failure must name the unmapped array element; got: " + ex.getMessage());
    }

    // ---------- stored form + collision safety (acceptance 2) ----------

    @Test
    void everySeededNameIsInStoredForm() throws Exception {
        runMigration();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT name FROM tag WHERE source_origin = 'bootstrap'");
             ResultSet rs = ps.executeQuery()) {
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            for (String name : names) {
                assertEquals(name, TagNormalizer.normalize(name),
                    "seeded name must round-trip the normalizer (stored form)");
            }
        }
    }

    @Test
    void collidingOperatorRowSurvivesTheSeedVerbaitm() throws Exception {
        // Hostile precondition: an operator 'user' row colliding with a seed
        // leaf name. The seed's ON CONFLICT (name) DO NOTHING must leave it
        // byte-identical.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement del = conn.prepareStatement("DELETE FROM tag WHERE name = 'style'");
             PreparedStatement ins = conn.prepareStatement(
                 "INSERT INTO tag (name, display, source_origin) VALUES ('style', 'OPERATOR STYLE', 'user')")) {
            assertEquals(1, del.executeUpdate(), "the seeded style row must exist");
            assertEquals(1, ins.executeUpdate());
        }
        try {
            runMigration();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT display, source_origin, node_kind, parent_name FROM tag WHERE name = 'style'");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("OPERATOR STYLE", rs.getString(1), "operator display survives verbatim");
                assertEquals("user", rs.getString(2), "operator origin survives verbatim");
                assertEquals("leaf", rs.getString(3), "the row stays a plain leaf");
                assertEquals(null, rs.getString(4), "no parent is assigned to the operator row");
            }
        } finally {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement del = conn.prepareStatement("DELETE FROM tag WHERE name = 'style'");
                 PreparedStatement ins = conn.prepareStatement(
                     "INSERT INTO tag (name, display, source_origin, node_kind, parent_name, fallback) "
                         + "VALUES ('style', 'style', 'bootstrap', 'leaf', 'fashion', FALSE)")) {
                assertEquals(1, del.executeUpdate());
                assertEquals(1, ins.executeUpdate());
            }
        }
    }

    @Test
    void identityLeafCollisionIsReparentedToItsTop() throws Exception {
        // Hostile precondition: the operator DB's flat profile already holds
        // the identity rows as parentless leaves; the seed's DO NOTHING keeps
        // them verbatim, so the reparent UPDATE must attach them to their tops.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement del = conn.prepareStatement(
                 "DELETE FROM tag WHERE name IN ('ai', 'crypto', 'research')");
             PreparedStatement ins = conn.prepareStatement(
                 "INSERT INTO tag (name, display, source_origin) VALUES (?, ?, 'bootstrap')")) {
            assertEquals(3, del.executeUpdate(), "the seeded identity rows must exist");
            ins.setString(1, "ai");
            ins.setString(2, "AI FLAT");
            ins.addBatch();
            ins.setString(1, "crypto");
            ins.setString(2, "CRYPTO FLAT");
            ins.addBatch();
            ins.setString(1, "research");
            ins.setString(2, "RESEARCH FLAT");
            ins.addBatch();
            ins.executeBatch();
        }
        try {
            runMigration();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT node_kind, parent_name, display, source_origin FROM tag "
                         + "WHERE name IN ('ai', 'crypto', 'research') ORDER BY name");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("leaf", rs.getString(1), "ai stays a leaf");
                assertEquals("tech", rs.getString(2), "ai is re-parented under tech");
                assertEquals("AI FLAT", rs.getString(3), "flat display survives verbatim");
                assertEquals("bootstrap", rs.getString(4), "flat origin survives verbatim");
                assertTrue(rs.next());
                assertEquals("leaf", rs.getString(1));
                assertEquals("business", rs.getString(2), "crypto is re-parented under business");
                assertEquals("CRYPTO FLAT", rs.getString(3));
                assertEquals("bootstrap", rs.getString(4));
                assertTrue(rs.next());
                assertEquals("leaf", rs.getString(1));
                assertEquals("science", rs.getString(2), "research is re-parented under science");
                assertEquals("RESEARCH FLAT", rs.getString(3));
                assertEquals("bootstrap", rs.getString(4));
                assertTrue(!rs.next(), "no further identity rows");
            }
        } finally {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement del = conn.prepareStatement(
                     "DELETE FROM tag WHERE name IN ('ai', 'crypto', 'research')");
                 PreparedStatement ins = conn.prepareStatement(
                     "INSERT INTO tag (name, display, source_origin, node_kind, parent_name, fallback) "
                         + "VALUES (?, ?, 'bootstrap', 'leaf', ?, FALSE)")) {
                assertEquals(3, del.executeUpdate(), "the identity rows must be restorable");
                restoreIdentityRow(ins, "ai", "tech");
                restoreIdentityRow(ins, "crypto", "business");
                restoreIdentityRow(ins, "research", "science");
            }
        }
    }

    private void restoreIdentityRow(PreparedStatement ins, String name, String parent)
            throws SQLException {
        ins.setString(1, name);
        ins.setString(2, name);
        ins.setString(3, parent);
        ins.executeUpdate();
    }

    // ---------- fallback path stores leaves (acceptance 4) ----------

    @Test
    void taggerFallbackStoresLeavesAfterMigration() throws Exception {
        UUID source = seedSource("mig-fb-src", List.of("security"));
        runMigration();

        // The migrated bootstrap_tags drive the three-surface fallback:
        // both LLM attempts fail -> BOOTSTRAP outcome writes bootstrap_tags
        // into post.tags unvalidated; those must now be tree leaves.
        QuarkusMock.installMockForType(
            Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        ((app.zcat.infochat.collector.eval.testing.StubLlmProvider) llmProvider).reset();
        ((app.zcat.infochat.collector.eval.testing.StubLlmProvider) llmProvider).failAll();
        tagVocabulary.load();

        List<String> migratedBootstrap = bootstrapTagsOf(source);
        UUID post = seedPost("mig-fb-post", List.of());
        taggerWorker.processOne(new TaggerWorker.PostRow(
            post, FETCHED_AT, "fallback title", "fallback body", migratedBootstrap));

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT tags FROM post WHERE id = ?")) {
            ps.setObject(1, post);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                String[] stored = (String[]) rs.getArray(1).getArray();
                assertEquals(List.of("cybersecurity"), Arrays.asList(stored),
                    "the fallback must store the migrated bootstrap leaf");
                for (String tag : stored) {
                    assertTrue(isLeaf(conn, tag), "stored fallback tag must be a tree leaf");
                }
            }
        }
    }

    // ---------- helpers ----------

    /** Executes V84's own statements (minus the boot-applied ALTER) in one transaction, read from the classpath resource. */
    private void runMigration() throws Exception {
        String sql;
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("db/migration/V84__tag_tree_seed_and_migration.sql")) {
            assertNotNull(in, "V84 must be on the classpath");
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String stripped = sql.replaceAll("(?s)ALTER TABLE tag ADD COLUMN fallback[^;]*;", "");
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute(stripped);
            }
            conn.commit();
        }
    }

    private void seedFlatVocabulary() throws Exception {
        // The flat operator profile's mapped names, as the DB held them.
        List<String> names = List.of("ai", "development", "claude", "security", "java",
            "glmai", "kimiai", "crypto", "zcash", "quarkus", "research", "news", "google",
            "openai", "anthropic", "qwen", "spring-io", "langchain4j", "oracle", "malware",
            "privacy", "comfyui");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO tag (name, display, source_origin) VALUES (?, ?, 'bootstrap') "
                     + "ON CONFLICT (name) DO NOTHING")) {
            for (String name : names) {
                ps.setString(1, name);
                ps.setString(2, name);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertV1TagRow(String name) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO tag (name, display, source_origin) VALUES (?, ?, 'bootstrap') "
                     + "ON CONFLICT (name) DO NOTHING")) {
            ps.setString(1, name);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    private UUID seedSource(String slug, List<String> bootstrapTags) throws Exception {
        UUID id;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', ?) RETURNING id")) {
            ps.setString(1, "https://mig.example.test/" + slug + "/feed.xml");
            ps.setString(2, "Migration IT " + slug);
            ps.setArray(3, conn.createArrayOf("TEXT", bootstrapTags.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                id = (UUID) rs.getObject(1);
            }
        }
        seededSourceIds.add(id);
        return id;
    }

    private UUID seedPost(String slug, List<String> tags) throws Exception {
        UUID sourceId = seedSource(slug, List.of());
        UUID postId;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status,"
                     + "  stage1_done, stage2_done, tagger_done, embedding_done,"
                     + "  stage1_flagged, stage2_failed, tagger_fallback, tags"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'RAW',"
                     + "  TRUE, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, ?"
                     + ") RETURNING id")) {
            ps.setString(1, "mig-" + slug + "-uid");
            ps.setObject(2, sourceId);
            ps.setString(3, "mig-" + slug + "-upstream");
            ps.setString(4, "Migration IT post " + slug);
            ps.setString(5, "body");
            ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
            ps.setArray(7, conn.createArrayOf("TEXT", tags.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                postId = (UUID) rs.getObject(1);
            }
        }
        seededPostIds.add(postId);
        return postId;
    }

    private void seedFollow(UUID scopeId, String tagName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO scope_tag (scope_kind, scope_id, tag_id) "
                     + "SELECT 'dm', ?, id FROM tag WHERE name = ? "
                     + "ON CONFLICT (scope_kind, scope_id, tag_id) DO NOTHING")) {
            ps.setObject(1, scopeId);
            ps.setString(2, tagName);
            ps.executeUpdate();
        }
        seededScopeIds.add(scopeId);
    }

    private void assertTagRow(String name, String nodeKind, String parent, String origin,
                              String display, boolean fallback) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT node_kind, parent_name, source_origin, display, fallback FROM tag WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected tag row: " + name);
                assertEquals(nodeKind, rs.getString(1), name + " node_kind");
                assertEquals(parent, rs.getString(2), name + " parent_name");
                assertEquals(origin, rs.getString(3), name + " source_origin");
                assertEquals(display, rs.getString(4), name + " display");
                assertEquals(fallback, rs.getBoolean(5), name + " fallback");
            }
        }
    }

    private void assertPostState(UUID postId, Set<String> expectedTags,
                                 Set<String> expectedCandidates) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT tags, tag_candidates FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expectedTags,
                    new LinkedHashSet<>(Arrays.asList((String[]) rs.getArray(1).getArray())),
                    "post.tags must be mapped leaves");
                assertEquals(expectedCandidates,
                    new LinkedHashSet<>(Arrays.asList((String[]) rs.getArray(2).getArray())),
                    "tag_candidates must carry the mapped-away entity names");
            }
        }
    }

    private List<String> bootstrapTagsOf(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT bootstrap_tags FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return Arrays.asList((String[]) rs.getArray(1).getArray());
            }
        }
    }

    private boolean isLeaf(Connection conn, String name) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT node_kind FROM tag WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "leaf".equals(rs.getString(1));
            }
        }
    }

    private Set<String> followedTagNames(Connection conn, UUID scopeId) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT t.name FROM scope_tag st JOIN tag t ON t.id = st.tag_id WHERE st.scope_id = ?")) {
            ps.setObject(1, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }
}
