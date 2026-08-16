package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.ChatReplyModeResolver;
import app.zcat.infochat.provider.group.GroupMembershipRepository;
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
 * Implements {@code /reply-mode translate|native} per
 * {@code docs/spec/commands.md} §Conversation control and decision D79
 * ({@code docs/spec/llm.md} §Translation flow). Auto-discovered by
 * {@code InboundRouter} via the CDI {@code Instance<CommandHandler>}
 * scan; no router-side edit needed.
 *
 * <p>The override is stored either way — a pair that clears later
 * activates it without a further command. An uncleared native override
 * resolves translate until the bar-clearing registry clears the
 * deployment's chat model and the scope language; the confirmation and
 * the bare-invocation status read both name that stored-but-inactive
 * state rather than silently no-op'ing.
 *
 * <p>Permission gate matches {@code /lang}: DM scope is the caller's own
 * scope; group scope requires bot-admin or group-admin. The handler writes
 * zero rows to {@code audit_log} — a user-preference mutation, not a
 * privileged action (spec §Authorization model) — and is in the slow-start
 * probation allowed set ({@code CommandPermissions.ALLOWED}).
 */
@ApplicationScoped
public class ReplyModeCommandHandler implements CommandHandler {

    private static final String SELECT_USER_ID_BY_ADAPTER_AND_CONTACT_ID =
            "SELECT id FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String SELECT_GROUP_ID_SQL =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ?";

    private static final String UPSERT_SCOPE_REPLY_MODE_SQL =
            "INSERT INTO scope_preferences (scope_kind, scope_id, reply_mode) "
                    + "VALUES (?, ?, ?) "
                    + "ON CONFLICT (scope_kind, scope_id) "
                    + "DO UPDATE SET reply_mode = EXCLUDED.reply_mode";

    private static final String SELECT_SCOPE_REPLY_MODE_SQL =
            "SELECT reply_mode FROM scope_preferences WHERE scope_kind = ? AND scope_id = ?";

    static final String MODE_TRANSLATE = "translate";
    static final String MODE_NATIVE = "native";

    @Inject BundleLoader bundleLoader;
    @Inject DataSource dataSource;
    @Inject InboundContext inboundContext;
    @Inject GroupMembershipRepository groupMembershipRepository;
    @Inject ChatReplyModeResolver replyModeResolver;

    @Override
    public String name() {
        return "reply-mode";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        final String scopeKind;
        final UUID scopeId;
        if (scope instanceof ScopeRef.Group group) {
            UUID actorId = lookupActorId(inboundContext.senderContactId());
            if (actorId == null) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_REPLY_MODE_GROUP_ADMIN, inboundContext.effectiveLanguage()));
            }
            UUID groupDbId = lookupGroupId(group.adapterGroupId());
            if (groupDbId == null
                    || (!isBotAdmin(actorId)
                        && !groupMembershipRepository.isGroupAdmin(groupDbId, actorId))) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_REPLY_MODE_GROUP_ADMIN, inboundContext.effectiveLanguage()));
            }
            scopeKind = "group";
            scopeId = groupDbId;
        } else {
            ScopeRef.Dm dm = (ScopeRef.Dm) scope;
            UUID actorId = lookupActorId(dm.contactId());
            if (actorId == null) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL, inboundContext.effectiveLanguage()));
            }
            scopeKind = "dm";
            scopeId = actorId;
        }

        String suppliedMode = parsePositionalMode(rawText);
        if (suppliedMode == null) {
            return statusReply(scope, scopeKind, scopeId);
        }
        if (!MODE_TRANSLATE.equals(suppliedMode) && !MODE_NATIVE.equals(suppliedMode)) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_REPLY_MODE_UNSUPPORTED, inboundContext.effectiveLanguage()));
        }

        upsertScopeReplyMode(scopeKind, scopeId, suppliedMode);

        if (MODE_NATIVE.equals(suppliedMode)
                && !replyModeResolver.nativeClears(inboundContext.effectiveLanguage())) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_MODE_SUCCESS_UNCLEARED, inboundContext.effectiveLanguage()));
        }
        String body = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_MODE_SUCCESS, inboundContext.effectiveLanguage()),
                suppliedMode);
        return reply(scope, body);
    }

    private OutboundMessage statusReply(ScopeRef scope, String scopeKind, UUID scopeId) {
        String stored = readScopeReplyMode(scopeKind, scopeId);
        String lang = inboundContext.effectiveLanguage();
        if (stored == null) {
            String body = MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_MODE_STATUS_DEFAULT, lang),
                    replyModeResolver.deploymentDefault());
            return reply(scope, body);
        }
        if (MODE_NATIVE.equals(stored) && !replyModeResolver.nativeClears(lang)) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_MODE_STATUS_UNCLEARED, lang));
        }
        String body = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_MODE_STATUS, lang), stored);
        return reply(scope, body);
    }

    private @Nullable String readScopeReplyMode(String scopeKind, UUID scopeId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_SCOPE_REPLY_MODE_SQL)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString("reply_mode");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ReplyModeCommandHandler.readScopeReplyMode failed for scope_kind=" + scopeKind, e);
        }
    }

    private void upsertScopeReplyMode(String scopeKind, UUID scopeId, String mode) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(UPSERT_SCOPE_REPLY_MODE_SQL)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setString(3, mode);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ReplyModeCommandHandler.upsertScopeReplyMode failed for contact_id="
                            + ContactIds.redact(inboundContext.senderContactId()), e);
        }
    }

    private @Nullable UUID lookupActorId(String contactId) {
        String adapter = inboundContext.adapterName();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     SELECT_USER_ID_BY_ADAPTER_AND_CONTACT_ID)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return (UUID) rs.getObject("id");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ReplyModeCommandHandler.lookupActorId failed for contact_id="
                            + ContactIds.redact(contactId), e);
        }
    }

    private @Nullable UUID lookupGroupId(String adapterGroupId) {
        String adapter = inboundContext.adapterName();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_GROUP_ID_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, adapterGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getObject("id", UUID.class);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ReplyModeCommandHandler.lookupGroupId failed", e);
        }
    }

    private boolean isBotAdmin(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_admin FROM users WHERE id = ?")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean("is_admin");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ReplyModeCommandHandler.isBotAdmin failed", e);
        }
    }

    /**
     * Extract the positional mode from {@code rawText}; {@code null} for a
     * bare invocation (the status-read path). Extra tokens after the mode
     * are ignored, matching {@code /lang}'s positional leniency.
     */
    private static @Nullable String parsePositionalMode(String rawText) {
        String[] parts = rawText.trim().split("\\s+", 2);
        if (parts.length < 2) {
            return null;
        }
        String remainder = parts[1].trim();
        if (remainder.isEmpty()) {
            return null;
        }
        return remainder.split("\\s+", 2)[0];
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }
}
