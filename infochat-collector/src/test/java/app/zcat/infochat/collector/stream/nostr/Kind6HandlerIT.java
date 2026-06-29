package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.collector.outbox.EvalQueueProducer;
import app.zcat.infochat.collector.outbox.PostPersister;
import app.zcat.infochat.collector.outbox.TestEvalQueueConsumer;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.ingest.NormalizedPost;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural assertions for {@link Kind6Handler}. The handler is driven
 * directly with hand-built {@link NormalizedPost} inputs (rather than
 * through {@link NostrEvent#toNormalizedPost} + the Registrar's deliver
 * lambda — that path is exercised by {@link Kind6LinkingIT}). Each test
 * pins one acceptance-criterion behaviour:
 *
 * <ul>
 *   <li>{@link #nonEmptyContent_storesBodyAndReference} — kind-6 with
 *     commentary stores body + post_reference edge with link_type='repost'.</li>
 *   <li>{@link #emptyContent_storesEmptyBodyAndReference} — kind-6 with
 *     empty content stores empty body + post_reference edge.</li>
 * </ul>
 *
 * <p>Both tests pin the unresolved-edge shape: to_upstream_identifier
 * carries the original event id verbatim and to_post is NULL (no
 * original is seeded here — resolution is covered by
 * {@link Kind6RepostResolutionIT}).</p>
 *
 * <p>Reused fixture {@code UID_PREFIX} keeps the per-test cleanup query
 * scoped to this class's seeded rows; the test runs concurrently in the
 * Quarkus test profile shared with {@link NostrStreamSourceIT} and other
 * Nostr ITs and must not delete their rows.
 */
@QuarkusTest
class Kind6HandlerIT {

    /** Pinned fetched_at inside V7/V29 bootstrap partitions (May 2026). */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-22T10:00:00Z");

    private static final String UID_PREFIX_PARTIAL = "kind6-handler-test/";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    Kind6Handler kind6Handler;

    @Inject
    PostPersister postPersister;

    @Inject
    EvalQueueProducer evalQueueProducer;

    @Inject
    RepostEdgeResolver repostEdgeResolver;

    @Inject
    TestEvalQueueConsumer evalConsumer;

    @BeforeEach
    void reset() throws Exception {
        clearTestData();
        evalConsumer.drain();
    }

    /**
     * A kind-6 event with non-empty content stores the commentary text
     * as the post body and writes one post_reference row with
     * link_type='repost' carrying the referenced original's event id
     * verbatim in to_upstream_identifier and NULL in to_post (the
     * original is not stored, so the edge is unresolved).
     */
    @Test
    void nonEmptyContent_storesBodyAndReference() throws Exception {
        UUID sourceUuid = seedNostrSource("kind6-handler-test/nonEmpty");
        String originalEventId = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        String upstreamIdentifier = UID_PREFIX_PARTIAL + "nonEmptyEvent";
        NormalizedPost post = kind6Post(upstreamIdentifier, "This is repost commentary", originalEventId);

        kind6Handler.handle(post, sourceUuid);

        UUID postId = lookupPostId(sourceUuid, upstreamIdentifier);
        assertEquals("This is repost commentary", lookupBody(postId),
                "post.body carries the kind-6 commentary text");

        List<RepostEdge> edges = queryRepostEdges(postId);
        assertEquals(1, edges.size(),
                "exactly one post_reference row written with link_type='repost'");
        RepostEdge edge = edges.get(0);
        assertEquals(postId, edge.fromPost(),
                "from_post is the kind-6's own post.id UUID");
        assertEquals(originalEventId, edge.toUpstreamIdentifier(),
                "to_upstream_identifier stores the original event id verbatim");
        assertNull(edge.toPost(),
                "to_post is NULL while the original event is not stored (unresolved edge)");
        assertEquals(1.0f, edge.score(),
                "repost edge score is 1.0 (per-link_type unit, no scalar to vary)");

        // Acceptance item 6 demands the persisted-key flows to eval-queue
        // so Stage 1 / Stage 2 / tagger / embedding run on the commentary.
        assertTrue(awaitConsumerSize(1), "the kind-6 post key reached the eval-queue");
    }

    /**
     * A kind-6 event with empty content stores an empty post body AND
     * still writes the post_reference edge — empty-content reposts are
     * a valid NIP-18 shape (the original event is the entire message,
     * with no added commentary).
     */
    @Test
    void emptyContent_storesEmptyBodyAndReference() throws Exception {
        UUID sourceUuid = seedNostrSource("kind6-handler-test/emptyContent");
        String originalEventId = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
        String upstreamIdentifier = UID_PREFIX_PARTIAL + "emptyEvent";
        NormalizedPost post = kind6Post(upstreamIdentifier, "", originalEventId);

        kind6Handler.handle(post, sourceUuid);

        UUID postId = lookupPostId(sourceUuid, upstreamIdentifier);
        assertEquals("", lookupBody(postId),
                "post.body is the empty string for an empty-content kind-6");

        List<RepostEdge> edges = queryRepostEdges(postId);
        assertEquals(1, edges.size(),
                "the post_reference edge is still written when commentary is empty");
        assertEquals(originalEventId, edges.get(0).toUpstreamIdentifier(),
                "to_upstream_identifier still stores the original event id verbatim");
        assertNull(edges.get(0).toPost(),
                "to_post is still NULL while the original event is not stored");
    }

    /**
     * Edge-write failure inside the post-plus-edge transaction rolls the
     * post write back (the pinned atomicity semantics): a committed
     * kind-6 post can never exist without its repost edge. Nothing is
     * committed, nothing reaches the eval queue. The handler is
     * constructed by hand around {@link EdgeWriteFailingDataSource} so
     * only the transaction's DataSource differs from production wiring.
     */
    @Test
    void edgeWriteFailure_rollsBackPostWrite() throws Exception {
        UUID sourceUuid = seedNostrSource("kind6-handler-test/edgeFail");
        String originalEventId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String upstreamIdentifier = UID_PREFIX_PARTIAL + "edgeFailEvent";
        NormalizedPost post = kind6Post(upstreamIdentifier, "doomed commentary", originalEventId);

        Kind6Handler failingHandler = new Kind6Handler();
        failingHandler.dataSource = new EdgeWriteFailingDataSource(dataSource);
        failingHandler.postPersister = postPersister;
        failingHandler.evalQueueProducer = evalQueueProducer;
        failingHandler.repostEdgeResolver = repostEdgeResolver;

        assertThrows(IllegalStateException.class,
            () -> failingHandler.handle(post, sourceUuid),
            "the injected edge-write failure must propagate out of the transaction");

        assertEquals(0, countPosts(sourceUuid, upstreamIdentifier),
            "the post write must roll back when the edge write fails — no edgeless post is committed");
        assertEquals(0, evalConsumer.size(),
            "nothing may reach the eval queue when the transaction rolled back");
    }

    // ---------- helpers ----------

    private int countPosts(UUID sourceUuid, String upstreamIdentifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM post WHERE source_id = ? AND upstream_identifier = ?")) {
            ps.setObject(1, sourceUuid);
            ps.setString(2, upstreamIdentifier);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Build a NormalizedPost shaped like {@link NostrEvent#toNormalizedPost}
     * would produce for a kind-6 event carrying an {@code ["e", originalEventId]}
     * tag: rawMetadata holds nostr.kind=6 + nostr.repost-target=originalEventId.
     */
    private NormalizedPost kind6Post(String upstreamIdentifier, String body, String originalEventId) {
        return new NormalizedPost(
                0L,
                upstreamIdentifier,
                null,
                body,
                null,
                FETCHED_AT,
                FETCHED_AT,
                Map.of(NostrEvent.META_KIND, "6",
                       NostrEvent.META_REPOST_TARGET, originalEventId));
    }

    private UUID seedNostrSource(String identifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('nostr', ?, 'Kind6 handler test source', 'social', '{}') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            ps.setString(1, identifier);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID lookupPostId(UUID sourceUuid, String upstreamIdentifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id FROM post WHERE source_id = ? AND upstream_identifier = ?")) {
            ps.setObject(1, sourceUuid);
            ps.setString(2, upstreamIdentifier);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a post row for " + upstreamIdentifier);
                return (UUID) rs.getObject(1);
            }
        }
    }

    private String lookupBody(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT body FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a post row for id " + postId);
                String body = rs.getString(1);
                return body == null ? "" : body;
            }
        }
    }

    private List<RepostEdge> queryRepostEdges(UUID fromPost) throws Exception {
        List<RepostEdge> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT from_post, to_post, to_upstream_identifier, link_type, score "
                     + "FROM post_reference "
                     + "WHERE from_post = ? AND link_type = 'repost'")) {
            ps.setObject(1, fromPost);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new RepostEdge(
                            (UUID) rs.getObject(1),
                            (UUID) rs.getObject(2),
                            rs.getString(3),
                            rs.getString(4),
                            rs.getFloat(5)));
                }
            }
        }
        return out;
    }

    private void clearTestData() throws Exception {
        // Delete this test's seeded post_reference rows + posts +
        // sources without disturbing rows seeded by parallel Nostr
        // tests sharing the @QuarkusTest profile. Scoped by uid LIKE.
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM post_reference "
                    + "WHERE from_post IN (SELECT id FROM post WHERE upstream_identifier LIKE ?)")) {
                ps.setString(1, UID_PREFIX_PARTIAL + "%");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM post WHERE upstream_identifier LIKE ?")) {
                ps.setString(1, UID_PREFIX_PARTIAL + "%");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM source WHERE kind = 'nostr' AND identifier LIKE ?")) {
                ps.setString(1, UID_PREFIX_PARTIAL + "%");
                ps.executeUpdate();
            }
        }
    }

    private boolean awaitConsumerSize(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (evalConsumer.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        return evalConsumer.size() >= expected;
    }

    private record RepostEdge(UUID fromPost, @Nullable UUID toPost,
                              String toUpstreamIdentifier, String linkType, float score) {
    }
}
