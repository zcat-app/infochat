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
 * Implements {@code /follow-all-sources} per {@code docs/spec/commands.md}
 * §Source management — repurposed under D59 (M1-621) to "re-include all
 * bootstrap sources": clears the calling scope's {@code source_exclusion}
 * rows so every opted-out bootstrap source rejoins the scope's implicit
 * world.
 *
 * <p>Motivation: under the D59 implicit-bootstrap model every scope
 * already retrieves the whole bootstrap catalogue, so the pre-D59
 * bulk-subscribe reading would be a silent no-op. Re-including cleared
 * exclusions keeps the command's original spirit — "give me everything"
 * — with a coherent effect; the per-source undo remains
 * {@code /unfollow-source <id>}.</p>
 *
 * <p><b>Idempotent, audited bulk write.</b> One set-based
 * {@code DELETE FROM source_exclusion} scoped to the caller — only the
 * calling scope's exclusions are touched, never another scope's. The
 * JDBC update-count is the number of exclusions cleared (the re-included
 * count in the reply); a re-run deletes nothing and reports zero. An
 * effective clear writes one {@link AuditAction#FOLLOW_ALL_SOURCES} row
 * audit-before-effect in the same transaction (Invariant 7; red-team
 * 2026-07-14 — the bulk re-include must be as attributable as the
 * exclusions it reverses); the zero-clear no-op writes no row (the
 * established no-effect-doesn't-audit pattern).</p>
 *
 * <p><b>Permission model</b> mirrors {@code /add-source} and
 * {@link UnfollowSourceCommandHandler}: any registered caller in their own DM
 * scope; group admin (or bot admin) only for the group scope. A probation
 * caller is refused upstream by {@code InboundRouter}'s step-5 gate — this
 * command is deliberately absent from {@link CommandPermissions}'s
 * allowed-during-probation set — so the handler carries no probation check (that
 * would be dead defensive code). Banned callers likewise never reach here (the
 * router's step-4 ban check is authoritative), matching the sibling
 * {@code /unfollow-source} which also omits a handler ban check.</p>
 *
 * <p>Auto-discovered by {@code InboundRouter} via the CDI
 * {@code Instance<CommandHandler>} scan; dispatch keys on {@link #name()}, so no
 * router-side edit is needed. Thin-SQL "Shape B" handler: the single statement
 * needs no repository class.</p>
 */
@ApplicationScoped
public class FollowAllSourcesCommandHandler implements CommandHandler {

    private static final String SELECT_USER_FLAGS_SQL =
            "SELECT id, is_admin FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String SELECT_GROUP_ID_SQL =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ?";

    // Clear the calling scope's bootstrap opt-outs in one statement. The
    // scope filter is the privacy boundary: only this scope's exclusions
    // are deleted. Update-count = exclusions cleared = the re-included
    // count; a re-run deletes nothing (idempotent).
    private static final String CLEAR_EXCLUSIONS_SQL =
            "DELETE FROM source_exclusion WHERE scope_kind = ? AND scope_id = ?";

    private static final String COUNT_EXCLUSIONS_SQL =
            "SELECT count(*) FROM source_exclusion WHERE scope_kind = ? AND scope_id = ?";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Inject
    GroupMembershipRepository groupMembershipRepository;

    @Inject
    AuditLogWriter auditLogWriter;

    @Override
    public String name() {
        return "follow-all-sources";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        String adapter = inboundContext.adapterName();
        String callerContactId = scope instanceof ScopeRef.Dm dm
                ? dm.contactId() : inboundContext.senderContactId();
        Optional<UserRow> actorOpt = lookupUser(adapter, callerContactId);

        // Permission gate + scope resolution, mirroring /add-source and
        // /unfollow-source: DM subscribes the caller's own scope; group is
        // group-admin-or-bot-admin only.
        final String scopeKind;
        final UUID scopeId;
        if (scope instanceof ScopeRef.Group group) {
            if (actorOpt.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_FOLLOW_ALL_SOURCES_GROUP_ADMIN_ONLY, inboundContext.effectiveLanguage()));
            }
            UserRow actor = actorOpt.get();
            UUID groupDbId = lookupGroupId(adapter, group.adapterGroupId());
            if (groupDbId == null
                    || (!actor.isAdmin()
                        && !groupMembershipRepository.isGroupAdmin(groupDbId, actor.id()))) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_FOLLOW_ALL_SOURCES_GROUP_ADMIN_ONLY, inboundContext.effectiveLanguage()));
            }
            scopeKind = "group";
            scopeId = groupDbId;
        } else {
            if (actorOpt.isEmpty()) {
                // The router dispatches /follow-all-sources only for a registered
                // DM caller, so an empty lookup here is a wiring invariant
                // violation, not a user-facing case (mirrors /add-source and
                // /unfollow-source's DM branch).
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL, inboundContext.effectiveLanguage()));
            }
            scopeKind = "dm";
            scopeId = actorOpt.get().id();
        }
        int reIncluded = clearExclusions(
                scopeKind, scopeId, actorOpt.get(), callerContactId, adapter);
        String body = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_FOLLOW_ALL_SOURCES_DONE, inboundContext.effectiveLanguage()),
                reIncluded);
        return reply(scope, body);
    }

    private int clearExclusions(String scopeKind, UUID scopeId, UserRow actor,
                                @Nullable String callerContactId, String adapter) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (countExclusions(conn, scopeKind, scopeId) == 0) {
                    // Idempotent zero-clear: no state change, no audit row
                    // (the established no-effect-doesn't-audit pattern).
                    conn.rollback();
                    return 0;
                }
                // Invariant 7: audit-before-effect, same transaction —
                // the bulk re-include must be as attributable as the
                // exclusions it reverses (red-team 2026-07-14).
                insertAudit(conn, actor, callerContactId, adapter, scopeId);
                int cleared;
                try (PreparedStatement ps = conn.prepareStatement(CLEAR_EXCLUSIONS_SQL)) {
                    ps.setString(1, scopeKind);
                    ps.setObject(2, scopeId);
                    cleared = ps.executeUpdate();
                }
                conn.commit();
                return cleared;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "FollowAllSourcesCommandHandler.clearExclusions failed for scope_kind="
                            + scopeKind, e);
        }
    }

    private int countExclusions(Connection conn, String scopeKind, UUID scopeId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(COUNT_EXCLUSIONS_SQL)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void insertAudit(Connection conn, UserRow actor,
                             @Nullable String callerContactId, String adapter,
                             UUID scopeId) throws SQLException {
        // target_kind='source' with the literal target_id='all' is the
        // established sentinel for a non-single-row source target
        // (LIST_SOURCES_ALL); the affected scope rides in scope_id.
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .actorUserId(actor.id())
                .actorContactId(callerContactId)
                .actorAdapter(adapter)
                .action(AuditAction.FOLLOW_ALL_SOURCES)
                .targetKind(TargetKind.SOURCE)
                .targetId("all")
                .scopeId(scopeId)
                .requestId(UUID.randomUUID().toString())
                .build();
        auditLogWriter.write(conn, row);
    }

    private Optional<UserRow> lookupUser(@Nullable String adapter, @Nullable String contactId) {
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
                    "FollowAllSourcesCommandHandler.lookupUser failed for contact_id="
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
                    "FollowAllSourcesCommandHandler.lookupGroupId failed", e);
        }
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private record UserRow(UUID id, boolean isAdmin) {}
}
