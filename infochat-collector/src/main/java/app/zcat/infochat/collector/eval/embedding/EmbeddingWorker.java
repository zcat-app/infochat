package app.zcat.infochat.collector.eval.embedding;

import app.zcat.infochat.collector.eval.TransactionHelper;
import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.postgresql.util.PGobject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Collector-side scheduled poller that runs Stage 4 of the eval
 * pipeline (vector embedding). Sits between Stage 3 (Tagger, M1-034a)
 * and Stage 5 ({@link app.zcat.infochat.collector.eval.ready.ReadyPromoter}).
 *
 * <h2>Pickup criteria</h2>
 *
 * <p>{@code status='RAW' AND tagger_done=TRUE AND embedding_done=FALSE}.
 * Quarantined posts are mechanically excluded by the {@code status='RAW'}
 * filter (Stage 2 INJECTION/MALWARE/UNKNOWN and Stage 1 watchdog
 * fail-closed both write {@code status='QUARANTINED'}). Posts on the
 * release-on-stage2-failure infra-failure path have
 * {@code status='RAW' AND stage2_failed=TRUE} and ARE picked up — the
 * infra-failure path still needs an embedding to become user-facing.
 *
 * <h2>One-failure-fails-batch retry</h2>
 *
 * <p>Per {@code docs/spec/llm.md} §Embedding pipeline ("If the provider
 * returns a batch result of the wrong shape, an exception, or any
 * per-element error the Collector cannot map back to a specific post,
 * the entire batch retries once") AND §Failure handling (recap)
 * ("Retry policy: on a batch failure the same batch is resubmitted
 * as-is; the batch is not split on retry"):
 *
 * <ul>
 *   <li>Any exception from {@link EmbeddingProvider#embed(List)} OR a
 *       response whose size does NOT equal the input size triggers ONE
 *       retry with the IDENTICAL input list.</li>
 *   <li>On second failure: every post in the batch advances
 *       {@code embedding_done=TRUE} WITHOUT a corresponding
 *       {@code post_embedding} row — the no-vector release path. The
 *       WARN log line carries the canonical
 *       {@code error_class='embedding.batch_failure'} for the future
 *       T2-G throttled admin notifier.</li>
 * </ul>
 *
 * <h2>Per-vector dimensionality fatal</h2>
 *
 * <p>Per {@code docs/spec/llm.md} §Embedding pipeline ("Dimensionality
 * mismatch at runtime is fatal. Storing vectors of mixed dimensions
 * in the same pgvector column silently corrupts cosine similarity
 * scores. The only safe recovery is a full re-embed"): if any returned
 * vector's length differs from {@code embedding_metadata.dimension}
 * (cached at @PostConstruct after the {@link EmbeddingMetadataStartupGuard}
 * has validated the singleton), this worker throws
 * {@link IllegalStateException} immediately — NOT a batch-failure
 * retry, but a metadata-invariant violation. The throw unwinds the
 * narrow {@link TransactionHelper#inTransaction} boundary so no
 * {@code post_embedding} rows are inserted and {@code embedding_done}
 * stays {@code FALSE} for every post in the batch. The operator runs
 * the re-embed procedure ({@code docs/design/02-schema.md} §2.8).
 *
 * <h2>Persistence cursor</h2>
 *
 * <p>Per Invariant 5 ({@code docs/spec/schema.md} §Invariants — "the
 * per-stage flags are the durable cursor"), {@code embedding_done=TRUE}
 * is the cursor advance for the Embedding boundary. The INSERT of the
 * {@code post_embedding} row AND the UPDATE of {@code embedding_done}
 * happen inside the same {@link TransactionHelper#inTransaction}
 * boundary so a crash between them is rolled back (the next tick
 * re-picks the same posts).
 *
 * <h2>{@code embedding_model} column</h2>
 *
 * <p>The {@code post_embedding.embedding_model} value is read from
 * {@code embedding_metadata.model_identifier} via {@link EmbeddingMetadataDao}
 * — the canonical record per the model identity guard, NOT the
 * provider's reported value (the spec does not surface a per-call
 * model-identifier on the SPI). The metadata is immutable across the
 * JVM lifetime (operator-override rotation runs at @Priority(125)
 * BEFORE this @ApplicationScoped bean's @PostConstruct), so caching
 * once at init is correct.
 */
@ApplicationScoped
public class EmbeddingWorker {

    /** Canonical error class emitted on the no-vector release path. */
    public static final String ERROR_CLASS_EMBEDDING_BATCH_FAILURE = "embedding.batch_failure";

    private static final Logger LOG = Logger.getLogger(EmbeddingWorker.class);

    /**
     * The {@code title + "\n\n" + (body_summary OR first 800 chars of
     * body)} truncation point per
     * {@code docs/design/05-llm-and-embeddings.md} §5.5 step 1. Chosen
     * to bound the input length for tiny local-Ollama embed models that
     * cap at ~512 tokens; longer bodies fall back to the first 800
     * characters as the salient lead.
     */
    static final int BODY_FALLBACK_PREFIX_CHARS = 800;

    @Inject
    DataSource dataSource;

    @Inject
    EmbeddingProvider embeddingProvider;

    @Inject
    EmbeddingMetadataDao metadataDao;

    @ConfigProperty(name = "infochat.embeddings.batch-size")
    int batchSize;

    @ConfigProperty(name = "infochat.embeddings.max-concurrency")
    int maxConcurrency;

    private Semaphore concurrencyPermits;
    private String cachedModelIdentifier;
    private int cachedDimension;

    @PostConstruct
    void init() {
        if (batchSize < 1) {
            throw new IllegalStateException(
                "EmbeddingWorker: infochat.embeddings.batch-size must be >= 1; got " + batchSize);
        }
        if (maxConcurrency < 1) {
            throw new IllegalStateException(
                "EmbeddingWorker: infochat.embeddings.max-concurrency must be >= 1; got " + maxConcurrency);
        }
        this.concurrencyPermits = new Semaphore(maxConcurrency);
        EmbeddingMetadataDao.Metadata meta = metadataDao.readSingleton().orElseThrow(
            () -> new IllegalStateException(
                "EmbeddingWorker: embedding_metadata is empty at @PostConstruct; "
                    + "EmbeddingMetadataStartupGuard should have refused startup earlier"));
        this.cachedModelIdentifier = meta.modelIdentifier();
        this.cachedDimension = meta.dimension();
    }

    /**
     * Scheduled tick. Pulls up to {@link #batchSize} pending posts and
     * processes them in one batch. The single-batch-per-tick shape is
     * the simplest equivalent to the design's "flush when full or on
     * profile-driven timer" — each tick is a flush.
     */
    @Scheduled(every = "{infochat.embeddings.poll-interval}")
    public void onTick() {
        List<PostRow> pending;
        try {
            pending = enumeratePending(batchSize);
        } catch (SQLException e) {
            LOG.warn("EmbeddingWorker: failed to enumerate pending posts; skipping tick", e);
            return;
        }
        if (pending.isEmpty()) {
            return;
        }
        processBatch(pending);
    }

    /**
     * Process one batch. Public/non-static so the IT can invoke it
     * directly with a hand-rolled batch (the scheduler is halted in
     * the test profile per M1-034a's
     * {@code %test.quarkus.scheduler.start-mode=halted}).
     *
     * <p>The transaction boundary is deliberately narrow: only the
     * INSERT-then-UPDATE pair runs inside
     * {@link TransactionHelper#inTransaction} so the semaphore
     * acquisition and the outbound embedding HTTP call do not hold
     * a connection idle-in-transaction. A per-vector dimensionality
     * mismatch throws BEFORE any INSERT or UPDATE so the rollback is
     * a no-op against the on-disk state; the post stays
     * {@code embedding_done=FALSE} and the next tick re-picks it (or
     * the operator runs the re-embed procedure).
     */
    public void processBatch(List<PostRow> batch) {
        try {
            concurrencyPermits.acquire();
        } catch (InterruptedException e) {
            // Interrupted while parked waiting for a permit — the JVM is
            // shutting down. Restore the interrupt flag (acquire() clears it)
            // so the scheduler/executor still observes the shutdown request,
            // and abandon this batch: the posts stay embedding_done=FALSE and
            // the next tick after restart re-picks them (the pickup query is
            // idempotent). Swallowing the interrupt here would hinder shutdown.
            Thread.currentThread().interrupt();
            return;
        }
        try {
            List<String> inputs = new ArrayList<>(batch.size());
            for (PostRow row : batch) {
                inputs.add(buildInputText(row));
            }

            AttemptResult attempt = attemptEmbed(inputs, /* attempt */ 1);
            if (!attempt.success()) {
                AttemptResult retry = attemptEmbed(inputs, /* attempt */ 2);
                if (!retry.success()) {
                    // Two consecutive batch failures → no-vector
                    // release path. Every post advances
                    // embedding_done=TRUE so the ReadyPromoter can
                    // pick them up; no post_embedding row is
                    // inserted. The WARN line names the canonical
                    // error_class for the future T2-G notifier.
                    LOG.warnf(
                        "EmbeddingWorker: batch of %d posts failed embed twice; releasing without vectors "
                            + "(error_class=%s; first=%s second=%s)",
                        batch.size(), ERROR_CLASS_EMBEDDING_BATCH_FAILURE,
                        attempt.failureReason(), retry.failureReason());
                    TransactionHelper.inTransaction(dataSource, "EmbeddingWorker", conn ->
                        advanceEmbeddingDoneOnly(conn, batch));
                    return;
                }
                attempt = retry;
            }

            // Per-vector dimensionality fatal per
            // docs/spec/llm.md §Embedding pipeline. Validate ALL
            // vectors before any INSERT so the throw unwinds cleanly
            // with no partial state.
            List<EmbeddingResult> results = attempt.results();
            for (int i = 0; i < results.size(); i++) {
                int actualDimension = results.get(i).vector().length;
                if (actualDimension != cachedDimension) {
                    throw new IllegalStateException(
                        "EmbeddingWorker: per-vector dimensionality mismatch at batch index " + i
                            + " for post_id=" + batch.get(i).id()
                            + "; expected=" + cachedDimension + " actual=" + actualDimension
                            + ". Run the re-embed procedure ("
                            + EmbeddingMetadataStartupGuard.REEMBED_PROCEDURE_PATH + ").");
                }
            }

            TransactionHelper.inTransaction(dataSource, "EmbeddingWorker", conn -> {
                insertEmbeddingRows(conn, batch, results);
                advanceEmbeddingDoneOnly(conn, batch);
            });
        } finally {
            concurrencyPermits.release();
        }
    }

    private AttemptResult attemptEmbed(List<String> inputs, int attempt) {
        List<EmbeddingResult> results;
        try {
            results = embeddingProvider.embed(inputs);
        } catch (RuntimeException e) {
            LOG.warnf(e, "EmbeddingWorker: embed call attempt %d threw", attempt);
            return AttemptResult.failure("exception: " + e.getClass().getSimpleName());
        }
        if (results.size() != inputs.size()) {
            // Wrong-shape response: per spec, "any per-element error
            // the Collector cannot map back to a specific post"
            // triggers the same one-failure-fails-batch retry path.
            LOG.warnf(
                "EmbeddingWorker: embed call attempt %d returned wrong shape (expected %d got %d)",
                attempt, inputs.size(), results.size());
            return AttemptResult.failure("wrong-shape: expected=" + inputs.size() + " got=" + results.size());
        }
        return AttemptResult.success(results);
    }

    /**
     * Build the embedding input per
     * {@code docs/design/05-llm-and-embeddings.md} §5.5 step 1:
     * {@code title + "\n\n" + (body_summary OR first 800 chars of body)}.
     * A null/empty title contributes the empty string but the
     * {@code "\n\n"} separator stays so the body section starts on its
     * own line — the embedding model treats blank-prefixed inputs the
     * same as the same body without a leading title.
     */
    static String buildInputText(PostRow row) {
        String title = row.title() == null ? "" : row.title();
        String body;
        if (row.bodySummary() != null && !row.bodySummary().isEmpty()) {
            body = row.bodySummary();
        } else if (row.body() != null) {
            body = row.body().length() > BODY_FALLBACK_PREFIX_CHARS
                ? row.body().substring(0, BODY_FALLBACK_PREFIX_CHARS)
                : row.body();
        } else {
            body = "";
        }
        return title + "\n\n" + body;
    }

    /**
     * INSERT one row per (post, vector). {@code embedding_model} is
     * the cached metadata identifier — the canonical record per the
     * model identity guard.
     */
    private void insertEmbeddingRows(Connection conn, List<PostRow> batch,
                                     List<EmbeddingResult> results) throws SQLException {
        final String sql =
            "INSERT INTO post_embedding (post_id, embedding, embedding_model, fetched_at) "
                + "VALUES (?, ?::vector, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < batch.size(); i++) {
                PostRow row = batch.get(i);
                ps.setObject(1, row.id());
                ps.setObject(2, formatVector(results.get(i).vector()));
                ps.setString(3, cachedModelIdentifier);
                ps.setTimestamp(4, Timestamp.from(row.fetchedAt()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Advance {@code embedding_done=TRUE} for every post in the batch.
     * Called in two places: the success path (after
     * {@link #insertEmbeddingRows}) AND the no-vector release path
     * (after a second batch failure). The (id, fetched_at) WHERE
     * clause matches the partitioned-PK shape so the UPDATE plans on
     * the right partition.
     */
    private void advanceEmbeddingDoneOnly(Connection conn, List<PostRow> batch) throws SQLException {
        final String sql =
            "UPDATE post SET embedding_done = TRUE WHERE id = ? AND fetched_at = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (PostRow row : batch) {
                ps.setObject(1, row.id());
                ps.setTimestamp(2, Timestamp.from(row.fetchedAt()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Enumerate the next batch of pending posts. The pickup filter
     * excludes quarantined posts ({@code status='RAW'} is the
     * load-bearing column) and already-embedded posts
     * ({@code embedding_done=FALSE}). The ORDER BY makes the pickup
     * deterministic.
     */
    List<PostRow> enumeratePending(int limit) throws SQLException {
        final String sql =
            "SELECT id, fetched_at, title, body, body_summary "
                + "  FROM post "
                + " WHERE status = 'RAW' "
                + "   AND tagger_done = TRUE "
                + "   AND embedding_done = FALSE "
                + " ORDER BY fetched_at, id "
                + " LIMIT ?";
        List<PostRow> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = (UUID) rs.getObject(1);
                    Instant fetchedAt = rs.getTimestamp(2).toInstant();
                    String title = rs.getString(3);
                    String body = rs.getString(4);
                    String bodySummary = rs.getString(5);
                    rows.add(new PostRow(id, fetchedAt, title, body, bodySummary));
                }
            }
        }
        return rows;
    }

    /**
     * pgvector accepts a string literal like {@code "[0.1,0.2,...]"}
     * cast via {@code ?::vector} in the INSERT statement. A PGobject
     * with type {@code "vector"} makes the cast explicit at JDBC
     * binding time. The {@link Float#toString} per element preserves
     * the float's exact textual round-trip.
     */
    private static PGobject formatVector(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(vector[i]));
        }
        sb.append(']');
        PGobject pg = new PGobject();
        try {
            pg.setType("vector");
            pg.setValue(sb.toString());
        } catch (SQLException e) {
            throw new IllegalStateException("EmbeddingWorker: PGobject formatting failed", e);
        }
        return pg;
    }

    /** One pending post, populated by {@link #enumeratePending}. */
    public record PostRow(UUID id, Instant fetchedAt, String title, String body, String bodySummary) {
    }

    /**
     * Outcome of one {@link #attemptEmbed} call: either a successful
     * results list or a structured failure description for the second
     * attempt's log line.
     */
    private record AttemptResult(boolean success, List<EmbeddingResult> results, String failureReason) {
        static AttemptResult success(List<EmbeddingResult> results) {
            return new AttemptResult(true, results, null);
        }

        static AttemptResult failure(String reason) {
            return new AttemptResult(false, List.of(), reason);
        }
    }
}
