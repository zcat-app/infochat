package app.zcat.infochat.llm;


import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Per-call observability context carried through every
 * {@link LlmProvider} and {@link EmbeddingProvider} call
 * (docs/spec/llm.md §SPI shape "Call context"): trace id, scope id,
 * task, language. The same context wraps both SPI surfaces so traces
 * stitch across the embedding boundary. This is an in-process
 * correlation surface — ids land in logs and metric labels, not in a
 * distributed-tracing backend.
 *
 * <p>Propagation uses a {@link ScopedValue} rather than a
 * ThreadLocal: bindings are visible only inside the
 * {@link #callWith} body and cannot leak across pooled or virtual
 * threads, which is exactly the lifetime a per-call context needs.
 * Callers open an ambient context around a unit of work; the metrics
 * decorators derive the per-call context from the ambient one (fresh
 * trace id when none is bound) and re-bind it around the delegate
 * call, so {@link #current()} is always readable inside a provider
 * impl — the "observable at the provider boundary" commitment.</p>
 *
 * @param traceId  correlation id stitching the calls issued under one
 *                 ambient context. Never null; generated when absent.
 * @param scopeId  the originating user/group scope, or null when the
 *                 call has no scope (e.g. collector ingest pipeline).
 * @param task     the {@link ModelTask} of an {@code LlmProvider}
 *                 call; null for {@code EmbeddingProvider} calls —
 *                 the embedder is deliberately not a {@code ModelTask}
 *                 (docs/spec/llm.md §SPI shape).
 * @param language the scope language driving routing/translation, or
 *                 null when not applicable.
 */
public record LlmCallContext(String traceId, @Nullable String scopeId,
                             @Nullable ModelTask task, @Nullable String language) {

    private static final ScopedValue<LlmCallContext> CURRENT = ScopedValue.newInstance();

    /** A context with a freshly generated trace id and no scope/task/language. */
    public static LlmCallContext fresh() {
        return new LlmCallContext(UUID.randomUUID().toString(), null, null, null);
    }

    /**
     * The context bound around the current call.
     *
     * @throws java.util.NoSuchElementException when no context is
     *         bound — inside a provider impl invoked through the
     *         metrics decorators one always is.
     */
    public static LlmCallContext current() {
        return CURRENT.get();
    }

    /** The ambient context, or a {@link #fresh} one when none is bound. */
    public static LlmCallContext currentOrFresh() {
        return CURRENT.isBound() ? CURRENT.get() : fresh();
    }

    /** Copy of this context with {@code task} replaced. */
    public LlmCallContext withTask(@Nullable ModelTask task) {
        return new LlmCallContext(traceId, scopeId, task, language);
    }

    /**
     * Runs {@code body} with {@code context} bound as the current
     * call context. Callers use it to open an ambient context spanning
     * several SPI calls (one trace id across all of them); the metrics
     * decorators use it to bind the derived per-call context around
     * the delegate invocation.
     */
    public static <T> T callWith(LlmCallContext context, Supplier<T> body) {
        return ScopedValue.where(CURRENT, context).call(body::get);
    }
}
