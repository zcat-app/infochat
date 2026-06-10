package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implements {@code /group-timezone <tz>} per
 * {@code docs/spec/commands.md} §Conversation control: group scope,
 * requires group-admin or bot-admin caller, validates IANA zone name,
 * updates {@code groups.timezone}, audit-logs before effect.
 */
@ApplicationScoped
public class GroupTimezoneCommandHandler implements CommandHandler {

    private static final String SELECT_ACTOR_SQL =
            "SELECT id, is_admin FROM users "
                    + "WHERE adapter = ? AND contact_id = ?";

    private static final String CHECK_GROUP_ADMIN_SQL =
            "SELECT is_group_admin FROM group_membership "
                    + "WHERE group_id = ? AND user_id = ? AND removed_at IS NULL";

    private static final String SELECT_GROUP_SQL =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ? "
                    + "AND removed_at IS NULL";

    private static final String UPDATE_TIMEZONE_SQL =
            "UPDATE groups SET timezone = ? WHERE id = ?";

    private static final int MAX_SUGGESTIONS = 5;

    @Inject DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject AuditLogWriter auditLogWriter;

    @Override
    public String name() {
        return "group-timezone";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        if (!(scope instanceof ScopeRef.Group group)) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_GROUP_TIMEZONE_DM_SCOPE, inboundContext.effectiveLanguage()));
        }

        String adapter = inboundContext.adapterName();
        String callerContactId = inboundContext.senderContactId();

        String tzArg = parseTimezone(rawText);
        if (tzArg == null || tzArg.isBlank()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_GROUP_TIMEZONE_NOT_ADMIN, inboundContext.effectiveLanguage()));
        }

        // Validate IANA zone
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(tzArg);
        } catch (DateTimeException e) {
            String suggestions = fuzzySuggestions(tzArg);
            String replyText = MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_GROUP_TIMEZONE_INVALID_ZONE, inboundContext.effectiveLanguage()),
                    tzArg, suggestions);
            return reply(scope, replyText);
        }

        String requestId = UUID.randomUUID().toString();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Resolve actor
                ActorRow actor = resolveActor(conn, adapter, callerContactId);
                if (actor == null) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_GROUP_TIMEZONE_NOT_ADMIN, inboundContext.effectiveLanguage()));
                }

                // Resolve group
                UUID groupId = resolveGroupInTx(conn, adapter, group.adapterGroupId());

                // Authorization: group-admin OR bot-admin
                if (!actor.isAdmin && !isGroupAdmin(conn, groupId, actor.id)) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_GROUP_TIMEZONE_NOT_ADMIN, inboundContext.effectiveLanguage()));
                }

                // Audit before effect
                RedactionHook.AuditRow auditRow = RedactionHook.AuditRow.builder()
                        .actorUserId(actor.id)
                        .actorContactId(callerContactId)
                        .actorAdapter(adapter)
                        .action(AuditAction.SET_TIMEZONE)
                        .targetKind("group")
                        .targetId(groupId.toString())
                        .scopeId(groupId)
                        .requestId(requestId)
                        .detailsJson("{\"timezone\":\"" + zoneId.getId() + "\"}")
                        .build();
                auditLogWriter.write(conn, auditRow);

                // Update timezone
                updateTimezone(conn, groupId, zoneId.getId());

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "GroupTimezoneCommandHandler failed for adapter=" + adapter
                                + " caller=" + ContactIds.redact(callerContactId), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "GroupTimezoneCommandHandler connection failed for adapter=" + adapter, e);
        }

        String replyText = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_GROUP_TIMEZONE_SUCCESS, inboundContext.effectiveLanguage()),
                zoneId.getId());
        return reply(scope, replyText);
    }

    private @Nullable ActorRow resolveActor(Connection conn, String adapter,
                                  String contactId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_ACTOR_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new ActorRow(
                        (UUID) rs.getObject("id"),
                        rs.getBoolean("is_admin"));
            }
        }
    }

    private boolean isGroupAdmin(Connection conn, UUID groupId,
                                 UUID userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(CHECK_GROUP_ADMIN_SQL)) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean("is_group_admin");
            }
        }
    }

    private UUID resolveGroupInTx(Connection conn, String adapter,
                                  String upstreamGroupId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_GROUP_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "GroupTimezoneCommandHandler: group not found for adapter=" + adapter);
                }
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void updateTimezone(Connection conn, UUID groupId,
                                String timezone) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_TIMEZONE_SQL)) {
            ps.setString(1, timezone);
            ps.setObject(2, groupId);
            ps.executeUpdate();
        }
    }

    static String fuzzySuggestions(String input) {
        // Locale.ROOT folding per commands.md §Surface conventions: the
        // default-locale toLowerCase() breaks IANA-zone matching on a
        // Turkish-locale JVM (dotless-i), where "Istanbul" would not
        // fold to "istanbul".
        String lowerInput = input.toLowerCase(Locale.ROOT);
        List<String> matches = ZoneId.getAvailableZoneIds().stream()
                .filter(z -> z.toLowerCase(Locale.ROOT).contains(lowerInput)
                        || lowerInput.contains(z.toLowerCase(Locale.ROOT).replace("/", "")))
                .sorted(Comparator.comparingInt(String::length))
                .limit(MAX_SUGGESTIONS)
                .collect(Collectors.toList());
        if (matches.isEmpty()) {
            matches = ZoneId.getAvailableZoneIds().stream()
                    .filter(z -> levenshtein(z.toLowerCase(Locale.ROOT), lowerInput) <= 3)
                    .sorted(Comparator.comparingInt(z -> levenshtein(z.toLowerCase(Locale.ROOT), lowerInput)))
                    .limit(MAX_SUGGESTIONS)
                    .collect(Collectors.toList());
        }
        return matches.isEmpty() ? "UTC, America/New_York, Europe/London"
                : String.join(", ", matches);
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    private static @Nullable String parseTimezone(String rawText) {
        String[] parts = rawText.split("\\s+", 3);
        return parts.length >= 2 ? parts[1] : null;
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private record ActorRow(UUID id, boolean isAdmin) {}
}
