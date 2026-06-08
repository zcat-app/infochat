package app.zcat.infochat.llm.impl;

import app.zcat.infochat.llm.LlmResponse;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Shared HTTP helpers for the LLM / embedding provider impls in this
 * package. The load-bearing member is a <b>bounded body read</b> —
 * {@link #boundedStringHandler(long)} replaces
 * {@code BodyHandlers.ofString()} (unbounded) so a pathological
 * multi-GB reply from a misbehaving or hostile endpoint cannot OOM
 * the JVM. The LLM endpoint is operator-configured (semi-trusted),
 * so this is hygiene, not an SSRF-grade target — these calls do not
 * pass through the {@code infochat-ssrf} guard's {@code readBounded}.
 * Also home to the small request/log helpers every provider needs:
 * {@link #joinPath} and {@link #preview}.
 *
 * <p>Package-private: the three providers ({@link OpenAiCompatibleProvider},
 * {@link AnthropicProvider}, {@link OpenAiCompatibleEmbeddingProvider})
 * are its only callers and share its package.
 */
final class LlmHttpSupport {

    private static final Logger LOG = Logger.getLogger(LlmHttpSupport.class);

    /** Lower bound of the operator-configurable response-body cap (1 MiB). */
    static final long MIN_BODY_CAP_BYTES = 1L * 1024 * 1024;

    /** Upper bound of the operator-configurable response-body cap (8 MiB). */
    static final long MAX_BODY_CAP_BYTES = 8L * 1024 * 1024;

    /**
     * Default cap when the operator does not configure one. The most
     * permissive value in the allowed range — large enough not to
     * truncate a legitimate batch-embedding reply, still bounded so a
     * runaway response cannot exhaust the heap. The two HTTP chat
     * providers reach this default through {@link #executeJsonCall}'s
     * {@code getOptionalValue(...).orElse(...)} read — they reference
     * the constant directly, not a mirrored literal. Only
     * {@link OpenAiCompatibleEmbeddingProvider} mirrors it as the literal
     * {@code "8388608"} in its {@code @ConfigProperty} default, because
     * annotation arguments must be compile-time constants.
     */
    static final long DEFAULT_BODY_CAP_BYTES = MAX_BODY_CAP_BYTES;

    private LlmHttpSupport() {
    }

    /**
     * Clamp an operator-supplied byte cap into {@code [MIN, MAX]}. A
     * value below 1 MiB or above 8 MiB is a misconfiguration; rather
     * than fail the call we pull it to the nearest legal bound so the
     * provider keeps serving with a sane limit.
     */
    static long clampBodyCapBytes(long configured) {
        if (configured < MIN_BODY_CAP_BYTES) {
            return MIN_BODY_CAP_BYTES;
        }
        if (configured > MAX_BODY_CAP_BYTES) {
            return MAX_BODY_CAP_BYTES;
        }
        return configured;
    }

    /**
     * Provider-specific response-text extraction: turns a 2xx response
     * body into an {@link LlmResponse}, or throws
     * {@link OpenAiCompatibleProvider.LlmCallFailedException} when the
     * body is malformed or the wrong shape. {@code uri} is for
     * diagnostic messages only.
     */
    @FunctionalInterface
    interface LlmResponseParser {
        LlmResponse parse(String responseBody, URI uri);
    }

    /**
     * The shared HTTP call pipeline for the two chat-completion HTTP
     * providers ({@link AnthropicProvider} and
     * {@link OpenAiCompatibleProvider}): read and clamp the
     * operator-configurable body cap, send {@code request} with the
     * {@linkplain #boundedStringHandler(long) bounded} body handler, map
     * a transport failure or a non-2xx status onto
     * {@link OpenAiCompatibleProvider.LlmCallFailedException}, and hand a
     * 2xx body to {@code parser}. Single-sourced here so the response-cap
     * and failure-surface contract cannot drift between the two providers;
     * each provider supplies only its fully-built {@code request} and its
     * response-text {@code parser}.
     *
     * <p>{@code providerLabel} prefixes every log line and exception
     * message so the failing provider stays identifiable now that the
     * call site is shared.
     */
    static LlmResponse executeJsonCall(HttpClient http, Config config, HttpRequest request,
                                       String providerLabel, LlmResponseParser parser) {
        URI uri = request.uri();
        long cap = clampBodyCapBytes(
            config.getOptionalValue("infochat.llm.max-response-bytes", Long.class)
                .orElse(DEFAULT_BODY_CAP_BYTES));
        HttpResponse<String> response;
        try {
            response = http.send(request, boundedStringHandler(cap));
        } catch (IOException e) {
            throw new OpenAiCompatibleProvider.LlmCallFailedException(
                providerLabel + ": HTTP call failed for " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenAiCompatibleProvider.LlmCallFailedException(
                providerLabel + ": HTTP call interrupted for " + uri, e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String preview = preview(response.body());
            LOG.warnf("%s: non-2xx %d from %s; body preview: %s",
                providerLabel, response.statusCode(), uri, preview);
            throw new OpenAiCompatibleProvider.LlmCallFailedException(
                providerLabel + ": non-2xx status " + response.statusCode()
                    + " from " + uri + ": " + preview);
        }

        return parser.parse(response.body(), uri);
    }

    /**
     * Hard cap on the characters {@link #preview} retains. This is the
     * log-leak bound for error-path body previews: a non-2xx provider
     * reply reaches the log only through {@code preview}, so at most
     * this many characters of a response body can ever be logged.
     */
    static final int PREVIEW_MAX_CHARS = 200;

    /**
     * Concatenate {@code base} + {@code path} with exactly one slash
     * between them. {@code base} may end with {@code "/"} (Ollama
     * config often ends with {@code /v1/}); {@code path} starts with
     * {@code "/"} by convention in this package.
     */
    static String joinPath(String base, String path) {
        if (base.endsWith("/")) {
            return base.substring(0, base.length() - 1) + path;
        }
        return base + path;
    }

    /** Truncate a body for log inclusion — never leak the full reply. */
    static String preview(String s) {
        if (s.length() <= PREVIEW_MAX_CHARS) {
            return s;
        }
        return s.substring(0, PREVIEW_MAX_CHARS) + "…(" + s.length() + " bytes)";
    }

    /**
     * A bounded replacement for {@code BodyHandlers.ofString()}:
     * accumulates the body as UTF-8 but aborts the moment the received
     * byte count exceeds {@code maxBytes}, completing the body stage
     * with an {@link IOException}. {@code HttpClient.send} rethrows an
     * IOException body-failure as-is, so the provider's existing
     * {@code catch (IOException)} arm wraps it into the provider's own
     * call-failed exception — no new catch arm is needed at the call site.
     */
    static HttpResponse.BodyHandler<String> boundedStringHandler(long maxBytes) {
        return responseInfo -> new BoundedStringSubscriber(maxBytes);
    }

    /**
     * Counts bytes as they arrive and cancels the subscription the
     * instant the running total crosses {@code maxBytes}, so at most
     * one buffer beyond the cap is ever held in memory. On a clean
     * completion it joins the retained buffers into one UTF-8 string.
     */
    private static final class BoundedStringSubscriber implements HttpResponse.BodySubscriber<String> {
        private final long maxBytes;
        private final List<ByteBuffer> received = new ArrayList<>();
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private long byteCount = 0;
        // Assigned in onSubscribe() before any onNext()/onComplete() can run
        // (reactive-streams contract guarantees onSubscribe precedes every
        // other signal). NullAway's field-init check models only
        // constructors/initializers, not the Flow.Subscriber lifecycle, so
        // suppress that one check here; the field stays non-null for every
        // dereference.
        @SuppressWarnings("NullAway.Init")
        private Flow.Subscription subscription;

        BoundedStringSubscriber(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<String> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                byteCount += buffer.remaining();
            }
            if (byteCount > maxBytes) {
                subscription.cancel();
                body.completeExceptionally(new IOException(
                    "LLM response body exceeded the " + maxBytes + "-byte cap"));
                return;
            }
            received.addAll(buffers);
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            int total = 0;
            for (ByteBuffer buffer : received) {
                total += buffer.remaining();
            }
            byte[] bytes = new byte[total];
            int offset = 0;
            for (ByteBuffer buffer : received) {
                int length = buffer.remaining();
                buffer.get(bytes, offset, length);
                offset += length;
            }
            body.complete(new String(bytes, StandardCharsets.UTF_8));
        }
    }
}
