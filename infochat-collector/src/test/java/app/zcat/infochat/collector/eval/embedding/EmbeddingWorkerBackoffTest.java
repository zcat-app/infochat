package app.zcat.infochat.collector.eval.embedding;

import app.zcat.infochat.collector.eval.RetryBackoff;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.llm.EmbeddingProvider;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the sleep-before-retry backoff on {@link EmbeddingWorker}'s batch
 * retry arm (M1-485). Before this ticket the second {@code attemptEmbed}
 * fired immediately, so a transient embedding failure burned both
 * attempts back-to-back and prematurely released every post in the batch
 * with {@code embedding_done=TRUE} and no vector. The fix mirrors the
 * sibling {@link app.zcat.infochat.collector.eval.entity.EntityExtractorWorker}'s
 * UNREACHABLE arm: an exception sleeps the shared {@link RetryBackoff}
 * before the single retry; a reachable wrong-shape reply re-issues
 * immediately (RetryBackoff's documented contract).
 *
 * <h2>Counting backoff double</h2>
 *
 * <p>{@link CountingRetryBackoff} replaces the real {@link RetryBackoff}
 * via {@link QuarkusMock#installMockForType} so the conditional can be
 * asserted by invocation count — deterministic, with no wall-clock
 * timing assertion that could flake on a loaded CI box. The real
 * {@code RetryBackoff.sleepBeforeRetry} timing is already pinned by
 * {@code EntityExtractorWorkerBackoffTest}.
 *
 * <h2>Why {@code processBatch}</h2>
 *
 * <p>{@code processBatch} is the exact per-tick work unit; calling it
 * with a hand-rolled batch of seeded posts exercises the retry path
 * deterministically without the scheduler or the non-deterministic
 * whole-table {@code onTick} pickup (same rationale as
 * {@link EmbeddingWorkerDimensionMismatchTest}).
 */
@QuarkusTest
class EmbeddingWorkerBackoffTest {

    /** The active embedding dimension under the test profile (V11 seed). */
    private static final int EXPECTED_DIMENSION = 768;

    /** fetched_at inside V11's bootstrap partition (May 2026). */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-16T10:00:00Z");

    private static final String UID_PREFIX = "embed-backoff/";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    EmbeddingWorker embeddingWorker;

    @Inject
    EmbeddingProvider embeddingProvider;

    private CountingRetryBackoff countingBackoff;

    private EmbeddingWorkerIT.StubEmbeddingProvider stub() {
        return (EmbeddingWorkerIT.StubEmbeddingProvider) embeddingProvider;
    }

    @BeforeEach
    void reset() throws Exception {
        countingBackoff = new CountingRetryBackoff();
        QuarkusMock.installMockForType(countingBackoff, RetryBackoff.class);
        stub().reset();
        clearPosts();
    }

    @Test
    void exceptionFailureSleepsBackoffBeforeRetryThenReleasesVectorless() throws Exception {
        SeededPost post = seedPickupReadyPost("exception");
        // Both the first call and the retry throw: the infrastructure-shaped
        // failure arm must sleep the backoff exactly once between them, then
        // take the no-vector release path.
        stub().queueException();
        stub().queueException();

        embeddingWorker.processBatch(List.of(rowFor(post)));

        assertEquals(1, countingBackoff.sleepCalls,
            "an exception on the first attempt must sleep the backoff exactly once before the retry");
        assertEquals(2, stub().callCount(),
            "the one-failure-fails-batch retry must invoke embed twice before release");
        assertEmbeddingDone(post.id, true);
        assertEquals(0, postEmbeddingCount(post.id),
            "no post_embedding row on the no-vector release path");
    }

    @Test
    void wrongShapeFailureSkipsBackoffAndReleasesVectorless() throws Exception {
        SeededPost post = seedPickupReadyPost("wrong-shape");
        // A reachable response of the wrong shape (two vectors for a
        // one-post batch) proves the endpoint is up, so the retry must
        // re-issue immediately with NO backoff — mirroring the entity
        // stage's SCHEMA_VIOLATING arm. The release semantics are
        // unchanged: two failures still release vectorless.
        stub().queueSuccess(List.of(zeroVector(EXPECTED_DIMENSION), zeroVector(EXPECTED_DIMENSION)));
        stub().queueSuccess(List.of(zeroVector(EXPECTED_DIMENSION), zeroVector(EXPECTED_DIMENSION)));

        embeddingWorker.processBatch(List.of(rowFor(post)));

        assertEquals(0, countingBackoff.sleepCalls,
            "a reachable wrong-shape reply must NOT sleep the backoff (it re-issues immediately)");
        assertEquals(2, stub().callCount(),
            "wrong-shape failure must retry with the SAME batch");
        assertEmbeddingDone(post.id, true);
        assertEquals(0, postEmbeddingCount(post.id),
            "no post_embedding row on the no-vector release path");
    }

    // ---------- helpers ----------

    private SeededPost seedPickupReadyPost(String slug) throws Exception {
        UUID sourceId = seedRssSource(slug);
        String uid = UID_PREFIX + slug;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body, "
                     + "  fetched_at, status, "
                     + "  stage1_done, stage2_done, tagger_done, embedding_done, "
                     + "  stage1_flagged, stage2_failed, tagger_fallback, tags"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'RAW', "
                     + "  TRUE, FALSE, TRUE, FALSE, FALSE, FALSE, FALSE, '{}'"
                     + ") RETURNING id")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, "embed-backoff-upstream-" + slug);
            ps.setString(4, "Embed backoff title " + slug);
            ps.setString(5, "Embed backoff body for slug " + slug);
            ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "INSERT INTO post must yield an id");
                return new SeededPost((UUID) rs.getObject(1), uid);
            }
        }
    }

    private UUID seedRssSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', '{ai}') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            ps.setString(1, "https://embed-backoff.example.test/" + slug + "/feed.xml");
            ps.setString(2, "Embed backoff source " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void clearPosts() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM post_embedding WHERE post_id IN "
                    + "(SELECT id FROM post WHERE uid LIKE ?)")) {
                ps.setString(1, UID_PREFIX + "%");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM post WHERE uid LIKE ?")) {
                ps.setString(1, UID_PREFIX + "%");
                ps.executeUpdate();
            }
        }
    }

    private EmbeddingWorker.PostRow rowFor(SeededPost post) {
        return new EmbeddingWorker.PostRow(
            post.id, FETCHED_AT, "Embed backoff title " + post.uid,
            "Embed backoff body for " + post.uid, null);
    }

    private void assertEmbeddingDone(UUID postId, boolean expected) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT embedding_done FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "post row must exist");
                assertEquals(expected, rs.getBoolean("embedding_done"), "embedding_done");
            }
        }
    }

    private int postEmbeddingCount(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM post_embedding WHERE post_id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private static float[] zeroVector(int dimension) {
        return new float[dimension];
    }

    private record SeededPost(UUID id, String uid) {
    }

    /**
     * Counts {@link #sleepBeforeRetry()} invocations instead of sleeping,
     * so the test asserts the conditional deterministically. Overriding
     * the method (rather than calling {@code super}) avoids any real
     * wall-clock wait; {@code backoffMs} stays at its default 0 because
     * {@code @PostConstruct} does not run on this hand-constructed double.
     */
    static final class CountingRetryBackoff extends RetryBackoff {
        int sleepCalls = 0;

        @Override
        public void sleepBeforeRetry() {
            sleepCalls++;
        }
    }
}
