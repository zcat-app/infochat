package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.LlmRateCap;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.messaging.RateCapBucket;
import app.zcat.infochat.provider.render.DisplayHeadline;
import app.zcat.infochat.provider.translation.TranslationCache;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.time.Clock;
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

    // Visibility interlock (M1-730; redteam 2026-07-30, medium/INFO-LEAK):
    // saved_post is a frozen snapshot with no status column, so without this
    // predicate a post re-hidden to QUARANTINED after being saved keeps
    // rendering — and in group scope the reply is broadcast to every member.
    // A row is listed iff NO post row carries its uid (the post aged out of
    // retention — the D13/D33 TTL-bookmark case the snapshot exists for) OR
    // at least one does with status = 'READY'. That is the same rule
    // /summary, search and getPost apply, so an admin approve or a BENIGN
    // requeue makes the bookmark reappear with nothing destroyed, and a
    // multi-window duplicate uid behaves identically across surfaces.
    //
    // The probe reads EXISTENCE and STATUS only — no post content column
    // crosses into the reply, so the snapshot contract (content never
    // re-resolves against post) is untouched. Cost is bounded: post is
    // partitioned monthly by fetched_at with ~2 partitions live under the
    // 30-day retention, each carrying the local index its
    // UNIQUE(uid, fetched_at) constraint implies, so each probe is an index
    // seek over at most two partitions.
    private static final String VISIBILITY_INTERLOCK_SQL =
            " AND (NOT EXISTS (SELECT 1 FROM post p WHERE p.uid = saved_post.post_uid)"
                    + " OR EXISTS (SELECT 1 FROM post p WHERE p.uid = saved_post.post_uid"
                    + " AND p.status = 'READY'))";

    private static final String SELECT_COUNT_BASE_SQL =
            "SELECT COUNT(*) FROM saved_post WHERE user_id = ?" + VISIBILITY_INTERLOCK_SQL;

    // `body` is selected for the headline fallback only (M1-730): a save taken
    // from a titleless-by-design source snapshots the ingest
    // IngestTextNormalizer.UNTITLED_TITLE sentinel into `title`, so without the
    // body column the line has nothing to show but that storage placeholder.
    // Still a pure snapshot read of CONTENT — nothing the line renders
    // re-resolves against `post`; only the row's VISIBILITY consults it (the
    // interlock above).
    //
    // It is read through left() rather than bare because `saved_post.body` has
    // no write-boundary cap anywhere (unlike `title`, capped at ingest by
    // IngestTextNormalizer.TITLE_MAX_LENGTH): DisplayHeadline's own
    // BODY_SCAN_LIMIT guard runs only after the whole column has crossed JDBC,
    // which for a 20-row page means 20 unbounded columns materialised to reach
    // 20 bounded headlines. Bounding in SQL cannot change a headline: left()
    // counts code points while Java counts UTF-16 units, so the truncated
    // value always carries at least BODY_SCAN_LIMIT Java chars whenever the
    // full body would have, and the helper's own cut consumes no more than
    // that. (Redteam 2026-07-30, medium/DOS.)
    private static final String SELECT_ROWS_BASE_SQL =
            "SELECT post_uid, title, left(body, " + DisplayHeadline.BODY_SCAN_LIMIT + ") AS body, "
                    + "url, snapshot_tags, personal_tags, saved_at, source_language "
                    + "FROM saved_post WHERE user_id = ?" + VISIBILITY_INTERLOCK_SQL;

    // D47 group backstop (M1-755, redteam 2026-08-03): the active (not
    // soft-removed) groups row for the inbound (adapter, upstream_group_id)
    // — the same query the InboundRouter and SummaryCommandHandler run, so
    // the id the group LLM bucket keys on is the same groups.id the
    // router carries.
    private static final String SELECT_GROUP_ID_SQL =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ? "
                    + "AND removed_at IS NULL";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    // M1-675: the /saved reply echoes attacker-influenceable stored values
    // (the post title, its body since M1-730 promoted the body to the
    // headline, and the personal/snapshot tags) and, in an approved
    // group, is broadcast to every member. A stored value shaped like a
    // privileged command would put a syntactically valid admin line in
    // front of every reader, including any bot admin who copy-pastes it —
    // the deterministic-reply reflection class of M1-656 / M1-659. The
    // write-side slash reject in SaveCommandHandler stops NEW slash tags,
    // but the title and body are upstream-controlled (PostPersister stores
    // them with no slash gate) and pre-existing rows predate the reject, so
    // the group-visible echo is redacted here at render via the same
    // closed-list sanitizer the LLM-output surfaces use — for the headline
    // inside DisplayHeadline, for the tags at the interpolation below.
    @Inject
    LlmOutputSanitizer llmOutputSanitizer;

    // The /saved -w window cutoff in bindFilters is a decision-gate "now", so
    // it reads from the injected Clock to stay pinnable in tests (M1-454,
    // engineering-rules §9). bindFilters is therefore an instance method.
    // The relativeAge / reply timestamps below stay on Instant.now(): they
    // render/record, they gate nothing (§9 display/record exemption).
    @Inject
    Clock clock = Clock.systemUTC();

    // The display-hit translation leg (M1-755): the /saved render path is
    // user-global (D13), so the pipeline's cache partition rides on the
    // ACTOR (hit/saved/<actorUserId>/<effectiveLanguage>), not the calling
    // scope — entries are shared across the user's own scopes but never
    // across users. The snapshot column was frozen at /save time (V76),
    // so the source language never re-resolves against the live post.
    //
    // LLM-cost metering (redteam 2026-08-03, high/DOS refine): the leg
    // draws ONE per-user LlmRateCap token per invocation that actually
    // translates — the same per-user bucket as chat / on-demand /summary
    // / /retry (security.md §Rate limiting) — and a per-page translator
    // budget bounds the per-invocation generative count. Cache hits cost
    // no translator call, so the handler probes the REAL display-hit
    // keyspace before drawing from the budget: repeated renders converge
    // to fully-translated pages.
    @Inject
    TranslationPipeline translationPipeline;

    @Inject
    LlmRateCap llmRateCap;

    @Inject
    RateCapBucket rateCapBucket;

    @Inject
    TranslationCache translationCache;

    @Inject
    @ConfigProperty(name = "infochat.save.translation-max-per-page", defaultValue = "5")
    int translationMaxPerPage;

    @Override
    public String name() {
        return "saved";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        ParsedArgs args = parseArgs(rawText);
        String adapter = inboundContext.adapterName();
        String callerContactId = resolveContactId(scope);
        if (callerContactId == null) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_SAVED_EMPTY, inboundContext.effectiveLanguage()));
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
                return reply(scope, bundleLoader.get(BundleKeys.REPLY_SAVED_EMPTY, inboundContext.effectiveLanguage()));
            }

            long totalCount = countSaves(conn, userId, args);
            if (totalCount == 0) {
                return reply(scope, bundleLoader.get(BundleKeys.REPLY_SAVED_EMPTY, inboundContext.effectiveLanguage()));
            }

            List<Row> rows = selectSaves(conn, userId, args);
            if (rows.isEmpty()) {
                // Filter narrowed to zero rows (or page past the end) —
                // treat the same as an empty library so the surface
                // stays consistent.
                return reply(scope, bundleLoader.get(BundleKeys.REPLY_SAVED_EMPTY, inboundContext.effectiveLanguage()));
            }

            // D47 group backstop: when the display-hit leg may run in
            // group scope, resolve the group's DB id so the aggregate
            // group LLM bucket can be drawn alongside the per-user token.
            // En scopes skip the leg entirely, so the lookup is gated on
            // a non-en effective language.
            UUID groupId = null;
            if (scope instanceof ScopeRef.Group group
                    && !"en".equalsIgnoreCase(inboundContext.effectiveLanguage())) {
                groupId = resolveGroupId(conn, adapter, group.adapterGroupId());
            }

            return reply(scope, buildReply(rows, totalCount, args, userId, groupId));
        }
    }

    private @Nullable UUID lookupActor(Connection conn, String adapter, String contactId) throws SQLException {
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

    private void bindFilters(PreparedStatement ps, Connection conn, UUID userId,
                             ParsedArgs args) throws SQLException {
        int idx = 1;
        ps.setObject(idx++, userId);
        if (args.tag != null) {
            Array tagArray = conn.createArrayOf("TEXT", new String[] { args.tag });
            ps.setArray(idx++, tagArray);
        }
        if (args.window != null) {
            ps.setObject(idx++, OffsetDateTime.ofInstant(
                    clock.instant().minus(args.window), java.time.ZoneOffset.UTC));
        }
    }

    private static Row readRow(ResultSet rs) throws SQLException {
        Array snapshotArr = rs.getArray("snapshot_tags");
        Array personalArr = rs.getArray("personal_tags");
        Timestamp savedAtTs = rs.getTimestamp("saved_at");
        return new Row(
                rs.getString("post_uid"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("url"),
                snapshotArr == null ? List.of() : Arrays.asList((String[]) snapshotArr.getArray()),
                personalArr == null ? List.of() : Arrays.asList((String[]) personalArr.getArray()),
                savedAtTs.toInstant(),
                rs.getString("source_language"));
    }

    private @Nullable UUID resolveGroupId(Connection conn, String adapter, String upstreamGroupId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_GROUP_ID_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return (UUID) rs.getObject("id");
            }
        }
    }

    private String buildReply(List<Row> rows, long totalCount, ParsedArgs args, UUID userId,
                              @Nullable UUID groupId) {
        int totalPages = (int) Math.max(1L, (totalCount + PAGE_SIZE - 1) / PAGE_SIZE);
        // The filter echo ({4}) reflects the caller's own /saved <tag>
        // token, and only renders once it byte-matched a stored personal
        // tag — so a pre-existing slash-bearing row is the one way it can
        // carry a command. Sanitize the DISPLAY only; the raw args.tag
        // still drives the personal_tags @> ? match in bindFilters.
        String filterClause = args.tag == null
                ? ""
                : ", filter: " + llmOutputSanitizer.sanitize(args.tag);
        String header = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_SAVED_HEADER_GLOBAL, inboundContext.effectiveLanguage()),
                rows.size(),
                totalCount,
                args.page,
                totalPages,
                filterClause);

        String lineTemplate = bundleLoader.get(BundleKeys.REPLY_SAVED_LINE, inboundContext.effectiveLanguage());
        String noHeadlineTemplate = bundleLoader.get(
                BundleKeys.REPLY_SAVED_LINE_NO_HEADLINE, inboundContext.effectiveLanguage());
        StringBuilder body = new StringBuilder(header);
        String scopeLanguage = inboundContext.effectiveLanguage();
        // LLM-cost metering (M1-755, redteam 2026-08-03 high/DOS refine):
        // the display-hit leg draws ONE per-user LlmRateCap token per
        // invocation that actually makes a translator call (drawn on the
        // first cache-miss row); in group scope the D47 aggregate group
        // bucket is drawn alongside it. A rejected draw degrades the
        // page's cache-miss rows to untranslated (the listing itself
        // stays cheap and usable). The per-page translator budget then
        // bounds the invocation's generative count; rows beyond it
        // render untranslated, unbracketed (the M1-747 degraded-cluster
        // precedent).
        boolean llmTokenHeld = false;
        int translationBudget = translationMaxPerPage;
        for (Row row : rows) {
            String tagJoined = joinTags(row.personalTags, row.snapshotTags);
            // Both attacker-influenceable placeholders stay redacted, each by
            // its own call over its own field. The headline's sanitize now
            // lives inside DisplayHeadline (M1-730) — which selects title OR
            // body and sanitizes that ONE field, never the concatenation
            // (M1-697: a flag-bearing closed-list entry deletes the span from
            // command word to flag token, so a widened input would let a
            // command word in the title and a flag in the body erase
            // everything between them). The tags are a SECOND author field and
            // keep their own separate call here. sanitize() is a no-op on a
            // value with no closed-list token (byte-identical passthrough of a
            // legit-slash title like TCP/IP), and opens no DB connection
            // unless it actually redacts. The bot-authored uid and relative
            // age are not sanitized.
            String headline = DisplayHeadline.of(row.title, row.body, llmOutputSanitizer);
            if (!headline.isEmpty()
                    && !"en".equalsIgnoreCase(scopeLanguage)
                    && !row.sourceLanguage.equalsIgnoreCase(scopeLanguage)) {
                // Display-hit translation (M1-755): a no-op for en scopes,
                // same-language hits, and null source language — the
                // pipeline owns the decision, the controls (pre-bound →
                // flatten → sanitizer-2 → re-truncate → bracketed original
                // line) and the fallback. Input is the DisplayHeadline OUTPUT, so the
                // headline is capped before the translator call by
                // construction. The cache partition is per-USER
                // (hit/saved/<userId>/<effectiveLanguage>) — the list is
                // user-global (D13), so the user's own scopes share
                // entries, but no user ever sees another's.
                // Cache hits cost no translator call: the per-user token
                // and the D47 group bucket are drawn only on the first
                // row that will actually CALL the translator — a cache
                // miss — so an en-scope, all-no-op, or fully-converged
                // page never draws. A rejected per-user draw degrades the
                // page's cache-miss rows to untranslated; a rejected
                // group draw refunds the per-user token first (the
                // RetryCommandHandler pattern) and degrades the same.
                // Cached translations still render — they cost no
                // generative call.
                String displayHitKey = TranslationPipeline.displayHitCacheLanguage(
                        "saved", userId, scopeLanguage);
                boolean cacheHit = translationCache.get(headline, displayHitKey).isPresent();
                if (!cacheHit && !llmTokenHeld) {
                    llmTokenHeld = true;
                    if (!llmRateCap.tryAcquire(userId)) {
                        translationBudget = 0;
                    } else if (groupId != null && !rateCapBucket.tryAcquireGroupLlm(groupId)) {
                        llmRateCap.refund(userId);
                        translationBudget = 0;
                    }
                }
                // Cache hits cost no translator call: they render free.
                // A cache miss consumes one budget slot — the row that
                // spends the last slot still calls.
                if (cacheHit) {
                    headline = translationPipeline.runForDisplayHit(
                            headline, row.sourceLanguage, "saved", userId, scopeLanguage);
                } else if (translationBudget > 0) {
                    translationBudget--;
                    headline = translationPipeline.runForDisplayHit(
                            headline, row.sourceLanguage, "saved", userId, scopeLanguage);
                }
            }
            // An empty headline means the snapshot carries no renderable text
            // at all; DisplayHeadline's contract is that the caller drops the
            // token together with its separator, which needs the second
            // template — interpolating "" into lineTemplate would leave a
            // doubled separator where the headline was.
            String line = headline.isEmpty()
                    ? MessageFormat.format(noHeadlineTemplate,
                            row.postUid,
                            relativeAge(row.savedAt),
                            llmOutputSanitizer.sanitize(tagJoined))
                    : MessageFormat.format(lineTemplate,
                            row.postUid,
                            headline,
                            relativeAge(row.savedAt),
                            llmOutputSanitizer.sanitize(tagJoined));
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

    private static @Nullable Duration parseWindow(String raw) {
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

    record ParsedArgs(@Nullable String tag, @Nullable Duration window, int page) {}

    private record Row(
            String postUid,
            String title,
            // `saved_post.body` is nullable in the DDL (V15) — a save taken
            // from a body-less source snapshots NULL here.
            @Nullable String body,
            String url,
            List<String> snapshotTags,
            List<String> personalTags,
            Instant savedAt,
            // `saved_post.source_language` is NOT NULL DEFAULT 'en' (V76) —
            // the declared source language frozen at /save time.
            String sourceLanguage) {}
}
