package app.zcat.infochat.collector.eval;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;

/**
 * Shared source of the {@code fetched_at} partition-scan floor for the
 * eval-pipeline pickup queries. The {@code post} table is
 * {@code RANGE(fetched_at)} partitioned, so a query without a
 * {@code fetched_at} lower bound forces the planner to scan every live
 * partition each tick; bounding {@code fetched_at >= scanWindowFloor(now)}
 * lets it prune partitions.
 *
 * <p>The window is the post retention horizon
 * ({@code infochat.partitions.retention-days.post}) widened by
 * {@link #PARTITION_SCAN_SLACK}. This is the single declaration of that
 * slack: the four eval-stage pickup queries (Embedding, Tagger, Entity,
 * ReadyPromoter) and the three other partition-pruning scanners
 * ({@code ReEvaluationJob}, {@code PerSourceUnknownTracker},
 * {@code NostrStreamSource}) all reference it, so the value cannot drift
 * across the scans (M1-412).
 *
 * <p>The semantic trade-off is the one those scanners already accept: a
 * post fetched longer ago than horizon+slack drops out of pickup, but
 * such a post is about to be partition-dropped anyway, so it can never
 * legitimately need processing.
 */
@ApplicationScoped
public class PartitionScan {

    // Slack added past the exact retention horizon so the floor never
    // excludes a post inside a live partition. The single source for every
    // partition-pruning scan in the collector — ReEvaluationJob,
    // PerSourceUnknownTracker, and NostrStreamSource reference this constant
    // rather than redeclaring it, so the slack cannot drift (M1-412).
    public static final Duration PARTITION_SCAN_SLACK = Duration.ofDays(2);

    @ConfigProperty(name = "infochat.partitions.retention-days.post")
    int postRetentionDays;

    /**
     * The pickup-query floor as an absolute instant: {@code now} minus the
     * retention horizon widened by {@link #PARTITION_SCAN_SLACK}. The
     * eval-stage pickup queries bind {@code fetched_at >= ?} to
     * {@code Timestamp.from(scanWindowFloor(clock.instant()))} — the instant
     * sampled from the injected {@code Clock} — instead of the in-SQL
     * {@code now() - ?::INTERVAL}, so the scan instant can be pinned in tests
     * (M1-448). Whole-day arithmetic ({@code (retention + slack) days}) keeps
     * the floor byte-for-byte aligned to the partition-pruning boundary under
     * the production {@code Clock.systemUTC()}. Mirrors
     * {@code ReEvaluationJob.scanWindowFloor} (M1-444).
     */
    public Instant scanWindowFloor(Instant now) {
        return now.minus(Duration.ofDays(postRetentionDays + PARTITION_SCAN_SLACK.toDays()));
    }
}
