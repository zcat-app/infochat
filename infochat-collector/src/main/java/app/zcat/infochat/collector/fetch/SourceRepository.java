package app.zcat.infochat.collector.fetch;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
 * Source-row DAO for the D42 per-source failure ladder.
 *
 * <p>Owns the two per-tick UPDATE shapes the FetchScheduler issues
 * against the {@code source} table after a Fetcher returns:
 * <ul>
 *   <li>{@link #recordSuccess} — counter reset, both
 *       {@code last_fetch_at} and {@code last_success_at} bumped.</li>
 *   <li>{@link #recordFailure} — counter increment,
 *       {@code last_fetch_at} bumped, {@code last_success_at}
 *       unchanged, and the {@code active → failed} status
 *       transition fired when the post-increment counter reaches the
 *       configured threshold. The post-update state is returned so
 *       the caller can decide whether to fire a throttled admin
 *       notification (only on the crossing tick).</li>
 * </ul>
 *
 * <p>Also owns the re-probe-ladder SQL (D42 as amended by M1-752;
 * M1-754): schedule initialization, due-candidate selection, attempt
 * bookkeeping, the compare-and-swap restore, and the
 * sustained-success counter clear. Every selection carries the full
 * eligibility predicate — {@code status='failed' AND
 * park_reason='fetch-failure' AND deleted_at IS NULL} — as positive
 * equality on the reason, never a negation: a NULL or unrecognized
 * reason is fail-closed (D42 property (c)), and a soft-deleted row is
 * never probed or revived (property (d)).
 *
 * <h2>Concurrency</h2>
 * <p>D41 guarantees exactly one Collector instance per deployment,
 * so two ticks for the same source cannot race. The counter
 * increment is a simple
 * {@code SET consecutive_failures = consecutive_failures + 1} with
 * no CAS or optimistic-lock; the threshold check rides on the same
 * atomic UPDATE via a {@code CASE} expression so the
 * increment-and-flip is a single statement.</p>
 *
 * <h2>Notification key cardinality</h2>
 * <p>The crossing detection here drives a {@link
 * app.zcat.infochat.core.notifier.ThrottledAdminNotifier#notifyOnce}
 * call keyed on the per-source UUID. Per-source keys are bounded by
 * the {@code source} row count (operator-controlled, not
 * attacker-controlled), so the
 * {@code admin_notification_state}-growth concern from the notifier's
 * sanitization rules does not apply here.</p>
 */
@ApplicationScoped
public class SourceRepository {

    private static final String RECORD_SUCCESS_SQL =
        "UPDATE source SET consecutive_failures = 0, "
        + "                 last_fetch_at = now(), "
        + "                 last_success_at = now() "
        + "WHERE id = ?";

    // The CASE expression flips status only when (a) the post-increment
    // counter would reach the threshold AND (b) the row is currently
    // 'active'. The 'active' guard makes the UPDATE idempotent against
    // an already-'failed' row (no spurious status churn) and against a
    // 'disabled' row (admin /source-disable wins; the ladder does not
    // override). The increment itself fires unconditionally — even on
    // a 'failed' row — so a future race-or-bug that lets the scheduler
    // tick a failed source still keeps the counter monotonic.
    //
    // park_reason and parked_at ride the SAME park guard, never the
    // unconditional increment (D42 property (a)): the increment fires
    // against already-'failed' rows, so an unguarded reason term would
    // relabel a row another writer (PerSourceUnknownTracker) parked
    // moments earlier — handing its manual-only security park the
    // re-probe-eligible 'fetch-failure' label. parked_at is a pure
    // record write for the parked-set summary (DB clock is fine;
    // engineering-rules §9 exemption — no decision reads it back).
    private static final String RECORD_FAILURE_SQL =
        "UPDATE source SET consecutive_failures = consecutive_failures + 1, "
        + "                 last_fetch_at = now(), "
        + "                 status = CASE "
        + "                            WHEN consecutive_failures + 1 >= ? "
        + "                                 AND status = 'active' "
        + "                            THEN 'failed' "
        + "                            ELSE status "
        + "                          END, "
        + "                 park_reason = CASE "
        + "                                 WHEN consecutive_failures + 1 >= ? "
        + "                                      AND status = 'active' "
        + "                                 THEN 'fetch-failure' "
        + "                                 ELSE park_reason "
        + "                               END, "
        + "                 parked_at = CASE "
        + "                               WHEN consecutive_failures + 1 >= ? "
        + "                                    AND status = 'active' "
        + "                               THEN now() "
        + "                               ELSE parked_at "
        + "                             END "
        + "WHERE id = ? "
        + "RETURNING consecutive_failures, status";

    @Inject
    DataSource dataSource;

    /**
     * Record a successful tick: zero the counter, refresh both
     * timestamps. No return value — the success path does not flip
     * status (the scheduler only sees {@code status='active'} rows in
     * the first place) and does not fire any notification.
     */
    public void recordSuccess(UUID sourceId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(RECORD_SUCCESS_SQL)) {
            ps.setObject(1, sourceId);
            ps.executeUpdate();
        }
    }

    /**
     * Record a failed tick: increment the counter, refresh
     * {@code last_fetch_at} (NOT {@code last_success_at}), and flip
     * {@code active → failed} when the post-increment counter reaches
     * {@code threshold}.
     *
     * @param sourceId the row to update.
     * @param threshold the consecutive-failure count at which the
     *                  source transitions to {@code status='failed'}.
     *                  Sourced from
     *                  {@code infochat.fetch.failure-threshold}.
     * @return the post-update counter, status, and a
     *         {@code crossedThreshold} flag that is true iff THIS
     *         increment is the one that flipped the row from
     *         {@code active → failed}. The flag drives the throttled
     *         admin notification — fire on the crossing only, not on
     *         every post-threshold failure.
     */
    public FailureOutcome recordFailure(UUID sourceId, int threshold) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(RECORD_FAILURE_SQL)) {
            ps.setInt(1, threshold);
            ps.setInt(2, threshold);
            ps.setInt(3, threshold);
            ps.setObject(4, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // The row vanished between the scheduler's
                    // enumerate-active and the UPDATE — possible only
                    // if an admin /source-delete fired in the window.
                    // Surface as a no-op outcome; the caller's WARN
                    // log for the underlying fetch failure already
                    // records the event.
                    return new FailureOutcome(0, "missing", false);
                }
                int count = rs.getInt(1);
                String status = rs.getString(2);
                // The crossing tick is uniquely identified by
                // (count == threshold && status == 'failed'). Counts
                // above the threshold (a "shouldn't happen" race-or-
                // bug residue) produce crossed=false so the admin
                // notifier sees exactly one EMITTED per source per
                // active-to-failed transition.
                boolean crossed = (count == threshold) && "failed".equals(status);
                return new FailureOutcome(count, status, crossed);
            }
        }
    }

    /**
     * Post-update state from {@link #recordFailure}.
     *
     * @param consecutiveFailures the new counter value after the
     *                            increment.
     * @param status the post-update status — either unchanged or
     *               {@code 'failed'} if the threshold was crossed by
     *               this tick. {@code "missing"} when the row vanished
     *               mid-tick.
     * @param crossedThreshold {@code true} iff THIS tick is the one
     *                         that transitioned the row from
     *                         {@code active → failed}. Used by the
     *                         caller to fire a throttled admin
     *                         notification once per crossing.
     */
    public record FailureOutcome(int consecutiveFailures,
                                 String status,
                                 boolean crossedThreshold) {
    }

    // ------------------------------------------------------------------
    // Re-probe ladder (D42 as amended by M1-752; M1-754). All schedule
    // timestamps (next_reprobe_at, reprobe_restored_at) are Java-computed
    // by the caller from the injected Clock and bound as Timestamps —
    // the same component both writes and reads them, so the decision
    // never splits across the app and DB clocks (engineering-rules §9).
    // ------------------------------------------------------------------

    // Seeds next_reprobe_at for fetch-failure parks the re-probe job has
    // not seen yet. The park writers deliberately do not set it: writing
    // DB now() there and comparing against the injected Clock here would
    // be exactly the app-vs-DB clock split §9 forbids.
    private static final String REPROBE_INIT_SQL =
        "UPDATE source SET next_reprobe_at = ? "
        + "WHERE status = 'failed' AND park_reason = 'fetch-failure' "
        + "  AND deleted_at IS NULL AND next_reprobe_at IS NULL "
        + "  AND reprobe_count < ?";

    private static final String REPROBE_DUE_SQL =
        "SELECT id, identifier, kind, reprobe_count FROM source "
        + "WHERE status = 'failed' AND park_reason = 'fetch-failure' "
        + "  AND deleted_at IS NULL "
        + "  AND next_reprobe_at IS NOT NULL AND next_reprobe_at <= ? "
        + "  AND reprobe_count < ? "
        + "ORDER BY next_reprobe_at, id";

    private static final String REPROBE_ATTEMPT_SQL =
        "UPDATE source SET reprobe_count = reprobe_count + 1, "
        + "                 next_reprobe_at = ? "
        + "WHERE id = ?";

    // The restore is compare-and-swap, not blind (D42 property (e)):
    // selection and restore are separated by a network probe, and the
    // writers that can invalidate eligibility in that window run
    // concurrently (the UNKNOWN-rate evaluator is a separate scheduled
    // job; /remove-source runs in the Provider process — D41's
    // single-Collector topology serializes neither). The WHERE repeats
    // the whole eligibility predicate; zero rows updated is a no-op
    // that leaves the park intact. reprobe_count is deliberately NOT
    // reset — clearing it is gated on the sustained-success window,
    // not the restore, or park→probe→restore→park would cycle forever.
    private static final String REPROBE_RESTORE_SQL =
        "UPDATE source SET status = 'active', consecutive_failures = 0, "
        + "                 park_reason = NULL, parked_at = NULL, "
        + "                 next_reprobe_at = NULL, reprobe_restored_at = ? "
        + "WHERE id = ? AND status = 'failed' "
        + "  AND park_reason = 'fetch-failure' AND deleted_at IS NULL "
        + "  AND reprobe_count <= ?";

    private static final String SUSTAINED_SUCCESS_CLEAR_SQL =
        "UPDATE source SET reprobe_count = 0, reprobe_restored_at = NULL "
        + "WHERE status = 'active' AND reprobe_restored_at IS NOT NULL "
        + "  AND reprobe_restored_at <= ?";

    /**
     * Seed {@code next_reprobe_at} for newly-parked fetch-failure rows
     * (those with no probe scheduled yet), skipping rows already at the
     * absolute cap.
     *
     * @param firstProbeAt when the first probe becomes due — the
     *                     caller's {@code clock.instant() + first-delay}.
     * @param cap the absolute re-probe cap; at or above it the row is
     *            terminally parked and scheduling it would be noise.
     */
    public void initializeReprobeSchedule(Instant firstProbeAt, int cap) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(REPROBE_INIT_SQL)) {
            ps.setTimestamp(1, Timestamp.from(firstProbeAt));
            ps.setInt(2, cap);
            ps.executeUpdate();
        }
    }

    /**
     * Fetch-failure parks whose probe is due and that sit under the
     * absolute cap. The predicate is the normative D42 selection —
     * positive reason equality, {@code deleted_at IS NULL} — so a
     * NULL-reason park, a security park ({@code unknown-rate} /
     * {@code stream-cycle-cap}) or a soft-deleted row is never
     * returned.
     */
    public List<ReprobeCandidate> selectDueReprobes(Instant now, int cap) throws SQLException {
        List<ReprobeCandidate> candidates = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(REPROBE_DUE_SQL)) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setInt(2, cap);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    candidates.add(new ReprobeCandidate(
                        (UUID) rs.getObject(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getInt(4)));
                }
            }
        }
        return candidates;
    }

    /**
     * Record a probe attempt BEFORE the fetch runs: increment the cap
     * counter and stamp the next backoff slot. Recording first makes
     * the attempt durable whatever the probe does — a successful
     * restore keeps the incremented count (only the sustained-success
     * window clears it), and a crash mid-probe cannot grant a free
     * retry.
     */
    public void recordReprobeAttempt(UUID sourceId, Instant nextProbeAt) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(REPROBE_ATTEMPT_SQL)) {
            ps.setTimestamp(1, Timestamp.from(nextProbeAt));
            ps.setObject(2, sourceId);
            ps.executeUpdate();
        }
    }

    /**
     * Compare-and-swap restore on the caller's connection so the
     * caller can commit it atomically with the transition's audit row
     * and RECOVERED notification (D42: the audit row rides the same
     * transaction; a rollback leaves no orphan).
     *
     * @return rows updated — {@code 0} means eligibility vanished
     *         between selection and restore (reason upgraded,
     *         soft-deleted, already restored); the park stands and the
     *         caller must treat the probe as a no-op.
     */
    public int casRestore(Connection conn, UUID sourceId, Instant restoredAt, int cap)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(REPROBE_RESTORE_SQL)) {
            ps.setTimestamp(1, Timestamp.from(restoredAt));
            ps.setObject(2, sourceId);
            ps.setInt(3, cap);
            return ps.executeUpdate();
        }
    }

    /**
     * Zero the cap counter for restored sources that have stayed
     * healthy past the sustained-success window (the half-open→closed
     * transition of an ordinary circuit breaker). A source that
     * re-parked meanwhile is {@code status='failed'} and keeps its
     * count — it resumes the ladder where it left off.
     *
     * @param healthyFloor {@code clock.instant() - window}; rows
     *                     restored at or before this instant qualify.
     * @return rows cleared, for the caller's log line.
     */
    public int clearSustainedSuccessCounters(Instant healthyFloor) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SUSTAINED_SUCCESS_CLEAR_SQL)) {
            ps.setTimestamp(1, Timestamp.from(healthyFloor));
            return ps.executeUpdate();
        }
    }

    /**
     * One due re-probe candidate.
     *
     * @param uuid the source row id.
     * @param identifier the fetch identifier (URL for HTTP-shaped
     *                   sources). NEVER logged or notified — M1-023
     *                   INFO-LEAK rule; it is carried only to hand to
     *                   {@code Fetcher.fetch}.
     * @param kind the source kind, for Fetcher dispatch.
     * @param reprobeCount attempts already consumed (pre-increment).
     */
    public record ReprobeCandidate(UUID uuid,
                                   String identifier,
                                   String kind,
                                   int reprobeCount) {
    }
}
