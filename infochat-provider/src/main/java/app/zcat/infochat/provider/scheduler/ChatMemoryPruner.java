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
 * (Invariant 9, D37/D40).
 */
@ApplicationScoped
public class ChatMemoryPruner {

    private static final Logger LOG = Logger.getLogger(ChatMemoryPruner.class);

    @Inject
    DataSource dataSource;

    // 90 days default (laptop/vps/remote-llm); Pi overrides to 720h (30 days).
    @ConfigProperty(name = "infochat.chat.retention", defaultValue = "PT2160H")
    Duration retention;

    @Scheduled(every = "{infochat.chat.pruner.interval:24h}")
    void prune() throws SQLException {
        int days = (int) retention.toDays();
        int total = 0;
        try (Connection conn = dataSource.getConnection()) {
            total += deleteOlderThan(conn,
                "DELETE FROM chat_memory WHERE created_at < now() - make_interval(days => ?)",
                days);
            // chat_session ON DELETE CASCADE removes chat_message rows automatically.
            total += deleteOlderThan(conn,
                "DELETE FROM chat_session WHERE updated_at < now() - make_interval(days => ?)",
                days);
            total += deleteOlderThan(conn,
                "DELETE FROM summary_anchor WHERE generated_at < now() - make_interval(days => ?)",
                days);
        }
        if (total > 0) {
            LOG.infof("Chat-memory pruner removed %d rows (horizon=%d days)", total, days);
        }
    }

    private int deleteOlderThan(Connection conn, String sql, int days)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, days);
            return ps.executeUpdate();
        }
    }
}
