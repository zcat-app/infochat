package app.zcat.infochat.llm.metrics;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.impl.LlmCallFailedException;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerRegistry;
import app.zcat.infochat.llm.routing.LlmRouter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The breaker's streaming mirror: a mid-stream failure classified by
 * the provider pipeline trips or spares the breaker exactly as the
 * same class does on the single-string path, and the wrapper chain
 * wraps the streaming shape — no streaming call bypasses the
 * decorator.
 */
class CircuitBreakingLlmProviderStreamingTest {

    private static LlmCircuitBreakerRegistry thresholdOneRegistry() {
        // The breaker keys by the task's resolved endpoint, so the config
        // must carry one — an empty config resolves no key and records
        // nothing (the registry's bypass posture for unresolvable tasks).
        return new LlmCircuitBreakerRegistry(1, 60_000, Clock.systemUTC(),
            LlmRouter.ConfigReader.fromMap(Map.of(
                ModelTask.CHAT_AGENT.baseUrlKey(), "http://stub-endpoint")));
    }

    @Test
    void midStreamTransportFailureTripsTheBreaker() {
        ScriptedStreamingProvider delegate =
            new ScriptedStreamingProvider(List.of("Hel"), new LlmCallFailedException.ProviderUnreachableException(
                "connection dropped mid-stream"));
        LlmCircuitBreakerRegistry registry = thresholdOneRegistry();
        CircuitBreakingLlmProvider breaker = new CircuitBreakingLlmProvider(delegate, registry);

        assertThrows(LlmCallFailedException.ProviderUnreachableException.class,
            () -> breaker.generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", c -> { }));

        assertTrue(registry.wouldShortCircuit(ModelTask.CHAT_AGENT),
            "a mid-stream transport drop trips the endpoint breaker exactly like a "
                + "failed single-string call");
    }

    @Test
    void midStreamApplicationErrorProvesReachabilityAndDoesNotTrip() {
        ScriptedStreamingProvider delegate = new ScriptedStreamingProvider(
            List.of("Hel"), new LlmCallFailedException("malformed SSE frame"));
        LlmCircuitBreakerRegistry registry = thresholdOneRegistry();
        CircuitBreakingLlmProvider breaker = new CircuitBreakingLlmProvider(delegate, registry);

        assertThrows(LlmCallFailedException.class,
            () -> breaker.generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", c -> { }));

        assertEquals(false, registry.wouldShortCircuit(ModelTask.CHAT_AGENT),
            "the endpoint answered before failing — an application error never trips the breaker");
    }

    @Test
    void openBreakerShortCircuitsTheStreamingCallWithoutAttemptingIt() {
        ScriptedStreamingProvider delegate = new ScriptedStreamingProvider(
            List.of(), new LlmCallFailedException.ProviderUnreachableException("unreachable"));
        LlmCircuitBreakerRegistry registry = thresholdOneRegistry();
        CircuitBreakingLlmProvider breaker = new CircuitBreakingLlmProvider(delegate, registry);
        assertThrows(LlmCallFailedException.class,
            () -> breaker.generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", c -> { }));
        assertEquals(1, delegate.streamingCalls, "one failed attempt trips the threshold-one breaker");

        assertThrows(LlmCallFailedException.ProviderUnreachableException.class,
            () -> breaker.generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", c -> { }),
            "an OPEN breaker short-circuits the streaming call with the typed signal");

        assertEquals(1, delegate.streamingCalls,
            "the short-circuit issues no streaming attempt against the delegate");
    }

    @Test
    void nonStreamingDelegateReportsExplicitlyAndRefusesLoudly() {
        PlainLlmProvider delegate = new PlainLlmProvider();
        CircuitBreakingLlmProvider breaker =
            new CircuitBreakingLlmProvider(delegate, thresholdOneRegistry());

        assertEquals(false, breaker.supportsStreaming(ModelTask.CHAT_AGENT),
            "a delegate that cannot stream reports exactly that — no silent assumption");
        UnsupportedOperationException refusal = assertThrows(UnsupportedOperationException.class,
            () -> breaker.generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", c -> { }),
            "calling the streaming shape past an explicit cannot-stream verdict fails loudly");
        assertTrue(refusal.getMessage().contains("does not support streaming"),
            "the refusal names the provider and posture; got: " + refusal.getMessage());
        assertEquals("ok", breaker.generate(ModelTask.CHAT_AGENT, "sys", "usr").text(),
            "the single-string path through the same decorator is untouched");
    }

    /** Delivers {@code chunks} then throws {@code failure} (or returns when null). */
    private static final class ScriptedStreamingProvider implements LlmProvider {
        private final List<String> chunks;
        private final RuntimeException failure;
        int streamingCalls;

        ScriptedStreamingProvider(List<String> chunks, RuntimeException failure) {
            this.chunks = List.copyOf(chunks);
            this.failure = failure;
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
            return new LlmResponse(text.toString());
        }
    }

    /** A plain single-string provider — the explicit cannot-stream posture. */
    private static final class PlainLlmProvider implements LlmProvider {
        @Override
        public String providerName() {
            return "plain";
        }

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            return new LlmResponse("ok");
        }
    }
}
