package app.zcat.infochat.llm;


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
