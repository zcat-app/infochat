package app.zcat.infochat.collector.eval.reeval;

import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

/**
 * Monitors per-source UNKNOWN verdict rates over a rolling window.
 * When a source's UNKNOWN rate exceeds the profile-driven threshold,
 * the source is auto-disabled ({@code status='failed'}) and a
 * throttled admin notification fires.
 *
 * <p>In-flight posts from a disabled source continue through their
 * current evaluation stage unaffected — the disable blocks only new
 * ingest: it fires a {@link SourceDisabled} signal that stops the
 * source's running stream-source worker, while polled fetchers skip
 * the now-disabled source on their next tick.
 */
@ApplicationScoped
public class PerSourceUnknownTracker {

    static final String ERROR_CLASS_SOURCE_UNKNOWN_AUTO_DISABLE = "source-unknown-auto-disable";

    // Slack added to the rolling window when bounding the partition key
    // (post.fetched_at) so partition pruning applies. fetched_at is the post
    // partition key; status_changed_at (the recency-of-verdict signal the rate
    // is actually computed over) is not — so bounding only status_changed_at
    // forced every tick to scan all partitions. The fetched_at bound must never
    // exclude a post that is inside the status_changed_at window, so it is
    // widened by this slack, which must exceed the worst-case lag between a
    // post's fetched_at and its stage-2 verdict (the eval-pipeline drain time).
    // Two days is generous: the pipeline drains in seconds/minutes normally and
    // this still tolerates a long sustained backlog. The trade-off (a post
    // evaluated now but fetched longer ago than window+slack drops out of the
    // rate) is argued in the commit message's semantic-delta note.
    private static final Duration PARTITION_SCAN_SLACK = Duration.ofDays(2);

    private static final Logger LOG = LoggerFactory.getLogger(PerSourceUnknownTracker.class);

    @Inject
    DataSource dataSource;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    // Worker-stop signal for an auto-disabled source. Fired (synchronously)
    // from disableSource so the observer reaches the supervisor before the
    // admin notify. An Event — not a direct StreamSourceSupervisor reference —
    // keeps this re-eval bean free of any stream-module dependency: the
    // NostrStreamSource.Registrar observer owns the sourceId→dispatchKey map.
    @Inject
    Event<SourceDisabled> sourceDisabledEvent;

    @ConfigProperty(name = "infochat.reeval.unknown-rate-threshold")
    double unknownRateThreshold;

    @ConfigProperty(name = "infochat.reeval.unknown-rate-window")
    Duration unknownRateWindow;

    @ConfigProperty(name = "infochat.reeval.unknown-rate-min-sample", defaultValue = "5")
    int minSampleSize;

    @Scheduled(every = "{infochat.reeval.unknown-tracker-poll-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void onTick() {
        try {
            checkAllSources();
        } catch (SQLException e) {
            // SafeLog, never the raw Throwable (docs/spec/security.md
            // §Secrets handling — User content in exceptions).
            SafeLog.warn(LOG, "PerSourceUnknownTracker: failed to check sources; skipping tick", e);
        }
    }

    void checkAllSources() throws SQLException {
        // Find active sources whose UNKNOWN rate within the window
        // exceeds the threshold. Uses the stage2_verdict column (V22)
        // to count only UNKNOWN verdicts, not INJECTION/MALWARE.
        //
        // The p.fetched_at lower bound is the partition-pruning predicate:
        // fetched_at is the post partition key, status_changed_at is not, so
        // bounding only status_changed_at made every tick scan all partitions.
        // fetched_at is widened by PARTITION_SCAN_SLACK so it never excludes a
        // post inside the status_changed_at window; status_changed_at stays the
        // precise recency-of-verdict signal the rate is computed over.
        final String sql =
            "SELECT s.id, "
                + "  COUNT(*) FILTER (WHERE p.stage2_verdict = 'UNKNOWN') AS unknown_count, "
                + "  COUNT(*) AS total_count "
                + "FROM source s "
                + "JOIN post p ON p.source_id = s.id "
                + "WHERE s.status = 'active' "
                + "  AND p.stage2_done = TRUE "
                + "  AND p.stage2_failed = FALSE "
                + "  AND p.fetched_at >= now() - ?::INTERVAL "
                + "  AND p.status_changed_at >= now() - ?::INTERVAL "
                + "GROUP BY s.id "
                + "HAVING COUNT(*) >= ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            long windowSeconds = unknownRateWindow.toSeconds();
            ps.setString(1, (windowSeconds + PARTITION_SCAN_SLACK.toSeconds()) + " seconds");
            ps.setString(2, windowSeconds + " seconds");
            ps.setInt(3, minSampleSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID sourceId = (UUID) rs.getObject(1);
                    long unknownCount = rs.getLong(2);
                    long totalCount = rs.getLong(3);
                    double rate = (double) unknownCount / totalCount;
                    if (rate > unknownRateThreshold) {
                        disableSource(sourceId, rate);
                    }
                }
            }
        }
    }

    private void disableSource(UUID sourceId, double observedRate) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE source SET status = 'failed' WHERE id = ? AND status = 'active'")) {
            ps.setObject(1, sourceId);
            int updated = ps.executeUpdate();
            if (updated > 0) {
                // Stop the source's running stream-source worker BEFORE the
                // admin notify, so "source disabled" is true the moment it
                // fires (U-03). fire() is synchronous: the
                // NostrStreamSource.Registrar observer has reached
                // supervisor.stop by the time fire() returns. A source with no
                // live stream worker (the common case — polled sources) is a
                // logged no-op in that observer.
                sourceDisabledEvent.fire(new SourceDisabled(sourceId));
                // Per-source coalescing key so two different sources auto-disabled
                // inside one throttle window each emit their own notification — a
                // constant key (the error class) would suppress the second source's
                // notification as a duplicate. The error_class argument stays the
                // stable constant for operator scrapes keyed on error_class.
                throttledAdminNotifier.notifyOnce(
                    ERROR_CLASS_SOURCE_UNKNOWN_AUTO_DISABLE + ":" + sourceId,
                    ERROR_CLASS_SOURCE_UNKNOWN_AUTO_DISABLE,
                    "Source " + sourceId + " auto-disabled: UNKNOWN rate "
                        + String.format("%.2f", observedRate)
                        + " exceeds threshold " + unknownRateThreshold);
                LOG.warn("PerSourceUnknownTracker: disabled source {} (rate={} threshold={})",
                    sourceId, String.format("%.2f", observedRate),
                    String.format("%.2f", unknownRateThreshold));
            }
        } catch (SQLException e) {
            // SafeLog, never the raw Throwable (docs/spec/security.md
            // §Secrets handling — User content in exceptions).
            SafeLog.error(LOG, "PerSourceUnknownTracker: failed to disable source " + sourceId, e);
        }
    }

    /**
     * Signal that a source was auto-disabled, carrying the source id so an
     * observer can stop that source's running stream-source worker. Owned by
     * the producer (this tracker) so the re-eval bean needs no stream-module
     * type; the {@link app.zcat.infochat.collector.stream.nostr.NostrStreamSource}
     * Registrar observes it.
     */
    public record SourceDisabled(UUID sourceId) {
    }
}
