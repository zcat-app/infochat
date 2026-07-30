package app.zcat.infochat.provider.render;

import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;

/**
 * Derives the bounded one-line headline that labels a post in the three
 * user-visible render surfaces ({@code ClusterBlockRenderer}, {@code
 * SummaryProseGenerator.degradedProseFor}, {@code DegradedDigestRenderer}).
 * Sharing one derivation is what keeps those three from drifting.
 *
 * <p><b>Not for prompt input.</b> The summarizer prompt
 * ({@code SummaryProseGenerator.buildPrompt}) and the rollup prompt
 * ({@code CategoryRollupGenerator.buildPrompt}) must keep appending the FULL
 * untruncated title — feeding a bounded headline to either would have the
 * model summarize a fragment, which produces no error and no failing test.
 * This helper is display-only.
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
 * because it writes one {@code audit_log} row per hit — truncating first would
 * silently drop the audit rows for the removed tail and shrink what the
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
     */
    static final int BODY_SCAN_LIMIT = MAX_LENGTH * 10;

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
        String source = headlineSource(post);
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
     */
    private static String headlineSource(Post post) {
        String title = post.title();
        if (!title.isBlank()) {
            return title;
        }
        // Nullable in the DDL (`body TEXT`) even though the record's type is
        // not marked — 728 of the 729 empty-title Bluesky posts resolve here.
        String body = post.body();
        if (body == null || body.isBlank()) {
            return "";
        }
        // Unlike the title, the body has no write-boundary length cap, so it is
        // bounded here BEFORE the sanitizer is handed it. See BODY_SCAN_LIMIT.
        return body.length() <= BODY_SCAN_LIMIT
                ? body
                : body.substring(0, codePointSafeCut(body, BODY_SCAN_LIMIT));
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
     */
    private static String truncate(String headline) {
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
