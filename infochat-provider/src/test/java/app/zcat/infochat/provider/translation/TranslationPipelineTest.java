package app.zcat.infochat.provider.translation;

import app.zcat.infochat.messaging.TranslationProvider;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 (Shape A) tests for {@link TranslationPipeline}.
 * Collaborators: {@link TranslationCache} (real), a counting
 * {@link TranslationProvider} stub, and {@link LlmOutputSanitizer}
 * built with the {@link SanitizerTestDoubles} no-op audit collaborators
 * (so {@code sanitize()} runs without a DB).
 */
class TranslationPipelineTest {

    private TranslationPipeline pipeline;
    private TranslationCache cache;
    private RecordingTranslationProvider translatorStub;
    private CountingSanitizer sanitizer;
    private StubBundleLoader bundleLoaderStub;

    @BeforeEach
    void setUp() {
        cache = new TranslationCache();
        translatorStub = new RecordingTranslationProvider("translated-cs");
        sanitizer = new CountingSanitizer();
        bundleLoaderStub = new StubBundleLoader();

        pipeline = new TranslationPipeline();
        pipeline.translationCache = cache;
        pipeline.translationProvider = translatorStub;
        pipeline.llmOutputSanitizer = sanitizer;
        pipeline.bundleLoader = bundleLoaderStub;
    }

    @Test
    void runReturnsEnglishUnchangedWhenScopeLanguageIsEn() {
        String input = "English prose from the summarizer.";

        String result = pipeline.run(input, "en");

        assertEquals(input, result, "en-scope must return input unchanged");
        assertEquals(0, translatorStub.callCount(),
                "en-scope short-circuit must NOT invoke the translator");
        assertEquals(0, sanitizer.sanitizeCallCount(),
                "en-scope short-circuit must NOT invoke sanitizer-2");
    }

    @Test
    void runWithCsScopeOnCacheMissInvokesTranslatorAndSanitizerExactlyOnce() {
        String input = "English text for translation.";

        String result = pipeline.run(input, "cs");

        assertEquals(1, translatorStub.callCount(),
                "cache miss must invoke the translator exactly once");
        assertEquals(1, sanitizer.sanitizeCallCount(),
                "cache miss must invoke sanitizer-2 exactly once");
        // The sanitizer returns the input unchanged (no command tokens
        // to strip), so the result equals the translator's output —
        // a distinct, non-empty translation, so NO fallback note is appended.
        assertEquals("translated-cs", result);
        assertFalse(result.contains(bundleLoaderStub.noteFor("cs")),
                "happy path must NOT append the translation-fallback note");

        // Verify the cache was populated.
        assertTrue(cache.get(input, "cs").isPresent(),
                "cache must contain the (text, cs) entry after a miss");
    }

    @Test
    void runWithCsScopeOnCacheHitSkipsTranslatorAndSanitizer() {
        String input = "Cached English text.";
        cache.put(input, "cs", "cached-translated");

        String result = pipeline.run(input, "cs");

        assertEquals("cached-translated", result,
                "cache hit must return the cached value verbatim — NOT re-sanitized");
        assertEquals(0, translatorStub.callCount(),
                "cache hit must NOT invoke the translator");
        assertEquals(0, sanitizer.sanitizeCallCount(),
                "cache hit must NOT invoke sanitizer-2");
    }

    @Test
    void runDerivesCacheKeyFromPostSanitizer1TextSoTriviallyDifferentLlmOutputsCollide() {
        String input = "Identical input for both calls.";

        pipeline.run(input, "cs");
        pipeline.run(input, "cs");

        assertEquals(1, translatorStub.callCount(),
                "second call with same input and language must hit the cache "
                        + "populated by the first call — translator invoked exactly once");
    }

    @Test
    void runWithCsScopeOnTranslatorFailureReturnsEnglishTextPlusNote() {
        String input = "English fallback text.";
        translatorStub.setThrowOnCall(true);

        String result = pipeline.run(input, "cs");

        // Condition (a): provider error. The pre-M1-437 behavior returned
        // the English text silently; the spec requires a one-line note on
        // every fallback path, so the result is now English + note.
        assertEquals(input + "\n" + bundleLoaderStub.noteFor("cs"), result,
                "translator failure must return the English text plus the fallback note");
        assertEquals(BundleKeys.REPLY_TRANSLATION_UNAVAILABLE, bundleLoaderStub.lastKey(),
                "the note must be resolved from the translation-unavailable bundle key");
        assertEquals("cs", bundleLoaderStub.lastLang(),
                "the note must be resolved in the scope language");
        assertEquals(0, sanitizer.sanitizeCallCount(),
                "translator failure must NOT invoke sanitizer-2");
        assertTrue(cache.get(input, "cs").isEmpty(),
                "translator failure must NOT populate the cache");
    }

    @Test
    void runWithCsScopeOnEmptyTranslationReturnsEnglishTextPlusNote() {
        String input = "English text for the empty-output case.";
        translatorStub.setResponseText("");

        String result = pipeline.run(input, "cs");

        // Condition (c): empty output.
        assertEquals(input + "\n" + bundleLoaderStub.noteFor("cs"), result,
                "empty translator output must return the English text plus the fallback note");
        assertEquals("cs", bundleLoaderStub.lastLang());
        assertEquals(0, sanitizer.sanitizeCallCount(),
                "empty-output fallback must NOT invoke sanitizer-2");
        assertTrue(cache.get(input, "cs").isEmpty(),
                "empty-output fallback must NOT populate the cache");
    }

    @Test
    void runWithCsScopeOnWhitespaceTranslationReturnsEnglishTextPlusNote() {
        String input = "English text for the whitespace-output case.";
        translatorStub.setResponseText("   \t\n  ");

        String result = pipeline.run(input, "cs");

        // Condition (c): whitespace-only output (isBlank() == true).
        assertEquals(input + "\n" + bundleLoaderStub.noteFor("cs"), result,
                "whitespace-only translator output must return the English text plus the fallback note");
        assertEquals(0, sanitizer.sanitizeCallCount(),
                "whitespace-output fallback must NOT invoke sanitizer-2");
        assertTrue(cache.get(input, "cs").isEmpty(),
                "whitespace-output fallback must NOT populate the cache");
    }

    @Test
    void runWithCsScopeOnTranslationIdenticalToInputReturnsEnglishTextPlusNote() {
        String input = "English text the translator echoes back unchanged.";
        translatorStub.setResponseText(input);

        String result = pipeline.run(input, "cs");

        // Condition (b): output byte-identical to the post-sanitizer-1 input.
        assertEquals(input + "\n" + bundleLoaderStub.noteFor("cs"), result,
                "translation identical to the input must return the English text plus the fallback note");
        assertEquals(0, sanitizer.sanitizeCallCount(),
                "identical-output fallback must NOT invoke sanitizer-2");
        assertTrue(cache.get(input, "cs").isEmpty(),
                "identical-output fallback must NOT populate the cache");
    }

    @Test
    void fallsBackToEnglishWhenOutputCarriesNoTargetScript() {
        String input = "English prose the translator refuses to translate.";
        // Condition (d): a translator that answers a ru-scope request in
        // English. The reply is neither blank nor byte-identical to the
        // input, so (b) and (c) both pass it — this is the exact failure
        // only the target-script check can see.
        translatorStub.setResponseText("Sorry, I cannot translate that text.");

        String result = pipeline.run(input, "ru");

        assertEquals(input + "\n" + bundleLoaderStub.noteFor("ru"), result,
                "output with zero Cyrillic characters must return the English text "
                        + "plus the fallback note");
        assertEquals(BundleKeys.REPLY_TRANSLATION_UNAVAILABLE, bundleLoaderStub.lastKey(),
                "the note must be resolved from the translation-unavailable bundle key");
        assertEquals("ru", bundleLoaderStub.lastLang(),
                "the note must be resolved in the scope language");
        assertEquals(0, sanitizer.sanitizeCallCount(),
                "no-target-script fallback must NOT invoke sanitizer-2");
        assertTrue(cache.get(input, "ru").isEmpty(),
                "no-target-script fallback must NOT populate the cache");
    }

    @Test
    void runWithRuScopeDeliversOutputCarryingCyrillic() {
        String input = "English prose the translator handles correctly.";
        // The control for the check above: one Cyrillic character is the
        // whole threshold, and real Russian prose keeps Latin fragments
        // (proper nouns, numbers) that must not trip it.
        translatorStub.setResponseText("Обзор новостей Bitcoin за 2026 год");

        String result = pipeline.run(input, "ru");

        assertEquals("Обзор новостей Bitcoin за 2026 год", result,
                "a translation carrying Cyrillic must be delivered unchanged");
        assertFalse(result.contains(bundleLoaderStub.noteFor("ru")),
                "a valid Cyrillic translation must NOT append the fallback note");
        assertEquals(1, sanitizer.sanitizeCallCount(),
                "the happy path must invoke sanitizer-2 exactly once");
        assertTrue(cache.get(input, "ru").isPresent(),
                "the happy path must populate the cache");
    }

    @Test
    void latinTargetIsNotSubjectedToTheTargetScriptCheck() {
        String input = "English text whose translation carries no letters at all.";
        // Spec §Failure handling scopes (d) to non-Latin targets: for a
        // Latin target, byte-identity — condition (b) — IS the check, and
        // the character test can never fire against Latin English input.
        // A letterless cs translation is therefore delivered, not refused;
        // this pins that adding ru left cs behavior byte-for-byte unchanged.
        translatorStub.setResponseText("2026 — 42 %");

        String result = pipeline.run(input, "cs");

        assertEquals("2026 — 42 %", result,
                "a Latin-target translation must not be tested for target-script characters");
        assertFalse(result.contains(bundleLoaderStub.noteFor("cs")),
                "a Latin-target translation must NOT append the fallback note");
        assertTrue(cache.get(input, "cs").isPresent(),
                "a Latin-target translation must populate the cache");
    }

    // -- test stubs --

    /**
     * Records {@link TranslationProvider#translate} calls. Returns a
     * fixed response or throws on demand.
     */
    static final class RecordingTranslationProvider implements TranslationProvider {
        private final AtomicInteger callCount = new AtomicInteger();
        private volatile String responseText;
        private volatile boolean throwOnCall;

        RecordingTranslationProvider(String responseText) {
            this.responseText = responseText;
        }

        @Override
        public String translate(String text, Locale from, Locale to) {
            if (throwOnCall) {
                throw new RuntimeException("translator stub: simulated failure");
            }
            callCount.incrementAndGet();
            return responseText;
        }

        void setThrowOnCall(boolean shouldThrow) {
            this.throwOnCall = shouldThrow;
        }

        void setResponseText(String responseText) {
            this.responseText = responseText;
        }

        int callCount() {
            return callCount.get();
        }
    }

    /**
     * Stub {@link BundleLoader} that bypasses the real {@code @PostConstruct}
     * bundle load (no classpath properties needed) and returns a sentinel
     * note for the 2-arg accessor, recording the key + language the pipeline
     * resolved with. The real bundle's content is asserted separately by
     * {@code BundleLoaderTest}; this stub only proves the pipeline's wiring.
     */
    static final class StubBundleLoader extends BundleLoader {
        private volatile String lastKey;
        private volatile String lastLang;

        @Override
        public String get(String key, String langCode) {
            this.lastKey = key;
            this.lastLang = langCode;
            return noteFor(langCode);
        }

        /** Deterministic sentinel note, independent of recorded state. */
        String noteFor(String langCode) {
            return "translation-fallback-note[" + langCode + "]";
        }

        String lastKey() {
            return lastKey;
        }

        String lastLang() {
            return lastLang;
        }
    }

    /**
     * Extends {@link LlmOutputSanitizer} to count {@code sanitize}
     * calls. The real sanitizer runs (stripping any command tokens)
     * and the counter tracks invocations for assertion.
     */
    static final class CountingSanitizer extends LlmOutputSanitizer {
        private final AtomicInteger callCount = new AtomicInteger();

        CountingSanitizer() {
            // The no-arg sanitizer seam was removed (M1-363); super.sanitize()
            // below always emits audit rows, so supply the no-op collaborators.
            super(SanitizerTestDoubles.noOpAuditLogWriter(),
                  SanitizerTestDoubles.noOpDataSource());
        }

        @Override
        public String sanitize(String llmOutput) {
            callCount.incrementAndGet();
            return super.sanitize(llmOutput);
        }

        int sanitizeCallCount() {
            return callCount.get();
        }
    }
}
