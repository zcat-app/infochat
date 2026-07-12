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
 *
 * <p>Not {@code final}: {@link ProviderUnreachableException} subtypes it so
 * the transport-unreachable failure class stays catchable under this one
 * family — every existing consumer catch keeps working unchanged while the
 * circuit breaker (M1-606) can attribute precisely.</p>
 */
public class LlmCallFailedException extends RuntimeException {

    public LlmCallFailedException(String message) {
        super(message);
    }

    public LlmCallFailedException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * The transport-unreachable subclass of the LLM call-failure family:
     * connection refused, DNS failure, no route, or a request/read timeout
     * — the endpoint itself could not be reached or did not answer. An
     * application-level failure (non-2xx status, response-body cap,
     * malformed JSON, wrong response shape) proves the endpoint IS
     * reachable and stays the plain {@link LlmCallFailedException}; the
     * split is decided by {@link LlmHttpSupport#isTransportUnreachable} at
     * the {@code sendForBody} catch site, the only place that sees the raw
     * transport exception. Only THIS type advances the circuit breaker's
     * consecutive-failure count, and the breaker's OPEN short-circuit
     * throws it too — so callers observe one typed "provider unreachable"
     * signal whether the call was attempted or fail-fasted (M1-606).
     *
     * <p>Nested in its supertype (mirroring the embedding side, where the
     * unreachable subtype nests beside {@code EmbeddingCallFailedException})
     * so each SPI's failure family lives in one file.</p>
     */
    public static final class ProviderUnreachableException extends LlmCallFailedException {

        public ProviderUnreachableException(String message) {
            super(message);
        }

        public ProviderUnreachableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
