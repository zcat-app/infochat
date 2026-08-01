package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.digest.DigestCategorizer.CategorySection;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the deterministic tag arithmetic of {@link DigestCategorizer}
 * (D62): highest-count qualifying-tag assignment, alphabetical tie-break,
 * post-assignment fold-back, and the Other bucket — plus the M1-721 section
 * cap layered on top of the ordered result and the M1-727 personal routing
 * (all-personal clusters to Other, excluded from the qualifying-tag count,
 * sorted last within Other).
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

    // ----- personal routing (M1-727) ---------------------------------------

    @Test
    void allPersonalClusterRoutesToOtherDespiteQualifyingTag() {
        // security qualifies (3 real clusters carry it); the personal
        // cluster carries it too but routes to Other regardless — its tag
        // is not its destination.
        Cluster personal = personalCluster("cat-pic", "security");
        Cluster sec1 = cluster("sec1", "security");
        Cluster sec2 = cluster("sec2", "security");
        Cluster sec3 = cluster("sec3", "security");

        List<CategorySection> sections = categorizer.categorize(
                List.of(personal, sec1, sec2, sec3));

        assertEquals(2, sections.size());
        assertEquals("security", sections.get(0).tag());
        assertEquals(List.of(sec1, sec2, sec3), sections.get(0).clusters(),
                "the personal cluster does not join its tag's category");
        assertNull(sections.get(1).tag());
        assertEquals(List.of(personal), sections.get(1).clusters(),
                "an all-personal cluster lands in Other despite a qualifying tag");
    }

    @Test
    void mixedClusterStaysInItsTopicSection() {
        // The all-versus-any choice: ONE personal post that clustered with
        // real coverage does not make the cluster personal — hiding real
        // news over one stray member is the worse error.
        Cluster mixed = new Cluster("mixed", List.of(
                personalPost("mixed-joke", "security"),
                post("mixed-cve", "security")));
        Cluster sec1 = cluster("sec1", "security");
        Cluster sec2 = cluster("sec2", "security");
        Cluster sec3 = cluster("sec3", "security");

        List<CategorySection> sections = categorizer.categorize(
                List.of(mixed, sec1, sec2, sec3));

        assertEquals(1, sections.size(), "no Other: every cluster is on-topic");
        assertEquals("security", sections.get(0).tag());
        assertEquals(List.of(mixed, sec1, sec2, sec3), sections.get(0).clusters(),
                "a mixed cluster stays in its topic section");
    }

    @Test
    void personalClustersDoNotPromoteTheirSharedTagToACategory() {
        // Three personal clusters all tagged security: excluded from the
        // qualifying-tag count, so security's count is 0 — the cat
        // pictures can neither create a category nor fill one.
        Cluster p1 = personalCluster("p1", "security");
        Cluster p2 = personalCluster("p2", "security");
        Cluster p3 = personalCluster("p3", "security");

        List<CategorySection> sections = categorizer.categorize(List.of(p1, p2, p3));

        assertEquals(1, sections.size(), "no security category is created");
        assertNull(sections.get(0).tag());
        assertEquals(List.of(p1, p2, p3), sections.get(0).clusters());
    }

    @Test
    void realCategoryAtThresholdIsUnaffectedByPersonalClustersCarryingItsTag() {
        // Exactly category-min-clusters real clusters carry security: the
        // category must survive whether or not personal clusters carrying
        // the same tag are present — they can neither hold a dying
        // category open nor inflate one.
        Cluster sec1 = cluster("sec1", "security");
        Cluster sec2 = cluster("sec2", "security");
        Cluster sec3 = cluster("sec3", "security");
        Cluster p1 = personalCluster("p1", "security");
        Cluster p2 = personalCluster("p2", "security");

        List<CategorySection> withoutPersonal = categorizer.categorize(List.of(sec1, sec2, sec3));
        List<CategorySection> withPersonal = categorizer.categorize(
                List.of(p1, sec1, p2, sec2, sec3));

        assertEquals(1, withoutPersonal.size());
        assertEquals(2, withPersonal.size(), "the personal clusters add only Other");
        assertEquals(withoutPersonal.get(0), withPersonal.get(0),
                "the security section is identical with and without personal clusters");
        assertNull(withPersonal.get(1).tag());
        assertEquals(List.of(p1, p2), withPersonal.get(1).clusters());
    }

    @Test
    void personalClustersSortLastWithinOther() {
        // Other competes for budget like any section: the budget must cut
        // cat pictures before it cuts genuinely-uncategorizable news, so
        // personal clusters sort after non-personal ones — any prefix
        // budget drops personal clusters first. Relative order inside
        // each group is the input (digest) order.
        Cluster untagged1 = cluster("untagged1");
        Cluster p1 = personalCluster("p1", "cats");
        Cluster rare = cluster("rare", "rare-tag");
        Cluster p2 = personalCluster("p2", "dogs");
        Cluster untagged2 = cluster("untagged2");
        Cluster p3 = personalCluster("p3", "cats");

        List<CategorySection> sections = categorizer.categorize(
                List.of(untagged1, p1, rare, p2, untagged2, p3));

        assertEquals(1, sections.size(), "everything lands in Other");
        assertNull(sections.get(0).tag());
        assertEquals(List.of(untagged1, rare, untagged2, p1, p2, p3),
                sections.get(0).clusters(),
                "non-personal first in input order, personal after in input order");
        // The budget pin: every prefix that drops clusters from the tail
        // (the M1-721/M1-732 head-of-section render shapes) drops ONLY
        // personal clusters until none remain.
        List<Cluster> other = sections.get(0).clusters();
        assertEquals(List.of(untagged1, rare, untagged2),
                other.subList(0, other.size() - 3),
                "a budget-constrained Other renders the real clusters and drops the personal ones");
    }

    @Test
    void nonPersonalClassificationsAreRoutingTransparent() {
        // The byte-identical pin at categorizer level: the whole existing
        // suite runs {unknown} fixtures; swapping in the other five
        // non-personal labels must not move a single cluster.
        Cluster dual = new Cluster("dual", List.of(
                postWithClassification("dual-a", List.of("factual"), "ai"),
                postWithClassification("dual-b", List.of("opinion", "technical"), "security")));
        Cluster ai1 = clusterWithClassification("ai1", List.of("urgent"), "ai");
        Cluster ai2 = clusterWithClassification("ai2", List.of("ongoing"), "ai");
        Cluster ai3 = clusterWithClassification("ai3", List.of("factual", "technical", "urgent"), "ai");
        Cluster ai4 = clusterWithClassification("ai4", List.of("opinion"), "ai");
        Cluster sec1 = clusterWithClassification("sec1", List.of("technical"), "security");
        Cluster sec2 = clusterWithClassification("sec2", List.of("factual"), "security");
        Cluster sec3 = clusterWithClassification("sec3", List.of("unknown"), "security");

        List<CategorySection> sections = categorizer.categorize(
                List.of(dual, ai1, ai2, ai3, ai4, sec1, sec2, sec3));

        assertEquals(2, sections.size(), "two sections, no Other");
        assertEquals("ai", sections.get(0).tag());
        assertEquals(List.of(dual, ai1, ai2, ai3, ai4), sections.get(0).clusters());
        assertEquals("security", sections.get(1).tag());
        assertEquals(List.of(sec1, sec2, sec3), sections.get(1).clusters());
    }

    // ----- section cap (M1-721) ---------------------------------------------

    @Test
    void capKeepsTheLargestSectionsInOrderAndDropsTheTail() {
        DigestCategorizer capped = newCategorizer(3, 8);
        List<CategorySection> sections = capped.categorize(twelveSizedCategories());

        assertEquals(12, sections.size(), "fixture builds 12 real categories, no Other");

        List<CategorySection> result = capped.capSections(sections);

        assertEquals(8, result.size(), "cap 8 keeps 8 sections");
        assertEquals(List.of("cat00", "cat01", "cat02", "cat03",
                        "cat04", "cat05", "cat06", "cat07"),
                result.stream().map(CategorySection::tag).toList(),
                "the tail (smallest sections) is dropped; surviving order is untouched");
    }

    @Test
    void otherSurvivesTheCapAndDisplacesOneRealCategory() {
        DigestCategorizer capped = newCategorizer(3, 8);
        List<Cluster> clusters = new ArrayList<>(twelveSizedCategories());
        clusters.add(cluster("untagged"));
        List<CategorySection> sections = capped.categorize(clusters);

        assertEquals(13, sections.size(), "12 real categories plus Other");
        assertNull(sections.getLast().tag(), "Other is last in D62 order");

        List<CategorySection> result = capped.capSections(sections);

        // Other is the bucket for clusters with no qualifying tag — the
        // content with no other route to a reader — so a naive tail-drop
        // (which would evict it first) is the bug this pins.
        assertEquals(8, result.size(), "still 8 sections");
        assertEquals(Arrays.asList("cat00", "cat01", "cat02", "cat03",
                        "cat04", "cat05", "cat06", null),
                result.stream().map(CategorySection::tag).toList(),
                "7 real categories plus Other: Other takes the last slot and cat07 yields");
    }

    @Test
    void capDropsNothingWhenSectionCountEqualsTheCap() {
        DigestCategorizer capped = newCategorizer(3, 12);
        List<CategorySection> sections = capped.categorize(twelveSizedCategories());

        assertSame(sections, capped.capSections(sections),
                "exactly at the cap is not over it — the list is returned untouched");
    }

    @Test
    void droppedSectionClustersAreNotRedistributedOrFoldedIntoOther() {
        DigestCategorizer capped = newCategorizer(3, 8);
        List<Cluster> clusters = new ArrayList<>(twelveSizedCategories());
        Cluster untagged = cluster("untagged");
        clusters.add(untagged);
        List<CategorySection> sections = capped.categorize(clusters);

        List<CategorySection> result = capped.capSections(sections);

        // Folding dropped clusters into Other would inflate Other exactly
        // when the cap binds — the opposite of what the cap is for.
        assertEquals(List.of(untagged), result.getLast().clusters(),
                "Other still holds only the cluster with no qualifying tag");
        for (int i = 0; i < result.size() - 1; i++) {
            assertEquals(sections.get(i).clusters(), result.get(i).clusters(),
                    "surviving section " + i + " keeps exactly its own clusters");
        }
        int renderedClusters = result.stream().mapToInt(s -> s.clusters().size()).sum();
        assertEquals(14 + 13 + 12 + 11 + 10 + 9 + 8 + 1, renderedClusters,
                "the dropped sections' clusters are rendered nowhere");
    }

    // ----- helpers ----------------------------------------------------------

    /**
     * Twelve qualifying categories {@code cat00}..{@code cat11} sized 14 down
     * to 3 clusters. Distinct sizes make the D62 count-descending order
     * total, so a cap assertion pins WHICH sections survive, not just how
     * many.
     */
    private static List<Cluster> twelveSizedCategories() {
        List<Cluster> clusters = new ArrayList<>();
        for (int categoryIndex = 0; categoryIndex < 12; categoryIndex++) {
            String tag = String.format("cat%02d", categoryIndex);
            for (int i = 0; i < 14 - categoryIndex; i++) {
                clusters.add(cluster(tag + "-" + i, tag));
            }
        }
        return clusters;
    }

    private static DigestCategorizer newCategorizer(int minClusters) {
        DigestCategorizer categorizer = new DigestCategorizer();
        categorizer.categoryMinClusters = minClusters;
        return categorizer;
    }

    private static DigestCategorizer newCategorizer(int minClusters, int maxCategories) {
        DigestCategorizer categorizer = newCategorizer(minClusters);
        categorizer.maxCategories = maxCategories;
        return categorizer;
    }

    /** One single-post cluster carrying the given tags (categories count at the cluster level, so one post per cluster suffices). */
    private static Cluster cluster(String topicId, String... tags) {
        return new Cluster(topicId, List.of(post("post-" + topicId, tags)));
    }

    /** One single-post cluster whose post carries the given classification. */
    private static Cluster clusterWithClassification(String topicId, List<String> classification,
                                                     String... tags) {
        return new Cluster(topicId,
                List.of(postWithClassification("post-" + topicId, classification, tags)));
    }

    /** One single-post all-personal cluster (M1-727). */
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
        return new Post(
                UUID.randomUUID(), uid, UUID.randomUUID(), "TestSrc",
                "title-" + uid, "https://example.com/" + uid, "body",
                Instant.now(), List.of(tags), List.of("unknown"));
    }
}
