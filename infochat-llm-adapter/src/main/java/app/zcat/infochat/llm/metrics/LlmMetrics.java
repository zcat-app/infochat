package app.zcat.infochat.llm.metrics;


import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer emission point for the LLM/embedding observability
 * catalogue (docs/design/05-llm-and-embeddings.md §5.9):
 *
 * <ul>
 *   <li>{@code llm.calls.total}{task, provider, model, outcome} — counter</li>
 *   <li>{@code llm.tokens.in}{task, provider, model} — counter</li>
 *   <li>{@code llm.tokens.out}{task, provider, model} — counter</li>
 *   <li>{@code llm.latency.ms}{task, provider, model} — histogram</li>
 *   <li>{@code llm.concurrency.inflight}{task, provider} — gauge</li>
 *   <li>{@code llm.queue.wait.ms}{task, provider} — histogram</li>
 *   <li>{@code embedding.calls.total}{provider, model, outcome} — counter</li>
 *   <li>{@code embedding.dimension}{provider, model} — gauge</li>
 * </ul>
 *
 * The per-call metrics are driven by the {@link MeteredLlmProvider} /
 * {@link MeteredEmbeddingProvider} decorators; {@code llm.queue.wait.ms}
 * is driven by the worker that owns the LLM concurrency semaphore
 * (the queue exists upstream of the provider boundary, where the
 * decorators cannot see it).
 */
@ApplicationScoped
public class LlmMetrics {

    /**
     * The catalogue's outcome label set. The decorators classify
     * {@link #OK} (delegate returned) and {@link #FAIL} (delegate
     * threw); {@link #RETRY} and {@link #FALLBACK} are call-site
     * classifications — only the retry/fallback harness around a call
     * knows an attempt was a retry or that it fell back.
     */
    public enum Outcome {
        OK("ok"), RETRY("retry"), FALLBACK("fallback"), FAIL("fail");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final MeterRegistry registry;

    /**
     * Gauges hold a live object reference rather than recording
     * events, so the AtomicInteger behind each (tag-set) gauge must be
     * registered once and reused — re-registering per call would leak
     * meters and reset state.
     */
    private final ConcurrentMap<String, AtomicInteger> inflightGauges = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> dimensionGauges = new ConcurrentHashMap<>();

    @Inject
    public LlmMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One completed {@code LlmProvider} call: counter, latency, and (when reported) token counters. */
    public void recordLlmCall(ModelTask task, String provider, String model, Outcome outcome,
                              Duration latency, LlmResponse.@Nullable TokenUsage usage) {
        registry.counter("llm.calls.total",
                "task", task.keySegment(), "provider", provider, "model", model,
                "outcome", outcome.label())
            .increment();
        registry.timer("llm.latency.ms",
                "task", task.keySegment(), "provider", provider, "model", model)
            .record(latency);
        if (usage != null) {
            registry.counter("llm.tokens.in",
                    "task", task.keySegment(), "provider", provider, "model", model)
                .increment(usage.inputTokens());
            registry.counter("llm.tokens.out",
                    "task", task.keySegment(), "provider", provider, "model", model)
                .increment(usage.outputTokens());
        }
    }

    /** The live in-flight count behind {@code llm.concurrency.inflight} for one (task, provider). */
    public AtomicInteger llmInflight(ModelTask task, String provider) {
        return inflightGauges.computeIfAbsent(task.keySegment() + "|" + provider, key -> {
            AtomicInteger value = new AtomicInteger();
            registry.gauge("llm.concurrency.inflight",
                Tags.of("task", task.keySegment(), "provider", provider), value);
            return value;
        });
    }

    /** Time one caller spent waiting for an LLM concurrency permit. */
    public void recordQueueWait(ModelTask task, String provider, Duration wait) {
        registry.timer("llm.queue.wait.ms",
                "task", task.keySegment(), "provider", provider)
            .record(wait);
    }

    /** One completed {@code EmbeddingProvider} call. */
    public void recordEmbeddingCall(String provider, String model, Outcome outcome) {
        registry.counter("embedding.calls.total",
                "provider", provider, "model", model, "outcome", outcome.label())
            .increment();
    }

    /** The active embedding dimensionality as observed from a returned vector. */
    public void recordEmbeddingDimension(String provider, String model, int dimension) {
        dimensionGauges.computeIfAbsent(provider + "|" + model, key -> {
            AtomicInteger value = new AtomicInteger();
            registry.gauge("embedding.dimension",
                Tags.of("provider", provider, "model", model), value);
            return value;
        }).set(dimension);
    }
}
