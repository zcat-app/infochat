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
