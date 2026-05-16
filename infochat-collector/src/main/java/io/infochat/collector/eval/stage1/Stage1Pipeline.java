package io.infochat.collector.eval.stage1;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.text.StringEscapeUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;

/**
 * Stage 1 of the eval pipeline: deterministic security sanitization
 * on every upstream feed body. Per
 * {@code docs/design/04-security.md} §4.2:
 *
 * <ol>
 *   <li><b>HTML entity pre-decode</b> — a single
 *       {@link StringEscapeUtils#unescapeHtml4} pass decodes numeric
 *       (decimal {@code &#NNN;}, hex {@code &#xNN;}, zero-padded
 *       {@code &#0NN;}) and named ({@code &amp;}, {@code &lt;}, …)
 *       HTML entities to their literal codepoints BEFORE Unicode
 *       normalization and the regex set. Without this step, a payload
 *       like {@code &#105;gnore previous instructions} reaches the
 *       regex set as the literal byte sequence {@code &#105;gnore...}
 *       (no {@code ignore} substring; no match); the downstream OWASP
 *       sanitizer then decodes the entity as part of HTML parsing,
 *       producing decoded prompt-injection text in {@code post.body}
 *       that the regex never saw. The pre-decode closes that bypass
 *       by ensuring the regex sees the same decoded form OWASP would
 *       eventually emit. See {@code docs/plan/m1/redteam/M1-032-2026-05-16.md}
 *       Finding 1 for the documented attack vector.</li>
 *   <li><b>Unicode normalize unconditionally</b> — NFKC over the
 *       entire body; bidi-control strip (U+202A..U+202E,
 *       U+2066..U+2069); zero-width strip
 *       (U+200B/U+200C/U+200D/U+FEFF). The Provider chat-intake
 *       carve-out ({@code docs/spec/security.md} §Ingest pipeline
 *       parenthetical) does NOT apply on the ingest path; the
 *       whole body is normalized.</li>
 *   <li><b>Prompt-injection regex set under wall-clock watchdog</b>
 *       — the seven patterns in {@link Stage1RegexSet} run with a
 *       per-input deadline; on overrun, the post is quarantined
 *       (fail-closed per {@code docs/spec/security.md}
 *       §Failure handling).</li>
 *   <li><b>Per-match record + redact</b> — INSERT one
 *       {@code quarantine} row per regex hit; replace the matched
 *       span in the body with {@code [REDACTED:<id>]}.</li>
 *   <li><b>OWASP allowlist HTML sanitize</b> on the redacted body
 *       — strip {@code script}, {@code style}, {@code iframe},
 *       {@code object}, {@code form}, {@code on*} event attributes,
 *       and {@code javascript:} / {@code data:} / {@code file:} URL
 *       schemes. Per §4.2's "Unicode-first, OWASP-last" rationale,
 *       OWASP runs last because its default renderer HTML-entity-
 *       encodes non-ASCII codepoints; running OWASP first would
 *       defeat the upstream NFKC pass on Unicode-obfuscated
 *       payloads. The {@code [REDACTED:[A-Z2-7]{26}]} marker is
 *       pure ASCII non-HTML-significant so OWASP leaves it
 *       intact. The call is wrapped in a try/catch over
 *       {@link RuntimeException}; on failure the pipeline takes the
 *       sanitizer-exception fail-closed branch
 *       ({@link #handleSanitizerException}) which writes
 *       {@code post.status='QUARANTINED'} and a whole-body
 *       quarantine row with {@code rule_id='sanitizer_exception'}
 *       per {@code docs/spec/security.md} §Failure handling
 *       ("HTML sanitizer exception → fail-closed").</li>
 *   <li><b>UPDATE post</b> — set {@code stage1_done = true},
 *       {@code stage1_flagged = (any match)}, body to the
 *       OWASP-sanitized form. {@code post.status} stays
 *       {@code 'RAW'}; the downstream M1-033 / M1-034 workers
 *       advance it from there. The ONLY exceptions are the
 *       watchdog and sanitizer-exception fail-closed paths, which
 *       UPDATE {@code post.status='QUARANTINED'} directly.</li>
 * </ol>
 *
 * <h2>Step order — load-bearing, do not reorder</h2>
 * <p>The "entity-decode → Unicode-first → OWASP-last" sequence is a
 * correctness commitment, not a stylistic preference. A future
 * refactor that moves OWASP earlier (e.g. "sanitize input first,
 * then process") silently defeats two defenses; a refactor that
 * drops the entity pre-decode reopens the entity-bypass vector
 * documented above.
 *
 * <ul>
 *   <li><b>OWASP HTML-entity-encodes non-ASCII codepoints.</b> The
 *       library's {@code HtmlStreamRenderer} encodes every char ≥
 *       U+0080 as a numeric entity (e.g. {@code ｉ} → {@code &#65353;}).
 *       If OWASP runs before NFKC, the normalizer receives entity-
 *       reference text and cannot decompose the original codepoints.
 *       A Unicode-obfuscated injection ({@code ｉｇｎｏｒｅ previous
 *       instructions}, fullwidth Latin) survives the pipeline
 *       undetected — the exact threat NFKC is supposed to neutralize.</li>
 *   <li><b>OWASP mangles {@code <<<UNTRUSTED>>>} markers.</b> The
 *       library parses these as malformed HTML and strips the
 *       {@code UNTRUSTED} token while escaping the surrounding
 *       brackets. If OWASP runs before the regex set, the
 *       delimiter-injection pattern never sees the marker shape
 *       it's designed to catch (per the M1-032 pre-existing-
 *       {@code <<<UNTRUSTED>>>} acceptance scenario).</li>
 * </ul>
 *
 * <p>Running entity-decode FIRST and OWASP LAST on the placeholder-
 * redacted body preserves all four guarantees: entity-encoded
 * injections are exposed to the regex; HTML is sanitized; Unicode
 * is normalized while NFKC can still see the original codepoints;
 * delimiter injection is detected before OWASP gets a chance to
 * mangle the marker shape. The
 * {@code [REDACTED:[A-Z2-7]{26}]} placeholder is pure ASCII
 * non-HTML-significant so OWASP leaves it intact. See
 * {@code docs/design/04-security.md} §4.2 for the same rationale
 * at the design-tier.
 *
 * <h2>Stage 1 never blocks release on its own matches</h2>
 * <p>Per {@code docs/spec/security.md} §Ingest pipeline: "Stage 1
 * never blocks release on its own — it scrubs and routes to review."
 * A regex match scrubs the body and records a quarantine row but
 * leaves {@code post.status='RAW'} so Stage 2 can judge. The only
 * fail-closed path here is the watchdog abort (Stage 1 INFRASTRUCTURE
 * failure), which writes {@code QUARANTINED} directly because the
 * matcher did not finish and a partial redaction is not safe.
 *
 * <h2>Watchdog shape</h2>
 * <p>The per-input wall-clock cap is enforced via an interruptible
 * {@link CharSequence} wrapper ({@link InterruptibleCharSequence})
 * whose {@code charAt} throws {@link RegexInterruptedException} after
 * the deadline. {@link Matcher#find()} calls {@code charAt} per
 * character iteration; the exception unwinds the matcher cleanly.
 * The {@code Future + Future.get(timeout)} alternative is documented
 * in the ticket's Implementation notes but rejected here: it spawns
 * a worker thread per Stage 1 call without offering more accurate
 * timing on JDK 25's virtual-thread runtime (project_quarkus_jdk25
 * memory). The cap value is read from the property
 * {@code infochat.security.stage1.regex-timeout-ms} whose default
 * lives in {@code application.properties} per the M1-028 precedent
 * (no inline {@code defaultValue}).
 *
 * <h2>Stage-2 hand-off</h2>
 * <p>{@link Stage1Result} carries BOTH {@code originalBody} (the
 * post-normalize / pre-redact body) and {@code redactedBody} (with
 * {@code [REDACTED:<id>]} placeholders). M1-033's Stage 2 judge
 * sees the ORIGINAL per
 * {@code docs/spec/security.md} §Ingest pipeline ("The judge sees
 * the original (pre-redaction) content"). The result record is the
 * hand-off shape; M1-033 invokes Stage 1 in-process and reads
 * {@code result.originalBody()}.
 *
 * <h2>NULL body at the DB boundary</h2>
 * <p>The {@code post.body} column is nullable per V7. The SPI
 * contract ({@code NormalizedPost.body}) is "never null in
 * production", but seeds in preserved tests (OutboxRehydratorIT
 * inserts post rows directly via JDBC with {@code body=NULL}) can
 * reach Stage 1. Null is treated as an empty body — a system-
 * boundary coercion (SQL deserialization), not internal-code
 * defensive code per CLAUDE.md §"No defensive code".
 */
@ApplicationScoped
public class Stage1Pipeline {

    private static final Logger LOG = Logger.getLogger(Stage1Pipeline.class);

    /** Canonical error_class for the throttled admin notifier (T2-G). */
    public static final String ERROR_CLASS_REGEX_TIMEOUT = "stage1.regex_timeout";

    /**
     * Canonical error_class for an OWASP sanitizer exception. The
     * future throttled admin notifier (T2-G) coalesces on
     * {@code (channel, error_class)} per
     * {@code docs/spec/security.md} §Failure handling.
     */
    public static final String ERROR_CLASS_SANITIZER_EXCEPTION = "stage1.sanitizer_exception";

    /** Rule id used on the watchdog-abort whole-body quarantine row. */
    public static final String REGEX_TIMEOUT_RULE_ID = "regex_timeout";

    /**
     * Rule id used on the sanitizer-exception fail-closed whole-body
     * quarantine row. Parallel shape to {@link #REGEX_TIMEOUT_RULE_ID};
     * downstream admin triage scripts correlate Stage 1 infrastructure
     * failures by this rule_id.
     */
    public static final String SANITIZER_EXCEPTION_RULE_ID = "sanitizer_exception";

    /**
     * OWASP allowlist policy — the §4.2 step 1 whitelist set.
     * Sanitizers.FORMATTING covers {@code p, br, strong, em, b, i, u};
     * Sanitizers.BLOCKS covers {@code blockquote, h1-h6, ul, ol, li,
     * pre, code}; Sanitizers.LINKS covers anchors with http/https
     * hrefs only (javascript:/data:/file: schemes are stripped by
     * the LINKS sanitizer's URL filter). The composition strips
     * {@code script}, {@code style}, {@code iframe}, {@code object},
     * {@code form}, and every {@code on*} attribute since none of
     * those are in any of the three named whitelists.
     */
    private static final PolicyFactory OWASP_POLICY =
        Sanitizers.FORMATTING
            .and(Sanitizers.BLOCKS)
            .and(Sanitizers.LINKS);

    /**
     * Sanitizer-invocation seam. Production wires this to
     * {@code OWASP_POLICY::sanitize}; tests in the same package
     * temporarily replace it with a function that throws to verify
     * the {@link #handleSanitizerException} fail-closed branch
     * (per redteam Finding 2 — OWASP's own sanitize() is robust
     * by design, so injecting a thrower is the only practical way
     * to exercise the fail-closed path). Package-private and
     * non-final by deliberate choice: writing to this field from
     * outside the package is impossible, and the test contract is
     * "swap, run one scenario, restore in @AfterEach".
     */
    static UnaryOperator<String> sanitizer = OWASP_POLICY::sanitize;

    @Inject
    DataSource dataSource;

    @Inject
    QuarantineDao quarantineDao;

    @ConfigProperty(name = "infochat.security.stage1.regex-timeout-ms")
    long regexTimeoutMs;

    /**
     * Run Stage 1 on one post. The caller supplies the loaded
     * post's identifying tuple plus the body (which may be null —
     * coerced to empty).
     *
     * @param postId          parent post {@code id}
     * @param postUid         parent post {@code uid} (denormalized
     *                        into every quarantine row)
     * @param postFetchedAt   parent post {@code fetched_at}
     *                        (partition locator)
     * @param rawBody         the upstream feed body; may be null
     * @return the {@link Stage1Result} carrying both the pre-redact
     *         and post-redact bodies plus the flagged / watchdog
     *         flags for downstream consumers (Stage 2 / Tagger).
     */
    public Stage1Result process(UUID postId, String postUid, Instant postFetchedAt, String rawBody) {
        String safeBody = rawBody == null ? "" : rawBody;
        // Step 1: HTML entity pre-decode. Closes the
        // entity-bypass vector documented in
        // docs/plan/m1/redteam/M1-032-2026-05-16.md Finding 1 by
        // ensuring the regex set sees the same decoded form that
        // OWASP would emit downstream.
        String entityDecoded = StringEscapeUtils.unescapeHtml4(safeBody);
        String normalized = unicodeNormalize(entityDecoded);

        try {
            List<Match> matches = findAllMatchesUnderWatchdog(normalized);
            return handleSuccess(postId, postUid, postFetchedAt, normalized, matches);
        } catch (RegexInterruptedException ex) {
            return handleWatchdogAbort(postId, postUid, postFetchedAt, normalized);
        } catch (SanitizerFailedException ex) {
            return handleSanitizerException(postId, postUid, postFetchedAt, normalized, ex.getCause());
        }
    }

    /**
     * Unicode normalize: NFKC then strip bidi-control codepoints
     * \\u202A..\\u202E and \\u2066..\\u2069, plus zero-width
     * codepoints \\u200B/\\u200C/\\u200D/\\uFEFF. UNCONDITIONAL on
     * the whole body — the Provider chat-intake carve-out does NOT
     * apply on the ingest path (per docs/spec/security.md §Ingest
     * pipeline parenthetical).
     */
    private static String unicodeNormalize(String body) {
        String nfkc = Normalizer.normalize(body, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(nfkc.length());
        for (int i = 0; i < nfkc.length(); i++) {
            char c = nfkc.charAt(i);
            // bidi controls ‪..‮ (LRE, RLE, PDF, LRO, RLO)
            if (c >= '‪' && c <= '‮') {
                continue;
            }
            // bidi isolates ⁦..⁩ (LRI, RLI, FSI, PDI)
            if (c >= '⁦' && c <= '⁩') {
                continue;
            }
            // zero-width: ​ (ZWSP), ‌ (ZWNJ),
            // ‍ (ZWJ), ﻿ (BOM / ZWNBSP)
            if (c == '​' || c == '‌' || c == '‍' || c == '﻿') {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Run every {@link Stage1RegexSet} pattern against the body,
     * collect all {@link Match} tuples, and resolve overlaps. The
     * input wrapper enforces the per-input wall-clock watchdog.
     *
     * <p>Overlap resolution: matches are sorted by {@code (start,
     * -length)} so a longer-first or earlier-start match wins; any
     * subsequent match whose start lies within a prior accepted
     * match's range is discarded. Iteration order through
     * {@link Stage1RegexSet#RULES} affects only the order of equal-
     * start equal-end matches (rare); ties resolve to the
     * earlier-listed rule, which is deterministic across runs.
     */
    private List<Match> findAllMatchesUnderWatchdog(String body) {
        long deadlineNanos = System.nanoTime() + (regexTimeoutMs * 1_000_000L);
        InterruptibleCharSequence wrapper = new InterruptibleCharSequence(body, deadlineNanos);

        List<Match> all = new ArrayList<>();
        for (Stage1RegexSet.Rule rule : Stage1RegexSet.RULES) {
            Matcher m = rule.pattern().matcher(wrapper);
            while (m.find()) {
                int start = m.start();
                int end = m.end();
                // m.group() reads from the wrapper, but the wrapper's
                // charAt is the bottleneck. Slice from the original
                // String to extract the verbatim span for
                // quarantine.original_html.
                String span = body.substring(start, end);
                all.add(new Match(rule.ruleId(), start, end, span));
            }
        }

        // Sort by start ascending, then by end descending (longer
        // wins on a tie). Discard overlaps with already-accepted
        // earlier matches.
        all.sort((a, b) -> {
            int byStart = Integer.compare(a.start(), b.start());
            return byStart != 0 ? byStart : Integer.compare(b.end(), a.end());
        });
        List<Match> accepted = new ArrayList<>();
        int lastEnd = -1;
        for (Match m : all) {
            if (m.start() >= lastEnd) {
                accepted.add(m);
                lastEnd = m.end();
            }
        }
        return accepted;
    }

    /**
     * Success path: write quarantine rows for every match, build
     * the redacted body, UPDATE post — all in one transaction so a
     * crash between the quarantine inserts and the {@code stage1_done}
     * flip cannot orphan quarantine rows for the rehydrator to
     * re-process. {@code post.status} stays {@code 'RAW'}.
     */
    private Stage1Result handleSuccess(UUID postId, String postUid, Instant postFetchedAt,
                                       String normalized, List<Match> matches) {
        if (matches.isEmpty()) {
            // No regex hits — OWASP-sanitize the normalized body
            // and write the result back to post.body. The
            // sanitization still runs on every post (Stage 1's
            // HTML-safety guarantee) even when no injection
            // patterns matched. Wrap in try/catch and re-throw as
            // SanitizerFailedException so process()'s outer
            // dispatcher routes to the fail-closed branch — per
            // docs/spec/security.md §Failure handling, an HTML
            // sanitizer exception must quarantine the post, not
            // propagate uncaught (which would leave the post at
            // RAW indefinitely and stall the eval queue).
            String sanitizedClean = safeSanitize(normalized);
            inTransaction(conn -> updatePostBodyAndFlags(conn, postId, postFetchedAt, sanitizedClean, false));
            return new Stage1Result(normalized, sanitizedClean, false, false);
        }

        // Build redacted body and one quarantine row per match.
        // Replace right-to-left to keep earlier-match offsets stable.
        StringBuilder redacted = new StringBuilder(normalized);
        List<QuarantineDao.QuarantineRow> rowsToInsert = new ArrayList<>(matches.size());
        for (int i = matches.size() - 1; i >= 0; i--) {
            Match match = matches.get(i);
            String placeholderId = PlaceholderIds.next();
            redacted.replace(match.start(), match.end(), PlaceholderIds.marker(placeholderId));
            rowsToInsert.add(new QuarantineDao.QuarantineRow(
                postId, postUid, postFetchedAt,
                match.ruleId(), match.start(), match.end(),
                match.span(), placeholderId));
        }

        // OWASP-sanitize the placeholder-redacted body. The
        // [REDACTED:[A-Z2-7]{26}] placeholders are pure ASCII non-
        // HTML-significant characters, so OWASP leaves them intact;
        // any remaining HTML markup in the body is sanitized as
        // designed. The safeSanitize wrapper re-throws as
        // SanitizerFailedException on RuntimeException so the
        // outer dispatcher in process() takes the fail-closed
        // branch — and IMPORTANT, the throw must happen BEFORE the
        // transaction starts so we never commit half a write (the
        // quarantine inserts would land but the post.body UPDATE
        // would not, leaving the consistency property
        // "post.body's placeholders match quarantine rows" broken).
        String sanitizedRedacted = safeSanitize(redacted.toString());
        inTransaction(conn -> {
            for (QuarantineDao.QuarantineRow row : rowsToInsert) {
                quarantineDao.insert(conn, row);
            }
            updatePostBodyAndFlags(conn, postId, postFetchedAt, sanitizedRedacted, true);
        });
        return new Stage1Result(normalized, sanitizedRedacted, true, false);
    }

    /**
     * Watchdog fail-closed: per
     * {@code docs/spec/security.md} §Failure handling, Stage 1
     * infrastructure failure quarantines the post immediately
     * with NO auto-release. UPDATE {@code post.status='QUARANTINED'},
     * write one quarantine row with {@code rule_id='regex_timeout'}
     * spanning the whole body, log at WARN with the canonical
     * error_class string. Both writes run in one transaction so a
     * partial commit cannot leave a QUARANTINED post without its
     * audit row. The post body is overwritten with a single
     * whole-body placeholder so any future render of
     * {@code post.body} cannot leak the unredacted content (the
     * row stays QUARANTINED regardless of
     * {@code release-on-stage2-failure}, but the consistency
     * property "post.body matches the [REDACTED:<id>] placeholders
     * referenced by its quarantine rows" must hold).
     */
    private Stage1Result handleWatchdogAbort(UUID postId, String postUid, Instant postFetchedAt,
                                             String normalized) {
        LOG.warnf("Stage 1 watchdog fired on post_id=%s (rule_id=%s, error_class=%s, cap_ms=%d)",
            postId, REGEX_TIMEOUT_RULE_ID, ERROR_CLASS_REGEX_TIMEOUT, regexTimeoutMs);

        String placeholderId = PlaceholderIds.next();
        String placeholderMarker = PlaceholderIds.marker(placeholderId);

        inTransaction(conn -> {
            quarantineDao.insert(conn, new QuarantineDao.QuarantineRow(
                postId, postUid, postFetchedAt,
                REGEX_TIMEOUT_RULE_ID, 0, normalized.length(),
                normalized, placeholderId));
            updatePostQuarantined(conn, postId, postFetchedAt, placeholderMarker);
        });
        return new Stage1Result(normalized, placeholderMarker, true, true);
    }

    /**
     * OWASP-sanitize wrapper that re-throws any
     * {@link RuntimeException} from the underlying sanitizer as a
     * {@link SanitizerFailedException}. Per
     * {@code docs/spec/security.md} §Failure handling, a Stage 1
     * infrastructure failure — including an HTML sanitizer exception —
     * must fail-closed to {@code QUARANTINED}; without this wrapper
     * an OWASP exception would propagate out of {@code process()},
     * leave the post at {@code status='RAW'} indefinitely, and stall
     * the eval queue (since the {@code @Incoming} worker would nack
     * the message or drop it depending on SmallRye's failure-strategy
     * config). Going through a dedicated checked-ish exception type
     * keeps the dispatcher in {@code process()} shape-parallel to the
     * watchdog-abort path.
     */
    private static String safeSanitize(String input) {
        try {
            return sanitizer.apply(input);
        } catch (RuntimeException ex) {
            throw new SanitizerFailedException(ex);
        }
    }

    /**
     * Sanitizer-exception fail-closed: parallel shape to
     * {@link #handleWatchdogAbort}. Per
     * {@code docs/spec/security.md} §Failure handling, an HTML
     * sanitizer exception is a Stage 1 infrastructure failure and
     * must produce {@code post.status='QUARANTINED'} with one
     * whole-body quarantine row, NEVER auto-released. The log line
     * carries the canonical {@link #ERROR_CLASS_SANITIZER_EXCEPTION}
     * string so the future throttled admin notifier (T2-G) can
     * coalesce alerts on it without diff churn here.
     */
    private Stage1Result handleSanitizerException(UUID postId, String postUid, Instant postFetchedAt,
                                                  String normalized, Throwable cause) {
        LOG.warnf(cause,
            "Stage 1 sanitizer exception on post_id=%s (rule_id=%s, error_class=%s, cause=%s)",
            postId, SANITIZER_EXCEPTION_RULE_ID, ERROR_CLASS_SANITIZER_EXCEPTION,
            cause == null ? "<none>" : cause.getClass().getName());

        String placeholderId = PlaceholderIds.next();
        String placeholderMarker = PlaceholderIds.marker(placeholderId);

        inTransaction(conn -> {
            quarantineDao.insert(conn, new QuarantineDao.QuarantineRow(
                postId, postUid, postFetchedAt,
                SANITIZER_EXCEPTION_RULE_ID, 0, normalized.length(),
                normalized, placeholderId));
            updatePostQuarantined(conn, postId, postFetchedAt, placeholderMarker);
        });
        return new Stage1Result(normalized, placeholderMarker, true, true);
    }

    /**
     * Run the given writes inside a single transaction. Per the
     * {@link io.infochat.collector.bootstrap.BootstrapLoader}
     * precedent, raw JDBC ownership of {@code autoCommit=false} +
     * explicit commit/rollback is the project's transactional shape
     * for multi-statement units of work.
     */
    private void inTransaction(TxBody body) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                body.run(conn);
                conn.commit();
            } catch (RuntimeException | SQLException e) {
                conn.rollback();
                throw (e instanceof RuntimeException re)
                    ? re
                    : new IllegalStateException("Stage1Pipeline: transactional write failed", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                "Stage1Pipeline: failed to acquire connection or rollback", e);
        }
    }

    @FunctionalInterface
    private interface TxBody {
        void run(Connection conn) throws SQLException;
    }

    private static void updatePostBodyAndFlags(Connection conn, UUID postId, Instant postFetchedAt,
                                               String newBody, boolean flagged) throws SQLException {
        final String sql =
            "UPDATE post SET body = ?, stage1_flagged = ?, stage1_done = TRUE "
                + "WHERE id = ? AND fetched_at = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newBody);
            ps.setBoolean(2, flagged);
            ps.setObject(3, postId);
            ps.setTimestamp(4, Timestamp.from(postFetchedAt));
            ps.executeUpdate();
        }
    }

    private static void updatePostQuarantined(Connection conn, UUID postId, Instant postFetchedAt,
                                              String wholeBodyPlaceholder) throws SQLException {
        final String sql =
            "UPDATE post SET body = ?, status = 'QUARANTINED', "
                + "       stage1_done = TRUE, "
                + "       status_changed_at = now() "
                + "WHERE id = ? AND fetched_at = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, wholeBodyPlaceholder);
            ps.setObject(2, postId);
            ps.setTimestamp(3, Timestamp.from(postFetchedAt));
            ps.executeUpdate();
        }
    }

    /**
     * One regex-match tuple. Captured during scan; rewritten into a
     * quarantine row + placeholder later.
     */
    private record Match(String ruleId, int start, int end, String span) {
    }

    /**
     * Wraps a body string with a wall-clock deadline. Every
     * {@link #charAt(int)} call checks the clock and throws
     * {@link RegexInterruptedException} after the deadline. The
     * exception unwinds {@link Matcher#find()} cleanly because the
     * underlying NFA engine never catches it.
     */
    private static final class InterruptibleCharSequence implements CharSequence {
        private final CharSequence delegate;
        private final long deadlineNanos;

        InterruptibleCharSequence(CharSequence delegate, long deadlineNanos) {
            this.delegate = delegate;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadlineNanos) {
                throw new RegexInterruptedException();
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new InterruptibleCharSequence(delegate.subSequence(start, end), deadlineNanos);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    /**
     * Thrown by {@link InterruptibleCharSequence#charAt(int)} when
     * the wall-clock cap has elapsed. Unchecked because
     * {@link CharSequence#charAt(int)} cannot declare a checked
     * throws clause.
     */
    static final class RegexInterruptedException extends RuntimeException {
        RegexInterruptedException() {
            super("Stage 1 regex watchdog fired");
        }
    }

    /**
     * Thrown by {@link #safeSanitize(String)} when the underlying
     * OWASP sanitizer raises any {@link RuntimeException}. The
     * outer dispatcher in {@link #process} catches this and routes
     * to {@link #handleSanitizerException} for the fail-closed
     * branch. Wrapping rather than letting the original exception
     * propagate keeps the fail-closed handler signature parallel
     * to {@link RegexInterruptedException}'s handler and pins the
     * "OWASP threw — fail-closed" semantics to a single exception
     * type the reviewer can grep for.
     */
    static final class SanitizerFailedException extends RuntimeException {
        SanitizerFailedException(Throwable cause) {
            super("Stage 1 sanitizer raised an exception", cause);
        }
    }

    /**
     * The hand-off carrier for downstream stages.
     *
     * @param originalBody  the post-normalize / PRE-redact body —
     *                      what Stage 2's LLM judge sees per
     *                      {@code docs/spec/security.md} §Ingest pipeline.
     * @param redactedBody  the body with {@code [REDACTED:<id>]}
     *                      placeholders woven in; what
     *                      {@code post.body} now holds in the DB.
     *                      Equals {@code originalBody} when
     *                      {@code !flagged} and no watchdog;
     *                      equals a whole-body placeholder on a
     *                      watchdog abort.
     * @param flagged        true when any regex matched OR the
     *                       watchdog fired.
     * @param quarantinedByWatchdog true ONLY on the watchdog
     *                              fail-closed path. {@code flagged}
     *                              is then also true; the post is
     *                              now at {@code status='QUARANTINED'}.
     */
    public record Stage1Result(
        String originalBody,
        String redactedBody,
        boolean flagged,
        boolean quarantinedByWatchdog
    ) {
    }
}
