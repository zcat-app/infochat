package app.zcat.infochat.collector.eval.reeval;

import app.zcat.infochat.collector.eval.PartitionScan;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

    // Both scan-window floors are computed in Java from one sample of the
    // injected Clock and bound as Timestamps (see checkAllSources), never SQL
    // now(), so the window can be pinned under a fixed test clock (M1-448). One
    // sample feeds both the fetched_at and status_changed_at floors so they can
    // never diverge (the no-split rule). The systemUTC() initializer is what
    // the CDI producer supplies; injection overrides it in the managed bean.
    @Inject
    Clock clock = Clock.systemUTC();

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
        // fetched_at is widened by the shared PartitionScan.PARTITION_SCAN_SLACK
        // so it never excludes a post inside the status_changed_at window — the
        // slack must exceed the worst-case lag between a post's fetched_at and
        // its stage-2 verdict; status_changed_at stays the precise
        // recency-of-verdict signal the rate is computed over.
        //
        // Both floors are Java-computed from ONE sample of the injected Clock
        // and bound as Timestamps (M1-448): a single sample feeds both so they
        // share an instant, preserving the equality the two in-SQL now() calls
        // gave them, and the window is pinnable under a fixed test clock.
        // Seconds granularity matches the prior ?::INTERVAL seconds strings so
        // the floors stay byte-for-byte under Clock.systemUTC().
        final String sql =
            "SELECT s.id, "
                + "  COUNT(*) FILTER (WHERE p.stage2_verdict = 'UNKNOWN') AS unknown_count, "
                + "  COUNT(*) AS total_count "
                + "FROM source s "
                + "JOIN post p ON p.source_id = s.id "
                // Candidates are active sources PLUS rows the D42 fetch ladder
                // already parked (D42 property (b), M1-754): recovery rights
                // depend on the recorded reason, so this security control must
                // be able to UPGRADE a 'fetch-failure' park to 'unknown-rate'
                // — otherwise a source that failed its way into a re-probe-
                // eligible park first could never be marked as a security park
                // and the re-probe ladder would auto-readmit it. Positive
                // equality on the reason: 'stream-cycle-cap' (already manual-
                // only) is never relabeled.
                + "WHERE (s.status = 'active' "
                + "       OR (s.status = 'failed' AND s.park_reason = 'fetch-failure')) "
                + "  AND p.stage2_done = TRUE "
                + "  AND p.stage2_failed = FALSE "
                + "  AND p.fetched_at >= ? "
                + "  AND p.status_changed_at >= ? "
                + "GROUP BY s.id "
                + "HAVING COUNT(*) >= ?";
        Instant now = clock.instant();
        long windowSeconds = unknownRateWindow.toSeconds();
        Instant fetchedFloor =
            now.minus(Duration.ofSeconds(windowSeconds + PartitionScan.PARTITION_SCAN_SLACK.toSeconds()));
        Instant statusChangedFloor = now.minus(Duration.ofSeconds(windowSeconds));
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(fetchedFloor));
            ps.setTimestamp(2, Timestamp.from(statusChangedFloor));
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
        // The WHERE mirrors the candidate selection (D42 property (b), M1-754):
        // a fresh park of an active row AND an upgrade of a 'fetch-failure'
        // park both land 'unknown-rate' in the same guarded statement — never
        // a downgrade of a manual-only reason. COALESCE keeps the original
        // parked-since stamp on the upgrade path (the row has been dark since
        // the fetch ladder parked it, not since the upgrade).
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE source SET status = 'failed', "
                     + "park_reason = 'unknown-rate', "
                     + "parked_at = COALESCE(parked_at, now()) "
                     + "WHERE id = ? AND (status = 'active' "
                     + "  OR (status = 'failed' AND park_reason = 'fetch-failure'))")) {
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
