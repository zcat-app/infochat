package io.infochat.provider.messaging;

import io.infochat.messaging.Identity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * MVP-legacy auto-register-on-first-DM service per
 * {@code docs/design/00-mvp.md} §4. The {@link InboundRouter} calls
 * {@link #resolveOrRegister} immediately after Unicode normalization
 * and before slash-prefix dispatch so every reachable
 * {@link CommandHandler} sees a stable {@code users.id} for the
 * sender.
 *
 * <p><b>SQL shape.</b> A single
 * {@code INSERT INTO users (...) VALUES (...) ON CONFLICT
 * (adapter, contact_id) DO NOTHING} statement, followed by a
 * {@code SELECT id FROM users WHERE adapter=? AND contact_id=?} to
 * read back the row identifier. The {@code ON CONFLICT DO NOTHING}
 * clause is the race-protection load-bearer: two concurrent first-DMs
 * from the same {@code contact_id} would otherwise race the V5
 * {@code UNIQUE (adapter, contact_id)} constraint and one transaction
 * would raise PostgreSQL 23505 (unique violation). The {@code DO
 * NOTHING} clause turns the second concurrent INSERT into a no-op;
 * the SELECT then reads the row the first transaction inserted.</p>
 *
 * <p><b>Hardcoded defaults.</b> {@code is_admin} is the literal
 * {@code FALSE} — no template, no per-deployment override path. A
 * future attacker who controls part of the {@link Identity} record
 * (e.g., a spoofed {@code contactId} under a LOW-trust adapter) must
 * not be able to elevate to admin via this path. The bootstrap-admin
 * {@code @Startup} bean (deferred) is the only path that sets
 * {@code is_admin=true}; it runs against a configured contact id, not
 * an arbitrary inbound user. {@code registration_state} is the
 * literal {@code 'invited'} — the closest match in the four-value V5
 * CHECK ({@code preban}, {@code group_only}, {@code invited},
 * {@code vouched}) for the MVP-legacy "DM-pathway-registered without
 * an explicit invite" semantics. T2-A's invite-gating ticket
 * naturally writes {@code 'invited'} when an actual invite consume
 * happens, so the value carries forward.</p>
 *
 * <p><b>Column omission.</b> The INSERT column list omits the
 * slow-start probation column so the V5 default (NULL) applies — "no
 * probation in effect," which is what MVP requires (slow-start tier
 * is deferred to T2-A). T2-A may retro-fit existing rows when the
 * probation tier is wired; that retro-fit is T2-A's call.</p>
 *
 * <p><b>No audit row.</b> Per {@code docs/design/00-mvp.md} §5, the
 * V5 closed action set does not include an {@code AUTO_REGISTER}
 * verb. MVP auto-register skips the audit insert entirely; T2-A
 * writes the {@code INVITE_CONSUME} row (which exists in the closed
 * set) at the moment invite-gated registration happens, replacing
 * this MVP-legacy "register-and-skip-audit" path. Adding
 * {@code AUTO_REGISTER} would be a spec amendment + a separate
 * {@code spec:} commit, not an in-flight edit here.</p>
 *
 * <p>T2-A's invite-gating ticket will replace the method body but
 * leave the call site (the {@link InboundRouter}'s intake point) and
 * the method signature unchanged — the seam is intentional.</p>
 */
@ApplicationScoped
public class AutoRegisterService {

    private static final String UPSERT_SQL =
            "INSERT INTO users (adapter, contact_id, display_name, is_admin, registration_state) "
                    + "VALUES (?, ?, ?, FALSE, 'invited') "
                    + "ON CONFLICT (adapter, contact_id) DO NOTHING";

    private static final String SELECT_ID_SQL =
            "SELECT id FROM users WHERE adapter = ? AND contact_id = ?";

    @Inject
    DataSource dataSource;

    /**
     * Resolve the {@code users.id} for the sender, inserting a row
     * iff one does not already exist for {@code (adapterName,
     * sender.contactId())}. Idempotent across concurrent first-DMs
     * from the same contact (the ON CONFLICT clause serializes them
     * against the UNIQUE constraint).
     *
     * @param sender      the inbound message's {@link Identity}; only
     *                    {@code contactId()} and {@code displayName()}
     *                    are consulted.
     * @param adapterName the adapter the inbound came from (e.g.,
     *                    {@code "inmemory"}, {@code "simplex"}).
     * @return the {@code users.id} UUID of the resolved or newly-
     *         inserted row.
     */
    public UUID resolveOrRegister(Identity sender, String adapterName) {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement insert = conn.prepareStatement(UPSERT_SQL)) {
                insert.setString(1, adapterName);
                insert.setString(2, sender.contactId());
                insert.setString(3, sender.displayName());
                insert.executeUpdate();
            }
            try (PreparedStatement select = conn.prepareStatement(SELECT_ID_SQL)) {
                select.setString(1, adapterName);
                select.setString(2, sender.contactId());
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next()) {
                        // Unreachable under ON CONFLICT DO NOTHING semantics: a row
                        // for (adapterName, contactId) must exist after the upsert.
                        // Surface as IllegalStateException so the test layer notices
                        // immediately if some future schema change breaks the
                        // invariant.
                        throw new IllegalStateException(
                                "users row missing after upsert: adapter=" + adapterName
                                        + " contact_id=" + sender.contactId());
                    }
                    return (UUID) rs.getObject(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "AutoRegisterService.resolveOrRegister failed for adapter="
                            + adapterName + " contact_id=" + sender.contactId(), e);
        }
    }
}
