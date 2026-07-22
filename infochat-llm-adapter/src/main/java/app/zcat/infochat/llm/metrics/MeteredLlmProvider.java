package app.zcat.infochat.llm.metrics;


import app.zcat.infochat.llm.LlmCallContext;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.Config;
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
 * {@code model} label reads the operator-configured per-task model id
 * ({@code infochat.llm.<task>.model}) and deliberately NOT the
 * provider-reported {@link LlmResponse#model()} it once carried
 * (M1-673): a Micrometer registry retains one meter per distinct tag
 * value — the tag string included — for the JVM lifetime, so a
 * wire-derived label hands a hostile or compromised endpoint a
 * persistent memory-amplification channel that the providers' bounded
 * body read does not close (that cap bounds only the transient read).
 * Operator config is the cardinality-bounded source. A task with no
 * configured model (the stub-provider test topologies) and a failed
 * call are both labeled {@code unknown}. The trace-id log line carries
 * ids, labels, and durations only — never prompt or response content
 * (log-hygiene rule, docs/spec/security.md).</p>
 */
@Decorator
@Priority(Interceptor.Priority.APPLICATION)
public class MeteredLlmProvider implements LlmProvider {

    private static final Logger LOG = Logger.getLogger(MeteredLlmProvider.class);

    private static final String UNKNOWN_MODEL = "unknown";

    private final LlmProvider delegate;
    private final LlmMetrics metrics;
    private final LlmRouter.ConfigReader config;

    /**
     * Seam constructor: hand-supplied config reader, for plain-JUnit
     * tests (map-backed config, no Quarkus boot) — the same two-ctor
     * shape as {@link LlmRouter} and {@code LlmCircuitBreakerRegistry}.
     */
    public MeteredLlmProvider(LlmProvider delegate, LlmMetrics metrics,
                              LlmRouter.ConfigReader config) {
        this.delegate = delegate;
        this.metrics = metrics;
        this.config = config;
    }

    /**
     * CDI constructor. This is the only {@link Inject} one, so ArC picks
     * it — and the delegate injection point — for the decorator.
     */
    @Inject
    public MeteredLlmProvider(@Delegate @Any LlmProvider delegate, LlmMetrics metrics,
                              Config mpConfig) {
        this(delegate, metrics, key -> mpConfig.getOptionalValue(key, String.class));
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
            String model = configuredModel(task);
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

    /**
     * The operator-configured model id for {@code task}, read per call
     * from {@code infochat.llm.<task>.model} — the same key, spelled the
     * same way, the concrete providers' {@code configFor} reads. Per-call
     * rather than cached for the reason those readers state: a map lookup
     * in microseconds against an LLM call in seconds, and caching would
     * freeze a value the rest of the config surface treats as
     * runtime-resolvable. Absent or empty (the stub-provider test
     * topologies configure no model) degrades to {@code unknown} so the
     * decorator never fails a call the undecorated bean would survive.
     */
    private String configuredModel(ModelTask task) {
        return config.get(task.configPrefix() + "model")
            .filter(model -> !model.isEmpty())
            .orElse(UNKNOWN_MODEL);
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
