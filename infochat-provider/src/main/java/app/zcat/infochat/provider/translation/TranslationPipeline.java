package app.zcat.infochat.provider.translation;

import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.messaging.TranslationProvider;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.bundle.LanguageRegistry;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.render.DisplayHeadline;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.Character.UnicodeScript;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

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

    /**
     * Conservative ISO-639 shape for a declared source language: 2-3 ASCII
     * letters. {@code source.language} is an unvalidated TEXT column and
     * {@code Locale.of} accepts anything ({@code Locale.of("{{id}}")
     * .getDisplayLanguage} echoes the garbage verbatim), while the display
     * name lands in the translator prompt's INSTRUCTION region — so a
     * non-conforming value must never reach a {@code Locale.of} call.
     * Unreachable today (nothing writes the column until M1-750); closed at
     * introduction. [redteam 2026-08-03, low/INJECTION]
     */
    private static final Pattern ISO_639_SHAPE = Pattern.compile("[A-Za-z]{2,3}");

    /**
     * Display-hit cache value recording that condition (d) rejected this
     * headline's translation, so later renders converge instead of
     * re-calling the translator. It occupies the SAME key a translation
     * would, which is what lets the callers that decide a row is free by
     * probing that key ({@code SavedCommandHandler},
     * {@code DigestRenderer}) inherit the fix unchanged.
     *
     * <p>UNFORGEABLE by the translator, which is what makes an in-band
     * marker safe here: every value this leg writes has passed
     * {@link DisplayHeadline#prepareTranslatedHeadline}, whose flatten
     * collapses each {@code (?:\R|\s)+} run to one space, and nothing
     * downstream can reintroduce a line separator — the sanitizer emits
     * only fixed literals and text recomposed from already-flattened
     * input, and NFKC has no mapping that produces one. A value carrying
     * U+000A therefore cannot arise from translator output, however that
     * output is steered. The leading newline is the whole guarantee; the
     * trailing text is for a human reading a cache dump.
     *
     * <p>UNRENDERABLE: the cache-read path returns the fallback before
     * {@link #finishDisplayHit}, so this can never be truncated, bracketed
     * or delivered.
     *
     * <p>Expires with the entry it replaces — one 24h TTL for the whole
     * cache — so a rejection is not retried for up to a day. Accepted
     * deliberately: a (d) failure is a SUCCESSFUL call returning unusable
     * content, so retrying the same prompt against a temperature-0 model
     * buys little, and a provider that is actually down throws instead,
     * taking the condition-(a) path, which records nothing.
     */
    private static final String REJECTED_BY_TARGET_SCRIPT_CHECK =
            "\ntarget-script check rejected this translation";

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
     *     fallback condition (provider error, blank output, output
     *     identical to the input, or — for a non-Latin target — output
     *     carrying no character of the target script), the English text
     *     plus a one-line localized note. Never null.
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
            return fallbackWithNote(postSanitizer1English, scopeLanguage);
        }

        // Step 3.5: sanity-check the translator output before sanitizer-2
        // (spec §Failure handling conditions b and c). (c) blank output and
        // (b) output byte-identical to the English input both mean the
        // translator produced nothing usable; fall back to English + note
        // rather than deliver an empty or untranslated message.
        if (translated.isBlank() || translated.equals(postSanitizer1English)) {
            LOG.warn("TranslationPipeline: translator returned unusable output for "
                    + "target_language={} (blank or identical to input); falling back "
                    + "to English with a note", scopeLanguage);
            return fallbackWithNote(postSanitizer1English, scopeLanguage);
        }

        // Condition (d): for a non-Latin target, output carrying zero
        // target-script characters. This is the failure (b) and (c) cannot
        // see — a translator that answers a ru-scope request in English
        // returns prose that is neither blank nor byte-identical to the
        // input, so without this check the user is delivered untranslated
        // English with no "(translation unavailable)" note.
        UnicodeScript missingScript = missingTargetScript(translated, scopeLanguage);
        if (missingScript != null) {
            LOG.warn("TranslationPipeline: translator returned no {} characters for "
                    + "target_language={}; falling back to English with a note",
                    missingScript, scopeLanguage);
            return fallbackWithNote(postSanitizer1English, scopeLanguage);
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
     * Condition (d) of {@code llm.md} §Failure handling, shared by both
     * legs: the script the target language's prose is written in, when
     * {@code text} carries none of it — or null when the condition does
     * not apply, i.e. the target declares no script, the target is Latin,
     * or the script is present.
     *
     * <p>Scoped to non-Latin targets by the spec itself ("for non-Latin
     * target scripts the output contains zero target-script characters;
     * for Latin target scripts the output is byte-identical to the
     * input"): against a Latin target the spec's own form of the
     * condition is byte identity, which is condition (b) on the prose leg
     * and, on the display-hit leg, deliberately not a failure at all. The
     * expected script is declared per language in
     * {@link LanguageRegistry}, so a fourth script needs no edit here.</p>
     */
    private static @Nullable UnicodeScript missingTargetScript(String text, String scopeLanguage) {
        UnicodeScript targetScript = LanguageRegistry.scriptOf(scopeLanguage).orElse(null);
        if (targetScript == null
                || targetScript == UnicodeScript.LATIN
                || containsScript(text, targetScript)) {
            return null;
        }
        return targetScript;
    }

    /**
     * Whether {@code text} carries at least one character of
     * {@code script}. Code points, not chars, so a supplementary-plane
     * target script is read as one character rather than two unpaired
     * surrogates of script UNKNOWN.
     *
     * <p>One character is the whole threshold ("contains zero
     * target-script characters" is the spec's wording): a proportion
     * would misfire on legitimate prose, whose proper nouns, URLs,
     * numbers and command tokens stay Latin in every target language.</p>
     */
    private static boolean containsScript(String text, UnicodeScript script) {
        return text.codePoints().anyMatch(codePoint -> UnicodeScript.of(codePoint) == script);
    }

    /**
     * Display-hit leg (M1-747): translate a retrieved post's rendered
     * headline — the {@link DisplayHeadline} output, already flattened,
     * sanitized and truncated — into the scope language for display. The
     * ORIGINAL stored fields are never touched; the result is ephemeral.
     *
     * <p>No-op legs, each returning the input with no provider call:
     * {@code en} scope (same guard as {@link #run}), {@code null} source
     * language ("unknown — never translate": the language is declared per
     * source, never inferred, D29), a source language that is not
     * ISO-639-shaped (see {@link #ISO_639_SHAPE} — treated exactly like
     * unknown), a hit already in the scope language, and an empty headline
     * (the renderer omits the line anyway).
     *
     * <p>Translating leg, order load-bearing: translator call → pre-bound
     * at {@link DisplayHeadline#BODY_SCAN_LIMIT} → flatten to one line →
     * sanitizer-2 → target-script check → cache write → truncate →
     * bracketed original line. The pre-bound runs
     * FIRST because the reply is otherwise bounded only by the provider's
     * 1-8 MiB body cap, and a hostile endpoint's in-cap reply must not buy
     * megabytes of NFKC + closed-list scanning before the 200-char display
     * cut (the same guard {@link DisplayHeadline} applies to the unbounded
     * body operand; redteam 2026-08-03, low/DOS). Flatten runs BEFORE
     * sanitizer-2 for the same reason {@link DisplayHeadline} flattens
     * before sanitizing: the sanitizer's token separators are ASCII-only
     * and its canonical form leaves U+0085/U+2028/U+2029 intact, so an
     * unflattened translator output could carry a line-boundary-smuggled
     * command past the closed list (the 2026-07-30 DisplayHeadline
     * finding, applied to this leg's LLM). Sanitize runs before truncate
     * so the audit sees the full output. The sanitize unit is ONE post's
     * headline per call (M1-697).
     *
     * <p>The CACHE-HIT path applies NO transform: truncate + bracketed
     * original only, or — for a stored
     * {@link #REJECTED_BY_TARGET_SCRIPT_CHECK} — the fallback with note
     * and nothing else. Every value this leg can read back was written by
     * this leg — hence already flattened AND sanitized — because
     * display-hit entries occupy a keyspace disjoint from the prose leg's
     * (see {@link #displayHitCacheLanguage}). A read-path rewrite of a
     * sanitized value is exactly the post-sanitize-rewrite ordering
     * {@link DisplayHeadline} forbids. [redteam 2026-08-03,
     * medium/INJECTION]
     *
     * @param displayHeadline  the rendered headline; never null (may be
     *     empty — the renderer's omission contract)
     * @param sourceLanguage  the post's declared source language, or null
     *     when unknown (hand-built {@code Post} fixtures, compat
     *     constructors)
     * @param scopeKind  the rendering scope's kind ({@code dm}/{@code
     *     group}) — a cache-partition dimension; never null
     * @param scopeId  the rendering scope's id — a cache-partition
     *     dimension; never null
     * @param scopeLanguage  ISO 639-1 code from
     *     {@code scope_preferences.language}; never null
     * @return the translated, sanitized, bounded headline with the original
     *     headline on a bracketed line beneath it; the input unchanged on
     *     any no-op leg (or when the translation is byte-identical to the
     *     input);
     *     or, on translator failure, blank output, or — for a non-Latin
     *     target — a differing translation carrying no character of the
     *     target script, the original headline plus the one-line
     *     unavailable note. Never null, never empty for a non-empty
     *     input.
     */
    public String runForDisplayHit(String displayHeadline,
                                   @Nullable String sourceLanguage,
                                   String scopeKind,
                                   UUID scopeId,
                                   String scopeLanguage) {
        if (scopeLanguage.equalsIgnoreCase("en")
                || sourceLanguage == null
                || !ISO_639_SHAPE.matcher(sourceLanguage).matches()
                || sourceLanguage.equalsIgnoreCase(scopeLanguage)
                || displayHeadline.isEmpty()) {
            return displayHeadline;
        }

        String cacheLanguage = displayHitCacheLanguage(scopeKind, scopeId, scopeLanguage);

        // Cache hit: deliver the stored bytes with NO transform — truncate
        // + bracketed original only, both applied OUTSIDE the cache. The disjoint,
        // per-scope keyspace guarantees the value was written by this leg,
        // i.e. flattened before sanitizer-2 ran, so there is nothing left
        // to rewrite — and rewriting here would be a rewrite AFTER
        // sanitization, the exact hazard the flatten-before-sanitize order
        // exists to prevent. [redteam 2026-08-03, medium/INJECTION]
        var cached = translationCache.get(displayHeadline, cacheLanguage);
        if (cached.isPresent()) {
            // A recorded condition-(d) rejection short-circuits to the same
            // fallback the rejecting render produced. Tested BEFORE
            // finishDisplayHit, which is what makes the sentinel
            // unrenderable: it can never be truncated, bracketed, or reach
            // a reader.
            return REJECTED_BY_TARGET_SCRIPT_CHECK.equals(cached.get())
                    ? fallbackWithNote(displayHeadline, scopeLanguage)
                    : finishDisplayHit(displayHeadline, cached.get());
        }

        String translated;
        try {
            translated = translationProvider.translate(
                    displayHeadline,
                    Locale.of(sourceLanguage),
                    Locale.of(scopeLanguage));
        } catch (RuntimeException e) {
            SafeLog.warn(LOG, "TranslationPipeline: display-hit translator failed for target_language="
                    + scopeLanguage + "; falling back to the original headline with a note", e);
            return fallbackWithNote(displayHeadline, scopeLanguage);
        }

        if (translated.isBlank()) {
            LOG.warn("TranslationPipeline: display-hit translator returned blank output for "
                    + "target_language={}; falling back to the original headline with a note",
                    scopeLanguage);
            return fallbackWithNote(displayHeadline, scopeLanguage);
        }

        // One call, not three: DisplayHeadline owns the bound → flatten →
        // sanitize order so this leg cannot sequence it wrongly.
        String sanitized = DisplayHeadline.prepareTranslatedHeadline(translated, llmOutputSanitizer);

        // Condition (d), threaded between two obligations this leg carries
        // and the prose leg does not (M1-761). It is evaluated on the
        // SANITIZED form and only for a translation that DIFFERS from the
        // input, because that is exactly the form and comparison
        // finishDisplayHit's passthrough uses: a headline that translates
        // to itself is a proper noun, not a refusal, so (d) must never see
        // it — placed ahead of that passthrough the check would refuse
        // precisely the headlines the passthrough exists to deliver. And
        // it runs BEFORE the cache write below rather than after, because
        // this leg writes the cache on the fresh path before rendering,
        // where run() reaches its write only once every check has passed;
        // after the write, a rejected translation would be served to every
        // subsequent render of the same headline for the whole TTL.
        if (!sanitized.equals(displayHeadline)) {
            UnicodeScript missingScript = missingTargetScript(sanitized, scopeLanguage);
            if (missingScript != null) {
                LOG.warn("TranslationPipeline: display-hit translator returned no {} characters "
                        + "for target_language={}; falling back to the original headline "
                        + "with a note", missingScript, scopeLanguage);
                // Record the REJECTION, never the rejected text. Without this
                // the leg never converges: /saved and /summary re-render the
                // same headline, so each render re-calls the translator and —
                // because both decide a row is free by probing this key
                // themselves — re-spends a per-render budget slot and the
                // per-user LLM bucket token security.md §Rate limiting says a
                // fully-converged page never draws. Same key as a translation
                // precisely so those probes need no edit. The digest leg pays
                // nothing either way: it renders a post in one window only.
                // [redteam 2026-08-04, low/DOS]
                translationCache.put(displayHeadline, cacheLanguage,
                        REJECTED_BY_TARGET_SCRIPT_CHECK);
                return fallbackWithNote(displayHeadline, scopeLanguage);
            }
        }

        // Cached even when byte-identical to the input (unlike run()'s
        // condition (b)): a short headline can translate to itself
        // legitimately — a proper noun is not a failure — and caching it
        // spares the translator call on every subsequent render.
        translationCache.put(displayHeadline, cacheLanguage, sanitized);
        return finishDisplayHit(displayHeadline, sanitized);
    }

    /**
     * The display-hit leg's cache-key language dimension:
     * {@code hit/<scopeKind>/<scopeId>/<scopeLanguage>}. Riding the
     * partition on the language dimension (rather than the hashed-text
     * dimension) keeps the {@link TranslationCache} bean, TTL and size
     * bound shared while making both required key properties structural
     * [redteam 2026-08-03, medium/INJECTION + low/INFO-LEAK]:
     *
     * <p>DISJOINT from the prose leg — {@link #run} stores
     * sanitize(translated) UNFLATTENED, so a shared keyspace would let
     * this leg read back a value carrying U+2028 into a headline position.
     * The language dimension is system-controlled on every leg: the prose
     * leg passes a bare {@code scope_preferences.language} code, which the
     * {@code /lang} handler only ever writes from the
     * {@code LanguageRegistry} closed set — no attacker-reachable input
     * can produce a code starting with {@code hit/}, so no prose entry can
     * ever land in this keyspace (unlike a prefix on the hashed TEXT
     * dimension, whose other-leg input is LLM-authored prose an attacker
     * can steer byte-by-byte).
     *
     * <p>PARTITIONED per (scope_kind, scope_id) — {@code security.md}
     * §"What's intentionally NOT in v1" accepts the cross-scope cache
     * timing side-channel expressly because cached strings are bot
     * presentation prose, "not user-authored content"; feed-authored
     * headlines are, so they never share entries across scopes.
     *
     * <p>Public so the /saved leg (M1-755) can probe the REAL composition
     * before drawing from its per-page translator budget — a re-derived
     * copy in the handler could drift from the implementation.
     */
    public static String displayHitCacheLanguage(String scopeKind, UUID scopeId, String scopeLanguage) {
        return "hit/" + scopeKind + "/" + scopeId + "/" + scopeLanguage;
    }

    /**
     * Shared tail of the display-hit fresh and cached paths: bound the
     * flattened, sanitized translation at {@code DisplayHeadline.MAX_LENGTH},
     * then put the ORIGINAL headline on a bracketed line beneath it
     * (docs/spec/llm.md §D29 display leg). The bracket implies one
     * direction only: a bracketed line means the line above it is this
     * leg's translation. The converse — an unbracketed line means the text
     * is already in the reader's language — describes the D29 TARGET
     * render (M1-759), not this leg: {@link #runForDisplayHit}'s entry
     * guard returns the input untouched when the source language is absent
     * or malformed, and a caller whose per-render translation budget is
     * spent skips this leg entirely, both rendering source-language text
     * with no bracket. The original is the leg's input — already bounded,
     * flattened and sanitized by
     * {@link DisplayHeadline} — so the brackets wrap publisher text that
     * needs no further transform, and no bundle-resolved label is involved:
     * the bracketed line carries no claim about who produced the
     * translation. A translation byte-identical to the input is delivered
     * unchanged and unbracketed — there is nothing to attribute.
     */
    private String finishDisplayHit(String displayHeadline,
                                    String flattenedSanitized) {
        if (flattenedSanitized.equals(displayHeadline)) {
            return displayHeadline;
        }
        String bounded = DisplayHeadline.truncate(flattenedSanitized);
        return bounded + "\n[" + displayHeadline + "]";
    }

    /**
     * Shared fallback for every reachable failure condition on both legs
     * (prose a/b/c/d, display-hit provider-error/blank/d): return the
     * original text — post-sanitizer-1 English for prose, the untranslated
     * headline for a display hit — with a one-line note, resolved from the
     * localization bundle in the scope language (D43) so the user is told
     * why the text is not in their language. The note carries no
     * interpolated user content. This method never writes the cache, and
     * no fallback stores a translated form (spec §Pipeline order step 5);
     * the display-hit (d) caller separately records
     * {@link #REJECTED_BY_TARGET_SCRIPT_CHECK} before calling in, which is
     * what lets that leg converge rather than re-translate every render.
     */
    private String fallbackWithNote(String originalText, String scopeLanguage) {
        String note = bundleLoader.get(BundleKeys.REPLY_TRANSLATION_UNAVAILABLE, scopeLanguage);
        return originalText + "\n" + note;
    }
}
