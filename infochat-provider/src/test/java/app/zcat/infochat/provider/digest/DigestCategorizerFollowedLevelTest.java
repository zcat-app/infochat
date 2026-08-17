package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.digest.DigestCategorizer.CategorySection;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Tree-aware {@link DigestCategorizer} section-key map semantics (M1-867). */
class DigestCategorizerFollowedLevelTest {

    private final DigestCategorizer categorizer = newCategorizer(3);

    @Test
    void topKeyAggregatesSubtreeClustersAndQualifiesAtRenderedLevel() {
        // ai and cybersecurity each carry only 2 clusters — below the
        // category-min-clusters threshold per leaf — but both roll up to the
        // followed top, where the rendered-level count of 4 qualifies.
        Cluster ai1 = cluster("ai1", "ai");
        Cluster ai2 = cluster("ai2", "ai");
        Cluster cyb1 = cluster("cyb1", "cybersecurity");
        Cluster cyb2 = cluster("cyb2", "cybersecurity");
        Map<String, String> keys = Map.of("ai", "tech", "cybersecurity", "tech");

        List<CategorySection> sections =
                categorizer.categorize(List.of(ai1, ai2, cyb1, cyb2), Set.of(), keys);

        assertEquals(1, sections.size(), "both leaves aggregate into one tech section");
        assertEquals("tech", sections.get(0).tag());
        assertEquals(List.of(ai1, ai2, cyb1, cyb2), sections.get(0).clusters(),
                "the top section qualifies at the rendered level with 4 clusters");
    }

    @Test
    void topKeyAggregationNeverLowersThePerLeafThreshold() {
        // One ai cluster plus one cybersecurity cluster: the aggregated
        // top count of 2 is still below threshold — aggregation must not
        // make a sub-threshold pair qualify.
        Map<String, String> keys = Map.of("ai", "tech", "cybersecurity", "tech");

        List<CategorySection> sections =
                categorizer.categorize(List.of(cluster("ai1", "ai"), cluster("cyb1", "cybersecurity")),
                        Set.of(), keys);

        assertEquals(1, sections.size());
        assertNull(sections.get(0).tag(), "two clusters under one top do not qualify");
    }

    @Test
    void mostSpecificFollowedNodeWinsAndSectionCountTracksFollowedNodes() {
        // Following BOTH the tech top and its ai leaf: ai clusters key to
        // the leaf, the rest of the tech subtree to the top — one section
        // per followed node.
        Cluster ai1 = cluster("ai1", "ai");
        Cluster ai2 = cluster("ai2", "ai");
        Cluster ai3 = cluster("ai3", "ai");
        Cluster cyb1 = cluster("cyb1", "cybersecurity");
        Cluster cyb2 = cluster("cyb2", "cybersecurity");
        Cluster cyb3 = cluster("cyb3", "cybersecurity");
        Map<String, String> keys = Map.of("ai", "ai", "cybersecurity", "tech");

        List<CategorySection> sections = categorizer.categorize(
                List.of(ai1, ai2, ai3, cyb1, cyb2, cyb3), Set.of(), keys);

        assertEquals(2, sections.size(), "one section per followed node");
        assertEquals("ai", sections.get(0).tag(), "count tie broken alphabetically");
        assertEquals(List.of(ai1, ai2, ai3), sections.get(0).clusters());
        assertEquals("tech", sections.get(1).tag());
        assertEquals(List.of(cyb1, cyb2, cyb3), sections.get(1).clusters());
    }

    @Test
    void clustersUnderNoFollowedNodeFoldIntoOther() {
        // football is not under any followed node, so its clusters key to
        // nothing and land in the Other bucket via the existing fold pass.
        Cluster ai1 = cluster("ai1", "ai");
        Cluster ai2 = cluster("ai2", "ai");
        Cluster ai3 = cluster("ai3", "ai");
        Cluster foot1 = cluster("foot1", "football");
        Cluster foot2 = cluster("foot2", "football");
        Cluster foot3 = cluster("foot3", "football");
        Map<String, String> keys = Map.of("ai", "tech");

        List<CategorySection> sections = categorizer.categorize(
                List.of(ai1, ai2, ai3, foot1, foot2, foot3), Set.of(), keys);

        assertEquals(2, sections.size());
        assertEquals("tech", sections.get(0).tag());
        assertNull(sections.get(1).tag(), "unfollowed-leaf clusters fold into Other");
        assertEquals(List.of(foot1, foot2, foot3), sections.get(1).clusters());
    }

    @Test
    void leafFollowMapIsByteIdenticalToIdentityKeying() {
        // A scope following only LEAVES maps every leaf to itself — the
        // tree-aware overload must render exactly the pre-change bytes.
        Cluster ai1 = cluster("ai1", "ai");
        Cluster ai2 = cluster("ai2", "ai");
        Cluster ai3 = cluster("ai3", "ai");
        Cluster foot1 = cluster("foot1", "football");
        Cluster foot2 = cluster("foot2", "football");
        Cluster foot3 = cluster("foot3", "football");
        List<Cluster> clusters = List.of(ai1, ai2, ai3, foot1, foot2, foot3);
        Map<String, String> leafKeys = Map.of("ai", "ai", "football", "football");

        List<CategorySection> identity = categorizer.categorize(clusters, Set.of());
        List<CategorySection> leafFollow =
                categorizer.categorize(clusters, Set.of(), leafKeys);

        assertEquals(identity, leafFollow,
                "a leaf-follow map is identity keying — the D62 bytes are unchanged");
    }

    @Test
    void othersTopSectionStaysDistinctFromTheNullOtherBucket() {
        // Following the 'others' TOP: its non-personal leaf clusters roll up
        // to a real 'others' section; an all-personal cluster routes to the
        // null-tag Other bucket per M1-727, with personal-last inside Other.
        Cluster p1 = cluster("p1", "personal");
        Cluster p2 = cluster("p2", "personal");
        Cluster p3 = cluster("p3", "personal");
        Cluster o1 = cluster("o1", "opinion");
        Cluster o2 = cluster("o2", "opinion");
        Cluster o3 = cluster("o3", "opinion");
        Cluster cat1 = personalCluster("cat1", "personal");
        Cluster cat2 = personalCluster("cat2", "misc");
        Cluster untagged = cluster("untagged");
        Map<String, String> keys = Map.of("personal", "others", "opinion", "others",
                "misc", "others");

        List<CategorySection> sections = categorizer.categorize(
                List.of(p1, p2, p3, o1, o2, o3, cat1, cat2, untagged),
                Set.of(), keys);

        assertEquals(2, sections.size());
        assertEquals("others", sections.get(0).tag(),
                "followed-others leaf clusters render a real section");
        assertEquals(List.of(p1, p2, p3, o1, o2, o3), sections.get(0).clusters());
        assertNull(sections.get(1).tag(),
                "the all-personal and null-tag clusters land in the D62 Other bucket");
        assertEquals(List.of(untagged, cat1, cat2), sections.get(1).clusters(),
                "non-personal first, personal-last within Other");
    }

    // ----- helpers ----------------------------------------------------------

    private static DigestCategorizer newCategorizer(int minClusters) {
        DigestCategorizer categorizer = new DigestCategorizer();
        categorizer.categoryMinClusters = minClusters;
        return categorizer;
    }

    private static Cluster cluster(String topicId, String... tags) {
        return new Cluster(topicId, List.of(post("post-" + topicId, tags)));
    }

    private static Cluster personalCluster(String topicId, String... tags) {
        return new Cluster(topicId, List.of(personalPost("post-" + topicId, tags)));
    }

    private static Post personalPost(String uid, String... tags) {
        return postWithClassification(uid, List.of("personal"), tags);
    }

    private static Post postWithClassification(String uid, List<String> classification,
                                               String... tags) {
        return new Post(
                UUID.randomUUID(), uid, UUID.randomUUID(), "TestSrc",
                "title-" + uid, "https://example.com/" + uid, "body",
                Instant.now(), List.of(tags), classification);
    }

    private static Post post(String uid, String... tags) {
        return postWithClassification(uid, List.of("unknown"), tags);
    }
}
