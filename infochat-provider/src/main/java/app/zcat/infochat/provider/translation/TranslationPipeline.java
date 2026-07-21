package app.zcat.infochat.provider.translation;

import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.messaging.TranslationProvider;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Ordered pipeline per {@code docs/spec/llm.md} §Pipeline order
 * (delivery direction) for LLM-authored output. Steps:
 * <ol>
 *   <li>en-short-circuit (skip everything when scope is English)</li>
 *   <li>Cache lookup (hit returns cached post-sanitizer-2 value
 *       — skips BOTH translator and sanitizer-2)</li>
 *   <li>Translator call via {@link TranslationProvider}</li>
 *   <li>Sanitizer-2 pass via {@link LlmOutputSanitizer}</li>
 *   <li>Cache write: {@code (text, lang) → sanitized}</li>
 *   <li>Return sanitized translated text</li>
 * </ol>
 *
 * <p>The pipeline has no degraded-branch awareness: the {@code
 * cp.degraded()} gate is the caller's responsibility (e.g.
 * {@code SummaryCommandHandler.appendClusterBlock}). Callers
 * MUST NOT pass degraded/bundle-sourced prose into this pipeline
 * — that would violate the D43 bundle-not-translator invariant.
 */
@ApplicationScoped
public class TranslationPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(TranslationPipeline.class);

    @Inject
    TranslationCache translationCache;

    @Inject
    TranslationProvider translationProvider;

    @Inject
    LlmOutputSanitizer llmOutputSanitizer;

    @Inject
    BundleLoader bundleLoader;

    /**
     * Translate post-sanitizer-1 English text into the scope's
     * language, running sanitizer-2 on the translator's output.
     *
     * @param postSanitizer1English  LLM-authored prose after
     *     sanitizer-1; never null.
     * @param scopeLanguage  ISO 639-1 code from
     *     {@code scope_preferences.language}; never null.
     * @return the translated, sanitized text; or, on any reachable
     *     fallback condition (provider error, blank output, or output
     *     identical to the input), the English text plus a one-line
     *     localized note. Never null.
     */
    public String run(String postSanitizer1English,
                               String scopeLanguage) {
        // Step 1: en-short-circuit — the TranslationProvider is never
        // invoked, the cache is never consulted, the sanitizer-2 pass
        // is skipped (spec §Translation flow line 199).
        if (scopeLanguage.equalsIgnoreCase("en")) {
            return postSanitizer1English;
        }

        // Step 2: cache lookup — a hit returns the cached post-sanitizer-2
        // value immediately, short-circuiting both the translator call
        // AND the sanitizer-2 pass (spec §Pipeline order lines 263-264).
        var cached = translationCache.get(postSanitizer1English, scopeLanguage);
        if (cached.isPresent()) {
            return cached.get();
        }

        // Step 3: translator call. On failure (condition a), fall back to
        // the English text with a one-line note — the cs-scope user sees
        // English, which is the safer of the two failure modes (spec
        // §Per-task routing rules "No fallback chain").
        String translated;
        try {
            translated = translationProvider.translate(
                    postSanitizer1English,
                    Locale.ENGLISH,
                    Locale.of(scopeLanguage));
        } catch (RuntimeException e) {
            SafeLog.warn(LOG, "TranslationPipeline: translator failed for target_language=" + scopeLanguage
                    + "; falling back to post-sanitizer-1 English text with a note", e);
            return fallbackToEnglish(postSanitizer1English, scopeLanguage);
        }

        // Step 3.5: sanity-check the translator output before sanitizer-2
        // (spec §Failure handling conditions b and c). (c) blank output and
        // (b) output byte-identical to the English input both mean the
        // translator produced nothing usable; fall back to English + note
        // rather than deliver an empty or untranslated message. Condition (d)
        // (zero target-script characters) is unreachable in v1 — cs is the
        // only non-English language and is Latin-script — so it is not built.
        if (translated.isBlank() || translated.equals(postSanitizer1English)) {
            LOG.warn("TranslationPipeline: translator returned unusable output for "
                    + "target_language={} (blank or identical to input); falling back "
                    + "to English with a note", scopeLanguage);
            return fallbackToEnglish(postSanitizer1English, scopeLanguage);
        }

        // Step 4: sanitizer-2 — the translator is itself an LLM and may
        // emit admin-command-shaped strings; each match writes one
        // audit_log row per the M1-041 per-occurrence durability commitment.
        String sanitized = llmOutputSanitizer.sanitize(translated);

        // Step 5: cache write — the cached value is the post-sanitizer-2
        // form so hits skip step 4 (spec §Pipeline order step 5).
        // NOT populated on the failure path (the cache stores translated
        // forms only).
        translationCache.put(postSanitizer1English, scopeLanguage, sanitized);

        return sanitized;
    }

    /**
     * Shared fallback for all three reachable failure conditions (a/b/c):
     * return the post-sanitizer-1 English text with a one-line note,
     * resolved from the localization bundle in the scope language (D43) so
     * the user is told why the reply is in English. The note carries no
     * interpolated user content. The cache is NOT written on this path —
     * the cache stores translated forms only (spec §Pipeline order step 5).
     */
    private String fallbackToEnglish(String postSanitizer1English, String scopeLanguage) {
        String note = bundleLoader.get(BundleKeys.REPLY_TRANSLATION_UNAVAILABLE, scopeLanguage);
        return postSanitizer1English + "\n" + note;
    }
}
