package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Bot-admin-only handler for {@code /quarantine list|approve|reject}.
 * Dispatches on the first whitespace-delimited token after the command
 * name, following the {@link InviteCommandHandler} subcommand pattern.
 *
 * <p>{@code approve} and {@code reject} call the SECURITY DEFINER
 * stored functions from V21. The functions atomically transition the
 * quarantine row, write the audit_log entry, and (for approve) fire
 * {@code NOTIFY new_post} so the Provider re-renders the restored body.
 * This handler never touches quarantine.original_html directly.
 */
@ApplicationScoped
public class QuarantineCommandHandler implements CommandHandler {

    private static final Logger LOG = Logger.getLogger(QuarantineCommandHandler.class);

    private static final String SELECT_USER_SQL =
            "SELECT id, contact_id, is_admin FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String LIST_PENDING_SQL =
            "SELECT id, post_uid, flagged_by, flagged_at, rule_id, status "
                    + "FROM quarantine_review_view "
                    + "WHERE status = 'PENDING' "
                    + "ORDER BY flagged_at DESC";

    private static final String LIST_ALL_SQL =
            "SELECT id, post_uid, flagged_by, flagged_at, rule_id, status "
                    + "FROM quarantine_review_view "
                    + "ORDER BY flagged_at DESC";

    private static final String COUNT_PENDING_SQL =
            "SELECT count(*) FROM quarantine_review_view WHERE status = 'PENDING'";

    private static final String COUNT_ALL_SQL =
            "SELECT count(*) FROM quarantine_review_view";

    @Inject BundleLoader bundleLoader;
    @Inject DataSource dataSource;
    @Inject InboundContext inboundContext;

    @Override
    public String name() {
        return "quarantine";
    }

    @Override
    public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
        if (scope instanceof ScopeRef.Group) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_COMMAND_DM_ONLY));
        }

        String adapter = inboundContext.adapterName();
        String callerContactId = contactIdOf(scope);

        Optional<ActorRow> actorOpt = lookupActor(adapter, callerContactId);
        if (actorOpt.isEmpty() || !actorOpt.get().isAdmin) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
        }
        ActorRow actor = actorOpt.get();

        String[] split = rawText.trim().split("\\s+", 3);
        String subcommand = split.length > 1 ? split[1].toLowerCase(Locale.ROOT) : "";
        String remainder = split.length > 2 ? split[2] : "";

        return switch (subcommand) {
            case "list" -> handleList(scope, remainder);
            case "approve" -> handleApprove(scope, actor, remainder);
            case "reject" -> handleReject(scope, actor, remainder);
            default -> reply(scope, bundleLoader.get(BundleKeys.ERROR_QUARANTINE_UNKNOWN_SUBCOMMAND));
        };
    }

    private OutboundMessage handleList(ScopeRef scope, String remainder) {
        boolean showAll = remainder.trim().equalsIgnoreCase("--all");
        String countSql = showAll ? COUNT_ALL_SQL : COUNT_PENDING_SQL;
        String dataSql = showAll ? LIST_ALL_SQL : LIST_PENDING_SQL;

        try (Connection conn = dataSource.getConnection()) {
            long totalCount;
            try (PreparedStatement ps = conn.prepareStatement(countSql);
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                totalCount = rs.getLong(1);
            }

            if (totalCount == 0) {
                return reply(scope, bundleLoader.get(BundleKeys.REPLY_QUARANTINE_LIST_EMPTY));
            }

            int pageSize = 20;
            int totalPages = (int) Math.ceil((double) totalCount / pageSize);
            int page = 1;

            StringBuilder sb = new StringBuilder();
            sb.append(MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_QUARANTINE_LIST_HEADER),
                    String.valueOf(Math.min(totalCount, pageSize)),
                    String.valueOf(page),
                    String.valueOf(totalPages)));

            try (PreparedStatement ps = conn.prepareStatement(dataSql + " LIMIT ? OFFSET ?")) {
                ps.setInt(1, pageSize);
                ps.setInt(2, (page - 1) * pageSize);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        sb.append('\n');
                        sb.append(MessageFormat.format(
                                bundleLoader.get(BundleKeys.REPLY_QUARANTINE_LIST_LINE),
                                rs.getObject("id", UUID.class).toString(),
                                rs.getString("post_uid"),
                                rs.getString("flagged_by"),
                                rs.getTimestamp("flagged_at").toInstant().toString(),
                                rs.getString("rule_id"),
                                rs.getString("status")));
                    }
                }
            }
            return reply(scope, sb.toString());
        } catch (SQLException e) {
            LOG.errorf(e, "/quarantine list failed");
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL));
        }
    }

    private OutboundMessage handleApprove(ScopeRef scope, ActorRow actor, String remainder) {
        String idStr = remainder.trim().split("\\s+")[0];
        if (idStr.isEmpty()) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_QUARANTINE_MISSING_ID), "approve"));
        }

        UUID quarantineId;
        try {
            quarantineId = UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_QUARANTINE_INVALID_ID), idStr));
        }

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT approve_quarantine(?, ?)")) {
                ps.setObject(1, quarantineId);
                ps.setObject(2, actor.id);
                ps.execute();
            }
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_QUARANTINE_APPROVE_SUCCESS),
                    quarantineId.toString()));
        } catch (SQLException e) {
            return mapStoredProcError(scope, e, quarantineId);
        }
    }

    private OutboundMessage handleReject(ScopeRef scope, ActorRow actor, String remainder) {
        String idStr = remainder.trim().split("\\s+")[0];
        if (idStr.isEmpty()) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_QUARANTINE_MISSING_ID), "reject"));
        }

        UUID quarantineId;
        try {
            quarantineId = UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_QUARANTINE_INVALID_ID), idStr));
        }

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT reject_quarantine(?, ?)")) {
                ps.setObject(1, quarantineId);
                ps.setObject(2, actor.id);
                ps.execute();
            }
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_QUARANTINE_REJECT_SUCCESS),
                    quarantineId.toString()));
        } catch (SQLException e) {
            return mapStoredProcError(scope, e, quarantineId);
        }
    }

    /**
     * Maps the stored procedure's RAISE EXCEPTION messages to user-visible
     * bundle replies. The two known shapes are "not found" and "expected
     * PENDING or BENIGN_CLOSED".
     */
    private OutboundMessage mapStoredProcError(ScopeRef scope, SQLException e, UUID quarantineId) {
        String msg = e.getMessage();
        if (msg != null && msg.contains("not found")) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_QUARANTINE_NOT_FOUND),
                    quarantineId.toString()));
        }
        if (msg != null && msg.contains("expected PENDING or BENIGN_CLOSED")) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_QUARANTINE_INVALID_STATE),
                    quarantineId.toString()));
        }
        LOG.errorf(e, "/quarantine stored procedure failed for id=%s", quarantineId);
        return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL));
    }

    private Optional<ActorRow> lookupActor(String adapter, String contactId) {
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
                return Optional.of(new ActorRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("contact_id"),
                        rs.getBoolean("is_admin")));
            }
        } catch (SQLException e) {
            LOG.errorf(e, "lookupActor failed for adapter=%s", adapter);
            return Optional.empty();
        }
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private static String contactIdOf(ScopeRef scope) {
        return scope instanceof ScopeRef.Dm dm ? dm.contactId() : null;
    }

    record ActorRow(UUID id, String contactId, boolean isAdmin) {}
}
