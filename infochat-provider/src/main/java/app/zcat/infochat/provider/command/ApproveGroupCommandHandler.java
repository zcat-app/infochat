package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.util.JsonEscaper;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.group.GroupRepository;
import app.zcat.infochat.provider.messaging.AdapterRegistry;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.messaging.OutboundDelivery;
import app.zcat.infochat.provider.user.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /approve-group <group_id>} per
 * {@code docs/spec/commands.md} §Admin (bot admin) and decision D47.
 *
 * <p>Dispatch sequence:
 * <ol>
 *   <li>Resolve the actor by {@code (adapter, sender_contact_id)} using
 *       {@link InboundContext#senderContactId()}. Works in BOTH DM and
 *       group scope per spec — the actor's contact id is carried by
 *       the request-scope bean regardless of scope shape.</li>
 *   <li>Admin gate — non-admin or absent actor returns
 *       {@code error.admin_only}. No DB write.</li>
 *   <li>Parse one positional {@code <group_id>} (UUID); malformed UUID
 *       or missing arg falls back to {@code error.group_not_found}.</li>
 *   <li>Open the transaction ({@code autoCommit=false}). Look up the
 *       group by id; absent row → {@code error.group_not_found} with
 *       no audit. Already {@code approval_status='approved'} → no-op
 *       reply, no audit, no group message (matches the
 *       {@code /grant-admin}-already-admin precedent).</li>
 *   <li>Audit-before-effect: pre-write the APPROVE_GROUP audit row
 *       inside the same transaction (Invariant 7); UPDATE
 *       {@code approval_status} to {@code 'approved'} via
 *       {@link GroupRepository#setApprovalStatus}; COMMIT.</li>
 *   <li>Post-commit, send the one-time {@code group.approved_message}
 *       to the target group via the adapter named on the row. Delivery
 *       failures are logged but do NOT roll back the approval — the
 *       admin's reply still announces success, mirroring the
 *       {@link app.zcat.infochat.provider.digest.DigestWorker}
 *       fire-and-log convention.</li>
 *   <li>Reply {@code reply.approve_group.success} with the group id.</li>
 * </ol>
 *
 * <p>The cross-scope outbound — replying to the admin in their scope
 * AND sending a one-time message to the target group — uses the
 * adapter-registry lookup pattern from
 * {@link app.zcat.infochat.provider.digest.DigestWorker#findAdapter}.
 * The admin need not be a member of the target group; the spec is
 * explicit on this point.</p>
 */
@ApplicationScoped
public class ApproveGroupCommandHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ApproveGroupCommandHandler.class);

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Inject
    AuditLogWriter auditLogWriter;

    @Inject
    GroupRepository groupRepository;

    @Inject
    AdapterRegistry adapterRegistry;

    @Inject
    OutboundDelivery outboundDelivery;

    @Inject
    UserRepository userRepository;

    @Override
    public String name() {
        return "approve-group";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        String adapter = inboundContext.adapterName();
        String callerContactId = inboundContext.senderContactId();

        // Step 1+2 — actor resolution + admin gate. Fail fast before
        // parsing or opening any transaction. Group scope reaches here
        // identically to DM scope because senderContactId() carries the
        // sender id regardless of scope shape.
        if (adapter == null || callerContactId == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY, inboundContext.effectiveLanguage()));
        }

        // Step 3 — parse positional <group_id>. The "missing arg" and
        // "non-UUID" paths both reduce to "no such group", which is
        // what error.group_not_found expresses.
        UUID groupId = parseGroupId(rawText);
        if (groupId == null) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_GROUP_NOT_FOUND, inboundContext.effectiveLanguage()),
                    parseGroupIdRaw(rawText)));
        }

        return executeApprove(scope, adapter, callerContactId, groupId);
    }

    private OutboundMessage executeApprove(ScopeRef scope, String adapter,
                                           String callerContactId, UUID groupId) {
        String requestId = UUID.randomUUID().toString();
        GroupRepository.GroupRow targetGroup;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Step 1 (in-tx) — admin gate INSIDE the transaction via
                // SELECT FOR UPDATE on the actor row. Mirrors the
                // GrantAdminCommandHandler M1-046-redteam closure: a
                // concurrent /revoke-admin against the caller serializes
                // on the row lock so the is_admin read reflects the
                // post-revoke state, not a stale snapshot.
                Optional<UserRow> actorOpt =
                        lookupActorForUpdate(conn, adapter, callerContactId);
                if (actorOpt.isEmpty() || !actorOpt.get().isAdmin) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY, inboundContext.effectiveLanguage()));
                }
                UserRow actor = actorOpt.get();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT set_config('infochat.actor_id', ?, true)")) {
                    ps.setString(1, actor.id.toString());
                    ps.execute();
                }

                // Step 4a — look up the target group by id.
                Optional<GroupRepository.GroupRow> targetOpt =
                        groupRepository.findById(groupId);
                if (targetOpt.isEmpty()) {
                    conn.rollback();
                    return reply(scope, MessageFormat.format(
                            bundleLoader.get(BundleKeys.ERROR_GROUP_NOT_FOUND, inboundContext.effectiveLanguage()),
                            groupId));
                }
                targetGroup = targetOpt.get();

                // Step 4b — already approved no-op. No audit row, no
                // group message (matches /grant-admin already-admin
                // precedent: a no-op writes no trail).
                if ("approved".equals(targetGroup.approvalStatus())) {
                    conn.rollback();
                    return reply(scope, MessageFormat.format(
                            bundleLoader.get(BundleKeys.REPLY_APPROVE_GROUP_NOOP, inboundContext.effectiveLanguage()),
                            groupId));
                }

                // Step 5 — pre-write APPROVE_GROUP audit row.
                insertAudit(conn, AuditAction.APPROVE_GROUP, actor, adapter,
                        targetGroup, requestId);

                // Step 5b — UPDATE approval_status. The filter on
                // approval_status<>'approved' guards against a race
                // between the no-op check and the UPDATE; row count 0
                // would mean another transaction approved between our
                // findById and this UPDATE, which is benign (we still
                // committed an audit row, but that records our INTENT,
                // which matches the M1-051 audit-on-intent precedent).
                groupRepository.setApprovalStatus(conn, groupId, "approved");

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "ApproveGroupCommandHandler.executeApprove failed for groupId="
                                + groupId, e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ApproveGroupCommandHandler.executeApprove connection failed for groupId="
                            + groupId, e);
        }

        // Step 6 — post-commit group notification. Delivery failures
        // are logged but do not retract the approval; the admin's
        // success reply still goes out.
        sendGroupNotification(targetGroup,
                bundleLoader.get(BundleKeys.GROUP_APPROVED_MESSAGE, inboundContext.effectiveLanguage()));

        return reply(scope, MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_APPROVE_GROUP_SUCCESS, inboundContext.effectiveLanguage()),
                groupId));
    }

    private Optional<UserRow> lookupActorForUpdate(Connection conn,
                                                   String adapter,
                                                   String contactId) throws SQLException {
        return userRepository.findByAdapterAndContactIdForUpdate(conn, adapter, contactId)
                .map(u -> new UserRow(u.id(), u.contactId(), u.isAdmin(), u.isBanned()));
    }

    private void insertAudit(Connection conn, AuditAction action, UserRow actor,
                             String adapter, GroupRepository.GroupRow targetGroup,
                             String requestId) throws SQLException {
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .actorUserId(actor.id)
                .actorContactId(actor.contactId)
                .actorAdapter(adapter)
                .action(action)
                .targetKind(TargetKind.GROUP)
                .targetId(targetGroup.id().toString())
                .targetContactId(null)
                .requestId(requestId)
                .detailsJson(groupDetailsJson(targetGroup))
                .build();
        auditLogWriter.write(conn, row);
    }

    /**
     * Send the one-time group notification via the adapter named on
     * the {@code groups} row. The target group's adapter may differ
     * from the inbound adapter (a SimpleX admin can approve a Signal
     * group), so we look up the adapter from the registry by name.
     * No-op on missing or inactive adapter — the approval has already
     * committed and the admin still gets the success reply.
     */
    private void sendGroupNotification(GroupRepository.GroupRow targetGroup, String body) {
        MessagingAdapter targetAdapter = findAdapter(targetGroup.adapter());
        if (targetAdapter == null) {
            log.warn("No activated adapter '{}' for group {} — group notification skipped",
                    targetGroup.adapter(), targetGroup.id());
            return;
        }
        OutboundMessage msg = new OutboundMessage(
                new ScopeRef.Group(targetGroup.upstreamGroupId()),
                body,
                Instant.now(),
                "approve-group-" + targetGroup.id());
        // Route through the chokepoint: retry on TRANSIENT, abort on
        // PERMANENT, and count permanent group-send failures toward
        // bot-removed cleanup. The approval already committed, so an
        // aborted announcement is logged by the chokepoint and dropped.
        outboundDelivery.deliverToGroup(targetAdapter, msg, targetGroup.id());
    }

    private @Nullable MessagingAdapter findAdapter(String adapterName) {
        for (MessagingAdapter adapter : adapterRegistry.activatedAdapters()) {
            if (adapter.name().equals(adapterName)) {
                return adapter;
            }
        }
        return null;
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    /**
     * Parse the first positional argument as a UUID. Returns the parsed
     * UUID on success, {@code null} on any failure (missing arg,
     * non-UUID literal). The caller distinguishes the failure modes by
     * the user-visible reply, not the parser.
     */
    private static @Nullable UUID parseGroupId(String rawText) {
        String raw = parseGroupIdRaw(rawText);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Return the first positional token as a literal string (un-parsed),
     * or {@code "<missing>"} when no positional arg is present. Used to
     * interpolate the offending value into the
     * {@code error.group_not_found} reply when the UUID parse fails.
     */
    private static String parseGroupIdRaw(String rawText) {
        String[] split = rawText.trim().split("\\s+", 2);
        if (split.length < 2) {
            return "<missing>";
        }
        String remainder = split[1].trim();
        if (remainder.isEmpty()) {
            return "<missing>";
        }
        return remainder.split("\\s+")[0];
    }

    private static String groupDetailsJson(GroupRepository.GroupRow targetGroup) {
        return "{\"target_adapter\":\"" + JsonEscaper.escape(targetGroup.adapter())
                + "\",\"upstream_group_id\":\"" + JsonEscaper.escape(targetGroup.upstreamGroupId())
                + "\",\"previous_status\":\"" + JsonEscaper.escape(targetGroup.approvalStatus()) + "\"}";
    }

    private record UserRow(UUID id, String contactId, boolean isAdmin, boolean isBanned) {}
}
