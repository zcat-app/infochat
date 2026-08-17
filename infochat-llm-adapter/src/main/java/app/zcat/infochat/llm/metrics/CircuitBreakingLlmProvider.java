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

import java.util.List;

/**
 * CDI decorator wrapping every {@link LlmProvider} bean with the
 * per-endpoint circuit breaker (M1-606): an OPEN breaker short-circuits
 * {@link #generate} and its streaming mirror
 * {@link #generateStreaming} with the typed
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
 * shape) proves the endpoint answered and records as reachable. An
 * exception from OUTSIDE that family records nothing — it carries no
 * evidence either way — and RELEASES the HALF-OPEN probe the call may
 * have acquired, so an unobserved endpoint is not left denied for a
 * further cooldown by a call that never looked at it.</p>
 *
 * <p>"No evidence" is decided by CAUSE, not by exception type alone,
 * because one member of the class hides inside the reachable family: an
 * interrupted caller sends no request, yet the interrupt surfaces as a
 * plain {@link LlmCallFailedException}. Recording that as reachability
 * would un-trip a breaker on an endpoint nobody contacted, which is why
 * both recording arms consult {@link #releasedAsCancelled} first.</p>
 *
 * <p>The short-circuit throw itself never records: it is not
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
        return withBreaker(task, () -> delegate.generate(task, systemPrompt, userPrompt));
    }

    @Override
    public boolean supportsStreaming(ModelTask task) {
        return delegate.supportsStreaming(task);
    }

    @Override
    public boolean supportsToolCalls(ModelTask task) {
        return delegate.supportsToolCalls(task);
    }

    /**
     * The tools-bearing mirror of {@link #generate} through the same
     * acquire/classify/record ladder.
     */
    @Override
    public LlmResponse generateWithTools(ModelTask task, String systemPrompt, String userPrompt,
                                         List<LlmProvider.ToolDeclaration> tools) {
        return withBreaker(task,
            () -> delegate.generateWithTools(task, systemPrompt, userPrompt, tools));
    }

    /**
     * The streaming mirror of {@link #generate} through the same
     * acquire/classify/record ladder: a mid-stream failure is
     * classified by the provider's streaming pipeline exactly as a
     * single-string transport or application failure, so the breaker
     * trips on the same evidence either way.
     */
    @Override
    public LlmResponse generateStreaming(ModelTask task, String systemPrompt, String userPrompt,
                                         java.util.function.Consumer<String> chunkConsumer) {
        return withBreaker(task,
            () -> delegate.generateStreaming(task, systemPrompt, userPrompt, chunkConsumer));
    }

    private LlmResponse withBreaker(ModelTask task,
                                    java.util.function.Supplier<LlmResponse> call) {
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
            response = call.get();
        } catch (LlmCallFailedException.ProviderUnreachableException e) {
            if (!releasedAsCancelled(task)) {
                breakerRegistry.recordUnreachableForTask(task);
            }
            throw e;
        } catch (LlmCallFailedException e) {
            if (!releasedAsCancelled(task)) {
                breakerRegistry.recordReachableForTask(task);
            }
            throw e;
        } catch (RuntimeException e) {
            // No evidence either way — and the probe this call may have
            // acquired above must go BACK rather than be burned. Typed at
            // RuntimeException rather than at the budget's refusal by
            // name: naming the refusal would import the budget into the
            // breaker, undoing the very separation that keeps a spend cap
            // from reading as endpoint evidence. The no-evidence calls
            // that hide INSIDE that family are handled by the interrupt
            // check in the two arms above.
            // Without this, a budget-refused render spends one recovery
            // probe per cooldown for its whole length and every LLM
            // surface keeps failing fast against a recovered provider.
            // (M1-769)
            breakerRegistry.releaseProbeForTask(task);
            throw e;
        }
        breakerRegistry.recordReachableForTask(task);
        return response;
    }

    /**
     * Whether this failure is caller-side cancellation rather than an
     * observation of the endpoint — in which case the probe it may have
     * acquired is returned and NOTHING is recorded (M1-769).
     *
     * <p>An interrupted call sends no request at all
     * ({@code LlmHttpSupport.sendForBody} re-arms the flag and rethrows,
     * pinned by {@code
     * HttpProviderSharedPipelineTest.interruptedCallerSendsNoRequestAndKeepsTheInterruptArmed}),
     * yet it surfaces as a PLAIN {@link LlmCallFailedException} — the
     * type the caller above records as REACHABLE, whose
     * {@code recordReachable()} closes unconditionally. So without this
     * check a call that sent no bytes un-trips the breaker and zeroes its
     * consecutive-failure count, and M1-763's cancelled render — whose
     * loop runs to completion issuing interrupted calls — re-zeroes it on
     * every one, so the breaker cannot re-trip until that loop ends. The
     * flag is read rather than the exception typed because the
     * classification belongs where "is this evidence about the endpoint?"
     * is already decided, and retyping would move M1-764's transport
     * contract for no gain.
     *
     * <p>Mis-classification is possible in one direction only and it is
     * the safe one: an interrupt landing between a real response and this
     * catch discards one piece of evidence, leaving the breaker as it was
     * for the next uncancelled call to settle. The success path is
     * deliberately NOT guarded — a call that returned a response observed
     * the endpoint whatever the flag says afterwards.
     */
    private boolean releasedAsCancelled(ModelTask task) {
        if (!Thread.currentThread().isInterrupted()) {
            return false;
        }
        breakerRegistry.releaseProbeForTask(task);
        return true;
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
