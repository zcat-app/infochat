package app.zcat.infochat.llm.metrics;


import app.zcat.infochat.llm.LlmCallContext;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CDI decorator wrapping every {@link LlmProvider} bean with the
 * per-call observability surface (M1-321): constructs the per-call
 * {@link LlmCallContext} (derived from the ambient one, fresh trace id
 * when none is bound), binds it around the delegate call so it is
 * observable inside the provider impl, and emits the §5.9 LLM metrics
 * through {@link LlmMetrics}. A decorator keeps metric emission out of
 * each provider impl and applies uniformly to all of them — including
 * test alternatives — with no router or call-site change.
 *
 * <p>Outcome classification here is {@code ok} (delegate returned) vs
 * {@code fail} (delegate threw, exception rethrown unchanged). The
 * {@code model} label comes from the provider-reported
 * {@link LlmResponse#model()}; a failed call has no response, so it is
 * labeled {@code unknown}. The trace-id log line carries ids, labels,
 * and durations only — never prompt or response content (log-hygiene
 * rule, docs/spec/security.md).</p>
 */
@Decorator
@Priority(Interceptor.Priority.APPLICATION)
public class MeteredLlmProvider implements LlmProvider {

    private static final Logger LOG = Logger.getLogger(MeteredLlmProvider.class);

    private static final String UNKNOWN_MODEL = "unknown";

    private final LlmProvider delegate;
    private final LlmMetrics metrics;

    @Inject
    public MeteredLlmProvider(@Delegate @Any LlmProvider delegate, LlmMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    @Override
    public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
        LlmCallContext context = LlmCallContext.currentOrFresh().withTask(task);
        String provider = delegate.providerName();
        AtomicInteger inflight = metrics.llmInflight(task, provider);
        long startNanos = System.nanoTime();
        inflight.incrementAndGet();
        try {
            LlmResponse response =
                LlmCallContext.callWith(context, () -> delegate.generate(task, systemPrompt, userPrompt));
            Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
            String reportedModel = response.model();
            String model = reportedModel != null ? reportedModel : UNKNOWN_MODEL;
            metrics.recordLlmCall(task, provider, model, LlmMetrics.Outcome.OK, latency, response.usage());
            LOG.debugf("llm call ok: trace=%s task=%s provider=%s model=%s latencyMs=%d",
                context.traceId(), task.keySegment(), provider, model, latency.toMillis());
            return response;
        } catch (RuntimeException e) {
            Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
            metrics.recordLlmCall(task, provider, UNKNOWN_MODEL, LlmMetrics.Outcome.FAIL, latency, null);
            LOG.debugf("llm call fail: trace=%s task=%s provider=%s latencyMs=%d",
                context.traceId(), task.keySegment(), provider, latency.toMillis());
            throw e;
        } finally {
            inflight.decrementAndGet();
        }
    }

    @Override
    public void assertTaskConfigResolvable(ModelTask task) {
        delegate.assertTaskConfigResolvable(task);
    }

    /**
     * Must forward: the interface default walks {@code getClass()},
     * which on the decorator would yield the decorator's own name and
     * break the router's name-based provider resolution.
     */
    @Override
    public String providerName() {
        return delegate.providerName();
    }
}
