package app.zcat.infochat.collector.eval.embedding;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.notifier.AdminNotificationRecord;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.llm.EmbeddingProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the pgvector literal-rejection behavior of {@link EmbeddingWorker}
 * (M1-354 / opus-47 collector F5): a {@code ?::vector} literal pgvector's
 * parser rejects after it has survived the in-Java NaN/Infinity and dimension
 * guards must NOT raise {@code SQLException} out of the batch transaction on
 * every poll and re-wedge the pipeline. Instead the worker fires exactly one
 * coalesced operator alert via {@link ThrottledAdminNotifier#notifyOnce} and
 * skips the batch with no persisted row, leaving {@code embedding_done=FALSE}
 * so a later valid-vector tick auto-recovers it.
 *
 * <p>Mirrors {@link EmbeddingWorkerNonFiniteTest} — same stub provider, same
 * {@code processBatch} entry point, same seed/clear helpers — for the pgvector
 * rejection branch rather than the in-Java non-finite guard.
 *
 * <p><b>Why a test-only subclass.</b> No finite, right-dimension vector can
 * trigger a pgvector literal rejection: every finite Java float round-trips
 * through pgvector, and the dimension/non-finite guards already own the only
 * natural triggers. The {@link FormatRejectingEmbeddingWorker} alternative is
 * therefore the only way to drive the defense-in-depth coalesce branch — and it
 * does so with a REAL server-side pgvector rejection (an empty {@code []}
 * literal → SQLSTATE 22000), not a hand-thrown SQLException, so the end-to-end
 * rollback-and-skip path is exercised faithfully.
 */
@QuarkusTest
class EmbeddingWorkerPgvectorRejectionTest {

    /** The active embedding dimension under the test profile (V11 seed). */
    private static final int EXPECTED_DIMENSION = 768;

    /** fetched_at inside V11's bootstrap partition (May 2026). */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-16T10:00:00Z");

    private static final String UID_PREFIX = "embed-pgreject/";

    /**
     * A finite sentinel first-component value that {@link
     * FormatRejectingEmbeddingWorker} maps to a literal pgvector rejects. Chosen
     * so no other embedding test's vectors (all-zero, or 1/(i+1)) collide with
     * it, leaving those tests' formatVector path unchanged.
     */
    static final float REJECT_SENTINEL_COMPONENT = 0.123456f;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    EmbeddingWorker embeddingWorker;

    @Inject
    EmbeddingProvider embeddingProvider;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    private EmbeddingWorkerIT.StubEmbeddingProvider stub() {
        return (EmbeddingWorkerIT.StubEmbeddingProvider) embeddingProvider;
    }

    @BeforeEach
    void reset() throws Exception {
        stub().reset();
        clearPosts();
        clearNotification(EmbeddingWorker.ERROR_CLASS_EMBEDDING_FORMAT_REJECTED);
    }

    /**
     * A right-dimension, all-finite vector whose literal pgvector rejects is
     * caught by the new guard: the batch is skipped via one coalesced alert, no
     * embed retry fires, no row is written, and {@code embedding_done} stays
     * FALSE — without an exception escaping the batch loop.
     */
    @Test
    void pgvectorLiteralRejectionAlertsOperatorAndSkipsWithoutThrowing() throws Exception {
        SeededPost post = seedPickupReadyPost("reject");
        EmbeddingWorker.PostRow row = rowFor(post);

        stub().queueSuccess(List.of(rejectSentinelVector(EXPECTED_DIMENSION)));

        assertDoesNotThrow(
            () -> embeddingWorker.processBatch(List.of(row)),
            "a pgvector literal rejection must be skipped via a coalesced alert, not throw");

        assertEquals(1, stub().callCount(),
            "the pgvector-rejection skip must NOT retry (it is not a batch-failure case)");

        Optional<AdminNotificationRecord> state = throttledAdminNotifier.getState(
            EmbeddingWorker.ERROR_CLASS_EMBEDDING_FORMAT_REJECTED);
        assertTrue(state.isPresent(),
            "a coalesced operator alert must fire on the pgvector literal rejection");
        assertEquals(1, state.get().notificationCount(),
            "exactly one notifyOnce emission for a single rejected tick");

        assertEmbeddingDone(post.id, false);
        assertEquals(0, postEmbeddingCount(post.id),
            "no post_embedding row may be inserted on the pgvector-rejection skip path");
    }

    /**
     * The wedge fix end-to-end: a rejected literal skips with one alert and no
     * write, then a subsequent tick with a valid vector completes the embedding
     * (the idempotent pickup auto-recovers once the provider output normalizes).
     */
    @Test
    void rejectionSkipsThenValidVectorAutoRecovers() throws Exception {
        SeededPost post = seedPickupReadyPost("recover");
        EmbeddingWorker.PostRow row = rowFor(post);

        // Tick 1: provider returns a vector whose literal pgvector rejects. The
        // worker skips before persisting anything (the transaction rolls back).
        stub().queueSuccess(List.of(rejectSentinelVector(EXPECTED_DIMENSION)));
        embeddingWorker.processBatch(List.of(row));

        Optional<AdminNotificationRecord> state = throttledAdminNotifier.getState(
            EmbeddingWorker.ERROR_CLASS_EMBEDDING_FORMAT_REJECTED);
        assertTrue(state.isPresent() && state.get().notificationCount() == 1,
            "the rejected tick must fire exactly one coalesced operator alert");
        assertEmbeddingDone(post.id, false);
        assertEquals(0, postEmbeddingCount(post.id),
            "the rejected tick must write no post_embedding row");

        // Tick 2: provider output has normalized to a valid vector. The same
        // batch is re-processed and the embedding completes.
        stub().queueSuccess(List.of(finiteVector(EXPECTED_DIMENSION)));
        embeddingWorker.processBatch(List.of(row));

        assertEmbeddingDone(post.id, true);
        assertEquals(1, postEmbeddingCount(post.id),
            "the valid recovery tick must insert exactly one post_embedding row");
    }

    // ---------- helpers ----------

    /** A finite vector with distinct non-zero components (1, 1/2, 1/3, ...). */
    private static float[] finiteVector(int dimension) {
        float[] v = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            v[i] = 1.0f / (i + 1);
        }
        return v;
    }

    /** A finite vector carrying {@link #REJECT_SENTINEL_COMPONENT} at index 0. */
    private static float[] rejectSentinelVector(int dimension) {
        float[] v = finiteVector(dimension);
        v[0] = REJECT_SENTINEL_COMPONENT;
        return v;
    }

    private EmbeddingWorker.PostRow rowFor(SeededPost post) {
        return new EmbeddingWorker.PostRow(
            post.id, post.fetchedAt, "pgreject title " + post.uid, "pgreject body " + post.uid, null);
    }

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
                     + ") RETURNING id, fetched_at")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, "embed-pgreject-upstream-" + slug);
            ps.setString(4, "Embed pgreject title " + slug);
            ps.setString(5, "Embed pgreject body for slug " + slug);
            ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
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
            ps.setString(1, "https://embed-pgreject.example.test/" + slug + "/feed.xml");
            ps.setString(2, "Embed pgreject source " + slug);
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

    private void clearNotification(String notificationKey) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM admin_notification_state WHERE notification_key = ?")) {
            ps.setString(1, notificationKey);
            ps.executeUpdate();
        }
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

    private record SeededPost(UUID id, String uid, Instant fetchedAt) {
    }

    /**
     * Test-scoped {@link EmbeddingWorker} that overrides {@link
     * EmbeddingWorker#formatVector} to inject a literal pgvector's parser
     * rejects — an empty {@code []} vector → "vector must have at least 1
     * dimension" (SQLSTATE 22000) — whenever the input vector carries {@link
     * #REJECT_SENTINEL_COMPONENT} at index 0. Selected over the production bean
     * by {@code @Alternative @Priority(Integer.MAX_VALUE)}; every other vector
     * delegates to {@code super.formatVector}, so all other embedding tests see
     * the unmodified format path.
     */
    @Alternative
    @Priority(Integer.MAX_VALUE)
    @ApplicationScoped
    public static class FormatRejectingEmbeddingWorker extends EmbeddingWorker {

        @Override
        PGobject formatVector(float[] vector) throws SQLException {
            if (vector.length > 0 && vector[0] == REJECT_SENTINEL_COMPONENT) {
                PGobject malformed = new PGobject();
                malformed.setType("vector");
                malformed.setValue("[]");
                return malformed;
            }
            return super.formatVector(vector);
        }
    }
}
