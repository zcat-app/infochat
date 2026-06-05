package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.util.JsonEscaper;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.group.GroupRepository;
import app.zcat.infochat.provider.messaging.AdapterRegistry;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.user.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /reject-group <group_id>} per
 * {@code docs/spec/commands.md} §Admin (bot admin) and decision D47.
 * Destructive (stops digests, blocks @mention replies); requires the
 * M1-051 confirm gate.
 *
 * <p>Dispatch sequence:
 * <ol>
 *   <li>Resolve the actor by {@code (adapter, sender_contact_id)} via
 *       {@link InboundContext#senderContactId()} — works in BOTH DM
 *       and group scope per spec.</li>
 *   <li>Admin gate has precedence over the confirm gate: a non-admin
 *       sending {@code /reject-group <id> confirm} must see
 *       {@code error.admin_only}, not {@code error.confirm.no_pending}.</li>
 *   <li>Confirm-gate fork (M1-051): a {@code rawText} ending in
 *       {@code " confirm"} pops the previously-stored pending arg via
 *       {@link ConfirmStateService#takeMatching} and runs the
 *       reject transaction. Else (first call) parses
 *       {@code <group_id>}, validates the row exists, writes a
 *       REJECT_GROUP_INTENT audit row (security.md §Authorization
 *       model step 8), stores the {@link RejectGroupConfirm} payload,
 *       and returns the prompt template.</li>
 *   <li>Reject transaction (confirm leg): open
 *       {@code autoCommit=false}, re-validate the group still exists
 *       and is not already rejected (idempotent no-op path), pre-write
 *       the REJECT_GROUP audit row, UPDATE
 *       {@code approval_status='rejected'}, COMMIT.</li>
 *   <li>Post-commit, send the one-time
 *       {@code group.rejected_message} to the target group. Delivery
 *       failures are logged but do NOT retract the rejection — the
 *       admin's success reply still goes out.</li>
 *   <li>Reply {@code reply.reject_group.success} with the group id.</li>
 * </ol>
 *
 * <p><b>Why {@link RejectGroupConfirm} is nested.</b> The corpus
 * convention is one {@code *Confirm} record per top-level file
 * (BanConfirm.java, ForgetConfirm.java, etc.). M1-113's
 * {@code files_budget} is 11 and every other slot is already
 * allocated; nesting the record keeps the file count at the budgeted
 * total. The record's sole consumer is this enclosing handler, so
 * the visibility cost is zero. The
 * {@link RejectGroupConfirm#commandName} / {@link
 * RejectGroupConfirm#sweepPrefix} pair is still the wire-level
 * contract the router's step 4.5 sweep depends on.</p>
 */
@ApplicationScoped
public class RejectGroupCommandHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(RejectGroupCommandHandler.class);

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Inject
    AuditLogWriter auditLogWriter;

    @Inject
    ConfirmStateService confirmStateService;

    @Inject
    GroupRepository groupRepository;

    @Inject
    AdapterRegistry adapterRegistry;

    @Inject
    UserRepository userRepository;

    @Override
    public String name() {
        return "reject-group";
    }

    @Override
    public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
        String adapter = inboundContext.adapterName();
        String callerContactId = inboundContext.senderContactId();

        if (adapter == null || callerContactId == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
        }

        // Admin gate has precedence — non-admin sending `confirm` must
        // see admin_only, not confirm_no_pending. Done outside the
        // transaction; the in-tx FOR UPDATE re-check happens inside
        // executeReject for the M1-046-style TOCTOU closure.
        Optional<UserRow> actorOpt = lookupActor(adapter, callerContactId);
        if (actorOpt.isEmpty() || !actorOpt.get().isAdmin) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
        }
        UserRow actor = actorOpt.get();

        // M1-051 confirm-gate fork.
        if (rawText.trim().endsWith(" confirm")) {
            Optional<ConfirmStateService.PendingConfirm> taken =
                    confirmStateService.takeMatching(actor.id, scope, "reject-group");
            if (taken.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_CONFIRM_NO_PENDING));
            }
            RejectGroupConfirm pending = (RejectGroupConfirm) taken.get();
            return executeReject(scope, adapter, actor, pending.groupId());
        }

        // First-call path — parse <group_id>, validate it exists,
        // write REJECT_GROUP_INTENT, store pending, return prompt.
        UUID groupId = parseGroupId(rawText);
        if (groupId == null) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_GROUP_NOT_FOUND),
                    parseGroupIdRaw(rawText)));
        }

        Optional<GroupRepository.GroupRow> targetOpt = groupRepository.findById(groupId);
        if (targetOpt.isEmpty()) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_GROUP_NOT_FOUND), groupId));
        }
        GroupRepository.GroupRow targetGroup = targetOpt.get();

        // Pre-check: an already-rejected group on the first call is a
        // no-op. Short-circuit BEFORE remember() so the admin sees the
        // friendly no-op reply on the single call (matches the most
        // natural reading of acceptance item "already rejected → no-op
        // reply"), and so the REJECT_GROUP_INTENT audit row is not
        // written for a no-op. The confirm-leg re-check in
        // executeReject covers the rare race where the row transitions
        // between this first-call read and the confirmed UPDATE.
        if ("rejected".equals(targetGroup.approvalStatus())) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_REJECT_GROUP_NOOP), groupId));
        }

        // Audit-on-intent (security.md §Authorization model step 8):
        // write ONE REJECT_GROUP_INTENT row BEFORE remember() / prompt.
        // Atomic single-statement INSERT with autoCommit=true — the
        // prompt path mutates no other state.
        String intentRequestId = UUID.randomUUID().toString();
        try (Connection conn = dataSource.getConnection()) {
            insertAudit(conn, AuditAction.REJECT_GROUP_INTENT, actor, adapter,
                    targetGroup, intentRequestId);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to write REJECT_GROUP_INTENT audit row for groupId="
                            + groupId, e);
        }

        confirmStateService.remember(actor.id, scope, new RejectGroupConfirm(groupId));
        String prompt = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_CONFIRM_PROMPT_REJECT_GROUP),
                Long.toString(confirmStateService.timeoutSeconds()),
                groupId);
        return reply(scope, prompt);
    }

    private OutboundMessage executeReject(ScopeRef scope, String adapter,
                                          UserRow actor, UUID groupId) {
        String requestId = UUID.randomUUID().toString();
        GroupRepository.GroupRow targetGroup;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Re-validate admin INSIDE the transaction via SELECT
                // FOR UPDATE on the actor row (M1-046 redteam closure):
                // a concurrent /revoke-admin against the caller
                // serializes on the row lock so the is_admin read here
                // reflects the post-revoke state.
                Optional<UserRow> reActorOpt =
                        lookupActorForUpdate(conn, adapter, actor.contactId);
                if (reActorOpt.isEmpty() || !reActorOpt.get().isAdmin) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
                }
                UserRow reActor = reActorOpt.get();
                try (Statement st = conn.createStatement()) {
                    st.execute("SET LOCAL infochat.actor_id = '" + reActor.id + "'");
                }

                // Re-look up the group inside the tx — the row could
                // have been mutated (e.g. /approve-group raced us)
                // between first-call and confirm.
                Optional<GroupRepository.GroupRow> targetOpt =
                        groupRepository.findById(groupId);
                if (targetOpt.isEmpty()) {
                    conn.rollback();
                    return reply(scope, MessageFormat.format(
                            bundleLoader.get(BundleKeys.ERROR_GROUP_NOT_FOUND), groupId));
                }
                targetGroup = targetOpt.get();

                if ("rejected".equals(targetGroup.approvalStatus())) {
                    conn.rollback();
                    return reply(scope, MessageFormat.format(
                            bundleLoader.get(BundleKeys.REPLY_REJECT_GROUP_NOOP), groupId));
                }

                insertAudit(conn, AuditAction.REJECT_GROUP, reActor, adapter,
                        targetGroup, requestId);
                groupRepository.setApprovalStatus(conn, groupId, "rejected");

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "RejectGroupCommandHandler.executeReject failed for groupId="
                                + groupId, e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "RejectGroupCommandHandler.executeReject connection failed for groupId="
                            + groupId, e);
        }

        sendGroupNotification(targetGroup,
                bundleLoader.get(BundleKeys.GROUP_REJECTED_MESSAGE));

        return reply(scope, MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_REJECT_GROUP_SUCCESS), groupId));
    }

    private Optional<UserRow> lookupActor(String adapter, String contactId) {
        return userRepository.findByAdapterAndContactId(adapter, contactId)
                .map(u -> new UserRow(u.id(), u.contactId(), u.isAdmin(), u.isBanned()));
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
                .targetKind("group")
                .targetId(targetGroup.id().toString())
                .targetContactId(null)
                .requestId(requestId)
                .detailsJson(groupDetailsJson(targetGroup))
                .build();
        auditLogWriter.write(conn, row);
    }

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
                "reject-group-" + targetGroup.id());
        try {
            targetAdapter.send(msg);
        } catch (MessagingException e) {
            log.warn("Group notification send failed for group {} on adapter '{}'",
                    targetGroup.id(), targetGroup.adapter(), e);
        }
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

    private static @Nullable UUID parseGroupId(String rawText) {
        String raw = parseGroupIdRaw(rawText);
        if (raw == null || "<missing>".equals(raw)) {
            return null;
        }
        // Strip trailing ` confirm` token if present — the confirm fork
        // popped its own arg via takeMatching, so this path is only
        // reached on the first call (no trailing `confirm`). Defense:
        // a lone `confirm` should not be parsed as a group id.
        if ("confirm".equals(raw)) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String parseGroupIdRaw(String rawText) {
        // For prompt-first calls, strip any trailing ` confirm` so the
        // tokenizer doesn't pick `confirm` as the positional arg.
        String trimmed = rawText.trim();
        if (trimmed.endsWith(" confirm")) {
            trimmed = trimmed.substring(0, trimmed.length() - " confirm".length()).trim();
        }
        String[] split = trimmed.split("\\s+", 2);
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

    /**
     * Pending {@code /reject-group <group_id>} confirm payload. Nested
     * inside the handler to keep the file count at the M1-113 budget;
     * the wire-level contract ({@link #commandName()} +
     * {@link #sweepPrefix()}) is identical to the BanConfirm /
     * RemoveSourceConfirm / etc. precedent and is the load-bearing
     * shape the router's step 4.5 sweep depends on.
     */
    public record RejectGroupConfirm(UUID groupId)
            implements ConfirmStateService.PendingConfirm {

        @Override
        public String commandName() {
            return "reject-group";
        }

        @Override
        public String sweepPrefix() {
            return "reject-group";
        }
    }
}
