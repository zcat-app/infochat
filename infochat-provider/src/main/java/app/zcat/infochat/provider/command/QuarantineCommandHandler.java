package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.messaging.RateCapBucket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
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

    private static final String RATE_LIMIT_KEY = "error.quarantine.rate_limit";

    @Inject BundleLoader bundleLoader;
    @Inject DataSource dataSource;
    @Inject InboundContext inboundContext;
    @Inject RateCapBucket rateCapBucket;
    @Inject AuditLogWriter auditLogWriter;

    @Override
    public String name() {
        return "quarantine";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        if (scope instanceof ScopeRef.Group) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_COMMAND_DM_ONLY, inboundContext.effectiveLanguage()));
        }

        String adapter = inboundContext.adapterName();
        String callerContactId = contactIdOf(scope);

        Optional<ActorRow> actorOpt = lookupActor(adapter, callerContactId);
        if (actorOpt.isEmpty() || !actorOpt.get().isAdmin) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY, inboundContext.effectiveLanguage()));
        }
        ActorRow actor = actorOpt.get();

        String[] split = rawText.trim().split("\\s+", 3);
        String subcommand = split.length > 1 ? split[1].toLowerCase(Locale.ROOT) : "";
        String remainder = split.length > 2 ? split[2] : "";

        return switch (subcommand) {
            case "list" -> handleList(scope, actor, adapter, remainder);
            case "approve" -> handleApprove(scope, actor, adapter, remainder);
            case "reject" -> handleReject(scope, actor, adapter, remainder);
            default -> reply(scope, bundleLoader.get(BundleKeys.ERROR_QUARANTINE_UNKNOWN_SUBCOMMAND, inboundContext.effectiveLanguage()));
        };
    }

    private OutboundMessage handleList(ScopeRef scope, ActorRow actor,
                                       String adapter, String remainder) {
        ListArgs args = ListArgs.parse(remainder);
        if (args == null) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_USAGE_MISSING_ARGUMENT, inboundContext.effectiveLanguage()),
                    "/quarantine list [--all] [--page N]"));
        }
        String countSql = args.showAll ? COUNT_ALL_SQL : COUNT_PENDING_SQL;
        String dataSql = args.showAll ? LIST_ALL_SQL : LIST_PENDING_SQL;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT set_config('infochat.actor_id', ?, true)")) {
                    ps.setString(1, actor.id.toString());
                    ps.execute();
                }
                RedactionHook.AuditRow auditRow = RedactionHook.AuditRow.builder()
                        .actorUserId(actor.id)
                        .actorContactId(actor.contactId)
                        .actorAdapter(adapter)
                        .action(AuditAction.QUARANTINE_LIST)
                        .targetKind(TargetKind.QUARANTINE)
                        .targetId("list")
                        .requestId(UUID.randomUUID().toString())
                        .detailsJson("{\"show_all\":" + args.showAll + "}")
                        .build();
                auditLogWriter.write(conn, auditRow);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            LOG.errorf(e, "/quarantine list audit write failed");
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL, inboundContext.effectiveLanguage()));
        }

        try (Connection conn = dataSource.getConnection()) {
            long totalCount;
            try (PreparedStatement ps = conn.prepareStatement(countSql);
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                totalCount = rs.getLong(1);
            }

            if (totalCount == 0) {
                return reply(scope, bundleLoader.get(BundleKeys.REPLY_QUARANTINE_LIST_EMPTY, inboundContext.effectiveLanguage()));
            }

            int pageSize = 20;
            int totalPages = (int) Math.ceil((double) totalCount / pageSize);
            int page = Math.max(1, Math.min(args.page, totalPages));

            long rowsOnPage = Math.min(totalCount - (long) (page - 1) * pageSize, pageSize);
            StringBuilder sb = new StringBuilder();
            sb.append(MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_QUARANTINE_LIST_HEADER, inboundContext.effectiveLanguage()),
                    String.valueOf(rowsOnPage),
                    String.valueOf(page),
                    String.valueOf(totalPages)));

            try (PreparedStatement ps = conn.prepareStatement(dataSql + " LIMIT ? OFFSET ?")) {
                ps.setInt(1, pageSize);
                ps.setInt(2, (page - 1) * pageSize);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        sb.append('\n');
                        sb.append(MessageFormat.format(
                                bundleLoader.get(BundleKeys.REPLY_QUARANTINE_LIST_LINE, inboundContext.effectiveLanguage()),
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
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL, inboundContext.effectiveLanguage()));
        }
    }

    private OutboundMessage handleApprove(ScopeRef scope, ActorRow actor,
                                          String adapter, String remainder) {
        if (!rateCapBucket.tryAcquire("quarantine", actor.id.toString())) {
            return reply(scope, bundleLoader.get(RATE_LIMIT_KEY, inboundContext.effectiveLanguage()));
        }

        String idStr = remainder.trim().split("\\s+")[0];
        if (idStr.isEmpty()) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_QUARANTINE_MISSING_ID, inboundContext.effectiveLanguage()), "approve"));
        }

        UUID quarantineId;
        try {
            quarantineId = UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_QUARANTINE_INVALID_ID, inboundContext.effectiveLanguage()), idStr));
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT set_config('infochat.actor_id', ?, true)")) {
                ps.setString(1, actor.id.toString());
                ps.execute();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT approve_quarantine(?, ?)")) {
                ps.setObject(1, quarantineId);
                ps.setObject(2, actor.id);
                ps.execute();
            }
            conn.commit();
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_QUARANTINE_APPROVE_SUCCESS, inboundContext.effectiveLanguage()),
                    quarantineId.toString()));
        } catch (SQLException e) {
            return mapStoredProcError(scope, e, quarantineId);
        }
    }

    private OutboundMessage handleReject(ScopeRef scope, ActorRow actor,
                                         String adapter, String remainder) {
        if (!rateCapBucket.tryAcquire("quarantine", actor.id.toString())) {
            return reply(scope, bundleLoader.get(RATE_LIMIT_KEY, inboundContext.effectiveLanguage()));
        }

        String idStr = remainder.trim().split("\\s+")[0];
        if (idStr.isEmpty()) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_QUARANTINE_MISSING_ID, inboundContext.effectiveLanguage()), "reject"));
        }

        UUID quarantineId;
        try {
            quarantineId = UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_QUARANTINE_INVALID_ID, inboundContext.effectiveLanguage()), idStr));
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT set_config('infochat.actor_id', ?, true)")) {
                ps.setString(1, actor.id.toString());
                ps.execute();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT reject_quarantine(?, ?)")) {
                ps.setObject(1, quarantineId);
                ps.setObject(2, actor.id);
                ps.execute();
            }
            conn.commit();
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_QUARANTINE_REJECT_SUCCESS, inboundContext.effectiveLanguage()),
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
                    bundleLoader.get(BundleKeys.ERROR_QUARANTINE_NOT_FOUND, inboundContext.effectiveLanguage()),
                    quarantineId.toString()));
        }
        if (msg != null && msg.contains("expected PENDING or BENIGN_CLOSED")) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_QUARANTINE_INVALID_STATE, inboundContext.effectiveLanguage()),
                    quarantineId.toString()));
        }
        LOG.errorf(e, "/quarantine stored procedure failed for id=%s", quarantineId);
        return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL, inboundContext.effectiveLanguage()));
    }

    private Optional<ActorRow> lookupActor(String adapter, @Nullable String contactId) {
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

    private static @Nullable String contactIdOf(ScopeRef scope) {
        return scope instanceof ScopeRef.Dm dm ? dm.contactId() : null;
    }

    record ActorRow(UUID id, String contactId, boolean isAdmin) {}

    record ListArgs(boolean showAll, int page) {
        // A malformed --page value returns null (the parse-failure marker) so the
        // handler renders ERROR_USAGE_MISSING_ARGUMENT, matching the convention in
        // BanArgs / AssetHandler rather than silently falling back to page 1 (M1-343).
        static @Nullable ListArgs parse(String remainder) {
            boolean showAll = false;
            int page = 1;
            List<String> tokens = new ArrayList<>();
            for (String tok : remainder.trim().split("\\s+")) {
                if (!tok.isEmpty()) tokens.add(tok);
            }
            int i = 0;
            while (i < tokens.size()) {
                String tok = tokens.get(i).toLowerCase(Locale.ROOT);
                if ("--all".equals(tok)) {
                    showAll = true;
                    i++;
                } else if ("--page".equals(tok) && i + 1 < tokens.size()) {
                    try {
                        page = Math.max(1, Integer.parseInt(tokens.get(i + 1)));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                    i += 2;
                } else if (tok.startsWith("--page=")) {
                    try {
                        page = Math.max(1, Integer.parseInt(tok.substring("--page=".length())));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                    i++;
                } else {
                    i++;
                }
            }
            return new ListArgs(showAll, page);
        }
    }
}
