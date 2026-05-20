package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.Identity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Authorization step 3 group-registration service per
 * docs/spec/security.md §Authorization model. The intake-step
 * splice (M1-044b) calls {@link #resolveOrRegisterGroup} on the
 * first non-banned {@code @mention} of the bot in a new group;
 * a row is inserted under {@code registration_state = 'group_only'}
 * with a slow-start probation expiry. The DM-unknown contact path
 * — formerly handled here in the MVP-legacy shape — now routes
 * through {@link InviteCodeConsumer} at step 2.
 *
 * <p><b>UPSERT shape.</b> {@code INSERT ... ON CONFLICT (adapter,
 * contact_id) DO NOTHING}; the {@code SELECT id} reads back the
 * row whether it was just inserted or already existed. Idempotent
 * across concurrent first-{@code @mentions} from the same contact
 * — the UNIQUE (adapter, contact_id) constraint is the
 * serialization point.</p>
 *
 * <p><b>Probation window.</b> {@code probation_until = NOW() +
 * @ConfigProperty(infochat.probation.duration, defaultValue="24h")}
 * — every newly-registered contact begins in the slow-start tier
 * per D45 / docs/design/03-commands.md §3.3.</p>
 *
 * <p><b>InboundRouter compatibility seam.</b> The deprecated
 * {@link #resolveOrRegister} method exists solely so M1-035d's
 * call site at {@code InboundRouter.onMessage} continues to
 * compile under M1-044a's narrowed contract. M1-044b removes the
 * call site as part of the intake-step splice and a follow-up
 * removes the deprecated method here.</p>
 */
@ApplicationScoped
public class AutoRegisterService {

    /**
     * The {@code registration_state} value written for newly-
     * registered users per spec §Authorization model step 3
     * (the four-value V5 CHECK enumerates
     * {@code 'preban','group_only','invited','vouched'}).
     */
    static final String REGISTRATION_STATE_GROUP_ONLY = "group_only";

    private static final String UPSERT_SQL =
            "INSERT INTO users (adapter, contact_id, display_name, "
                    + "is_admin, registration_state, probation_until) "
                    + "VALUES (?, ?, ?, FALSE, '" + REGISTRATION_STATE_GROUP_ONLY + "', ?) "
                    + "ON CONFLICT (adapter, contact_id) DO NOTHING";

    private static final String SELECT_ID_SQL =
            "SELECT id FROM users WHERE adapter = ? AND contact_id = ?";

    @ConfigProperty(name = "infochat.probation.duration", defaultValue = "24h")
    Duration probationDuration;

    @Inject
    DataSource dataSource;

    /**
     * Group-registration path: insert a row under
     * {@code registration_state = 'group_only'} with
     * {@code probation_until = NOW() + slow_start_window}, or read
     * back the existing row if one already matches
     * {@code (adapterName, sender.contactId())}. Idempotent.
     */
    public UUID resolveOrRegisterGroup(Identity sender, String adapterName) {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement insert = conn.prepareStatement(UPSERT_SQL)) {
                insert.setString(1, adapterName);
                insert.setString(2, sender.contactId());
                insert.setString(3, sender.displayName());
                insert.setObject(4, OffsetDateTime.now().plus(probationDuration));
                insert.executeUpdate();
            }
            try (PreparedStatement select = conn.prepareStatement(SELECT_ID_SQL)) {
                select.setString(1, adapterName);
                select.setString(2, sender.contactId());
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next()) {
                        // Unreachable under ON CONFLICT DO NOTHING: the row exists
                        // after the upsert. Surface as IllegalStateException so a
                        // future schema change breaking the invariant fails loud.
                        throw new IllegalStateException(
                                "users row missing after upsert: adapter=" + adapterName
                                        + " contact_id=" + sender.contactId());
                    }
                    return rs.getObject(1, UUID.class);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "AutoRegisterService.resolveOrRegisterGroup failed for adapter="
                            + adapterName + " contact_id=" + sender.contactId(), e);
        }
    }

    /**
     * Compatibility pass-through for M1-035d's InboundRouter call
     * site. M1-044b removes the call site as part of the intake
     * splice; a follow-up removes this method.
     *
     * @deprecated use {@link #resolveOrRegisterGroup} from the
     *     group {@code @mention} path; the DM-unknown contact path
     *     routes through {@link InviteCodeConsumer} at step 2.
     */
    @Deprecated
    public UUID resolveOrRegister(Identity sender, String adapterName) {
        return resolveOrRegisterGroup(sender, adapterName);
    }
}
