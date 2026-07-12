package app.zcat.infochat.llm.metrics;


import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.impl.LlmCallFailedException;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerRegistry;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

/**
 * CDI decorator wrapping every {@link LlmProvider} bean with the
 * per-endpoint circuit breaker (M1-606): an OPEN breaker short-circuits
 * {@link #generate} with the typed
 * {@link LlmCallFailedException.ProviderUnreachableException} WITHOUT
 * attempting the HTTP call, so every {@link ModelTask} consumer fails
 * fast on an endpoint already known unreachable and degrades exactly as
 * it does today (fail-fast, never fail-over — the router still resolves
 * one provider per call). The decorator altitude protects all eight
 * task consumers at their single funnel, with no router or call-site
 * change — same rationale as {@link MeteredLlmProvider}.
 *
 * <p>Priority is {@code APPLICATION + 100}: higher value = invoked
 * later = INSIDE {@link MeteredLlmProvider}, so a short-circuited call
 * still records a {@code fail} outcome on the {@code llm.*} metrics —
 * an outage stays observable while the breaker suppresses the doomed
 * HTTP attempts themselves.</p>
 *
 * <p>Outcome attribution: only the typed unreachable subtype advances
 * the breaker's consecutive-failure count; a plain
 * {@link LlmCallFailedException} (non-2xx, body cap, parse, wrong
 * shape) proves the endpoint answered and records as reachable. Any
 * other exception passes through untouched — it carries no evidence
 * either way. The short-circuit throw itself never records: it is not
 * an observation of the endpoint, and counting it would let the
 * caller-side retry harness double-step the counter per doomed call.</p>
 */
@Decorator
@Priority(Interceptor.Priority.APPLICATION + 100)
public class CircuitBreakingLlmProvider implements LlmProvider {

    private final LlmProvider delegate;
    private final LlmCircuitBreakerRegistry breakerRegistry;

    @Inject
    public CircuitBreakingLlmProvider(@Delegate @Any LlmProvider delegate,
                                      LlmCircuitBreakerRegistry breakerRegistry) {
        this.delegate = delegate;
        this.breakerRegistry = breakerRegistry;
    }

    @Override
    public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
        if (!breakerRegistry.tryAcquireForTask(task)) {
            // Endpoint identity stays out of the message: task + provider
            // name the route; the breaker's own state-transition log lines
            // carry the endpoint.
            throw new LlmCallFailedException.ProviderUnreachableException(
                delegate.providerName() + ": circuit breaker OPEN for task "
                    + task.keySegment() + "; call short-circuited without an HTTP attempt");
        }
        LlmResponse response;
        try {
            response = delegate.generate(task, systemPrompt, userPrompt);
        } catch (LlmCallFailedException.ProviderUnreachableException e) {
            breakerRegistry.recordUnreachableForTask(task);
            throw e;
        } catch (LlmCallFailedException e) {
            breakerRegistry.recordReachableForTask(task);
            throw e;
        }
        breakerRegistry.recordReachableForTask(task);
        return response;
    }

    @Override
    public void assertTaskConfigResolvable(ModelTask task) {
        delegate.assertTaskConfigResolvable(task);
    }

    /**
     * Must forward: the interface default walks {@code getClass()},
     * which on the decorator would yield the decorator's own name and
     * break the router's name-based provider resolution (same trap
     * {@link MeteredLlmProvider#providerName()} documents).
     */
    @Override
    public String providerName() {
        return delegate.providerName();
    }
}
