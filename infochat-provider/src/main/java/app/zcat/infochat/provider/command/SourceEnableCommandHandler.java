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
import app.zcat.infochat.provider.source.UrlProbe;
import app.zcat.infochat.provider.source.UrlProbe.ProbeResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /source-enable <id>} per
 * {@code docs/spec/commands.md} §Source management + §Permission model
 * + {@code docs/spec/security.md} §Authorization model.
 *
 * <p>Three-branch admin mutator with a kind gate:</p>
 * <ol>
 *   <li><b>Kind gate</b> — runs BEFORE state-branching, AFTER the
 *       admin gate and source-id resolution. Only {@code kind='rss'}
 *       qualifies in v1; non-rss kinds return
 *       {@code error.source_enable.kind_not_supported_in_v1}. This
 *       bounds the handler to the v1 HTTP-shaped probe surface
 *       (per {@code FetchScheduler} which schedules only
 *       {@code kind='rss'} rows). The relay-probe primitive for
 *       Nostr/Bluesky/etc. lands in a later ticket alongside
 *       {@code StreamSourceSupervisor}.</li>
 *   <li><b>State branch: {@code active AND deleted_at IS NULL}</b>
 *       → {@code error.source_enable.already_active}, no probe,
 *       no audit, no state change.</li>
 *   <li><b>State branch: {@code (failed|disabled) AND deleted_at
 *       IS NULL}</b> → run {@link UrlProbe} (HEAD/small-range-GET);
 *       probe failure → {@code error.source_enable.probe_failed}
 *       (single key collapses all probe-failure shapes);
 *       probe success → ONE transaction: pre-write
 *       {@link AuditAction#SOURCE_ENABLE} audit row + {@code UPDATE
 *       source SET status='active', consecutive_failures = 0 WHERE
 *       id = ?}. NO confirm gate (the row was always present;
 *       re-enabling an operationally-failed source is not
 *       destructive). Reply: {@code reply.source_enable.success}.</li>
 *   <li><b>State branch: {@code deleted_at IS NOT NULL}</b>
 *       (soft-deleted revival) → confirm-gated. First call:
 *       pre-write {@link AuditAction#SOURCE_ENABLE_INTENT} (spec
 *       §Authorization step 8) → register a {@link SourceEnableConfirm}
 *       pending → return the soft-deleted-revival prompt.
 *       Confirm call: {@code takeMatching} → run probe (probe
 *       failure leaves the row soft-deleted with no audit row, no
 *       state change) → ONE transaction: {@code SOURCE_ENABLE} audit
 *       + {@code UPDATE source SET deleted_at = NULL, status =
 *       'active', consecutive_failures = 0 WHERE id = ?}. Reply
 *       includes the literal no-subscriptions-restored disclosure
 *       per spec §Source management.</li>
 * </ol>
 *
 * <p>Subscriptions are NEVER recreated on revival (the spec
 * disclosure pins this).</p>
 */
@ApplicationScoped
public class SourceEnableCommandHandler implements CommandHandler {

    private static final String SELECT_USER_SQL =
            "SELECT id, contact_id, is_admin FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String SELECT_SOURCE_SQL =
            "SELECT display_name, identifier, kind, status, deleted_at "
                    + "FROM source WHERE id = ?";

    private static final String SELECT_SOURCE_FOR_UPDATE_SQL =
            "SELECT display_name, identifier, kind, status, deleted_at "
                    + "FROM source WHERE id = ? FOR UPDATE";

    private static final String UPDATE_SOURCE_REACTIVATE_SQL =
            "UPDATE source SET status = 'active', consecutive_failures = 0 WHERE id = ?";

    private static final String UPDATE_SOURCE_REVIVE_SQL =
            "UPDATE source SET deleted_at = NULL, deleted_by = NULL, "
                    + "  status = 'active', consecutive_failures = 0 WHERE id = ?";

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
    UrlProbe urlProbe;

    @Override
    public String name() {
        return "source-enable";
    }

    @Override
    public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
        if (scope instanceof ScopeRef.Group) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_GROUP_ADMIN_NOT_IN_V1));
        }

        String adapter = inboundContext.adapterName();
        String callerContactId = contactIdOf(scope);

        // Admin gate first — non-admin sending the confirm-shape must
        // see error.admin_only (precedence over confirm fork).
        Optional<UserRow> actorOpt = lookupUser(adapter, callerContactId);
        if (actorOpt.isEmpty() || !actorOpt.get().isAdmin) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
        }
        UserRow actor = actorOpt.get();

        // Confirm fork (soft-deleted revival second leg).
        if (rawText.trim().endsWith(" confirm")) {
            Optional<ConfirmStateService.PendingConfirm> taken =
                    confirmStateService.takeMatching(actor.id, scope, "source-enable");
            if (taken.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_CONFIRM_NO_PENDING));
            }
            SourceEnableConfirm pending = (SourceEnableConfirm) taken.get();
            return executeRevive(scope, actor, adapter, pending.sourceId());
        }

        // First call: parse <id>.
        UUID sourceId = parseSourceId(rawText);
        if (sourceId == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_SOURCE_ENABLE_UNKNOWN_ID));
        }

        Optional<SourceRow> sourceOpt = lookupSource(sourceId);
        if (sourceOpt.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_SOURCE_ENABLE_UNKNOWN_ID));
        }
        SourceRow source = sourceOpt.get();

        // Kind gate: v1 only re-enables kind='rss' rows because the
        // Collector's FetchScheduler only schedules rss rows. Allowing
        // /source-enable on a stream-shaped kind would silently
        // activate a row no fetcher will read.
        if (!"rss".equals(source.kind)) {
            return reply(scope,
                    bundleLoader.get(BundleKeys.ERROR_SOURCE_ENABLE_KIND_NOT_SUPPORTED_IN_V1));
        }

        // State branch.
        if (source.deletedAt != null) {
            return promptRevive(scope, actor, adapter, sourceId, source);
        }
        if ("active".equals(source.status)) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_SOURCE_ENABLE_ALREADY_ACTIVE));
        }
        // failed or disabled — re-enable directly with a probe.
        return reactivateFailedOrDisabled(scope, actor, adapter, sourceId, source);
    }

    private OutboundMessage reactivateFailedOrDisabled(ScopeRef scope, UserRow actor,
                                                       String adapter, UUID sourceId,
                                                       SourceRow source) {
        ProbeResult probe = probeSourceUrl(source.identifier);
        if (!probe.ok()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_SOURCE_ENABLE_PROBE_FAILED));
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // TOCTOU-safe re-check: a concurrent /remove-source or
                // /source-disable could have raced. Re-read the locked
                // row; if state changed, surface a generic error rather
                // than silently activating something we did not see.
                LockedRow locked = selectSourceForUpdate(conn, sourceId);
                if (locked == null
                        || !"rss".equals(locked.kind)
                        || locked.deletedAt != null
                        || "active".equals(locked.status)) {
                    conn.rollback();
                    // Surface the most likely state-mismatch reason as
                    // already_active (the row may have been activated
                    // by a concurrent dispatch). The user can retry to
                    // see the fresh state.
                    return reply(scope,
                            bundleLoader.get(BundleKeys.ERROR_SOURCE_ENABLE_ALREADY_ACTIVE));
                }
                insertAudit(conn, AuditAction.SOURCE_ENABLE, sourceId, actor, adapter,
                        UUID.randomUUID().toString());
                updateSourceReactivate(conn, sourceId);
                conn.commit();
                String body = MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_SOURCE_ENABLE_SUCCESS),
                        source.displayName);
                return reply(scope, body);
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "SourceEnableCommandHandler.reactivate failed for adapter=" + adapter, e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "SourceEnableCommandHandler connection failed for adapter=" + adapter, e);
        }
    }

    private OutboundMessage promptRevive(ScopeRef scope, UserRow actor, String adapter,
                                         UUID sourceId, SourceRow source) {
        // Audit-on-intent: write the SOURCE_ENABLE_INTENT row BEFORE
        // remember() / prompt so a probe-and-abandon attempt leaves an
        // audit trail. Single auto-committed INSERT.
        try (Connection conn = dataSource.getConnection()) {
            insertAudit(conn, AuditAction.SOURCE_ENABLE_INTENT, sourceId, actor, adapter,
                    UUID.randomUUID().toString());
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "SourceEnableCommandHandler intent audit failed for adapter=" + adapter, e);
        }
        confirmStateService.remember(actor.id, scope, new SourceEnableConfirm(sourceId));
        String prompt = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_CONFIRM_PROMPT_SOURCE_ENABLE_SOFT_DELETED),
                source.displayName,
                Long.toString(confirmStateService.timeoutSeconds()));
        return reply(scope, prompt);
    }

    private OutboundMessage executeRevive(ScopeRef scope, UserRow actor, String adapter,
                                          UUID sourceId) {
        // Pre-flight read outside the transaction — surfaces a
        // disappeared row early. The probe runs OUTSIDE the
        // transaction (probe is HTTP I/O; holding a row lock across
        // an external call is unsafe).
        Optional<SourceRow> preflightOpt = lookupSource(sourceId);
        if (preflightOpt.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_SOURCE_ENABLE_UNKNOWN_ID));
        }
        SourceRow preflight = preflightOpt.get();
        if (preflight.deletedAt == null) {
            // The row was un-soft-deleted between the prompt and the
            // confirm; nothing to revive.
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_SOURCE_ENABLE_ALREADY_ACTIVE));
        }
        if (!"rss".equals(preflight.kind)) {
            return reply(scope,
                    bundleLoader.get(BundleKeys.ERROR_SOURCE_ENABLE_KIND_NOT_SUPPORTED_IN_V1));
        }

        ProbeResult probe = probeSourceUrl(preflight.identifier);
        if (!probe.ok()) {
            // Probe failure on the revive path: leave the row
            // soft-deleted, no audit row, no state change (the spec
            // commits to "row remains in prior state on probe
            // failure"). The pending was already consumed by
            // takeMatching upstream — the admin needs to re-issue the
            // first call to restart the flow.
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_SOURCE_ENABLE_PROBE_FAILED));
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                LockedRow locked = selectSourceForUpdate(conn, sourceId);
                if (locked == null
                        || !"rss".equals(locked.kind)
                        || locked.deletedAt == null) {
                    conn.rollback();
                    return reply(scope,
                            bundleLoader.get(BundleKeys.ERROR_SOURCE_ENABLE_ALREADY_ACTIVE));
                }
                String requestId = UUID.randomUUID().toString();
                insertAudit(conn, AuditAction.SOURCE_ENABLE, sourceId, actor, adapter, requestId);
                updateSourceRevive(conn, sourceId);
                conn.commit();
                String successLine = MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_SOURCE_ENABLE_SUCCESS_FROM_SOFT_DELETED),
                        locked.displayName);
                String disclosure = bundleLoader.get(
                        BundleKeys.REPLY_SOURCE_ENABLE_NO_SUBSCRIPTIONS_RESTORED);
                return reply(scope, successLine + "\n" + disclosure);
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "SourceEnableCommandHandler.executeRevive failed for adapter=" + adapter,
                        e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "SourceEnableCommandHandler connection failed for adapter=" + adapter, e);
        }
    }

    private ProbeResult probeSourceUrl(String identifier) {
        try {
            return urlProbe.probe(new URI(identifier));
        } catch (URISyntaxException e) {
            return ProbeResult.failure(BundleKeys.ERROR_SOURCE_ENABLE_PROBE_FAILED, 0);
        }
    }

    private Optional<UserRow> lookupUser(String adapter, String contactId) {
        if (adapter == null || contactId == null) {
            return Optional.empty();
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_USER_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                UUID id = (UUID) rs.getObject("id");
                String resolvedContactId = rs.getString("contact_id");
                boolean isAdmin = rs.getBoolean("is_admin");
                return Optional.of(new UserRow(id, resolvedContactId, isAdmin));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "SourceEnableCommandHandler.lookupUser failed for adapter=" + adapter
                            + " contact_id=" + ContactIds.redact(contactId),
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
                        rs.getString("identifier"),
                        rs.getString("kind"),
                        rs.getString("status"),
                        rs.getTimestamp("deleted_at") == null
                                ? null
                                : rs.getTimestamp("deleted_at").toInstant()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "SourceEnableCommandHandler.lookupSource failed for id=" + sourceId, e);
        }
    }

    private LockedRow selectSourceForUpdate(Connection conn, UUID sourceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_SOURCE_FOR_UPDATE_SQL)) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new LockedRow(
                        rs.getString("display_name"),
                        rs.getString("kind"),
                        rs.getString("status"),
                        rs.getTimestamp("deleted_at") == null
                                ? null
                                : rs.getTimestamp("deleted_at").toInstant());
            }
        }
    }

    private void updateSourceReactivate(Connection conn, UUID sourceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_SOURCE_REACTIVATE_SQL)) {
            ps.setObject(1, sourceId);
            ps.executeUpdate();
        }
    }

    private void updateSourceRevive(Connection conn, UUID sourceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_SOURCE_REVIVE_SQL)) {
            ps.setObject(1, sourceId);
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
                .targetKind("source")
                .targetId(sourceId.toString())
                .requestId(requestId)
                .build();
        auditLogWriter.write(conn, row);
    }

    private static UUID parseSourceId(String rawText) {
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

    private static String contactIdOf(ScopeRef scope) {
        return scope instanceof ScopeRef.Dm dm ? dm.contactId() : null;
    }

    private record UserRow(UUID id, String contactId, boolean isAdmin) {}

    private record SourceRow(String displayName, String identifier, String kind,
                             String status, Instant deletedAt) {}

    private record LockedRow(String displayName, String kind, String status, Instant deletedAt) {}
}
