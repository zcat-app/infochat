package app.zcat.infochat.llm;

import org.jspecify.annotations.NonNull;

/**
 * The single value an {@link LlmProvider} call returns. v1 commits only
 * to the model-produced text; per-call token usage, finish-reason,
 * latency, model-id, structured-output JSON parsing, and cache-hit
 * signals are impl-side concerns and land on a wrapped/companion shape
 * in the impl ticket that needs them.
 *
 * <p>The record wrapper exists so adding such a companion field later
 * is a one-spot diff, not a cross-call-site signature change.</p>
 */
public record LlmResponse(@NonNull String text) {
}
