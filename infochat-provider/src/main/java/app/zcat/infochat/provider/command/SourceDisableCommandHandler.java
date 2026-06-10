package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
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
 * Implements {@code /source-disable <id>} per
 * {@code docs/spec/commands.md} §Source management and §Permission model.
 *
 * <p>The simplest of the four M1-053 admin handlers: no confirm gate (the
 * operator is intentionally pausing the source), no URL probe (a disabled
 * source still has a known-good URL — disabling is the human-initiated
 * brake, distinct from the Collector's failure-counter-driven
 * {@code active → failed} transition). The handler executes one SQL
 * transaction:
 * <ol>
 *   <li>Admin gate via {@code (adapter, contact_id)} actor lookup —
 *       non-admin short-circuits to {@code error.admin_only}, no audit
 *       row, no state change.</li>
 *   <li>Group-scope reject — {@code ScopeRef.Group} does not carry the
 *       actor's contact id in v1 (T2-F lands the SPI widening);
 *       returns {@code error.group_admin_not_in_v1}.</li>
 *   <li>Parse the positional {@code <id>} as a {@code UUID} literal;
 *       parse failure returns {@code error.source_disable.unknown_id}.</li>
 *   <li>Open one transaction; SELECT the source row with
 *       {@code FOR UPDATE} to lock it; reject if
 *       {@code status <> 'active' OR deleted_at IS NOT NULL} with
 *       {@code error.source_disable.not_active}; otherwise pre-write the
 *       {@code SOURCE_DISABLE} audit row (Invariant 7: audit-before-effect)
 *       then {@code UPDATE source SET status = 'disabled' WHERE id = ?};
 *       commit. A failure after the audit INSERT but before/during the
 *       UPDATE rolls both writes back together.</li>
 * </ol>
 *
 * <p>The handler writes audit rows via the M1-041 {@link AuditLogWriter}
 * consolidation (sole writer of {@code audit_log} from Java source). The
 * audit row's {@code target_kind = "source"}, {@code target_id} carries
 * the source UUID as text per V5 §2.1.7 column conventions.</p>
 */
@ApplicationScoped
public class SourceDisableCommandHandler implements CommandHandler {

    private static final String SELECT_GROUP_ID_SQL =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ?";

    private static final String SELECT_SOURCE_SQL =
            "SELECT display_name, status, deleted_at FROM source WHERE id = ?";

    private static final String SELECT_SOURCE_FOR_UPDATE_SQL =
            "SELECT status, deleted_at FROM source WHERE id = ? FOR UPDATE";

    private static final String UPDATE_SOURCE_DISABLE_SQL =
            "UPDATE source SET status = 'disabled' WHERE id = ?";

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

    @Inject
    UserRepository userRepository;

    @Override
    public String name() {
        return "source-disable";
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

        Optional<UserRow> actorOpt = lookupUser(adapter, callerContactId);
        if (actorOpt.isEmpty() || !actorOpt.get().isAdmin) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY, inboundContext.effectiveLanguage()));
        }
        UserRow actor = actorOpt.get();

        UUID sourceId = parseSourceId(rawText);
        if (sourceId == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_SOURCE_DISABLE_UNKNOWN_ID, inboundContext.effectiveLanguage()));
        }

        // Pre-flight read outside the transaction — surfaces unknown-id
        // and not-active errors without opening (and immediately
        // rolling back) a transaction. The TOCTOU-safe re-check runs
        // inside the transaction with SELECT FOR UPDATE before the
        // audit + state write.
        Optional<SourceRow> preflightOpt = lookupSource(sourceId);
        if (preflightOpt.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_SOURCE_DISABLE_UNKNOWN_ID, inboundContext.effectiveLanguage()));
        }
        SourceRow preflight = preflightOpt.get();
        if (!"active".equals(preflight.status) || preflight.deletedAt != null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_SOURCE_DISABLE_NOT_ACTIVE, inboundContext.effectiveLanguage()));
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // TOCTOU-safe re-check: a concurrent /source-disable,
                // /source-enable, or /remove-source could have raced
                // between the pre-flight read and here. Lock the row
                // and re-read its status / deleted_at; if the state
                // changed, surface the same not-active error.
                LockedRow locked = selectSourceForUpdate(conn, sourceId);
                if (locked == null
                        || !"active".equals(locked.status)
                        || locked.deletedAt != null) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_SOURCE_DISABLE_NOT_ACTIVE, inboundContext.effectiveLanguage()));
                }

                // Invariant 7: audit-before-effect. The SOURCE_DISABLE
                // audit row is pre-written inside the transaction so a
                // failure on the subsequent UPDATE rolls both back
                // together — audit-vs-state divergence is forbidden.
                insertAudit(conn, AuditAction.SOURCE_DISABLE, sourceId, actor, adapter);
                updateSourceDisable(conn, sourceId);
                conn.commit();

                String body = MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_SOURCE_DISABLE_SUCCESS, inboundContext.effectiveLanguage()),
                        preflight.displayName);
                return reply(scope, body);
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "SourceDisableCommandHandler failed for adapter=" + adapter
                                + " contact_id=" + ContactIds.redact(callerContactId),
                        e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "SourceDisableCommandHandler connection failed for adapter=" + adapter
                            + " contact_id=" + ContactIds.redact(callerContactId),
                    e);
        }
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
                        rs.getString("status"),
                        rs.getTimestamp("deleted_at") == null
                                ? null
                                : rs.getTimestamp("deleted_at").toInstant()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "SourceDisableCommandHandler.lookupSource failed for id=" + sourceId, e);
        }
    }

    private Optional<UserRow> lookupUser(String adapter, String contactId) {
        if (adapter == null || contactId == null) {
            return Optional.empty();
        }
        return userRepository.findByAdapterAndContactId(adapter, contactId)
                .map(u -> new UserRow(u.id(), u.contactId(), u.isAdmin()));
    }

    private @Nullable LockedRow selectSourceForUpdate(Connection conn, UUID sourceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_SOURCE_FOR_UPDATE_SQL)) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new LockedRow(
                        rs.getString("status"),
                        rs.getTimestamp("deleted_at") == null
                                ? null
                                : rs.getTimestamp("deleted_at").toInstant());
            }
        }
    }

    private void insertAudit(Connection conn, AuditAction action, UUID sourceId,
                             UserRow actor, String adapter) throws SQLException {
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .actorUserId(actor.id)
                .actorContactId(actor.contactId)
                .actorAdapter(adapter)
                .action(action)
                .targetKind("source")
                .targetId(sourceId.toString())
                .requestId(UUID.randomUUID().toString())
                .build();
        auditLogWriter.write(conn, row);
    }

    private void updateSourceDisable(Connection conn, UUID sourceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_SOURCE_DISABLE_SQL)) {
            ps.setObject(1, sourceId);
            ps.executeUpdate();
        }
    }

    private static @Nullable UUID parseSourceId(String rawText) {
        // /source-disable has no confirm gate; the positional <id> is
        // the single non-empty token after the command name.
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
                    "SourceDisableCommandHandler.lookupGroupId failed", e);
        }
    }

    private record UserRow(UUID id, String contactId, boolean isAdmin) {}

    private record SourceRow(String displayName, String status, @Nullable Instant deletedAt) {}

    private record LockedRow(String status, @Nullable Instant deletedAt) {}
}
