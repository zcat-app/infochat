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

    /** What the display-hit leg did, so the renderer never has to guess from bytes. */
    public enum DisplayHitOutcome {
        /** The provider returned a translation differing from the input. */
        TRANSLATED,
        /**
         * No translation was needed, or one was returned and not accepted:
         * an {@code en} scope, an unknown or non-ISO source language, a
         * source already in the reader's language, an empty headline, a
         * translation byte-identical to its input, or one that still
         * displays as its input (M1-771). The headline is therefore the
         * text this leg was HANDED — which on the anchored path is the
         * post's English anchor, not the reader's language and not the
         * publisher's own words. What that means for the reader is
         * {@link TranslationPipeline#primaryInReaderLanguage}'s decision,
         * never this enum's:
         * it reads NO_OP against the declared languages and brackets
         * accordingly.
         */
        NO_OP,
        /** A translation was ATTEMPTED AND FAILED; {@code note} says so. */
        FALLBACK
    }

    /**
     * The display-hit leg's result.
     *
     * @param headline the text for the primary slot — the translation on
     *     TRANSLATED, the untouched input otherwise
     * @param note     the localized translation-unavailable note, non-null
     *     on FALLBACK only. Kept OUT of {@code headline} so the renderer
     *     can bracket one without bracketing the other
     */
    public record DisplayHit(DisplayHitOutcome outcome, String headline, @Nullable String note) {
    }

    /**
     * The result a caller reports when it SKIPPED the leg rather than
     * calling it — a degraded cluster, an exhausted per-render translator
     * budget, a rejected {@code LlmRateCap} draw. Modelled as a NO_OP so
     * every surface composes its block the same way; whether the line is
     * bracketed is then decided by
     * {@link #primaryInReaderLanguage(DisplayHit, boolean, String, String)},
     * which does NOT assume a skipped leg means the text is readable.
     */
    public static DisplayHit skipped(String headline) {
        return noOp(headline);
    }

    /**
     * THE bracketing decision, in one place for all four render surfaces:
     * is the primary line known to be in the reader's language?
     *
     * <p>The outcome alone cannot answer it, which is the trap this method
     * exists to close. {@link DisplayHitOutcome#NO_OP} conflates two very
     * different states — "no translation was needed" (a source already in
     * the reader's language, or an unknown one) and "this leg
     * short-circuits for {@code en} scopes and looked at nothing" — and
     * the second says nothing at all about what language the text is in.
     * Reading NO_OP as "readable" would render every foreign headline bare
     * to the default reader: exactly the indistinguishability D29 (c)'s
     * bracket invariant exists to remove.
     *
     * <p>So NO_OP defers to the declared languages, and the same rule
     * covers every path that skips the leg — an exhausted budget, a
     * rejected rate-cap draw, a degraded cluster — which would otherwise
     * each leak a bare foreign line to a non-English reader.
     *
     * <p>An unknown or non-ISO source language counts as readable and
     * renders BARE: D29 declares languages and never infers them, so an
     * undeclared source is not evidence of foreignness. The ISO shape test
     * is the same one the no-op gate applies, deliberately kept beside it
     * so the two cannot drift.
     *
     * @param usesAnchor whether the primary line is the English anchor
     *                   rather than the post's own text
     */
    public static boolean primaryInReaderLanguage(DisplayHit hit,
                                                  boolean usesAnchor,
                                                  @Nullable String sourceLanguage,
                                                  String scopeLanguage) {
        return switch (hit.outcome()) {
            case TRANSLATED -> true;
            case FALLBACK -> false;
            case NO_OP -> usesAnchor
                    ? scopeLanguage.equalsIgnoreCase("en")
                    : sourceLanguage == null
                            || !ISO_639_SHAPE.matcher(sourceLanguage).matches()
                            || sourceLanguage.equalsIgnoreCase(scopeLanguage);
        };
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
     * sanitizer-2 → target-script check → cache write → echo check →
     * truncate.
     * The pre-bound runs
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
     * <p>The CACHE-HIT path applies NO transform: truncate only, or — for
     * a stored {@link #REJECTED_BY_TARGET_SCRIPT_CHECK} — the
     * fallback with note and nothing else. Every value this leg can read
     * back was written by this leg — hence already flattened AND
     * sanitized — because display-hit entries occupy a keyspace disjoint
     * from the prose leg's (see {@link #displayHitCacheLanguage}). A
     * read-path rewrite of a sanitized value is exactly the
     * post-sanitize-rewrite ordering {@link DisplayHeadline} forbids.
     * [redteam 2026-08-03, medium/INJECTION]
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
     * @param anchored  whether {@code displayHeadline} is the post's
     *     English anchor rather than its original text. Sets the
     *     translation's SOURCE locale (D29's collapse); it does NOT gate
     *     the no-op decision below, which keeps reading the DECLARED
     *     source language — an unconditional anchored rule would
     *     round-trip a cs-source post for a cs reader through cs → en → cs
     *     where the leg correctly no-ops today
     * @param scopeLanguage  ISO 639-1 code from
     *     {@code scope_preferences.language}; never null
     * @return a {@link DisplayHit} discriminating TRANSLATED from NO_OP
     *     from FALLBACK. The outcome is returned rather than left for the
     *     caller to infer from the bytes: {@link #fallbackWithNote}'s
     *     shape means a renderer comparing the result against its input
     *     cannot tell a failed translation from an untranslated headline,
     *     and would render the headline twice. Never null; the headline
     *     component is never empty for a non-empty input.
     */
    public DisplayHit runForDisplayHit(String displayHeadline,
                                       @Nullable String sourceLanguage,
                                       boolean anchored,
                                       String scopeKind,
                                       UUID scopeId,
                                       String scopeLanguage) {
        if (scopeLanguage.equalsIgnoreCase("en")
                || sourceLanguage == null
                || !ISO_639_SHAPE.matcher(sourceLanguage).matches()
                || sourceLanguage.equalsIgnoreCase(scopeLanguage)
                || displayHeadline.isEmpty()) {
            return noOp(displayHeadline);
        }

        String cacheLanguage = displayHitCacheLanguage(scopeKind, scopeId, scopeLanguage);

        // Cache hit: deliver the stored bytes with NO transform — truncate
        // only, applied OUTSIDE the cache. The disjoint,
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
                    ? displayFallback(displayHeadline, scopeLanguage)
                    : finishDisplayHit(displayHeadline, cached.get());
        }

        String translated;
        try {
            translated = translationProvider.translate(
                    displayHeadline,
                    // D29's collapse: when the caller handed us the English
                    // anchor, the translation direction is en → reader, not
                    // source → reader — one measured direction per reader
                    // language instead of one per (source, reader) pair.
                    // A literal Locale.ENGLISH also keeps the anchored leg
                    // clear of the Locale.of parse ISO_639_SHAPE guards.
                    anchored ? Locale.ENGLISH : Locale.of(sourceLanguage),
                    Locale.of(scopeLanguage));
        } catch (RuntimeException e) {
            SafeLog.warn(LOG, "TranslationPipeline: display-hit translator failed for target_language="
                    + scopeLanguage + "; falling back to the original headline with a note", e);
            return displayFallback(displayHeadline, scopeLanguage);
        }

        if (translated.isBlank()) {
            LOG.warn("TranslationPipeline: display-hit translator returned blank output for "
                    + "target_language={}; falling back to the original headline with a note",
                    scopeLanguage);
            return displayFallback(displayHeadline, scopeLanguage);
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
                return displayFallback(displayHeadline, scopeLanguage);
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
     * flattened, sanitized translation at {@code DisplayHeadline.MAX_LENGTH}
     * with the marker-safe cut.
     *
     * <p>A translation byte-identical to its input is reported as a
     * {@link DisplayHitOutcome#NO_OP}, not a translation — a short headline
     * can translate to itself legitimately (a proper noun is not a
     * failure), and those are the publisher's own words, which the renderer
     * must not label as derived.
     *
     * <p><b>A reply that merely PADS its input is the same no-op (M1-771),
     * and byte identity alone cannot see it</b> — one added character
     * clears it, which is how a hostile or steered endpoint hands a
     * {@code cs}/{@code es}/{@code tr} reader English under an unbracketed
     * line claiming their language. {@code missingTargetScript} does not
     * cover it either: it returns null the moment the target script is
     * Latin. So the same word walk the anchor hop applies is CALLED here —
     * see {@link DisplayHeadline#displaysAsTheOriginal} for why one shared
     * predicate rather than a second copy.
     *
     * <p>Three placement consequences, each load-bearing:
     * <ul>
     *   <li><b>NO_OP, not {@link #displayFallback}.</b> Every path that
     *       reaches the translating leg has already established a non-null,
     *       ISO-shaped source language differing from a non-{@code en}
     *       scope, and on all of those {@link #primaryInReaderLanguage}
     *       reads NO_OP as "not in the reader's language" — so the line is
     *       bracketed either way and the fallback's only added effect would
     *       be a spurious "translation unavailable" note on the accepted
     *       false positives (a proper-noun headline the walk matches).
     *       Unlike condition (d), whose false-positive rate is nil, the
     *       walk has a stated one, so it degrades to the quieter shape.</li>
     *   <li><b>Here rather than beside condition (d)</b>, so the CACHE-HIT
     *       path is covered by the same test: an echo written to the cache
     *       before this check ran would otherwise be promoted on every
     *       later render. It also makes the rejection sentinel unnecessary
     *       — the cached echo is inert, since a hit re-answers no-op and
     *       renders the INPUT, and the entry is what stops the leg
     *       re-calling the translator every render.</li>
     *   <li><b>Before {@link DisplayHeadline#truncate}</b>, which is what
     *       keeps the display hop clear of the anchor hop's leading-pad
     *       residual (red-team 2026-08-05 round 5): truncating the reply
     *       first would cut exactly the tail whose match proves the
     *       echo.</li>
     * </ul>
     * The equality test stays in front of the walk rather than being
     * subsumed by it: for an all-invisible headline the walk has no word to
     * match and answers false, and that headline's own echo must stay the
     * publisher's words.
     */
    private DisplayHit finishDisplayHit(String displayHeadline, String flattenedSanitized) {
        if (flattenedSanitized.equals(displayHeadline)
                || DisplayHeadline.displaysAsTheOriginal(displayHeadline, flattenedSanitized)) {
            return noOp(displayHeadline);
        }
        return new DisplayHit(DisplayHitOutcome.TRANSLATED,
                DisplayHeadline.truncate(flattenedSanitized), null);
    }

    private static DisplayHit noOp(String displayHeadline) {
        return new DisplayHit(DisplayHitOutcome.NO_OP, displayHeadline, null);
    }

    /**
     * The display-hit leg's fallback. Unlike {@link #fallbackWithNote} it
     * returns the note SEPARATELY from the headline rather than joined on a
     * newline: the renderer brackets the headline (a failed translation
     * leaves the primary line in a language the reader did not ask for)
     * and must not bracket the bot-authored note with it. Returning the
     * joined string is also what would force the renderer to infer "was
     * this translated?" by comparing bytes against the leg's input — the
     * inference that double-prints the headline once as the failed line
     * and once as the bracketed original.
     */
    private DisplayHit displayFallback(String displayHeadline, String scopeLanguage) {
        return new DisplayHit(DisplayHitOutcome.FALLBACK, displayHeadline,
                unavailableNote(scopeLanguage));
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
        return originalText + "\n" + unavailableNote(scopeLanguage);
    }

    private String unavailableNote(String scopeLanguage) {
        return bundleLoader.get(BundleKeys.REPLY_TRANSLATION_UNAVAILABLE, scopeLanguage);
    }
}
