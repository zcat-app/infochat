package app.zcat.infochat.messaging;

import java.util.Locale;

/**
 * Presentation-layer translator for bot-authored prose
 * ({@code docs/spec/llm.md} §Translation flow). Translates
 * <strong>only</strong> the LLM-authored strings the bot itself
 * produces — cluster summaries, chat replies, digest headers,
 * {@code /retry} re-rolls — into the user scope's configured
 * language.
 *
 * <h2>Out-of-scope, by design</h2>
 * <ul>
 *   <li><strong>Source post bodies are NEVER translated.</strong>
 *       Posts arrive in whatever language the source publishes;
 *       Provider surfaces them as-is.</li>
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
