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
import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.UUID;

/**
 * Implements {@code /demote <contact>} per
 * {@code docs/spec/security.md} §Authorization model: bot-admin-only,
 * group scope, clears {@code is_group_admin} on the target.
 */
@ApplicationScoped
public class DemoteCommandHandler implements CommandHandler {

    private static final String SELECT_ACTOR_SQL =
            "SELECT id, is_admin FROM users "
                    + "WHERE adapter = ? AND contact_id = ?";

    private static final String SELECT_TARGET_SQL =
            "SELECT id, contact_id FROM users "
                    + "WHERE adapter = ? AND contact_id = ?";

    private static final String CHECK_TARGET_IS_ADMIN_SQL =
            "SELECT is_group_admin FROM group_membership "
                    + "WHERE group_id = ? AND user_id = ? AND removed_at IS NULL";

    private static final String SELECT_GROUP_SQL =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ? "
                    + "AND removed_at IS NULL";

    private static final String DEMOTE_SQL =
            "UPDATE group_membership SET is_group_admin = false "
                    + "WHERE group_id = ? AND user_id = ? AND removed_at IS NULL";

    @Inject DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject AuditLogWriter auditLogWriter;

    @Override
    public String name() {
        return "demote";
    }

    @Override
    public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
        if (!(scope instanceof ScopeRef.Group group)) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_DEMOTE_GROUP_SCOPE_REQUIRED));
        }

        String adapter = inboundContext.adapterName();
        String callerContactId = inboundContext.senderContactId();

        String targetContactId = parseTarget(rawText);
        if (targetContactId == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
        }

        String requestId = UUID.randomUUID().toString();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Admin gate
                UUID actorId = resolveAdmin(conn, adapter, callerContactId);
                if (actorId == null) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
                }

                // Resolve target
                TargetRow target = resolveTarget(conn, adapter, targetContactId);
                if (target == null) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_CONTACT_NOT_REGISTERED));
                }

                // Resolve group
                UUID groupId = resolveGroupInTx(conn, adapter, group.adapterGroupId());

                // Validate target is current group admin
                if (!isGroupAdmin(conn, groupId, target.id)) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_DEMOTE_TARGET_NOT_ADMIN));
                }

                // Audit before effect
                RedactionHook.AuditRow auditRow = RedactionHook.AuditRow.builder()
                        .actorUserId(actorId)
                        .actorContactId(callerContactId)
                        .actorAdapter(adapter)
                        .action(AuditAction.DEMOTE_GROUP_ADMIN)
                        .targetKind("user")
                        .targetId(target.id.toString())
                        .targetContactId(targetContactId)
                        .scopeId(groupId)
                        .requestId(requestId)
                        .build();
                auditLogWriter.write(conn, auditRow);

                // Demote
                demote(conn, groupId, target.id);

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "DemoteCommandHandler failed for adapter=" + adapter
                                + " target=" + ContactIds.redact(targetContactId), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "DemoteCommandHandler connection failed for adapter=" + adapter, e);
        }

        String replyText = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_DEMOTE_SUCCESS),
                ContactIds.redact(targetContactId));
        return reply(scope, replyText);
    }

    private UUID resolveAdmin(Connection conn, String adapter,
                              String contactId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_ACTOR_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                if (!rs.getBoolean("is_admin")) return null;
                return (UUID) rs.getObject("id");
            }
        }
    }

    private TargetRow resolveTarget(Connection conn, String adapter,
                                    String contactId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_TARGET_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new TargetRow(
                        (UUID) rs.getObject("id"),
                        rs.getString("contact_id"));
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
                            "DemoteCommandHandler: group not found for adapter=" + adapter);
                }
                return (UUID) rs.getObject("id");
            }
        }
    }

    private boolean isGroupAdmin(Connection conn, UUID groupId,
                                 UUID userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(CHECK_TARGET_IS_ADMIN_SQL)) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean("is_group_admin");
            }
        }
    }

    private void demote(Connection conn, UUID groupId, UUID userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(DEMOTE_SQL)) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            ps.executeUpdate();
        }
    }

    private static String parseTarget(String rawText) {
        String[] parts = rawText.split("\\s+", 3);
        return parts.length >= 2 ? parts[1] : null;
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private record TargetRow(UUID id, String contactId) {}
}
