package app.zcat.infochat.llm;

import org.jspecify.annotations.NonNull;

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
 * logic are downstream concerns and land with the first concrete impl;
 * they are intentionally NOT method-shape commitments here.</p>
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
    LlmResponse generate(@NonNull ModelTask task, @NonNull String systemPrompt, @NonNull String userPrompt);
}
