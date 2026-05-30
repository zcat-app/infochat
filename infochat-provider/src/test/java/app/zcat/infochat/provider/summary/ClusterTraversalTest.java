package app.zcat.infochat.provider.summary;

import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ClusterTraversal}: covers the deterministic
 * topic_id derivation and the BFS graph-traversal contract over a
 * test-time {@link PostReferenceEdgeSource}. Default constructor wires
 * an empty edge source — a node with no neighbours is its own
 * singleton, preserving the v1-stub-era assertions.
 */
class ClusterTraversalTest {

    private static final int DEFAULT_DEPTH = 3;

    /** Test-time edge source backed by an in-memory adjacency map. */
    private static final class InMemoryEdgeSource implements PostReferenceEdgeSource {
        private final Map<UUID, Set<UUID>> edges = new HashMap<>();

        void addEdge(UUID a, UUID b) {
            edges.computeIfAbsent(a, k -> new LinkedHashSet<>()).add(b);
            edges.computeIfAbsent(b, k -> new LinkedHashSet<>()).add(a);
        }

        @Override
        public Map<UUID, Set<UUID>> neighborsAmong(Collection<UUID> postIds) {
            Map<UUID, Set<UUID>> out = new HashMap<>();
            Set<UUID> inputSet = new HashSet<>(postIds);
            for (UUID id : postIds) {
                Set<UUID> neighbors = new LinkedHashSet<>();
                for (UUID n : edges.getOrDefault(id, Set.of())) {
                    if (inputSet.contains(n)) {
                        neighbors.add(n);
                    }
                }
                out.put(id, neighbors);
            }
            return out;
        }
    }

    private final InMemoryEdgeSource edges = new InMemoryEdgeSource();
    private final ClusterTraversal traversal = new ClusterTraversal(edges, DEFAULT_DEPTH);

    @Test
    void emptyInputProducesEmptyOutput() {
        List<Cluster> result = traversal.cluster(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void onePostBecomesOneSingletonCluster() {
        Post p = post("p-only", "Only post");
        List<Cluster> result = traversal.cluster(List.of(p));
        assertEquals(1, result.size());
        assertEquals(1, result.get(0).posts().size());
        assertEquals(p, result.get(0).posts().get(0));
    }

    @Test
    void nPostsBecomeNSingletonClustersWithNoEdges() {
        // With an empty edge source every input post is its own
        // component. Without a positional-order guarantee from the
        // traversal, the assertion is on component MEMBERSHIP: each
        // input post appears in exactly one singleton cluster.
        List<Post> input = List.of(
                post("p-aaa", "A"),
                post("p-bbb", "B"),
                post("p-ccc", "C"));
        List<Cluster> result = traversal.cluster(input);
        assertEquals(3, result.size(), "no edges → one singleton per input post");
        Set<Post> seen = new HashSet<>();
        for (Cluster c : result) {
            assertEquals(1, c.posts().size(), "singleton when the input-graph degree is zero");
            seen.add(c.posts().get(0));
        }
        assertEquals(new HashSet<>(input), seen,
                "every input post must appear in exactly one component");
    }

    @Test
    void topicIdIsADeterministicFunctionOfClusterPosts() {
        Post p = post("p-deadbeef", "Det");
        List<Cluster> first = traversal.cluster(List.of(p));
        List<Cluster> second = traversal.cluster(List.of(p));
        assertEquals(first.get(0).topicId(), second.get(0).topicId(),
                "same posts → same topic_id across runs");
    }

    @Test
    void topicIdDiffersForDifferentInputs() {
        Post a = post("p-aaaaaaaa", "A");
        Post b = post("p-bbbbbbbb", "B");
        List<Cluster> aOut = traversal.cluster(List.of(a));
        List<Cluster> bOut = traversal.cluster(List.of(b));
        assertNotEquals(aOut.get(0).topicId(), bOut.get(0).topicId());
    }

    @Test
    void topicIdSeedIsLexicographicallySmallestUid() {
        // topicIdFor is package-private so the test can pin the
        // derivation rule directly. Same input → same answer; the
        // assertion is on the SEED (smallest uid in the cluster).
        Post a = post("p-zzz", "Z");
        Post b = post("p-aaa", "A");
        String id = ClusterTraversal.topicIdFor(new ArrayList<>(List.of(a, b)));
        assertTrue(id.startsWith("t-"),
                "topic ids carry the t- prefix per design 03 §`/summary` layout");
        assertTrue(id.contains("p-aaa"),
                "the lex-smallest uid is the seed");
    }

    @Test
    void connectedComponents() {
        // Seed two disjoint clusters: {A,B,C} connected as A-B-C, and
        // {D,E} connected as D-E. Two components, total membership = 5.
        Post a = post("p-a", "A");
        Post b = post("p-b", "B");
        Post c = post("p-c", "C");
        Post d = post("p-d", "D");
        Post e = post("p-e", "E");
        edges.addEdge(a.id(), b.id());
        edges.addEdge(b.id(), c.id());
        edges.addEdge(d.id(), e.id());

        List<Cluster> result = traversal.cluster(List.of(a, b, c, d, e));

        assertEquals(2, result.size(),
                "two disjoint edge sets produce exactly two components");
        Set<Set<Post>> componentSets = new HashSet<>();
        for (Cluster cl : result) {
            componentSets.add(new HashSet<>(cl.posts()));
        }
        assertTrue(componentSets.contains(new HashSet<>(List.of(a, b, c))),
                "{A,B,C} must form one component");
        assertTrue(componentSets.contains(new HashSet<>(List.of(d, e))),
                "{D,E} must form the other component");
    }

    private static Post post(String uid, String title) {
        return new Post(UUID.randomUUID(), uid, UUID.randomUUID(), "Src", title,
                "https://example.com/" + uid, "Body", Instant.now(), List.of("tech"));
    }
}
