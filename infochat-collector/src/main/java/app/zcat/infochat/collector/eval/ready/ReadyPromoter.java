package app.zcat.infochat.collector.eval.ready;

import app.zcat.infochat.collector.eval.PartitionScan;
import app.zcat.infochat.core.log.SafeLog;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.function.Consumer;

/**
 * Collector-side scheduled poller for Stage 5 of the eval pipeline:
 * the {@code RAW → READY} promotion plus the first
 * {@code pg_notify('new_post', ...)} emit in the codebase.
 *
 * <h2>Pickup criteria</h2>
 *
 * <p>{@code status='RAW' AND stage1_done=TRUE AND
 * (stage1_flagged=FALSE OR stage2_done=TRUE) AND tagger_done=TRUE AND
 * entity_done=TRUE AND embedding_done=TRUE}. Entity extraction and
 * embedding are independent parallel stages after the Tagger; this
 * promoter is the synchronization point that waits for BOTH to
 * complete. The {@code status='RAW'} filter mechanically
 * excludes quarantined posts — this class NEVER promotes a
 * QUARANTINED post to READY (Stage 2 INJ/MAL/UNK and Stage 1
 * watchdog fail-closed are the writers that move a post into the
 * quarantined status; lifting them is {@code /quarantine approve}
 * territory in T2-G).
 *
 * <h2>Same-transaction rule</h2>
 *
 * <p>Per {@code docs/spec/architecture.md} §Inter-service communication:
 * "the high-water mark advances both fields in the same DB transaction
 * as the side effect it triggers, making processing idempotent. ... a
 * duplicate NOTIFY or a repeated catch-up pass for the same row
 * produces no additional side effect." The
 * {@code UPDATE post SET status='READY', ready_at=now(),
 * status_changed_at=now()} AND the {@code pg_notify('new_post', ...)}
 * emit MUST commit or rollback together. {@link #promoteOne} enforces
 * this by managing the JDBC transaction explicitly
 * ({@code setAutoCommit(false)} + a single {@code commit}). A
 * {@code @Transactional} annotation could NOT: {@link #onTick}
 * self-invokes {@code promoteOne}, so the CDI interceptor never fires
 * and the two statements would fall back to separate autocommits. A
 * NOTIFY outside the transaction would survive a rollback as a phantom
 * event, advancing the Provider cursor past a non-existent post.
 *
 * <h2>NOTIFY payload contract</h2>
 *
 * <p>JSON object with two fields:
 * <pre>{@code {"ready_at":"<iso8601-instant>","post_id":"<uuid>"}}</pre>
 * The values are produced by {@link Instant#toString()} and
 * {@link UUID#toString()} — M1-027's Provider-side
 * {@code NewPostListener.parsePayload} calls {@link Instant#parse} and
 * {@link UUID#fromString} on the extracted strings, so the round-trip
 * is guaranteed by both classes' canonical forms.
 *
 * <p>The payload is built inline here (no shared helper extracted) —
 * the parser lives in {@code infochat-provider} and this ticket's
 * out-of-scope rule forbids Provider-module edits. The contract is
 * the JSON byte shape, not a shared class.
 *
 * <h2>Catch-up</h2>
 *
 * <p>If the Provider is down when this NOTIFY fires, the message is
 * dropped on the wire (LISTEN/NOTIFY has no durable queue). M1-027's
 * {@code NewPostReconciler} catches up at Provider startup by scanning
 * for READY rows past the stored cursor — that path is independent of
 * this NOTIFY emit and remains correct regardless of whether the
 * live NOTIFY was delivered.
 */
@ApplicationScoped
public class ReadyPromoter {

    /** The LISTEN/NOTIFY channel name (matches M1-027's NewPostListener). */
    public static final String NEW_POST_CHANNEL = "new_post";

    private static final Logger LOG = LoggerFactory.getLogger(ReadyPromoter.class);

    /**
     * Cap on the number of posts promoted per @Scheduled tick. Same
     * single-batch-per-tick shape as the EmbeddingWorker; a backlog
     * drains over multiple ticks without unbounded memory pressure on
     * a single tick.
     */
    private static final int PROMOTION_BATCH_LIMIT = 64;

    @Inject
    DataSource dataSource;

    @Inject
    PartitionScan partitionScan;

    /**
     * Test-only seam: invoked with the in-transaction connection AFTER
     * the UPDATE succeeds but BEFORE the {@code pg_notify} statement.
     * Production code never sets this — the default no-op runs in every
     * production promotion. {@code ReadyPromoterIT} uses it to throw a
     * RuntimeException inside the explicit transaction
     * to assert that the UPDATE rolls back AND no NOTIFY is
     * delivered, and to observe the transaction timestamp the
     * DB-assigned {@code ready_at} must equal. Package-private so
     * cross-package tests cannot reach in.
     */
    Consumer<Connection> afterUpdateHook = conn -> {};

    /**
     * Scheduled tick. Enumerates pending posts and promotes each one
     * (each promotion is its own transaction — a failure on one post
     * does not block the rest of the batch).
     *
     * <p>Cadence is owned by {@code infochat.eval.ready-promoter.poll-interval},
     * independent of the embedding stage: Stage 5 promotion is a distinct
     * step whose latency must not be coupled to embedding poll tuning.
     * The key defaults to the same value the embedding poll uses, so
     * observable steady-state behaviour is unchanged.
     */
    @Scheduled(every = "{infochat.eval.ready-promoter.poll-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void onTick() {
        List<PromotionCandidate> pending;
        try {
            pending = enumeratePending(PROMOTION_BATCH_LIMIT);
        } catch (SQLException e) {
            // SafeLog, never the raw Throwable (docs/spec/security.md
            // §Secrets handling — User content in exceptions).
            SafeLog.warn(LOG, "ReadyPromoter: failed to enumerate pending posts; skipping tick", e);
            return;
        }
        for (PromotionCandidate post : pending) {
            try {
                promoteOne(post.id(), post.fetchedAt());
            } catch (RuntimeException e) {
                SafeLog.warn(LOG, "ReadyPromoter: promotion failed for post_id=" + post.id()
                    + "; will retry next tick", e);
            }
        }
    }

    /**
     * Promote one post inside a single transaction: the
     * {@code UPDATE post SET status='READY', ...} and the
     * {@code pg_notify('new_post', ...)} commit together. The
     * idempotency guarantee comes from the
     * {@code WHERE status='RAW'} predicate — a second invocation
     * with the same id is a no-op (the UPDATE matches zero rows)
     * AND the NOTIFY is also suppressed because both statements run
     * in the same SQL session against the same row.
     */
    public void promoteOne(UUID postId, Instant fetchedAt) {
        try (Connection conn = dataSource.getConnection()) {
            // Explicit transaction boundary: the UPDATE and the
            // pg_notify('new_post') below must commit or roll back as
            // one unit (class javadoc §Same-transaction rule). The
            // default-pool connection arrives in autocommit mode; turn
            // it off so both statements ride a single commit.
            conn.setAutoCommit(false);
            try {
                // Single clock for ready_at: the DB's now() — the same
                // transaction-timestamp source the approve_quarantine
                // SQL function uses — so every ready_at writer stamps
                // from one ordered timeline and JVM↔DB clock skew
                // cannot land a row below the Provider's
                // already-advanced (ready_at, id) cursor. RETURNING
                // feeds the DB-assigned value into the NOTIFY payload
                // so payload and column stay byte-identical.
                // Residual the single clock does NOT close: now() is
                // transaction-START time, so a writer transaction that
                // begins before another's but commits after it can
                // still publish a ready_at below an already-advanced
                // cursor (e.g. a slow approve_quarantine overlapping a
                // fast promotion). The strictly-monotonic cursor CAS
                // classifies such a row as out-of-order; a real
                // new_post consumer must tolerate that (lag-window
                // scan and/or per-post dedupe) before it attaches.
                Instant readyAt;
                try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE post "
                        + "   SET status = 'READY', "
                        + "       ready_at = now(), "
                        + "       status_changed_at = now() "
                        + " WHERE id = ? "
                        + "   AND fetched_at = ? "
                        + "   AND status = 'RAW' "
                        + " RETURNING ready_at")) {
                    ps.setObject(1, postId);
                    ps.setTimestamp(2, Timestamp.from(fetchedAt));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            // The post was no longer RAW (concurrent promotion
                            // by another tick, or status flipped to QUARANTINED
                            // between enumeratePending and this UPDATE). No
                            // NOTIFY — the same-transaction rule cuts both
                            // ways: no UPDATE means no NOTIFY for this id.
                            conn.rollback();
                            return;
                        }
                        readyAt = rs.getTimestamp(1).toInstant();
                    }
                }
                // Test-only seam: production sets this to a no-op
                // (declared above). The IT injects a throwing hook
                // here to assert the same-transaction rule rolls back
                // both the UPDATE and any pending NOTIFY.
                afterUpdateHook.accept(conn);
                String payload = "{\"ready_at\":\"" + readyAt.toString()
                    + "\",\"post_id\":\"" + postId.toString() + "\"}";
                try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT pg_notify(?, ?)")) {
                    ps.setString(1, NEW_POST_CHANNEL);
                    ps.setString(2, payload);
                    try (ResultSet rs = ps.executeQuery()) {
                        // Drain the result set; pg_notify returns void
                        // but JDBC requires the cursor be consumed.
                        rs.next();
                    }
                }
                conn.commit();
            } catch (RuntimeException e) {
                // A mid-transaction failure (the afterUpdateHook seam, or
                // any unchecked error after the UPDATE) must discard the
                // UPDATE so neither a phantom READY row nor a phantom
                // NOTIFY survives.
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                "ReadyPromoter: RAW → READY transition failed for post_id=" + postId, e);
        }
    }

    /**
     * Enumerate the next batch of posts ready for the Stage-5
     * promotion. All per-stage gates must be passed:
     * {@code stage1_done}, the Stage-2-only-if-flagged conjunction,
     * {@code tagger_done}, and the two independent parallel stages
     * {@code entity_done} and {@code embedding_done}. The
     * {@code status='RAW'} filter excludes quarantined posts. The
     * {@code fetched_at} floor ({@link PartitionScan#scanWindow()})
     * lets the planner prune partitions of the RANGE(fetched_at) post
     * table.
     */
    List<PromotionCandidate> enumeratePending(int limit) throws SQLException {
        final String sql =
            "SELECT id, fetched_at "
                + "  FROM post "
                + " WHERE status = 'RAW' "
                + "   AND stage1_done = TRUE "
                + "   AND (stage1_flagged = FALSE OR stage2_done = TRUE) "
                + "   AND tagger_done = TRUE "
                + "   AND entity_done = TRUE "
                + "   AND embedding_done = TRUE "
                + "   AND fetched_at >= now() - ?::INTERVAL "
                + " ORDER BY fetched_at, id "
                + " LIMIT ?";
        List<PromotionCandidate> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, partitionScan.scanWindow());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = (UUID) rs.getObject(1);
                    Instant fetchedAt = rs.getTimestamp(2).toInstant();
                    rows.add(new PromotionCandidate(id, fetchedAt));
                }
            }
        }
        return rows;
    }

    /** Minimal projection of a row eligible for promotion. */
    public record PromotionCandidate(UUID id, Instant fetchedAt) {
    }
}
