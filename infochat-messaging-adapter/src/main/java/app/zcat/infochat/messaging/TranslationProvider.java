package app.zcat.infochat.messaging;


import java.util.Locale;

/**
 * Presentation-layer translator ({@code docs/spec/llm.md} §Translation
 * flow) into the user scope's configured language. Two kinds of string
 * reach it, and the second is easy to miss:
 * <ul>
 *   <li>the LLM-authored prose the bot itself produces — cluster
 *       summaries, chat replies, digest headers, {@code /retry}
 *       re-rolls;</li>
 *   <li><strong>display headlines, which are SOURCE-authored</strong> —
 *       the D29 display-leg amendment (2026-08-04) routes a retrieved
 *       post's headline through here so a cross-language hit is legible
 *       to the reader. That headline is the post's own {@code title}, or
 *       a bounded prefix of its {@code body} when the title is empty
 *       (see {@code DisplayHeadline}).</li>
 * </ul>
 *
 * <h2>Out-of-scope, by design</h2>
 * <ul>
 *   <li><strong>The stored post row is never rewritten.</strong> That is
 *       the whole of D29's guarantee, and it is a claim about STORAGE,
 *       not about the render: ingest translates a non-English post into
 *       a SEPARATE derived English field and leaves {@code post.body} /
 *       {@code post.title} byte-identical. Do not restate it as "source
 *       post bodies are never translated" — that stronger form is false
 *       on both live paths (ingest and the display leg above) and was
 *       propagated from this javadoc into four reader-facing documents
 *       before M1-758 caught it.</li>
 *   <li><strong>Deterministic strings do NOT route through this
 *       SPI.</strong> The localization-bundle path (decision D43) is
 *       a separate code path: {@code /help} text, friendly-error
 *       templates, progress-notifier stage strings, the banned-user
 *       fixed reply, and any other static UI string come from a
 *       resource bundle by key. Mixing the two paths is forbidden —
 *       sending a bundle key through {@link #translate} would
 *       non-deterministically re-translate text the bundle already
 *       owns, and routing free-form LLM prose through bundle lookup
 *       would lose the per-call meaning. The next reader looking at
 *       a string should ask "is this LLM-authored prose, or a
 *       template key?" and pick the right path.</li>
 * </ul>
 */
public interface TranslationProvider {

    /**
     * Translate one piece of LLM-authored prose.
     *
     * @param text   the source text, in the {@code from} language;
     *               never null.
     * @param from   the source language (the language the LLM
     *               produced the text in — typically the bot's
     *               internal working language); never null.
     * @param to     the destination language (the user scope's
     *               configured language); never null. When equal to
     *               {@code from} the implementation MAY return
     *               {@code text} unchanged.
     * @return the translated text in the {@code to} language; never
     *         null.
     */
    String translate(String text, Locale from, Locale to);
}
