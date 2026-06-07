package app.zcat.infochat.collector.fetch;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
    private static final String RECORD_FAILURE_SQL =
        "UPDATE source SET consecutive_failures = consecutive_failures + 1, "
        + "                 last_fetch_at = now(), "
        + "                 status = CASE "
        + "                            WHEN consecutive_failures + 1 >= ? "
        + "                                 AND status = 'active' "
        + "                            THEN 'failed' "
        + "                            ELSE status "
        + "                          END "
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
            ps.setObject(2, sourceId);
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
}
