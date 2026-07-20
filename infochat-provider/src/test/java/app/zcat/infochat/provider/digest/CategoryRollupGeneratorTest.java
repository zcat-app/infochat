package app.zcat.infochat.provider.digest;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit unit tests for {@link CategoryRollupGenerator}. The
 * {@link LlmProvider} collaborator is a hand-rolled stub (no Mockito on
 * the Provider classpath); the sanitizer and translator are recording
 * subclasses so the test can prove the LLM output runs through both
 * before reaching the caller.
 */
class CategoryRollupGeneratorTest {

    @Test
    void producesOneRollupPerCategory() {
        CapturingStub stub = new CapturingStub();
        stub.responseText.set("theme synthesis");
        CategoryRollupGenerator gen = generatorWith(stub, new IdentitySanitizer(), new IdentityPipeline());
        gen.categorySummaryEnabled = true;

        // Three category calls → exactly three LLM calls (one roll-up per
        // category). The digest already makes one cluster-prose call per
        // cluster, so the added volume is proportionally small.
        Optional<String> r1 = gen.generateRollup(singletonClusterList("p-a", "Title A"), "en");
        Optional<String> r2 = gen.generateRollup(singletonClusterList("p-b", "Title B"), "en");
        Optional<String> r3 = gen.generateRollup(singletonClusterList("p-c", "Title C"), "en");

        assertEquals(3, stub.callCount.get(), "exactly one LLM call per category");
        assertTrue(r1.isPresent() && r2.isPresent() && r3.isPresent(),
                "each category produces a roll-up");
    }

    @Test
    void flagOffYieldsNoRollupAndNoLlmCall() {
        CapturingStub stub = new CapturingStub();
        CategoryRollupGenerator gen = generatorWith(stub, new IdentitySanitizer(), new IdentityPipeline());
        // categorySummaryEnabled left at its default false.

        Optional<String> result = gen.generateRollup(singletonClusterList("p-a", "Title A"), "en");

        assertTrue(result.isEmpty(), "flag off → no roll-up");
        assertEquals(0, stub.callCount.get(), "flag off → no LLM call");
    }

    @Test
    void rollupIsSanitizedAndTranslated() {
        CapturingStub stub = new CapturingStub();
        stub.responseText.set("raw LLM theme synthesis");
        RecordingSanitizer sanitizer = new RecordingSanitizer();
        RecordingPipeline pipeline = new RecordingPipeline();
        CategoryRollupGenerator gen = generatorWith(stub, sanitizer, pipeline);
        gen.categorySummaryEnabled = true;

        Optional<String> result = gen.generateRollup(singletonClusterList("p-a", "Title A"), "cs");

        assertTrue(result.isPresent());
        // The LLM output runs through the sanitizer first (security.md §LLM
        // output sanitizer is unconditional: "before any LLM-generated text
        // is delivered to a user").
        assertEquals(List.of("raw LLM theme synthesis"), sanitizer.inputs,
                "LLM output is sanitized before anything else");
        // The sanitized text then runs through the translation pipeline
        // (llm.md: TranslationPipeline re-runs the sanitizer on translated
        // text — the same treatment cluster prose gets in DigestRenderer).
        assertEquals(List.of("raw LLM theme synthesis"), pipeline.inputs);
        assertEquals("cs", pipeline.lastLanguage,
                "scope language forwarded to the translation pipeline");
        // The roll-up the caller receives is the pipeline's output, not the
        // raw LLM text.
        assertEquals("raw LLM theme synthesis", result.get());
    }

    @Test
    void failedRollupYieldsCategoryWithoutPrefix() {
        CapturingStub stub = new CapturingStub();
        stub.throwOnCall.set(true);
        CategoryRollupGenerator gen = generatorWith(stub, new IdentitySanitizer(), new IdentityPipeline());
        gen.categorySummaryEnabled = true;

        Optional<String> result = gen.generateRollup(singletonClusterList("p-a", "Title A"), "en");

        assertTrue(result.isEmpty(),
                "a roll-up LLM failure yields Optional.empty — the caller ships "
                        + "the category WITHOUT a prefix (exactly the flag-off shape)");
        assertEquals(1, stub.callCount.get(), "the failing LLM call was attempted");
    }

    @Test
    void refusalMarkerYieldsCategoryWithoutPrefix() {
        CapturingStub stub = new CapturingStub();
        stub.responseText.set("[REFUSAL: wrapped content asked for an action]");
        CategoryRollupGenerator gen = generatorWith(stub, new IdentitySanitizer(), new IdentityPipeline());
        gen.categorySummaryEnabled = true;

        Optional<String> result = gen.generateRollup(singletonClusterList("p-a", "Title A"), "en");

        assertTrue(result.isEmpty(),
                "an LLM refusal marker is treated as no-roll-up — never surface the "
                        + "marker (or any LLM-authored prose) to the user");
    }

    @Test
    void emptyLlmResponseYieldsCategoryWithoutPrefix() {
        CapturingStub stub = new CapturingStub();
        stub.responseText.set("");
        CategoryRollupGenerator gen = generatorWith(stub, new IdentitySanitizer(), new IdentityPipeline());
        gen.categorySummaryEnabled = true;

        Optional<String> result = gen.generateRollup(singletonClusterList("p-a", "Title A"), "en");

        assertTrue(result.isEmpty(),
                "an empty LLM response yields Optional.empty — no roll-up prefix");
    }

    // ----- helpers ----------------------------------------------------------

    private static CategoryRollupGenerator generatorWith(
            LlmProvider provider, LlmOutputSanitizer sanitizer, TranslationPipeline pipeline) {
        CategoryRollupGenerator gen = new CategoryRollupGenerator();
        gen.llmRouter = routerYielding(provider);
        gen.llmOutputSanitizer = sanitizer;
        gen.translationPipeline = pipeline;
        return gen;
    }

    private static LlmRouter routerYielding(LlmProvider provider) {
        return new LlmRouter(
                List.of(new LlmRouter.Entry("test-stub", provider, Set.of("en", "cs"))),
                LlmRouter.ConfigReader.fromMap(Map.of(
                        LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER, "test-stub")));
    }

    private static List<Cluster> singletonClusterList(String uid, String title) {
        Post p = new Post(UUID.randomUUID(), uid, UUID.randomUUID(), "Src", title,
                "https://example.com/" + uid, "Body for " + title, Instant.now(),
                List.of("news"), List.of("unknown"));
        return List.of(new Cluster("t-" + uid, List.of(p)));
    }

    /** Pass-through sanitizer that records its inputs (proof the LLM output was sanitized). */
    private static final class IdentitySanitizer extends LlmOutputSanitizer {
        IdentitySanitizer() {
            super(app.zcat.infochat.provider.testsupport.SanitizerTestDoubles.noOpAuditLogWriter(),
                    app.zcat.infochat.provider.testsupport.SanitizerTestDoubles.noOpDataSource());
        }

        @Override
        public String sanitize(String llmOutput) {
            return llmOutput;
        }
    }

    /** Pass-through translation pipeline that records its inputs and language. */
    private static final class IdentityPipeline extends TranslationPipeline {
        @Override
        public String run(String postSanitizer1English, String scopeLanguage) {
            return postSanitizer1English;
        }
    }

    /** Recording sanitizer: returns input unchanged, captures inputs for assertion. */
    private static final class RecordingSanitizer extends LlmOutputSanitizer {
        final List<String> inputs = new CopyOnWriteArrayList<>();

        RecordingSanitizer() {
            super(app.zcat.infochat.provider.testsupport.SanitizerTestDoubles.noOpAuditLogWriter(),
                    app.zcat.infochat.provider.testsupport.SanitizerTestDoubles.noOpDataSource());
        }

        @Override
        public String sanitize(String llmOutput) {
            inputs.add(llmOutput);
            return llmOutput;
        }
    }

    /** Recording pipeline: returns input unchanged, captures inputs + language for assertion. */
    private static final class RecordingPipeline extends TranslationPipeline {
        final List<String> inputs = new CopyOnWriteArrayList<>();
        volatile String lastLanguage;

        @Override
        public String run(String postSanitizer1English, String scopeLanguage) {
            inputs.add(postSanitizer1English);
            lastLanguage = scopeLanguage;
            return postSanitizer1English;
        }
    }

    /**
     * Hand-rolled {@link LlmProvider} stub mirroring the stub-and-flag
     * shape used in {@link app.zcat.infochat.provider.summary.SummaryProseGeneratorTest}.
     */
    private static final class CapturingStub implements LlmProvider {
        final AtomicInteger callCount = new AtomicInteger();
        final AtomicReference<String> responseText = new AtomicReference<>("default");
        final AtomicBoolean throwOnCall = new AtomicBoolean(false);

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            callCount.incrementAndGet();
            if (throwOnCall.get()) {
                throw new RuntimeException("LLM unreachable (test stub)");
            }
            return new LlmResponse(responseText.get());
        }
    }
}
