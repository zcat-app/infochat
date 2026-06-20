package app.zcat.infochat.collector.eval;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * Shared source of the {@code fetched_at} partition-scan floor for the
 * eval-pipeline pickup queries. The {@code post} table is
 * {@code RANGE(fetched_at)} partitioned, so a query without a
 * {@code fetched_at} lower bound forces the planner to scan every live
 * partition each tick; bounding {@code fetched_at >= now() - scanWindow()}
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
     * The pickup-query floor as a PostgreSQL {@code INTERVAL} string
     * (e.g. {@code "32 days"}), bound to {@code now() - ?::INTERVAL}.
     */
    public String scanWindow() {
        return (postRetentionDays + PARTITION_SCAN_SLACK.toDays()) + " days";
    }
}
