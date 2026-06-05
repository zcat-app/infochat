package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.collector.outbox.TestEvalQueueConsumer;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.ingest.NormalizedPost;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 *   <li>{@link #joinKeyIsUpstreamIdentifier} — post_reference.to_post
 *     equals {@code UUID.nameUUIDFromBytes(originalEventId.getBytes(UTF_8))},
 *     deterministic and source-independent.</li>
 * </ul>
 *
 * <p>Reused fixture {@code UID_PREFIX} keeps the per-test cleanup query
 * scoped to this class's seeded rows; the test runs concurrently in the
 * Quarkus test profile shared with {@link NostrStreamSourceIT} and other
 * Nostr ITs and must not delete their rows.
 */
@QuarkusTest
class Kind6HandlerTest {

    /** Pinned fetched_at inside V7/V29 bootstrap partitions (May 2026). */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-22T10:00:00Z");

    private static final String UID_PREFIX_PARTIAL = "kind6-handler-test/";

    @Inject
    @SeedDataSource
    DataSource dataSource;

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
     * Acceptance items 1 and 6: a kind-6 event with non-empty content
     * stores the commentary text as the post body and writes one
     * post_reference row with link_type='repost' whose to_post is the
     * deterministic derivation of the referenced original's event id.
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
        assertEquals(Kind6Handler.deriveToPostUuid(originalEventId), edge.toPost(),
                "to_post is the deterministic derivation of the original event id");
        assertEquals(1.0f, edge.score(),
                "repost edge score is 1.0 (per-link_type unit, no scalar to vary)");

        // Acceptance item 6 demands the persisted-key flows to eval-queue
        // so Stage 1 / Stage 2 / tagger / embedding run on the commentary.
        assertTrue(awaitConsumerSize(1), "the kind-6 post key reached the eval-queue");
    }

    /**
     * Acceptance items 2 and 7: a kind-6 event with empty content stores
     * an empty post body AND still writes the post_reference edge —
     * empty-content reposts are a valid NIP-18 shape (the original event
     * is the entire message, with no added commentary).
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
        assertEquals(Kind6Handler.deriveToPostUuid(originalEventId), edges.get(0).toPost(),
                "to_post is still the deterministic derivation of the original event id");
    }

    /**
     * Acceptance items 3, 4, and 8: the join key for kind-6 reposts is
     * the original event's upstream_identifier, encoded as
     * {@code UUID.nameUUIDFromBytes(eventId.getBytes(UTF_8))}. The
     * derivation is source-independent: the same event id produces the
     * same UUID regardless of which Nostr source / relay observes it,
     * so a future arrival of the original event from any source can
     * re-derive the same UUID to resolve the link.
     */
    @Test
    void joinKeyIsUpstreamIdentifier() {
        String originalEventId = "0011223344556677889900112233445566778899001122334455667788990011";
        UUID derived = Kind6Handler.deriveToPostUuid(originalEventId);
        UUID expected = UUID.nameUUIDFromBytes(originalEventId.getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, derived,
                "deriveToPostUuid uses UUID.nameUUIDFromBytes(eventId.getBytes(UTF_8)) verbatim");

        // Source-independence: the derivation depends ONLY on the event id,
        // not on any other input. Calling twice with the same event id
        // produces the same UUID (deterministic). Acceptance item 3:
        // "NOT the derived post UID" — PostPersister.deriveUid uses
        // sha256(source_id || '|' || upstream_identifier), so derivations
        // that incorporate source_id are forbidden. The to_post UUID
        // depends solely on the event id.
        UUID derivedAgain = Kind6Handler.deriveToPostUuid(originalEventId);
        assertEquals(derived, derivedAgain, "derivation is deterministic per event id");

        // A different event id derives a different UUID.
        UUID different = Kind6Handler.deriveToPostUuid(
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        assertEquals(false, derived.equals(different),
                "distinct event ids derive distinct to_post UUIDs (no collision on these fixtures)");
    }

    // ---------- helpers ----------

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
                 "SELECT from_post, to_post, link_type, score "
                     + "FROM post_reference "
                     + "WHERE from_post = ? AND link_type = 'repost'")) {
            ps.setObject(1, fromPost);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new RepostEdge(
                            (UUID) rs.getObject(1),
                            (UUID) rs.getObject(2),
                            rs.getString(3),
                            rs.getFloat(4)));
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

    private record RepostEdge(UUID fromPost, UUID toPost, String linkType, float score) {
    }
}
