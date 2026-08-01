package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.messaging.RateCapBucket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Logger LOG = LoggerFactory.getLogger(QuarantineCommandHandler.class);

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

    // Forensic --all view bounded by the -w window. The window is valid ONLY on
    // --all (never the default PENDING queue — D53 / M1-528); flagged_at is the
    // forensic timestamp the cutoff filters on.
    private static final String LIST_ALL_WINDOWED_SQL =
            "SELECT id, post_uid, flagged_by, flagged_at, rule_id, status "
                    + "FROM quarantine_review_view "
                    + "WHERE flagged_at >= ? "
                    + "ORDER BY flagged_at DESC";

    private static final String COUNT_PENDING_SQL =
            "SELECT count(*) FROM quarantine_review_view WHERE status = 'PENDING'";

    private static final String COUNT_ALL_SQL =
            "SELECT count(*) FROM quarantine_review_view";

    private static final String COUNT_ALL_WINDOWED_SQL =
            "SELECT count(*) FROM quarantine_review_view WHERE flagged_at >= ?";

    // Read via quarantine_review_view, not the raw quarantine table: the
    // infochat_provider role has NO grant on quarantine (the original_html
    // confidentiality boundary, V10) — only SELECT on this view, which omits
    // original_html and exposes status. Same view handleList reads.
    private static final String SELECT_QUARANTINE_STATUS_SQL =
            "SELECT status FROM quarantine_review_view WHERE id = ?";

    @Inject BundleLoader bundleLoader;
    @Inject DataSource dataSource;
    @Inject InboundContext inboundContext;
    @Inject RateCapBucket rateCapBucket;
    @Inject AuditLogWriter auditLogWriter;
    @Inject ConfirmStateService confirmStateService;

    // The /quarantine list -w window cutoff is a decision-gate "now" (it gates
    // which forensic rows the admin sees), so it reads the injected Clock to stay
    // pinnable in tests (engineering-rules §9 / M1-528).
    @Inject
    Clock clock = Clock.systemUTC();

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
        // -w is forensic-only: a window over the PENDING review queue would hide
        // stale-but-unreviewed items, breaking the never-drop-unreviewed
        // invariant (D53 / M1-528). Reject -w without --all at the boundary; the
        // default PENDING queue is NEVER windowed.
        if (args.window != null && !args.showAll) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_QUARANTINE_WINDOW_REQUIRES_ALL, inboundContext.effectiveLanguage()));
        }

        // windowCutoff is non-null ONLY on the forensic --all-with-window path;
        // its nullness drives both SQL selection and parameter binding below
        // (args.window is dereferenced here, inside its own null check, so the
        // contract is visible to NullAway).
        String countSql;
        String dataSql;
        OffsetDateTime windowCutoff = null;
        if (!args.showAll) {
            countSql = COUNT_PENDING_SQL;
            dataSql = LIST_PENDING_SQL;
        } else if (args.window != null) {
            countSql = COUNT_ALL_WINDOWED_SQL;
            dataSql = LIST_ALL_WINDOWED_SQL;
            windowCutoff = OffsetDateTime.ofInstant(clock.instant().minus(args.window), ZoneOffset.UTC);
        } else {
            countSql = COUNT_ALL_SQL;
            dataSql = LIST_ALL_SQL;
        }

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
            SafeLog.error(LOG, "/quarantine list audit write failed", e);
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL, inboundContext.effectiveLanguage()));
        }

        try (Connection conn = dataSource.getConnection()) {
            long totalCount;
            try (PreparedStatement ps = conn.prepareStatement(countSql)) {
                if (windowCutoff != null) {
                    ps.setObject(1, windowCutoff);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    totalCount = rs.getLong(1);
                }
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
                int idx = 1;
                if (windowCutoff != null) {
                    ps.setObject(idx++, windowCutoff);
                }
                ps.setInt(idx++, pageSize);
                ps.setInt(idx, (page - 1) * pageSize);
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
            SafeLog.error(LOG, "/quarantine list failed", e);
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL, inboundContext.effectiveLanguage()));
        }
    }

    private OutboundMessage handleApprove(ScopeRef scope, ActorRow actor,
                                          String adapter, String remainder) {
        if (!rateCapBucket.tryAcquire("quarantine", actor.id.toString())) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_QUARANTINE_RATE_LIMIT, inboundContext.effectiveLanguage()));
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
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_QUARANTINE_RATE_LIMIT, inboundContext.effectiveLanguage()));
        }

        // Confirm leg of the forensic (BENIGN_CLOSED) path. The pending is
        // keyed by (actor, scope) and its remembered quarantine id is
        // authoritative, so the retyped body id is NOT re-parsed here
        // (mirrors SourceEnableCommandHandler). Both the canonical
        // `/quarantine reject confirm` and the args-retyped
        // `/quarantine reject <id> confirm` forms reach here — the router's
        // step 4.5 sweep recognizes both against sweepPrefix "quarantine
        // reject", so the trimmed remainder is either "confirm" or
        // "<id> confirm". (M1-458)
        String trimmed = remainder.trim();
        if (trimmed.equals("confirm") || trimmed.endsWith(" confirm")) {
            Optional<ConfirmStateService.PendingConfirm> taken =
                    confirmStateService.takeMatching(actor.id, scope, "quarantine-reject");
            if (taken.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_CONFIRM_NO_PENDING, inboundContext.effectiveLanguage()));
            }
            QuarantineRejectConfirm pending = (QuarantineRejectConfirm) taken.get();
            return executeReject(scope, actor, pending.quarantineId());
        }

        String idStr = trimmed.split("\\s+")[0];
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

        // State-dependent confirm gate (M1-458): the forensic
        // BENIGN_CLOSED -> REJECTED override (re-hiding a post the system
        // already cleared, with no bot command to undo it) is a lasting,
        // surprising admin action and is confirm-gated; the routine PENDING
        // reject is the expected review decision and stays direct. Read the
        // row's status to pick the path. The proc re-checks status under
        // FOR UPDATE, so a status change between this read and executeReject
        // surfaces as the same invalid-state error.
        String status = lookupQuarantineStatus(quarantineId);
        if (status == null) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_QUARANTINE_NOT_FOUND, inboundContext.effectiveLanguage()),
                    quarantineId.toString()));
        }
        return switch (status) {
            case "BENIGN_CLOSED" -> promptReject(scope, actor, adapter, quarantineId);
            case "PENDING" -> executeReject(scope, actor, quarantineId);
            // APPROVED / REJECTED are terminal — surface the same
            // invalid-state message the stored-proc error mapping would,
            // just earlier (the proc would otherwise raise it).
            default -> reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_QUARANTINE_INVALID_STATE, inboundContext.effectiveLanguage()),
                    quarantineId.toString()));
        };
    }

    /**
     * Forensic-path first call: write the audit-on-intent
     * {@link AuditAction#QUARANTINE_REJECT_INTENT} row BEFORE registering
     * the pending and prompting, so a probe-and-abandon still leaves a
     * trace (M1-051 rationale). Single auto-committed INSERT — the audit
     * write does not need {@code infochat.actor_id} (that GUC gates the
     * users last-admin trigger, not the audit_log insert). Does NOT call
     * {@code reject_quarantine}.
     */
    private OutboundMessage promptReject(ScopeRef scope, ActorRow actor,
                                         String adapter, UUID quarantineId) {
        try (Connection conn = dataSource.getConnection()) {
            RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                    .actorUserId(actor.id)
                    .actorContactId(actor.contactId)
                    .actorAdapter(adapter)
                    .action(AuditAction.QUARANTINE_REJECT_INTENT)
                    .targetKind(TargetKind.QUARANTINE)
                    .targetId(quarantineId.toString())
                    .requestId(UUID.randomUUID().toString())
                    .build();
            auditLogWriter.write(conn, row);
        } catch (SQLException e) {
            SafeLog.error(LOG, "/quarantine reject intent audit failed for id=" + quarantineId, e);
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL, inboundContext.effectiveLanguage()));
        }
        confirmStateService.remember(actor.id, scope, new QuarantineRejectConfirm(quarantineId));
        String prompt = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_CONFIRM_PROMPT_QUARANTINE_REJECT, inboundContext.effectiveLanguage()),
                Long.toString(confirmStateService.timeoutSeconds()));
        return reply(scope, prompt);
    }

    /**
     * Execute the reject: the {@code reject_quarantine} SECURITY DEFINER
     * procedure transitions the row to REJECTED and writes the in-proc
     * {@code REJECT_QUARANTINE} audit row. Used by the routine PENDING path
     * directly and by the forensic path after confirm.
     */
    private OutboundMessage executeReject(ScopeRef scope, ActorRow actor, UUID quarantineId) {
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

    private @Nullable String lookupQuarantineStatus(UUID quarantineId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_QUARANTINE_STATUS_SQL)) {
            ps.setObject(1, quarantineId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString("status");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "QuarantineCommandHandler.lookupQuarantineStatus failed for id=" + quarantineId, e);
        }
    }

    /**
     * Maps the stored procedure's RAISE EXCEPTION messages to user-visible
     * bundle replies. The three known shapes are "not found", "expected
     * PENDING or BENIGN_CLOSED", and "stage 2 verdict still owed" (M1-741).
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
        if (msg != null && msg.contains("stage 2 verdict still owed")) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_QUARANTINE_VERDICT_OWED, inboundContext.effectiveLanguage()),
                    quarantineId.toString()));
        }
        SafeLog.error(LOG, "/quarantine stored procedure failed for id=" + quarantineId, e);
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
            SafeLog.error(LOG, "lookupActor failed for adapter=" + adapter, e);
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

    record ListArgs(boolean showAll, @Nullable Duration window, int page) {

        /** {@code -w <N><unit>} pattern; unit is one of {@code h}, {@code d}, {@code w} (D12). */
        private static final Pattern WINDOW_PATTERN = Pattern.compile("^([0-9]+)([hdw])$");

        // A malformed --page OR -w value returns null (the parse-failure marker) so the
        // handler renders ERROR_USAGE_MISSING_ARGUMENT, matching the convention in
        // BanArgs / AssetHandler rather than silently falling back to page 1 (M1-343;
        // -w added M1-528). The -w-requires-all SEMANTIC check lives in the handler,
        // not here — a syntactically valid window without --all is a boundary error,
        // not a parse failure.
        static @Nullable ListArgs parse(String remainder) {
            boolean showAll = false;
            Duration window = null;
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
                } else if ("-w".equals(tok)) {
                    if (i + 1 >= tokens.size()) {
                        return null;
                    }
                    Duration parsed = parseWindow(tokens.get(i + 1));
                    if (parsed == null) {
                        return null;
                    }
                    window = parsed;
                    i += 2;
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
            return new ListArgs(showAll, window, page);
        }

        private static @Nullable Duration parseWindow(String raw) {
            Matcher m = WINDOW_PATTERN.matcher(raw.toLowerCase(Locale.ROOT));
            if (!m.matches()) {
                return null;
            }
            long n;
            try {
                n = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
            String unit = m.group(2);
            // Bound the magnitude to the documented accepted ranges (design 03
            // §Time window flag: 1–168h / 1–30d / 1–4w, mirroring SummaryArgs). An
            // unbounded value would overflow Duration.ofDays (uncaught
            // ArithmeticException) or let n*7 silently wrap to a negative window —
            // a future cutoff that returns an empty view (M1-528 redteam finding).
            if (!withinRange(unit, n)) {
                return null;
            }
            return switch (unit) {
                case "h" -> Duration.ofHours(n);
                case "d" -> Duration.ofDays(n);
                case "w" -> Duration.ofDays(n * 7);
                default -> null;
            };
        }

        private static boolean withinRange(String unit, long n) {
            return switch (unit) {
                case "h" -> n >= 1 && n <= 168;
                case "d" -> n >= 1 && n <= 30;
                case "w" -> n >= 1 && n <= 4;
                default -> false;
            };
        }
    }
}
