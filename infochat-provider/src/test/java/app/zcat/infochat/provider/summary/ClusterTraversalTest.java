package app.zcat.infochat.provider.summary;

import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ClusterTraversal}'s MVP singleton behavior +
 * the deterministic {@code topic_id} derivation. The graph-traversal
 * tests land when T2 introduces the {@code post_reference} table.
 */
class ClusterTraversalTest {

    private final ClusterTraversal traversal = new ClusterTraversal();

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
    void nPostsBecomeNSingletonClustersInInputOrder() {
        List<Post> input = List.of(
                post("p-aaa", "A"),
                post("p-bbb", "B"),
                post("p-ccc", "C"));
        List<Cluster> result = traversal.cluster(input);
        assertEquals(3, result.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(1, result.get(i).posts().size(),
                    "MVP traversal yields singleton clusters");
            assertEquals(input.get(i), result.get(i).posts().get(0),
                    "input order is preserved");
        }
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

    private static Post post(String uid, String title) {
        return new Post(UUID.randomUUID(), uid, UUID.randomUUID(), "Src", title,
                "https://example.com/" + uid, "Body", Instant.now(), List.of("tech"));
    }
}
