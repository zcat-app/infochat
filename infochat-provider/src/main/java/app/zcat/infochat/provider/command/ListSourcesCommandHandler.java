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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /list-sources [--all] [--include-deleted] [--page N]}
 * per {@code docs/spec/commands.md} §Source management + §Permission model
 * + {@code docs/spec/security.md} §Source URL visibility.
 *
 * <p>Flag-as-identity (spec §Permission model: "Admin-only flags are
 * part of command identity"): a non-admin caller passing {@code --all}
 * OR {@code --include-deleted} receives the admin-only-flag error; the
 * flag is NOT silently stripped. {@code --include-deleted} requires
 * {@code --all} even for an admin caller.</p>
 *
 * <p>The four happy paths:</p>
 * <ul>
 *   <li><b>(a) no flags, DM scope</b> — caller's own
 *       {@code source_subscription} rows
 *       ({@code scope_kind='dm' AND scope_id=actor.id}).</li>
 *   <li><b>(b) no flags, group scope</b> — the group's subscriptions
 *       (per spec D7, visible to every group member);
 *       {@code scope_kind='group' AND scope_id=groups.id} after
 *       mapping the adapter-local upstream group id.</li>
 *   <li><b>(c) {@code --all} (admin)</b> — every source row globally
 *       where {@code deleted_at IS NULL}, regardless of subscription.
 *       Reply header includes the URL-visibility caveat.</li>
 *   <li><b>(d) {@code --all --include-deleted} (admin)</b> — like (c)
 *       plus rows where {@code deleted_at IS NOT NULL} (soft-deleted
 *       sources flagged inline as {@code status=deleted}).</li>
 * </ul>
 *
 * <p>{@code --page N} is parsed (1-indexed, page size 20 per design
 * notes); paging is intentionally minimal in v1 (the acceptance
 * enumeration does not include a paging assertion). The handler
 * applies {@code LIMIT 20 OFFSET (N-1)*20}; a page beyond the result
 * set returns the empty reply.</p>
 *
 * <p>This is a read-only handler — no audit row, no state mutation.
 * Per Invariant 7, audit-on-intent fires only on confirm-gated
 * destructive admin actions; a list-only command writes no
 * {@code audit_log} row even on the admin {@code --all} path.</p>
 */
@ApplicationScoped
public class ListSourcesCommandHandler implements CommandHandler {

    private static final int PAGE_SIZE = 20;

    private static final String SELECT_GROUP_SQL =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ? "
                    + "AND removed_at IS NULL";

    private static final String SELECT_SCOPED_SOURCES_SQL =
            "SELECT s.display_name, s.identifier, s.kind, s.status, s.deleted_at "
                    + "FROM source s "
                    + "JOIN source_subscription ss ON ss.source_id = s.id "
                    + "WHERE ss.scope_kind = ? AND ss.scope_id = ? "
                    + "  AND s.deleted_at IS NULL "
                    + "ORDER BY s.display_name "
                    + "LIMIT ? OFFSET ?";

    private static final String SELECT_ALL_NON_DELETED_SOURCES_SQL =
            "SELECT display_name, identifier, kind, status, deleted_at "
                    + "FROM source WHERE deleted_at IS NULL "
                    + "ORDER BY display_name LIMIT ? OFFSET ?";

    private static final String SELECT_ALL_INCLUDING_DELETED_SOURCES_SQL =
            "SELECT display_name, identifier, kind, status, deleted_at "
                    + "FROM source "
                    + "ORDER BY display_name LIMIT ? OFFSET ?";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Inject
    AuditLogWriter auditLogWriter;

    @Inject
    UserRepository userRepository;

    @Override
    public String name() {
        return "list-sources";
    }

    @Override
    public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
        ListSourcesArgs args = ListSourcesArgs.parse(rawText);
        String adapter = inboundContext.adapterName();
        String callerContactId = contactIdOf(scope);

        // Admin-only flag-as-identity (spec §Permission model). Both
        // --all and --include-deleted are admin-only; the flag is NOT
        // silently stripped from a non-admin caller. In group scope
        // the SPI does not carry the actor's contact id (T2-F lands
        // the widening), so the admin lookup yields empty and the
        // rejection surfaces — which matches the spec intent
        // (admin verification is unavailable in v1 group scope, so
        // any --all in that scope is rejected).
        if (args.all || args.includeDeleted) {
            Optional<UserRow> actor = lookupUser(adapter, callerContactId);
            if (actor.isEmpty() || !actor.get().isAdmin) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_LIST_SOURCES_ADMIN_ONLY_FLAG));
            }
            // Admin verified. --include-deleted requires --all even
            // for an admin caller — a paired-flag constraint, not a
            // permission gate.
            if (args.includeDeleted && !args.all) {
                return reply(scope,
                        bundleLoader.get(BundleKeys.ERROR_LIST_SOURCES_INCLUDE_DELETED_REQUIRES_ALL));
            }
            // Spec §Authorization model step 8: admin gate passed, log
            // intent BEFORE step 9 (the deployment-wide SELECT). The
            // privileged --all enumeration is a §Source URL visibility
            // disclosure (every source URL across the deployment); the
            // audit row closes the gap between "destructive admin
            // writes audited" and "privileged admin reads not audited"
            // that the unprivileged DM/group form intentionally leaves
            // open (matching the established read-only-doesn't-audit
            // pattern, e.g. SummaryCommandHandler).
            writePrivilegedReadAuditRow(actor.get(), callerContactId, adapter, args.includeDeleted);
            return adminAllPath(scope, args.includeDeleted, args.page);
        }

        // No admin flags — caller-subscription path. Works in both DM
        // and group scope per spec §Source management.
        if (scope instanceof ScopeRef.Dm dm) {
            Optional<UserRow> actor = lookupUser(adapter, dm.contactId());
            if (actor.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.REPLY_LIST_SOURCES_EMPTY));
            }
            return scopedPath(scope, "dm", actor.get().id, args.page);
        }
        if (scope instanceof ScopeRef.Group group) {
            Optional<UUID> groupId = lookupGroupId(adapter, group.adapterGroupId());
            if (groupId.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.REPLY_LIST_SOURCES_EMPTY));
            }
            return scopedPath(scope, "group", groupId.get(), args.page);
        }
        return reply(scope, bundleLoader.get(BundleKeys.REPLY_LIST_SOURCES_EMPTY));
    }

    private OutboundMessage scopedPath(ScopeRef scope, String scopeKind, UUID scopeId, int page) {
        List<SourceRow> rows = selectScopedSources(scopeKind, scopeId, page);
        if (rows.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_LIST_SOURCES_EMPTY));
        }
        return reply(scope, renderReply(rows, /* withVisibilityCaveat */ false));
    }

    private OutboundMessage adminAllPath(ScopeRef scope, boolean includeDeleted, int page) {
        List<SourceRow> rows = selectAllSources(includeDeleted, page);
        if (rows.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_LIST_SOURCES_EMPTY));
        }
        return reply(scope, renderReply(rows, /* withVisibilityCaveat */ true));
    }

    private void writePrivilegedReadAuditRow(UserRow actor, @Nullable String callerContactId,
                                             String adapter, boolean includeDeleted) {
        // Audit-before-effect: write the row in its own short
        // transaction BEFORE adminAllPath issues the deployment-wide
        // SELECT, so a probe-and-abandon (handler exception in the
        // SELECT path) still leaves the privileged-read intent
        // recorded. One verb (LIST_SOURCES_ALL) covers both privileged
        // forms; the --include-deleted variant is encoded in
        // details_json so a single closed-set enum suffices.
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // target_kind is constrained by the V5 CHECK to the closed
                // set {user, group, source, post, invite, quarantine,
                // asset, memory, system}; "source" with the literal
                // target_id="all" is the project's sentinel for a
                // deployment-wide enumeration (the target_id column is
                // TEXT NOT NULL with no UUID format constraint).
                RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                        .actorUserId(actor.id)
                        .actorContactId(callerContactId)
                        .actorAdapter(adapter)
                        .action(AuditAction.LIST_SOURCES_ALL)
                        .targetKind("source")
                        .targetId("all")
                        .requestId(UUID.randomUUID().toString())
                        .detailsJson("{\"include_deleted\":" + includeDeleted + "}")
                        .build();
                auditLogWriter.write(conn, row);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ListSourcesCommandHandler.writePrivilegedReadAuditRow failed for adapter="
                            + adapter + " contact_id=" + ContactIds.redact(callerContactId),
                    e);
        }
    }

    private String renderReply(List<SourceRow> rows, boolean withVisibilityCaveat) {
        StringBuilder sb = new StringBuilder();
        sb.append(bundleLoader.get(BundleKeys.REPLY_LIST_SOURCES_HEADER));
        if (withVisibilityCaveat) {
            sb.append('\n');
            sb.append(bundleLoader.get(BundleKeys.REPLY_LIST_SOURCES_URL_VISIBILITY_CAVEAT));
        }
        for (SourceRow row : rows) {
            sb.append('\n');
            sb.append(MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_LIST_SOURCES_LINE),
                    row.displayName, row.identifier, row.kind, statusLabel(row)));
        }
        return sb.toString();
    }

    private static String statusLabel(SourceRow row) {
        // Soft-deleted rows surface as status='deleted' regardless of
        // the underlying status column (which the spec keeps active
        // post-soft-delete for the post-delete revival path). The
        // status column's three values (active|failed|disabled) flow
        // through verbatim for non-soft-deleted rows.
        if (row.deletedAt != null) {
            return "deleted";
        }
        return row.status;
    }

    private Optional<UserRow> lookupUser(String adapter, @Nullable String contactId) {
        if (adapter == null || contactId == null) {
            return Optional.empty();
        }
        return userRepository.findByAdapterAndContactId(adapter, contactId)
                .map(u -> new UserRow(u.id(), u.isAdmin()));
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
                    "ListSourcesCommandHandler.lookupGroupId failed for adapter=" + adapter, e);
        }
    }

    private List<SourceRow> selectScopedSources(String scopeKind, UUID scopeId, int page) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_SCOPED_SOURCES_SQL)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setInt(3, PAGE_SIZE);
            ps.setInt(4, (page - 1) * PAGE_SIZE);
            return collectRows(ps);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ListSourcesCommandHandler.selectScopedSources failed for scopeKind="
                            + scopeKind,
                    e);
        }
    }

    private List<SourceRow> selectAllSources(boolean includeDeleted, int page) {
        String sql = includeDeleted
                ? SELECT_ALL_INCLUDING_DELETED_SOURCES_SQL
                : SELECT_ALL_NON_DELETED_SOURCES_SQL;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, PAGE_SIZE);
            ps.setInt(2, (page - 1) * PAGE_SIZE);
            return collectRows(ps);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ListSourcesCommandHandler.selectAllSources failed (includeDeleted="
                            + includeDeleted + ")",
                    e);
        }
    }

    private static List<SourceRow> collectRows(PreparedStatement ps) throws SQLException {
        List<SourceRow> rows = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new SourceRow(
                        rs.getString("display_name"),
                        rs.getString("identifier"),
                        rs.getString("kind"),
                        rs.getString("status"),
                        rs.getTimestamp("deleted_at") == null
                                ? null
                                : rs.getTimestamp("deleted_at").toInstant()));
            }
        }
        return rows;
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private static @Nullable String contactIdOf(ScopeRef scope) {
        return scope instanceof ScopeRef.Dm dm ? dm.contactId() : null;
    }

    private record UserRow(UUID id, boolean isAdmin) {}

    private record SourceRow(String displayName, String identifier, String kind,
                             String status, @Nullable Instant deletedAt) {}

    /**
     * Parsed form of {@code /list-sources [--all] [--include-deleted] [--page N]}.
     * Token-based parser; unknown flags are ignored (no error surface
     * — the acceptance does not name one and the simplest robust
     * interpretation is "no-op on unknown flags").
     */
    record ListSourcesArgs(boolean all, boolean includeDeleted, int page) {

        static ListSourcesArgs parse(String rawText) {
            boolean all = false;
            boolean includeDeleted = false;
            int page = 1;
            String[] tokens = rawText.trim().split("\\s+");
            for (int i = 1; i < tokens.length; i++) {
                String tok = tokens[i];
                if (tok.equals("--all")) {
                    all = true;
                } else if (tok.equals("--include-deleted")) {
                    includeDeleted = true;
                } else if (tok.equals("--page")) {
                    if (i + 1 < tokens.length) {
                        page = parsePage(tokens[i + 1]);
                        i++;
                    }
                } else if (tok.startsWith("--page=")) {
                    page = parsePage(tok.substring("--page=".length()));
                }
            }
            return new ListSourcesArgs(all, includeDeleted, page);
        }

        private static int parsePage(String value) {
            try {
                int n = Integer.parseInt(value);
                return n >= 1 ? n : 1;
            } catch (NumberFormatException e) {
                return 1;
            }
        }
    }
}
