package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.core.util.JsonEscaper;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.user.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /promote <contact>} per
 * {@code docs/spec/security.md} §Authorization model: bot-admin-only,
 * group scope, demotes existing admin and promotes target in one
 * transaction. The partial unique index {@code one_admin_per_group}
 * guarantees at most one admin at any point.
 *
 * <p>Audit-on-intent: after a non-locking permission pre-check passes
 * and BEFORE the mutation transaction opens, the handler writes a
 * PROMOTE_GROUP_ADMIN_INTENT row on a separate auto-commit connection
 * (spec §Authorization model step 8 "Audit-log the intent" precedes
 * step 9 "Execute"; GRANT_ADMIN_INTENT placement) — so the
 * unknown-contact, banned-target, probation-target and not-in-group
 * refusal legs all leave a surviving intent row while
 * non-admin-caller refusals stay audit-silent. The pre-transaction
 * placement avoids the FK-vs-FOR-UPDATE deadlock: the
 * audit_log.actor_user_id FK takes FOR KEY SHARE on the actor row,
 * which the in-tx FOR UPDATE admin gate would block undetectably.</p>
 */
@ApplicationScoped
public class PromoteCommandHandler implements CommandHandler {

    private static final String SELECT_TARGET_SQL =
            "SELECT id, contact_id, is_banned, probation_until FROM users "
                    + "WHERE adapter = ? AND contact_id = ?";

    private static final String CHECK_ACTIVE_MEMBERSHIP_SQL =
            "SELECT 1 FROM group_membership "
                    + "WHERE group_id = ? AND user_id = ? AND removed_at IS NULL";

    private static final String SELECT_GROUP_SQL =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ? "
                    + "AND removed_at IS NULL";

    private static final String DEMOTE_EXISTING_SQL =
            "UPDATE group_membership SET is_group_admin = false "
                    + "WHERE group_id = ? AND is_group_admin = true AND removed_at IS NULL";

    private static final String PROMOTE_TARGET_SQL =
            "UPDATE group_membership SET is_group_admin = true "
                    + "WHERE group_id = ? AND user_id = ? AND removed_at IS NULL";

    @Inject DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject AuditLogWriter auditLogWriter;
    @Inject UserRepository userRepository;

    @Override
    public String name() {
        return "promote";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        if (!(scope instanceof ScopeRef.Group group)) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_PROMOTE_GROUP_SCOPE_REQUIRED));
        }

        String adapter = inboundContext.adapterName();
        String callerContactId = inboundContext.senderContactId();

        String targetContactId = parseTarget(rawText);
        if (targetContactId == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
        }

        // Permission pre-check (spec §Authorization model step 7),
        // refusing and NON-LOCKING — keeps unauthorized senders from
        // growing the append-only audit_log with the intent row below.
        // The in-tx FOR UPDATE gate stays authoritative for EXECUTION:
        // an actor revoked between this read and the transaction is
        // still refused in-tx, so execution can never happen without
        // the intent row.
        Optional<UserRepository.UserRow> actorPre =
                userRepository.findByAdapterAndContactId(adapter, callerContactId);
        if (actorPre.isEmpty() || !actorPre.get().isAdmin()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
        }

        String requestId = UUID.randomUUID().toString();

        // Audit-on-intent (spec step 8) on a separate auto-commit
        // connection BEFORE the locking transaction opens, so the
        // unknown-contact, banned-target, probation-target and
        // not-in-group refusal legs all leave a surviving intent row.
        // The shared requestId correlates it with the
        // PROMOTE_GROUP_ADMIN effect row.
        insertIntentAudit(adapter, targetContactId, actorPre.get(),
                group.adapterGroupId(), requestId);

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

                if (target.isBanned) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_PROMOTE_TARGET_BANNED));
                }

                if (target.inProbation()) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_PROMOTE_TARGET_PROBATION));
                }

                // Resolve group
                UUID groupId = resolveGroupInTx(conn, adapter, group.adapterGroupId());

                // Validate active membership
                if (!hasActiveMembership(conn, groupId, target.id)) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_PROMOTE_TARGET_NOT_IN_GROUP));
                }

                // Audit before effect
                RedactionHook.AuditRow auditRow = RedactionHook.AuditRow.builder()
                        .actorUserId(actorId)
                        .actorContactId(callerContactId)
                        .actorAdapter(adapter)
                        .action(AuditAction.PROMOTE_GROUP_ADMIN)
                        .targetKind("user")
                        .targetId(target.id.toString())
                        .targetContactId(targetContactId)
                        .scopeId(groupId)
                        .requestId(requestId)
                        .build();
                auditLogWriter.write(conn, auditRow);

                // Demote existing admin + promote target in one tx
                demoteExisting(conn, groupId);
                promoteTarget(conn, groupId, target.id);

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "PromoteCommandHandler failed for adapter=" + adapter
                                + " target=" + ContactIds.redact(targetContactId), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "PromoteCommandHandler connection failed for adapter=" + adapter, e);
        }

        String replyText = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_PROMOTE_SUCCESS),
                ContactIds.redact(targetContactId));
        return reply(scope, replyText);
    }

    // FOR UPDATE locks the actor row for the rest of the transaction so
    // a concurrent /revoke-admin UPDATE on the same row serializes
    // against this read — the is_admin value cannot go stale between
    // the admin gate and the membership UPDATEs below (mirrors the
    // M1-046 PERM-ESCAL closure on the sibling admin handlers).
    private @Nullable UUID resolveAdmin(Connection conn, String adapter,
                              String contactId) throws SQLException {
        return userRepository.findByAdapterAndContactIdForUpdate(conn, adapter, contactId)
                .filter(UserRepository.UserRow::isAdmin)
                .map(UserRepository.UserRow::id)
                .orElse(null);
    }

    private @Nullable TargetRow resolveTarget(Connection conn, String adapter,
                                    String contactId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_TARGET_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Timestamp ts = rs.getTimestamp("probation_until");
                Instant probationUntil = ts == null ? null : ts.toInstant();
                return new TargetRow(
                        (UUID) rs.getObject("id"),
                        rs.getString("contact_id"),
                        rs.getBoolean("is_banned"),
                        probationUntil);
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
                            "PromoteCommandHandler: group not found for adapter=" + adapter);
                }
                return (UUID) rs.getObject("id");
            }
        }
    }

    private boolean hasActiveMembership(Connection conn, UUID groupId,
                                        UUID userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(CHECK_ACTIVE_MEMBERSHIP_SQL)) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void demoteExisting(Connection conn, UUID groupId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(DEMOTE_EXISTING_SQL)) {
            ps.setObject(1, groupId);
            ps.executeUpdate();
        }
    }

    private void promoteTarget(Connection conn, UUID groupId,
                               UUID userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(PROMOTE_TARGET_SQL)) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            ps.executeUpdate();
        }
    }

    /**
     * Write the PROMOTE_GROUP_ADMIN_INTENT row on its own auto-commit
     * connection (GRANT_ADMIN_INTENT pattern). The target lookup here
     * resolves ONLY the row's target_id — the target's users id when
     * registered, a synthetic UUID otherwise (target_contact_id
     * carries the identity either way) — and the details_json
     * target_registered flag that makes a synthetic id
     * distinguishable from a real-but-since-deleted user id; target
     * state never gates the write. details_json additionally records
     * the upstream group id: the refusal legs commit no effect row
     * carrying scope_id, so without it the probe's group context
     * would be lost.
     */
    private void insertIntentAudit(String adapter, String targetContactId,
                                   UserRepository.UserRow actor,
                                   String upstreamGroupId, String requestId) {
        Optional<UUID> targetPre = userRepository
                .findByAdapterAndContactId(adapter, targetContactId)
                .map(UserRepository.UserRow::id);
        UUID targetUserIdForIntent = targetPre.orElse(UUID.randomUUID());
        try (Connection conn = dataSource.getConnection()) {
            RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                    .actorUserId(actor.id())
                    .actorContactId(actor.contactId())
                    .actorAdapter(adapter)
                    .action(AuditAction.PROMOTE_GROUP_ADMIN_INTENT)
                    .targetKind("user")
                    .targetId(targetUserIdForIntent.toString())
                    .targetContactId(targetContactId)
                    .requestId(requestId)
                    .detailsJson("{\"target_registered\":" + targetPre.isPresent()
                            + ",\"upstream_group_id\":\"" + JsonEscaper.escape(upstreamGroupId)
                            + "\"}")
                    .build();
            auditLogWriter.write(conn, row);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "PromoteCommandHandler intent-audit write failed for adapter="
                            + adapter + " target=" + ContactIds.redact(targetContactId), e);
        }
    }

    private static @Nullable String parseTarget(String rawText) {
        String[] parts = rawText.split("\\s+", 3);
        return parts.length >= 2 ? parts[1] : null;
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private record TargetRow(UUID id, String contactId, boolean isBanned, @Nullable Instant probationUntil) {
        boolean inProbation() {
            return probationUntil != null && probationUntil.isAfter(Instant.now());
        }
    }
}
