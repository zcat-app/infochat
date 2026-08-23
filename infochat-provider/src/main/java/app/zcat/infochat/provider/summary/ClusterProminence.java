package app.zcat.infochat.provider.summary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Ranks the clusters of ONE digest by significance (M1-724): each cluster
 * is scored as a weighted sum over five percentile-normalized terms —
 * corroboration, reposts, likes, comments, source scarcity — gated by the
 * {@code urgent} ingest classification and tie-broken by the input
 * (recency) order. The full ordering is (0) PERSONAL clusters behind
 * non-personal (the M1-727 bottom gate — an all-personal cluster is
 * routed to the D62 Other bucket and must not evict
 * genuinely-uncategorizable news from it), (1) urgent clusters ahead of
 * non-urgent, (2) descending weighted score, (3) ascending input index —
 * the collector's {@code COALESCE(published_at, fetched_at) DESC, id DESC}
 * at cluster granularity — so the order is total without relying on sort
 * stability. The bottom gate is NOT a sixth weighted term: it reads no
 * score component, exactly as the {@code urgent} top gate reads none.
 *
 * <p>Every term is an INTEGER percentile 0–100 within its own population,
 * never a raw value: distinct-source counts run 1–10 while like counts run
 * to five figures, so a weighted sum over raw values would be a like-count
 * ranking with rounding noise from the other terms. Populations are
 * like-with-like: corroboration and scarcity rank against the other
 * clusters in this digest; reposts, likes and comments rank against
 * clusters of the SAME source kind (Bluesky against Bluesky, reddit
 * against reddit, never against RSS).
 *
 * <p>A MISSING term (NULL column) drops out of the denominator — it does
 * not score zero. An RSS cluster is scored out of 7 + 2 = 9, which is what
 * keeps editorial sources competitive with social ones; a 0 count is a
 * present term with a bottom percentile (M1-723 §Absent is not zero).
 *
 * <p>D19 byte-identical replay: no float participates in any comparison.
 * Percentiles are {@code floor(100 * strictlyBelow / populationSize)} in
 * long arithmetic; the corroboration RATIO is ordered by cross-multiplied
 * longs, never divided; the weighted score is ORDERED by exact rational
 * comparison, with the floored integer only the REPORTED score. There is
 * no {@code Instant.now()}, no Clock — every input is a DB column or a
 * count derived from the collected set.
 */
@ApplicationScoped
public class ClusterProminence {

    // Field-initialized to the ticket defaults so a plain-JUnit construction
    // scores with the documented weights; CDI overwrites at runtime — the
    // DigestRenderer#categoryRollupGenerator pattern.
    @ConfigProperty(name = "infochat.digest.weight.corroboration", defaultValue = "7")
    int weightCorroboration = 7;

    @ConfigProperty(name = "infochat.digest.weight.reposts", defaultValue = "2")
    int weightReposts = 2;

    @ConfigProperty(name = "infochat.digest.weight.likes", defaultValue = "1")
    int weightLikes = 1;

    // The reddit reply count's own term (M1-914): default 1 keeps
    // corroboration (7) the heaviest single weight, per owner direction.
    @ConfigProperty(name = "infochat.digest.weight.comments", defaultValue = "1")
    int weightComments = 1;

    @ConfigProperty(name = "infochat.digest.weight.scarcity", defaultValue = "2")
    int weightScarcity = 2;

    /**
     * One scored cluster: the ordering inputs AND the per-term components.
     * The weights ship uncalibrated and are meant to be tuned against the
     * live corpus, which is impossible if the inputs cannot be read back —
     * so the percentiles, the weights applied, the exact numerator, the
     * present-terms denominator and the floored score all ride alongside
     * the ordering. A null term percentile means the term was ABSENT for
     * this cluster (excluded from both numerator and denominator).
     */
    public record ScoredCluster(
            Cluster cluster,
            int index,
            boolean urgent,
            boolean personal,
            int corroborationPercentile,
            @Nullable Integer repostsPercentile,
            @Nullable Integer likesPercentile,
            @Nullable Integer commentsPercentile,
            @Nullable Integer scarcityPercentile,
            int weightCorroboration,
            int weightReposts,
            int weightLikes,
            int weightComments,
            int weightScarcity,
            long numerator,
            int denominator,
            int score) {
    }

    /**
     * The total order: personal LAST (the M1-727 bottom gate), then
     * urgent first, then descending weighted score by exact rational
     * comparison ({@code numerator/denominator} cross-multiplied — never
     * divided, so no float and no premature flooring), then ascending
     * input index (the existing recency sort key at cluster granularity).
     * Static and stateless: the comparator reads only the
     * {@link ScoredCluster} fields, so it is valid across instances and
     * weight configurations.
     */
    public static Comparator<ScoredCluster> totalOrder() {
        return (a, b) -> {
            if (a.personal() != b.personal()) {
                return a.personal() ? 1 : -1;
            }
            if (a.urgent() != b.urgent()) {
                return a.urgent() ? -1 : 1;
            }
            long lhs = a.numerator() * b.denominator();
            long rhs = b.numerator() * a.denominator();
            if (lhs != rhs) {
                return lhs > rhs ? -1 : 1;
            }
            return Integer.compare(a.index(), b.index());
        };
    }

    /**
     * Score every cluster of one digest. {@code digestOrderClusters} is the
     * cluster list in collector order (index i is the recency tiebreak);
     * {@code assignedTagByCluster} maps each cluster to its FINAL assigned
     * category tag post-D62-fold (identity keys), with a null tag marking
     * the Other bucket — Other-bucket clusters have no qualifying tag by
     * construction, so their corroboration denominator is the digest-wide
     * count of distinct sources that posted within the window.
     *
     * <p>The returned list is in INPUT order; sorting is the caller's job
     * ({@link #totalOrder()}), which is what keeps percentile populations
     * independent of any downstream section cap.
     */
    public List<ScoredCluster> score(List<Cluster> digestOrderClusters,
                                     Map<Cluster, @Nullable String> assignedTagByCluster) {
        int n = digestOrderClusters.size();

        // Tag-activity populations for the corroboration denominator:
        // distinct sources that posted under each tag across the collected
        // set, plus the digest-wide distinct-source count for Other-bucket
        // clusters. Counts are order-independent, so HashMap/HashSet
        // iteration order never reaches the output (every lookup is keyed).
        Map<String, Set<UUID>> sourcesByTag = new HashMap<>();
        Set<UUID> allSources = new HashSet<>();
        for (Cluster cluster : digestOrderClusters) {
            for (Post p : cluster.posts()) {
                allSources.add(p.sourceId());
                for (String tag : p.tags()) {
                    sourcesByTag.computeIfAbsent(tag, k -> new HashSet<>()).add(p.sourceId());
                }
            }
        }
        int digestWideSources = allSources.size();

        // Per-cluster raw term values, indexed by input position.
        long[] corrobNum = new long[n];
        long[] corrobDen = new long[n];
        boolean[] urgent = new boolean[n];
        boolean[] personal = new boolean[n];
        long[] maxReposts = new long[n];
        boolean[] hasReposts = new boolean[n];
        String[] repostsKind = new String[n];
        long[] maxLikes = new long[n];
        boolean[] hasLikes = new boolean[n];
        String[] likesKind = new String[n];
        long[] maxComments = new long[n];
        boolean[] hasComments = new boolean[n];
        String[] commentsKind = new String[n];
        long[] scarcityVolume = new long[n];
        boolean[] hasScarcity = new boolean[n];

        for (int i = 0; i < n; i++) {
            Cluster cluster = digestOrderClusters.get(i);
            Set<UUID> clusterSources = new HashSet<>();
            // The M1-727 bottom gate: personal only when EVERY member post
            // carries the label — the same all-versus-any predicate as
            // DigestCategorizer.isPersonal (keep the two in step; the
            // summary package cannot import the digest one). Absent data
            // (null classification, empty cluster) is NOT personal. The
            // "personal" literal matches the "urgent" one below: closed-set
            // vocabulary, not an importable constant.
            boolean allPersonal = !cluster.posts().isEmpty();
            for (Post p : cluster.posts()) {
                clusterSources.add(p.sourceId());
                if (p.classification() != null && p.classification().contains("urgent")) {
                    urgent[i] = true;
                }
                if (p.classification() == null || !p.classification().contains("personal")) {
                    allPersonal = false;
                }
                // Max value with the contributing post's kind; strict >
                // keeps the FIRST max-contributing post on ties, so the
                // mixed-kind population key is deterministic in input order.
                if (p.reposts() != null && (!hasReposts[i] || p.reposts() > maxReposts[i])) {
                    hasReposts[i] = true;
                    maxReposts[i] = p.reposts();
                    repostsKind[i] = p.sourceKind();
                }
                if (p.likes() != null && (!hasLikes[i] || p.likes() > maxLikes[i])) {
                    hasLikes[i] = true;
                    maxLikes[i] = p.likes();
                    likesKind[i] = p.sourceKind();
                }
                if (p.comments() != null && (!hasComments[i] || p.comments() > maxComments[i])) {
                    hasComments[i] = true;
                    maxComments[i] = p.comments();
                    commentsKind[i] = p.sourceKind();
                }
                // Scarcity ranks the INVERSE posting volume of the cluster's
                // least-prolific member source, so the value kept is the MIN
                // window count; lower volume ranks better (below, the negated
                // value feeds the same higher-is-better percentile pass).
                if (p.sourceWindowPosts() != null
                        && (!hasScarcity[i] || p.sourceWindowPosts() < scarcityVolume[i])) {
                    hasScarcity[i] = true;
                    scarcityVolume[i] = p.sourceWindowPosts();
                }
            }
            corrobNum[i] = clusterSources.size();
            String tag = assignedTagByCluster.get(cluster);
            Set<UUID> tagSources = tag == null ? null : sourcesByTag.get(tag);
            // den >= 1 always: a tagged cluster's own posts carry the
            // assigned tag (D62), so at least one source is active under it;
            // Other-bucket clusters use the digest-wide count.
            corrobDen[i] = tagSources == null ? digestWideSources : tagSources.size();
            personal[i] = allPersonal;
        }

        // Percentiles, one population per term.
        int[] corrobPercentile = new int[n];
        percentilesByRatio(corrobNum, corrobDen, corrobPercentile);
        Integer[] repostsPercentile = new Integer[n];
        Integer[] likesPercentile = new Integer[n];
        Integer[] commentsPercentile = new Integer[n];
        percentilesByKind(maxReposts, hasReposts, repostsKind, repostsPercentile);
        percentilesByKind(maxLikes, hasLikes, likesKind, likesPercentile);
        percentilesByKind(maxComments, hasComments, commentsKind, commentsPercentile);
        Integer[] scarcityPercentile = new Integer[n];
        percentilesByValue(scarcityVolume, hasScarcity, true, allIndices(n), scarcityPercentile);

        List<ScoredCluster> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long numerator = (long) weightCorroboration * corrobPercentile[i];
            int denominator = weightCorroboration;
            if (repostsPercentile[i] != null) {
                numerator += (long) weightReposts * repostsPercentile[i];
                denominator += weightReposts;
            }
            if (likesPercentile[i] != null) {
                numerator += (long) weightLikes * likesPercentile[i];
                denominator += weightLikes;
            }
            if (commentsPercentile[i] != null) {
                numerator += (long) weightComments * commentsPercentile[i];
                denominator += weightComments;
            }
            if (scarcityPercentile[i] != null) {
                numerator += (long) weightScarcity * scarcityPercentile[i];
                denominator += weightScarcity;
            }
            out.add(new ScoredCluster(
                    digestOrderClusters.get(i), i, urgent[i], personal[i],
                    corrobPercentile[i], repostsPercentile[i], likesPercentile[i],
                    commentsPercentile[i], scarcityPercentile[i],
                    weightCorroboration, weightReposts, weightLikes, weightComments,
                    weightScarcity,
                    numerator, denominator, (int) (numerator / denominator)));
        }
        return out;
    }

    private static List<Integer> allIndices(int n) {
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(i);
        }
        return out;
    }

    /**
     * Corroboration percentiles over the whole digest: rank-based with
     * ties sharing a value ("percentage of the population scoring strictly
     * below"). Ratios are ordered by cross-multiplied longs — never
     * divided — so equal ratios tie exactly and no float participates.
     */
    private static void percentilesByRatio(long[] num, long[] den, int[] out) {
        List<Integer> order = allIndices(num.length);
        order.sort((a, b) -> Long.compare(num[a] * den[b], num[b] * den[a]));
        int pos = 0;
        while (pos < order.size()) {
            int end = pos + 1;
            while (end < order.size()
                    && num[order.get(end)] * den[order.get(pos)]
                            == num[order.get(pos)] * den[order.get(end)]) {
                end++;
            }
            for (int k = pos; k < end; k++) {
                out[order.get(k)] = percentile(pos, order.size());
            }
            pos = end;
        }
    }

    /**
     * Social-term percentiles, one population per source kind: a cluster
     * ranks only against clusters of the SAME kind (the kind of the post
     * contributing the cluster's max value). Clusters with the term absent
     * keep a null percentile.
     */
    private static void percentilesByKind(long[] value, boolean[] present,
                                          String[] kind, Integer[] out) {
        Map<String, List<Integer>> byKind = new HashMap<>();
        for (int i = 0; i < value.length; i++) {
            if (present[i]) {
                byKind.computeIfAbsent(kind[i], k -> new ArrayList<>()).add(i);
            }
        }
        for (List<Integer> population : byKind.values()) {
            percentilesByValue(value, present, false, population, out);
        }
    }

    /**
     * Rank-based percentiles over one population of long values, ties
     * sharing a value. {@code negate} inverts the ranking (scarcity: lower
     * volume is better, so the negated volume is what "scores strictly
     * below" measures).
     */
    private static void percentilesByValue(long[] value, boolean[] present, boolean negate,
                                           List<Integer> population, Integer[] out) {
        List<Integer> order = new ArrayList<>(population.size());
        for (int i : population) {
            // Absent terms stay OUT of the population (and keep a null
            // percentile): a NULL column is not a bottom-ranked value.
            if (present[i]) {
                order.add(i);
            }
        }
        order.sort((a, b) -> {
            long va = negate ? -value[a] : value[a];
            long vb = negate ? -value[b] : value[b];
            return Long.compare(va, vb);
        });
        int pos = 0;
        while (pos < order.size()) {
            int end = pos + 1;
            while (end < order.size()
                    && value[order.get(end)] == value[order.get(pos)]) {
                end++;
            }
            for (int k = pos; k < end; k++) {
                out[order.get(k)] = percentile(pos, order.size());
            }
            pos = end;
        }
    }

    /** floor(100 * strictlyBelow / populationSize) in long arithmetic. */
    private static int percentile(long strictlyBelow, long populationSize) {
        return (int) (100L * strictlyBelow / populationSize);
    }
}
