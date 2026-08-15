package app.zcat.infochat.llm.metrics;

import app.zcat.infochat.llm.LlmCallBudget;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.impl.LlmCallFailedException;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerRegistry;
import app.zcat.infochat.llm.routing.LlmRouter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The metered decorator's streaming mirror: one streaming call is ONE
 * metric event (never one per chunk), the model label is
 * operator-config (never the wire-reported string), the terminal
 * usage frame passes the same impossible-count boundary checks, and
 * the full wrapper chain — metered outside breaker outside budget —
 * wraps the streaming shape end to end.
 */
class MeteredLlmProviderStreamingTest {

    private static final String CONFIGURED_MODEL = "model-chat";

    private SimpleMeterRegistry registry;
    private LlmMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new LlmMetrics(registry);
    }

    private MeteredLlmProvider metered(LlmProvider delegate, String maxTokens) {
        Map<String, String> config = maxTokens == null
            ? Map.of(ModelTask.CHAT_AGENT.configPrefix() + "model", CONFIGURED_MODEL)
            : Map.of(
                ModelTask.CHAT_AGENT.configPrefix() + "model", CONFIGURED_MODEL,
                ModelTask.CHAT_AGENT.configPrefix() + "max-tokens", maxTokens);
        return new MeteredLlmProvider(delegate, metrics, LlmRouter.ConfigReader.fromMap(config));
    }

    private double callsTotal(String outcome) {
        return registry.get("llm.calls.total")
            .tags("task", "chat", "provider", "scripted", "model", CONFIGURED_MODEL,
                "outcome", outcome)
            .counter().count();
    }

    @Test
    void emitsOncePerStreamingCallWithConfigSourcedModelLabel() {
        StreamingLlmStub delegate = new StreamingLlmStub(
            List.of("Hel", "lo", "!"), new LlmResponse.TokenUsage(10, 4), "wire-model-9");
        MeteredLlmProvider metered = metered(delegate, null);
        List<String> chunks = new CopyOnWriteArrayList<>();

        LlmResponse response = metered.generateStreaming(
            ModelTask.CHAT_AGENT, "sys", "usr", chunks::add);

        assertEquals("Hello!", response.text());
        assertEquals(List.of("Hel", "lo", "!"), chunks, "chunks pass through untouched");
        assertEquals(1.0, callsTotal("ok"),
            "three chunks are ONE streaming call — one counter increment, never per chunk");
        assertEquals(1, registry.get("llm.latency.ms")
            .tags("task", "chat", "provider", "scripted", "model", CONFIGURED_MODEL)
            .timer().count(), "one latency sample for the whole call");
        assertEquals(10.0, registry.get("llm.tokens.in")
            .tags("task", "chat", "provider", "scripted", "model", CONFIGURED_MODEL)
            .counter().count(), "token counts come from the terminal usage frame");
        assertEquals(4.0, registry.get("llm.tokens.out")
            .tags("task", "chat", "provider", "scripted", "model", CONFIGURED_MODEL)
            .counter().count());
        assertNull(registry.find("llm.calls.total")
            .tags("task", "chat", "provider", "scripted", "model", "wire-model-9", "outcome", "ok")
            .counter(), "the wire-reported model string must never mint a meter label");
    }

    @Test
    void impossibleTerminalUsageIsDiscardedWhole() {
        StreamingLlmStub negativeInput =
            new StreamingLlmStub(List.of("x"), new LlmResponse.TokenUsage(-5, 4), null);
        MeteredLlmProvider metered = metered(negativeInput, null);

        metered.generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", c -> { });

        assertEquals(1.0, callsTotal("ok"), "the call itself is fine — only the usage report dies");
        assertNull(registry.find("llm.tokens.in").counter(),
            "an impossible count discards the report whole — never clamped into a counter");
        assertNull(registry.find("llm.tokens.out").counter());

        StreamingLlmStub overCapOutput =
            new StreamingLlmStub(List.of("x"), new LlmResponse.TokenUsage(10, 99_999), null);
        MeteredLlmProvider capped = metered(overCapOutput, "600");

        capped.generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", c -> { });

        assertEquals(2.0, callsTotal("ok"));
        assertNull(registry.find("llm.tokens.out").counter(),
            "an output count over the request's generation cap is impossible and discarded");
    }

    @Test
    void failedStreamRecordsFailOutcomeOnceAndRethrows() {
        StreamingLlmStub delegate = new StreamingLlmStub(List.of("Hel"),
            new LlmCallFailedException("malformed frame"), null);
        MeteredLlmProvider metered = metered(delegate, null);

        assertThrows(LlmCallFailedException.class,
            () -> metered.generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", c -> { }));

        assertEquals(1.0, registry.get("llm.calls.total")
            .tags("task", "chat", "provider", "scripted", "model", "unknown", "outcome", "fail")
            .counter().count(), "a failed stream is one fail outcome, model unknown as on generate");
    }

    @Test
    void fullWrapperChainStreamsThroughEveryLayer() {
        StreamingLlmStub delegate =
            new StreamingLlmStub(List.of("Hel", "lo"), new LlmResponse.TokenUsage(7, 2), null);
        LlmCircuitBreakerRegistry breakers = new LlmCircuitBreakerRegistry(1, 60_000,
            Clock.systemUTC(), LlmRouter.ConfigReader.fromMap(
                Map.of(ModelTask.CHAT_AGENT.baseUrlKey(), "http://stub-endpoint")));
        MeteredLlmProvider chain = metered(
            new CircuitBreakingLlmProvider(new BudgetedLlmProvider(delegate), breakers), null);
        List<String> chunks = new CopyOnWriteArrayList<>();
        AtomicInteger draws = new AtomicInteger();

        LlmResponse response = LlmCallBudget.callWith(() -> draws.incrementAndGet() > 0,
            () -> chain.generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", chunks::add));

        assertEquals("Hello", response.text());
        assertEquals(List.of("Hel", "lo"), chunks);
        assertEquals(1, draws.get(), "one streaming call is exactly one budget draw");
        assertEquals(1.0, callsTotal("ok"), "the metered layer records the streamed call once");
        assertEquals(false, breakers.wouldShortCircuit(ModelTask.CHAT_AGENT),
            "a successful stream through the chain proves reachability, not an outage");
    }

    @Test
    void budgetRefusalFailsTheStreamWithoutTrippingTheBreaker() {
        StreamingLlmStub delegate =
            new StreamingLlmStub(List.of("Hel", "lo"), new LlmResponse.TokenUsage(7, 2), null);
        LlmCircuitBreakerRegistry breakers = new LlmCircuitBreakerRegistry(1, 60_000,
            Clock.systemUTC(), LlmRouter.ConfigReader.fromMap(
                Map.of(ModelTask.CHAT_AGENT.baseUrlKey(), "http://stub-endpoint")));
        MeteredLlmProvider chain = metered(
            new CircuitBreakingLlmProvider(new BudgetedLlmProvider(delegate), breakers), null);

        assertThrows(LlmCallBudget.RefusedException.class,
            () -> LlmCallBudget.callWith(() -> false,
                () -> chain.generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", c -> { })));
        assertEquals(1.0, registry.get("llm.calls.total")
            .tags("task", "chat", "provider", "scripted", "model", "unknown", "outcome", "fail")
            .counter().count(), "the refusal surfaces as a failed call through the metered layer");
        assertEquals(false, breakers.wouldShortCircuit(ModelTask.CHAT_AGENT),
            "a spend refusal is not endpoint evidence — the breaker must stay closed");
        assertEquals(0, delegate.streamingCalls, "a refused call never reaches the provider");
    }

    /**
     * Scripted streaming delegate: fixed chunks, then either a
     * terminal usage report or a failure; {@code wireModel} rides the
     * returned response so a label-regression would surface it.
     */
    private static final class StreamingLlmStub implements LlmProvider {
        private final List<String> chunks;
        private final LlmResponse.TokenUsage usage;
        private final RuntimeException failure;
        private final String wireModel;
        int streamingCalls;

        StreamingLlmStub(List<String> chunks, LlmResponse.TokenUsage usage, String wireModel) {
            this(chunks, null, wireModel, usage);
        }

        StreamingLlmStub(List<String> chunks, RuntimeException failure, String wireModel) {
            this(chunks, failure, wireModel, null);
        }

        private StreamingLlmStub(List<String> chunks, RuntimeException failure, String wireModel,
                                 LlmResponse.TokenUsage usage) {
            this.chunks = List.copyOf(chunks);
            this.failure = failure;
            this.wireModel = wireModel;
            this.usage = usage;
        }

        @Override
        public String providerName() {
            return "scripted";
        }

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            return new LlmResponse("unused");
        }

        @Override
        public boolean supportsStreaming(ModelTask task) {
            return true;
        }

        @Override
        public LlmResponse generateStreaming(ModelTask task, String systemPrompt, String userPrompt,
                                             Consumer<String> chunkConsumer) {
            streamingCalls++;
            StringBuilder text = new StringBuilder();
            for (String chunk : chunks) {
                text.append(chunk);
                chunkConsumer.accept(chunk);
            }
            if (failure != null) {
                throw failure;
            }
            return new LlmResponse(text.toString(), wireModel, usage);
        }
    }
}
