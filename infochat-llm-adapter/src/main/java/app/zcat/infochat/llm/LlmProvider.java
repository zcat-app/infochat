package app.zcat.infochat.llm;


import java.util.function.Consumer;

/**
 * Chat-completion + structured-output classification SPI. One
 * {@code generate} call against one {@link ModelTask} + prompt pair
 * yields an {@link LlmResponse}.
 *
 * <p>The SPI is deliberately minimal in v1: a {@link ModelTask}
 * discriminator plus the system / user prompt strings. The
 * {@code (ModelTask, scope_language) → LlmProvider} router, per-profile
 * model defaults, structured-output schema wiring, call-context
 * threading (trace-id / scope-id), and prompt-template / delimiter-wrap
 * logic are downstream concerns that live in the concrete impls and the
 * router, not on this SPI surface; they are intentionally NOT
 * method-shape commitments here.</p>
 *
 * <p>For LLM-backed translation of bot prose, see
 * {@code TranslationProvider} in {@code infochat-messaging-adapter}
 * — it is a presentation-layer SPI consumed only by Provider and is
 * grouped with the messaging SPIs, not here.</p>
 *
 * <h2>Streaming call shape</h2>
 *
 * <p>{@link #supportsStreaming} and {@link #generateStreaming} are the
 * streaming mirror of {@code generate}, declared HERE rather than on a
 * sub-interface because the CDI decorator chain (breaker, metered,
 * budget) forwards only members of the type it decorates: a streaming
 * shape off this interface would let a streaming call bypass the
 * wrappers, silently dropping the breaker/metrics/budget controls the
 * single-string call carries. The defaults are the explicit
 * cannot-stream posture — {@code supportsStreaming} answers
 * {@code false} and {@code generateStreaming} refuses — so a provider
 * that does not override them REPORTS that it cannot stream; a caller
 * gates on the signal (surfaced through
 * {@code LlmRouter.streamingSupportedFor}) and never reaches the
 * refusal unaware.</p>
 *
 * <p><b>Chunk and terminal semantics.</b> One
 * {@link #generateStreaming} call pushes each model-produced text
 * delta to {@code chunkConsumer} in wire order, on the calling
 * thread, and returns the assembled {@link LlmResponse} when the
 * wire's terminal frame arrives. The returned {@code text()} is the
 * concatenation of every delivered chunk; {@code usage()} carries the
 * terminal usage frame when the wire sent one. Chunks are DELTAS, not
 * cumulative prefixes.</p>
 *
 * <p><b>Failure semantics.</b> Any failure — transport before or
 * <b>mid-stream</b>, non-2xx status, a frame that does not parse, a
 * response body over the operator cap, a stream that ends without
 * its terminal frame — throws the {@code LlmCallFailedException}
 * family the concrete providers throw, with the same
 * transport-vs-application split the circuit breaker classifies on:
 * a mid-stream transport failure is the unreachable subtype, an
 * application-level failure the plain type. Chunks already delivered
 * to the consumer before the failure stay delivered — a failed call
 * never emits a synthetic chunk, a synthetic terminal, or a partial
 * result. A consumer that throws propagates its exception unchanged
 * and aborts the call.</p>
 */
public interface LlmProvider {

    /**
     * Run one LLM call for the given task.
     *
     * @param task         the call-shape discriminator; routers use it to
     *                     pick a provider/model and (in impl code) the
     *                     prompt template / structured-output schema.
     * @param systemPrompt the system-role prompt; never null. Pass empty
     *                     string when the task has no system framing.
     * @param userPrompt   the user-role prompt; never null.
     * @return the response carrying the model-produced text. Never null.
     */
    LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt);

    /**
     * Whether this provider can serve {@code task} as a streaming
     * call. Explicit, per-task, and side-effect-free: the router
     * evaluates it at startup for the task whose consumer gates on
     * it, so an implementation that throws here fails boot rather
     * than the first live call. The default is the honest
     * cannot-stream report, not a silent assumption.
     */
    default boolean supportsStreaming(ModelTask task) {
        return false;
    }

    /**
     * Run one streaming LLM call for the given task, pushing each
     * text delta to {@code chunkConsumer} in wire order. Only legal
     * after {@link #supportsStreaming} answered {@code true} for
     * {@code task}; the default implementation refuses for providers
     * that do not stream.
     *
     * @param task          the call-shape discriminator, as on
     *                      {@link #generate}.
     * @param systemPrompt  the system-role prompt; never null. Pass
     *                      empty string when the task has no system
     *                      framing.
     * @param userPrompt    the user-role prompt; never null.
     * @param chunkConsumer receives every text delta in wire order;
     *                      never null. Called on the calling thread;
     *                      must not block unduly (a slow consumer
     *                      stalls the stream read up to the per-task
     *                      timeout).
     * @return the response carrying the assembled final text and the
     *         terminal usage frame when the wire sent one. Never
     *         null.
     */
    default LlmResponse generateStreaming(ModelTask task, String systemPrompt, String userPrompt,
                                          Consumer<String> chunkConsumer) {
        throw new UnsupportedOperationException(
            providerName() + " does not support streaming for task " + task.keySegment());
    }

    /**
     * Startup-time assertion that this provider can serve the given
     * task under the current configuration. Providers that resolve
     * per-task config lazily (per call) MUST override this to run the
     * same config resolution their {@link #generate} path uses, so a
     * missing or typoed required property fails boot — via
     * {@code LlmRouter.assertAllTasksResolve()} — instead of throwing
     * at the first live call, where callers' retry-then-fallback
     * machinery would swallow the misconfiguration as a permanent
     * "transient" outage. The default is a no-op so providers with no
     * per-task config requirements (notably test stubs) remain valid
     * without overriding.
     */
    default void assertTaskConfigResolvable(ModelTask task) {
    }

    /**
     * Stable, operator-facing name the router registers this provider
     * under. The router resolves a per-task override property (e.g.
     * {@code infochat.llm.security.provider=openai-compatible}) and the
     * default-provider key against this name — so a concrete impl whose
     * config name differs from its class name (e.g.
     * {@code openai-compatible}, {@code anthropic}) MUST override this
     * to return its constant.
     *
     * <p>The default walks up from a CDI client-proxy subclass (whose
     * simple name carries a framework suffix such as {@code _ClientProxy})
     * to the developer-authored class and returns that simple name, so a
     * provider that does not override still gets a stable name across
     * framework versions. Defined here rather than as an {@code instanceof}
     * cascade in the router so adding a provider no longer edits the
     * router.
     */
    default String providerName() {
        return ProviderNames.unwrapProxySimpleName(getClass(), LlmProvider.class);
    }

    /**
     * Thrown when a provider's required per-task configuration cannot be
     * resolved — the missing/typoed-property failure that
     * {@link #assertTaskConfigResolvable} surfaces at boot and that the
     * shared per-call config read surfaces at the first {@link #generate}
     * call. Owned by the SPI so the config-system's own missing-property
     * type (e.g. SmallRye-Config's {@link java.util.NoSuchElementException})
     * stays an implementation detail: callers and the startup-scan tests
     * assert this type and never reach through the public API into a
     * third-party exception class. (M1-357)
     */
    final class TaskConfigUnresolvableException extends RuntimeException {
        public TaskConfigUnresolvableException(String message, Throwable cause) {
            super(message, cause);
        }

        public TaskConfigUnresolvableException(String message) {
            super(message);
        }
    }
}
