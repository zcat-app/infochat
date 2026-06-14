package app.zcat.infochat.provider.messaging;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Live slow-start probation queries against the {@code users} row,
 * per {@code docs/spec/security.md} §Slow-start tier and
 * §Authorization model. Used by callers that do NOT already hold a
 * per-dispatch {@code InboundRouter.UserSnapshot}: {@link #inProbation}
 * answers "is this user still in probation" for the admin/help command
 * handlers (grant-admin, revoke-admin, /help) reached downstream of
 * dispatch. {@code InboundRouter}'s step-5 gate no longer calls
 * {@link #inProbation} — it reads {@code probation_until} from its
 * single per-dispatch users-row snapshot (M1-364) — but still calls
 * {@link #clearIfPromoted} on the first post-graduation inbound.
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
 * read or write).
 */
@ApplicationScoped
public class ProbationCheck {

    private static final String SELECT_IN_PROBATION_SQL =
            "SELECT 1 FROM users WHERE id = ? AND probation_until IS NOT NULL AND probation_until > NOW()";

    private static final String CLEAR_IF_PROMOTED_SQL =
            "UPDATE users SET probation_until = NULL "
                    + "WHERE id = ? AND probation_until IS NOT NULL AND probation_until <= NOW()";

    @Inject
    DataSource dataSource;

    /**
     * @return {@code true} iff a {@code users} row exists for
     *         {@code userId} with {@code probation_until > NOW()}.
     *         An absent row or a NULL/past {@code probation_until}
     *         returns {@code false} (already promoted; the row
     *         passes step 5 unchanged).
     */
    public boolean inProbation(UUID userId) {
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
    public void clearIfPromoted(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(CLEAR_IF_PROMOTED_SQL)) {
            ps.setObject(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ProbationCheck.clearIfPromoted failed for userId=" + userId, e);
        }
    }
}
