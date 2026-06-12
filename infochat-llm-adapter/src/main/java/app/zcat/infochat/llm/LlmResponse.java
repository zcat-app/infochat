package app.zcat.infochat.llm;


import org.jspecify.annotations.Nullable;

/**
 * The value an {@link LlmProvider} call returns. v1 commits to the
 * model-produced text; {@code model} and {@code usage} are optional
 * observability companions (M1-321) populated when the provider's wire
 * response reports them — {@code null} means "not reported", and
 * callers other than the metrics decorator must not depend on them.
 * Finish-reason, structured-output JSON parsing, and cache-hit signals
 * remain impl-side concerns for the ticket that needs them.
 *
 * @param text  the model-produced text. Never null.
 * @param model the model identifier the server reports having run
 *              (response-body {@code model} field), or null when the
 *              response omits it.
 * @param usage per-call token counts as reported by the provider, or
 *              null when the response omits them.
 */
public record LlmResponse(String text, @Nullable String model, @Nullable TokenUsage usage) {

    /** Text-only shape: providers and stubs with no usage reporting. */
    public LlmResponse(String text) {
        this(text, null, null);
    }

    /** Provider-reported token counts for one call. */
    public record TokenUsage(long inputTokens, long outputTokens) {
    }
}
