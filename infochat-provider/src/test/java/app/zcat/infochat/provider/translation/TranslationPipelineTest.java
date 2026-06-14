package app.zcat.infochat.provider.translation;

import app.zcat.infochat.messaging.TranslationProvider;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @BeforeEach
    void setUp() {
        cache = new TranslationCache();
        translatorStub = new RecordingTranslationProvider("translated-cs");
        sanitizer = new CountingSanitizer();

        pipeline = new TranslationPipeline();
        pipeline.translationCache = cache;
        pipeline.translationProvider = translatorStub;
        pipeline.llmOutputSanitizer = sanitizer;
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
        // to strip), so the result equals the translator's output.
        assertEquals("translated-cs", result);

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
    void runWithCsScopeOnTranslatorFailureReturnsPostSanitizer1EnglishText() {
        String input = "English fallback text.";
        translatorStub.setThrowOnCall(true);

        String result = pipeline.run(input, "cs");

        assertEquals(input, result,
                "translator failure must return the post-sanitizer-1 English text");
        assertEquals(0, sanitizer.sanitizeCallCount(),
                "translator failure must NOT invoke sanitizer-2");
        assertTrue(cache.get(input, "cs").isEmpty(),
                "translator failure must NOT populate the cache");
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

        int callCount() {
            return callCount.get();
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
