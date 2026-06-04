package app.zcat.infochat.provider.summary;

import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.NonNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;

/**
 * Computes connected components of the V29 {@code post_reference}
 * graph over an eligible-post input. For each input post the traversal
 * looks up intra-input neighbours via {@link PostReferenceEdgeSource}
 * and groups posts into clusters by BFS, depth-limited via
 * {@code infochat.provider.cluster-traversal.depth-limit}. An input
 * post with no neighbours in the input set becomes its own singleton.
 *
 * <p>The {@link Cluster#topicId() topic id} is a deterministic function
 * of {@link Cluster#posts() cluster.posts}: the lexicographically
 * smallest {@code uid} in the cluster, prefixed with {@code t-} and
 * truncated to a short slug. Same input → same id across runs
 * (docs/spec/llm.md §Determinism boundary).
 *
 * <p>The {@link #cluster(List)} signature and the {@code Cluster(String,
 * List<Post>)} record shape are preserved verbatim from the v1
 * stub so the @Inject production call sites (DigestRenderer,
 * SummaryCommandHandler) and the four tests that import only the
 * {@code Cluster} type stay unchanged.
 */
@ApplicationScoped
public class ClusterTraversal {

    /**
     * One connected component. {@link #posts} is a defensive copy and
     * preserves the BFS visit order from the cluster's seed post.
     */
    public record Cluster(String topicId, List<Post> posts) {
        public Cluster {
            posts = List.copyOf(posts);
        }
    }

    private final PostReferenceEdgeSource edgeSource;
    private final int depthLimit;

    /**
     * CDI constructor. The depth-limit config key carries an inline
     * default of 3 so missing configuration does not break boot — the
     * %test, %laptop, %vps, %pi, %remote-llm profiles all set the same
     * value in {@code application.properties}.
     */
    @Inject
    public ClusterTraversal(
            @NonNull PostReferenceEdgeSource edgeSource,
            @ConfigProperty(name = "infochat.provider.cluster-traversal.depth-limit",
                            defaultValue = "3") int depthLimit) {
        this.edgeSource = edgeSource;
        this.depthLimit = depthLimit;
    }

    /**
     * Build connected components from the input post list. The input
     * order (deterministic {@code published_at DESC, id DESC} from
     * {@link EligiblePostQuery}) drives the BFS seed order, so the same
     * input + same DB state produces the same cluster sequence across
     * runs.
     */
    public List<Cluster> cluster(List<Post> posts) {
        if (posts == null || posts.isEmpty()) {
            return List.of();
        }
        // Index input by id for O(1) lookups during BFS expansion.
        Map<UUID, Post> byId = new LinkedHashMap<>();
        for (Post p : posts) {
            byId.put(p.id(), p);
        }
        Map<UUID, Set<UUID>> adjacency = edgeSource.neighborsAmong(byId.keySet());

        List<Cluster> out = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        for (Post seed : posts) {
            if (visited.contains(seed.id())) {
                continue;
            }
            List<Post> componentMembers = bfs(seed, byId, adjacency, visited);
            out.add(new Cluster(topicIdFor(componentMembers), componentMembers));
        }
        return out;
    }

    /**
     * BFS the input-restricted graph from {@code seed}, bounded by
     * {@code depthLimit}. Returns the member list in BFS visit order;
     * the seed is index 0. Posts reachable beyond the depth limit are
     * left unvisited so they seed their own cluster on a later
     * iteration of the outer loop (matches the documented depth-limit
     * semantics — a hard cap on per-cluster radius, not on total
     * cluster size).
     */
    private List<Post> bfs(Post seed,
                            Map<UUID, Post> byId,
                            Map<UUID, Set<UUID>> adjacency,
                            Set<UUID> visited) {
        List<Post> members = new ArrayList<>();
        Deque<UUID> frontier = new ArrayDeque<>();
        Map<UUID, Integer> depth = new HashMap<>();
        frontier.add(seed.id());
        depth.put(seed.id(), 0);
        visited.add(seed.id());
        members.add(seed);
        while (!frontier.isEmpty()) {
            UUID currentId = frontier.poll();
            // currentId came off the frontier, and every id pushed to the
            // frontier is recorded in depth at the same time — so this is set.
            int currentDepth = Objects.requireNonNull(depth.get(currentId));
            if (currentDepth >= depthLimit) {
                continue;
            }
            Set<UUID> neighbors = adjacency.getOrDefault(currentId, Set.of());
            for (UUID neighborId : neighbors) {
                if (visited.contains(neighborId)) {
                    continue;
                }
                Post neighbor = byId.get(neighborId);
                if (neighbor == null) {
                    continue;
                }
                visited.add(neighborId);
                depth.put(neighborId, currentDepth + 1);
                frontier.add(neighborId);
                members.add(neighbor);
            }
        }
        return members;
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
