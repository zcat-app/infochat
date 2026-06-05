package app.zcat.infochat.collector.notify;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

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
 *
 * <p>Every interpolated payload value comes from a closed set — the
 * two enums below plus a {@link UUID} — so building the JSON by
 * concatenation cannot produce an injectable or malformed payload.
 */
@ApplicationScoped
public class QuarantineNotifyEmitter {

    public static final String CHANNEL = "quarantine_review";

    private static final Logger LOG = Logger.getLogger(QuarantineNotifyEmitter.class);

    /**
     * The payload's {@code target_kind} discriminator — the spec
     * constrains it to {@code 'quarantine'} (a quarantine
     * state-machine move) or {@code 'post'} (a
     * {@code post.status → NEEDS_REVIEW} transition).
     */
    public enum TargetKind {
        QUARANTINE("quarantine"),
        POST("post");

        private final String wireValue;

        TargetKind(String wireValue) {
            this.wireValue = wireValue;
        }

        String wireValue() {
            return wireValue;
        }
    }

    /**
     * The closed set of {@code new_status} values the channel carries
     * per {@code architecture.md} §Inter-service communication —
     * quarantine state-machine statuses plus the post-side
     * {@link #NEEDS_REVIEW}. The enum constant name IS the wire value.
     */
    public enum NewStatus {
        PENDING,
        BENIGN_CLOSED,
        APPROVED,
        REJECTED,
        NEEDS_REVIEW
    }

    /**
     * Emit a quarantine_review NOTIFY inside the caller's transaction.
     *
     * @param conn         the caller's JDBC connection (must be in a transaction)
     * @param targetKind   discriminator: quarantine state-machine move or post transition
     * @param targetId     the UUID of the quarantine row or post
     * @param newStatus    the status the target just transitioned to
     */
    public void emit(Connection conn, TargetKind targetKind,
                     UUID targetId, NewStatus newStatus) throws SQLException {
        String payload = "{\"target_kind\":\"" + targetKind.wireValue()
            + "\",\"target_id\":\"" + targetId
            + "\",\"new_status\":\"" + newStatus.name() + "\"}";
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_notify(?, ?)")) {
            ps.setString(1, CHANNEL);
            ps.setString(2, payload);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
        LOG.debugf("quarantine_review NOTIFY: target_kind=%s target_id=%s new_status=%s",
            targetKind.wireValue(), targetId, newStatus);
    }
}
