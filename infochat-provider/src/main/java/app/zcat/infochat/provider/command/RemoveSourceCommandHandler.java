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
import app.zcat.infochat.provider.group.GroupMembershipRepository;
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
import java.text.MessageFormat;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /remove-source <id>} per
 * {@code docs/spec/commands.md} §Source management +
 * {@code docs/spec/security.md} §Authorization model (step-8
 * audit-on-intent + step-9 execute).
 *
 * <p>Confirm-gated destructive admin command. Two-call shape per
 * {@link ConfirmStateService}:
 * <ol>
 *   <li><b>First call</b> ({@code /remove-source <id>}): admin gate →
 *       parse {@code <id>} as a {@code UUID} (parse failure →
 *       {@code error.remove_source.unknown_id}) → resolve source row
 *       → reject if already soft-deleted
 *       ({@code error.remove_source.already_deleted}) → pre-write
 *       the {@link AuditAction#REMOVE_SOURCE_INTENT} audit row
 *       (spec §Authorization model step 8) → register a pending
 *       {@link RemoveSourceConfirm} → return the confirm prompt.
 *       Validation failures on this leg write NO audit row and
 *       store NO pending state.</li>
 *   <li><b>Confirm call</b> (any body matching the sweep prefix that
 *       ends in {@code " confirm"}): {@code takeMatching} pops the
 *       pending; on empty Optional → {@code error.confirm.no_pending}.
 *       On non-empty → execute the soft-delete + cascade in ONE
 *       transaction: pre-write the {@link AuditAction#REMOVE_SOURCE}
 *       audit row → {@code DELETE FROM source_subscription WHERE
 *       source_id = ?} → {@code UPDATE source SET deleted_at = now()
 *       WHERE id = ?}. Reply interpolates the cascade-deleted
 *       subscription count.</li>
 * </ol>
 *
 * <p>Cascade-delete ordering: subscriptions first so the FK to
 * {@code source} resolves cleanly (the soft-delete leaves the row
 * present, but ordering matches the spec intent "cascade-deleted in
 * the same transaction"). Both writes commit together with the
 * pre-written {@code REMOVE_SOURCE} audit row.</p>
 *
 * <p>Group scope is not supported in v1 — the SPI does not carry the
 * actor's contact id, so the admin gate cannot resolve. T2-F widens
 * the SPI.</p>
 */
@ApplicationScoped
public class RemoveSourceCommandHandler implements CommandHandler {

    private static final String SELECT_GROUP_ID_SQL =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ?";

    private static final String SELECT_SOURCE_SQL =
            "SELECT display_name, deleted_at FROM source WHERE id = ?";

    private static final String SELECT_SOURCE_FOR_UPDATE_SQL =
            "SELECT display_name, deleted_at FROM source WHERE id = ? FOR UPDATE";

    private static final String COUNT_SUBSCRIPTIONS_SQL =
            "SELECT count(*) FROM source_subscription WHERE source_id = ?";

    private static final String DELETE_SUBSCRIPTIONS_SQL =
            "DELETE FROM source_subscription WHERE source_id = ?";

    private static final String UPDATE_SOURCE_SOFT_DELETE_SQL =
            "UPDATE source SET deleted_at = now(), deleted_by = ? WHERE id = ?";

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
    GroupMembershipRepository groupMembershipRepository;

    @Inject
    UserRepository userRepository;

    @Override
    public String name() {
        return "remove-source";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        if (scope instanceof ScopeRef.Group group) {
            if (!isGroupAdmin(group)) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_GROUP_ADMIN_NOT_IN_V1, inboundContext.effectiveLanguage()));
            }
        }

        String adapter = inboundContext.adapterName();
        String callerContactId = scope instanceof ScopeRef.Dm dm
                ? dm.contactId() : inboundContext.senderContactId();

        // Admin gate runs FIRST — non-admin sending the confirm-shape
        // must see error.admin_only (not error.confirm.no_pending);
        // admin gate has precedence over the confirm fork.
        Optional<UserRow> actorOpt = lookupUser(adapter, callerContactId);
        if (actorOpt.isEmpty() || !actorOpt.get().isAdmin) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY, inboundContext.effectiveLanguage()));
        }
        UserRow actor = actorOpt.get();

        // Confirm fork. Per the M1-051 BanCommandHandler precedent:
        // any body ending in " confirm" is the second-leg dispatch;
        // takeMatching pops the typed RemoveSourceConfirm payload.
        if (rawText.trim().endsWith(" confirm")) {
            Optional<ConfirmStateService.PendingConfirm> taken =
                    confirmStateService.takeMatching(actor.id, scope, "remove-source");
            if (taken.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_CONFIRM_NO_PENDING, inboundContext.effectiveLanguage()));
            }
            RemoveSourceConfirm pending = (RemoveSourceConfirm) taken.get();
            return executeRemove(scope, actor, adapter, pending.sourceId());
        }

        // First-call: parse the positional <id>.
        UUID sourceId = parseSourceId(rawText);
        if (sourceId == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_REMOVE_SOURCE_UNKNOWN_ID, inboundContext.effectiveLanguage()));
        }

        Optional<SourceRow> sourceOpt = lookupSource(sourceId);
        if (sourceOpt.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_REMOVE_SOURCE_UNKNOWN_ID, inboundContext.effectiveLanguage()));
        }
        SourceRow source = sourceOpt.get();
        if (source.deletedAt != null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_REMOVE_SOURCE_ALREADY_DELETED, inboundContext.effectiveLanguage()));
        }

        long subscriberCount = countSubscriptions(sourceId);

        // Audit-on-intent (spec §Authorization model step 8). Single
        // atomic INSERT with autoCommit=true; failure here surfaces
        // SQLException and the prompt is never sent. The intent row
        // persists across a probe-and-abandon attempt (M1-051
        // BanCommandHandler precedent at lines 192-214).
        try (Connection conn = dataSource.getConnection()) {
            insertAudit(conn, AuditAction.REMOVE_SOURCE_INTENT, sourceId, actor, adapter,
                    UUID.randomUUID().toString());
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "RemoveSourceCommandHandler intent audit failed for adapter=" + adapter
                            + " contact_id=" + ContactIds.redact(callerContactId),
                    e);
        }
        confirmStateService.remember(actor.id, scope, new RemoveSourceConfirm(sourceId));

        String prompt = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_CONFIRM_PROMPT_REMOVE_SOURCE, inboundContext.effectiveLanguage()),
                source.displayName,
                Long.toString(subscriberCount),
                Long.toString(confirmStateService.timeoutSeconds()));
        return reply(scope, prompt);
    }

    private OutboundMessage executeRemove(ScopeRef scope, UserRow actor, String adapter,
                                          UUID sourceId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Lock the source row before the cascade so a concurrent
                // /source-enable cannot race in and revive a row we are
                // about to soft-delete (or vice versa).
                LockedSourceRow locked = selectSourceForUpdate(conn, sourceId);
                if (locked == null) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_REMOVE_SOURCE_UNKNOWN_ID, inboundContext.effectiveLanguage()));
                }
                if (locked.deletedAt != null) {
                    conn.rollback();
                    return reply(scope,
                            bundleLoader.get(BundleKeys.ERROR_REMOVE_SOURCE_ALREADY_DELETED, inboundContext.effectiveLanguage()));
                }

                String requestId = UUID.randomUUID().toString();

                // Invariant 7: audit-before-effect. The completion
                // REMOVE_SOURCE row writes BEFORE the mutation; a
                // post-INSERT failure rolls both back together.
                insertAudit(conn, AuditAction.REMOVE_SOURCE, sourceId, actor, adapter, requestId);

                int cascadeDeleted = deleteSubscriptions(conn, sourceId);
                softDeleteSource(conn, sourceId, actor.id);

                conn.commit();

                String body = MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_REMOVE_SOURCE_SUCCESS, inboundContext.effectiveLanguage()),
                        locked.displayName, Long.toString(cascadeDeleted));
                return reply(scope, body);
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "RemoveSourceCommandHandler.executeRemove failed for adapter=" + adapter,
                        e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "RemoveSourceCommandHandler connection failed for adapter=" + adapter, e);
        }
    }

    private Optional<UserRow> lookupUser(String adapter, String contactId) {
        if (adapter == null || contactId == null) {
            return Optional.empty();
        }
        return userRepository.findByAdapterAndContactId(adapter, contactId)
                .map(u -> new UserRow(u.id(), u.contactId(), u.isAdmin()));
    }

    private Optional<SourceRow> lookupSource(UUID sourceId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_SOURCE_SQL)) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new SourceRow(
                        rs.getString("display_name"),
                        rs.getTimestamp("deleted_at") == null
                                ? null
                                : rs.getTimestamp("deleted_at").toInstant()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "RemoveSourceCommandHandler.lookupSource failed for id=" + sourceId, e);
        }
    }

    private @Nullable LockedSourceRow selectSourceForUpdate(Connection conn, UUID sourceId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_SOURCE_FOR_UPDATE_SQL)) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new LockedSourceRow(
                        rs.getString("display_name"),
                        rs.getTimestamp("deleted_at") == null
                                ? null
                                : rs.getTimestamp("deleted_at").toInstant());
            }
        }
    }

    private long countSubscriptions(UUID sourceId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(COUNT_SUBSCRIPTIONS_SQL)) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "RemoveSourceCommandHandler.countSubscriptions failed for id=" + sourceId, e);
        }
    }

    private int deleteSubscriptions(Connection conn, UUID sourceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(DELETE_SUBSCRIPTIONS_SQL)) {
            ps.setObject(1, sourceId);
            return ps.executeUpdate();
        }
    }

    private void softDeleteSource(Connection conn, UUID sourceId, UUID actorId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_SOURCE_SOFT_DELETE_SQL)) {
            ps.setObject(1, actorId);
            ps.setObject(2, sourceId);
            ps.executeUpdate();
        }
    }

    private void insertAudit(Connection conn, AuditAction action, UUID sourceId,
                             UserRow actor, String adapter, String requestId) throws SQLException {
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .actorUserId(actor.id)
                .actorContactId(actor.contactId)
                .actorAdapter(adapter)
                .action(action)
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

    private boolean isGroupAdmin(ScopeRef.Group group) {
        String adapter = inboundContext.adapterName();
        String senderContact = inboundContext.senderContactId();
        Optional<UserRow> user = lookupUser(adapter, senderContact);
        if (user.isEmpty()) {
            return false;
        }
        if (user.get().isAdmin) {
            return true;
        }
        UUID groupDbId = lookupGroupId(adapter, group.adapterGroupId());
        return groupDbId != null
                && groupMembershipRepository.isGroupAdmin(groupDbId, user.get().id);
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
                    "RemoveSourceCommandHandler.lookupGroupId failed", e);
        }
    }

    private record UserRow(UUID id, String contactId, boolean isAdmin) {}

    private record SourceRow(String displayName, @Nullable Instant deletedAt) {}

    private record LockedSourceRow(String displayName, @Nullable Instant deletedAt) {}
}
