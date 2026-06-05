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
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for kind-6 repost edge <em>resolution</em> — the
 * architecture.md §Ingest SPIs promise that the repost link, written as
 * "(kind-6 post UID) →repost→ (original upstream_identifier)", is
 * "resolved to a post UID if and when the original event is also seen
 * and stored". Both arrival orders are covered:
 *
 * <ul>
 *   <li>{@link #repostThenOriginalResolves} — the kind-6 arrives first
 *     (edge starts unresolved), the original kind-1 arrives second; the
 *     Registrar-side {@link RepostEdgeResolver#resolveEdgesPointingTo}
 *     sweep flips the edge's {@code to_post} to the original's
 *     {@code post.id}.</li>
 *   <li>{@link #originalThenRepostResolves} — the original kind-1
 *     arrives first, the kind-6 second; the handler-side
 *     {@link RepostEdgeResolver#findNostrOriginalPostId} lookup resolves
 *     the edge at write time.</li>
 * </ul>
 *
 * <p>Events are driven through an inline verbatim copy of the
 * production Registrar deliver lambda, exactly as {@link Kind6LinkingIT}
 * does (see its class javadoc for why the copy, not the production
 * lambda, is invoked). Event ids are prefix-scoped strings rather than
 * 64-hex digests so the per-test cleanup can find every row this class
 * seeds without disturbing parallel Nostr ITs.</p>
 */
@QuarkusTest
class Kind6RepostResolutionIT {

    /** Pinned fetched_at inside V7/V29 bootstrap partitions (May 2026). */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-22T10:00:00Z");

    private static final String UPSTREAM_PREFIX = "kind6-resolution-it/";

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
    RepostEdgeResolver repostEdgeResolver;

    @Inject
    TestEvalQueueConsumer evalConsumer;

    @BeforeEach
    void reset() throws Exception {
        clearTestData();
        evalConsumer.drain();
    }

    /**
     * Repost-first arrival order: the kind-6 edge starts unresolved
     * (verbatim to_upstream_identifier, NULL to_post); ingesting the
     * original kind-1 event afterwards resolves the edge's to_post to
     * the original's post.id via the Registrar-side resolver sweep.
     */
    @Test
    void repostThenOriginalResolves() throws Exception {
        UUID sourceUuid = seedNostrSource(UPSTREAM_PREFIX + "source-repost-first");
        String originalEventId = UPSTREAM_PREFIX + "original-repost-first";
        Consumer<NormalizedPost> deliver = deliverLambda(sourceUuid);

        deliver.accept(kind6Event(UPSTREAM_PREFIX + "repost-1", originalEventId)
                .toNormalizedPost(0L, FETCHED_AT));

        UUID repostPostId = lookupPostId(sourceUuid, UPSTREAM_PREFIX + "repost-1");
        assertNotNull(repostPostId, "the kind-6 event produced a post row");
        RepostEdge unresolved = querySingleRepostEdge(repostPostId);
        assertEquals(originalEventId, unresolved.toUpstreamIdentifier(),
                "to_upstream_identifier stores the original event id verbatim");
        assertNull(unresolved.toPost(),
                "to_post is NULL while the original event is not yet stored");

        deliver.accept(kind1Event(originalEventId).toNormalizedPost(0L, FETCHED_AT));

        UUID originalPostId = lookupPostId(sourceUuid, originalEventId);
        assertNotNull(originalPostId, "the original kind-1 event produced a post row");
        RepostEdge resolved = querySingleRepostEdge(repostPostId);
        assertEquals(originalPostId, resolved.toPost(),
                "the edge's to_post resolved to the original's post.id");
    }

    /**
     * Original-first arrival order: the original kind-1 is already
     * stored when the kind-6 arrives, so the handler-side lookup
     * resolves the edge at write time.
     */
    @Test
    void originalThenRepostResolves() throws Exception {
        UUID sourceUuid = seedNostrSource(UPSTREAM_PREFIX + "source-original-first");
        String originalEventId = UPSTREAM_PREFIX + "original-original-first";
        Consumer<NormalizedPost> deliver = deliverLambda(sourceUuid);

        deliver.accept(kind1Event(originalEventId).toNormalizedPost(0L, FETCHED_AT));
        UUID originalPostId = lookupPostId(sourceUuid, originalEventId);
        assertNotNull(originalPostId, "the original kind-1 event produced a post row");

        deliver.accept(kind6Event(UPSTREAM_PREFIX + "repost-2", originalEventId)
                .toNormalizedPost(0L, FETCHED_AT));

        UUID repostPostId = lookupPostId(sourceUuid, UPSTREAM_PREFIX + "repost-2");
        assertNotNull(repostPostId, "the kind-6 event produced a post row");
        RepostEdge resolved = querySingleRepostEdge(repostPostId);
        assertEquals(originalEventId, resolved.toUpstreamIdentifier(),
                "to_upstream_identifier stores the original event id verbatim");
        assertEquals(originalPostId, resolved.toPost(),
                "the edge's to_post resolved to the original's post.id");
    }

    // ---------- helpers ----------

    /**
     * Verbatim copy of the Registrar's deliver lambda from
     * NostrStreamSource.Registrar.registerNostrSources (same rationale
     * as Kind6LinkingIT's copy).
     */
    private Consumer<NormalizedPost> deliverLambda(UUID sourceUuid) {
        return p -> {
            if ("6".equals(p.rawMetadata().get(NostrEvent.META_KIND))) {
                kind6Handler.handle(p, sourceUuid).ifPresent(key ->
                        repostEdgeResolver.resolveEdgesPointingTo(key.id(), p.upstreamIdentifier()));
            } else {
                postPersister.persist(sourceUuid, p).ifPresent(key -> {
                    evalQueueProducer.emit(key);
                    repostEdgeResolver.resolveEdgesPointingTo(key.id(), p.upstreamIdentifier());
                });
            }
        };
    }

    private NostrEvent kind6Event(String eventId, String originalEventId) {
        return new NostrEvent(
                eventId,
                "deadbeef".repeat(8),  // pubkey (any 64-char hex; not verified here)
                FETCHED_AT.getEpochSecond(),
                6,
                List.of(List.of("e", originalEventId)),
                "Resolution IT commentary",
                "00".repeat(64));  // sig (any 128-char hex; not verified here)
    }

    private NostrEvent kind1Event(String eventId) {
        return new NostrEvent(
                eventId,
                "deadbeef".repeat(8),
                FETCHED_AT.getEpochSecond(),
                1,
                List.of(),
                "Original event body",
                "00".repeat(64));
    }

    private UUID seedNostrSource(String identifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('nostr', ?, 'Kind6 resolution IT source', 'social', '{}') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            ps.setString(1, identifier);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private @Nullable UUID lookupPostId(UUID sourceUuid, String upstreamIdentifier) throws Exception {
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

    private RepostEdge querySingleRepostEdge(UUID fromPost) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT from_post, to_post, to_upstream_identifier, link_type, score "
                     + "FROM post_reference "
                     + "WHERE from_post = ? AND link_type = 'repost'")) {
            ps.setObject(1, fromPost);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected exactly one repost edge for from_post=" + fromPost);
                RepostEdge edge = new RepostEdge(
                        (UUID) rs.getObject(1),
                        (UUID) rs.getObject(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getFloat(5));
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

    private record RepostEdge(UUID fromPost, @Nullable UUID toPost,
                              String toUpstreamIdentifier, String linkType, float score) {
    }
}
