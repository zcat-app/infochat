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
                ModelTask.SECURITY_JUDGE.configPrefix() + "model", "stub-model",
                // The generation cap the request would carry, so it is also the
                // most output tokens an honest reply for this task can report
                // (M1-677). Only SECURITY_JUDGE configures one explicitly; the
                // other tasks exercise the absent-key path, which is bounded by
                // the same 1024 default their requests would carry, not
                // unbounded.
                ModelTask.SECURITY_JUDGE.configPrefix() + "max-tokens", "256")));
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
    void negativeReportedTokenCountsNeverMoveTheTokenCountersBackwards() {
        meteredLlm.generate(ModelTask.SECURITY_JUDGE, "", "prompt");
        llmStub.wireUsage = new LlmResponse.TokenUsage(-40, -25);

        meteredLlm.generate(ModelTask.SECURITY_JUDGE, "", "prompt");

        // A Prometheus counter that moves backwards reads downstream as a
        // counter reset, so every rate() over the series silently mis-reports
        // instead of showing an obvious anomaly. The second call must leave
        // the first call's honest totals exactly where they were.
        assertEquals(10.0, registry.get("llm.tokens.in")
            .tags("task", "security", "provider", "stub", "model", "stub-model")
            .counter().count());
        assertEquals(5.0, registry.get("llm.tokens.out")
            .tags("task", "security", "provider", "stub", "model", "stub-model")
            .counter().count());
        // Both calls are still counted: calls.total outrunning the token
        // counters is exactly how a discarded report stays visible.
        assertEquals(2.0, registry.get("llm.calls.total")
            .tags("task", "security", "provider", "stub", "model", "stub-model", "outcome", "ok")
            .counter().count());
    }

    @Test
    void outputTokenCountAboveTheConfiguredMaxTokensIsNotRecorded() {
        // 50k output tokens under a 256-token generation cap the request itself
        // carried: impossible for a server that obeyed the cap.
        llmStub.wireUsage = new LlmResponse.TokenUsage(10, 50_000);

        meteredLlm.generate(ModelTask.SECURITY_JUDGE, "", "prompt");

        // Discarded whole rather than clamped to the bound, and the honest-looking
        // input count goes with it: a clamped figure would be indistinguishable
        // from an honest max-length completion, hiding that anything happened.
        assertNull(registry.find("llm.tokens.out").counter());
        assertNull(registry.find("llm.tokens.in").counter());
        assertEquals(1.0, registry.get("llm.calls.total")
            .tags("task", "security", "provider", "stub", "model", "stub-model", "outcome", "ok")
            .counter().count());
    }

    @Test
    void taskWithNoConfiguredMaxTokensIsStillBoundedByTheDefaultTheRequestCarries() {
        // TAGGER configures no max-tokens — the shipped state for every task
        // except chat and summarizer, since no properties file sets the key.
        // Its request still carries the 1024 default OpenAiCompatibleProvider
        // sends, so 4_000 output tokens is as impossible here as an over-cap
        // count is for a task with an explicit key. Reading an absent key as
        // "unbounded" would leave the check dead in every real deployment.
        llmStub.wireUsage = new LlmResponse.TokenUsage(10, 4_000);

        meteredLlm.generate(ModelTask.TAGGER, "", "prompt");

        assertNull(registry.find("llm.tokens.out").counter());
        assertNull(registry.find("llm.tokens.in").counter());
        assertEquals(1.0, registry.get("llm.calls.total")
            .tags("task", "tagger", "provider", "stub", "model", "unknown", "outcome", "ok")
            .counter().count());
    }

    @Test
    void outputCountWithinTheDefaultCapIsRecordedForATaskWithNoConfiguredMaxTokens() {
        // The complement of the test above: the default bounds the count, it
        // does not suppress reporting. Without this, discarding every TAGGER
        // record would pass the test above just as well.
        llmStub.wireUsage = new LlmResponse.TokenUsage(10, 1_024);

        meteredLlm.generate(ModelTask.TAGGER, "", "prompt");

        assertEquals(1024.0, registry.get("llm.tokens.out")
            .tags("task", "tagger", "provider", "stub", "model", "unknown")
            .counter().count());
    }

    @Test
    void inputCountFarAboveWhatThePromptCouldTokenizeToIsNotRecorded() {
        // Counters are monotonic, so one such report makes every later honest
        // increment invisible for the JVM lifetime — the "poisoned for the
        // process lifetime" shape M1-673 closed for the model tag. The bound
        // comes from the prompt the decorator itself was handed, so it needs
        // no config and holds in both services.
        llmStub.wireUsage = new LlmResponse.TokenUsage(Long.MAX_VALUE, 5);

        meteredLlm.generate(ModelTask.SECURITY_JUDGE, "", "prompt");

        assertNull(registry.find("llm.tokens.in").counter());
        assertNull(registry.find("llm.tokens.out").counter());
        assertEquals(1.0, registry.get("llm.calls.total")
            .tags("task", "security", "provider", "stub", "model", "stub-model", "outcome", "ok")
            .counter().count());
    }

    @Test
    void inputCountAboveTheRawPromptLengthButWithinTemplateOverheadIsStillRecorded() {
        // The bound must never discard an honest record just because the
        // provider's own chat-template markers pushed the count past what the
        // visible prompt alone tokenizes to. 1_000 exceeds 3 x 6 prompt chars
        // by orders of magnitude and is still kept; the allowance stays small
        // enough that it cannot hide sustained phantom usage.
        llmStub.wireUsage = new LlmResponse.TokenUsage(1_000, 5);

        meteredLlm.generate(ModelTask.SECURITY_JUDGE, "", "prompt");

        assertEquals(1000.0, registry.get("llm.tokens.in")
            .tags("task", "security", "provider", "stub", "model", "stub-model")
            .counter().count());
    }

    @Test
    void inputCountJustAboveTheOverheadAllowanceIsNotRecorded() {
        // Pins the allowance as bounded rather than a blanket admission: with a
        // 6-char prompt the ceiling is 3 x 6 + 1024, so 2_000 is rejected even
        // though it is nowhere near the counter-destroying magnitudes. Without
        // this, widening the slack would silently pass the whole suite.
        llmStub.wireUsage = new LlmResponse.TokenUsage(2_000, 5);

        meteredLlm.generate(ModelTask.SECURITY_JUDGE, "", "prompt");

        assertNull(registry.find("llm.tokens.in").counter());
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

        /** The reported token counts: endpoint-chosen, so equally never trusted. */
        LlmResponse.TokenUsage wireUsage = new LlmResponse.TokenUsage(10, 5);

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            observed.set(LlmCallContext.current());
            if (failure != null) {
                throw failure;
            }
            return new LlmResponse("reply", wireModel, wireUsage);
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
