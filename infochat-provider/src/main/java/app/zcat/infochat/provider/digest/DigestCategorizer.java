package app.zcat.infochat.provider.digest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import app.zcat.infochat.provider.summary.ClusterProminence;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Groups already-clustered digest posts under deterministic topic categories
 * (D62). Pure tag arithmetic over the cluster set — no LLM call — so the
 * digest's section structure is byte-reproducible for a given cluster set,
 * extending the project's "deterministic retrieval, LLM only for prose"
 * boundary to the digest's structure.
 *
 * <p>The D62 Other bucket additionally receives PERSONAL clusters (M1-727):
 * a cluster whose every member post carries the {@code personal} ingest
 * classification routes to Other regardless of its tags, is excluded from
 * the qualifying-tag counting pass (cat pictures must neither create a
 * category nor keep a dying one open), and sorts after non-personal
 * clusters within Other — the same order {@link ClusterProminence}'s
 * bottom gate re-applies on the scored digest path, so the non-scored
 * render paths ({@code /summary} forms) agree with it.
 */
@ApplicationScoped
public class DigestCategorizer {

    /**
     * The ingest classification label that routes a cluster to Other
     * (M1-727). A literal, like {@code ClusterProminence}'s
     * {@code "urgent"}: the closed set is shared vocabulary enforced by
     * the V73 CHECK and {@code ClassifierWorker.SUBSTANTIVE_LABELS}, not
     * a constant either module can import across the collector/provider
     * boundary.
     */
    static final String PERSONAL = "personal";

    /**
     * A tag qualifies as a category only when carried by at least this many
     * clusters in the digest; post-assignment, a category keeps its section
     * only when at least this many clusters ended up assigned to it.
     */
    @ConfigProperty(name = "infochat.digest.category-min-clusters", defaultValue = "3")
    int categoryMinClusters;

    /**
     * Max category sections a single non-degraded digest renders (M1-721).
     * The section count tracks the tag vocabulary, which grows with every
     * source an operator adds, so without this the digest's length has an
     * unbounded factor.
     *
     * <p>Field-initialized to the same value as {@code defaultValue}, which
     * CDI overwrites at runtime — the {@link DigestRenderer#categoryRollupGenerator}
     * pattern in this package. Unlike {@link #categoryMinClusters}, the Java
     * default of 0 is not a benign starting point here: it would cap every
     * hand-wired plain-JUnit renderer down to its Other bucket. The
     * initializer is what keeps those tests wiring only what they care about.
     */
    @ConfigProperty(name = "infochat.digest.max-categories", defaultValue = "8")
    int maxCategories = 8;

    /**
     * One digest section: the category tag with its assigned clusters in
     * digest order, or the Other bucket when {@code tag} is null.
     */
    public record CategorySection(@Nullable String tag, List<Cluster> clusters) {
        public CategorySection {
            clusters = List.copyOf(clusters);
        }
    }

    /**
     * Assigns each cluster to exactly one category and returns the ordered
     * section list: assigned-cluster count descending, ties alphabetical,
     * Other last (present only when non-empty).
     */
    public List<CategorySection> categorize(List<Cluster> clusters) {
        return categorize(clusters, Set.of(), Map.of());
    }

    /**
     * {@link #categorize(List)} with a set of clusters EXCLUDED from the
     * section lists — the M1-725 digest lead. The D62 arithmetic runs over
     * the FULL cluster set exactly as the 1-arg form: an excluded cluster
     * still counts toward its tags' qualification and is still assigned its
     * best tag, so promoting a cluster to the lead never re-tags the
     * clusters that stay behind. The exclusion bites only after assignment:
     * an excluded cluster is not counted in {@code assignedCounts} and is
     * skipped by the regrouping pass, so it renders nowhere here — and a
     * category the removal drops below {@code categoryMinClusters} folds
     * into Other through the EXISTING second pass, not a new code path.
     *
     * <p>{@code excluded} is an IDENTITY set (the caller builds it with
     * {@code Collections.newSetFromMap(new IdentityHashMap<>())}): this
     * method partitions the very instances passed in, and {@link Cluster}
     * carries no value equality. Only the digest render path
     * ({@code DigestRenderer.renderSections}) passes a non-empty set; the
     * {@code /summary} forms pass an empty {@code excluded} set through the
     * 3-arg overload (via {@code DigestRenderer.renderSummarySections}).
     */
    public List<CategorySection> categorize(List<Cluster> clusters, Set<Cluster> excluded) {
        return categorize(clusters, excluded, Map.of());
    }

    // Tree-aware overload: rolls leaf tags up to followed-node section keys.
    // Empty map = identity keying (today's D62 bytes). Non-empty restricts
    // the qualifying universe to leaves under followed nodes
    // (docs/plan/m1/tick-analysis/tag-tree-taxonomy-v2.md, decision 7).
    public List<CategorySection> categorize(List<Cluster> clusters, Set<Cluster> excluded,
                                            Map<String, String> sectionKeyByLeaf) {
        List<Boolean> clusterPersonal = new ArrayList<>(clusters.size());
        List<Set<String>> clusterKeySets = new ArrayList<>(clusters.size());
        Map<String, Integer> keyClusterCounts = new HashMap<>();
        for (Cluster cluster : clusters) {
            boolean personal = isPersonal(cluster);
            clusterPersonal.add(personal);
            Set<String> keys = new TreeSet<>();
            for (Post post : cluster.posts()) {
                for (String tag : post.tags()) {
                    String key = keyOf(tag, sectionKeyByLeaf);
                    if (key != null) {
                        keys.add(key);
                    }
                }
            }
            clusterKeySets.add(keys);
            if (!personal) {
                for (String key : keys) {
                    keyClusterCounts.merge(key, 1, Integer::sum);
                }
            }
        }

        List<@Nullable String> chosenKeys = new ArrayList<>(clusters.size());
        Map<String, Integer> assignedCounts = new HashMap<>();
        for (int i = 0; i < clusterKeySets.size(); i++) {
            if (clusterPersonal.get(i)) {
                chosenKeys.add(null);
                continue;
            }
            String best = null;
            for (String key : clusterKeySets.get(i)) {
                if (keyClusterCounts.getOrDefault(key, 0) < categoryMinClusters) {
                    continue;
                }
                if (best == null
                        || keyClusterCounts.getOrDefault(key, 0) > keyClusterCounts.getOrDefault(best, 0)) {
                    best = key;
                }
            }
            chosenKeys.add(best);
            if (best != null && !excluded.contains(clusters.get(i))) {
                assignedCounts.merge(best, 1, Integer::sum);
            }
        }

        Map<String, List<Cluster>> sectionsByKey = new HashMap<>();
        List<Cluster> otherNonPersonal = new ArrayList<>();
        List<Cluster> otherPersonal = new ArrayList<>();
        for (int i = 0; i < clusters.size(); i++) {
            if (excluded.contains(clusters.get(i))) {
                continue;
            }
            String key = chosenKeys.get(i);
            if (key == null || assignedCounts.getOrDefault(key, 0) < categoryMinClusters) {
                (clusterPersonal.get(i) ? otherPersonal : otherNonPersonal).add(clusters.get(i));
            } else {
                sectionsByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(clusters.get(i));
            }
        }
        List<Cluster> other = new ArrayList<>(otherNonPersonal.size() + otherPersonal.size());
        other.addAll(otherNonPersonal);
        other.addAll(otherPersonal);

        List<CategorySection> sections = sectionsByKey.entrySet().stream()
                .sorted(Comparator
                        .comparingInt((Map.Entry<String, List<Cluster>> entry) -> -entry.getValue().size())
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> new CategorySection(entry.getKey(), entry.getValue()))
                .collect(Collectors.toCollection(ArrayList::new));
        if (!other.isEmpty()) {
            sections.add(new CategorySection(null, other));
        }
        return List.copyOf(sections);
    }

    /** Empty map = identity (every tag its own key); non-empty = followed-node lookup, null if unmapped. */
    private static @Nullable String keyOf(String tag, Map<String, String> sectionKeyByLeaf) {
        return sectionKeyByLeaf.isEmpty() ? tag : sectionKeyByLeaf.get(tag);
    }

    /**
     * A cluster is personal only when EVERY member post carries the
     * {@code personal} ingest label (M1-727 §The all-versus-any choice).
     * Clusters are connected components of the {@code post_reference}
     * graph, so a personal post that clustered with real coverage was
     * linked to it by shared entities or embedding similarity — evidence
     * it is part of the story, not noise beside it; an any-rule would let
     * one stray member hide a genuine multi-source cluster in Other.
     * Absent data fails the check (a null classification or an empty
     * cluster is NOT personal): demoting real news on missing data is the
     * worse error. {@link ClusterProminence#score} carries the same
     * predicate for its bottom gate — keep the two in step.
     */
    static boolean isPersonal(Cluster cluster) {
        if (cluster.posts().isEmpty()) {
            return false;
        }
        for (Post post : cluster.posts()) {
            if (post.classification() == null || !post.classification().contains(PERSONAL)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Bounds an ordered {@link #categorize} result to {@code maxCategories}
     * sections by dropping from the TAIL (M1-721). Section order is
     * assigned-cluster count descending, so the tail is the smallest
     * sections — the cheapest content to omit.
     *
     * <p><b>Other is never dropped.</b> A naive tail-drop would evict it
     * first (it is always last), yet Other holds exactly the clusters with
     * no qualifying tag — the content with no other route to a reader. When
     * the cap binds and Other is present it takes the last slot and one
     * more real category yields in its place.
     *
     * <p>Clusters in a dropped section are NOT redistributed into the
     * survivors or folded into Other: folding them in would inflate Other
     * precisely when the cap binds, which is the opposite of what the cap
     * is for. The caller accounts for them with one overflow line.
     *
     * <p>Applied ONLY on the digest render path. {@code /summary} is an
     * interactive pull the reader asked for and keeps every section.
     */
    public List<CategorySection> capSections(List<CategorySection> sections) {
        // Floor of one section: DigestWorker joins whatever this returns into
        // the broadcast body, so a mistyped cap of 0 would send an empty
        // message for a slot that had posts — an outage shape, not a setting.
        int keep = Math.max(maxCategories, 1);
        if (sections.size() <= keep) {
            return sections;
        }
        if (sections.getLast().tag() != null) {
            return List.copyOf(sections.subList(0, keep));
        }
        // Other present: it consumes the last slot, so keep-1 real categories
        // survive and the smallest one yields to it.
        List<CategorySection> capped = new ArrayList<>(sections.subList(0, keep - 1));
        capped.add(sections.getLast());
        return List.copyOf(capped);
    }
}
