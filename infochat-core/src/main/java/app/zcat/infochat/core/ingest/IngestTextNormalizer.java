package app.zcat.infochat.core.ingest;

/**
 * Deterministic obfuscation-codepoint stripping shared across the
 * ingest path and by the Provider's LLM output sanitizer
 * ({@code LlmOutputSanitizer.canonicalizeForMatching}, which composes
 * NFKC with {@link #stripBidiAndZeroWidth} to match the closed list on
 * the same representation the command parser consumes). Two related
 * operations live here so the bidi/zero-width codepoint list has
 * exactly one declaration:
 *
 * <ul>
 *   <li>{@link #stripBidiAndZeroWidth} — strips the bidi-control and
 *       zero-width codepoints only. Stage 1's {@code unicodeNormalize}
 *       calls this for the strip portion of its body normalization, so
 *       the body output stays byte-identical to the prior inline loop.
 *       Control characters are deliberately NOT removed: a body
 *       legitimately contains newlines/tabs, and stripping them would
 *       destroy its formatting.</li>
 *   <li>{@link #stripMetadataField} — strips bidi-control, zero-width,
 *       AND ISO control characters. Applied to the single-line
 *       {@code title} and {@code url} post fields at the persist
 *       boundary. These fields never legitimately carry a newline/tab,
 *       so removing control characters costs nothing and closes the
 *       obfuscation gap that body normalization already covers (a
 *       {@code U+202E} bidi override in a title, or an embedded newline
 *       in a bare-emitted url, can render a misleading extra line in a
 *       bot reply).</li>
 * </ul>
 *
 * <p>The codepoint set mirrors Stage 1's body strip
 * ({@code Stage1Pipeline.unicodeNormalize}); NFKC is intentionally not
 * applied here — each caller composes it where its own path needs it
 * (the body strip and the sanitizer do; {@link #stripMetadataField}
 * does not). Codepoints are compared as {@code int} hex literals (a
 * {@code char} widens to {@code int}) to keep the source free of
 * invisible bidi/zero-width characters.
 * See {@code docs/spec/security.md} §Ingest pipeline
 * (security side) and the 2026-06-23 deep-review finding
 * {@code 06-module-infochat-collector.md#F1}.
 */
public final class IngestTextNormalizer {

    private IngestTextNormalizer() {
    }

    /**
     * Strip bidi-control codepoints (U+061C, U+200E/U+200F,
     * U+202A..U+202E, U+2066..U+2069) and zero-width codepoints
     * (U+200B/U+200C/U+200D/U+FEFF). This is the single declaration of
     * that codepoint loop, reused by Stage 1's body normalization.
     * Control characters are NOT touched here — see the class javadoc.
     */
    public static String stripBidiAndZeroWidth(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // implicit directional marks U+061C (ALM), U+200E/U+200F
            // (LRM, RLM) — bidi controls NFKC does NOT remove
            if (c == 0x061C || c == 0x200E || c == 0x200F) {
                continue;
            }
            // bidi controls U+202A..U+202E (LRE, RLE, PDF, LRO, RLO)
            if (c >= 0x202A && c <= 0x202E) {
                continue;
            }
            // bidi isolates U+2066..U+2069 (LRI, RLI, FSI, PDI)
            if (c >= 0x2066 && c <= 0x2069) {
                continue;
            }
            // zero-width: U+200B (ZWSP), U+200C (ZWNJ), U+200D (ZWJ),
            // U+FEFF (BOM / ZWNBSP)
            if (c == 0x200B || c == 0x200C || c == 0x200D || c == 0xFEFF) {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Strip bidi-control, zero-width, ISO control characters AND the two
     * Unicode line/paragraph separators from a single-line metadata field
     * (post title / url). Composes {@link #stripBidiAndZeroWidth} with an
     * ISO-control pass ({@link Character#isISOControl}, covering
     * U+0000..U+001F and U+007F..U+009F) so the bidi/zero-width loop stays
     * singly declared.
     */
    public static String stripMetadataField(String text) {
        String stripped = stripBidiAndZeroWidth(text);
        StringBuilder out = new StringBuilder(stripped.length());
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (Character.isISOControl(c)) {
                continue;
            }
            // U+2028 (LINE SEPARATOR, Zl) and U+2029 (PARAGRAPH SEPARATOR,
            // Zp): UAX #14 mandatory line breaks that are NOT ISO control
            // characters, so the pass above misses them. A single-line
            // title/url never legitimately carries either, so removing them
            // closes the same extra-apparent-line obfuscation the control
            // strip covers. Deliberately NOT added to stripBidiAndZeroWidth
            // — the body legitimately spans lines and must keep them. (M1-435)
            if (c == 0x2028 || c == 0x2029) {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Maximum length, in UTF-16 {@code char} units, of a stored
     * {@code post.title}. Titles longer than this are stored truncated
     * with a trailing {@code "…"} so a single nitter tweet-as-title
     * (up to 280 chars) cannot become a paragraph-length bot headline.
     *
     * <p>200 matches the existing in-repo prose-summary cut at
     * {@code CompressCommandHandler.java:272}, is shorter than a
     * tweet's 280-char ceiling so the common nitter case actually
     * truncates, and exceeds the 31-char
     * {@code NitterFetcher.XCANCEL_PLACEHOLDER_TITLE} ({@code "RSS
     * reader not yet whitelisted!"}) so the D42 placeholder detection
     * at {@code NitterFetcher.java:86-87} is unaffected. Measured in
     * {@code char} units to match the existing in-repo length caps
     * ({@code CompressCommandHandler}, {@code NostrMessage}).
     */
    public static final int TITLE_MAX_LENGTH = 200;

    /**
     * The value stored in {@code post.title} when the upstream carries no
     * title at all. Bluesky and Nostr have no title concept and pass null;
     * an absent Reddit title arrives as {@code ""}; and {@code post.title}
     * is NOT NULL per V7, so something has to be written.
     *
     * <p>Declared here rather than at the write path because the value is a
     * two-party contract across module boundaries: whoever writes it and
     * whoever renders a post must agree on it byte-exactly, the writer to
     * store it and the reader to recognise it as "no title" so a body
     * fallback still fires. A literal duplicated across those modules drifts
     * silently — the reader's fallback stops firing with no failing test to
     * say so, which is precisely how M1-693 (which introduced the sentinel
     * at ingest) and M1-714 (which added the display-side body fallback,
     * conditioned on a blank title) collided. See M1-729.
     *
     * <p>A post whose upstream title is genuinely the word "untitled" is
     * indistinguishable from this sentinel and is treated as titleless. That
     * is accepted: such a post falls back to its body, which is a better
     * headline than the word itself.
     */
    public static final String UNTITLED_TITLE = "untitled";

    /**
     * Truncate a single-line metadata field to at most {@code maxLength}
     * UTF-16 {@code char} units, appending the {@code "…"} ellipsis
     * marker when truncation fires so the cut is visible to a reader.
     *
     * <p>The cut is surrogate-aware: if the boundary would land inside
     * a surrogate pair (the {@code char} before the cut is a high
     * surrogate), the cut backs off by one so a supplementary codepoint
     * is never split in two. This is the one correction to the
     * {@code char}-based {@code substring} cuts the existing in-repo
     * truncators use ({@code ThrottledAdminNotifier.sanitize},
     * {@code NostrMessage}); those split a surrogate pair at the
     * boundary, which a headline must not.
     *
     * <p>Callers must supply an already-stripped string — the cap runs
     * AFTER {@link #stripMetadataField} so a bidi/zero-width/control
     * strip can never be split mid-sequence by the cut (M1-693).
     */
    public static String truncateMetadataField(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        int keep = Math.max(0, maxLength - 1); // 1 == "…".length()
        if (keep > 0 && Character.isHighSurrogate(text.charAt(keep - 1))) {
            keep--;
        }
        return text.substring(0, keep) + "…";
    }
}
