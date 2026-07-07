package app.zcat.infochat.collector.eval;

/**
 * Pre-parse normalizer shared by the eval-pipeline JSON parsers
 * ({@link app.zcat.infochat.collector.eval.entity.EntityExtractorWorker}
 * and {@link app.zcat.infochat.collector.eval.tagger.TaggerWorker}).
 *
 * <p>Some providers wrap a structured JSON reply in a markdown code
 * fence on a fraction of calls (DeepSeek did so on ~60% of
 * entity-extraction calls at the default temperature, observed live
 * 2026-07-07 — M1-586), for example:
 * <pre>
 * ```json
 * [{"text": "CISA", "type": "org"}]
 * ```
 * </pre>
 * The strict {@code objectMapper.readTree} parsers reject the fence
 * wrapper even when the JSON inside is valid, so a recoverable reply
 * degrades to the D22 failure path (entity release-without-entities /
 * tagger schema-violating). {@link #stripCodeFence} removes a single
 * enclosing fence before the parse.
 *
 * <p>The strip can never make a genuinely-bad reply pass: it only
 * unwraps a fence; the inner text must still parse as the expected JSON
 * shape or the caller returns {@code null} exactly as before. A reply
 * with no enclosing fence is returned byte-for-byte unchanged, so the
 * non-fenced path — including the D22 degrade — is untouched.
 */
public final class LlmJson {

    private static final String FENCE = "```";

    private LlmJson() {
    }

    /**
     * Strip a single enclosing markdown code fence and return the inner
     * text, or return {@code text} unchanged when it is not fenced.
     *
     * <p>A fence is recognized only when the whitespace-trimmed reply
     * both starts with {@code ```} (optionally followed by a language
     * token such as {@code json}/{@code JSON} on the opener line) and
     * ends with a matching {@code ```}. Anything else — no fence, an
     * opener with no closer (a truncated reply), or backticks that
     * merely appear mid-content — is returned unchanged. The recovered
     * inner text is itself whitespace-trimmed.
     */
    public static String stripCodeFence(String text) {
        String trimmed = text.strip();
        if (!trimmed.startsWith(FENCE) || !trimmed.endsWith(FENCE)) {
            return text;
        }
        int openerLineEnd = trimmed.indexOf('\n');
        if (openerLineEnd < 0) {
            // Opener and closer on one line (e.g. ```json``` with no body
            // line): there is no inner content to recover.
            return text;
        }
        int closingFence = trimmed.lastIndexOf(FENCE);
        if (closingFence <= openerLineEnd) {
            // The only fence marker is the opener; with no distinct
            // trailing fence the reply is not a well-formed fenced block.
            return text;
        }
        return trimmed.substring(openerLineEnd + 1, closingFence).strip();
    }
}
