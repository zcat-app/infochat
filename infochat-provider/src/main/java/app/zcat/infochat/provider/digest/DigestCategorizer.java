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

import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Groups already-clustered digest posts under deterministic topic categories
 * (D62). Pure tag arithmetic over the cluster set — no LLM call — so the
 * digest's section structure is byte-reproducible for a given cluster set,
 * extending the project's "deterministic retrieval, LLM only for prose"
 * boundary to the digest's structure.
 */
@ApplicationScoped
public class DigestCategorizer {

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
        // A cluster's tag-set is the union of its member posts' tags;
        // categories are counted at the cluster level (the render unit),
        // not the post level.
        List<Set<String>> clusterTagSets = new ArrayList<>(clusters.size());
        Map<String, Integer> tagClusterCounts = new HashMap<>();
        for (Cluster cluster : clusters) {
            Set<String> tags = new TreeSet<>();
            for (Post post : cluster.posts()) {
                tags.addAll(post.tags());
            }
            clusterTagSets.add(tags);
            for (String tag : tags) {
                tagClusterCounts.merge(tag, 1, Integer::sum);
            }
        }

        // First pass: assign each cluster to its highest-count qualifying
        // tag. The TreeSet iterates alphabetically and `best` is replaced
        // only on a strictly greater count, so equal-count ties resolve to
        // the alphabetically first tag.
        List<@Nullable String> chosenTags = new ArrayList<>(clusters.size());
        Map<String, Integer> assignedCounts = new HashMap<>();
        for (Set<String> tagSet : clusterTagSets) {
            String best = null;
            for (String tag : tagSet) {
                if (tagClusterCounts.getOrDefault(tag, 0) < categoryMinClusters) {
                    continue;
                }
                if (best == null
                        || tagClusterCounts.getOrDefault(tag, 0) > tagClusterCounts.getOrDefault(best, 0)) {
                    best = tag;
                }
            }
            chosenTags.add(best);
            if (best != null) {
                assignedCounts.merge(best, 1, Integer::sum);
            }
        }

        // Second pass: regroup in digest order, folding under-threshold
        // categories into Other — a category can lose its clusters to a
        // larger co-tag, and a near-empty section reads worse than Other.
        // Single deterministic pass, no cascade: folding only grows Other,
        // never shrinks another category below its already-final count.
        Map<String, List<Cluster>> sectionsByTag = new HashMap<>();
        List<Cluster> other = new ArrayList<>();
        for (int i = 0; i < clusters.size(); i++) {
            String tag = chosenTags.get(i);
            if (tag == null || assignedCounts.getOrDefault(tag, 0) < categoryMinClusters) {
                other.add(clusters.get(i));
            } else {
                sectionsByTag.computeIfAbsent(tag, key -> new ArrayList<>()).add(clusters.get(i));
            }
        }

        List<CategorySection> sections = sectionsByTag.entrySet().stream()
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
