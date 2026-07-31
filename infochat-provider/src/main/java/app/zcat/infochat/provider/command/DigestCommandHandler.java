package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.TargetKind;
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
import java.util.UUID;

/**
 * Implements {@code /digest on|off|brief|normal|full} per
 * {@code docs/spec/commands.md} §Conversation control: group scope,
 * requires group-admin or bot-admin caller. {@code on|off} toggles
 * {@code groups.digest_enabled} (the digest scheduler's delivery
 * gate); {@code brief|normal|full} sets {@code groups.digest_mode}
 * (the render detail level) — independent of the pause flag, so
 * setting the mode never resumes a paused group. Both forms audit-log
 * before effect. A call that requests the state the group is already
 * in is a friendly no-op — no UPDATE and no audit row — so repeated
 * toggles do not spam the audit log.
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
            "SELECT id, digest_enabled, digest_mode FROM groups "
                    + "WHERE adapter = ? AND upstream_group_id = ? AND removed_at IS NULL";

    private static final String UPDATE_DIGEST_SQL =
            "UPDATE groups SET digest_enabled = ? WHERE id = ?";

    private static final String UPDATE_DIGEST_MODE_SQL =
            "UPDATE groups SET digest_mode = ? WHERE id = ?";

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

        SubVerb subVerb = parseSubVerb(rawText);
        if (subVerb == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_DIGEST_USAGE, inboundContext.effectiveLanguage()));
        }

        String adapter = inboundContext.adapterName();
        String callerContactId = inboundContext.senderContactId();

        String requestId = UUID.randomUUID().toString();
        String successKey;
        String successArg = null;
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

                if (subVerb instanceof SubVerb.SetEnabled toggle) {
                    boolean desiredEnabled = toggle.enabled();

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
                            .targetKind(TargetKind.GROUP)
                            .targetId(groupRow.id.toString())
                            .scopeId(groupRow.id)
                            .requestId(requestId)
                            .detailsJson("{\"digest_enabled\":" + desiredEnabled + "}")
                            .build();
                    auditLogWriter.write(conn, auditRow);

                    updateDigestEnabled(conn, groupRow.id, desiredEnabled);

                    successKey = desiredEnabled
                            ? BundleKeys.REPLY_DIGEST_ON
                            : BundleKeys.REPLY_DIGEST_OFF;
                } else {
                    String desiredMode = ((SubVerb.SetMode) subVerb).mode();

                    // Same idempotent no-op contract as on|off: naming the mode
                    // the group already has writes no UPDATE and no audit row.
                    if (groupRow.digestMode.equals(desiredMode)) {
                        conn.rollback();
                        return reply(scope, MessageFormat.format(
                                bundleLoader.get(BundleKeys.REPLY_DIGEST_MODE_ALREADY, inboundContext.effectiveLanguage()),
                                desiredMode));
                    }

                    // Audit before effect under DIGEST_MODE_SET — never
                    // DIGEST_ENABLE, whose rows pin the scheduler's
                    // paused-through-window carve-out boundary
                    // (DigestScheduler.latestDigestEnableTime).
                    RedactionHook.AuditRow auditRow = RedactionHook.AuditRow.builder()
                            .actorUserId(actor.id)
                            .actorContactId(callerContactId)
                            .actorAdapter(adapter)
                            .action(AuditAction.DIGEST_MODE_SET)
                            .targetKind(TargetKind.GROUP)
                            .targetId(groupRow.id.toString())
                            .scopeId(groupRow.id)
                            .requestId(requestId)
                            .detailsJson("{\"digest_mode_old\":\"" + groupRow.digestMode
                                    + "\",\"digest_mode_new\":\"" + desiredMode + "\"}")
                            .build();
                    auditLogWriter.write(conn, auditRow);

                    updateDigestMode(conn, groupRow.id, desiredMode);

                    successKey = BundleKeys.REPLY_DIGEST_MODE_SET;
                    successArg = desiredMode;
                }

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

        String successText = bundleLoader.get(successKey, inboundContext.effectiveLanguage());
        if (successArg != null) {
            successText = MessageFormat.format(successText, successArg);
        }
        return reply(scope, successText);
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
                        rs.getBoolean("digest_enabled"),
                        rs.getString("digest_mode"));
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

    private void updateDigestMode(Connection conn, UUID groupId,
                                  String mode) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_DIGEST_MODE_SQL)) {
            ps.setString(1, mode);
            ps.setObject(2, groupId);
            ps.executeUpdate();
        }
    }

    /**
     * The parsed {@code /digest} sub-verb: {@code on|off} toggles
     * {@code groups.digest_enabled}; {@code brief|normal|full} sets
     * {@code groups.digest_mode}. The two are independent — a mode set
     * never touches the pause flag.
     */
    private sealed interface SubVerb {
        record SetEnabled(boolean enabled) implements SubVerb {}
        record SetMode(String mode) implements SubVerb {}
    }

    /**
     * Returns the parsed sub-verb for {@code on}, {@code off},
     * {@code brief}, {@code normal} or {@code full} (all matched
     * case-insensitively), or {@code null} when the sub-verb is missing
     * or unrecognized — the caller maps {@code null} to the friendly
     * usage error.
     */
    private static @Nullable SubVerb parseSubVerb(String rawText) {
        String[] parts = rawText.trim().split("\\s+", 3);
        if (parts.length < 2) return null;
        String subVerb = parts[1].toLowerCase();
        return switch (subVerb) {
            case "on" -> new SubVerb.SetEnabled(true);
            case "off" -> new SubVerb.SetEnabled(false);
            case "brief", "normal", "full" -> new SubVerb.SetMode(subVerb);
            default -> null;
        };
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private record ActorRow(UUID id, boolean isAdmin) {}

    private record GroupRow(UUID id, boolean digestEnabled, String digestMode) {}
}
