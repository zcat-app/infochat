package app.zcat.infochat.collector.eval.tagger;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.util.TagNormalizer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the v2 tag-tree mechanism (M1-865): one-branch resolution by the fixed top-priority order, News-last + identity passthrough, the leaf-only load, and the V82 tree columns — through the real DB load path, no LLM (analysis P8, D19). Tree fixtures are real tag rows, snapshot-restored per test because the table is shared across the Quarkus test instance. */
@QuarkusTest
class TagTreeResolutionTest {

    /** Every tag name this suite inserts or flips; snapshot-restored per test. */
    private static final List<String> TOUCHED = List.of(
        "sport", "health", "fashion", "culture", "science", "tech", "business", "news", "others",
        "football", "esports", "gaming", "research", "ai", "europe", "world", "misc",
        "v81-plain-leaf", "v81-top", "v81-child");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    TagVocabulary tagVocabulary;

    @Inject
    TagTreeResolver tagTreeResolver;

    /** Pre-test {@code [node_kind, parent_name]} per touched name; absent = the row did not exist. */
    private final Map<String, String[]> snapshot = new HashMap<>();

    @BeforeEach
    void snapshotTouchedRows() throws Exception {
        snapshot.clear();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT name, node_kind, parent_name FROM tag WHERE name = ANY(?)")) {
            ps.setArray(1, conn.createArrayOf("text", TOUCHED.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    snapshot.put(rs.getString(1), new String[]{rs.getString(2), rs.getString(3)});
                }
            }
        }
    }

    @AfterEach
    void restoreTouchedRows() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            String[] touched = TOUCHED.toArray(new String[0]);
            // Detach parent links first: deleting or re-flipping a top while
            // a touched leaf still references it violates the self-FK.
            try (PreparedStatement detach = conn.prepareStatement(
                    "UPDATE tag SET parent_name = NULL WHERE name = ANY(?)")) {
                detach.setArray(1, conn.createArrayOf("text", touched));
                detach.executeUpdate();
            }
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM tag WHERE name = ANY(?) AND NOT (name = ANY(?))")) {
                del.setArray(1, conn.createArrayOf("text", touched));
                del.setArray(2, conn.createArrayOf("text", snapshot.keySet().toArray(new String[0])));
                del.executeUpdate();
            }
            try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE tag SET node_kind = ?, parent_name = ? WHERE name = ?")) {
                for (Map.Entry<String, String[]> e : snapshot.entrySet()) {
                    upd.setString(1, e.getValue()[0]);
                    upd.setString(2, e.getValue()[1]);
                    upd.setString(3, e.getKey());
                    upd.addBatch();
                }
                upd.executeBatch();
            }
        }
        // Re-sync the shared bean so no tree shape lingers for later classes.
        tagVocabulary.load();
    }

    // ---------- the converted reproduction (acceptance 1) ----------

    @Test
    void crossTopProposalResolvesToASingleBranch() throws Exception {
        // The showcase's constant cross-top proposals (football+europe,
        // esports+gaming, ai+research): exactly ONE stored leaf, the branch
        // whose top ranks highest in the fixed priority order.
        seedShowcaseTree();
        tagVocabulary.load();

        assertResolution(List.of("football"), List.of("europe"), List.of("football", "europe"));
        // Science outranks Tech, so the research branch wins over ai.
        assertResolution(List.of("research"), List.of("ai"), List.of("ai", "research"));
        assertResolution(List.of("esports"), List.of("gaming"), List.of("esports", "gaming"));
    }

    @Test
    void newsIsLowestPriorityFallback() throws Exception {
        // A news report that fits nothing else files under News->continent;
        // News mixed with any other top never wins.
        seedShowcaseTree();
        tagVocabulary.load();

        assertResolution(List.of("europe"), List.of("world"), List.of("europe", "world"));
        // Emission order is the within-top tiebreak, so the reversed
        // proposal resolves to the other News leaf.
        assertResolution(List.of("world"), List.of("europe"), List.of("world", "europe"));
        assertResolution(List.of("ai"), List.of("europe"), List.of("europe", "ai"));
    }

    @Test
    void parentlessVocabularyResolvesToItself() throws Exception {
        // P6 / acceptance 3: until M1-866 seeds the tree, every row is a
        // parentless leaf and resolution is the identity — stored sets stay
        // byte-identical to today. Feeds the CURRENT vocabulary as loaded.
        tagVocabulary.load();
        List<String> current = List.copyOf(tagVocabulary.names());
        assertTrue(current.size() > 1,
            "fixture invalid: an identity assertion over fewer than two names cannot fail");

        TagTreeResolver.Resolution all = tagTreeResolver.resolve(current, tagVocabulary.tree());
        assertEquals(current, all.stored(), "a parentless proposal set resolves to itself");
        assertTrue(all.losers().isEmpty(), "identity passthrough has no losers");

        TagTreeResolver.Resolution single =
            tagTreeResolver.resolve(List.of(current.get(0)), tagVocabulary.tree());
        assertEquals(List.of(current.get(0)), single.stored(), "a single leaf resolves to itself");
        assertTrue(single.losers().isEmpty());
    }

    @Test
    void deepestLeafWinsWithinOneTop_equalDepthKeepsEmissionOrder() {
        // Depth-generality pin (acceptance 1): hand-built depth-3 tree, no DB.
        Map<String, TagVocabulary.TagNode> tree = Map.of(
            "sport", new TagVocabulary.TagNode(true, null),
            "ball-games", new TagVocabulary.TagNode(false, "sport"),
            "football", new TagVocabulary.TagNode(false, "ball-games"),
            "tennis", new TagVocabulary.TagNode(false, "sport"),
            "hockey", new TagVocabulary.TagNode(false, "sport"));

        TagTreeResolver.Resolution deep =
            tagTreeResolver.resolve(List.of("tennis", "football"), tree);
        assertEquals(List.of("football"), deep.stored(), "the deeper leaf wins within one top");
        assertEquals(List.of("tennis"), deep.losers());

        TagTreeResolver.Resolution tie =
            tagTreeResolver.resolve(List.of("tennis", "hockey"), tree);
        assertEquals(List.of("tennis"), tie.stored(), "an equal-depth tie keeps emission order");
        assertEquals(List.of("hockey"), tie.losers());
    }

    @Test
    void unlistedTopRanksBelowNews() throws Exception {
        // The Others top is deliberately outside the fixed priority order
        // (bounded dump category); it ranks below even News.
        upsertTag("others", "top", null);
        upsertTag("news", "top", null);
        upsertTag("misc", "leaf", "others");
        upsertTag("europe", "leaf", "news");
        tagVocabulary.load();

        assertResolution(List.of("europe"), List.of("misc"), List.of("misc", "europe"));
        assertResolution(List.of("misc"), List.of(), List.of("misc"));
    }

    // ---------- leaf-only load (acceptance 4) ----------

    @Test
    void leafOnlyLoadExcludesTopsAndKeepsQueryOrder() throws Exception {
        upsertTag("sport", "top", null);
        upsertTag("football", "leaf", "sport");
        tagVocabulary.load();

        assertFalse(tagVocabulary.names().contains("sport"), "tops must never enter the vocabulary");
        assertFalse(tagVocabulary.contains("sport"), "a top name is not a valid proposal");
        assertTrue(tagVocabulary.contains("football"), "the leaf under the top stays loadable");

        assertEquals(leafNamesInQueryOrder(), List.copyOf(tagVocabulary.names()),
            "names() must iterate the leaf-filtered ORDER BY name order (P7)");
    }

    // ---------- V82 schema pin (acceptance 8) ----------

    @Test
    void v82TreeColumnsDefaultLeafAndPlainInsertStillWorks() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // A pre-V82-shaped INSERT (no tree columns) still succeeds and
            // lands as a parentless leaf — the M1-866-intermediate semantics.
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tag (name, display) VALUES ('v81-plain-leaf', 'v81 plain leaf')")) {
                assertEquals(1, ps.executeUpdate(), "a plain INSERT ... (name, display) must succeed");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT node_kind, parent_name FROM tag WHERE name = 'v81-plain-leaf'");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("leaf", rs.getString(1), "node_kind must DEFAULT to 'leaf'");
                assertEquals(null, rs.getString(2), "parent_name must DEFAULT to NULL");
            }

            // The discriminator CHECK closes the two-value set.
            SQLException kind = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO tag (name, display, node_kind) VALUES ('v81-bad', 'v81 bad', 'branch')")) {
                    ps.executeUpdate();
                }
            });
            assertEquals("23514", kind.getSQLState(), "out-of-set node_kind must be a CHECK violation");

            // The parent reference is a real FK to tag(name).
            SQLException fk = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO tag (name, display, parent_name) VALUES ('v81-orphan', 'v81 orphan',"
                            + " 'v81-no-such-parent')")) {
                    ps.executeUpdate();
                }
            });
            assertEquals("23503", fk.getSQLState(), "a dangling parent must be an FK violation");

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tag (name, display, node_kind) VALUES ('v81-top', 'v81 top', 'top')");
                 PreparedStatement ps2 = conn.prepareStatement(
                    "INSERT INTO tag (name, display, parent_name) VALUES ('v81-child', 'v81 child', 'v81-top')")) {
                ps.executeUpdate();
                assertEquals(1, ps2.executeUpdate(), "a leaf referencing an existing top inserts cleanly");
            }
        }
    }

    // ---------- helpers ----------

    private void assertResolution(List<String> expectedStored, List<String> expectedLosers,
                                  List<String> validatedProposals) {
        TagTreeResolver.Resolution r = tagTreeResolver.resolve(validatedProposals, tagVocabulary.tree());
        assertEquals(expectedStored, r.stored(), "stored set");
        assertEquals(expectedLosers, r.losers(), "losers in emission order");
    }

    /** The showcase-shaped tree slice the resolution tests read: five tops, depth-2 leaves. */
    private void seedShowcaseTree() throws Exception {
        for (String top : List.of("sport", "culture", "science", "tech", "news")) {
            upsertTag(top, "top", null);
        }
        upsertTag("football", "leaf", "sport");
        upsertTag("esports", "leaf", "sport");
        upsertTag("gaming", "leaf", "culture");
        upsertTag("research", "leaf", "science");
        upsertTag("ai", "leaf", "tech");
        upsertTag("europe", "leaf", "news");
        upsertTag("world", "leaf", "news");
    }

    private void upsertTag(String name, String nodeKind, String parent) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO tag (name, display, source_origin, node_kind, parent_name) "
                     + "VALUES (?, ?, 'bootstrap', ?, ?) "
                     + "ON CONFLICT (name) DO UPDATE SET node_kind = EXCLUDED.node_kind, "
                     + "parent_name = EXCLUDED.parent_name")) {
            ps.setString(1, name);
            ps.setString(2, name);
            ps.setString(3, nodeKind);
            ps.setString(4, parent);
            ps.executeUpdate();
        }
    }

    /** The leaf-filtered projection the load publishes, read straight from the table. */
    private List<String> leafNamesInQueryOrder() throws Exception {
        Set<String> ordered = new LinkedHashSet<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT name FROM tag WHERE node_kind = 'leaf' ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String normalized = TagNormalizer.normalize(rs.getString(1));
                if (normalized != null) {
                    ordered.add(normalized);
                }
            }
        }
        return List.copyOf(ordered);
    }
}
