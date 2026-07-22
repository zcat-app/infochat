package app.zcat.infochat.llm.metrics;


import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import app.zcat.infochat.llm.LlmCallContext;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins M1-321's two acceptance surfaces: the per-call
 * {@link LlmCallContext} (constructed for every call, observable at
 * the provider boundary, one trace id stitching both SPI surfaces)
 * and the §5.9 Micrometer catalogue emitted through
 * {@link LlmMetrics} via the two decorators. Plain JUnit against
 * {@link SimpleMeterRegistry} — same no-CDI pattern as the router
 * tests.
 */
class LlmObservabilityTest {

    private SimpleMeterRegistry registry;
    private LlmMetrics metrics;
    private CapturingLlmProvider llmStub;
    private CapturingEmbeddingProvider embeddingStub;
    private MeteredLlmProvider meteredLlm;
    private MeteredEmbeddingProvider meteredEmbedding;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new LlmMetrics(registry);
        llmStub = new CapturingLlmProvider();
        embeddingStub = new CapturingEmbeddingProvider();
        // The model tag's only legitimate source is operator config, so the
        // meter assertions below read "stub-model" from this map, never the
        // deliberately-different model the stub reports on the wire (M1-673).
        meteredLlm = new MeteredLlmProvider(llmStub, metrics,
            LlmRouter.ConfigReader.fromMap(Map.of(
                ModelTask.SECURITY_JUDGE.configPrefix() + "model", "stub-model")));
        meteredEmbedding = new MeteredEmbeddingProvider(embeddingStub, metrics, "stub-embed-model");
    }

    @Test
    void sameTraceIdStitchesLlmAndEmbeddingCallsUnderOneContext() {
        LlmCallContext ambient = new LlmCallContext("trace-1", "scope-7", null, "en");

        LlmCallContext.callWith(ambient, () -> {
            meteredLlm.generate(ModelTask.SUMMARIZER, "", "prompt");
            return meteredEmbedding.embed(List.of("text"));
        });

        LlmCallContext seenByLlm = llmStub.observed.get();
        LlmCallContext seenByEmbedding = embeddingStub.observed.get();
        assertEquals("trace-1", seenByLlm.traceId());
        assertEquals("trace-1", seenByEmbedding.traceId());
        assertEquals("scope-7", seenByLlm.scopeId());
        assertEquals("en", seenByLlm.language());
        assertEquals(ModelTask.SUMMARIZER, seenByLlm.task());
        // The embedder is deliberately not a ModelTask (spec §SPI shape).
        assertNull(seenByEmbedding.task());
    }

    @Test
    void contextIsConstructedFreshForEveryCallWhenNoAmbientContextIsBound() {
        meteredLlm.generate(ModelTask.TAGGER, "", "first");
        String firstTraceId = llmStub.observed.get().traceId();
        meteredLlm.generate(ModelTask.TAGGER, "", "second");
        String secondTraceId = llmStub.observed.get().traceId();

        assertNotEquals(firstTraceId, secondTraceId);
    }

    @Test
    void okCallIncrementsCallsTotalTokensAndLatencyWithOkOutcome() {
        meteredLlm.generate(ModelTask.SECURITY_JUDGE, "", "prompt");

        assertEquals(1.0, registry.get("llm.calls.total")
            .tags("task", "security", "provider", "stub", "model", "stub-model", "outcome", "ok")
            .counter().count());
        assertEquals(10.0, registry.get("llm.tokens.in")
            .tags("task", "security", "provider", "stub", "model", "stub-model")
            .counter().count());
        assertEquals(5.0, registry.get("llm.tokens.out")
            .tags("task", "security", "provider", "stub", "model", "stub-model")
            .counter().count());
        assertEquals(1, registry.get("llm.latency.ms")
            .tags("task", "security", "provider", "stub", "model", "stub-model")
            .timer().count());
        assertEquals(0, metrics.llmInflight(ModelTask.SECURITY_JUDGE, "stub").get());
    }

    @Test
    void failedCallIncrementsCallsTotalWithFailOutcomeAndRethrows() {
        RuntimeException boom = new RuntimeException("boom");
        llmStub.failure = boom;

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> meteredLlm.generate(ModelTask.SECURITY_JUDGE, "", "prompt"));

        assertSame(boom, thrown);
        assertEquals(1.0, registry.get("llm.calls.total")
            .tags("task", "security", "provider", "stub", "model", "unknown", "outcome", "fail")
            .counter().count());
        // No response, no usage: token counters must not appear for the failed call.
        assertNull(registry.find("llm.tokens.in").counter());
        assertEquals(0, metrics.llmInflight(ModelTask.SECURITY_JUDGE, "stub").get());
    }

    @Test
    void hostileWireModelValuesDoNotGrowTheMeterRegistry() {
        // MiB-scale distinct model strings per reply: the shape a hostile or
        // compromised endpoint uses to mint one permanently-retained meter
        // (the tag string included) per call until the heap is gone. The
        // providers' bounded body read caps each string but not their number.
        for (int call = 0; call < 8; call++) {
            llmStub.wireModel = ("hostile-" + call).repeat(1 << 17);
            meteredLlm.generate(ModelTask.SECURITY_JUDGE, "", "prompt");
        }

        long callMeters = registry.getMeters().stream()
            .filter(meter -> meter.getId().getName().equals("llm.calls.total"))
            .count();
        assertEquals(1L, callMeters);
        assertEquals(8.0, registry.get("llm.calls.total")
            .tags("task", "security", "provider", "stub", "model", "stub-model", "outcome", "ok")
            .counter().count());
    }

    @Test
    void modelTagCarriesOperatorConfiguredIdNotTheWireReportedValue() {
        meteredLlm.generate(ModelTask.SECURITY_JUDGE, "", "prompt");

        assertEquals(1.0, registry.get("llm.calls.total")
            .tags("task", "security", "provider", "stub", "model", "stub-model", "outcome", "ok")
            .counter().count());
        // The constraint is applied at the decorator boundary, so no meter it
        // emits carries the endpoint-chosen string on any of the three names.
        assertNull(registry.find("llm.calls.total").tag("model", "wire-reported-model").counter());
        assertNull(registry.find("llm.latency.ms").tag("model", "wire-reported-model").timer());
        assertNull(registry.find("llm.tokens.in").tag("model", "wire-reported-model").counter());
    }

    @Test
    void okEmbeddingCallIncrementsCallsTotalAndSetsDimensionGauge() {
        meteredEmbedding.embed(List.of("text"));

        assertEquals(1.0, registry.get("embedding.calls.total")
            .tags("provider", "stub-embed", "model", "stub-embed-model", "outcome", "ok")
            .counter().count());
        assertEquals(3.0, registry.get("embedding.dimension")
            .tags("provider", "stub-embed", "model", "stub-embed-model")
            .gauge().value());
    }

    @Test
    void failedEmbeddingCallIncrementsCallsTotalWithFailOutcomeAndRethrows() {
        RuntimeException boom = new RuntimeException("embed-boom");
        embeddingStub.failure = boom;

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> meteredEmbedding.embed(List.of("text")));

        assertSame(boom, thrown);
        assertEquals(1.0, registry.get("embedding.calls.total")
            .tags("provider", "stub-embed", "model", "stub-embed-model", "outcome", "fail")
            .counter().count());
    }

    @Test
    void embeddingProviderNameForwardsDelegateStableNameNotDecoratorName() {
        // The delegate's stable constant ("stub-embed") differs from both
        // stub and decorator class names; without the override the interface
        // default would walk getClass() on MeteredEmbeddingProvider and return
        // "MeteredEmbeddingProvider", mislabelling the provider metric tag.
        assertEquals("stub-embed", meteredEmbedding.providerName());
        assertNotEquals("MeteredEmbeddingProvider", meteredEmbedding.providerName());
    }

    @Test
    void queueWaitRecordsTimerUnderCatalogueName() {
        metrics.recordQueueWait(ModelTask.SECURITY_JUDGE, "stub", Duration.ofMillis(5));

        assertEquals(1, registry.get("llm.queue.wait.ms")
            .tags("task", "security", "provider", "stub")
            .timer().count());
    }

    /** Captures the boundary-visible context; throws instead when {@code failure} is set. */
    private static final class CapturingLlmProvider implements LlmProvider {
        final AtomicReference<LlmCallContext> observed = new AtomicReference<>();
        @Nullable RuntimeException failure;
        /** The response-body {@code model} field: endpoint-chosen, so never a metric tag. */
        String wireModel = "wire-reported-model";

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            observed.set(LlmCallContext.current());
            if (failure != null) {
                throw failure;
            }
            return new LlmResponse("reply", wireModel, new LlmResponse.TokenUsage(10, 5));
        }

        @Override
        public String providerName() {
            return "stub";
        }
    }

    /** Embedding-side counterpart of {@link CapturingLlmProvider}; returns one 3-dim vector per input. */
    private static final class CapturingEmbeddingProvider implements EmbeddingProvider {
        final AtomicReference<LlmCallContext> observed = new AtomicReference<>();
        @Nullable RuntimeException failure;

        @Override
        public List<EmbeddingResult> embed(List<String> texts) {
            observed.set(LlmCallContext.current());
            if (failure != null) {
                throw failure;
            }
            return texts.stream()
                .map(text -> new EmbeddingResult(new float[] {1.0f, 2.0f, 3.0f}))
                .toList();
        }

        @Override
        public String providerName() {
            return "stub-embed";
        }
    }
}
