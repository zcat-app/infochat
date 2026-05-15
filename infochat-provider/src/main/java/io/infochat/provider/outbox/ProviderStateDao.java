package io.infochat.provider.outbox;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Narrow JDBC wrapper over {@code provider_state} — the per-channel
 * high-water-mark cursor table (docs/design/02-schema.md §2.9.2). The DAO
 * carries exactly two SQL shapes:
 *
 * <ol>
 *   <li><b>Read cursor</b> ({@link #readCursor(String)}) — returns the stored
 *       cursor tuple for the named channel.</li>
 *   <li><b>Compare-and-swap update</b> ({@link #advanceCursor}) — writes a new
 *       cursor iff it is strictly greater than the stored one on the compound
 *       tuple. A slow processor cannot roll back a fast one's mark.</li>
 * </ol>
 *
 * <p>The compound-cursor predicate (NOT {@code cursor_high} alone) is
 * load-bearing per docs/spec/schema.md §Operational: two events sharing a
 * {@code cursor_high} must both be processed on catch-up — the earlier
 * advances the mark to itself, the later advances it to itself in the same
 * transaction as its side effect.
 *
 * <p>This DAO is the SOLE production code path that writes
 * {@code provider_state}. Both {@link NewPostReconciler} and
 * {@link NewPostListener} advance the cursor through {@link NewPostHandler},
 * which delegates to this DAO inside its {@code @Transactional} boundary so
 * the cursor advance and the handler's side effect commit atomically.
 */
@ApplicationScoped
public class ProviderStateDao {

    @Inject
    DataSource dataSource;

    /**
     * Reads the current cursor for the named channel.
     *
     * <p>Returns empty if no row exists for the channel. The first-boot
     * INSERT in V9__provider_state.sql seeds the {@code new_post} row at
     * migration time, so a healthy deployment never observes empty here.
     */
    public Optional<Cursor> readCursor(String channel) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT cursor_high, cursor_low_kind, cursor_low_id "
                     + "FROM provider_state WHERE channel = ?")) {
            ps.setString(1, channel);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Cursor(
                    rs.getTimestamp("cursor_high").toInstant(),
                    rs.getString("cursor_low_kind"),
                    rs.getString("cursor_low_id")));
            }
        }
    }

    /**
     * Compare-and-swap cursor advance per docs/design/02-schema.md §2.9.2.
     *
     * <p>The UPDATE's predicate uses tuple-{@code <} comparison on
     * {@code (cursor_high, cursor_low_kind, cursor_low_id)} so the row is
     * mutated only when the supplied cursor is STRICTLY greater than the
     * stored one. A duplicate NOTIFY (cursor equal) or an out-of-order
     * dispatch (cursor earlier) is a no-op — the idempotency promise from
     * docs/spec/architecture.md §Inter-service communication §Catch-up
     * ("a duplicate NOTIFY or a repeated catch-up pass for the same row
     * produces no additional side effect").
     *
     * <p>When invoked inside a JTA {@code @Transactional} boundary (the
     * expected call site is {@link NewPostHandler#handle}), the underlying
     * {@link DataSource#getConnection()} returns the connection enlisted in
     * the current transaction. The UPDATE therefore commits atomically with
     * the handler's side effect — the same-transaction invariant from
     * docs/spec/architecture.md §Catch-up ("the high-water mark advances
     * both fields in the same DB transaction as the side effect it
     * triggers, making processing idempotent").
     *
     * @return {@code true} if the cursor advanced; {@code false} if the CAS
     *     was a no-op because the stored cursor was already at or past the
     *     supplied cursor.
     */
    public boolean advanceCursor(String channel, Instant newHigh, String newKind, String newId)
            throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE provider_state "
                     + "   SET cursor_high     = ?, "
                     + "       cursor_low_kind = ?, "
                     + "       cursor_low_id   = ?, "
                     + "       updated_at      = now() "
                     + " WHERE channel = ? "
                     + "   AND (cursor_high, cursor_low_kind, cursor_low_id) < (?, ?, ?)")) {
            Timestamp ts = Timestamp.from(newHigh);
            ps.setTimestamp(1, ts);
            ps.setString(2, newKind);
            ps.setString(3, newId);
            ps.setString(4, channel);
            ps.setTimestamp(5, ts);
            ps.setString(6, newKind);
            ps.setString(7, newId);
            return ps.executeUpdate() == 1;
        }
    }

    /** Immutable cursor tuple returned by {@link #readCursor(String)}. */
    public record Cursor(Instant cursorHigh, String cursorLowKind, String cursorLowId) {}
}
