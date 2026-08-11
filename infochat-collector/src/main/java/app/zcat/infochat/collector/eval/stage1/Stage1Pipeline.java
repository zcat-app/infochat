package app.zcat.infochat.collector.eval.stage1;

import app.zcat.infochat.collector.eval.TransactionHelper;
import app.zcat.infochat.core.ingest.IngestTextNormalizer;
import app.zcat.infochat.core.log.SafeLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.text.StringEscapeUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import org.owasp.html.HtmlSanitizer;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
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
 *       {@code &#0NN;}) and named (&amp;amp;, &amp;lt;, …)
 *       HTML entities to their literal codepoints BEFORE Unicode
 *       normalization and the regex set. Without this step, a payload
 *       like &amp;#105;gnore previous instructions reaches the
 *       regex set as the literal byte sequence &amp;#105;gnore...
 *       (no {@code ignore} substring; no match); the downstream OWASP
 *       sanitizer then decodes the entity as part of HTML parsing,
 *       producing decoded prompt-injection text in {@code post.body}
 *       that the regex never saw. The pre-decode closes that bypass
 *       by ensuring the regex sees the same decoded form OWASP would
 *       eventually emit. Finding 1 documents the attack vector.</li>
 *   <li><b>Unicode normalize unconditionally</b> — NFKC over the
 *       entire body; bidi-control strip (U+061C, U+200E/U+200F,
 *       U+202A..U+202E, U+2066..U+2069); zero-width strip
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
 *   <li><b>OWASP allowlist parse, emitted as plain text</b> on the
 *       redacted body — strip {@code script}, {@code style},
 *       {@code iframe}, {@code object}, {@code form}, {@code on*}
 *       event attributes, and {@code javascript:} / {@code data:} /
 *       {@code file:} URL schemes; the surviving event stream is
 *       rendered by {@link PlainTextSink}, so allowlisted tags
 *       contribute their text only (block boundaries and {@code <br>}
 *       become single line breaks, attributes are never emitted) and
 *       {@code post.body} stores plain text per §4.2 step 4
 *       bullet 3 (M1-784). The {@code [REDACTED:[A-Z2-7]{26}]} marker
 *       is pure ASCII whitespace-free text so the sink emits it
 *       byte-exact. The call is wrapped in a try/catch over
 *       {@link RuntimeException}; on failure the pipeline takes the
 *       sanitizer-exception fail-closed branch
 *       ({@link #handleSanitizerException}) which writes
 *       {@code post.status='QUARANTINED'} and a whole-body
 *       quarantine row with {@code rule_id='sanitizer_exception'}
 *       per {@code docs/spec/security.md} §Failure handling
 *       ("HTML sanitizer exception → fail-closed").</li>
 *   <li><b>Second injection scan over the sanitizer's output</b> —
 *       re-runs the rule set on the exact string about to be stored,
 *       charging the SAME per-post deadline and match allowance as
 *       the first scan (no second budget). The OWASP parse is itself
 *       a decode step: a doubly-encoded payload only turns into
 *       readable text during HTML parsing, and the plain-text emission
 *       synthesizes structure the first scan could not see (block-close
 *       line breaks, joined text runs). Its output is canonicalized
 *       (NFKC + bidi/zero-width strip) before scan and storage (M1-788).
 *       Hits are redacted and
 *       quarantined like first-pass matches; a match that
 *       straddles an existing {@code [REDACTED:<id>]} marker is
 *       split around it — each non-marker segment is redacted and recorded.</li>
 *   <li><b>UPDATE post</b> — set {@code stage1_done = true},
 *       {@code stage1_flagged = (any match)}, body to the
 *       plain-text form with any second-pass
 *       {@code [REDACTED:<id>]} markers woven in. {@code post.status} stays
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
 * documented above. The second scan stays AFTER {@code safeSanitize}
 * — a sanitizer throw must still unwind to
 * {@link #handleSanitizerException} before any second-pass work —
 * and the first scan is NOT moved onto the sanitized form: the
 * HTML-comment and {@code <<<UNTRUSTED>>>} rules (5 and 6) need the
 * pre-parse shape only the first scan sees.
 *
 * <ul>
 *   <li><b>The OWASP parse drops HTML comments outright.</b> The
 *       lexer discards comment tokens before the policy ever sees
 *       them, so the comment-hide rule (5) can only fire on the
 *       pre-parse form the first scan sees — and the parse's entity
 *       decode is covered by the second scan (M1-785) only for decode
 *       products the ASCII rule set can match on the stored string;
 *       non-canonical products fall under §4.2's obfuscation
 *       disclaimer.</li>
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
 * injections are exposed to the regex; HTML is reduced to sanitized
 * plain text; Unicode is normalized while NFKC can still see the
 * original codepoints; delimiter injection is detected before the
 * parse gets a chance to mangle the marker shape. The
 * {@code [REDACTED:[A-Z2-7]{26}]} placeholder is pure ASCII
 * whitespace-free text so the plain-text emission carries it
 * byte-exact. See
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

    private static final Logger LOG = LoggerFactory.getLogger(Stage1Pipeline.class);

    /** Canonical error_class for the throttled admin notifier (T2-G). */
    public static final String ERROR_CLASS_REGEX_TIMEOUT = "stage1.regex_timeout";

    /**
     * Canonical error_class for the match-count overflow fail-closed
     * path. Coalesced on {@code (channel, error_class)} by the future
     * throttled admin notifier (T2-G), parallel to
     * {@link #ERROR_CLASS_REGEX_TIMEOUT}.
     */
    public static final String ERROR_CLASS_MATCH_OVERFLOW = "stage1.match_overflow";

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
     * Rule id used on the match-overflow fail-closed whole-body
     * quarantine row. Parallel shape to {@link #REGEX_TIMEOUT_RULE_ID};
     * downstream admin triage correlates Stage 1 infrastructure
     * failures by this rule_id.
     */
    public static final String MATCH_OVERFLOW_RULE_ID = "match_overflow";

    /**
     * Rule id used on the sanitizer-exception fail-closed whole-body
     * quarantine row. Parallel shape to {@link #REGEX_TIMEOUT_RULE_ID};
     * downstream admin triage scripts correlate Stage 1 infrastructure
     * failures by this rule_id.
     */
    public static final String SANITIZER_EXCEPTION_RULE_ID = "sanitizer_exception";

    /**
     * OWASP allowlist policy — the §4.2 step 1 whitelist set.
     * Sanitizers.FORMATTING covers the inline set {@code b, i, font,
     * s, u, o, sup, sub, ins, del, strong, strike, tt, code, big,
     * small, br, span, em}; Sanitizers.BLOCKS covers {@code p, div,
     * h1-h6, ul, ol, li, blockquote}; Sanitizers.LINKS covers anchors
     * with http/https hrefs only (javascript:/data:/file: schemes are
     * stripped by the LINKS sanitizer's URL filter). The composition
     * strips {@code script}, {@code style}, {@code iframe},
     * {@code object}, {@code form}, and every {@code on*} attribute
     * since none of those are in any of the three named whitelists.
     * {@link PlainTextSink}'s block-boundary set is derived from
     * BLOCKS' element list and must move with it.
     */
    private static final PolicyFactory OWASP_POLICY =
        Sanitizers.FORMATTING
            .and(Sanitizers.BLOCKS)
            .and(Sanitizers.LINKS);

    @Inject
    DataSource dataSource;

    @Inject
    QuarantineDao quarantineDao;

    @ConfigProperty(name = "infochat.security.stage1.regex-timeout-ms")
    long regexTimeoutMs;

    /**
     * Hard cap on how many {@link Match} tuples may accumulate across
     * all rules for one body before Stage 1 fails closed. Orthogonal
     * to {@link #regexTimeoutMs}: the watchdog bounds scan <em>time</em>,
     * this bounds match <em>volume</em> so a crafted feed body cannot
     * transiently allocate a huge match list inside the watchdog window.
     * Default lives in {@code application.properties} per the M1-028
     * precedent (no inline {@code defaultValue}).
     */
    @ConfigProperty(name = "infochat.security.stage1.max-matches")
    int maxMatches;

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
    public Stage1Result process(UUID postId, String postUid, Instant postFetchedAt, @Nullable String rawBody) {
        String safeBody = rawBody == null ? "" : rawBody;
        // Step 1: HTML entity pre-decode. Closes the
        // entity-bypass vector documented by Finding 1 by ensuring the regex
        // set sees the same decoded form that
        // OWASP would emit downstream.
        String entityDecoded = StringEscapeUtils.unescapeHtml4(safeBody);
        String normalized = unicodeNormalize(entityDecoded);

        try {
            ConvertedBody converted = convertAndRescan(normalized, List.of());
            List<QuarantineDao.QuarantineRow> rowsToInsert =
                new ArrayList<>(converted.redactions().size());
            for (Redaction redaction : converted.redactions()) {
                rowsToInsert.add(new QuarantineDao.QuarantineRow(
                    postId, postUid, postFetchedAt,
                    redaction.ruleId(), redaction.start(), redaction.end(),
                    redaction.span(), redaction.placeholderId()));
            }
            boolean flagged = !rowsToInsert.isEmpty();
            // The transaction starts only AFTER the conversion: a
            // sanitizer throw must unwind before any write, and the
            // inserts and the UPDATE share one transaction so a crash
            // cannot leave quarantine rows against an unwritten body
            // (the "post.body's placeholders match quarantine rows"
            // consistency property). post.status stays 'RAW'.
            TransactionHelper.inTransaction(dataSource, "Stage1Pipeline", conn -> {
                for (QuarantineDao.QuarantineRow row : rowsToInsert) {
                    quarantineDao.insert(conn, row);
                }
                updatePostBodyAndFlags(conn, postId, postFetchedAt,
                    converted.storedBody(), flagged);
            });
            // originalBody stays `normalized`: Stage 2 judges the
            // pre-redaction text (docs/spec/security.md §Ingest pipeline).
            return new Stage1Result(normalized, converted.storedBody(), flagged, false);
        } catch (RegexInterruptedException ex) {
            return handleWatchdogAbort(postId, postUid, postFetchedAt, normalized);
        } catch (MatchOverflowException ex) {
            return handleMatchOverflow(postId, postUid, postFetchedAt, normalized);
        } catch (SanitizerFailedException ex) {
            return handleSanitizerException(postId, postUid, postFetchedAt, normalized, ex.getCause());
        }
    }

    /**
     * Unicode normalize: NFKC then strip bidi-control codepoints
     * \\u061C, \\u200E/\\u200F, \\u202A..\\u202E and \\u2066..\\u2069,
     * plus zero-width
     * codepoints \\u200B/\\u200C/\\u200D/\\uFEFF. UNCONDITIONAL on
     * the whole body — the Provider chat-intake carve-out does NOT
     * apply on the ingest path (per docs/spec/security.md §Ingest
     * pipeline parenthetical).
     */
    static String unicodeNormalize(String body) {
        String nfkc = Normalizer.normalize(body, Normalizer.Form.NFKC);
        // The bidi/zero-width strip is shared with the title/url
        // normalization (IngestTextNormalizer is the single declaration
        // of that codepoint loop). Control characters are intentionally
        // NOT stripped here — the body legitimately carries
        // newlines/tabs; only the single-line title/url fields get the
        // additional control strip.
        return IngestTextNormalizer.stripBidiAndZeroWidth(nfkc);
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
     *
     * <p>Matches are charged against the caller's {@link ScanBudget},
     * which carries {@link #maxMatches} for the whole {@link #process}
     * call rather than per scan: as soon as a crafted body would push
     * the total past the cap, a {@link MatchOverflowException} unwinds
     * the scan (the dispatcher in {@link #process} routes it to the
     * fail-closed {@link #handleMatchOverflow} quarantine path). The
     * throw happens BEFORE the offending match is added, so the list
     * never allocates past the cap — never silently truncated.
     */
    private List<Match> findAllMatchesUnderWatchdog(String body, ScanBudget budget) {
        InterruptibleCharSequence wrapper = new InterruptibleCharSequence(body, budget.deadlineNanos());

        List<Match> all = new ArrayList<>();
        for (Stage1RegexSet.Rule rule : Stage1RegexSet.RULES) {
            Matcher m = rule.pattern().matcher(wrapper);
            while (m.find()) {
                budget.chargeOneMatch();
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
     * Convert-plus-scan core shared with the M1-786 remediation job:
     * first-pass scan and placeholder redaction of {@code normalized},
     * OWASP parse down to plain text, canonicalize, then a second scan
     * over the exact string to be stored with its own redaction — all
     * under one {@link ScanBudget}, so a body that survives the first
     * scan cannot buy itself a second full budget (M1-785). Returns the
     * string to store plus one {@link Redaction} per match; the caller
     * owns the transaction and maps each redaction to its recording
     * shape. Throws {@link RegexInterruptedException},
     * {@link MatchOverflowException} or {@link SanitizerFailedException}
     * for the caller's fail-closed handling.
     *
     * <p>{@code protectedPlaceholderIds} are {@code [REDACTED:<id>]}
     * markers ALREADY in the input — reachable only on the remediation
     * path, where the stored body may carry an earlier quarantine's
     * marker and one altered byte makes {@code approve_quarantine}'s
     * literal replace a silent no-op; {@link #process} passes
     * {@link List#of()} because a fresh Stage 1 input carries none.
     * Both scans split matches around them so no emitted segment covers
     * a marker byte (M1-786 P7, reusing M1-787's straddle rule on the
     * first pass too).
     */
    ConvertedBody convertAndRescan(String normalized, List<String> protectedPlaceholderIds) {
        ScanBudget budget = new ScanBudget(regexTimeoutMs, maxMatches);
        List<Match> matches = splitMatchesAroundPlaceholders(
            findAllMatchesUnderWatchdog(normalized, budget), normalized, protectedPlaceholderIds);

        // Build redacted body and one redaction per match.
        // Replace right-to-left to keep earlier-match offsets stable.
        // An empty match list is the clean path: no redactions, no
        // replacements, and the sanitize below still runs — Stage 1's
        // HTML-safety guarantee applies to every post, matched or not.
        StringBuilder redacted = new StringBuilder(normalized);
        List<Redaction> redactions = new ArrayList<>(matches.size());
        List<String> secondPassProtectedIds = new ArrayList<>(protectedPlaceholderIds);
        for (int i = matches.size() - 1; i >= 0; i--) {
            Match match = matches.get(i);
            String placeholderId = PlaceholderIds.next();
            redacted.replace(match.start(), match.end(), PlaceholderIds.marker(placeholderId));
            secondPassProtectedIds.add(placeholderId);
            redactions.add(new Redaction(
                match.ruleId(), match.start(), match.end(), match.span(), placeholderId));
        }

        // OWASP-parse the placeholder-redacted body down to plain
        // text (PlainTextSink). The [REDACTED:[A-Z2-7]{26}]
        // placeholders are pure ASCII whitespace-free text, so the
        // sink emits them byte-exact; any remaining HTML markup is
        // reduced to its text. The safeSanitize wrapper re-throws as
        // SanitizerFailedException on RuntimeException so the
        // outer dispatcher in process() takes the fail-closed
        // branch.
        String sanitizedRedacted = safeSanitize(redacted.toString());

        // Canonicalize the parse output (M1-788): the parse decodes
        // depth-2 entities to literal text only now — fold/strip so the
        // second scan and the stored column share one canonical string.
        String canonicalRedacted = unicodeNormalize(sanitizedRedacted);

        // Second scan: the sanitize above is an HTML parse, so it decodes
        // a deeper-encoded payload to literal text only AFTER the first
        // scan ran. Scan what is actually stored (M1-785). Added, never
        // moved — rules 5 and 6 need the pre-parse shape (class doc).
        List<Match> secondPassMatches = splitMatchesAroundPlaceholders(
            findAllMatchesUnderWatchdog(canonicalRedacted, budget),
            canonicalRedacted, secondPassProtectedIds);

        StringBuilder finalBody = new StringBuilder(canonicalRedacted);
        for (int i = secondPassMatches.size() - 1; i >= 0; i--) {
            Match match = secondPassMatches.get(i);
            String placeholderId = PlaceholderIds.next();
            finalBody.replace(match.start(), match.end(), PlaceholderIds.marker(placeholderId));
            redactions.add(new Redaction(
                match.ruleId(), match.start(), match.end(), match.span(), placeholderId));
        }
        return new ConvertedBody(finalBody.toString(), redactions);
    }

    /**
     * Split second-pass matches around the first pass's
     * {@code [REDACTED:<id>]} markers: {@code approve_quarantine}
     * restores by literal replace, so no emitted segment may cover a
     * marker byte — but the payload bytes around the marker still get
     * their own quarantine row and redaction instead of the whole match
     * going unrecorded (M1-787); spans are located from the ids actually
     * emitted, never by matching bracket text.
     */
    private static List<Match> splitMatchesAroundPlaceholders(
            List<Match> candidates, String body, List<String> placeholderIds) {
        if (placeholderIds.isEmpty() || candidates.isEmpty()) {
            return candidates;
        }
        List<int[]> protectedSpans = new ArrayList<>(placeholderIds.size());
        for (String placeholderId : placeholderIds) {
            String marker = PlaceholderIds.marker(placeholderId);
            int at = body.indexOf(marker);
            while (at >= 0) {
                protectedSpans.add(new int[] {at, at + marker.length()});
                at = body.indexOf(marker, at + marker.length());
            }
        }
        // Ids arrive in reverse positional order (appended inside the
        // right-to-left replacement loop); clipping needs ascending
        // starts.
        protectedSpans.sort(Comparator.comparingInt(span -> span[0]));
        List<Match> kept = new ArrayList<>(candidates.size());
        for (Match candidate : candidates) {
            int segmentStart = candidate.start();
            for (int[] span : protectedSpans) {
                if (span[1] <= candidate.start()) {
                    continue;
                }
                if (span[0] >= candidate.end()) {
                    break;
                }
                // Overlap: emit the non-marker sub-span before this
                // marker, then resume after it. Boundary adjacency
                // (shared endpoint, no shared bytes) emits nothing.
                if (segmentStart < span[0]) {
                    kept.add(new Match(candidate.ruleId(), segmentStart, span[0],
                        body.substring(segmentStart, span[0])));
                }
                segmentStart = Math.max(segmentStart, span[1]);
            }
            if (segmentStart < candidate.end()) {
                kept.add(new Match(candidate.ruleId(), segmentStart, candidate.end(),
                    body.substring(segmentStart, candidate.end())));
            }
        }
        return kept;
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
        LOG.warn("Stage 1 watchdog fired on post_id={} (rule_id={}, error_class={}, cap_ms={})",
            postId, REGEX_TIMEOUT_RULE_ID, ERROR_CLASS_REGEX_TIMEOUT, regexTimeoutMs);
        return quarantineWholeBody(postId, postUid, postFetchedAt, normalized, REGEX_TIMEOUT_RULE_ID);
    }

    /**
     * Match-overflow fail-closed: parallel shape to
     * {@link #handleWatchdogAbort}. The match-count cap
     * ({@link #maxMatches}, from
     * {@code infochat.security.stage1.max-matches}) trips when a
     * crafted feed body produces more regex hits than any legitimate
     * post would, before the per-character wall-clock watchdog
     * necessarily fires. Per {@code docs/spec/security.md}
     * §Failure handling, a Stage 1 infrastructure failure quarantines
     * the post immediately with NO auto-release: UPDATE
     * {@code post.status='QUARANTINED'}, write one quarantine row with
     * {@code rule_id='match_overflow'} spanning the whole body, log at
     * WARN with the canonical {@link #ERROR_CLASS_MATCH_OVERFLOW}
     * string. Both writes run in one transaction so a partial commit
     * cannot leave a QUARANTINED post without its audit row, and the
     * body is overwritten with a single whole-body placeholder so any
     * future render cannot leak the unredacted content.
     */
    private Stage1Result handleMatchOverflow(UUID postId, String postUid, Instant postFetchedAt,
                                             String normalized) {
        LOG.warn("Stage 1 match-count cap exceeded on post_id={} (rule_id={}, error_class={}, cap={})",
            postId, MATCH_OVERFLOW_RULE_ID, ERROR_CLASS_MATCH_OVERFLOW, maxMatches);
        return quarantineWholeBody(postId, postUid, postFetchedAt, normalized, MATCH_OVERFLOW_RULE_ID);
    }

    /**
     * Shared Stage 1 fail-closed whole-body quarantine write, parameterized only
     * by {@code ruleId} (the sole per-handler distinguisher). Per
     * {@code docs/spec/security.md} §Failure handling: insert one quarantine row
     * spanning {@code [0, normalized.length())}, then UPDATE
     * {@code post.status='QUARANTINED'} with the body overwritten by a single
     * whole-body placeholder — both writes in one transaction so a partial commit
     * cannot leave a QUARANTINED post without its audit row, and any future render
     * of {@code post.body} cannot leak the unredacted content. NO auto-release.
     * Callers ({@link #handleWatchdogAbort}, {@link #handleMatchOverflow}) emit
     * their own distinguishing WARN line before delegating here.
     */
    private Stage1Result quarantineWholeBody(UUID postId, String postUid, Instant postFetchedAt,
                                             String normalized, String ruleId) {
        String placeholderId = PlaceholderIds.next();
        String placeholderMarker = PlaceholderIds.marker(placeholderId);

        TransactionHelper.inTransaction(dataSource, "Stage1Pipeline", conn -> {
            quarantineDao.insert(conn, new QuarantineDao.QuarantineRow(
                postId, postUid, postFetchedAt,
                ruleId, 0, normalized.length(),
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
    private String safeSanitize(String input) {
        try {
            return sanitize(input);
        } catch (RuntimeException ex) {
            throw new SanitizerFailedException(ex);
        }
    }

    /**
     * Sanitizer-invocation seam. Production runs the OWASP allowlist
     * parse with a {@link PlainTextSink} receiver — the policy stays
     * the one allowlist authority; only the emitter changed (M1-784).
     * Package-private and non-static (not {@code private static}) so a
     * test-scoped subclass can override it to inject a thrower and exercise the
     * {@link #handleSanitizerException} fail-closed branch (redteam Finding 2 —
     * OWASP's own {@code sanitize()} is robust by design, so a thrower is the
     * only practical way to reach the fail-closed path). Mirrors the
     * test-subclass seam idiom on
     * {@code EmbeddingWorker.formatVector}, replacing the prior risky
     * static-mutable-field seam (M1-377).
     */
    String sanitize(String input) {
        PlainTextSink sink = new PlainTextSink();
        HtmlSanitizer.sanitize(input, OWASP_POLICY.apply(sink));
        return sink.bodyText();
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
                                                  String normalized, @Nullable Throwable cause) {
        // SafeLog, never the raw Throwable: sanitizer/parser exceptions
        // routinely quote the offending input — here the
        // upstream-untrusted, pre-vetting feed body
        // (docs/spec/security.md §Secrets handling — User content in
        // exceptions). SafeLog appends the exception class name, which
        // replaces the old explicit cause=%s field.
        String message = "Stage 1 sanitizer exception on post_id=" + postId
            + " (rule_id=" + SANITIZER_EXCEPTION_RULE_ID
            + ", error_class=" + ERROR_CLASS_SANITIZER_EXCEPTION + ")";
        if (cause == null) {
            LOG.warn(message);
        } else {
            SafeLog.warn(LOG, message, cause);
        }

        String placeholderId = PlaceholderIds.next();
        String placeholderMarker = PlaceholderIds.marker(placeholderId);

        TransactionHelper.inTransaction(dataSource, "Stage1Pipeline", conn -> {
            quarantineDao.insert(conn, new QuarantineDao.QuarantineRow(
                postId, postUid, postFetchedAt,
                SANITIZER_EXCEPTION_RULE_ID, 0, normalized.length(),
                normalized, placeholderId));
            updatePostQuarantined(conn, postId, postFetchedAt, placeholderMarker);
        });
        return new Stage1Result(normalized, placeholderMarker, true, true);
    }

    private static void updatePostBodyAndFlags(Connection conn, UUID postId, Instant postFetchedAt,
                                               String newBody, boolean flagged) throws SQLException {
        // Every Stage 1 body write IS the plain-text representation, so
        // it stamps the V79 remediation marker with it — an unstamped
        // fresh row would enter the M1-786 job's batch, whose
        // unescape+parse conversion is not a no-op on new-format text.
        // now() is an audit-write stamp; the marker's NULL-ness, never
        // its value, gates pickup (engineering-rules §9 exemption).
        final String sql =
            "UPDATE post SET body = ?, stage1_flagged = ?, stage1_done = TRUE, "
                + "       body_remediated_at = now() "
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
        // The whole-body placeholder is plain ASCII text — the
        // plain-text representation — so the fail-closed paths stamp
        // the V79 marker too (see updatePostBodyAndFlags).
        final String sql =
            "UPDATE post SET body = ?, status = 'QUARANTINED', "
                + "       stage1_done = TRUE, "
                + "       status_changed_at = now(), "
                + "       body_remediated_at = now() "
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
     * Thrown by {@link #findAllMatchesUnderWatchdog} when the
     * accumulated match list reaches the configured
     * {@code infochat.security.stage1.max-matches} cap. The outer
     * dispatcher in {@link #process} catches this and routes to
     * {@link #handleMatchOverflow} for the fail-closed branch.
     * Parallel shape to {@link RegexInterruptedException}: a distinct
     * type the reviewer can grep for, pinning the "too many matches —
     * fail-closed" semantics to a single exception.
     */
    static final class MatchOverflowException extends RuntimeException {
        MatchOverflowException() {
            super("Stage 1 match-count cap exceeded");
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
     * The convert-plus-scan core's output (M1-786): the plain-text body
     * to store and one {@link Redaction} per match (empty on the clean
     * path — "flagged" is simply a non-empty redaction list). Kept
     * target-agnostic so the remediation job drives the SAME conversion
     * the live pipeline uses (P11, never a second decoder); each caller
     * maps the redactions to its own recording shape.
     */
    record ConvertedBody(String storedBody, List<Redaction> redactions) {
    }

    /**
     * One match the core redacted: the rule id, the span's UTF-16
     * offsets in the string that scan ran over (the pre-parse form for
     * first-pass matches, the canonical stored form for second-pass),
     * the verbatim span, and the placeholder id woven into the body at
     * that spot. {@link #process} and the remediation job's post path
     * record each redaction as a quarantine row; the job's snapshot
     * path cannot — saved_post carries no post locator columns — and
     * logs instead.
     */
    record Redaction(String ruleId, int start, int end, String span, String placeholderId) {
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

    /**
     * One deadline and one match allowance per {@link #process} call,
     * shared by both scans: {@code docs/spec/security.md} §Ingest
     * pipeline bounds these PER INPUT, so a per-scan budget would hand
     * a crafted body twice the stated bound (M1-785 P1).
     */
    private static final class ScanBudget {
        private final long deadlineNanos;
        private int remainingMatches;

        ScanBudget(long regexTimeoutMs, int maxMatches) {
            this.deadlineNanos = System.nanoTime() + (regexTimeoutMs * 1_000_000L);
            this.remainingMatches = maxMatches;
        }

        long deadlineNanos() {
            return deadlineNanos;
        }

        /**
         * Charge one match against the per-input allowance, throwing
         * once it is spent. Called BEFORE the match is added so the
         * accumulating list never allocates past the cap.
         */
        void chargeOneMatch() {
            if (remainingMatches <= 0) {
                throw new MatchOverflowException();
            }
            remainingMatches--;
        }
    }
}
