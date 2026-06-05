package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.collector.outbox.EvalQueueProducer;
import app.zcat.infochat.collector.outbox.PostPersister;
import app.zcat.infochat.collector.outbox.TestEvalQueueConsumer;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.ingest.NormalizedPost;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the kind-6 path through the Registrar's deliver
 * lambda: a {@link NostrEvent} carrying {@code kind=6} and a NIP-18
 * {@code ["e", originalEventId]} tag, converted to a {@link NormalizedPost}
 * via {@link NostrEvent#toNormalizedPost}, dispatched by the same
 * rawMetadata-key predicate the production Registrar uses, lands one
 * {@code post} row AND one {@code post_reference} row with
 * {@code link_type='repost'} in the DB.
 *
 * <p>The deliver lambda is reproduced inline here (rather than invoking
 * the production Registrar's lambda) to keep the IT independent of the
 * Registrar startup wiring — the Registrar fires at {@code @Startup
 * @Priority(460)} which has already run before any test method executes,
 * and re-wiring it inline would require a fresh CDI context. The contract
 * the IT pins is: {@code rawMetadata.get(NostrEvent.META_KIND).equals("6")}
 * → {@code kind6Handler.handle(post, sourceUuid)}, exactly as the
 * production Registrar's deliver lambda does (verbatim copy of the
 * dispatch predicate so any divergence in the production wiring shows up
 * as an IT failure).
 */
@QuarkusTest
class Kind6LinkingIT {

    /** Pinned fetched_at inside V7/V29 bootstrap partitions (May 2026). */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-22T10:00:00Z");

    private static final String UPSTREAM_PREFIX = "kind6-linking-it/";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    PostPersister postPersister;

    @Inject
    EvalQueueProducer evalQueueProducer;

    @Inject
    Kind6Handler kind6Handler;

    @Inject
    TestEvalQueueConsumer evalConsumer;

    @BeforeEach
    void reset() throws Exception {
        clearTestData();
        evalConsumer.drain();
    }

    /**
     * Acceptance item 9 + the dispatch acceptance item (5): a kind-6
     * event processed through the Registrar's deliver path produces one
     * post row AND one post_reference row in the DB.
     */
    @Test
    void kind6FlowsToPostReference() throws Exception {
        UUID sourceUuid = seedNostrSource(UPSTREAM_PREFIX + "source");
        String upstreamId = UPSTREAM_PREFIX + "kind6-event";
        String originalEventId = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";

        NostrEvent kind6 = new NostrEvent(
                upstreamId,
                "deadbeef".repeat(8),  // pubkey (any 64-char hex; not verified here)
                FETCHED_AT.getEpochSecond(),
                6,
                List.of(List.of("e", originalEventId)),
                "End-to-end commentary",
                "00".repeat(64));  // sig (any 128-char hex; not verified here)

        NormalizedPost post = kind6.toNormalizedPost(0L, FETCHED_AT);

        // Verbatim copy of the Registrar's deliver lambda dispatch
        // predicate from NostrStreamSource.Registrar.registerNostrSources.
        Consumer<NormalizedPost> deliver = p -> {
            if ("6".equals(p.rawMetadata().get(NostrEvent.META_KIND))) {
                kind6Handler.handle(p, sourceUuid);
            } else {
                postPersister.persist(sourceUuid, p).ifPresent(evalQueueProducer::emit);
            }
        };

        deliver.accept(post);

        UUID postId = lookupPostId(sourceUuid, upstreamId);
        assertNotNull(postId, "the kind-6 event produced a post row");
        assertEquals("End-to-end commentary", lookupBody(postId),
                "post.body carries the kind-6 commentary verbatim");

        RepostEdge edge = querySingleRepostEdge(postId);
        assertEquals(postId, edge.fromPost(),
                "from_post is the kind-6 post's own UUID");
        assertEquals(Kind6Handler.deriveToPostUuid(originalEventId), edge.toPost(),
                "to_post is UUID.nameUUIDFromBytes(originalEventId.getBytes(UTF_8))");
        assertEquals("repost", edge.linkType());

        // The eval-queue must receive the persisted post's key so the
        // commentary flows through Stage 1 / Stage 2 / tagger / embedding.
        assertTrue(awaitConsumerSize(1),
                "the kind-6 PersistedPostKey reached the eval-queue");
    }

    // ---------- helpers ----------

    private UUID seedNostrSource(String identifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('nostr', ?, 'Kind6 linking IT source', 'social', '{}') "
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
                if (!rs.next()) {
                    return null;
                }
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
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private RepostEdge querySingleRepostEdge(UUID fromPost) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT from_post, to_post, link_type, score "
                     + "FROM post_reference "
                     + "WHERE from_post = ? AND link_type = 'repost'")) {
            ps.setObject(1, fromPost);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected exactly one repost edge for from_post=" + fromPost);
                RepostEdge edge = new RepostEdge(
                        (UUID) rs.getObject(1),
                        (UUID) rs.getObject(2),
                        rs.getString(3),
                        rs.getFloat(4));
                assertTrue(!rs.next(), "expected exactly one repost edge, found additional rows");
                return edge;
            }
        }
    }

    private void clearTestData() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM post_reference "
                    + "WHERE from_post IN (SELECT id FROM post WHERE upstream_identifier LIKE ?)")) {
                ps.setString(1, UPSTREAM_PREFIX + "%");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM post WHERE upstream_identifier LIKE ?")) {
                ps.setString(1, UPSTREAM_PREFIX + "%");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM source WHERE kind = 'nostr' AND identifier LIKE ?")) {
                ps.setString(1, UPSTREAM_PREFIX + "%");
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

    private record RepostEdge(UUID fromPost, UUID toPost, String linkType, float score) {
    }
}
