package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.group.GroupRepository;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Implements {@code /status} per {@code docs/spec/commands.md}
 * §Discovery — runtime status (active profile, uptime; admin sees more).
 *
 * <p>The bot-admin view appends one extra line: a count of groups with
 * {@code approval_status='pending' AND removed_at IS NULL}, providing
 * passive discovery of groups awaiting approval without forcing the
 * admin to run {@code /list-groups}. Non-admin callers — and inbound
 * shapes whose caller cannot be resolved (no {@link InboundContext}
 * adapter / contact id) — see only the profile + uptime lines.</p>
 *
 * <p>Works in both DM and group scope. The actor's contact id resolves
 * via {@link InboundContext#senderContactId()}, matching the
 * {@link ListGroupsCommandHandler} convention. The admin-check is a
 * single SELECT against {@code users}; no audit row is written because
 * {@code /status} is a non-privileged read whose admin-only line is a
 * scalar count (no row-level enumeration).</p>
 */
@ApplicationScoped
public class StatusCommandHandler implements CommandHandler {

    private static final String SELECT_IS_ADMIN_SQL =
            "SELECT is_admin FROM users WHERE adapter = ? AND contact_id = ?";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    InboundContext inboundContext;

    @Inject
    GroupRepository groupRepository;

    @Inject
    DataSource dataSource;

    @ConfigProperty(name = "infochat.profile.label", defaultValue = "unknown")
    String profileLabel;

    // Captured at bean construction (Quarkus boot). The /status output
    // reports the JVM-side uptime, which is the operator-visible signal
    // the spec calls "runtime status".
    private final Instant startedAt = Instant.now();

    @Override
    public String name() {
        return "status";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        StringBuilder body = new StringBuilder();
        body.append(MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_STATUS_PROFILE, inboundContext.effectiveLanguage()),
                profileLabel));
        body.append('\n');
        body.append(MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_STATUS_UPTIME, inboundContext.effectiveLanguage()),
                formatUptime(Duration.between(startedAt, Instant.now()))));

        String adapter = inboundContext.adapterName();
        String callerContactId = inboundContext.senderContactId();
        if (adapter != null && callerContactId != null
                && lookupIsAdmin(adapter, callerContactId)) {
            body.append('\n');
            body.append(MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_STATUS_PENDING_GROUPS, inboundContext.effectiveLanguage()),
                    groupRepository.countPendingGroups()));
        }

        return new OutboundMessage(scope, body.toString(),
                Instant.now(), UUID.randomUUID().toString());
    }

    private boolean lookupIsAdmin(String adapter, String contactId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_IS_ADMIN_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return rs.getBoolean("is_admin");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "StatusCommandHandler.lookupIsAdmin failed for adapter="
                            + adapter, e);
        }
    }

    /**
     * Render a duration as {@code Nh Mm} or {@code Mm} when under an
     * hour. The {@code /status} spec asks for runtime status; second-
     * precision is not useful at an operator-discovery surface.
     */
    static String formatUptime(Duration uptime) {
        long totalMinutes = Math.max(0L, uptime.toMinutes());
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours == 0) {
            return minutes + "m";
        }
        return hours + "h " + minutes + "m";
    }
}
