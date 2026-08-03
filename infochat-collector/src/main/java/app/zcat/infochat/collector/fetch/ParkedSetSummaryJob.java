package app.zcat.infochat.collector.fetch;

import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * Recurring operator-visible statement of the whole parked set (D42 as
 * amended by M1-752; M1-754) — the missing half of the one-shot
 * crossing-tick notification. The one-shot fires once per park and is
 * coalesced/lossy; a missed one hid a dark source for 27 days on the
 * live deployment. This job re-states the full set on a recurring
 * cadence and is silent when the set is empty.
 *
 * <p>Covers ALL park reasons — {@code fetch-failure} (including
 * terminally re-probe-capped rows), the manual-only security parks,
 * and NULL-reason pre-discriminator rows. The NULL-reason rows are
 * precisely the already-parked corpus the introducing migration
 * deliberately did not backfill; this signal is what keeps them
 * visible until an operator {@code /source-enable}s them.
 */
@ApplicationScoped
public class ParkedSetSummaryJob {

    static final String ERROR_CLASS_PARKED_SET = "parked_set_summary";

    private static final Logger LOG = LoggerFactory.getLogger(ParkedSetSummaryJob.class);

    // UUID, reason, parked-since ONLY — never the identifier URL
    // (M1-023 INFO-LEAK rule).
    private static final String PARKED_SET_SQL =
        "SELECT id, park_reason, parked_at FROM source "
        + "WHERE status = 'failed' AND deleted_at IS NULL "
        + "ORDER BY parked_at NULLS FIRST, id";

    @Inject
    DataSource dataSource;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @Scheduled(every = "{infochat.fetch.parked-summary.interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void onTick() {
        try {
            runOnce();
        } catch (SQLException e) {
            // SafeLog, never the raw Throwable (docs/spec/security.md
            // §Secrets handling — User content in exceptions).
            SafeLog.warn(LOG, "ParkedSetSummaryJob: sweep failed; skipping", e);
        }
    }

    void runOnce() throws SQLException {
        String summary = composeSummary();
        if (summary == null) {
            return;
        }
        // Constant key: the summary cadence (default 24h) sits far above
        // the notifier's throttle window (default 1h), so every run lands
        // outside the window and EMITTED — no per-period key needed. An
        // operator who raises the window past the cadence trades summary
        // runs for suppressed-counter bumps; the cadence>window
        // relationship is documented in docs/design/01-architecture.md.
        throttledAdminNotifier.notifyOnce(
            ERROR_CLASS_PARKED_SET, ERROR_CLASS_PARKED_SET, summary);
    }

    /**
     * The summary message, or {@code null} when the parked set is
     * empty (the silent case). Package-private seam so the summary
     * content — every reason covered, no identifier URL — is
     * assertable directly ({@code ParkedSetSummaryIT}); the
     * notifier stores only throttle bookkeeping, not the message.
     */
    @Nullable
    String composeSummary() throws SQLException {
        StringBuilder lines = new StringBuilder();
        int count = 0;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(PARKED_SET_SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID id = (UUID) rs.getObject(1);
                String reason = rs.getString(2);
                Timestamp parkedAt = rs.getTimestamp(3);
                count++;
                lines.append("; uuid=").append(id)
                    .append(" reason=").append(reason != null ? reason : "unrecorded(manual-only)")
                    .append(" since=").append(parkedAt != null ? parkedAt.toInstant() : "unknown");
            }
        }
        if (count == 0) {
            return null;
        }
        // The count leads the message so the notifier's 2048-char
        // sanitize cap can only truncate the per-source tail, never the
        // headline.
        return count + " source(s) parked (status='failed'); manual-only rows need "
            + "/source-enable" + lines;
    }
}
