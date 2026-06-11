package app.zcat.infochat.llm.impl;


/**
 * Unchecked exception covering every failure mode the chat providers'
 * caller (Stage 2 worker) treats as infrastructure failure: network I/O,
 * non-2xx HTTP, malformed JSON, missing required response fields, and
 * assembly errors on the request side. Both {@link OpenAiCompatibleProvider}
 * and {@link AnthropicProvider} throw it, and the shared
 * {@link LlmHttpSupport#executeJsonCall} pipeline surfaces it — so it is a
 * top-level type in this package rather than nested inside one provider that
 * its sibling and the shared helper would have to reach into. The caller's
 * retry-once-then-fallback harness catches this type uniformly per
 * {@code docs/spec/security.md} §Failure handling.
 */
public final class LlmCallFailedException extends RuntimeException {

    public LlmCallFailedException(String message) {
        super(message);
    }

    public LlmCallFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
