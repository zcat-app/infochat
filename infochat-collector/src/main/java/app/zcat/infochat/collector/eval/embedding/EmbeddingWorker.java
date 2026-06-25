package app.zcat.infochat.collector.eval.embedding;

import app.zcat.infochat.collector.eval.PartitionScan;
import app.zcat.infochat.collector.eval.TransactionHelper;
import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
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
 * <h2>Per-vector dimensionality mismatch — operator alert + skip</h2>
 *
 * <p>Per {@code docs/spec/llm.md} §Embedding pipeline ("Dimensionality
 * mismatch at runtime is fatal. Storing vectors of mixed dimensions
 * in the same pgvector column silently corrupts cosine similarity
 * scores. The only safe recovery is a full re-embed"): if any returned
 * vector's length differs from {@code embedding_metadata.dimension}
 * (cached at @PostConstruct after the {@link EmbeddingMetadataStartupGuard}
 * has validated the singleton), this worker treats it as an
 * operator-action-required metadata-invariant violation — NOT a
 * batch-failure retry. It fires ONE coalesced operator alert via
 * {@link ThrottledAdminNotifier#notifyOnce} (keyed on
 * {@link #ERROR_CLASS_EMBEDDING_DIMENSION_MISMATCH}, so the repeated
 * per-poll detection collapses to a single notification per throttle
 * window) and skips the batch by returning BEFORE any INSERT or
 * UPDATE. No {@code post_embedding} row is written and
 * {@code embedding_done} stays {@code FALSE} for every post in the
 * batch; the pipeline soft-stalls (affected posts wait) and resumes
 * automatically once the operator runs the re-embed procedure
 * ({@code docs/design/02-schema.md} §2.8). Returning instead of
 * throwing stops the stack-trace-per-poll loop a throw would cause —
 * the idempotent pickup query re-selects the same wedged batch on
 * every tick, so a throw would log a fresh stack trace forever while
 * no operator is told.
 *
 * <h2>Non-finite vector component — operator alert + skip (M1-327)</h2>
 *
 * <p>A right-length vector still wedges the pipeline if any component is
 * {@code Float.NaN} or {@code ±Infinity}: pgvector rejects those literals,
 * so {@link #formatVector}'s {@code ?::vector} cast throws {@code SQLException}
 * out of the transaction, and the idempotent pickup re-selects the same
 * dimension-check-passing batch on every tick — a silent stack-trace-per-poll
 * wedge with no operator alert. This worker handles it with the SAME
 * notify-once + skip shape as the dimension-mismatch path above: it fires ONE
 * coalesced alert keyed on {@link #ERROR_CLASS_EMBEDDING_NONFINITE} and returns
 * BEFORE any INSERT or UPDATE, so {@code embedding_done} stays {@code FALSE} and
 * the affected post resumes automatically once the provider emits a finite
 * vector on a later tick (a buggy/compromised remote provider, transport
 * corruption, or normalization underflow can all produce a non-finite
 * component).
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

    /**
     * Canonical error class for the per-vector dimensionality mismatch
     * operator alert. Used as both the {@code notifyOnce} coalescing
     * key and the {@code error_class} so repeated per-poll detections
     * collapse to one notification per throttle window.
     */
    public static final String ERROR_CLASS_EMBEDDING_DIMENSION_MISMATCH = "embedding.dimension_mismatch";

    /**
     * Canonical error class for the non-finite vector component operator
     * alert. Used as both the {@code notifyOnce} coalescing key and the
     * {@code error_class} so repeated per-poll detections collapse to one
     * notification per throttle window — same shape as
     * {@link #ERROR_CLASS_EMBEDDING_DIMENSION_MISMATCH}.
     */
    public static final String ERROR_CLASS_EMBEDDING_NONFINITE = "embedding.nonfinite";

    /**
     * Canonical error class for a pgvector parser rejection of the
     * {@code ?::vector} literal that survives the in-Java NaN/Infinity and
     * dimension guards. Used as both the {@code notifyOnce} coalescing key and
     * the {@code error_class} so repeated per-poll rejections collapse to one
     * notification per throttle window — same shape as
     * {@link #ERROR_CLASS_EMBEDDING_NONFINITE} (M1-354 / opus-47 collector F5).
     */
    public static final String ERROR_CLASS_EMBEDDING_FORMAT_REJECTED = "embedding.format_rejected";

    /**
     * SQLState class (first two chars) pgvector raises for every literal-parser
     * rejection: {@code 22000} (bad/empty/wrong-dimension literal) and
     * {@code 22003} (component out of range for type vector). Matching on the
     * class — not a fixed five-char state — keeps the coalesce branch scoped to
     * data-exception rejections of the vector literal and excludes unrelated
     * SQLExceptions (connection loss, integrity violations) that must still
     * propagate as real infrastructure failures.
     */
    private static final String PGVECTOR_DATA_EXCEPTION_SQLSTATE_CLASS = "22";

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingWorker.class);

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

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @Inject
    PartitionScan partitionScan;

    // The scan-window floor is computed in Java from the injected Clock and
    // bound as a Timestamp (see enumeratePending), never SQL now(), so the
    // pickup window can be pinned under a fixed test clock instead of a
    // wall-clock-relative fixture that ages out (M1-448). The systemUTC()
    // initializer is what the CDI producer supplies; injection overrides it in
    // the managed bean, so it only takes effect for hand-constructed instances.
    @Inject
    Clock clock = Clock.systemUTC();

    @ConfigProperty(name = "infochat.embeddings.batch-size")
    int batchSize;

    @ConfigProperty(name = "infochat.embeddings.max-concurrency")
    int maxConcurrency;

    @SuppressWarnings("NullAway.Init")
    private Semaphore concurrencyPermits;
    @SuppressWarnings("NullAway.Init")
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
    @Scheduled(every = "{infochat.embeddings.poll-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void onTick() {
        List<PostRow> pending;
        try {
            pending = enumeratePending(batchSize);
        } catch (SQLException e) {
            // SafeLog, never the raw Throwable (docs/spec/security.md
            // §Secrets handling — User content in exceptions).
            SafeLog.warn(LOG, "EmbeddingWorker: failed to enumerate pending posts; skipping tick", e);
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
     * mismatch fires a coalesced operator alert and returns BEFORE any
     * INSERT or UPDATE, so no wrong-dimension vector is ever stored;
     * the post stays {@code embedding_done=FALSE} and the next tick
     * re-picks it (until the operator runs the re-embed procedure).
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
                    LOG.warn(
                        "EmbeddingWorker: batch of {} posts failed embed twice; releasing without vectors "
                            + "(error_class={}; first={} second={})",
                        batch.size(), ERROR_CLASS_EMBEDDING_BATCH_FAILURE,
                        attempt.failureReason(), retry.failureReason());
                    TransactionHelper.inTransaction(dataSource, "EmbeddingWorker", conn ->
                        advanceEmbeddingDoneOnly(conn, batch));
                    return;
                }
                attempt = retry;
            }

            // Per-vector dimensionality mismatch per docs/spec/llm.md
            // §Embedding pipeline. Validate ALL vectors before any
            // INSERT so the skip leaves no partial state.
            List<EmbeddingResult> results = attempt.results();
            for (int i = 0; i < results.size(); i++) {
                float[] vector = results.get(i).vector();
                int actualDimension = vector.length;
                if (actualDimension != cachedDimension) {
                    // Operator-action-required condition, not a self-healing
                    // batch failure: the metadata invariant is violated and the
                    // only recovery is a full re-embed. Fire ONE coalesced
                    // operator alert (the notifier throttles repeats on the
                    // error_class key) and skip the batch by returning BEFORE any
                    // INSERT or UPDATE — no wrong-dimension vector is stored,
                    // embedding_done stays FALSE, and the affected posts resume
                    // automatically after the operator re-embeds. Returning
                    // instead of throwing stops the stack-trace-per-poll loop the
                    // throw caused (the idempotent pickup re-selects the same
                    // wedged batch every tick).
                    LOG.error(
                        "EmbeddingWorker: per-vector dimensionality mismatch for post_id={} "
                            + "(expected={} actual={}); embedding halted until re-embed (error_class={})",
                        batch.get(i).id(), cachedDimension, actualDimension,
                        ERROR_CLASS_EMBEDDING_DIMENSION_MISMATCH);
                    throttledAdminNotifier.notifyOnce(
                        ERROR_CLASS_EMBEDDING_DIMENSION_MISMATCH,
                        ERROR_CLASS_EMBEDDING_DIMENSION_MISMATCH,
                        "Embedding dimensionality mismatch; run the re-embed procedure ("
                            + EmbeddingMetadataStartupGuard.REEMBED_PROCEDURE_PATH + ")");
                    return;
                }
                // Non-finite component guard (M1-327). pgvector rejects NaN and
                // ±Infinity literals, so a single non-finite component would make
                // formatVector's ?::vector cast throw SQLException out of the
                // transaction every tick — and because the idempotent pickup
                // re-selects the same right-length (so dimension-check-passing)
                // batch forever, the pipeline wedges with stack-trace spam and no
                // operator alert. Mirror the dimension-mismatch path exactly: one
                // coalesced operator alert + return BEFORE any INSERT/UPDATE, so
                // the post stays embedding_done=FALSE and resumes automatically
                // once the provider output normalizes (a finite vector on a later
                // tick completes the embedding).
                for (int j = 0; j < vector.length; j++) {
                    if (!Float.isFinite(vector[j])) {
                        LOG.error(
                            "EmbeddingWorker: non-finite embedding component for post_id={} "
                                + "(index={} value={}); embedding halted until the provider recovers "
                                + "(error_class={})",
                            batch.get(i).id(), j, vector[j], ERROR_CLASS_EMBEDDING_NONFINITE);
                        throttledAdminNotifier.notifyOnce(
                            ERROR_CLASS_EMBEDDING_NONFINITE,
                            ERROR_CLASS_EMBEDDING_NONFINITE,
                            "Embedding produced a non-finite vector component (NaN/Infinity); "
                                + "embedding stalled until the provider output normalizes");
                        return;
                    }
                }
            }

            try {
                TransactionHelper.inTransaction(dataSource, "EmbeddingWorker", conn -> {
                    insertEmbeddingRows(conn, batch, results);
                    advanceEmbeddingDoneOnly(conn, batch);
                });
            } catch (IllegalStateException e) {
                // A pgvector literal-parser rejection (SQLState class 22) that
                // survived the in-Java NaN/Infinity + dimension guards above —
                // e.g. some future/unforeseen malformed component. Without this
                // branch the SQLException propagates out of every poll and the
                // idempotent pickup re-selects the same wedged batch forever
                // (the same stack-trace-per-poll wedge M1-327 named). Mirror the
                // non-finite path's OBSERVABLE shape: one coalesced operator
                // alert + skip with no persisted row. The skip is by rollback,
                // not a pre-INSERT return: a server-side parser rejection cannot
                // be detected in-Java before the INSERT, so the narrow
                // transaction rolls back atomically (no post_embedding row, no
                // embedding_done advance) and the post resumes automatically
                // once the provider output normalizes. Any non-pgvector
                // SQLException is a real infrastructure failure and rethrows.
                SQLException rejection = pgvectorFormatRejection(e);
                if (rejection == null) {
                    throw e;
                }
                // Scalar fields only, never the raw Throwable: the PSQLException
                // message/cause chain can echo the offending ?::vector literal and
                // any token reachable in it, which would bypass SafeLog's redactor
                // (docs/spec/security.md §Secrets handling — "the original Throwable
                // is never passed to the underlying SLF4J logger"). Mirror the
                // sibling non-finite / dimension-mismatch branches, which log
                // scalars only. sqlState is the only diagnostic we need here.
                LOG.error(
                    "EmbeddingWorker: pgvector rejected a ?::vector literal for the batch "
                        + "(sqlState={}); embedding halted until the provider output normalizes "
                        + "(error_class={})",
                    rejection.getSQLState(), ERROR_CLASS_EMBEDDING_FORMAT_REJECTED);
                throttledAdminNotifier.notifyOnce(
                    ERROR_CLASS_EMBEDDING_FORMAT_REJECTED,
                    ERROR_CLASS_EMBEDDING_FORMAT_REJECTED,
                    "pgvector rejected an embedding vector literal; "
                        + "embedding stalled until the provider output normalizes");
            }
        } finally {
            concurrencyPermits.release();
        }
    }

    private AttemptResult attemptEmbed(List<String> inputs, int attempt) {
        List<EmbeddingResult> results;
        try {
            results = embeddingProvider.embed(inputs);
        } catch (RuntimeException e) {
            // SafeLog, never the raw Throwable: the provider exception
            // can echo its request context, which embeds the post
            // bodies in the embed input list (docs/spec/security.md
            // §Secrets handling — User content in exceptions).
            SafeLog.warn(LOG, "EmbeddingWorker: embed call attempt " + attempt + " threw", e);
            return AttemptResult.failure("exception: " + e.getClass().getSimpleName());
        }
        if (results.size() != inputs.size()) {
            // Wrong-shape response: per spec, "any per-element error
            // the Collector cannot map back to a specific post"
            // triggers the same one-failure-fails-batch retry path.
            LOG.warn(
                "EmbeddingWorker: embed call attempt {} returned wrong shape (expected {} got {})",
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
     * ({@code embedding_done=FALSE}). The {@code fetched_at} floor
     * ({@link PartitionScan#scanWindowFloor(Instant)}, sampled from the
     * injected Clock) lets the planner prune partitions of the
     * RANGE(fetched_at) post table. The ORDER BY makes the pickup
     * deterministic.
     */
    List<PostRow> enumeratePending(int limit) throws SQLException {
        final String sql =
            "SELECT id, fetched_at, title, body, body_summary "
                + "  FROM post "
                + " WHERE status = 'RAW' "
                + "   AND tagger_done = TRUE "
                + "   AND embedding_done = FALSE "
                + "   AND fetched_at >= ? "
                + " ORDER BY fetched_at, id "
                + " LIMIT ?";
        List<PostRow> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(partitionScan.scanWindowFloor(clock.instant())));
            ps.setInt(2, limit);
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
    // Package-private and non-static (not private static) so a test can
    // override it to inject a literal pgvector's parser actually rejects: no
    // finite, right-dimension vector can trigger that server-side rejection
    // naturally, so overriding formatVector is the only way to exercise the
    // defense-in-depth coalesce branch in processBatch. Declares throws
    // SQLException so a setValue rejection propagates to that branch instead of
    // being masked as an unrelated IllegalStateException.
    PGobject formatVector(float[] vector) throws SQLException {
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
        pg.setType("vector");
        pg.setValue(sb.toString());
        return pg;
    }

    /**
     * Walk the cause chain for a pgvector literal-parser rejection: a
     * {@link SQLException} whose SQLState is in the data-exception class
     * ({@value #PGVECTOR_DATA_EXCEPTION_SQLSTATE_CLASS}).
     * {@link TransactionHelper#inTransaction} wraps a body SQLException as
     * {@code IllegalStateException(cause = SQLException)}, so the rejection sits
     * one hop down the chain. Returns the matching SQLException, or {@code null}
     * when no cause is a data-exception — i.e. a real infrastructure failure the
     * caller must rethrow rather than coalesce.
     */
    private static @Nullable SQLException pgvectorFormatRejection(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLException sqle) {
                String sqlState = sqle.getSQLState();
                if (sqlState != null && sqlState.startsWith(PGVECTOR_DATA_EXCEPTION_SQLSTATE_CLASS)) {
                    return sqle;
                }
            }
        }
        return null;
    }

    /** One pending post, populated by {@link #enumeratePending}.
     *  title/body/bodySummary reflect the V7 schema: all three post
     *  columns are nullable. */
    public record PostRow(UUID id, Instant fetchedAt, @Nullable String title, @Nullable String body,
                          @Nullable String bodySummary) {
    }

    /**
     * Outcome of one {@link #attemptEmbed} call: either a successful
     * results list or a structured failure description for the second
     * attempt's log line.
     */
    private record AttemptResult(boolean success, List<EmbeddingResult> results, @Nullable String failureReason) {
        static AttemptResult success(List<EmbeddingResult> results) {
            return new AttemptResult(true, results, null);
        }

        static AttemptResult failure(String reason) {
            return new AttemptResult(false, List.of(), reason);
        }
    }
}
