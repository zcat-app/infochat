package app.zcat.infochat.collector.eval.reeval;

import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

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
 * current evaluation stage unaffected — the disable only prevents
 * new fetches from the source.
 */
@ApplicationScoped
public class PerSourceUnknownTracker {

    static final String ERROR_CLASS_SOURCE_UNKNOWN_AUTO_DISABLE = "source-unknown-auto-disable";

    private static final Logger LOG = Logger.getLogger(PerSourceUnknownTracker.class);

    @Inject
    DataSource dataSource;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @ConfigProperty(name = "infochat.reeval.unknown-rate-threshold")
    double unknownRateThreshold;

    @ConfigProperty(name = "infochat.reeval.unknown-rate-window")
    Duration unknownRateWindow;

    @ConfigProperty(name = "infochat.reeval.unknown-rate-min-sample", defaultValue = "5")
    int minSampleSize;

    @Scheduled(every = "{infochat.reeval.unknown-tracker-poll-interval}")
    public void onTick() {
        try {
            checkAllSources();
        } catch (SQLException e) {
            LOG.warn("PerSourceUnknownTracker: failed to check sources; skipping tick", e);
        }
    }

    void checkAllSources() throws SQLException {
        // Find active sources whose UNKNOWN rate within the window
        // exceeds the threshold. Uses the stage2_verdict column (V22)
        // to count only UNKNOWN verdicts, not INJECTION/MALWARE.
        final String sql =
            "SELECT s.id, "
                + "  COUNT(*) FILTER (WHERE p.stage2_verdict = 'UNKNOWN') AS unknown_count, "
                + "  COUNT(*) AS total_count "
                + "FROM source s "
                + "JOIN post p ON p.source_id = s.id "
                + "WHERE s.status = 'active' "
                + "  AND p.stage2_done = TRUE "
                + "  AND p.stage2_failed = FALSE "
                + "  AND p.status_changed_at >= now() - ?::INTERVAL "
                + "GROUP BY s.id "
                + "HAVING COUNT(*) >= ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, unknownRateWindow.toSeconds() + " seconds");
            ps.setInt(2, minSampleSize);
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
                throttledAdminNotifier.notifyOnce(
                    ERROR_CLASS_SOURCE_UNKNOWN_AUTO_DISABLE,
                    ERROR_CLASS_SOURCE_UNKNOWN_AUTO_DISABLE,
                    "Source " + sourceId + " auto-disabled: UNKNOWN rate "
                        + String.format("%.2f", observedRate)
                        + " exceeds threshold " + unknownRateThreshold);
                LOG.warnf("PerSourceUnknownTracker: disabled source %s (rate=%.2f threshold=%.2f)",
                    sourceId, observedRate, unknownRateThreshold);
            }
        } catch (SQLException e) {
            LOG.errorf(e, "PerSourceUnknownTracker: failed to disable source %s", sourceId);
        }
    }
}
