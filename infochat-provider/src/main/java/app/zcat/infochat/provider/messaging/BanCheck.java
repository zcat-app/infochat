package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.log.ContactIds;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Authorization step 4 ban gate per docs/spec/security.md §User ban
 * and §Authorization model. The intake-step splice (M1-044b) consults
 * {@link #isBanned} immediately after invite-gate consume and before
 * any LLM, DB write, or command dispatch beyond the drop counter.
 *
 * <p><b>Fail-closed shape.</b> An absent {@code users} row returns
 * {@code false} — "not banned, fall through to step 2's invite gate."
 * The downstream gate is what catches the unknown-contact case; this
 * service answers a single question: "does a row exist with
 * {@code is_banned = TRUE} for this (adapter, contact_id)?"</p>
 *
 * <p><b>Per-(adapter, contact_id) isolation</b> (D46): the WHERE
 * clause keys on both columns so a banned contact on adapter A does
 * not affect adapter B. The V5 UNIQUE (adapter, contact_id)
 * constraint guarantees at most one row matches.</p>
 */
@ApplicationScoped
public class BanCheck {

    private static final String SELECT_IS_BANNED_SQL =
            "SELECT is_banned FROM users WHERE adapter = ? AND contact_id = ?";

    @Inject
    DataSource dataSource;

    /**
     * @return {@code true} iff a {@code users} row exists for
     *         {@code (adapter, contactId)} with {@code is_banned = TRUE}.
     *         An absent row returns {@code false} (fail-closed: the
     *         caller falls through to the invite gate).
     */
    public boolean isBanned(String adapter, String contactId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_IS_BANNED_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "BanCheck.isBanned failed for adapter=" + adapter
                            + " contact_id=" + ContactIds.redact(contactId), e);
        }
    }
}
