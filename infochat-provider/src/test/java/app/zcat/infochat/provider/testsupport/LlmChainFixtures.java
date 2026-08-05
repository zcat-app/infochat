package app.zcat.infochat.provider.testsupport;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.metrics.BudgetedLlmProvider;
import app.zcat.infochat.llm.metrics.CircuitBreakingLlmProvider;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerRegistry;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.messaging.TranslationProvider;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.summary.SummaryProseGenerator;
import app.zcat.infochat.provider.translation.LlmTranslationProvider;
import app.zcat.infochat.provider.translation.TranslationCache;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds a REAL LLM call chain for the tests that must count provider
 * calls rather than collaborator invocations (M1-769): a counting
 * {@link LlmProvider} stub behind {@link BudgetedLlmProvider}, an
 * {@link LlmRouter} over the decorated pair, and a
 * {@link TranslationPipeline} whose translator is a real
 * {@link LlmTranslationProvider} on the same router.
 *
 * <p>Existing fixtures cannot serve this: {@link TranslationFixtures}
 * injects a {@link TranslationProvider} lambda straight into the
 * pipeline, which is exactly the shortcut that makes a call-COUNT
 * assertion vacuous — nothing downstream of it ever reaches an
 * {@code LlmProvider}. The pipeline's collaborators are injected fields
 * with no test constructor, so they are set reflectively, the same way
 * {@link TranslationFixtures} does.</p>
 */
public final class LlmChainFixtures {

    private static final String PROVIDER_NAME = "counting-stub";

    private LlmChainFixtures() {
    }

    /**
     * The assembled chain. {@link #providerCalls()} is the ground truth a
     * budget assertion is compared against — always assert the budget
     * against THIS, never against a hand-computed constant, or the test
     * pins the arithmetic the implementation was written from rather than
     * the calls it made.
     */
    public static final class Chain {

        private final CountingLlmProvider stub;
        public final LlmRouter router;
        public final TranslationPipeline translationPipeline;
        /** Sized to open after ONE unreachable failure, so a test can trip it in one line. */
        public final LlmCircuitBreakerRegistry breakers;

        private Chain(CountingLlmProvider stub, LlmRouter router,
                      TranslationPipeline translationPipeline,
                      LlmCircuitBreakerRegistry breakers) {
            this.stub = stub;
            this.router = router;
            this.translationPipeline = translationPipeline;
            this.breakers = breakers;
        }

        /** Provider calls that actually reached the stub, across all tasks. */
        public int providerCalls() {
            return stub.calls.get();
        }

        /** Provider calls that reached the stub for one task. */
        public int providerCalls(ModelTask task) {
            return stub.callsByTask.computeIfAbsent(task, t -> new AtomicInteger()).get();
        }

        /** Make every subsequent call throw {@code failure} instead of answering. */
        public void failWith(@Nullable RuntimeException failure) {
            stub.failure = failure;
        }
    }

    /** A chain whose stub answers every call with distinct, usable text. */
    public static Chain newChain(BundleLoader bundleLoader, Clock clock) throws Exception {
        CountingLlmProvider stub = new CountingLlmProvider();
        // The breaker reads its endpoint from config, so it needs a
        // base-url to have any state to keep. Its own reader, kept apart
        // from the router's map so an endpoint key cannot perturb routing.
        LlmCircuitBreakerRegistry breakers = new LlmCircuitBreakerRegistry(1, 60_000, clock,
                LlmRouter.ConfigReader.fromMap(
                        Map.of(LlmRouter.CONFIG_KEY_DEFAULT_BASE_URL, "http://llm.invalid")));
        // Nested exactly as the container nests them by @Priority: the
        // breaker (APPLICATION + 100) OUTSIDE the budget decorator
        // (APPLICATION + 200), so a short-circuit never reaches the draw.
        LlmProvider decorated =
                new CircuitBreakingLlmProvider(new BudgetedLlmProvider(stub), breakers);
        LlmRouter router = new LlmRouter(
                List.of(new LlmRouter.Entry(PROVIDER_NAME, decorated, Set.of("en", "cs"))),
                LlmRouter.ConfigReader.fromMap(
                        Map.of(LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER, PROVIDER_NAME)));
        return new Chain(stub, router, newPipeline(router, bundleLoader), breakers);
    }

    /**
     * A {@link SummaryProseGenerator} on {@code chain}'s router. Its
     * collaborators are package-private in another package, hence
     * reflection.
     */
    public static SummaryProseGenerator newProseGenerator(Chain chain) throws Exception {
        SummaryProseGenerator generator = new SummaryProseGenerator();
        set(SummaryProseGenerator.class, generator, "llmRouter", chain.router);
        set(SummaryProseGenerator.class, generator, "llmOutputSanitizer",
                SanitizerTestDoubles.noAuditSanitizer());
        return generator;
    }

    private static TranslationPipeline newPipeline(LlmRouter router, BundleLoader bundleLoader)
            throws Exception {
        LlmTranslationProvider translator = new LlmTranslationProvider();
        set(LlmTranslationProvider.class, translator, "llmRouter", router);
        // @PostConstruct in production; a plain construction must call it
        // or the prompt template is never loaded.
        Method loadPromptTemplate =
                LlmTranslationProvider.class.getDeclaredMethod("loadPromptTemplate");
        loadPromptTemplate.setAccessible(true);
        loadPromptTemplate.invoke(translator);

        TranslationPipeline pipeline = new TranslationPipeline();
        set(TranslationPipeline.class, pipeline, "translationCache", new TranslationCache());
        set(TranslationPipeline.class, pipeline, "translationProvider", translator);
        set(TranslationPipeline.class, pipeline, "llmOutputSanitizer",
                SanitizerTestDoubles.noAuditSanitizer());
        set(TranslationPipeline.class, pipeline, "bundleLoader", bundleLoader);
        return pipeline;
    }

    private static void set(Class<?> owner, Object target, String fieldName, Object value)
            throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * Answers every call with text unique to that call. Uniqueness is
     * load-bearing twice over: the translation cache is keyed on the text
     * it is asked to translate, so repeated prose would turn later
     * clusters into cache hits and silently drop their calls; and
     * {@link TranslationPipeline} treats output equal to its input as
     * unusable and falls back.
     */
    private static final class CountingLlmProvider implements LlmProvider {

        private final AtomicInteger calls = new AtomicInteger();
        private final Map<ModelTask, AtomicInteger> callsByTask = new java.util.concurrent.ConcurrentHashMap<>();
        private volatile @Nullable RuntimeException failure;

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            int nth = calls.incrementAndGet();
            callsByTask.computeIfAbsent(task, t -> new AtomicInteger()).incrementAndGet();
            RuntimeException toThrow = failure;
            if (toThrow != null) {
                throw toThrow;
            }
            return new LlmResponse(task.keySegment() + " reply " + nth, "stub-model", null);
        }

        @Override
        public String providerName() {
            return PROVIDER_NAME;
        }
    }
}
