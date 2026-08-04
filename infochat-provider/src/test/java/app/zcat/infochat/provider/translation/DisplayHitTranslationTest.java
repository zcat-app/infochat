package app.zcat.infochat.provider.translation;

import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.messaging.TranslationProvider;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.render.DisplayHeadline;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 (Shape A) tests for the display-hit translation leg
 * (M1-747): {@link TranslationPipeline#runForDisplayHit} plus the
 * {@code {{SOURCE_LANGUAGE}}} prompt slot in {@link LlmTranslationProvider}.
 * Each §10 carried-across control is asserted by a test naming it.
 * Collaborators follow the {@link TranslationPipelineTest} idiom: real
 * {@link TranslationCache}, recording provider/sanitizer stubs, and a
 * key-echoing bundle stub so the marker and the fallback note are
 * distinguishable in assertions (the real bundle values are covered by
 * {@code BundleLoaderTest} parity and the renderer-level test).
 */
class DisplayHitTranslationTest {

    private static final String HEADLINE = "Original headline text";

    /** The fixture rendering scope — the cache-partition dimensions. */
    private static final String SCOPE_KIND = "group";
    private static final UUID SCOPE_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private TranslationPipeline pipeline;
    private TranslationCache cache;
    private RecordingProvider translatorStub;
    private RecordingSanitizer sanitizer;
    private KeyedBundleLoader bundleLoaderStub;

    @BeforeEach
    void setUp() {
        cache = new TranslationCache();
        translatorStub = new RecordingProvider("přeložený titulek");
        sanitizer = new RecordingSanitizer();
        bundleLoaderStub = new KeyedBundleLoader();

        pipeline = new TranslationPipeline();
        pipeline.translationCache = cache;
        pipeline.translationProvider = translatorStub;
        pipeline.llmOutputSanitizer = sanitizer;
        pipeline.bundleLoader = bundleLoaderStub;
    }

    /** The leg under test, invoked for the fixture scope. */
    private String runDisplayHit(@Nullable String sourceLanguage, String scopeLanguage) {
        return runDisplayHit(HEADLINE, sourceLanguage, scopeLanguage);
    }

    private String runDisplayHit(String headline,
                                 @Nullable String sourceLanguage,
                                 String scopeLanguage) {
        return pipeline.runForDisplayHit(
                headline, sourceLanguage, SCOPE_KIND, SCOPE_ID, scopeLanguage);
    }

    /**
     * The fixture scope's display-hit cache keyspace — the REAL composition
     * (package-private on the pipeline), not a re-derived copy that could
     * drift from the implementation.
     */
    private String displayKeyspace(String lang) {
        return TranslationPipeline.displayHitCacheLanguage(SCOPE_KIND, SCOPE_ID, lang);
    }

    private String marker(String lang) {
        return bundleLoaderStub.valueFor(BundleKeys.REPLY_TRANSLATION_HIT_MARKER, lang);
    }

    private String note(String lang) {
        return bundleLoaderStub.valueFor(BundleKeys.REPLY_TRANSLATION_UNAVAILABLE, lang);
    }

    // ----- no-op legs ---------------------------------------------------

    @Test
    void enScopeReturnsHeadlineUnchangedWithNoProviderOrSanitizerCall() {
        String result = runDisplayHit("cs", "en");

        assertEquals(HEADLINE, result, "en scope must be a strict no-op");
        assertEquals(0, translatorStub.callCount(), "en scope must not invoke the translator");
        assertEquals(0, sanitizer.sanitizeCallCount(), "en scope must not invoke sanitizer-2");
    }

    @Test
    void sameSourceLanguageIsNoOp() {
        String result = runDisplayHit("cs", "cs");

        assertEquals(HEADLINE, result, "a hit already in the scope language must not be translated");
        assertEquals(0, translatorStub.callCount());
    }

    @Test
    void sameSourceLanguageComparisonIsCaseInsensitive() {
        String result = runDisplayHit("CS", "cs");

        assertEquals(HEADLINE, result);
        assertEquals(0, translatorStub.callCount(),
                "language-code comparison must be case-insensitive — 'CS' == 'cs'");
    }

    @Test
    void nullSourceLanguageIsNoOp() {
        String result = runDisplayHit(null, "cs");

        assertEquals(HEADLINE, result,
                "unknown source language means never translate (declared, not inferred — D29)");
        assertEquals(0, translatorStub.callCount());
    }

    @Test
    void emptyHeadlineIsNoOp() {
        String result = runDisplayHit("", "en", "cs");

        assertEquals("", result);
        assertEquals(0, translatorStub.callCount(), "nothing to translate — no call");
    }

    // ----- translating leg ----------------------------------------------

    @Test
    void csScopeWithEnSourceTranslatesAppendsMarkerAndCaches() {
        String result = runDisplayHit("en", "cs");

        assertEquals("přeložený titulek " + marker("cs"), result,
                "translated headline must carry the machine-translation marker, space-separated");
        assertEquals(1, translatorStub.callCount(), "exactly one translator call per hit");
        assertEquals(1, sanitizer.sanitizeCallCount(),
                "sanitizer-2 must run exactly once, on ONE headline (M1-697 unit)");
        assertEquals("přeložený titulek", cache.get(HEADLINE, displayKeyspace("cs")).orElseThrow(),
                "the cached value is the sanitized translation WITHOUT the marker");
        assertTrue(cache.get(HEADLINE, "cs").isEmpty(),
                "the display leg must write ONLY its own keyspace — never the prose leg's");
    }

    @Test
    void declaredSourceLocaleIsPassedToTheProvider() {
        runDisplayHit("es", "cs");

        assertEquals("es", translatorStub.lastFrom().getLanguage(),
                "the post's DECLARED source language must reach the provider, not a hard-coded English");
        assertEquals("cs", translatorStub.lastTo().getLanguage());
    }

    @Test
    void repeatedCallHitsTheCachePopulatedByTheFirst() {
        String first = runDisplayHit("en", "cs");
        String second = runDisplayHit("en", "cs");

        assertEquals(first, second, "cached render must equal the fresh render");
        assertEquals(1, translatorStub.callCount(),
                "second render of the same headline must hit the cache");
    }

    // ----- §10 carried-across controls ----------------------------------

    @Test
    void translatorOutputIsFlattenedToOneLineBeforeSanitizer2() {
        // Control (a). U+2028 is exactly the code point the sanitizer's
        // ASCII-only token separators miss (the 2026-07-30 DisplayHeadline
        // finding): flatten must run FIRST so sanitizer-2 inspects the
        // bytes that are delivered.
        translatorStub.setResponseText("první\u2028řádek\ndruhý");

        String result = runDisplayHit("en", "cs");

        assertEquals("první řádek druhý", sanitizer.lastInput(),
                "sanitizer-2 must receive the FLATTENED output — flatten runs before sanitize");
        assertEquals("první řádek druhý " + marker("cs"), result);
        assertFalse(result.contains("\u2028") || result.contains("\n"),
                "a translated headline must not be able to inject line breaks into the block");
    }

    @Test
    void overlongTranslationIsReboundedAndTheMarkerLandsAfterTheCut() {
        // Control (c) + (d). A translation of a MAX_LENGTH input
        // legitimately runs longer; the display bound travels onto the
        // translated form, with the marker appended AFTER the cut so it
        // can never be half-emitted.
        String overlong = "x".repeat(400);
        translatorStub.setResponseText(overlong);

        String result = runDisplayHit("en", "cs");

        assertEquals(DisplayHeadline.truncate(overlong) + " " + marker("cs"), result,
                "translated headline must be re-bounded by DisplayHeadline's own cut, marker after");
        assertTrue(result.startsWith("x".repeat(200) + "…"),
                "the bound is MAX_LENGTH with the ellipsis appended to the cut");
    }

    @Test
    void cacheHitStillGetsTruncateAndMarkerButSkipsProviderAndSanitizer() {
        // Truncate + marker live OUTSIDE the cache: a hit must render
        // exactly like a miss without re-invoking the translator or
        // sanitizer-2 (the prose path's existing hit contract).
        String overlong = "y".repeat(400);
        cache.put(HEADLINE, displayKeyspace("cs"), overlong);

        String result = runDisplayHit("en", "cs");

        assertEquals(DisplayHeadline.truncate(overlong) + " " + marker("cs"), result);
        assertEquals(0, translatorStub.callCount(), "cache hit must not invoke the translator");
        assertEquals(0, sanitizer.sanitizeCallCount(), "cache hit must not invoke sanitizer-2");
        assertEquals(overlong, cache.get(HEADLINE, displayKeyspace("cs")).orElseThrow(),
                "rendering must not write the marked/truncated form back into the cache");
    }

    @Test
    void cacheHitDeliversTheStoredBytesUnmodified() {
        // §10 control (e): NO REWRITE RUNS AFTER SANITIZATION ON ANY PATH.
        // Every value in the display keyspace was flattened BEFORE its
        // sanitizer-2 pass (the write path proves that above); a read-path
        // transform of an already-sanitized value is the 2026-08-03
        // medium/INJECTION defect — flattening U+2028 to a space AFTER the
        // closed list ran splices /quarantine<U+2028>approve into a
        // dispatchable /quarantine approve with no audit row. Seeding a
        // U+2028-bearing value directly into the keyspace proves no
        // transform runs, not that such values occur.
        cache.put(HEADLINE, displayKeyspace("cs"), "/quarantine\u2028approve now");

        String result = runDisplayHit("en", "cs");

        assertEquals("/quarantine\u2028approve now " + marker("cs"), result,
                "a cache hit must deliver the stored bytes with truncate + marker ONLY");
        assertFalse(result.contains("/quarantine approve"),
                "no read-path flatten may splice a dispatchable command out of the stored bytes");
        assertEquals(0, sanitizer.sanitizeCallCount(),
                "no sanitize on the hit path — disjointness is what makes that safe");
    }

    @Test
    void proseLegEntriesAreNotReadableByTheDisplayLeg() {
        // Disjoint keyspace [redteam 2026-08-03, medium/INJECTION]: the
        // prose leg stores sanitize(translated) UNFLATTENED under the BARE
        // language code. A shared keyspace would hand that multiline value
        // to the no-transform hit path above; disjointness makes the seed
        // invisible here, so the leg translates fresh instead.
        cache.put(HEADLINE, "cs", "řádek jedna\nřádek dva");

        String result = runDisplayHit("en", "cs");

        assertEquals("přeložený titulek " + marker("cs"), result,
                "a prose-leg entry under the same source text must be a display-leg MISS");
        assertEquals(1, translatorStub.callCount(),
                "the display leg must translate fresh rather than read the prose entry");
        assertEquals("řádek jedna\nřádek dva", cache.get(HEADLINE, "cs").orElseThrow(),
                "the prose entry must stay untouched in its own keyspace");
    }

    @Test
    void displayHitEntriesArePartitionedPerScope() {
        // Per-(scope_kind, scope_id) partition [redteam 2026-08-03,
        // low/INFO-LEAK]: security.md accepts the cross-scope cache timing
        // side-channel for bot prose only; feed-authored headlines must not
        // let one scope observe (via a first-render HIT) that another scope
        // rendered the same post within the TTL.
        String sameScope = runDisplayHit("en", "cs");
        String otherScope = pipeline.runForDisplayHit(HEADLINE, "en",
                SCOPE_KIND, UUID.fromString("22222222-2222-2222-2222-222222222222"), "cs");

        assertEquals(sameScope, otherScope, "both scopes render the same translation");
        assertEquals(2, translatorStub.callCount(),
                "a second scope must MISS on a headline another scope already cached");

        runDisplayHit("en", "cs");
        assertEquals(2, translatorStub.callCount(),
                "the first scope's own re-render still hits its partition");
    }

    @Test
    void nonIsoShapedSourceLanguageTakesTheNeverTranslateLeg() {
        // [redteam 2026-08-03, low/INJECTION] source.language is an
        // unvalidated TEXT column and Locale.of accepts anything —
        // Locale.of("{{id}}").getDisplayLanguage(ENGLISH) returns "{{id}}"
        // verbatim, headed for the prompt's INSTRUCTION region. Anything
        // not 2-3 ASCII letters is treated exactly like unknown.
        for (String garbage : List.of("{{id}}", "x".repeat(40), "zz9", "e", "")) {
            String result = runDisplayHit(garbage, "cs");

            assertEquals(HEADLINE, result,
                    "non-ISO-shaped source language '" + garbage
                            + "' must take the never-translate leg");
        }
        assertEquals(0, translatorStub.callCount(),
                "a non-ISO-shaped source language must never reach the provider");
        assertEquals(0, sanitizer.sanitizeCallCount());
    }

    @Test
    void overCapTranslatorReplyIsPreBoundedBeforeSanitize() {
        // [redteam 2026-08-03, low/DOS] The reply is bounded only by the
        // provider's 1-8 MiB body cap; without the pre-bound, megabytes
        // reach NFKC + the closed-list matchers before the 200-char
        // display cut. Same constant, same rationale as the body operand's
        // 2026-07-30 guard.
        translatorStub.setResponseText("z".repeat(DisplayHeadline.BODY_SCAN_LIMIT + 1000));

        String result = runDisplayHit("en", "cs");

        assertEquals(DisplayHeadline.BODY_SCAN_LIMIT, sanitizer.lastInput().length(),
                "sanitizer-2 must see the PRE-BOUNDED reply, never the raw over-cap bytes");
        assertTrue(result.startsWith("z".repeat(200) + "…"),
                "the display cut still governs what the reader sees");
    }

    // ----- saved-leg cache partition (M1-755) ---------------------------

    private static final UUID SAVED_USER_A =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SAVED_USER_B =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    /** The /saved leg's call shape: scopeKind "saved", scopeId = the ACTOR's users.id. */
    private String runSavedLeg(UUID userId, String sourceLanguage, String scopeLanguage) {
        return pipeline.runForDisplayHit(HEADLINE, sourceLanguage, "saved", userId, scopeLanguage);
    }

    @Test
    void savedLegEntriesArePartitionedPerUser() {
        // D13 makes the /saved list user-global, so the security-relevant
        // boundary for the display-hit cache is the USER, not the calling
        // scope: the partition is hit/saved/<userId>/<effectiveLanguage>.
        // An entry written for one user must be a MISS for another with
        // the same headline and language — no user may observe (via a
        // first-render HIT) that another user saved and rendered the same
        // post within the TTL.
        String userA = runSavedLeg(SAVED_USER_A, "en", "cs");
        String userB = runSavedLeg(SAVED_USER_B, "en", "cs");

        assertEquals(userA, userB, "both users render the same translation");
        assertEquals(2, translatorStub.callCount(),
                "a second user must MISS on a headline another user already cached");
        assertEquals(2, sanitizer.sanitizeCallCount(),
                "a per-user miss is a full translating render — one sanitize per user");

        runSavedLeg(SAVED_USER_A, "en", "cs");
        assertEquals(2, translatorStub.callCount(),
                "the first user's own re-render still hits its partition");
    }

    @Test
    void savedLegReadsThePerUserKeyspaceItWrites() {
        // The same user renders the same user-global list in every scope
        // they are in, and the leg keys the partition on the ACTOR, so
        // the user's own re-renders share entries. Seeding the REAL
        // composition (package-private on the pipeline) and rendering
        // with the leg's call shape proves the leg reads exactly
        // hit/saved/<userId>/<effectiveLanguage> — not a re-derived copy.
        cache.put(HEADLINE,
                TranslationPipeline.displayHitCacheLanguage("saved", SAVED_USER_A, "cs"),
                "přeložený titulek");

        String result = runSavedLeg(SAVED_USER_A, "en", "cs");

        assertEquals("přeložený titulek " + marker("cs"), result,
                "a saved-leg hit renders truncate + marker exactly like any display hit");
        assertEquals(0, translatorStub.callCount(),
                "an entry seeded in the saved-leg keyspace must be a HIT");
        assertEquals(0, sanitizer.sanitizeCallCount(),
                "no sanitize on the hit path — disjointness is what makes that safe");
    }

    // ----- fallback -----------------------------------------------------

    @Test
    void providerFailureFallsBackToOriginalPlusNoteWithoutMarker() {
        translatorStub.setThrowOnCall(true);

        String result = runDisplayHit("en", "cs");

        assertEquals(HEADLINE + "\n" + note("cs"), result,
                "translator failure must fall back to the ORIGINAL headline + the existing note");
        assertFalse(result.contains(marker("cs")),
                "a fallback is not a translation — no machine-translation marker");
        assertEquals(0, sanitizer.sanitizeCallCount(), "no output to sanitize on the failure path");
        assertTrue(cache.get(HEADLINE, displayKeyspace("cs")).isEmpty(),
                "the cache stores translated forms only — never the fallback");
    }

    @Test
    void blankTranslationFallsBackToOriginalPlusNote() {
        translatorStub.setResponseText("   \t\n ");

        String result = runDisplayHit("en", "cs");

        assertEquals(HEADLINE + "\n" + note("cs"), result,
                "blank translator output must fall back to the original headline + note");
        assertTrue(cache.get(HEADLINE, displayKeyspace("cs")).isEmpty());
    }

    @Test
    void identityTranslationIsDeliveredUnmarkedAndStillCached() {
        // Unlike run()'s condition (b), a short headline translating to
        // itself is legitimate (a proper noun is not a failure): deliver
        // it byte-identical and unmarked, but cache it so subsequent
        // renders skip the translator.
        translatorStub.setResponseText(HEADLINE);

        String first = runDisplayHit("en", "cs");
        String second = runDisplayHit("en", "cs");

        assertEquals(HEADLINE, first, "identity translation must render byte-identical");
        assertFalse(first.contains(marker("cs")),
                "marking an identity translation would label the publisher's own words machine output");
        assertFalse(first.contains(note("cs")), "an identity translation is not a fallback");
        assertEquals(HEADLINE, second);
        assertEquals(1, translatorStub.callCount(),
                "the identity result must be cached — one translator call across two renders");
    }

    // ----- condition (d): target script (M1-761) ------------------------

    @Test
    void latinOnlyTranslationForARuScopeFallsBackUnmarkedAndDiscardsTheText() {
        // The failure only condition (d) can see on this leg: a translator
        // answering a ru-scope request in English returns a headline that
        // is neither blank nor byte-identical to the input, so every check
        // the leg had before M1-761 passes it — and it reaches the reader
        // under the D30 marker asserting a machine produced it in their
        // language.
        translatorStub.setResponseText("Sorry, I cannot translate that headline.");

        String result = runDisplayHit("en", "ru");

        assertEquals(HEADLINE + "\n" + note("ru"), result,
                "a translation carrying zero Cyrillic characters must fall back to the "
                        + "ORIGINAL headline plus the existing unavailable note");
        assertFalse(result.contains(marker("ru")),
                "text the check has just judged untranslated must never carry the "
                        + "machine-translation marker");
        assertFalse(cache.get(HEADLINE, displayKeyspace("ru")).orElseThrow()
                        .contains("Sorry, I cannot translate that headline."),
                "the REJECTED TEXT must never be cached — only the fact of the rejection, "
                        + "or a later render would serve the rejected translation");
    }

    @Test
    void rejectedHeadlineConvergesInsteadOfRetranslatingOnEveryRender() {
        // [redteam 2026-08-04, low/DOS] Without a recorded rejection this
        // leg never converges: /saved and /summary re-render the same
        // headline, and because both decide a row is free by probing this
        // key themselves, each render re-spends a per-render budget slot
        // and the per-user LLM token security.md §Rate limiting says a
        // fully-converged page never draws. Recording under the SAME key a
        // translation would use is what carries the fix to those callers
        // with no edit to either.
        translatorStub.setResponseText("Sorry, I cannot translate that headline.");

        String first = runDisplayHit("en", "ru");
        String second = runDisplayHit("en", "ru");

        assertEquals(first, second, "a re-render must return the same fallback, not drift");
        assertEquals(HEADLINE + "\n" + note("ru"), second);
        assertEquals(1, translatorStub.callCount(),
                "the recorded rejection must make the second render a cache HIT — one "
                        + "translator call across two renders");
        assertTrue(cache.get(HEADLINE, displayKeyspace("ru")).isPresent(),
                "the probing callers read presence on THIS key to decide a row is free, so "
                        + "the rejection must occupy it rather than a separate partition");
    }

    @Test
    void recordedRejectionIsNeverRenderedToTheReader() {
        // The in-band marker is safe only because it cannot reach a reader:
        // the cache-read path returns the fallback BEFORE finishDisplayHit,
        // so the stored value is never truncated, never marked, never
        // delivered. Its unforgeability is structural — every value this
        // leg writes passes DisplayHeadline.prepareTranslatedHeadline,
        // whose flatten collapses every (?:\R|\s)+ run to one space, so no
        // translator output can carry the U+000A this marker leads with.
        translatorStub.setResponseText("Sorry, I cannot translate that headline.");
        runDisplayHit("en", "ru");

        String stored = cache.get(HEADLINE, displayKeyspace("ru")).orElseThrow();
        String rendered = runDisplayHit("en", "ru");

        assertTrue(stored.startsWith("\n"),
                "the marker must lead with a line separator — the one byte "
                        + "prepareTranslatedHeadline's flatten guarantees no translation can carry");
        assertFalse(rendered.contains(stored.strip()),
                "no part of the stored marker may surface in what the reader sees");
        assertEquals(HEADLINE + "\n" + note("ru"), rendered,
                "a hit on the marker renders exactly the fallback, with no truncate or marker step");
    }

    @Test
    void cyrillicTranslationForARuScopeIsDeliveredMarkedAndCached() {
        // The control for the check above: one Cyrillic character is the
        // whole threshold, and real Russian prose keeps Latin fragments
        // (proper nouns, tickers, numbers) that must not trip it.
        translatorStub.setResponseText("Обзор новостей Bitcoin за 2026 год");

        String result = runDisplayHit("en", "ru");

        assertEquals("Обзор новостей Bitcoin за 2026 год " + marker("ru"), result,
                "a translation carrying Cyrillic must be delivered with the marker");
        assertFalse(result.contains(note("ru")),
                "a valid Cyrillic translation must NOT append the fallback note");
        assertEquals("Обзор новостей Bitcoin за 2026 год",
                cache.get(HEADLINE, displayKeyspace("ru")).orElseThrow(),
                "the cached value is the sanitized translation WITHOUT the marker");
    }

    @Test
    void identityTranslationForARuScopeStillPassesThroughUnmarked() {
        // The ORDERING M1-761 exists for. HEADLINE is all-Latin, so a
        // translation byte-identical to it carries zero Cyrillic — placed
        // ahead of the passthrough, condition (d) would refuse exactly the
        // headlines (proper nouns, tickers) the passthrough exists to
        // deliver, turning every one of them into a fallback note for a
        // non-Latin scope.
        translatorStub.setResponseText(HEADLINE);

        String result = runDisplayHit("en", "ru");

        assertEquals(HEADLINE, result,
                "an identity translation must pass through unchanged even for a non-Latin target");
        assertFalse(result.contains(marker("ru")),
                "marking an identity translation would label the publisher's own words machine output");
        assertFalse(result.contains(note("ru")),
                "the identity passthrough is not a condition-(d) failure");
        assertTrue(cache.get(HEADLINE, displayKeyspace("ru")).isPresent(),
                "the passthrough short-circuits (d), not the cache write this leg does deliberately");
    }

    // ----- {{SOURCE_LANGUAGE}} prompt slot ------------------------------

    @Test
    void prosePathPromptStillReadsFromEnglishByteIdentical() {
        var recording = newRecordingLlmTranslator();

        recording.translator().translate("some prose", Locale.ENGLISH, Locale.forLanguageTag("cs"));

        assertTrue(recording.provider().lastUserPrompt().contains("from English to Czech"),
                "from=ENGLISH must render the exact pre-M1-747 'from English' bytes; got: "
                        + recording.provider().lastUserPrompt());
    }

    @Test
    void displayHitPromptNamesTheDeclaredSourceLanguage() {
        var recording = newRecordingLlmTranslator();

        recording.translator().translate("titular español", Locale.of("es"), Locale.forLanguageTag("cs"));

        assertTrue(recording.provider().lastUserPrompt().contains("from Spanish to Czech"),
                "the declared source language must be named in the prompt; got: "
                        + recording.provider().lastUserPrompt());
    }

    @Test
    void slotMarkerInsideASubstitutedValueIsNotExpanded() {
        // [redteam 2026-08-03 r2, low/INJECTION] Single-pass substitution.
        // An unknown locale echoes its code verbatim through
        // getDisplayLanguage, so a "{{id}}" source language lands in the
        // prompt's INSTRUCTION region. Under the old ordered .replace
        // chain the LATER {{id}} pass would expand it into the per-call
        // random delimiter — handing an attacker the one value the
        // wrapper's forgery-proofness depends on. Asserted against the
        // provider directly: the TranslationPipeline ISO-shape gate is
        // defense in depth, and this SPI is callable from any module.
        var recording = newRecordingLlmTranslator();

        recording.translator().translate(
                "text", Locale.of("{{id}}"), Locale.forLanguageTag("cs"));
        String prompt = recording.provider().lastUserPrompt();

        assertTrue(prompt.contains("from {{id}} to Czech"),
                "a marker inside a substituted value must survive VERBATIM; got: " + prompt);
        assertFalse(prompt.contains("id=\"{{id}}\""),
                "the real {{id}} slots must still resolve to the per-call delimiter; got: " + prompt);
        assertTrue(prompt.contains("<<<UNTRUSTED_CONTENT id=\""),
                "the wrapper must still carry a resolved delimiter id; got: " + prompt);
    }

    @Test
    void dollarSignInTheTranslatedTextSurvivesSubstitutionLiterally() {
        // Byte-compat with the String.replace chain this replaced: a
        // regex-style replacement would read "$1" as a group reference.
        var recording = newRecordingLlmTranslator();

        recording.translator().translate(
                "Bitcoin hits $100k \\o/", Locale.ENGLISH, Locale.forLanguageTag("cs"));

        assertTrue(recording.provider().lastUserPrompt().contains("Bitcoin hits $100k \\o/"),
                "$ and \\ in the content must stay literal; got: "
                        + recording.provider().lastUserPrompt());
    }

    @Test
    void unrecognizedSlotIsLeftVerbatim() throws Exception {
        // Byte-compat again: a chain with no matching .replace call left an
        // unknown {{SLOT}} untouched, so the single-pass renderer must too
        // — a template typo must not silently blank a region of the prompt.
        var stubProvider = new LlmTranslationProviderTest.RecordingLlmProvider("out");
        LlmTranslationProvider translator = newTranslatorWithTemplate(
                "from {{SOURCE_LANGUAGE}} / {{NOT_A_SLOT}} / {{content}}", stubProvider);

        translator.translate("body", Locale.ENGLISH, Locale.forLanguageTag("cs"));

        assertEquals("from English / {{NOT_A_SLOT}} / body", stubProvider.lastUserPrompt(),
                "an unrecognized slot must be left verbatim, known slots still filled");
    }

    private record RecordingLlmTranslator(
            LlmTranslationProvider translator,
            LlmTranslationProviderTest.RecordingLlmProvider provider) {
    }

    /** The {@link LlmTranslationProviderTest} fixture idiom, reused. */
    private static RecordingLlmTranslator newRecordingLlmTranslator() {
        var stubProvider = new LlmTranslationProviderTest.RecordingLlmProvider("translated output");
        LlmTranslationProvider translator = new LlmTranslationProvider();
        translator.llmRouter = routerFor(stubProvider);
        translator.loadPromptTemplate();
        return new RecordingLlmTranslator(translator, stubProvider);
    }

    /**
     * A provider whose prompt template is the supplied fixture rather than
     * the classpath resource — the only way to exercise a slot the real
     * template does not contain. The field is written directly because
     * {@code loadPromptTemplate} can only read from the classpath.
     */
    private static LlmTranslationProvider newTranslatorWithTemplate(
            String template, LlmTranslationProviderTest.RecordingLlmProvider stubProvider)
            throws Exception {
        LlmTranslationProvider translator = new LlmTranslationProvider();
        translator.llmRouter = routerFor(stubProvider);
        var templateField = LlmTranslationProvider.class.getDeclaredField("promptTemplate");
        templateField.setAccessible(true);
        templateField.set(translator, template);
        return translator;
    }

    private static LlmRouter routerFor(LlmTranslationProviderTest.RecordingLlmProvider stubProvider) {
        return new LlmRouter(
                List.of(new LlmRouter.Entry("test-translator", stubProvider, Set.of("en", "cs"))),
                LlmRouter.ConfigReader.fromMap(Map.of(
                        LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER, "test-translator")));
    }

    // ----- test stubs ---------------------------------------------------

    /** Records locales and counts calls; configurable response / throw. */
    static final class RecordingProvider implements TranslationProvider {
        private final AtomicInteger callCount = new AtomicInteger();
        private volatile String responseText;
        private volatile boolean throwOnCall;
        private volatile @Nullable Locale lastFrom;
        private volatile @Nullable Locale lastTo;

        RecordingProvider(String responseText) {
            this.responseText = responseText;
        }

        @Override
        public String translate(String text, Locale from, Locale to) {
            if (throwOnCall) {
                throw new RuntimeException("translator stub: simulated failure");
            }
            callCount.incrementAndGet();
            lastFrom = from;
            lastTo = to;
            return responseText;
        }

        void setResponseText(String responseText) {
            this.responseText = responseText;
        }

        void setThrowOnCall(boolean shouldThrow) {
            this.throwOnCall = shouldThrow;
        }

        int callCount() {
            return callCount.get();
        }

        Locale lastFrom() {
            Locale from = lastFrom;
            if (from == null) {
                throw new AssertionError("provider was never invoked");
            }
            return from;
        }

        Locale lastTo() {
            Locale to = lastTo;
            if (to == null) {
                throw new AssertionError("provider was never invoked");
            }
            return to;
        }
    }

    /**
     * Real sanitizer (no-op audit collaborators) that records the input of
     * the last {@code sanitize} call — the flatten-before-sanitize control
     * is asserted on what the sanitizer actually SAW, independent of the
     * closed list's content.
     */
    static final class RecordingSanitizer extends LlmOutputSanitizer {
        private final AtomicInteger callCount = new AtomicInteger();
        private volatile @Nullable String lastInput;

        RecordingSanitizer() {
            super(SanitizerTestDoubles.noOpAuditLogWriter(),
                  SanitizerTestDoubles.noOpDataSource());
        }

        @Override
        public String sanitize(String llmOutput) {
            callCount.incrementAndGet();
            lastInput = llmOutput;
            return super.sanitize(llmOutput);
        }

        int sanitizeCallCount() {
            return callCount.get();
        }

        String lastInput() {
            String input = lastInput;
            if (input == null) {
                throw new AssertionError("sanitizer was never invoked");
            }
            return input;
        }
    }

    /**
     * Echoes {@code key[lang]} so the marker and the fallback note resolve
     * to DISTINCT sentinels — {@code TranslationPipelineTest}'s stub returns
     * one sentinel for every key, which cannot tell the two apart.
     */
    static final class KeyedBundleLoader extends BundleLoader {
        @Override
        public String get(String key, String langCode) {
            return valueFor(key, langCode);
        }

        String valueFor(String key, String langCode) {
            return key + "[" + langCode + "]";
        }
    }
}
