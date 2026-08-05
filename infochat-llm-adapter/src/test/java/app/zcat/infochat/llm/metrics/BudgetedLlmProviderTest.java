package app.zcat.infochat.llm.metrics;


import app.zcat.infochat.llm.LlmCallBudget;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.impl.LlmCallFailedException;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerRegistry;
import app.zcat.infochat.llm.routing.LlmRouter;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link BudgetedLlmProvider}'s behaviours (M1-769) at the
 * decorator seam, composing the chain by hand the way
 * {@link LlmObservabilityTest} composes {@link MeteredLlmProvider} —
 * plain JUnit, no CDI boot.
 *
 * <p>The composed-by-hand nesting mirrors the {@code @Priority} order
 * the container applies ({@code APPLICATION + 100} outside {@code
 * APPLICATION + 200}), which is what makes the breaker-OPEN leg below a
 * test of the real arrangement rather than of a hypothetical one.</p>
 */
class BudgetedLlmProviderTest {

    /** Endpoint any task resolves to, so the breaker has state to keep. */
    private static final String ENDPOINT = "http://llm.invalid";

    private CountingLlmProvider stub;
    private CountingBudget budget;
    private LlmCircuitBreakerRegistry breakers;
    private LlmProvider chain;

    @BeforeEach
    void setUp() {
        stub = new CountingLlmProvider();
        budget = new CountingBudget();
        // failureThreshold = 1: one unreachable failure opens the breaker.
        // That is what makes the acceptance-item-8 assertion below
        // non-vacuous — if a budget refusal were recorded as endpoint
        // evidence at all, the breaker would open on the very first one.
        breakers = new LlmCircuitBreakerRegistry(1, 60_000,
            Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC),
            LlmRouter.ConfigReader.fromMap(
                Map.of(LlmRouter.CONFIG_KEY_DEFAULT_BASE_URL, ENDPOINT)));
        chain = new CircuitBreakingLlmProvider(new BudgetedLlmProvider(stub), breakers);
    }

    @Test
    void unboundBudgetDelegatesAndDrawsNothing() {
        chain.generate(ModelTask.SUMMARIZER, "", "prompt");

        assertEquals(1, stub.calls, "the call must still be issued when no budget is bound");
        assertEquals(0, budget.draws,
            "a call outside any bound budget — chat, saves, /summary, collector ingest — "
                + "must never draw; unbound is the scoping guarantee");
    }

    @Test
    void breakerOpenShortCircuitDrawsNothing() {
        // Trip the breaker with the budget unbound, so the draw counter
        // reflects only what happens after it is OPEN.
        stub.failure = new LlmCallFailedException.ProviderUnreachableException("down");
        assertThrows(LlmCallFailedException.ProviderUnreachableException.class,
            () -> chain.generate(ModelTask.SUMMARIZER, "", "prompt"));
        assertTrue(breakers.wouldShortCircuit(ModelTask.SUMMARIZER), "breaker must be OPEN");
        stub.calls = 0;

        LlmCallBudget.callWith(budget, () -> assertThrows(
            LlmCallFailedException.ProviderUnreachableException.class,
            () -> chain.generate(ModelTask.SUMMARIZER, "", "prompt")));

        assertEquals(0, stub.calls, "an OPEN breaker short-circuits without an HTTP attempt");
        assertEquals(0, budget.draws,
            "a short-circuited call spends nothing, so it must charge nothing — M1-767's "
                + "phantom charge turned a transient outage into a 24h degradation");
    }

    @Test
    void exhaustedBudgetRefusesTheCallWithoutTouchingTheBreaker() {
        budget.exhausted = true;

        LlmCallBudget.callWith(budget, () -> assertThrows(LlmCallBudget.RefusedException.class,
            () -> chain.generate(ModelTask.SUMMARIZER, "", "prompt")));

        assertEquals(0, stub.calls, "a refused call must not reach the provider");
        assertFalse(breakers.wouldShortCircuit(ModelTask.SUMMARIZER),
            "a budget refusal is evidence about our own spend, never about the endpoint: "
                + "recording it would trip the breaker against a healthy provider under "
                + "exactly the load the budget exists to shed");
    }

    @Test
    void refusalReturnsTheBreakersRecoveryProbeInsteadOfBurningIt() {
        // The CLOSED-breaker leg above cannot see this: tryAcquire does not
        // mutate a CLOSED breaker, so "leaves the breaker unchanged" is
        // satisfied vacuously. On an OPEN breaker whose cooldown has
        // elapsed, acquiring IS the mutation — it spends the single
        // HALF-OPEN probe and pushes the deadline a full cooldown out —
        // and the refusal thrown one layer in reports neither outcome. Burn
        // that probe and every LLM surface (chat's wouldShortCircuit
        // pre-check, /summary, query-anchor translation) keeps failing fast
        // against an endpoint that has already recovered, for as long as
        // the refused render keeps calling.
        //
        // A steppable clock is load-bearing: with the fixed clock of
        // setUp() the cooldown never elapses and no probe is ever handed
        // out, and with a zero cooldown the probe is re-admissible whether
        // or not it was released — either way the leg would pass against
        // the burning implementation.
        SteppableClock clock = new SteppableClock(Instant.parse("2026-08-05T00:00:00Z"));
        LlmCircuitBreakerRegistry recovering = new LlmCircuitBreakerRegistry(1, 60_000, clock,
            LlmRouter.ConfigReader.fromMap(
                Map.of(LlmRouter.CONFIG_KEY_DEFAULT_BASE_URL, ENDPOINT)));
        LlmProvider recoveringChain =
            new CircuitBreakingLlmProvider(new BudgetedLlmProvider(stub), recovering);

        stub.failure = new LlmCallFailedException.ProviderUnreachableException("down");
        assertThrows(LlmCallFailedException.ProviderUnreachableException.class,
            () -> recoveringChain.generate(ModelTask.SUMMARIZER, "", "prompt"));
        assertTrue(recovering.wouldShortCircuit(ModelTask.SUMMARIZER), "breaker must be OPEN");

        // The endpoint recovers and the cooldown expires, so exactly one
        // probe is now on offer. A budget-refused digest call takes it.
        clock.advance(Duration.ofMillis(60_000));
        stub.failure = null;
        stub.calls = 0;
        budget.exhausted = true;
        LlmCallBudget.callWith(budget, () -> assertThrows(LlmCallBudget.RefusedException.class,
            () -> recoveringChain.generate(ModelTask.SUMMARIZER, "", "prompt")));
        assertEquals(0, stub.calls, "a refused call must not reach the provider");

        // The probe must still be on offer: an unbound caller — chat — is
        // admitted, reaches the endpoint, and closes the breaker.
        assertFalse(recovering.wouldShortCircuit(ModelTask.SUMMARIZER),
            "a call that reported nothing about the endpoint must hand the probe back; "
                + "burning it extends the outage of every LLM surface against a "
                + "provider that has already recovered");
        recoveringChain.generate(ModelTask.SUMMARIZER, "", "prompt");
        assertEquals(1, stub.calls, "the returned probe must reach the provider");
        assertFalse(recovering.wouldShortCircuit(ModelTask.SUMMARIZER),
            "the successful probe closes the breaker");
    }

    @Test
    void interruptedCallerDelegatesAndDrawsNothing() {
        // M1-763 cancels an overrunning render by interrupting its thread
        // and M1-764 pins that an interrupted caller sends no request, so
        // the remaining calls reach this decorator but never the wire.
        Thread.currentThread().interrupt();
        try {
            LlmCallBudget.callWith(budget,
                () -> chain.generate(ModelTask.SUMMARIZER, "", "prompt"));

            assertEquals(1, stub.calls,
                "the decorator must not swallow the call — the M1-764 no-request contract "
                    + "belongs to the transport, not to the meter");
            assertEquals(0, budget.draws,
                "a cancelled render's remaining calls spend nothing; charging them was "
                    + "M1-767's largest named over-count leg");
            assertTrue(Thread.currentThread().isInterrupted(),
                "the interrupt must stay armed — clearing it would re-arm the very calls "
                    + "M1-764 stops");
        } finally {
            // Clear it for the next test regardless of how this one ended.
            Thread.interrupted();
        }
    }

    @Test
    void answeredEndpointFailureStillDraws() {
        // A non-2xx / body-cap / parse failure proves a request was
        // ISSUED. The budget meters spend, not success.
        stub.failure = new LlmCallFailedException("HTTP 500");

        LlmCallBudget.callWith(budget, () -> assertThrows(LlmCallFailedException.class,
            () -> chain.generate(ModelTask.SUMMARIZER, "", "prompt")));

        assertEquals(1, budget.draws, "a call that reached the endpoint is spend and must charge");
    }

    /**
     * Advanceable clock: the breaker's cooldown is a decision on "now", so
     * the probe-recovery leg needs time to move. Private-nested rather
     * than shared with {@code LlmCircuitBreakerRegistryTest}'s twin — that
     * one is private too, and a test-support extraction is not this
     * ticket's scope.
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

    private static final class CountingLlmProvider implements LlmProvider {
        int calls;
        @Nullable RuntimeException failure;

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return new LlmResponse("reply", "stub-model", null);
        }

        @Override
        public String providerName() {
            return "stub";
        }
    }

    private static final class CountingBudget implements LlmCallBudget {
        int draws;
        boolean exhausted;

        @Override
        public boolean tryDraw() {
            if (exhausted) {
                return false;
            }
            draws++;
            return true;
        }
    }
}
