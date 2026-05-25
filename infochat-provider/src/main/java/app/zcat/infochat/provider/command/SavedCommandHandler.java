package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implements {@code /saved [tag] [-w <window>] [--page N]} per
 * {@code docs/spec/commands.md} §Content + {@code docs/spec/schema.md}
 * §Per-user state (D13 — per-user-globally). Lists the actor's saved
 * posts; the reply header MUST disclose per-user-global semantics so a
 * user invoking the command in a group context is not surprised by
 * DM-only saves appearing.
 *
 * <p>Dispatch sequence:
 * <ol>
 *   <li>Parse {@code [tag] [-w <duration>] [--page N]}. All three are
 *       optional. Unknown flags fall back silently — the listing query
 *       is read-only and idempotent so a malformed flag just collapses
 *       to the default listing rather than a friendly-error catalogue.</li>
 *   <li>Actor lookup (no transaction; read-only). Missing actor →
 *       empty-library reply.</li>
 *   <li>SELECT with optional {@code personal_tags @> ARRAY[?]} filter
 *       and optional {@code saved_at > NOW() - INTERVAL} window;
 *       {@code ORDER BY saved_at DESC LIMIT 20 OFFSET (N-1)*20}. The
 *       query carries NO scope discriminator clause — per-user-globally
 *       per spec §Per-user state and the {@code saved_post} table's
 *       {@code (user_id, post_uid)} PRIMARY KEY shape.</li>
 *   <li>Build the reply: disclosure header + one line per row, OR
 *       empty-library reply.</li>
 * </ol>
 *
 * <p>Page size is fixed at 20 per
 * {@code docs/design/03-commands.md} §{@code /saved} — not user-tunable.</p>
 */
@ApplicationScoped
public class SavedCommandHandler implements CommandHandler {

    /** Page size per design 03 §`/saved`. */
    static final int PAGE_SIZE = 20;

    /** {@code -w <N><unit>} pattern; unit is one of {@code h}, {@code d}, {@code w}. */
    private static final Pattern WINDOW_PATTERN = Pattern.compile("^([0-9]+)([hdw])$");

    private static final String SELECT_ACTOR_SQL =
            "SELECT id FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String SELECT_COUNT_BASE_SQL =
            "SELECT COUNT(*) FROM saved_post WHERE user_id = ?";

    private static final String SELECT_ROWS_BASE_SQL =
            "SELECT post_uid, title, url, snapshot_tags, personal_tags, saved_at "
                    + "FROM saved_post WHERE user_id = ?";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Override
    public String name() {
        return "saved";
    }

    @Override
    public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
        ParsedArgs args = parseArgs(rawText);
        String adapter = inboundContext.adapterName();
        String callerContactId = resolveContactId(scope);
        if (callerContactId == null) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_SAVED_EMPTY));
        }

        try {
            return executeList(scope, adapter, callerContactId, args);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "SavedCommandHandler.executeList failed for adapter="
                            + adapter + " contact_id="
                            + ContactIds.redact(callerContactId), e);
        }
    }

    private OutboundMessage executeList(ScopeRef scope, String adapter,
                                        String callerContactId, ParsedArgs args) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            UUID userId = lookupActor(conn, adapter, callerContactId);
            if (userId == null) {
                return reply(scope, bundleLoader.get(BundleKeys.REPLY_SAVED_EMPTY));
            }

            long totalCount = countSaves(conn, userId, args);
            if (totalCount == 0) {
                return reply(scope, bundleLoader.get(BundleKeys.REPLY_SAVED_EMPTY));
            }

            List<Row> rows = selectSaves(conn, userId, args);
            if (rows.isEmpty()) {
                // Filter narrowed to zero rows (or page past the end) —
                // treat the same as an empty library so the surface
                // stays consistent.
                return reply(scope, bundleLoader.get(BundleKeys.REPLY_SAVED_EMPTY));
            }

            return reply(scope, buildReply(rows, totalCount, args));
        }
    }

    private UUID lookupActor(Connection conn, String adapter, String contactId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_ACTOR_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return (UUID) rs.getObject("id");
            }
        }
    }

    private long countSaves(Connection conn, UUID userId, ParsedArgs args) throws SQLException {
        StringBuilder sql = new StringBuilder(SELECT_COUNT_BASE_SQL);
        appendFilters(sql, args);
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindFilters(ps, conn, userId, args);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private List<Row> selectSaves(Connection conn, UUID userId, ParsedArgs args) throws SQLException {
        StringBuilder sql = new StringBuilder(SELECT_ROWS_BASE_SQL);
        appendFilters(sql, args);
        sql.append(" ORDER BY saved_at DESC LIMIT ").append(PAGE_SIZE);
        sql.append(" OFFSET ").append((args.page - 1) * PAGE_SIZE);
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindFilters(ps, conn, userId, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<Row> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(readRow(rs));
                }
                return out;
            }
        }
    }

    private static void appendFilters(StringBuilder sql, ParsedArgs args) {
        // The query carries NO scope discriminator clause — per-user-
        // globally per spec §Per-user state. The saved_post table has
        // no scope_kind / scope_id column (Invariant 1 carve-out).
        if (args.tag != null) {
            sql.append(" AND personal_tags @> ?");
        }
        if (args.window != null) {
            sql.append(" AND saved_at > ?");
        }
    }

    private static void bindFilters(PreparedStatement ps, Connection conn, UUID userId,
                                    ParsedArgs args) throws SQLException {
        int idx = 1;
        ps.setObject(idx++, userId);
        if (args.tag != null) {
            Array tagArray = conn.createArrayOf("TEXT", new String[] { args.tag });
            ps.setArray(idx++, tagArray);
        }
        if (args.window != null) {
            ps.setObject(idx++, OffsetDateTime.ofInstant(
                    Instant.now().minus(args.window), java.time.ZoneOffset.UTC));
        }
    }

    private static Row readRow(ResultSet rs) throws SQLException {
        Array snapshotArr = rs.getArray("snapshot_tags");
        Array personalArr = rs.getArray("personal_tags");
        Timestamp savedAtTs = rs.getTimestamp("saved_at");
        return new Row(
                rs.getString("post_uid"),
                rs.getString("title"),
                rs.getString("url"),
                snapshotArr == null ? List.of() : Arrays.asList((String[]) snapshotArr.getArray()),
                personalArr == null ? List.of() : Arrays.asList((String[]) personalArr.getArray()),
                savedAtTs.toInstant());
    }

    private String buildReply(List<Row> rows, long totalCount, ParsedArgs args) {
        int totalPages = (int) Math.max(1L, (totalCount + PAGE_SIZE - 1) / PAGE_SIZE);
        String filterClause = args.tag == null ? "" : ", filter: " + args.tag;
        String header = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_SAVED_HEADER_GLOBAL),
                rows.size(),
                totalCount,
                args.page,
                totalPages,
                filterClause);

        String lineTemplate = bundleLoader.get(BundleKeys.REPLY_SAVED_LINE);
        StringBuilder body = new StringBuilder(header);
        for (Row row : rows) {
            String tagJoined = joinTags(row.personalTags, row.snapshotTags);
            String line = MessageFormat.format(lineTemplate,
                    row.postUid,
                    row.title == null ? "" : row.title,
                    relativeAge(row.savedAt),
                    tagJoined);
            body.append('\n').append(line);
        }
        return body.toString();
    }

    private static String joinTags(List<String> personal, List<String> snapshot) {
        List<String> joined = new ArrayList<>(personal.size() + snapshot.size());
        joined.addAll(personal);
        for (String s : snapshot) {
            if (!joined.contains(s)) {
                joined.add(s);
            }
        }
        return String.join(", ", joined);
    }

    private static String relativeAge(Instant savedAt) {
        Duration age = Duration.between(savedAt, Instant.now());
        long days = age.toDays();
        if (days >= 1) {
            return days + "d ago";
        }
        long hours = age.toHours();
        if (hours >= 1) {
            return hours + "h ago";
        }
        long minutes = Math.max(0L, age.toMinutes());
        return minutes + "m ago";
    }

    private String resolveContactId(ScopeRef scope) {
        return scope instanceof ScopeRef.Dm dm
                ? dm.contactId()
                : inboundContext.senderContactId();
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    /**
     * Parse the inbound body for {@code /saved [tag] [-w <duration>] [--page N]}.
     * All three are optional. Malformed flags collapse to defaults
     * silently — listing is idempotent so a friendly-error catalogue
     * is not worth the surface complexity.
     */
    static ParsedArgs parseArgs(String rawText) {
        String[] split = rawText.trim().split("\\s+", 2);
        String remainder = split.length > 1 ? split[1].trim() : "";
        if (remainder.isEmpty()) {
            return new ParsedArgs(null, null, 1);
        }
        String[] tokens = remainder.split("\\s+");

        String tag = null;
        Duration window = null;
        int page = 1;

        int i = 0;
        while (i < tokens.length) {
            String token = tokens[i];
            if ("-w".equals(token) && i + 1 < tokens.length) {
                window = parseWindow(tokens[i + 1]);
                i += 2;
            } else if ("--page".equals(token) && i + 1 < tokens.length) {
                page = Math.max(1, parsePage(tokens[i + 1]));
                i += 2;
            } else if (token.startsWith("-")) {
                // Unknown flag — skip.
                i++;
            } else if (tag == null) {
                tag = token;
                i++;
            } else {
                // Extra positional token — ignore (no friendly-error
                // catalogue for /saved).
                i++;
            }
        }
        return new ParsedArgs(tag, window, page);
    }

    private static Duration parseWindow(String raw) {
        Matcher m = WINDOW_PATTERN.matcher(raw.toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            return null;
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "h" -> Duration.ofHours(n);
            case "d" -> Duration.ofDays(n);
            case "w" -> Duration.ofDays(n * 7);
            default -> null;
        };
    }

    private static int parsePage(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    record ParsedArgs(String tag, Duration window, int page) {}

    private record Row(
            String postUid,
            String title,
            String url,
            List<String> snapshotTags,
            List<String> personalTags,
            Instant savedAt) {}
}
