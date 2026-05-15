package io.infochat.provider.outbox;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * Single-row processor for the {@code new_post} channel — the SHARED code
 * path between the catch-up reconciler ({@link NewPostReconciler}) and the
 * live LISTEN/NOTIFY worker ({@link NewPostListener}). Funnelling both
 * dispatch paths through one method is the architectural commitment from
 * docs/spec/architecture.md §Inter-service communication §Catch-up
 * ("and feeds those rows into the same handler that processes live NOTIFY
 * new_post payloads") — push and catch-up advance the cursor identically.
 *
 * <p><b>Side effect (T1-C scope).</b> This handler is a STUB. Its observable
 * effect is a single INFO-level log line carrying the cursor key. The real
 * downstream consumers (cache invalidation, periodic-digest recompute, per-
 * group fan-out) land in T1-F and attach to this same method so the same-
 * transaction invariant below survives without a refactor.
 *
 * <p><b>Same-transaction invariant.</b> The cursor advance happens INSIDE
 * the {@code @Transactional} boundary that wraps this method, alongside the
 * side effect. This is load-bearing per docs/spec/architecture.md §Catch-up
 * ("the high-water mark advances both fields in the same DB transaction as
 * the side effect it triggers, making processing idempotent"). When T1-F
 * adds real consumers, those side effects must live inside the same method
 * (or be invoked synchronously from it before the cursor advance) so a
 * crash between side effect and CAS leaves no half-applied state.
 *
 * <p><b>Per-row transactions, not bulk.</b> The reconciler invokes this
 * method once per catch-up row inside the per-row JTA transaction the
 * {@code @Transactional} annotation opens. A single bulk transaction
 * spanning N rows would hold an UPDATE lock on the cursor's row for the
 * full scan; a concurrent NOTIFY arriving mid-catch-up would block until
 * commit. Per-row is the correct shape — the CAS no-op short-circuits
 * the concurrent arrival rather than blocking on the lock.
 *
 * <p><b>Idempotency.</b> A duplicate event whose cursor is {@code <=} the
 * stored cursor produces no additional side effect because the CAS update
 * is a no-op; the duplicate's log line is the only residue, and the
 * transaction commits a zero-row UPDATE which is semantically a NOP. T1-F
 * consumers attaching here must respect the {@code advanced} return value
 * to avoid double-firing real side effects on a duplicate.
 */
@ApplicationScoped
public class NewPostHandler {

    /** docs/design/02-schema.md §2.9.1 — the v1 closed-list channel name. */
    public static final String CHANNEL_NEW_POST = "new_post";

    /**
     * docs/design/02-schema.md §2.9.2 — the per-channel cursor interpretation
     * table commits {@code cursor_low_kind = 'post'} for every {@code new_post}
     * event. The first-boot seed is the empty-string sentinel; the first real
     * event upgrades the kind to {@code 'post'} permanently.
     */
    public static final String CURSOR_LOW_KIND_POST = "post";

    private static final Logger LOG = Logger.getLogger(NewPostHandler.class);

    @Inject
    ProviderStateDao providerStateDao;

    /**
     * Processes one {@code (post_id, ready_at)} pair. Idempotent on the
     * cursor: invoking the same {@code (post_id, ready_at)} twice causes
     * the second call's CAS update to be a no-op (the stored cursor is
     * already at or past the supplied one).
     *
     * @return {@code true} if the cursor advanced (this was a real event);
     *     {@code false} if the cursor was already at or past the supplied
     *     value (duplicate or out-of-order arrival).
     */
    @Transactional
    public boolean handle(UUID postId, Instant readyAt) throws SQLException {
        boolean advanced = providerStateDao.advanceCursor(
            CHANNEL_NEW_POST, readyAt, CURSOR_LOW_KIND_POST, postId.toString());
        if (advanced) {
            LOG.infof("new_post handled: post_id=%s ready_at=%s (cursor advanced)",
                postId, readyAt);
        } else {
            LOG.infof("new_post duplicate or out-of-order: post_id=%s ready_at=%s (cursor unchanged)",
                postId, readyAt);
        }
        return advanced;
    }
}
