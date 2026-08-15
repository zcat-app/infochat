package app.zcat.infochat.llm.metrics;


import app.zcat.infochat.llm.LlmCallBudget;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

/**
 * CDI decorator wrapping every {@link LlmProvider} bean with the
 * call-scoped spend meter (M1-769): when an {@link LlmCallBudget} is
 * bound around the current call, one draw is taken per {@link #generate}
 * invocation, and a refused draw throws
 * {@link LlmCallBudget.RefusedException} INSTEAD of issuing the call.
 * With nothing bound the decorator is a pass-through, which is what
 * keeps chat, saves, {@code /summary} and the collector's ingest tasks
 * off the digest's budget while still routing through one meter.
 *
 * <p>Priority is {@code APPLICATION + 200}: higher value = invoked
 * later = INSIDE {@link CircuitBreakingLlmProvider} ({@code + 100}),
 * which is itself inside {@link MeteredLlmProvider} ({@code
 * APPLICATION}). That nesting is the accounting, not a detail — it is
 * why a breaker-OPEN short-circuit, which throws "without an HTTP
 * attempt" one layer out, never reaches this draw. M1-767 metered the
 * render loop's cardinality instead and so charged a full window for an
 * outage that spent nothing, converting a transient provider failure
 * into a deployment-wide 24h degradation. Counting HERE closes that leg
 * by construction rather than by a hand-maintained list of call sites,
 * and picks up the calls a call-site list misses — notably the
 * translator leg a roll-up makes after its summarizer call.</p>
 *
 * <p>The draw precedes the delegate call because the budget's job is to
 * REFUSE spend, which is only possible before it happens. Two legs
 * follow from that ordering and are deliberate:</p>
 *
 * <ul>
 *   <li>An already-interrupted caller draws NOTHING and still delegates.
 *       M1-763 cancels an overrunning render by interrupting its thread,
 *       and M1-764 pins that an interrupted caller sends no request — so
 *       the remaining calls of a cancelled render reach this decorator
 *       but never reach the wire. Charging them was M1-767's largest
 *       named over-count leg; skipping the draw on an interrupt closes
 *       it. {@code isInterrupted()} rather than {@code interrupted()}:
 *       clearing the flag here would re-arm the very calls M1-764 stops.</li>
 *   <li>A call the provider rejects at its own per-task config read
 *       ({@link LlmProvider.TaskConfigUnresolvableException}) is charged
 *       though it opens no socket. {@code
 *       LlmRouter.assertAllTasksResolve()} fails boot on exactly that
 *       condition, so this is unreachable in a booted deployment — a
 *       stated residual, not an open leg.</li>
 * </ul>
 */
@Decorator
@Priority(Interceptor.Priority.APPLICATION + 200)
public class BudgetedLlmProvider implements LlmProvider {

    private final LlmProvider delegate;

    @Inject
    public BudgetedLlmProvider(@Delegate @Any LlmProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
        if (LlmCallBudget.isBound() && !Thread.currentThread().isInterrupted()
                && !LlmCallBudget.current().tryDraw()) {
            // Endpoint identity and prompt content stay out of the
            // message — this is a spend signal, and it is logged by the
            // degrading caller.
            throw new LlmCallBudget.RefusedException(
                "call budget exhausted for task " + task.keySegment()
                    + "; call refused without an HTTP attempt");
        }
        return delegate.generate(task, systemPrompt, userPrompt);
    }

    @Override
    public boolean supportsStreaming(ModelTask task) {
        return delegate.supportsStreaming(task);
    }

    /**
     * The streaming mirror of {@link #generate}: one streaming call is
     * one budget draw — the accounting shape does not differ from the
     * single-string call, so a stream costs exactly one unit of
     * budget, refused before the HTTP attempt as on that path.
     */
    @Override
    public LlmResponse generateStreaming(ModelTask task, String systemPrompt, String userPrompt,
                                         java.util.function.Consumer<String> chunkConsumer) {
        if (LlmCallBudget.isBound() && !Thread.currentThread().isInterrupted()
                && !LlmCallBudget.current().tryDraw()) {
            throw new LlmCallBudget.RefusedException(
                "call budget exhausted for task " + task.keySegment()
                    + "; call refused without an HTTP attempt");
        }
        return delegate.generateStreaming(task, systemPrompt, userPrompt, chunkConsumer);
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
