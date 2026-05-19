package app.zcat.infochat.provider.summary;

import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes connected components of the {@code post_reference} graph
 * over an eligible-post input. For MVP — {@code post_reference} table
 * does not exist (V12 is T2 territory) — every post becomes its own
 * singleton cluster, preserving the spec-shape contract for the
 * downstream prose generator without issuing any SQL against a missing
 * table. When T2 lands the graph, this class fills in the real
 * traversal and the call sites do not move.
 *
 * <p>The {@link Cluster#topicId() topic id} is a deterministic function
 * of {@link Cluster#posts() cluster.posts}: the lexicographically-
 * smallest {@code uid} in the cluster, prefixed with {@code t-} and
 * truncated to a short slug. Same input → same id across runs
 * (docs/spec/llm.md §Determinism boundary).
 */
@ApplicationScoped
public class ClusterTraversal {

    /**
     * One connected component. In MVP every cluster carries exactly one
     * post; the {@link #posts} list shape is preserved so T2's
     * multi-post clusters drop into the same downstream API.
     */
    public record Cluster(String topicId, List<Post> posts) {
        public Cluster {
            posts = List.copyOf(posts);
        }
    }

    /**
     * Build singleton clusters in the input order. The input's order is
     * the deterministic {@code published_at DESC, id DESC} sort produced
     * by {@link EligiblePostQuery}; preserving it here means the same
     * input DB state produces the same cluster sequence across runs.
     */
    public List<Cluster> cluster(List<Post> posts) {
        if (posts == null || posts.isEmpty()) {
            return List.of();
        }
        List<Cluster> out = new ArrayList<>(posts.size());
        for (Post p : posts) {
            out.add(new Cluster(topicIdFor(List.of(p)), List.of(p)));
        }
        return out;
    }

    /**
     * Deterministic topic-id derivation from a cluster's posts. The
     * lexicographically-smallest UID is the seed; same input → same id.
     * The {@code t-} prefix plus an 8-char tail keeps the id terse for
     * the reply layout (docs/design/03-commands.md §`/summary` shows
     * {@code [topic_id=t-7f3a]}).
     */
    static String topicIdFor(List<Post> posts) {
        String minUid = null;
        for (Post p : posts) {
            if (minUid == null || p.uid().compareTo(minUid) < 0) {
                minUid = p.uid();
            }
        }
        if (minUid == null) {
            return "t-unknown";
        }
        String tail = minUid.length() <= 8 ? minUid : minUid.substring(0, 8);
        return "t-" + tail;
    }
}
