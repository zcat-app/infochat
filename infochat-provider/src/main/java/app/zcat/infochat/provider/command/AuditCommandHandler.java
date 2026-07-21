package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditVerb;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.core.util.JsonEscaper;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Bot-admin-only handler for {@code /audit}. Reads {@code audit_log_view}
 * (the V5 redacted view) so contact ids and secrets are never surfaced
 * in plaintext. Supports {@code --actor}, {@code --action}, and
 * {@code --page} filters.
 *
 * <p>Per the acceptance criteria, an unknown {@code --actor} returns the
 * same "no audit rows" reply as a known actor with no rows — no
 * existence-vs-no-rows distinction is exposed.
 */
@ApplicationScoped
public class AuditCommandHandler implements CommandHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AuditCommandHandler.class);

    private static final String SELECT_USER_SQL =
            "SELECT id, contact_id, is_admin FROM users WHERE adapter = ? AND contact_id = ?";

    // The /audit filter resolves over the FULL verb catalogue (AuditVerb),
    // not just application-writable verbs: a bot admin must be able to filter
    // for procedure-only rows such as APPROVE_QUARANTINE that the SECURITY
    // DEFINER procedures wrote (M1-361).
    private static final String ACCEPTED_ACTIONS = Arrays.stream(AuditVerb.values())
            .map(AuditVerb::name)
            .collect(Collectors.joining(", "));

    @Inject BundleLoader bundleLoader;
    @Inject DataSource dataSource;
    @Inject InboundContext inboundContext;
    @Inject AuditLogWriter auditLogWriter;

    // The /audit -w window cutoff is a decision-gate "now" (it gates which audit
    // rows the admin sees), so it reads the injected Clock to stay pinnable in
    // tests (engineering-rules §9 / M1-528). The reply/audit-write timestamps
    // below stay on Instant.now(): they render/record, they gate nothing.
    @Inject
    Clock clock = Clock.systemUTC();

    @Inject
    @ConfigProperty(name = "infochat.provider.audit.page-size", defaultValue = "20")
    int pageSize;

    @Override
    public String name() {
        return "audit";
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

        AuditArgs args = AuditArgs.parse(rawText);
        if (args == null) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_USAGE_MISSING_ARGUMENT, inboundContext.effectiveLanguage()),
                    "/audit [--actor X] [--action Y] [--page N]"));
        }

        if (args.action != null) {
            try {
                AuditVerb.valueOf(args.action.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return reply(scope, MessageFormat.format(
                        bundleLoader.get(BundleKeys.ERROR_AUDIT_UNKNOWN_ACTION, inboundContext.effectiveLanguage()),
                        args.action, ACCEPTED_ACTIONS));
            }
        }

        // Resolve --actor against (inbound_adapter, contact_id) to get actor_user_id.
        // Unknown actor returns empty results — same as known-with-no-rows.
        UUID actorFilterId = null;
        if (args.actor != null) {
            Optional<ActorRow> filterTarget = lookupActor(adapter, args.actor);
            actorFilterId = filterTarget.map(a -> a.id).orElse(
                    // Sentinel UUID that will never match — produces zero rows
                    // without exposing existence-vs-no-rows distinction.
                    UUID.fromString("00000000-0000-0000-0000-000000000000"));
        }

        ActorRow actor = actorOpt.get();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                StringBuilder detailsJson = new StringBuilder("{");
                detailsJson.append("\"actor\":").append(args.actor != null
                        ? "\"" + JsonEscaper.escape(args.actor) + "\"" : "null");
                detailsJson.append(",\"action\":").append(args.action != null
                        ? "\"" + JsonEscaper.escape(args.action) + "\"" : "null");
                detailsJson.append('}');
                RedactionHook.AuditRow auditRow = RedactionHook.AuditRow.builder()
                        .actorUserId(actor.id)
                        .actorContactId(actor.contactId)
                        .actorAdapter(adapter)
                        .action(AuditAction.AUDIT_READ)
                        .targetKind(TargetKind.SYSTEM)
                        .targetId("audit_log")
                        .requestId(UUID.randomUUID().toString())
                        .detailsJson(detailsJson.toString())
                        .build();
                auditLogWriter.write(conn, auditRow);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            SafeLog.error(LOG, "/audit audit write failed", e);
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL, inboundContext.effectiveLanguage()));
        }

        int page = args.page;

        try (Connection conn = dataSource.getConnection()) {
            // Build count + data queries with the same WHERE clause
            StringBuilder where = new StringBuilder();
            List<Object> params = new ArrayList<>();

            if (actorFilterId != null) {
                where.append(" WHERE actor_user_id = ?");
                params.add(actorFilterId);
            }
            if (args.action != null) {
                where.append(where.isEmpty() ? " WHERE " : " AND ");
                where.append("action = ?");
                params.add(args.action.toUpperCase(Locale.ROOT));
            }
            // -w window: always applied (default 24h per design 03 §/audit) and
            // AND-combined with the optional --actor/--action predicates above.
            // The cutoff reads the injected Clock — it gates which rows the admin
            // sees, a decision on "now" (engineering-rules §9 / M1-528).
            OffsetDateTime windowCutoff = OffsetDateTime.ofInstant(
                    clock.instant().minus(args.window), ZoneOffset.UTC);
            where.append(where.isEmpty() ? " WHERE " : " AND ");
            where.append("created_at >= ?");
            params.add(windowCutoff);

            long totalCount;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM audit_log_view" + where)) {
                bindParams(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    totalCount = rs.getLong(1);
                }
            }

            if (totalCount == 0) {
                return reply(scope, bundleLoader.get(BundleKeys.REPLY_AUDIT_EMPTY, inboundContext.effectiveLanguage()));
            }

            int totalPages = (int) Math.ceil((double) totalCount / pageSize);
            if (page > totalPages) {
                page = totalPages;
            }

            StringBuilder sb = new StringBuilder();
            sb.append(MessageFormat.format(bundleLoader.get(BundleKeys.REPLY_AUDIT_HEADER, inboundContext.effectiveLanguage()),
                    String.valueOf(Math.min(totalCount - (long) (page - 1) * pageSize, pageSize)),
                    String.valueOf(page),
                    String.valueOf(totalPages)));

            String dataSql = "SELECT created_at, action, actor_contact_id, target_kind, target_id "
                    + "FROM audit_log_view" + where
                    + " ORDER BY created_at DESC LIMIT ? OFFSET ?";

            List<Object> dataParams = new ArrayList<>(params);
            dataParams.add(pageSize);
            dataParams.add((page - 1) * pageSize);

            try (PreparedStatement ps = conn.prepareStatement(dataSql)) {
                bindParams(ps, dataParams);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        sb.append('\n');
                        sb.append(MessageFormat.format(
                                bundleLoader.get(BundleKeys.REPLY_AUDIT_LINE, inboundContext.effectiveLanguage()),
                                rs.getTimestamp("created_at").toInstant().toString(),
                                rs.getString("action"),
                                rs.getString("actor_contact_id") != null
                                        ? rs.getString("actor_contact_id") : "-",
                                rs.getString("target_kind") != null
                                        ? rs.getString("target_kind") : "-",
                                rs.getString("target_id") != null
                                        ? rs.getString("target_id") : "-"));
                    }
                }
            }
            return reply(scope, sb.toString());
        } catch (SQLException e) {
            SafeLog.error(LOG, "/audit query failed", e);
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INTERNAL, inboundContext.effectiveLanguage()));
        }
    }

    private static void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p instanceof UUID u) {
                ps.setObject(i + 1, u);
            } else if (p instanceof String s) {
                ps.setString(i + 1, s);
            } else if (p instanceof Integer n) {
                ps.setInt(i + 1, n);
            } else if (p instanceof OffsetDateTime odt) {
                ps.setObject(i + 1, odt);
            }
        }
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

    record AuditArgs(@Nullable String actor, @Nullable String action, Duration window, int page) {

        /**
         * Default {@code -w} window per docs/design/03-commands.md §/audit —
         * bare {@code /audit} lists the last 24h (D12, matching /summary's
         * idiom). Single named constant so the default is not duplicated
         * between the parser and the handler.
         */
        static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

        /** {@code -w <N><unit>} pattern; unit is one of {@code h}, {@code d}, {@code w} (D12). */
        private static final Pattern WINDOW_PATTERN = Pattern.compile("^([0-9]+)([hdw])$");

        // A malformed --page OR -w value returns null (the parse-failure marker) so the
        // handler renders ERROR_USAGE_MISSING_ARGUMENT, matching the convention in
        // BanArgs / AssetHandler rather than silently falling back (M1-343 for --page;
        // M1-528 extends the same convention to -w).
        static @Nullable AuditArgs parse(String rawText) {
            List<String> tokens = CommandTokenizer.tokenize(rawText);
            String actor = null;
            String action = null;
            Duration window = DEFAULT_WINDOW;
            int page = 1;
            int i = 1; // skip the command name token
            while (i < tokens.size()) {
                String tok = tokens.get(i);
                if (tok.equals("--actor") && i + 1 < tokens.size()) {
                    actor = tokens.get(i + 1);
                    i += 2;
                } else if (tok.startsWith("--actor=")) {
                    actor = tok.substring("--actor=".length());
                    i++;
                } else if (tok.equals("--action") && i + 1 < tokens.size()) {
                    action = tokens.get(i + 1);
                    i += 2;
                } else if (tok.startsWith("--action=")) {
                    action = tok.substring("--action=".length());
                    i++;
                } else if (tok.equals("-w")) {
                    if (i + 1 >= tokens.size()) {
                        return null;
                    }
                    Duration parsed = parseWindow(tokens.get(i + 1));
                    if (parsed == null) {
                        return null;
                    }
                    window = parsed;
                    i += 2;
                } else if (tok.equals("--page") && i + 1 < tokens.size()) {
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
            return new AuditArgs(actor, action, window, page);
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
