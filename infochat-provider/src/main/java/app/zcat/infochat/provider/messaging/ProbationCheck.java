package app.zcat.infochat.provider.messaging;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Authorization step 5 slow-start probation gate per
 * {@code docs/spec/security.md} §Slow-start tier and
 * §Authorization model. The intake-step splice (M1-045) consults
 * {@link #inProbation} immediately after the ban check (step 4)
 * and before parse (step 6).
 *
 * <p><b>Lazy promotion.</b> The spec mandates a passive sweep:
 * the user is promoted at the instant {@code NOW() > probation_until}
 * regardless of whether the column has been nulled.
 * {@link #inProbation} consults {@code probation_until IS NOT NULL
 * AND probation_until > NOW()}; {@link #clearIfPromoted} runs the
 * opportunistic UPDATE that nulls the column on the next request
 * from a promoted user. No background job is required.
 *
 * <p><b>Per-method cost.</b> Each call is one short prepared
 * statement against the {@code users} primary key (one indexed
 * read). On the happy path the gate makes exactly one
 * {@link #inProbation} read; on the post-promotion-cleanup path
 * the gate makes one {@link #inProbation} read plus one
 * {@link #clearIfPromoted} UPDATE (only until the column nulls).
 * On the blocked-during-probation reply path the gate additionally
 * makes one {@link #probationExpiry} read to populate the
 * {@code {0}} time-until-unlock token; this third read is rare
 * (only when a probation user invokes a blocked command).
 */
@ApplicationScoped
public class ProbationCheck {

    private static final String SELECT_IN_PROBATION_SQL =
            "SELECT 1 FROM users WHERE id = ? AND probation_until IS NOT NULL AND probation_until > NOW()";

    private static final String CLEAR_IF_PROMOTED_SQL =
            "UPDATE users SET probation_until = NULL "
                    + "WHERE id = ? AND probation_until IS NOT NULL AND probation_until <= NOW()";

    private static final String SELECT_PROBATION_UNTIL_SQL =
            "SELECT probation_until FROM users WHERE id = ?";

    @Inject
    DataSource dataSource;

    /**
     * @return {@code true} iff a {@code users} row exists for
     *         {@code userId} with {@code probation_until > NOW()}.
     *         An absent row or a NULL/past {@code probation_until}
     *         returns {@code false} (already promoted; the row
     *         passes step 5 unchanged).
     */
    public boolean inProbation(@NonNull UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_IN_PROBATION_SQL)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ProbationCheck.inProbation failed for userId=" + userId, e);
        }
    }

    /**
     * Opportunistic UPDATE that nulls {@code probation_until} for a
     * row whose probation window has elapsed. The WHERE clause is
     * the idempotency guard: the UPDATE matches zero rows after the
     * first call (no harm to call repeatedly), and matches zero
     * rows for a still-probation or never-probation row. Spec says
     * "no background job is required" — the next inbound from a
     * graduated user clears the column inline.
     */
    public void clearIfPromoted(@NonNull UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(CLEAR_IF_PROMOTED_SQL)) {
            ps.setObject(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ProbationCheck.clearIfPromoted failed for userId=" + userId, e);
        }
    }

    /**
     * @return the {@code probation_until} {@link Instant} for the
     *         row, or {@code null} when the row is missing or
     *         {@code probation_until} is NULL. Used by
     *         {@code InboundRouter}'s blocked-during-probation
     *         reply path to interpolate the {@code {0}} time-
     *         until-unlock token in {@code error.probation.blocked}
     *         without widening the per-dispatch {@code UserSnapshot}
     *         column set.
     */
    public @Nullable Instant probationExpiry(@NonNull UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_PROBATION_UNTIL_SQL)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Timestamp ts = rs.getTimestamp("probation_until");
                return ts == null ? null : ts.toInstant();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ProbationCheck.probationExpiry failed for userId=" + userId, e);
        }
    }
}
