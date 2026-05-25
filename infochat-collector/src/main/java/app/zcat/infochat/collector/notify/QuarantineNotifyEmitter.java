package app.zcat.infochat.collector.notify;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.jspecify.annotations.NonNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Emits {@code pg_notify('quarantine_review', ...)} with the tagged
 * JSON payload defined in {@code docs/spec/architecture.md}
 * §Inter-service communication. The payload shape is cursor-only:
 * {@code {"target_kind":"quarantine"|"post","target_id":"<uuid>","new_status":"<status>"}}.
 *
 * <p>Callers invoke {@link #emit} inside an existing JDBC transaction
 * so the NOTIFY commits or rolls back together with the state-machine
 * UPDATE that produced the event (same-transaction rule from
 * {@code architecture.md}).
 */
@ApplicationScoped
public class QuarantineNotifyEmitter {

    public static final String CHANNEL = "quarantine_review";

    private static final Logger LOG = Logger.getLogger(QuarantineNotifyEmitter.class);

    /**
     * Emit a quarantine_review NOTIFY inside the caller's transaction.
     *
     * @param conn         the caller's JDBC connection (must be in a transaction)
     * @param targetKind   discriminator: "quarantine" or "post"
     * @param targetId     the UUID of the quarantine row or post
     * @param newStatus    the status the target just transitioned to
     */
    public void emit(@NonNull Connection conn, @NonNull String targetKind,
                     @NonNull UUID targetId, @NonNull String newStatus) throws SQLException {
        String payload = "{\"target_kind\":\"" + targetKind
            + "\",\"target_id\":\"" + targetId
            + "\",\"new_status\":\"" + newStatus + "\"}";
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_notify(?, ?)")) {
            ps.setString(1, CHANNEL);
            ps.setString(2, payload);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
        LOG.debugf("quarantine_review NOTIFY: target_kind=%s target_id=%s new_status=%s",
            targetKind, targetId, newStatus);
    }
}
