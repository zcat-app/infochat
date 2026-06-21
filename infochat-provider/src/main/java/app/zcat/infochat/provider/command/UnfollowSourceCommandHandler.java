package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /unfollow-source <id>} per
 * {@code docs/spec/commands.md} §Source management — the per-scope
 * inverse of the subscription {@code /add-source} creates.
 *
 * <p>Deletes ONLY the caller scope's {@code source_subscription} row
 * and <b>never touches the global {@code source} row</b> (contrast
 * {@link RemoveSourceCommandHandler}, which soft-deletes the source and
 * cascade-deletes ALL its subscriptions). A source with no remaining
 * subscribers is a valid state, not an auto-soft-delete trigger.</p>
 *
 * <p>Permission model (spec §Source management "v1" note):</p>
 * <ul>
 *   <li><b>DM</b> — any registered caller unsubscribes their own
 *       {@code (scope_kind='dm', scope_id=callerUserId)} row.</li>
 *   <li><b>Group</b> — group admin or bot admin only. A plain group
 *       member cannot unfollow a group subscription: the "any member
 *       may unfollow what they added" exception is NOT in v1 (it needs
 *       per-contributor ownership tracking that does not exist). The
 *       gate mirrors {@link AddSourceCommandHandler}'s group branch.</li>
 * </ul>
 *
 * <p>Outcomes: a malformed or unknown {@code <id>} errors (mirroring
 * {@code /remove-source}); a known source the caller does not subscribe
 * to in this scope yields a friendly no-op reply and writes NO audit
 * row; a real deletion writes one {@link AuditAction#UNFOLLOW_SOURCE}
 * row audit-before-effect (Invariant 7) in the same transaction.</p>
 *
 * <p>Auto-discovered by {@code InboundRouter} via the CDI
 * {@code Instance<CommandHandler>} scan; dispatch keys on {@link #name()},
 * so no router-side edit is needed when this handler lands.</p>
 *
 * <p>Thin-SQL "Shape B" handler: the scoped delete is one statement and
 * needs no repository class.</p>
 */
@ApplicationScoped
public class UnfollowSourceCommandHandler implements CommandHandler {

    private static final String SELECT_USER_FLAGS_SQL =
            "SELECT id, is_admin FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String SELECT_GROUP_ID_SQL =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ?";

    private static final String SELECT_SOURCE_DISPLAY_NAME_SQL =
            "SELECT display_name FROM source WHERE id = ?";

    // FOR UPDATE locks the caller's subscription row so the audit-write
    // and the delete that follow it act on a row that cannot vanish
    // between the existence check and the DELETE within this transaction.
    private static final String SELECT_SUBSCRIPTION_FOR_UPDATE_SQL =
            "SELECT 1 FROM source_subscription "
                    + "WHERE scope_kind = ? AND scope_id = ? AND source_id = ? FOR UPDATE";

    private static final String DELETE_SUBSCRIPTION_SQL =
            "DELETE FROM source_subscription "
                    + "WHERE scope_kind = ? AND scope_id = ? AND source_id = ?";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Inject
    AuditLogWriter auditLogWriter;

    @Inject
    GroupMembershipRepository groupMembershipRepository;

    @Override
    public String name() {
        return "unfollow-source";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        String adapter = inboundContext.adapterName();
        String callerContactId = scope instanceof ScopeRef.Dm dm
                ? dm.contactId() : inboundContext.senderContactId();
        Optional<UserRow> actorOpt = lookupUser(adapter, callerContactId);

        // Permission gate + scope resolution. Runs BEFORE the <id> parse
        // so a plain group member is refused regardless of whether the id
        // they typed is valid (no parse-error leak to an unauthorized
        // caller).
        final String scopeKind;
        final UUID scopeId;
        if (scope instanceof ScopeRef.Group group) {
            if (actorOpt.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_UNFOLLOW_SOURCE_GROUP_ADMIN_ONLY, inboundContext.effectiveLanguage()));
            }
            UserRow actor = actorOpt.get();
            UUID groupDbId = lookupGroupId(adapter, group.adapterGroupId());
            if (groupDbId == null
                    || (!actor.isAdmin
                        && !groupMembershipRepository.isGroupAdmin(groupDbId, actor.id))) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_UNFOLLOW_SOURCE_GROUP_ADMIN_ONLY, inboundContext.effectiveLanguage()));
            }
            scopeKind = "group";
            scopeId = groupDbId;
        } else {
            if (actorOpt.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL, inboundContext.effectiveLanguage()));
            }
            scopeKind = "dm";
            scopeId = actorOpt.get().id;
        }
        UserRow actor = actorOpt.get();

        UUID sourceId = parseSourceId(rawText);
        if (sourceId == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_UNFOLLOW_SOURCE_UNKNOWN_ID, inboundContext.effectiveLanguage()));
        }
        Optional<String> displayName = lookupSourceDisplayName(sourceId);
        if (displayName.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_UNFOLLOW_SOURCE_UNKNOWN_ID, inboundContext.effectiveLanguage()));
        }

        return executeUnfollow(scope, actor, adapter, callerContactId,
                scopeKind, scopeId, sourceId, displayName.get());
    }

    private OutboundMessage executeUnfollow(ScopeRef scope, UserRow actor, String adapter,
                                            String callerContactId, String scopeKind,
                                            UUID scopeId, UUID sourceId, String displayName) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!lockSubscription(conn, scopeKind, scopeId, sourceId)) {
                    // No subscription for this caller scope: friendly
                    // no-op, NO audit row (the established
                    // read-only / no-effect-doesn't-audit pattern).
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.REPLY_UNFOLLOW_SOURCE_NOT_SUBSCRIBED, inboundContext.effectiveLanguage()));
                }

                // Invariant 7: audit-before-effect. The UNFOLLOW_SOURCE
                // row writes BEFORE the delete; a post-INSERT failure
                // rolls both back together.
                insertAudit(conn, sourceId, actor, callerContactId, adapter,
                        UUID.randomUUID().toString());
                deleteSubscription(conn, scopeKind, scopeId, sourceId);
                conn.commit();

                String body = MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_UNFOLLOW_SOURCE_SUCCESS, inboundContext.effectiveLanguage()),
                        displayName);
                return reply(scope, body);
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "UnfollowSourceCommandHandler.executeUnfollow failed for adapter="
                                + adapter, e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "UnfollowSourceCommandHandler connection failed for adapter=" + adapter, e);
        }
    }

    private Optional<UserRow> lookupUser(String adapter, @Nullable String contactId) {
        if (adapter == null || contactId == null) {
            return Optional.empty();
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_USER_FLAGS_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new UserRow(
                        rs.getObject("id", UUID.class), rs.getBoolean("is_admin")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "UnfollowSourceCommandHandler.lookupUser failed for contact_id="
                            + ContactIds.redact(contactId), e);
        }
    }

    private @Nullable UUID lookupGroupId(String adapter, String upstreamGroupId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_GROUP_ID_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getObject("id", UUID.class);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "UnfollowSourceCommandHandler.lookupGroupId failed", e);
        }
    }

    private Optional<String> lookupSourceDisplayName(UUID sourceId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_SOURCE_DISPLAY_NAME_SQL)) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(rs.getString("display_name"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "UnfollowSourceCommandHandler.lookupSourceDisplayName failed for id="
                            + sourceId, e);
        }
    }

    private boolean lockSubscription(Connection conn, String scopeKind, UUID scopeId,
                                     UUID sourceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_SUBSCRIPTION_FOR_UPDATE_SQL)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void deleteSubscription(Connection conn, String scopeKind, UUID scopeId,
                                    UUID sourceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(DELETE_SUBSCRIPTION_SQL)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, sourceId);
            ps.executeUpdate();
        }
    }

    private void insertAudit(Connection conn, UUID sourceId, UserRow actor,
                             String actorContactId, String adapter, String requestId)
            throws SQLException {
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .actorUserId(actor.id)
                .actorContactId(actorContactId)
                .actorAdapter(adapter)
                .action(AuditAction.UNFOLLOW_SOURCE)
                .targetKind(TargetKind.SOURCE)
                .targetId(sourceId.toString())
                .requestId(requestId)
                .build();
        auditLogWriter.write(conn, row);
    }

    private static @Nullable UUID parseSourceId(String rawText) {
        String[] split = rawText.trim().split("\\s+");
        if (split.length < 2) {
            return null;
        }
        try {
            return UUID.fromString(split[1]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private record UserRow(UUID id, boolean isAdmin) {}
}
