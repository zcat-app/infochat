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
import app.zcat.infochat.provider.user.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /forget} per {@code docs/spec/commands.md}
 * §Conversation control and decision D37.
 *
 * <p>Hard purge of all chat-tier data for the calling user in the
 * calling scope, plus the global saved-post library.
 * Audit-before-effect with counts only (no user content leaks into
 * the audit surface). Idempotent: a no-op purge writes no audit row
 * (Invariant 7 carve-out). Remaining-scopes disclosure when other
 * scopes still hold the caller's chat-tier rows.</p>
 */
@ApplicationScoped
public class ForgetCommandHandler implements CommandHandler {

    private static final String SELECT_GROUP_SQL =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ? "
                    + "AND removed_at IS NULL";

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
    ForgetPurgeService forgetPurgeService;

    @Inject
    UserRepository userRepository;

    @Override
    public String name() {
        return "forget";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        String adapter = inboundContext.adapterName();
        String callerContactId = inboundContext.senderContactId();

        Optional<UserRow> actorOpt = lookupUser(adapter, callerContactId);
        if (actorOpt.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL, inboundContext.effectiveLanguage()));
        }
        UserRow actor = actorOpt.get();

        // Confirm-gate fork. `/forget confirm` is the second leg.
        if (rawText.trim().endsWith(" confirm")) {
            Optional<ConfirmStateService.PendingConfirm> taken =
                    confirmStateService.takeMatching(actor.id, scope, "forget");
            if (taken.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_CONFIRM_NO_PENDING, inboundContext.effectiveLanguage()));
            }
            return executeForget(scope, actor, adapter);
        }

        // First-call path — store pending and return prompt.
        confirmStateService.remember(actor.id, scope, new ForgetConfirm());
        String prompt = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_CONFIRM_PROMPT_FORGET, inboundContext.effectiveLanguage()),
                Long.toString(confirmStateService.timeoutSeconds()));
        return reply(scope, prompt);
    }

    private OutboundMessage executeForget(ScopeRef scope, UserRow actor, String adapter) {
        String scopeKind = scopeKindOf(scope);
        UUID scopeId = resolveScopeId(scope, actor, adapter);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Invariant 7 (audit-before-effect): pre-count, then
                // audit, then DELETE. Both steps share the transaction.
                ForgetPurgeService.PurgeResult counts =
                        forgetPurgeService.preCount(conn, actor.id, scopeKind, scopeId);

                if (counts.total() == 0) {
                    // Invariant 7 carve-out: verified no-op skips audit.
                    conn.commit();
                    return reply(scope, bundleLoader.get(BundleKeys.REPLY_FORGET_NOOP, inboundContext.effectiveLanguage()));
                }

                String requestId = UUID.randomUUID().toString();
                insertAudit(conn, actor, adapter, scopeId, requestId,
                        purgeDetailsJson(scopeKind, counts));

                forgetPurgeService.purge(conn, actor.id, scopeKind, scopeId);

                int remainingScopes = forgetPurgeService.countRemainingScopes(
                        conn, actor.id, scopeKind, scopeId);

                conn.commit();

                return buildReply(scope, remainingScopes);
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "ForgetCommandHandler.executeForget failed for adapter="
                                + adapter + " contact_id="
                                + ContactIds.redact(inboundContext.senderContactId()), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ForgetCommandHandler.executeForget connection failed for adapter="
                            + adapter + " contact_id="
                            + ContactIds.redact(inboundContext.senderContactId()), e);
        }
    }

    private OutboundMessage buildReply(ScopeRef scope, int remainingScopes) {
        if (remainingScopes == 0) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_FORGET_CLEARED, inboundContext.effectiveLanguage()));
        }
        String body = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_FORGET_CLEARED_WITH_REMAINING, inboundContext.effectiveLanguage()),
                Integer.toString(remainingScopes));
        return reply(scope, body);
    }

    /**
     * For DM scope, scope_id is the user's own UUID. For group scope,
     * scope_id is the group UUID looked up by (adapter, adapterGroupId).
     */
    private UUID resolveScopeId(ScopeRef scope, UserRow actor, String adapter) {
        return switch (scope) {
            case ScopeRef.Dm ignored -> actor.id;
            case ScopeRef.Group group -> lookupGroupId(adapter, group.adapterGroupId())
                    .orElseThrow(() -> new IllegalStateException(
                            "ForgetCommandHandler: group not found for adapter="
                                    + adapter));
        };
    }

    private static String scopeKindOf(ScopeRef scope) {
        return switch (scope) {
            case ScopeRef.Dm ignored -> "dm";
            case ScopeRef.Group ignored -> "group";
        };
    }

    private Optional<UserRow> lookupUser(String adapter, String contactId) {
        if (adapter == null || contactId == null) {
            return Optional.empty();
        }
        return userRepository.findByAdapterAndContactId(adapter, contactId)
                .map(u -> new UserRow(u.id(), u.contactId(), u.isAdmin(), u.isBanned()));
    }

    private Optional<UUID> lookupGroupId(String adapter, String upstreamGroupId) {
        if (adapter == null || upstreamGroupId == null) {
            return Optional.empty();
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_GROUP_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of((UUID) rs.getObject("id"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ForgetCommandHandler.lookupGroupId failed for adapter="
                            + adapter, e);
        }
    }

    private void insertAudit(Connection conn, UserRow actor, String adapter,
                             UUID scopeId, String requestId, String detailsJson)
            throws SQLException {
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .actorUserId(actor.id)
                .actorContactId(actor.contactId)
                .actorAdapter(adapter)
                .action(AuditAction.FORGET)
                .targetKind("user")
                .targetId(actor.id.toString())
                .targetContactId(actor.contactId)
                .scopeId(scopeId)
                .requestId(requestId)
                .detailsJson(detailsJson)
                .build();
        auditLogWriter.write(conn, row);
    }

    /**
     * Counts-only JSON for the audit row. No user content.
     */
    private static String purgeDetailsJson(String scopeKind,
                                           ForgetPurgeService.PurgeResult result) {
        return "{\"scope_kind\":\"" + scopeKind + "\""
                + ",\"chat_memory_count\":" + result.chatMemoryCount()
                + ",\"chat_session_count\":" + result.chatSessionCount()
                + ",\"summary_anchor_count\":" + result.summaryAnchorCount()
                + ",\"saved_post_count\":" + result.savedPostCount() + "}";
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(),
                UUID.randomUUID().toString());
    }

    record UserRow(UUID id, String contactId, boolean isAdmin, boolean isBanned) {}
}
