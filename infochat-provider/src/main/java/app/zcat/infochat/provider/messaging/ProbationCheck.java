package app.zcat.infochat.provider.messaging;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
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
 * the user is promoted at the instant {@code now > probation_until}
 * regardless of whether the column has been nulled.
 * {@link #inProbation} reads {@code probation_until} and gates it on
 * {@code clock.instant()} — the injected {@code Clock}, not SQL
 * {@code NOW()}, so the read shares one clock with
 * {@code InviteCodeConsumer}'s write (M1-447/M1-450) and is pinnable
 * in tests; {@link #clearIfPromoted} runs the opportunistic UPDATE
 * that nulls the column on the next request from a promoted user.
 * No background job is required.
 *
 * <p><b>Per-method cost.</b> Each call is one short prepared
 * statement against the {@code users} primary key (one indexed
 * read or write).
 */
@ApplicationScoped
public class ProbationCheck {

    private static final String SELECT_PROBATION_UNTIL_SQL =
            "SELECT probation_until FROM users WHERE id = ?";

    private static final String CLEAR_IF_PROMOTED_SQL =
            "UPDATE users SET probation_until = NULL "
                    + "WHERE id = ? AND probation_until IS NOT NULL AND probation_until <= NOW()";

    @Inject
    DataSource dataSource;

    // The probation-expiry read is gated on the injected Clock, never SQL
    // NOW(), so the gate is deterministic under a fixed test clock
    // (ProbationCheckClockIT) and the read shares one clock with
    // InviteCodeConsumer's app-Clock write of probation_until (M1-447/M1-450).
    // Field-injected with a systemUTC() initializer so the field stays non-null
    // for hand-constructed instances (NoopProbationCheck and InboundRouter tests
    // build subclasses directly, bypassing CDI); injection overrides it in the
    // managed bean. The CDI producer is ThrottledAdminNotifier.systemUtcClock();
    // production behaviour is byte-for-byte preserved under Clock.systemUTC().
    // (M1-450, pattern from M1-444 ReEvaluationJob / M1-447 GroupAutoPromoteService)
    @Inject
    Clock clock = Clock.systemUTC();

    /**
     * @return {@code true} iff a {@code users} row exists for
     *         {@code userId} whose {@code probation_until} is strictly
     *         after the injected {@code Clock}'s current instant.
     *         An absent row or a NULL/past {@code probation_until}
     *         returns {@code false} (already promoted; the row
     *         passes step 5 unchanged).
     */
    public boolean inProbation(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_PROBATION_UNTIL_SQL)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                Timestamp probationUntil = rs.getTimestamp("probation_until");
                return probationUntil != null
                        && probationUntil.toInstant().isAfter(clock.instant());
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
