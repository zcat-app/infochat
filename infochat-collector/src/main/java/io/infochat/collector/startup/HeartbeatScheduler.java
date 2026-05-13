package io.infochat.collector.startup;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Refreshes the Collector's {@code heartbeat.last_seen_at} on every tick of
 * {@code infochat.heartbeat.interval} (per-profile default in
 * {@code docs/design/07-deployment.md} §7.2.1). The lock-acquisition path in
 * {@link InstanceLockGuard} writes the row; this scheduler keeps the
 * fingerprint fresh so a rejected acquirer can distinguish a live holder
 * from a stale one (§7.8.5 staleness threshold).
 *
 * <p>Uses a transient pool connection per tick rather than the long-lived
 * session that owns the advisory lock — the lock is session-scoped, but the
 * {@code UPDATE} on {@code heartbeat} does not need to be co-located with it.
 */
@ApplicationScoped
public class HeartbeatScheduler {

    @Inject
    DataSource dataSource;

    @Scheduled(every = "{infochat.heartbeat.interval}")
    void tick() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE heartbeat SET last_seen_at = now() WHERE service = ?")) {
            ps.setString(1, InstanceLockGuard.SERVICE);
            ps.executeUpdate();
        }
    }
}
