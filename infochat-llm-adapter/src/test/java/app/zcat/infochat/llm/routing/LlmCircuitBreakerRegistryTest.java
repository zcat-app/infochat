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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void llmAndEmbeddingsOnOneEndpointDoNotShareABreaker() {
        // The SHIPPED default points BOTH SPIs at the same local Ollama on
        // every profile, so this is the normal deployment, not a corner
        // case. They apply different patience to that one URL
        // (infochat.embeddings.timeout-ms 30s vs infochat.llm.chat.timeout-ms
        // 120s) and a read timeout classifies as transport-unreachable, so a
        // live-but-slow backend fails embeddings while chat still answers.
        // On a shared key those embedding failures would deny chat with no
        // HTTP attempt against an endpoint that is demonstrably answering —
        // and docs/spec/security.md §Failure handling promises the two are
        // tracked separately. (M1-769)
        String sharedEndpoint = "http://one-ollama.test:11434/v1";
        registry = new LlmCircuitBreakerRegistry(THRESHOLD, COOLDOWN_MS, clock,
                LlmRouter.ConfigReader.fromMap(Map.of(
                        LlmRouter.CONFIG_KEY_DEFAULT_BASE_URL, sharedEndpoint,
                        LlmCircuitBreakerRegistry.CONFIG_KEY_EMBEDDINGS_BASE_URL,
                        sharedEndpoint)));
        llmBreaker = new CircuitBreakingLlmProvider(llmStub, registry);
        embedBreaker = new CircuitBreakingEmbeddingProvider(embedStub, registry);

        embedStub.mode = StubMode.THROW_TRANSPORT;
        for (int i = 0; i < THRESHOLD; i++) {
            assertThrows(EmbeddingProviderUnreachableException.class,
                    () -> embedBreaker.embed(List.of("text")));
        }
        assertFalse(registry.tryAcquireForEmbeddings(),
                "precondition: the embedding breaker tripped on the shared URL");

        assertFalse(registry.wouldShortCircuit(ModelTask.CHAT_AGENT),
                "embedding transport failures must not deny the generative SPI "
                        + "sharing the same base-url");
        assertTrue(registry.tryAcquireForTask(ModelTask.CHAT_AGENT));
        llmBreaker.generate(ModelTask.CHAT_AGENT, "", "hi");
        assertEquals(1, llmStub.calls, "the generative call still reaches the endpoint");
    }

    // --- caller-side cancellation is not endpoint evidence (M1-769) ---

    @Test
    void interruptedCallDoesNotResetTheConsecutiveCount() {
        // An interrupted caller sends no request (M1-764, pinned by
        // HttpProviderSharedPipelineTest) — but the interrupt surfaces as a
        // PLAIN LlmCallFailedException, the very type an answered-with-500
        // endpoint produces and which applicationErrorDoesNotTripTheBreaker
        // above correctly credits as reachability. Telling those two apart is
        // the point: crediting a call that sent nothing closes the breaker and
        // zeroes this counter, and M1-763's cancelled render issues a whole
        // LOOP of such calls, so the breaker could never re-trip while an
        // orphaned render drains.
        //
        // The consecutive-failure count is the observable that discriminates.
        // wouldShortCircuit() reads the same either way once the probe is
        // released, but a reset count cannot trip and an intact one can.
        failTransport(THRESHOLD - 1);
        assertFalse(registry.wouldShortCircuit(ModelTask.CHAT_AGENT),
                "precondition: below the threshold the breaker is still closed");

        llmStub.mode = StubMode.THROW_APPLICATION;
        onInterruptedThread(() -> assertThrows(LlmCallFailedException.class,
                () -> llmBreaker.generate(ModelTask.CHAT_AGENT, "", "hi")));

        // The run is intact, so ONE more transport failure reaches the
        // threshold. Credit the interrupted call and the count sits at 1
        // instead and the breaker stays closed.
        failTransport(1);
        assertTrue(registry.wouldShortCircuit(ModelTask.CHAT_AGENT),
                "an interrupted call must not break the run of consecutive transport "
                        + "failures — it observed nothing about the endpoint");
    }

    @Test
    void interruptedEmbeddingCallDoesNotResetTheConsecutiveCount() {
        // Same defect, different door: /stop cancels a chat turn whose
        // SemanticSearchTool / HelpLookupTool call embeds, and the embedding
        // providers share LlmHttpSupport.sendForBody with the generative ones.
        embedStub.mode = StubMode.THROW_TRANSPORT;
        for (int i = 0; i < THRESHOLD - 1; i++) {
            assertThrows(EmbeddingProviderUnreachableException.class,
                    () -> embedBreaker.embed(List.of("text")));
        }
        assertTrue(registry.tryAcquireForEmbeddings(),
                "precondition: below the threshold the embedding breaker is still closed");

        embedStub.mode = StubMode.THROW_APPLICATION;
        onInterruptedThread(() -> assertThrows(EmbeddingCallFailedException.class,
                () -> embedBreaker.embed(List.of("text"))));

        embedStub.mode = StubMode.THROW_TRANSPORT;
        assertThrows(EmbeddingProviderUnreachableException.class,
                () -> embedBreaker.embed(List.of("text")));
        assertFalse(registry.tryAcquireForEmbeddings(),
                "an interrupted embedding call must not break the run either");
    }

    @Test
    void releasingCallerCannotReturnAProbeItNeverHeld() {
        // The scenario the release path made reachable: a call admitted
        // while the breaker was still CLOSED outlives a trip AND a whole
        // cooldown (a chat call's 120s budget against a 30s cooldown), and
        // only then reports no evidence. By that time a DIFFERENT caller
        // holds the HALF-OPEN probe. A state-only release would hand back
        // the newer caller's slot, so a second probe would be admitted
        // while the first is still in flight — two concurrent probes
        // against an endpoint the breaker exists to touch once. (M1-769)
        assertTrue(registry.tryAcquireForTask(ModelTask.CHAT_AGENT),
                "precondition: this thread's call is admitted while CLOSED");
        failTransport(THRESHOLD);
        clock.advance(Duration.ofMillis(COOLDOWN_MS));

        // A different caller takes the single recovery probe and keeps it.
        onFreshThread(() -> assertTrue(registry.tryAcquireForTask(ModelTask.CHAT_AGENT),
                "precondition: the cooldown elapsed, so this caller becomes the probe"));

        // The long-running call finally reports no evidence and releases.
        registry.releaseProbeForTask(ModelTask.CHAT_AGENT);

        assertFalse(registry.tryAcquireForTask(ModelTask.CHAT_AGENT),
                "a caller that never held the probe must not free it — the "
                        + "outstanding probe stays the only one in flight");
    }

    // --- open-transition event emission ---

    @Test
    void openedTransitionEmitsExactlyOneEventPerTripOrReopen() {
        List<LlmCircuitBreakerOpenedEvent> events = new ArrayList<>();
        registry = new LlmCircuitBreakerRegistry(THRESHOLD, COOLDOWN_MS, clock,
                LlmRouter.ConfigReader.fromMap(Map.of(
                        LlmCircuitBreakerRegistry.CONFIG_KEY_EMBEDDINGS_BASE_URL,
                        EMBED_ENDPOINT)),
                events::add);
        for (int i = 0; i < THRESHOLD; i++) {
            registry.recordUnreachableForEmbeddings();
        }
        assertEquals(1, events.size(), "the CLOSED→OPEN trip emits exactly one event");
        assertEquals("EMBEDDINGS", events.get(0).transportKind());
        assertEquals(EMBED_ENDPOINT, events.get(0).endpoint());
        assertFalse(events.get(0).probeReopen());

        clock.advance(Duration.ofMillis(COOLDOWN_MS / 2));
        for (int i = 0; i < 5; i++) {
            assertFalse(registry.tryAcquireForEmbeddings());
        }
        assertEquals(1, events.size(), "denied acquisitions inside the cooldown emit nothing");

        clock.advance(Duration.ofMillis(COOLDOWN_MS / 2));
        assertTrue(registry.tryAcquireForEmbeddings(), "cooldown elapsed: the probe is admitted");
        registry.recordUnreachableForEmbeddings();
        assertEquals(2, events.size());
        assertEquals("EMBEDDINGS", events.get(1).transportKind());
        assertEquals(EMBED_ENDPOINT, events.get(1).endpoint());
        assertTrue(events.get(1).probeReopen(), "the failed probe re-open is flagged");
    }

    @Test
    void deniedCallsDuringOpenEmitNoEvent() {
        List<LlmCircuitBreakerOpenedEvent> events = new ArrayList<>();
        registry = new LlmCircuitBreakerRegistry(THRESHOLD, COOLDOWN_MS, clock,
                LlmRouter.ConfigReader.fromMap(Map.of(
                        LlmRouter.CONFIG_KEY_DEFAULT_BASE_URL, DEFAULT_ENDPOINT)),
                events::add);
        for (int i = 0; i < THRESHOLD; i++) {
            registry.recordUnreachableForTask(ModelTask.CHAT_AGENT);
        }
        assertEquals(1, events.size());
        clock.advance(Duration.ofMillis(COOLDOWN_MS / 2));
        for (int i = 0; i < 10; i++) {
            assertFalse(registry.tryAcquireForTask(ModelTask.CHAT_AGENT));
        }
        assertEquals(1, events.size(),
                "denied acquisitions are not endpoint observations and emit nothing");
    }

    @Test
    void recoveryAndHealthyCallsEmitNoEvent() {
        List<LlmCircuitBreakerOpenedEvent> events = new ArrayList<>();
        registry = new LlmCircuitBreakerRegistry(THRESHOLD, COOLDOWN_MS, clock,
                LlmRouter.ConfigReader.fromMap(Map.of(
                        LlmCircuitBreakerRegistry.CONFIG_KEY_EMBEDDINGS_BASE_URL,
                        EMBED_ENDPOINT)),
                events::add);
        assertTrue(registry.tryAcquireForEmbeddings());
        registry.recordReachableForEmbeddings();
        assertEquals(0, events.size(), "healthy steady-state calls emit nothing");
        for (int i = 0; i < THRESHOLD; i++) {
            registry.recordUnreachableForEmbeddings();
        }
        assertEquals(1, events.size());
        clock.advance(Duration.ofMillis(COOLDOWN_MS));
        assertTrue(registry.tryAcquireForEmbeddings());
        registry.recordReachableForEmbeddings();
        assertTrue(registry.tryAcquireForEmbeddings());
        registry.recordReachableForEmbeddings();
        assertEquals(1, events.size(),
                "recovery emits nothing — a mutation that fires on close or on "
                        + "every acquire fails here");
    }

    // --- helpers ---

    /**
     * Run {@code body} on a fresh thread with the interrupt flag already
     * armed, surfacing any assertion failure back here.
     *
     * <p>A fresh thread rather than JUnit's, for the two reasons
     * {@code HttpProviderSharedPipelineTest} documents: the armed flag dies
     * with the leg instead of leaking into later tests in this JVM, and an
     * assertion raised on another thread is invisible to JUnit unless it is
     * carried back — without the hand-off a failing leg passes silently.
     */
    private static void onInterruptedThread(Runnable body) {
        onFreshThread(() -> {
            Thread.currentThread().interrupt();
            body.run();
        });
    }

    /**
     * Run {@code body} on a fresh thread, carrying any assertion failure
     * back to the caller. Also the seam that lets a test act as a SECOND
     * caller — thread identity is what tells the breaker who holds the
     * recovery probe.
     */
    private static void onFreshThread(Runnable body) {
        AtomicReference<Throwable> legFailure = new AtomicReference<>();
        Thread leg = Thread.ofPlatform().unstarted(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                legFailure.set(t);
            }
        });
        leg.start();
        try {
            leg.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting the leg thread", e);
        }
        Throwable failed = legFailure.get();
        if (failed != null) {
            throw new AssertionError("assertion failed on the leg thread", failed);
        }
    }

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
