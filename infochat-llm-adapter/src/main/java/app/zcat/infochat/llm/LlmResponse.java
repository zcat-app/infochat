package app.zcat.infochat.llm;


import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The value an {@link LlmProvider} call returns. v1 commits to the
 * model-produced text; {@code model} and {@code usage} are optional
 * observability companions (M1-321) populated when the provider's wire
 * response reports them — {@code null} means "not reported", and
 * callers other than the metrics decorator must not depend on them.
 * Structured-output JSON parsing and cache-hit signals remain
 * impl-side concerns for the ticket that needs them.
 *
 * @param text  the model-produced text. Never null. Empty on a
 *              tools-bearing reply whose {@code message.content} the
 *              endpoint omitted.
 * @param model the model identifier the server reports having run
 *              (response-body {@code model} field), or null when the
 *              response omits it.
 * @param usage per-call token counts as reported by the provider, or
 *              null when the response omits them.
 * @param toolCalls the structured calls a tools-bearing reply carried
 *              ({@code choices[0].message.tool_calls[]}), or null on
 *              text-only shapes; args stay a raw JSON string.
 * @param finishReason the reply's {@code finish_reason} when the wire
 *              reported one (e.g. {@code tool_calls}, {@code stop}),
 *              else null.
 */
public record LlmResponse(String text, @Nullable String model, @Nullable TokenUsage usage,
                          @Nullable List<ToolCallRequest> toolCalls, @Nullable String finishReason) {

    /** Text-only shape: providers and stubs with no usage reporting. */
    public LlmResponse(String text) {
        this(text, null, null, null, null);
    }

    /** Text + observability companions: the text-only call shapes. */
    public LlmResponse(String text, @Nullable String model, @Nullable TokenUsage usage) {
        this(text, model, usage, null, null);
    }

    /** One structured tool call: tool name plus its raw args JSON string. */
    public record ToolCallRequest(String name, String argumentsJson) {
    }

    /** Provider-reported token counts for one call. */
    public record TokenUsage(long inputTokens, long outputTokens) {
    }
}
