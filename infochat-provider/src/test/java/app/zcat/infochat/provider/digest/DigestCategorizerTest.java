package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.digest.DigestCategorizer.CategorySection;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the deterministic tag arithmetic of {@link DigestCategorizer}
 * (D62): highest-count qualifying-tag assignment, alphabetical tie-break,
 * post-assignment fold-back, and the Other bucket.
 */
class DigestCategorizerTest {

    private final DigestCategorizer categorizer = newCategorizer(3);

    @Test
    void assignsClusterToHighestCountQualifyingTag() {
        // ai is carried by 5 clusters, security by 4; the dual-tagged cluster
        // (tag-set = union across its two member posts) goes to ai.
        Cluster dual = new Cluster("dual", List.of(post("dual-a", "ai"), post("dual-b", "security")));
        Cluster ai1 = cluster("ai1", "ai");
        Cluster ai2 = cluster("ai2", "ai");
        Cluster ai3 = cluster("ai3", "ai");
        Cluster ai4 = cluster("ai4", "ai");
        Cluster sec1 = cluster("sec1", "security");
        Cluster sec2 = cluster("sec2", "security");
        Cluster sec3 = cluster("sec3", "security");

        List<CategorySection> sections = categorizer.categorize(
                List.of(dual, ai1, ai2, ai3, ai4, sec1, sec2, sec3));

        assertEquals(2, sections.size(), "two sections, no Other");
        assertEquals("ai", sections.get(0).tag(), "largest section first");
        assertTrue(sections.get(0).clusters().contains(dual),
                "dual-tagged cluster assigned to the higher-count tag");
        assertEquals("security", sections.get(1).tag());
        assertEquals(List.of(sec1, sec2, sec3), sections.get(1).clusters(),
                "security keeps only its single-tag clusters, in digest order");
    }

    @Test
    void tieBreaksAlphabetically() {
        // alpha and beta are both carried by 4 clusters — the dual-tagged
        // cluster goes to alpha (alphabetically first on equal counts),
        // regardless of the order its posts list the tags in.
        Cluster dual = cluster("dual", "beta", "alpha");
        Cluster a1 = cluster("a1", "alpha");
        Cluster a2 = cluster("a2", "alpha");
        Cluster a3 = cluster("a3", "alpha");
        Cluster b1 = cluster("b1", "beta");
        Cluster b2 = cluster("b2", "beta");
        Cluster b3 = cluster("b3", "beta");

        List<CategorySection> sections = categorizer.categorize(
                List.of(dual, a1, a2, a3, b1, b2, b3));

        assertEquals(2, sections.size());
        assertEquals("alpha", sections.get(0).tag());
        assertTrue(sections.get(0).clusters().contains(dual),
                "equal-count tie broken alphabetically → alpha");
        assertEquals("beta", sections.get(1).tag());
        assertEquals(List.of(b1, b2, b3), sections.get(1).clusters());
    }

    @Test
    void foldsPostAssignmentUnderThresholdIntoOther() {
        // small qualifies (4 clusters carry it) but loses its three shared
        // clusters to big (count 8 > 4), ending with 1 assigned < 3 —
        // the near-empty section folds into Other.
        Cluster big1 = cluster("big1", "big");
        Cluster big2 = cluster("big2", "big");
        Cluster big3 = cluster("big3", "big");
        Cluster big4 = cluster("big4", "big");
        Cluster big5 = cluster("big5", "big");
        Cluster shared1 = cluster("shared1", "big", "small");
        Cluster shared2 = cluster("shared2", "big", "small");
        Cluster shared3 = cluster("shared3", "big", "small");
        Cluster small1 = cluster("small1", "small");

        List<CategorySection> sections = categorizer.categorize(
                List.of(big1, big2, big3, big4, big5, shared1, shared2, shared3, small1));

        assertEquals(2, sections.size(), "small's section folded away");
        assertEquals("big", sections.get(0).tag());
        assertEquals(8, sections.get(0).clusters().size());
        assertNull(sections.get(1).tag(), "folded clusters land in Other");
        assertEquals(List.of(small1), sections.get(1).clusters());
    }

    @Test
    void untaggedAndBelowThresholdGoToOther() {
        Cluster untagged = cluster("untagged");
        Cluster rare = cluster("rare", "rare-tag");
        Cluster c1 = cluster("c1", "common");
        Cluster c2 = cluster("c2", "common");
        Cluster c3 = cluster("c3", "common");

        List<CategorySection> sections = categorizer.categorize(
                List.of(untagged, rare, c1, c2, c3));

        assertEquals(2, sections.size());
        assertEquals("common", sections.get(0).tag());
        assertEquals(List.of(c1, c2, c3), sections.get(0).clusters());
        assertNull(sections.get(1).tag(), "Other renders last");
        assertEquals(List.of(untagged, rare), sections.get(1).clusters(),
                "Other preserves digest order across untagged and below-threshold clusters");
    }

    // ----- helpers ----------------------------------------------------------

    private static DigestCategorizer newCategorizer(int minClusters) {
        DigestCategorizer categorizer = new DigestCategorizer();
        categorizer.categoryMinClusters = minClusters;
        return categorizer;
    }

    /** One single-post cluster carrying the given tags (categories count at the cluster level, so one post per cluster suffices). */
    private static Cluster cluster(String topicId, String... tags) {
        return new Cluster(topicId, List.of(post("post-" + topicId, tags)));
    }

    private static Post post(String uid, String... tags) {
        return new Post(
                UUID.randomUUID(), uid, UUID.randomUUID(), "TestSrc",
                "title-" + uid, "https://example.com/" + uid, "body",
                Instant.now(), List.of(tags), List.of("unknown"));
    }
}
