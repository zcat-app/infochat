package app.zcat.infochat.provider.command;

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
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /clear} per {@code docs/spec/commands.md}
 * §Conversation control — /clear.
 *
 * <p>Wipes the active context window for the calling (user, scope)
 * by deleting all {@code chat_message} rows (via cascade from
 * {@code chat_session}) and resetting {@code token_count} and
 * {@code next_seq} to 0. {@code chat_memory} is NOT touched (D25).
 * Requires confirm per spec §Confirmation for destructive commands.</p>
 */
@ApplicationScoped
public class ClearCommandHandler implements CommandHandler {

    private static final String SELECT_USER_SQL =
            "SELECT id FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String SELECT_GROUP_SQL =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ? "
                    + "AND removed_at IS NULL";

    // Check whether a chat_session exists for (user, scope).
    private static final String SESSION_EXISTS_SQL =
            "SELECT 1 FROM chat_session "
                    + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ?";

    // Delete all chat_message rows; the ON DELETE CASCADE from
    // chat_session would also work, but directly deleting messages
    // preserves the session row (we reset counters explicitly).
    private static final String DELETE_MESSAGES_SQL =
            "DELETE FROM chat_message "
                    + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ?";

    // Reset session counters after message deletion.
    private static final String RESET_SESSION_SQL =
            "UPDATE chat_session SET token_count = 0, next_seq = 0, updated_at = now() "
                    + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ?";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Inject
    ConfirmStateService confirmStateService;

    @Override
    public String name() {
        return "clear";
    }

    @Override
    public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
        String adapter = inboundContext.adapterName();
        String callerContactId = inboundContext.senderContactId();

        Optional<UUID> actorIdOpt = lookupUserId(adapter, callerContactId);
        if (actorIdOpt.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL));
        }
        UUID actorId = actorIdOpt.get();

        if (rawText.trim().endsWith(" confirm")) {
            Optional<ConfirmStateService.PendingConfirm> taken =
                    confirmStateService.takeMatching(actorId, scope, "clear");
            if (taken.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_CONFIRM_NO_PENDING));
            }
            return executeClear(scope, actorId, adapter);
        }

        confirmStateService.remember(actorId, scope, new ClearConfirm());
        String prompt = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_CONFIRM_PROMPT_CLEAR),
                Long.toString(confirmStateService.timeoutSeconds()));
        return reply(scope, prompt);
    }

    private OutboundMessage executeClear(ScopeRef scope, UUID actorId, String adapter) {
        String scopeKind = scopeKindOf(scope);
        UUID scopeId = resolveScopeId(scope, actorId, adapter);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Idempotent: no session → no-op.
                if (!sessionExists(conn, actorId, scopeKind, scopeId)) {
                    conn.commit();
                    return reply(scope, bundleLoader.get(BundleKeys.REPLY_CLEAR_NOOP));
                }

                try (PreparedStatement ps = conn.prepareStatement(DELETE_MESSAGES_SQL)) {
                    ps.setObject(1, actorId);
                    ps.setString(2, scopeKind);
                    ps.setObject(3, scopeId);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement(RESET_SESSION_SQL)) {
                    ps.setObject(1, actorId);
                    ps.setString(2, scopeKind);
                    ps.setObject(3, scopeId);
                    ps.executeUpdate();
                }

                conn.commit();
                return reply(scope, bundleLoader.get(BundleKeys.REPLY_CLEAR_SUCCESS));
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ClearCommandHandler.executeClear failed for adapter=" + adapter, e);
        }
    }

    private boolean sessionExists(Connection conn, UUID userId,
                                  String scopeKind, UUID scopeId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SESSION_EXISTS_SQL)) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private UUID resolveScopeId(ScopeRef scope, UUID actorId, String adapter) {
        return switch (scope) {
            case ScopeRef.Dm ignored -> actorId;
            case ScopeRef.Group group -> lookupGroupId(adapter, group.adapterGroupId())
                    .orElseThrow(() -> new IllegalStateException(
                            "ClearCommandHandler: group not found for adapter=" + adapter));
        };
    }

    private static String scopeKindOf(ScopeRef scope) {
        return switch (scope) {
            case ScopeRef.Dm ignored -> "dm";
            case ScopeRef.Group ignored -> "group";
        };
    }

    private Optional<UUID> lookupUserId(String adapter, String contactId) {
        if (adapter == null || contactId == null) return Optional.empty();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_USER_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of((UUID) rs.getObject("id"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ClearCommandHandler.lookupUserId failed for adapter=" + adapter, e);
        }
    }

    private Optional<UUID> lookupGroupId(String adapter, String upstreamGroupId) {
        if (adapter == null || upstreamGroupId == null) return Optional.empty();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_GROUP_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of((UUID) rs.getObject("id"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ClearCommandHandler.lookupGroupId failed for adapter=" + adapter, e);
        }
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(),
                UUID.randomUUID().toString());
    }
}
