package app.zcat.infochat.collector.eval.embedding;

import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.annotation.Priority;
import org.jspecify.annotations.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for {@link EmbeddingWorker} covering
 * the five scenarios enumerated in M1-034b acceptance item
 * "EmbeddingWorkerIT.java is a @QuarkusTest IT".
 *
 * <h2>Stub provider</h2>
 *
 * <p>{@link StubEmbeddingProvider} is the test-scoped
 * {@code @Alternative @Priority(Integer.MAX_VALUE) @ApplicationScoped}
 * bean Quarkus ArC selects over {@code OpenAiCompatibleEmbeddingProvider}
 * for the test profile. Nested static class because this ticket's
 * {@code files_scope} permits only one new file for the EmbeddingWorker
 * IT; ArC discovers nested @ApplicationScoped beans the same way it
 * discovers top-level ones.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmbeddingWorkerIT {

    /**
     * The active embedding dimension under the test profile. V11's
     * seed row in {@code embedding_metadata} commits to 768 for the
     * laptop / vps default model, and the test profile inherits the
     * base {@code infochat.embeddings.dimension=768}.
     */
    private static final int EXPECTED_DIMENSION = 768;

    /**
     * The expected {@code post_embedding.embedding_model} value —
     * matches V11's seed and the laptop default
     * {@code infochat.embeddings.model=nomic-embed-text}.
     */
    private static final String EXPECTED_MODEL = "nomic-embed-text";

    /**
     * fetched_at must fall inside V11's bootstrap partition
     * (post_embedding_202605 covers May 2026).
     */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-16T10:00:00Z");

    @Inject
    DataSource dataSource;

    @Inject
    EmbeddingWorker embeddingWorker;

    @Inject
    EmbeddingProvider embeddingProvider;

    @Inject
    UserTransaction userTransaction;

    private StubEmbeddingProvider stub() {
        return (StubEmbeddingProvider) embeddingProvider;
    }

    @BeforeEach
    void reset() throws Exception {
        stub().reset();
        clearItPosts();
    }

    // ---------- 1. happy path ----------

    @Test
    @Order(1)
    void happyPathInsertsRowsAndAdvancesEmbeddingDone() throws Exception {
        SeededPost a = seedPickupReadyPost("embed-it-happy-a");
        SeededPost b = seedPickupReadyPost("embed-it-happy-b");

        stub().queueSuccess(List.of(zeroVector(EXPECTED_DIMENSION), oneVector(EXPECTED_DIMENSION)));

        embeddingWorker.processBatch(List.of(rowFor(a), rowFor(b)));

        assertEmbeddingDone(a.id, true);
        assertEmbeddingDone(b.id, true);
        assertEquals(1, postEmbeddingCount(a.id),
            "exactly one post_embedding row must exist for post A on success");
        assertEquals(1, postEmbeddingCount(b.id),
            "exactly one post_embedding row must exist for post B on success");
        assertEquals(EXPECTED_MODEL, readEmbeddingModel(a.id),
            "embedding_model must equal the active embedding_metadata identifier");
        assertEquals(EXPECTED_MODEL, readEmbeddingModel(b.id));
    }

    // ---------- 2. batch failure (exception, exception) → no-vector release ----------

    @Test
    @Order(2)
    void batchFailureTwiceReleasesAllPostsWithoutVectors() throws Exception {
        SeededPost a = seedPickupReadyPost("embed-it-fail-a");
        SeededPost b = seedPickupReadyPost("embed-it-fail-b");

        // First call AND retry both throw — second-failure path
        // triggers the no-vector release.
        stub().queueException();
        stub().queueException();

        embeddingWorker.processBatch(List.of(rowFor(a), rowFor(b)));

        assertEquals(2, stub().callCount(),
            "the one-failure-fails-batch retry must invoke embed twice before release");
        assertEmbeddingDone(a.id, true);
        assertEmbeddingDone(b.id, true);
        assertEquals(0, postEmbeddingCount(a.id),
            "no post_embedding row on the no-vector release path");
        assertEquals(0, postEmbeddingCount(b.id));
    }

    // ---------- 3. wrong-shape (N=1 result for N=2 inputs, twice) → no-vector release ----------

    @Test
    @Order(3)
    void wrongShapeTwiceReleasesAllPostsWithoutVectors() throws Exception {
        SeededPost a = seedPickupReadyPost("embed-it-shape-a");
        SeededPost b = seedPickupReadyPost("embed-it-shape-b");

        // The provider returns ONE result for a TWO-input call,
        // twice in a row. The worker cannot map results back to
        // posts so the whole batch retries; the retry also returns
        // wrong shape; the no-vector release fires.
        stub().queueSuccess(List.of(zeroVector(EXPECTED_DIMENSION)));
        stub().queueSuccess(List.of(zeroVector(EXPECTED_DIMENSION)));

        embeddingWorker.processBatch(List.of(rowFor(a), rowFor(b)));

        assertEquals(2, stub().callCount(),
            "wrong-shape failure must retry with the SAME batch");
        assertEmbeddingDone(a.id, true);
        assertEmbeddingDone(b.id, true);
        assertEquals(0, postEmbeddingCount(a.id));
        assertEquals(0, postEmbeddingCount(b.id));
    }

    // ---------- 4. dimensionality mismatch → fatal throw, no progress ----------

    @Test
    @Order(4)
    void dimensionMismatchThrowsImmediatelyAndLeavesPostsInFlight() throws Exception {
        SeededPost a = seedPickupReadyPost("embed-it-dim-a");
        SeededPost b = seedPickupReadyPost("embed-it-dim-b");

        // Provider returns the correct COUNT (2 of 2) but each
        // vector has the WRONG dimension (384 instead of 768).
        // The worker treats this as a metadata-invariant violation,
        // not a batch-failure-retry case — throws immediately.
        stub().queueSuccess(List.of(zeroVector(384), oneVector(384)));

        assertThrows(IllegalStateException.class,
            () -> embeddingWorker.processBatch(List.of(rowFor(a), rowFor(b))),
            "dim mismatch must throw IllegalStateException synchronously");

        assertEquals(1, stub().callCount(),
            "dim mismatch must NOT retry (it is not a batch-failure case)");
        // Throw lands before the narrow transaction starts: no DB
        // writes executed, so embedding_done stays FALSE and the
        // post stays in-flight for the operator's re-embed procedure.
        assertEmbeddingDone(a.id, false);
        assertEmbeddingDone(b.id, false);
        assertEquals(0, postEmbeddingCount(a.id));
        assertEquals(0, postEmbeddingCount(b.id));
    }

    // ---------- 5. pre-promotion boundary — already-embedded post NOT picked up ----------

    @Test
    @Order(5)
    void postAlreadyEmbeddedIsNotPickedUpByEnumeratePending() throws Exception {
        // A post that has cleared the Embedding boundary (status='RAW'
        // AND tagger_done=true AND embedding_done=true) is downstream
        // of EmbeddingWorker's responsibility — the ReadyPromoter is
        // the next stage. EmbeddingWorker.enumeratePending must not
        // return it.
        SeededPost already = seedAlreadyEmbeddedPost("embed-it-already");
        // Sanity: a fresh pickup-ready post IS returned, so the
        // negative assertion is meaningful (not a coincidence of an
        // empty pending list).
        SeededPost fresh = seedPickupReadyPost("embed-it-fresh");

        List<EmbeddingWorker.PostRow> pending = embeddingWorker.enumeratePending(10);

        boolean foundAlready = pending.stream().anyMatch(r -> r.id().equals(already.id));
        assertFalse(foundAlready,
            "post with embedding_done=true must NOT appear in EmbeddingWorker pickup");
        boolean foundFresh = pending.stream().anyMatch(r -> r.id().equals(fresh.id));
        assertTrue(foundFresh,
            "fresh tagger_done=true / embedding_done=false post MUST appear in pickup");
        assertEquals(0, stub().callCount(),
            "enumeratePending must not invoke the provider");
    }

    // ---------- 6. transaction does not span the embed HTTP call ----------

    @Test
    @Order(6)
    void transactionDoesNotSpanHttpCall() throws Exception {
        SeededPost a = seedPickupReadyPost("embed-it-txn-a");

        AtomicInteger jtaStatusDuringEmbed = new AtomicInteger(-1);
        stub().setEmbedCallback(() -> {
            try {
                jtaStatusDuringEmbed.set(userTransaction.getStatus());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        stub().queueSuccess(List.of(zeroVector(EXPECTED_DIMENSION)));

        embeddingWorker.processBatch(List.of(rowFor(a)));

        assertEquals(Status.STATUS_NO_TRANSACTION, jtaStatusDuringEmbed.get(),
            "no JTA transaction should be active during the embedding HTTP call");
        assertEmbeddingDone(a.id, true);
        assertEquals(1, postEmbeddingCount(a.id));
    }

    // ---------- helpers ----------

    private SeededPost seedPickupReadyPost(String slug) throws Exception {
        return seedPost(slug, /* taggerDone */ true, /* embeddingDone */ false, "RAW");
    }

    private SeededPost seedAlreadyEmbeddedPost(String slug) throws Exception {
        return seedPost(slug, /* taggerDone */ true, /* embeddingDone */ true, "RAW");
    }

    private SeededPost seedPost(String slug, boolean taggerDone, boolean embeddingDone, String status)
            throws Exception {
        UUID sourceId = seedRssSource(slug);
        String uid = "embed-it/" + slug;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body, "
                     + "  fetched_at, status, "
                     + "  stage1_done, stage2_done, tagger_done, embedding_done, "
                     + "  stage1_flagged, stage2_failed, tagger_fallback, tags"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, "
                     + "  TRUE, FALSE, ?, ?, FALSE, FALSE, FALSE, '{}'"
                     + ") RETURNING id, fetched_at")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, "embed-it-upstream-" + slug);
            ps.setString(4, "Embed IT title " + slug);
            ps.setString(5, "Embed IT body for slug " + slug);
            ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
            ps.setString(7, status);
            ps.setBoolean(8, taggerDone);
            ps.setBoolean(9, embeddingDone);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "INSERT INTO post must yield an id");
                UUID id = (UUID) rs.getObject(1);
                Instant fetchedAt = rs.getTimestamp(2).toInstant();
                return new SeededPost(id, uid, fetchedAt);
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
            ps.setString(1, "https://embed-it.example.test/" + slug + "/feed.xml");
            ps.setString(2, "Embed IT source " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void clearItPosts() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM post_embedding WHERE post_id IN "
                    + "(SELECT id FROM post WHERE uid LIKE 'embed-it/%')")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM post WHERE uid LIKE 'embed-it/%'")) {
                ps.executeUpdate();
            }
        }
    }

    private EmbeddingWorker.PostRow rowFor(SeededPost post) {
        return new EmbeddingWorker.PostRow(
            post.id, post.fetchedAt,
            "Embed IT title " + post.uid,
            "Embed IT body for " + post.uid,
            null);
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

    private String readEmbeddingModel(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT embedding_model FROM post_embedding WHERE post_id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "post_embedding row must exist");
                return rs.getString(1);
            }
        }
    }

    private static float[] zeroVector(int dimension) {
        return new float[dimension];
    }

    private static float[] oneVector(int dimension) {
        float[] v = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            v[i] = 1.0f / (i + 1);
        }
        return v;
    }

    private record SeededPost(UUID id, String uid, Instant fetchedAt) {
    }

    /**
     * Test-scoped {@link EmbeddingProvider} replacing the production
     * {@code OpenAiCompatibleEmbeddingProvider} for every
     * {@code @QuarkusTest} in this module that needs deterministic
     * embed results. FIFO queue of either canned vector lists or
     * pre-recorded exceptions; the IT's {@code @BeforeEach} must
     * call {@link #reset()} so per-test state is isolated.
     */
    @Alternative
    @Priority(Integer.MAX_VALUE)
    @ApplicationScoped
    public static class StubEmbeddingProvider implements EmbeddingProvider {

        private final Deque<Response> queue = new ArrayDeque<>();
        private int callCount = 0;
        private Runnable embedCallback;

        public void reset() {
            queue.clear();
            callCount = 0;
            embedCallback = null;
        }

        public void setEmbedCallback(@Nullable Runnable callback) {
            this.embedCallback = callback;
        }

        public int callCount() {
            return callCount;
        }

        public void queueSuccess(List<float[]> vectors) {
            queue.add(new SuccessResponse(vectors));
        }

        public void queueException() {
            queue.add(new ExceptionResponse());
        }

        @Override
        public List<EmbeddingResult> embed(List<String> texts) {
            callCount++;
            if (embedCallback != null) {
                embedCallback.run();
            }
            Response r = queue.pollFirst();
            if (r == null) {
                throw new RuntimeException(
                    "StubEmbeddingProvider: no queued response for call #" + callCount);
            }
            return r.materialize();
        }

        private sealed interface Response {
            List<EmbeddingResult> materialize();
        }

        private record SuccessResponse(List<float[]> vectors) implements Response {
            @Override
            public List<EmbeddingResult> materialize() {
                return vectors.stream().map(EmbeddingResult::new).toList();
            }
        }

        private record ExceptionResponse() implements Response {
            @Override
            public List<EmbeddingResult> materialize() {
                throw new RuntimeException("StubEmbeddingProvider: simulated embed failure");
            }
        }
    }
}
