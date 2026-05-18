package app.zcat.infochat.collector.eval.ready;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

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

/**
 * Collector-side scheduled poller for Stage 5 of the eval pipeline:
 * the {@code RAW → READY} promotion plus the first
 * {@code pg_notify('new_post', ...)} emit in the codebase.
 *
 * <h2>Pickup criteria</h2>
 *
 * <p>{@code status='RAW' AND stage1_done=TRUE AND
 * (stage1_flagged=FALSE OR stage2_done=TRUE) AND tagger_done=TRUE AND
 * embedding_done=TRUE}. The {@code status='RAW'} filter mechanically
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
 * emit MUST commit or rollback together. The {@code @Transactional}
 * boundary on {@link #promoteOne} is the enforcement; a NOTIFY outside
 * the transaction would survive a rollback as a phantom event,
 * advancing the Provider cursor past a non-existent post.
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

    private static final Logger LOG = Logger.getLogger(ReadyPromoter.class);

    /**
     * Cap on the number of posts promoted per @Scheduled tick. Same
     * single-batch-per-tick shape as the EmbeddingWorker; a backlog
     * drains over multiple ticks without unbounded memory pressure on
     * a single tick.
     */
    private static final int PROMOTION_BATCH_LIMIT = 64;

    @Inject
    DataSource dataSource;

    /**
     * Test-only seam: a Runnable invoked AFTER the UPDATE succeeds
     * but BEFORE the {@code pg_notify} statement. Production code
     * never sets this — the default no-op runs in every production
     * promotion. {@code ReadyPromoterIT} uses it to throw a
     * RuntimeException inside the {@code @Transactional} boundary
     * to assert that the UPDATE rolls back AND no NOTIFY is
     * delivered. Package-private so cross-package tests cannot
     * reach in.
     */
    Runnable afterUpdateHook = () -> {};

    /**
     * Scheduled tick. Enumerates pending posts and promotes each one
     * (each promotion is its own transaction — a failure on one post
     * does not block the rest of the batch).
     */
    @Scheduled(every = "{infochat.embeddings.poll-interval}")
    public void onTick() {
        List<PromotionCandidate> pending;
        try {
            pending = enumeratePending(PROMOTION_BATCH_LIMIT);
        } catch (SQLException e) {
            LOG.warn("ReadyPromoter: failed to enumerate pending posts; skipping tick", e);
            return;
        }
        for (PromotionCandidate post : pending) {
            try {
                promoteOne(post.id(), post.fetchedAt());
            } catch (RuntimeException e) {
                LOG.warnf(e,
                    "ReadyPromoter: promotion failed for post_id=%s; will retry next tick",
                    post.id());
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
    @Transactional
    public void promoteOne(UUID postId, Instant fetchedAt) {
        Instant readyAt = Instant.now();
        try (Connection conn = dataSource.getConnection()) {
            int rowsUpdated;
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE post "
                    + "   SET status = 'READY', "
                    + "       ready_at = ?, "
                    + "       status_changed_at = ? "
                    + " WHERE id = ? "
                    + "   AND fetched_at = ? "
                    + "   AND status = 'RAW'")) {
                Timestamp ts = Timestamp.from(readyAt);
                ps.setTimestamp(1, ts);
                ps.setTimestamp(2, ts);
                ps.setObject(3, postId);
                ps.setTimestamp(4, Timestamp.from(fetchedAt));
                rowsUpdated = ps.executeUpdate();
            }
            if (rowsUpdated == 0) {
                // The post was no longer RAW (concurrent promotion
                // by another tick, or status flipped to QUARANTINED
                // between enumeratePending and this UPDATE). No
                // NOTIFY — the same-transaction rule cuts both
                // ways: no UPDATE means no NOTIFY for this id.
                return;
            }
            // Test-only seam: production sets this to a no-op
            // (declared above). The IT injects a throwing Runnable
            // here to assert the same-transaction rule rolls back
            // both the UPDATE and any pending NOTIFY.
            afterUpdateHook.run();
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
        } catch (SQLException e) {
            throw new IllegalStateException(
                "ReadyPromoter: RAW → READY transition failed for post_id=" + postId, e);
        }
    }

    /**
     * Enumerate the next batch of posts ready for the Stage-5
     * promotion. All four per-stage gates must be passed:
     * {@code stage1_done}, the Stage-2-only-if-flagged conjunction,
     * {@code tagger_done}, {@code embedding_done}. The
     * {@code status='RAW'} filter excludes quarantined posts.
     */
    List<PromotionCandidate> enumeratePending(int limit) throws SQLException {
        final String sql =
            "SELECT id, fetched_at "
                + "  FROM post "
                + " WHERE status = 'RAW' "
                + "   AND stage1_done = TRUE "
                + "   AND (stage1_flagged = FALSE OR stage2_done = TRUE) "
                + "   AND tagger_done = TRUE "
                + "   AND embedding_done = TRUE "
                + " ORDER BY fetched_at, id "
                + " LIMIT ?";
        List<PromotionCandidate> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
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
