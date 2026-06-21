package app.zcat.infochat.provider.scheduler;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;

/**
 * Removes chat_memory, chat_session (cascades to chat_message), and
 * summary_anchor rows older than the profile-driven retention horizon
 * (Invariant 9, D37/D40): 90 days on laptop/vps/remote-llm, 30 days on pi
 * (docs/design/02-schema.md §2.10).
 */
@ApplicationScoped
public class ChatMemoryPruner {

    private static final Logger LOG = Logger.getLogger(ChatMemoryPruner.class);

    @Inject
    DataSource dataSource;

    // Resolved from application.properties per profile: 90 days
    // (laptop/vps/remote-llm), 30 days (pi). No inline defaultValue per the
    // profile-driven-key convention (AssetSnapshotFetcher §"Profile-driven
    // cadence") — a missing key must fail startup loudly, not silently apply
    // 90 days to every profile (the defect this key existing fixes).
    @ConfigProperty(name = "infochat.chat.retention")
    Duration retention;

    @Scheduled(every = "{infochat.chat.pruner.interval:24h}")
    void prune() throws SQLException {
        // Bind whole seconds, not toDays(): a sub-day retention (e.g. PT12H)
        // truncated to 0 days would make the cutoff "now()" and delete every row.
        long seconds = retention.toSeconds();
        int total = 0;
        try (Connection conn = dataSource.getConnection()) {
            total += deleteOlderThan(conn,
                "DELETE FROM chat_memory WHERE created_at < now() - make_interval(secs => ?)",
                seconds);
            // chat_session ON DELETE CASCADE removes chat_message rows automatically.
            total += deleteOlderThan(conn,
                "DELETE FROM chat_session WHERE updated_at < now() - make_interval(secs => ?)",
                seconds);
            total += deleteOlderThan(conn,
                "DELETE FROM summary_anchor WHERE generated_at < now() - make_interval(secs => ?)",
                seconds);
        }
        if (total > 0) {
            LOG.infof("Chat-memory pruner removed %d rows (horizon=%s)", total, retention);
        }
    }

    private int deleteOlderThan(Connection conn, String sql, long seconds)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, seconds);
            return ps.executeUpdate();
        }
    }
}
