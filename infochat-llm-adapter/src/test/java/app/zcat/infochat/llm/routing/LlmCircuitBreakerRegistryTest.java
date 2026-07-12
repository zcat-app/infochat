package app.zcat.infochat.llm.routing;

import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.impl.LlmCallFailedException;
import app.zcat.infochat.llm.impl.OpenAiCompatibleEmbeddingProvider.EmbeddingCallFailedException;
import app.zcat.infochat.llm.impl.OpenAiCompatibleEmbeddingProvider.EmbeddingProviderUnreachableException;
import app.zcat.infochat.llm.metrics.CircuitBreakingEmbeddingProvider;
import app.zcat.infochat.llm.metrics.CircuitBreakingLlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State-machine and short-circuit contract of
 * {@link LlmCircuitBreakerRegistry} plus the two breaker decorators
 * (M1-606), driven through the seam constructor with a manually-stepped
 * fixed {@link Clock} — this module has no Quarkus test harness, so the
 * clock is pinned via the constructor seam rather than QuarkusMock.
 * "No HTTP attempt" is observable as a stub provider whose call count
 * stays flat while the decorator keeps throwing the typed
 * provider-unreachable signal.
 */
class LlmCircuitBreakerRegistryTest {

    private static final int THRESHOLD = 3;
    private static final long COOLDOWN_MS = 30_000;
    private static final String DEFAULT_ENDPOINT = "http://llm.test:11434/v1";
    private static final String EMBED_ENDPOINT = "http://embed.test:11434/v1";

    private SteppableClock clock;
    private LlmCircuitBreakerRegistry registry;
    private CountingLlmStub llmStub;
    private CircuitBreakingLlmProvider llmBreaker;
    private CountingEmbeddingStub embedStub;
    private CircuitBreakingEmbeddingProvider embedBreaker;

    @BeforeEach
    void setUp() {
        clock = new SteppableClock(Instant.parse("2026-07-12T00:00:00Z"));
        registry = new LlmCircuitBreakerRegistry(THRESHOLD, COOLDOWN_MS, clock,
                LlmRouter.ConfigReader.fromMap(Map.of(
                        LlmRouter.CONFIG_KEY_DEFAULT_BASE_URL, DEFAULT_ENDPOINT,
                        LlmCircuitBreakerRegistry.CONFIG_KEY_EMBEDDINGS_BASE_URL,
                        EMBED_ENDPOINT)));
        llmStub = new CountingLlmStub();
        llmBreaker = new CircuitBreakingLlmProvider(llmStub, registry);
        embedStub = new CountingEmbeddingStub();
        embedBreaker = new CircuitBreakingEmbeddingProvider(embedStub, registry);
    }

    // --- CLOSED state ---

    @Test
    void closedByDefaultAdmitsCalls() {
        assertTrue(registry.tryAcquireForTask(ModelTask.CHAT_AGENT));
        assertFalse(registry.wouldShortCircuit(ModelTask.CHAT_AGENT));
        llmBreaker.generate(ModelTask.CHAT_AGENT, "", "hi");
        assertEquals(1, llmStub.calls);
    }

    @Test
    void belowThresholdConsecutiveTransportFailuresStaysClosed() {
        failTransport(THRESHOLD - 1);
        assertEquals(THRESHOLD - 1, llmStub.calls);
        assertFalse(registry.wouldShortCircuit(ModelTask.CHAT_AGENT));
        // Still admitted: the next call reaches the delegate.
        llmStub.mode = StubMode.SUCCEED;
        llmBreaker.generate(ModelTask.CHAT_AGENT, "", "hi");
        assertEquals(THRESHOLD, llmStub.calls);
    }

    @Test
    void reachableResponseResetsTheConsecutiveCount() {
        failTransport(THRESHOLD - 1);
        llmStub.mode = StubMode.SUCCEED;
        llmBreaker.generate(ModelTask.CHAT_AGENT, "", "hi");
        // The run of consecutive failures was broken: threshold-1 more
        // transport failures must NOT trip.
        failTransport(THRESHOLD - 1);
        assertFalse(registry.wouldShortCircuit(ModelTask.CHAT_AGENT));
        assertTrue(registry.tryAcquireForTask(ModelTask.CHAT_AGENT));
    }

    @Test
    void applicationErrorDoesNotTripTheBreaker() {
        // Acceptance pin: a provider that RESPONDS with an error (non-2xx,
        // parse, wrong shape → plain LlmCallFailedException) proves
        // reachability — however many arrive, every call keeps reaching
        // the delegate.
        llmStub.mode = StubMode.THROW_APPLICATION;
        for (int i = 0; i < THRESHOLD * 2; i++) {
            assertThrows(LlmCallFailedException.class,
                    () -> llmBreaker.generate(ModelTask.CHAT_AGENT, "", "hi"));
        }
        assertEquals(THRESHOLD * 2, llmStub.calls,
                "application errors must never short-circuit the delegate");
        assertFalse(registry.wouldShortCircuit(ModelTask.CHAT_AGENT));
    }

    // --- CLOSED → OPEN + short-circuit ---

    @Test
    void tripsOpenAfterThresholdConsecutiveTransportFailures() {
        failTransport(THRESHOLD);
        assertTrue(registry.wouldShortCircuit(ModelTask.CHAT_AGENT));
        assertFalse(registry.tryAcquireForTask(ModelTask.CHAT_AGENT));
    }

    @Test
    void openShortCircuitsWithoutAnHttpAttempt() {
        failTransport(THRESHOLD);
        int callsAtTrip = llmStub.calls;
        llmStub.mode = StubMode.SUCCEED; // would succeed if reached — must not be
        for (int i = 0; i < 5; i++) {
            assertThrows(LlmCallFailedException.ProviderUnreachableException.class,
                    () -> llmBreaker.generate(ModelTask.CHAT_AGENT, "", "hi"));
        }
        assertEquals(callsAtTrip, llmStub.calls,
                "an OPEN breaker must not attempt the HTTP call: the stub's "
                        + "call count stays flat");
    }

    @Test
    void shortCircuitedCallsDoNotExtendTheOpenWindow() {
        failTransport(THRESHOLD);
        // Hammer the OPEN breaker mid-cooldown: short-circuits are not
        // observations of the endpoint and must not move the probe
        // deadline (or the caller-side retry harness would double-step
        // the window per doomed call).
        clock.advance(Duration.ofMillis(COOLDOWN_MS / 2));
        for (int i = 0; i < 10; i++) {
            assertThrows(LlmCallFailedException.ProviderUnreachableException.class,
                    () -> llmBreaker.generate(ModelTask.CHAT_AGENT, "", "hi"));
        }
        clock.advance(Duration.ofMillis(COOLDOWN_MS / 2));
        assertTrue(registry.tryAcquireForTask(ModelTask.CHAT_AGENT),
                "exactly one cooldown after the trip, the probe must be admitted");
    }

    // --- OPEN → HALF_OPEN probe ---

    @Test
    void halfOpenAfterCooldownAdmitsExactlyOneProbe() {
        failTransport(THRESHOLD);
        clock.advance(Duration.ofMillis(COOLDOWN_MS));
        assertTrue(registry.tryAcquireForTask(ModelTask.CHAT_AGENT),
                "cooldown elapsed: the first caller becomes the probe");
        assertFalse(registry.tryAcquireForTask(ModelTask.CHAT_AGENT),
                "the probe slot is single: a second concurrent caller is denied");
    }

    @Test
    void successfulProbeClosesTheBreaker() {
        failTransport(THRESHOLD);
        clock.advance(Duration.ofMillis(COOLDOWN_MS));
        llmStub.mode = StubMode.SUCCEED;
        llmBreaker.generate(ModelTask.CHAT_AGENT, "", "probe");
        assertFalse(registry.wouldShortCircuit(ModelTask.CHAT_AGENT));
        llmBreaker.generate(ModelTask.CHAT_AGENT, "", "hi");
        llmBreaker.generate(ModelTask.CHAT_AGENT, "", "hi");
        assertEquals(THRESHOLD + 3, llmStub.calls, "CLOSED again: calls flow freely");
    }

    @Test
    void failedProbeReopensTheBreakerWithAFreshCooldown() {
        failTransport(THRESHOLD);
        clock.advance(Duration.ofMillis(COOLDOWN_MS));
        // The probe itself fails at transport level.
        failTransport(1);
        int callsAfterProbe = llmStub.calls;
        assertTrue(registry.wouldShortCircuit(ModelTask.CHAT_AGENT), "re-OPENED");
        assertThrows(LlmCallFailedException.ProviderUnreachableException.class,
                () -> llmBreaker.generate(ModelTask.CHAT_AGENT, "", "hi"));
        assertEquals(callsAfterProbe, llmStub.calls, "short-circuiting again");
        // A fresh full cooldown gates the next probe.
        clock.advance(Duration.ofMillis(COOLDOWN_MS - 1));
        assertFalse(registry.tryAcquireForTask(ModelTask.CHAT_AGENT));
        clock.advance(Duration.ofMillis(1));
        assertTrue(registry.tryAcquireForTask(ModelTask.CHAT_AGENT));
    }

    // --- ChatAgent's read-only pre-flight ---

    @Test
    void wouldShortCircuitTurnsFalseTheMomentTheCooldownElapses() {
        failTransport(THRESHOLD);
        assertTrue(registry.wouldShortCircuit(ModelTask.CHAT_AGENT));
        clock.advance(Duration.ofMillis(COOLDOWN_MS));
        // The next call is the probe — a real turn that deserves its
        // grounding, so the pre-flight must say "not short-circuited"...
        assertFalse(registry.wouldShortCircuit(ModelTask.CHAT_AGENT));
        // ...and being read-only it must NOT have consumed the probe slot.
        assertTrue(registry.tryAcquireForTask(ModelTask.CHAT_AGENT));
    }

    // --- endpoint keying ---

    @Test
    void tasksSharingOneEndpointShareBreakerState() {
        // All tasks here resolve to the shared default base-url (D56
        // one-LLM-service topology): tripping via CHAT_AGENT must
        // short-circuit SECURITY_JUDGE too.
        failTransport(THRESHOLD);
        assertTrue(registry.wouldShortCircuit(ModelTask.SECURITY_JUDGE));
        assertFalse(registry.tryAcquireForTask(ModelTask.TAGGER));
    }

    @Test
    void perTaskEndpointGetsAnIndependentBreaker() {
        registry = new LlmCircuitBreakerRegistry(THRESHOLD, COOLDOWN_MS, clock,
                LlmRouter.ConfigReader.fromMap(Map.of(
                        LlmRouter.CONFIG_KEY_DEFAULT_BASE_URL, DEFAULT_ENDPOINT,
                        ModelTask.CHAT_AGENT.baseUrlKey(), "http://pinned.test:9999/v1")));
        llmBreaker = new CircuitBreakingLlmProvider(llmStub, registry);
        // Trip the shared default endpoint via TAGGER.
        llmStub.mode = StubMode.THROW_TRANSPORT;
        for (int i = 0; i < THRESHOLD; i++) {
            assertThrows(LlmCallFailedException.ProviderUnreachableException.class,
                    () -> llmBreaker.generate(ModelTask.TAGGER, "", "hi"));
        }
        assertTrue(registry.wouldShortCircuit(ModelTask.TAGGER));
        // The per-task-pinned CHAT endpoint is a different breaker.
        assertFalse(registry.wouldShortCircuit(ModelTask.CHAT_AGENT));
        assertTrue(registry.tryAcquireForTask(ModelTask.CHAT_AGENT));
    }

    @Test
    void unresolvableEndpointBypassesTheBreaker() {
        registry = new LlmCircuitBreakerRegistry(THRESHOLD, COOLDOWN_MS, clock,
                LlmRouter.ConfigReader.fromMap(Map.of()));
        llmBreaker = new CircuitBreakingLlmProvider(llmStub, registry);
        // The stub-provider topology: no base-url anywhere. However many
        // typed failures arrive, nothing trips and nothing short-circuits.
        failTransport(THRESHOLD * 2);
        assertEquals(THRESHOLD * 2, llmStub.calls);
        assertFalse(registry.wouldShortCircuit(ModelTask.CHAT_AGENT));
        assertTrue(registry.tryAcquireForTask(ModelTask.CHAT_AGENT));
    }

    // --- embedding twin ---

    @Test
    void embeddingBreakerTripsAndShortCircuitsIndependently() {
        embedStub.mode = StubMode.THROW_TRANSPORT;
        for (int i = 0; i < THRESHOLD; i++) {
            assertThrows(EmbeddingProviderUnreachableException.class,
                    () -> embedBreaker.embed(List.of("text")));
        }
        int callsAtTrip = embedStub.calls;
        embedStub.mode = StubMode.SUCCEED;
        assertThrows(EmbeddingProviderUnreachableException.class,
                () -> embedBreaker.embed(List.of("text")));
        assertEquals(callsAtTrip, embedStub.calls,
                "an OPEN embedding breaker must not attempt the HTTP call");
        // The LLM endpoint (different base-url) is unaffected.
        assertTrue(registry.tryAcquireForTask(ModelTask.CHAT_AGENT));
        // And after the cooldown a successful probe closes it again.
        clock.advance(Duration.ofMillis(COOLDOWN_MS));
        embedBreaker.embed(List.of("text"));
        assertTrue(registry.tryAcquireForEmbeddings(), "closed again");
    }

    @Test
    void embeddingApplicationErrorDoesNotTripTheBreaker() {
        embedStub.mode = StubMode.THROW_APPLICATION;
        for (int i = 0; i < THRESHOLD * 2; i++) {
            assertThrows(EmbeddingCallFailedException.class,
                    () -> embedBreaker.embed(List.of("text")));
        }
        assertEquals(THRESHOLD * 2, embedStub.calls);
        assertTrue(registry.tryAcquireForEmbeddings());
    }

    // --- helpers ---

    private void failTransport(int times) {
        StubMode before = llmStub.mode;
        llmStub.mode = StubMode.THROW_TRANSPORT;
        for (int i = 0; i < times; i++) {
            assertThrows(LlmCallFailedException.ProviderUnreachableException.class,
                    () -> llmBreaker.generate(ModelTask.CHAT_AGENT, "", "hi"));
        }
        llmStub.mode = before;
    }

    private enum StubMode { SUCCEED, THROW_TRANSPORT, THROW_APPLICATION }

    private static final class CountingLlmStub implements LlmProvider {
        int calls;
        StubMode mode = StubMode.SUCCEED;

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            calls++;
            return switch (mode) {
                case SUCCEED -> new LlmResponse("ok");
                case THROW_TRANSPORT -> throw new LlmCallFailedException.ProviderUnreachableException(
                        "stub: connection refused");
                case THROW_APPLICATION -> throw new LlmCallFailedException(
                        "stub: non-2xx status 500");
            };
        }
    }

    private static final class CountingEmbeddingStub implements EmbeddingProvider {
        int calls;
        StubMode mode = StubMode.SUCCEED;

        @Override
        public List<EmbeddingResult> embed(List<String> texts) {
            calls++;
            return switch (mode) {
                case SUCCEED -> List.of(new EmbeddingResult(new float[] {0.1f}));
                case THROW_TRANSPORT -> throw new EmbeddingProviderUnreachableException(
                        "stub: connection refused");
                case THROW_APPLICATION -> throw new EmbeddingCallFailedException(
                        "stub: non-2xx status 500");
            };
        }
    }

    /**
     * A fixed clock that only moves when the test steps it — the
     * deterministic stand-in for the app-wide producer this module's
     * plain-JUnit tests can't QuarkusMock.
     */
    private static final class SteppableClock extends Clock {
        private Instant now;

        SteppableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration step) {
            now = now.plus(step);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
