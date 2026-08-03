package app.zcat.infochat.provider.render;

import app.zcat.infochat.core.ingest.IngestTextNormalizer;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import org.jspecify.annotations.Nullable;

/**
 * Derives the bounded one-line headline that labels a post in the four
 * user-visible render surfaces ({@code ClusterBlockRenderer}, {@code
 * SummaryProseGenerator.degradedProseFor}, {@code DegradedDigestRenderer},
 * {@code SavedCommandHandler}). Sharing one derivation is what keeps those
 * four from drifting.
 *
 * <p><b>Prompt input: summarizer no, roll-up yes.</b> The summarizer prompt
 * ({@code SummaryProseGenerator.buildPrompt}) must keep appending the FULL
 * untruncated title — it summarizes ONE cluster, so a bounded headline
 * would have the model describe a fragment, which produces no error and
 * no failing test. The roll-up prompt
 * ({@code CategoryRollupGenerator.buildPrompt}) is the deliberate
 * exception (M1-728, revising M1-714's original both-prompts rule): it
 * sees every cluster in a category and is told not to re-list them, so
 * it feeds each title through THIS helper — a corpus-maximum nitter
 * title contributes 200 chars + an ellipsis instead of crowding out
 * several hundred other titles.
 *
 * <p><b>Why a headline is derived at all.</b> {@code title} is a headline only
 * for RSS. For the social sources it is the post: measured over the 9,236-post
 * live corpus, a nitter title averages 334 characters (longest 24,776) and all
 * 729 Bluesky titles are empty. So the raw field is unbounded for one source
 * class and absent for another, and neither renders as a scannable anchor.
 *
 * <p><b>Order is load-bearing: flatten &rarr; sanitize &rarr; truncate.</b>
 * Flattening runs FIRST so the sanitizer inspects exactly the bytes that are
 * delivered. It cannot run after: {@link LlmOutputSanitizer}'s token separators
 * are the ASCII whitespace set only, and its canonical form (NFKC +
 * {@code stripBidiAndZeroWidth}) leaves U+0085, U+2028 and U+2029 intact, so
 * {@code /quarantine<U+2028>approve} reaches a post-sanitize rewrite as ONE
 * token — no closed-list match, no {@code LLM_OUTPUT_SANITIZED} row — and the
 * rewrite would then hand the group a dispatchable privileged command at a line
 * start. (Redteam 2026-07-30, medium/INJECTION.)
 * {@link LlmOutputSanitizer#sanitize} then runs over the WHOLE remaining field,
 * because every hit is audit-logged (rows aggregate per distinct token per
 * call, carrying the exact occurrence count) — truncating first would
 * silently drop the audit coverage for the removed tail and shrink what the
 * sanitizer sees. {@link #truncate} is therefore last.
 *
 * <p><b>Sanitize unit: ONE author's field per call (M1-697).</b> The helper
 * selects title OR body and sanitizes that single field. It must never
 * concatenate the two, because the flag-bearing closed-list entries delete the
 * span from command word to flag token, so a widened input lets a command word
 * in one field and a flag in another erase everything between them.
 */
public final class DisplayHeadline {

    /**
     * Display bound, in {@code char}s. 200 keeps essentially every RSS headline
     * whole (corpus average 74) while cutting the social sources down to a
     * scannable anchor (nitter average 334). A constant rather than a config
     * key on purpose: {@code ClusterBlockRenderer} is hand-constructed by two
     * command handlers, so a {@code @ConfigProperty} would have to be threaded
     * through both.
     */
    static final int MAX_LENGTH = 200;

    /** Appended only when something was actually cut. */
    static final String ELLIPSIS = "…";

    /**
     * Ceiling on how much of a body fallback reaches the sanitizer.
     * {@code post.title} is capped at {@code IngestTextNormalizer
     * .TITLE_MAX_LENGTH} (200) at the write boundary, but {@code post.body} has
     * no cap at any write path, and {@link LlmOutputSanitizer#sanitize} runs
     * NFKC, a markdown-link regex, 24 closed-list matchers and 10 tokenizer
     * scans over whatever it is handed — once per post, on the digest
     * scheduler thread. Bounding the body first restores the cost profile the
     * sanitizer's linearity argument assumes. (Redteam 2026-07-30, low/DOS.)
     *
     * <p>Generous relative to {@link #MAX_LENGTH} on purpose: this is a
     * runaway-input guard, not the display cut. At 10x the display bound, any
     * flagged span remotely near the visible region is still matched and still
     * audited, so the sanitize-then-truncate rule keeps governing what the
     * reader sees.
     *
     * <p>Public because a caller may need to bound the body BEFORE it reaches
     * Java at all. This guard runs after the column is materialised, which is
     * fine for one post but not for a paginated read: {@code /saved} lists 20
     * rows per page, so it repeats the bound in its {@code SELECT} and has to
     * name the same number here to keep the two from drifting. (M1-730,
     * redteam 2026-07-30 medium/DOS.)
     */
    public static final int BODY_SCAN_LIMIT = MAX_LENGTH * 10;

    private DisplayHeadline() {
    }

    /**
     * The post's display headline: its title when that has text, else its body,
     * sanitized, flattened to one line and bounded.
     *
     * @return the headline, or the empty string when the post carries no
     *         renderable text at all. Callers MUST then omit the headline
     *         token together with whatever separator would have followed it —
     *         no placeholder is invented here, because a stand-in like
     *         "(untitled)" conveys nothing the omission does not while adding
     *         an untranslated token to a line the scope's language governs.
     */
    public static String of(Post post, LlmOutputSanitizer llmOutputSanitizer) {
        // post.body() is nullable in the DDL (`body TEXT`) even though the
        // record's type is not marked, which is why it may cross into the
        // @Nullable parameter below. 728 of the 729 empty-title Bluesky posts
        // resolve through that body branch.
        return of(post.title(), post.body(), llmOutputSanitizer);
    }

    /**
     * The same headline for a caller holding a title/body pair rather than an
     * {@link Post} — {@code /saved} renders its own {@code saved_post}
     * snapshot content columns and never re-resolves CONTENT against
     * {@code post} (only the row's visibility consults {@code post.status},
     * inside the handler's SELECT), so it has no {@link Post} to hand over.
     * Both entry points run the identical derivation, which is the whole
     * point of sharing this class: {@code /saved} cannot drift from the
     * three surfaces M1-729 fixed. (M1-730.)
     *
     * @param body the snapshot body, nullable — {@code saved_post.body} and
     *             {@code post.body} are both nullable in the DDL
     * @return as {@link #of(Post, LlmOutputSanitizer)}: the empty string when
     *         neither field carries renderable text, and the caller must then
     *         omit the headline token together with its separator
     */
    public static String of(String title, @Nullable String body,
                            LlmOutputSanitizer llmOutputSanitizer) {
        String source = headlineSource(title, body);
        if (source.isEmpty()) {
            return "";
        }
        return truncate(llmOutputSanitizer.sanitize(flattenToOneLine(source)));
    }

    /**
     * Title when it carries text, else body, else empty. A blank field is
     * skipped rather than sanitized: a closed-list entry always begins with
     * {@code /}, so whitespace alone can never produce a hit, and skipping it
     * therefore costs no audit row.
     *
     * <p>{@link IngestTextNormalizer#UNTITLED_TITLE} counts as "no title"
     * alongside blank. The ingest write path substitutes that sentinel for a
     * titleless-by-design source to satisfy the {@code NOT NULL} column, so
     * matching only on blank would leave the fallback dead for every row
     * written since M1-693 — the sentinel is not blank. Exact equality, not a
     * contains or case-insensitive test: only the byte-exact value is the
     * storage placeholder, and a real title that merely mentions the word must
     * still render as itself. (M1-729.)
     */
    private static String headlineSource(String title, @Nullable String body) {
        if (!title.isBlank() && !IngestTextNormalizer.UNTITLED_TITLE.equals(title)) {
            return title;
        }
        if (body == null || body.isBlank()) {
            return "";
        }
        // Unlike the title, the body has no write-boundary length cap, so it is
        // bounded here BEFORE the sanitizer is handed it. See BODY_SCAN_LIMIT.
        return boundForScan(body);
    }

    /**
     * Bound an unbounded operand at {@link #BODY_SCAN_LIMIT} before it
     * reaches the sanitizer — the {@code BODY_SCAN_LIMIT} runaway-input
     * guard as a reusable cut (code-point-safe like {@link #truncate}'s).
     */
    private static String boundForScan(String text) {
        return text.length() <= BODY_SCAN_LIMIT
                ? text
                : text.substring(0, codePointSafeCut(text, BODY_SCAN_LIMIT));
    }

    /**
     * Prepare an LLM-authored headline replacement for a headline slot:
     * bound at {@link #BODY_SCAN_LIMIT}, flatten to one line, then
     * sanitize — the same order, and the same reasons, as
     * {@link #of(String, String, LlmOutputSanitizer)}. The display-hit
     * translation leg (M1-747) hands the translator's reply here; the
     * reply is LLM output bounded only by the provider's 1-8 MiB body cap,
     * so the pre-bound keeps a hostile endpoint's in-cap answer from
     * buying megabytes of NFKC and closed-list scanning.
     *
     * <p><b>This composite exists so the order cannot be gotten wrong.</b>
     * The three steps are private precisely because applying
     * {@code flattenToOneLine} AFTER {@link LlmOutputSanitizer#sanitize}
     * re-creates the line-boundary smuggling hazard documented on this
     * class — a finding twice over (2026-07-30 on the body operand;
     * 2026-08-03 round 1 on the display-hit cache-read path). A caller
     * that cannot reach the primitives cannot sequence them wrongly, which
     * is the structural standard {@code docs/spec/security.md} §"The
     * chokepoint routing is build-guarded" sets for this kind of
     * invariant. Truncation is deliberately NOT part of this composite:
     * the display-hit leg caches the sanitized form and cuts afterwards,
     * and {@link #truncate} can only drop a suffix, so it is safe to apply
     * on its own. (Redteam 2026-08-03 round 2, low/INJECTION.)
     *
     * @return the bounded, one-line, sanitized headline — NOT truncated to
     *         {@link #MAX_LENGTH}; the caller applies {@link #truncate}
     */
    public static String prepareTranslatedHeadline(String translated,
                                                   LlmOutputSanitizer llmOutputSanitizer) {
        return llmOutputSanitizer.sanitize(flattenToOneLine(boundForScan(translated)));
    }

    /**
     * Collapse every whitespace run to a single space so the headline cannot
     * inject extra lines into the block that contains it. {@code \R} is matched
     * alongside {@code \s} to catch the Unicode line boundaries {@code \s}
     * misses (U+0085, U+2028, U+2029) — a group-broadcast line start is exactly
     * where a smuggled break would do damage, and those three are also the code
     * points the sanitizer does not count as token separators, which is why
     * this runs BEFORE it rather than after.
     *
     * <p>Runs are REPLACED with a space, never deleted. Deletion would splice
     * {@code /list} and {@code -sources} into a {@code /list-sources} the author
     * never wrote; because this now runs ahead of the sanitizer that token
     * would be caught and redacted rather than leaked, but fabricating it in
     * the first place corrupts the headline and emits a spurious audit row.
     *
     * <p>PRIVATE, and load-bearingly so: reachable only through the two
     * entry points that sequence it before the sanitizer
     * ({@link #of(String, String, LlmOutputSanitizer)} and
     * {@link #prepareTranslatedHeadline}), so no caller can apply it to an
     * already-sanitized value.
     */
    private static String flattenToOneLine(String source) {
        return source.replaceAll("(?:\\R|\\s)+", " ").strip();
    }

    /**
     * Opening literal of the Stage 1 redaction placeholder
     * {@code [REDACTED:<id>]}. {@code docs/spec/security.md} §Ingest pipeline
     * commits the brackets and the {@code REDACTED:} literal as byte-identical
     * "so user-facing prose, snapshot bodies, and tests recognise the marker by
     * exact-match", so the display cut must not be able to break that shape.
     * The {@code <id>} token is per-row random, hence prefix-matching rather
     * than a whole-marker constant. Reachable because Stage 1 redactions live
     * in the body, which this helper can promote to a headline.
     * (Redteam 2026-07-30, out-of-model.)
     */
    private static final String STAGE1_REDACTION_PREFIX = "[REDACTED:";

    /**
     * Bound the headline at {@link #MAX_LENGTH}, appending {@link #ELLIPSIS}
     * only when something was cut. Three boundaries are respected: a cut may
     * not land inside a {@link LlmOutputSanitizer#REDACTED_COMMAND_REPLACEMENT}
     * marker nor inside a {@link #STAGE1_REDACTION_PREFIX} placeholder (a
     * half-emitted {@code [redacted comm} or {@code [REDACTED:9f} reads as
     * content rather than as a redaction), and a cut may not split a surrogate
     * pair (287 of 1,868 nitter titles carry emoji, so an astral-plane
     * character at the boundary is routine).
     *
     * <p>Public because the display-hit translation leg (M1-747) re-bounds
     * the translated headline with this same cut — a translation of a
     * MAX_LENGTH input legitimately runs longer, and a second truncation
     * implementation would lose the marker-safety arguments above.
     */
    public static String truncate(String headline) {
        if (headline.length() <= MAX_LENGTH) {
            return headline;
        }
        int cut = MAX_LENGTH;
        cut = Math.min(cut, markerSafeCut(headline, cut,
                LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT,
                LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT.length()));
        // The Stage 1 placeholder's own length is not fixed (per-row random
        // id), so the closing bracket AFTER the cut is what proves the cut
        // landed inside one.
        cut = Math.min(cut, stage1PlaceholderSafeCut(headline, cut));
        return headline.substring(0, codePointSafeCut(headline, cut))
                .stripTrailing() + ELLIPSIS;
    }

    /**
     * Pull {@code cut} back to the start of a {@code marker} occurrence that
     * straddles it, or leave it alone.
     */
    private static int markerSafeCut(String headline, int cut, String marker, int markerLength) {
        int start = headline.lastIndexOf(marker, cut - 1);
        return start >= 0 && start + markerLength > cut ? start : cut;
    }

    /**
     * Pull {@code cut} back to the start of a {@code [REDACTED:<id>]}
     * placeholder that straddles it. The placeholder is variable-length, so a
     * straddle is detected by an opener at or before the cut whose matching
     * {@code ]} falls at or after it.
     */
    private static int stage1PlaceholderSafeCut(String headline, int cut) {
        int start = headline.lastIndexOf(STAGE1_REDACTION_PREFIX, cut - 1);
        if (start < 0) {
            return cut;
        }
        int close = headline.indexOf(']', start + STAGE1_REDACTION_PREFIX.length());
        return close < 0 || close >= cut ? start : cut;
    }

    /** Back {@code cut} off by one when it would split a surrogate pair. */
    private static int codePointSafeCut(String text, int cut) {
        return cut > 0 && Character.isHighSurrogate(text.charAt(cut - 1)) ? cut - 1 : cut;
    }
}
