package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.group.GroupRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /list-groups [--page N]} per
 * {@code docs/spec/commands.md} §Admin (bot admin) and decision D47.
 * Bot-admin only; reads every {@code groups} row regardless of
 * approval_status or removed_at (admin auditing view).
 *
 * <p>Privileged-read handler: writes one {@link AuditAction#LIST_GROUPS}
 * row per call AFTER the admin gate passes and BEFORE the enumeration
 * runs (so a probe-and-abandon — exception in the SELECT path — still
 * leaves the privileged-read intent recorded). Mirrors the
 * {@link ListSourcesCommandHandler} shape (admin gate → audit-on-intent
 * → parse {@code --page} → paginated query → render). Works in BOTH DM
 * and group scope per spec — the actor's contact id is resolved via
 * {@link InboundContext#senderContactId()}.</p>
 *
 * <p>Per-row projection (from {@link GroupRepository#listGroupsPage}):
 * group id, approval_status, activated_by (redacted contact id or
 * literal {@code -} when NULL), member count, timezone. Pre-V26 rows
 * have NULL {@code activated_by} (the migration is intentional, per
 * V26 comments); rendering shows {@code -} so the admin can still
 * see the row.</p>
 */
@ApplicationScoped
public class ListGroupsCommandHandler implements CommandHandler {

    private static final int PAGE_SIZE = 20;

    private static final String SELECT_USER_SQL =
            "SELECT id, is_admin FROM users WHERE adapter = ? AND contact_id = ?";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Inject
    GroupRepository groupRepository;

    @Inject
    AuditLogWriter auditLogWriter;

    @Override
    public String name() {
        return "list-groups";
    }

    @Override
    public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
        String adapter = inboundContext.adapterName();
        String callerContactId = inboundContext.senderContactId();

        if (adapter == null || callerContactId == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
        }

        Optional<UserRow> actorOpt = lookupActor(adapter, callerContactId);
        if (actorOpt.isEmpty() || !actorOpt.get().isAdmin) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
        }

        int page = parsePage(rawText);

        // Spec §Authorization model step 8: admin gate passed, log
        // intent BEFORE the deployment-wide SELECT. Audit-before-effect
        // (own short transaction) so a probe-and-abandon — handler
        // exception inside the SELECT path — still leaves the
        // privileged-read intent recorded. Mirrors
        // ListSourcesCommandHandler.writePrivilegedReadAuditRow.
        writePrivilegedReadAuditRow(actorOpt.get(), callerContactId, adapter, page);

        long totalRows = groupRepository.countAllGroups();
        if (totalRows == 0) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_LIST_GROUPS_EMPTY));
        }
        int totalPages = (int) Math.max(1L, (totalRows + PAGE_SIZE - 1) / PAGE_SIZE);

        List<GroupRepository.GroupListRow> rows = groupRepository.listGroupsPage(page, PAGE_SIZE);
        if (rows.isEmpty()) {
            // Page out of range — show empty page rather than a bogus
            // header counting zero rows on page N of M.
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_LIST_GROUPS_EMPTY));
        }

        StringBuilder body = new StringBuilder();
        body.append(MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_LIST_GROUPS_HEADER),
                rows.size(), page, totalPages));
        body.append('\n');
        String lineTemplate = bundleLoader.get(BundleKeys.REPLY_LIST_GROUPS_LINE);
        for (GroupRepository.GroupListRow row : rows) {
            String activatorDisplay = row.activatorContactId() == null
                    ? "-"
                    : ContactIds.redact(row.activatorContactId());
            body.append(MessageFormat.format(lineTemplate,
                    row.id(),
                    row.approvalStatus(),
                    activatorDisplay,
                    row.memberCount(),
                    row.timezone()));
            body.append('\n');
        }

        return reply(scope, body.toString().stripTrailing());
    }

    private void writePrivilegedReadAuditRow(UserRow actor, String callerContactId,
                                             String adapter, int page) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // target_kind is constrained by the V5 CHECK to the closed
                // set {user, group, source, post, invite, quarantine,
                // asset, memory, system}; "group" with the literal
                // target_id="all" is the project's sentinel for a
                // deployment-wide enumeration, mirroring LIST_SOURCES_ALL's
                // use of "source"/"all" for /list-sources --all.
                RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                        .actorUserId(actor.id)
                        .actorContactId(callerContactId)
                        .actorAdapter(adapter)
                        .action(AuditAction.LIST_GROUPS)
                        .targetKind("group")
                        .targetId("all")
                        .requestId(UUID.randomUUID().toString())
                        .detailsJson("{\"page\":" + page + "}")
                        .build();
                auditLogWriter.write(conn, row);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ListGroupsCommandHandler.writePrivilegedReadAuditRow failed for adapter="
                            + adapter + " contact_id=" + ContactIds.redact(callerContactId),
                    e);
        }
    }

    private Optional<UserRow> lookupActor(String adapter, String contactId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_USER_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                UUID id = (UUID) rs.getObject("id");
                boolean isAdmin = rs.getBoolean("is_admin");
                return Optional.of(new UserRow(id, isAdmin));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ListGroupsCommandHandler.lookupActor failed for adapter="
                            + adapter, e);
        }
    }

    /**
     * Parse {@code --page N} from the raw text. Accepts both
     * {@code --page N} and {@code --page=N}; missing or malformed
     * value defaults to page 1.
     */
    private static int parsePage(String rawText) {
        String[] tokens = rawText.trim().split("\\s+");
        for (int i = 1; i < tokens.length; i++) {
            String tok = tokens[i];
            if (tok.equals("--page")) {
                if (i + 1 < tokens.length) {
                    return parsePageValue(tokens[i + 1]);
                }
            } else if (tok.startsWith("--page=")) {
                return parsePageValue(tok.substring("--page=".length()));
            }
        }
        return 1;
    }

    private static int parsePageValue(String s) {
        try {
            int v = Integer.parseInt(s);
            return v < 1 ? 1 : v;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private record UserRow(UUID id, boolean isAdmin) {}
}
