package app.zcat.infochat.llm;


import java.util.function.Supplier;

/**
 * Call-scoped spend sink consulted by {@link
 * app.zcat.infochat.llm.metrics.BudgetedLlmProvider} immediately before
 * every {@link LlmProvider#generate} call that reaches a provider impl
 * (M1-769). A budget owner binds one around a unit of work; the
 * decorator draws once per call and refuses when the owner says the
 * unit is out of budget.
 *
 * <p><b>Unbound is the default, and it is the whole scoping story.</b>
 * The decorator wraps every {@code LlmProvider} bean in the deployment —
 * collector ingest, chat, saves, {@code /summary} — but draws ONLY
 * inside a {@link #callWith} body. A counter that fired deployment-wide
 * would let a collector backlog starve the provider, an availability
 * trade M1-767 refused; keeping the sink unbound everywhere except the
 * one binder site is what buys correct SCOPE and correct COUNT at the
 * same time, which is the thing M1-767 could not have. The single v1
 * binder is {@code DigestRenderer.renderSections}.</p>
 *
 * <p>Propagation is a {@link ScopedValue} for the reason {@link
 * LlmCallContext} documents — a binding is visible only inside the
 * body and cannot leak across pooled or virtual threads. The same
 * property is a TRAP here: a {@code ScopedValue} binding is NOT
 * inherited across a plain executor submit, so a binder placed around
 * a {@code submit(...)} rather than inside the submitted work is
 * simply absent on the worker thread and every draw silently vanishes
 * — a deployment that looks metered and is not.</p>
 */
@FunctionalInterface
public interface LlmCallBudget {

    /**
     * Draw one LLM call against this budget.
     *
     * @return {@code true} when the call is admitted and has been
     *         charged; {@code false} when the budget is exhausted and
     *         the call must not be issued. An implementation charges
     *         only on {@code true} — a refusal never consumes budget,
     *         so the window drains and spending recovers.
     */
    boolean tryDraw();

    /**
     * Runs {@code body} with {@code budget} bound as the current call
     * budget, so every provider call issued on THIS thread inside the
     * body draws against it.
     */
    static <T> T callWith(LlmCallBudget budget, Supplier<T> body) {
        return ScopedValue.where(LlmCallBudgetScope.CURRENT, budget).call(body::get);
    }

    /** Whether a budget is bound around the current call. */
    static boolean isBound() {
        return LlmCallBudgetScope.CURRENT.isBound();
    }

    /**
     * The budget bound around the current call.
     *
     * @throws java.util.NoSuchElementException when none is bound —
     *         call {@link #isBound()} first.
     */
    static LlmCallBudget current() {
        return LlmCallBudgetScope.CURRENT.get();
    }

    /**
     * Thrown by the decorator when {@link #tryDraw()} refuses a call.
     *
     * <p>Deliberately OUTSIDE the {@code LlmCallFailedException}
     * hierarchy, and this is a security property rather than a taste
     * call: {@code CircuitBreakingLlmProvider} catches that type and
     * its {@code ProviderUnreachableException} subtype and records each
     * as evidence ABOUT THE ENDPOINT. A refusal is evidence about our
     * own spend, not about the provider — typing it into that family
     * would let a busy digest window trip the breaker against a
     * healthy endpoint under exactly the load this control exists to
     * shed, converting a cost cap into an outage.</p>
     *
     * <p>Being a plain {@link RuntimeException} is also what makes the
     * mid-render bound need no new degradation machinery: every
     * generative caller on the digest path ({@code
     * SummaryProseGenerator}, {@code CategoryRollupGenerator}, {@code
     * TranslationPipeline}) already catches {@code RuntimeException}
     * and degrades that unit, so a refused call yields the same output
     * a failed one does and the digest still goes out.</p>
     */
    final class RefusedException extends RuntimeException {
        public RefusedException(String message) {
            super(message);
        }
    }
}

/**
 * Holder for {@link LlmCallBudget}'s binding. A package-private
 * companion rather than a field on the interface because every member
 * an interface declares is implicitly public: the {@code ScopedValue}
 * itself must not be part of the SPI, or a caller could bind it
 * without going through {@link LlmCallBudget#callWith}.
 */
final class LlmCallBudgetScope {

    static final ScopedValue<LlmCallBudget> CURRENT = ScopedValue.newInstance();

    private LlmCallBudgetScope() {
    }
}
