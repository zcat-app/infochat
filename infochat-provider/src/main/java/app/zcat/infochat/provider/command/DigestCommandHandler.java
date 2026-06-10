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
import java.time.Instant;
import java.util.UUID;

/**
 * Implements {@code /digest on|off} per {@code docs/spec/commands.md}
 * §Conversation control: group scope, requires group-admin or
 * bot-admin caller, toggles {@code groups.digest_enabled} (the digest
 * scheduler's delivery gate), audit-logs before effect. A call that
 * requests the state the group is already in is a friendly no-op — no
 * UPDATE and no audit row — so repeated toggles do not spam the audit
 * log.
 */
@ApplicationScoped
public class DigestCommandHandler implements CommandHandler {

    private static final String SELECT_ACTOR_SQL =
            "SELECT id, is_admin FROM users "
                    + "WHERE adapter = ? AND contact_id = ?";

    private static final String CHECK_GROUP_ADMIN_SQL =
            "SELECT is_group_admin FROM group_membership "
                    + "WHERE group_id = ? AND user_id = ? AND removed_at IS NULL";

    // Folds the current digest_enabled read into the group resolve so the
    // idempotency check costs no extra round-trip.
    private static final String SELECT_GROUP_SQL =
            "SELECT id, digest_enabled FROM groups "
                    + "WHERE adapter = ? AND upstream_group_id = ? AND removed_at IS NULL";

    private static final String UPDATE_DIGEST_SQL =
            "UPDATE groups SET digest_enabled = ? WHERE id = ?";

    @Inject DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject AuditLogWriter auditLogWriter;

    @Override
    public String name() {
        return "digest";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        if (!(scope instanceof ScopeRef.Group group)) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_DIGEST_DM_SCOPE, inboundContext.effectiveLanguage()));
        }

        Boolean desiredEnabled = parseSubVerb(rawText);
        if (desiredEnabled == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_DIGEST_USAGE, inboundContext.effectiveLanguage()));
        }

        String adapter = inboundContext.adapterName();
        String callerContactId = inboundContext.senderContactId();

        String requestId = UUID.randomUUID().toString();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                ActorRow actor = resolveActor(conn, adapter, callerContactId);
                if (actor == null) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_DIGEST_NOT_ADMIN, inboundContext.effectiveLanguage()));
                }

                GroupRow groupRow = resolveGroupInTx(conn, adapter, group.adapterGroupId());

                // Authorization: group-admin OR bot-admin
                if (!actor.isAdmin && !isGroupAdmin(conn, groupRow.id, actor.id)) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_DIGEST_NOT_ADMIN, inboundContext.effectiveLanguage()));
                }

                // Idempotent no-op: already in the requested state — no UPDATE,
                // no audit row, so repeated toggles do not spam the audit log.
                if (groupRow.digestEnabled == desiredEnabled) {
                    conn.rollback();
                    String noopKey = desiredEnabled
                            ? BundleKeys.REPLY_DIGEST_ALREADY_ON
                            : BundleKeys.REPLY_DIGEST_ALREADY_OFF;
                    return reply(scope, bundleLoader.get(noopKey, inboundContext.effectiveLanguage()));
                }

                // Audit before effect, in the same transaction as the UPDATE, so
                // a committed audit row can never outlive a rolled-back mutation.
                RedactionHook.AuditRow auditRow = RedactionHook.AuditRow.builder()
                        .actorUserId(actor.id)
                        .actorContactId(callerContactId)
                        .actorAdapter(adapter)
                        .action(desiredEnabled ? AuditAction.DIGEST_ENABLE : AuditAction.DIGEST_DISABLE)
                        .targetKind("group")
                        .targetId(groupRow.id.toString())
                        .scopeId(groupRow.id)
                        .requestId(requestId)
                        .detailsJson("{\"digest_enabled\":" + desiredEnabled + "}")
                        .build();
                auditLogWriter.write(conn, auditRow);

                updateDigestEnabled(conn, groupRow.id, desiredEnabled);

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "DigestCommandHandler failed for adapter=" + adapter
                                + " caller=" + ContactIds.redact(callerContactId), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "DigestCommandHandler connection failed for adapter=" + adapter, e);
        }

        String successKey = desiredEnabled
                ? BundleKeys.REPLY_DIGEST_ON
                : BundleKeys.REPLY_DIGEST_OFF;
        return reply(scope, bundleLoader.get(successKey, inboundContext.effectiveLanguage()));
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

    private GroupRow resolveGroupInTx(Connection conn, String adapter,
                                      String upstreamGroupId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_GROUP_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "DigestCommandHandler: group not found for adapter=" + adapter);
                }
                return new GroupRow(
                        (UUID) rs.getObject("id"),
                        rs.getBoolean("digest_enabled"));
            }
        }
    }

    private void updateDigestEnabled(Connection conn, UUID groupId,
                                     boolean enabled) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_DIGEST_SQL)) {
            ps.setBoolean(1, enabled);
            ps.setObject(2, groupId);
            ps.executeUpdate();
        }
    }

    /**
     * Returns {@code true} for {@code on}, {@code false} for {@code off}
     * (both matched case-insensitively), or {@code null} when the sub-verb
     * is missing or unrecognized — the caller maps {@code null} to the
     * friendly usage error.
     */
    private static @Nullable Boolean parseSubVerb(String rawText) {
        String[] parts = rawText.trim().split("\\s+", 3);
        if (parts.length < 2) return null;
        String subVerb = parts[1].toLowerCase();
        return switch (subVerb) {
            case "on" -> Boolean.TRUE;
            case "off" -> Boolean.FALSE;
            default -> null;
        };
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private record ActorRow(UUID id, boolean isAdmin) {}

    private record GroupRow(UUID id, boolean digestEnabled) {}
}
