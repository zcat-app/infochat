package app.zcat.infochat.llm.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import app.zcat.infochat.llm.LlmResponse;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared HTTP helpers for the LLM / embedding provider impls in this
 * package. The load-bearing member is a <b>bounded body read</b> —
 * {@link #boundedStringHandler(long)} replaces
 * {@code BodyHandlers.ofString()} (unbounded) so a pathological
 * multi-GB reply from a misbehaving or hostile endpoint cannot OOM
 * the JVM. The LLM endpoint is operator-configured (semi-trusted),
 * so this is hygiene, not an SSRF-grade target — these calls do not
 * pass through the {@code infochat-ssrf} guard's {@code readBounded}.
 * Also home to the small shared state and helpers every provider needs:
 * the {@link #JSON} mapper, the {@link #requireHttpBaseUrl} config-boundary
 * validator, and {@link #joinPath}.
 *
 * <p>The class is {@code public} only so the one shared redactor
 * {@link #redactUserInfo} is reachable from the sibling {@code routing}
 * package's {@code LlmRouterStartupGuard} (M1-423) — duplicating the redactor
 * there would let the two copies drift. Every other member stays
 * package-private, so the three providers ({@link OpenAiCompatibleProvider},
 * {@link AnthropicProvider}, {@link OpenAiCompatibleEmbeddingProvider}) in this
 * package remain the only callers of the HTTP machinery.
 */
public final class LlmHttpSupport {

    private static final Logger LOG = Logger.getLogger(LlmHttpSupport.class);

    /**
     * Shared Jackson mapper for the impl-package providers. An
     * {@link ObjectMapper} is thread-safe once configured, so one default
     * instance serves all three providers' request-assembly and
     * response-parsing — collapsing what were three byte-identical
     * per-class fields.
     */
    static final ObjectMapper JSON = new ObjectMapper();

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

    /**
     * Marker for the streaming read's over-cap abort, so
     * {@link #executeStreamingCall} keeps the single-string path's
     * classification: an over-cap body proves the endpoint answered
     * (application failure, breaker does not trip) while every other
     * mid-body {@link IOException} is a transport drop (breaker trips).
     */
    static final class StreamBodyCapExceededException extends IOException {
        StreamBodyCapExceededException(String message) {
            super(message);
        }
    }

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
     * {@link LlmCallFailedException} when the
     * body is malformed or the wrong shape. {@code uri} is for
     * diagnostic messages only.
     */
    @FunctionalInterface
    interface LlmResponseParser {
        LlmResponse parse(String responseBody, URI uri);
    }

    /**
     * Per-dialect interpreter of an SSE-streaming chat completion:
     * {@link #onFrame} receives each completed {@code data:} payload
     * (deltas drive the chunk consumer) and returns {@code true} on the
     * dialect's terminal frame; {@link #result} assembles the
     * {@link LlmResponse} afterwards. A frame that does not parse
     * throws {@link LlmCallFailedException} from {@code onFrame} — the
     * application-failure family, never a synthetic chunk.
     */
    interface StreamingResponseParser {
        /** Process one data payload; true when it was the terminal frame. */
        boolean onFrame(String data);

        /** The assembled response, after the terminal frame. */
        LlmResponse result();
    }

    /**
     * Builds a provider-specific call-failure exception. {@code cause} is
     * {@code null} on the non-2xx path (a status check, no transport
     * exception to chain) and non-null on the transport / interrupt paths.
     * Each provider supplies its own exception type — chat providers throw
     * {@link LlmCallFailedException}, the embedding
     * provider its own {@code EmbeddingCallFailedException} — so the shared
     * {@link #sendForBody} pipeline can surface the failure under the type
     * the caller's downstream (LLM router vs. EmbeddingWorker) expects.
     *
     * <p>{@code providerUnreachable} carries {@link #isTransportUnreachable}'s
     * verdict out of the one catch site that sees the raw transport
     * exception, so each factory can pick its family's unreachable subtype
     * — the typed signal only the circuit breaker trips on (M1-606) —
     * versus the plain application-failure type.</p>
     */
    @FunctionalInterface
    interface CallFailureFactory {
        RuntimeException create(String message, @Nullable Throwable cause, boolean providerUnreachable);
    }

    /**
     * True iff the failure says the endpoint itself was unreachable —
     * connection refused ({@link ConnectException}), DNS failure
     * ({@link UnknownHostException}), no route
     * ({@link NoRouteToHostException}), or a connect/read timeout
     * ({@link HttpTimeoutException}) — as opposed to an application-level
     * failure from an endpoint that answered. The cause chain is walked
     * because the JDK {@link HttpClient} wraps the discriminating exception
     * at varying depths (e.g. a DNS failure surfaces as a
     * {@code ConnectException} whose cause names the unresolved address).
     * The bounded-body-cap {@link IOException} and an
     * {@link InterruptedException} deliberately classify as NOT unreachable:
     * the first proves the endpoint responded (too much), the second is a
     * caller-side cancellation that says nothing about the endpoint — and
     * the safe mis-classification direction is "not unreachable" (the
     * breaker trips less eagerly; a missed trip merely keeps today's
     * fail-slow behaviour). (M1-606)
     */
    static boolean isTransportUnreachable(IOException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException
                    || t instanceof UnknownHostException
                    || t instanceof NoRouteToHostException
                    || t instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * The shared send + clamp + non-2xx pipeline for all three HTTP
     * providers ({@link AnthropicProvider}, {@link OpenAiCompatibleProvider}
     * via {@link #executeJsonCall}, and {@link OpenAiCompatibleEmbeddingProvider}
     * directly): send {@code request} with the
     * {@linkplain #boundedStringHandler(long) bounded} body handler capped at
     * {@code cap} bytes, map a transport failure or a non-2xx status onto the
     * caller's exception via {@code failure}, and return the 2xx body for the
     * caller to parse. Single-sourced here so the response-cap and
     * failure-surface contract cannot drift across the providers; each one
     * supplies only its fully-built {@code request}, its already-clamped
     * {@code cap}, its {@code providerLabel}, and its exception
     * {@code failure} factory.
     *
     * <p>{@code providerLabel} prefixes every log line and exception message
     * so the failing provider stays identifiable now that the call site is
     * shared.
     */
    static String sendForBody(HttpClient http, HttpRequest request, long cap,
                              String providerLabel, CallFailureFactory failure) {
        URI uri = request.uri();
        HttpResponse<String> response;
        try {
            response = http.send(request, boundedStringHandler(cap));
        } catch (IOException e) {
            throw failure.create(providerLabel + ": HTTP call failed for " + uri, e,
                isTransportUnreachable(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Not unreachable: an interrupt is caller-side cancellation, no
            // evidence about the endpoint (see isTransportUnreachable).
            throw failure.create(providerLabel + ": HTTP call interrupted for " + uri, e, false);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // Provider error bodies can echo request fragments or user
            // content, so they never reach the log or the exception
            // message by default — only the provider, status, and host,
            // which is all triage needs.
            String host = uri.getHost();
            LOG.warnf("%s: non-2xx %d from %s",
                providerLabel, response.statusCode(), host);
            // Not unreachable: a status line means the endpoint answered.
            throw failure.create(providerLabel + ": non-2xx status " + response.statusCode()
                + " from " + host, null, false);
        }

        return response.body();
    }

    /**
     * The shared HTTP call pipeline for the two chat-completion HTTP
     * providers ({@link AnthropicProvider} and
     * {@link OpenAiCompatibleProvider}): read and clamp the
     * operator-configurable body cap, run the send / non-2xx surface through
     * {@link #sendForBody} under {@link LlmCallFailedException},
     * and hand the 2xx body to {@code parser}. Each provider supplies only
     * its fully-built {@code request} and its response-text {@code parser}.
     *
     * <p>{@code providerLabel} prefixes every log line and exception
     * message so the failing provider stays identifiable now that the
     * call site is shared.
     */
    static LlmResponse executeJsonCall(HttpClient http, Config config, HttpRequest request,
                                       String providerLabel, LlmResponseParser parser) {
        long cap = clampBodyCapBytes(
            config.getOptionalValue("infochat.llm.max-response-bytes", Long.class)
                .orElse(DEFAULT_BODY_CAP_BYTES));
        String body = sendForBody(http, request, cap, providerLabel, (message, cause, unreachable) -> {
            if (unreachable) {
                // cause is always non-null here (only the IOException path
                // classifies unreachable); the null-split form is what the
                // nullness analysis can verify.
                return cause == null
                    ? new LlmCallFailedException.ProviderUnreachableException(message)
                    : new LlmCallFailedException.ProviderUnreachableException(message, cause);
            }
            return cause == null
                ? new LlmCallFailedException(message)
                : new LlmCallFailedException(message, cause);
        });
        return parser.parse(body, request.uri());
    }

    /**
     * The streaming twin of {@link #executeJsonCall}: sends
     * {@code request} asynchronously, rejects a non-2xx status with the
     * same redacted message shape, then drives the SSE body through
     * {@link SseFramer} into {@code parser} until its terminal frame.
     * Distinct failure postures, all under {@link LlmCallFailedException}:
     * the whole call — headers AND every inter-chunk read — is bounded
     * by {@code callTimeoutMs} (a stall anywhere is a read timeout,
     * transport class, and trips the breaker); a mid-body
     * {@link IOException} that is not the cap marker is a transport
     * drop and trips the breaker; the cap marker, a frame the parser
     * rejects, and malformed UTF-8 are application failures and do
     * not; an interrupt aborts the call without an un-cancelled
     * exchange and re-arms the flag. A body that ends without the
     * dialect's terminal frame fails the call — no synthetic terminal.
     */
    static LlmResponse executeStreamingCall(HttpClient http, Config config, HttpRequest request,
                                            String providerLabel, long callTimeoutMs,
                                            StreamingResponseParser parser) {
        long cap = clampBodyCapBytes(
            config.getOptionalValue("infochat.llm.max-response-bytes", Long.class)
                .orElse(DEFAULT_BODY_CAP_BYTES));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(callTimeoutMs);
        // sendAsync (unlike send) performs no armed-interrupt entry
        // check, so an already-cancelled caller would still fire the
        // request; refuse here instead.
        if (Thread.currentThread().isInterrupted()) {
            throw new LlmCallFailedException(
                providerLabel + ": streaming call interrupted before send for " + request.uri());
        }
        CompletableFuture<HttpResponse<StreamLineReader>> exchange =
            http.sendAsync(request, lineReaderHandler(cap));
        HttpResponse<StreamLineReader> response;
        try {
            response = exchange.get(remainingMillisUntil(deadline), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new LlmCallFailedException(
                providerLabel + ": streaming call interrupted for " + request.uri(), e);
        } catch (ExecutionException e) {
            throw headersPhaseFailure(providerLabel, request.uri(), e);
        } catch (TimeoutException e) {
            throw new LlmCallFailedException.ProviderUnreachableException(
                providerLabel + ": streaming call timed out after " + callTimeoutMs
                    + " ms for " + request.uri());
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String host = request.uri().getHost();
            LOG.warnf("%s: non-2xx %d from %s", providerLabel, response.statusCode(), host);
            throw new LlmCallFailedException(providerLabel + ": non-2xx status "
                + response.statusCode() + " from " + host);
        }
        try (StreamLineReader reader = response.body()) {
            SseFramer framer = new SseFramer();
            String line;
            while ((line = reader.nextLine(deadline)) != null) {
                String payload = framer.feed(line);
                if (payload != null && parser.onFrame(payload)) {
                    return parser.result();
                }
            }
            throw new LlmCallFailedException(
                providerLabel + ": stream ended without a terminal frame from " + request.uri().getHost());
        } catch (StreamBodyCapExceededException e) {
            throw new LlmCallFailedException(
                providerLabel + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new LlmCallFailedException.ProviderUnreachableException(
                providerLabel + ": stream read failed for " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmCallFailedException(
                providerLabel + ": stream read interrupted for " + request.uri(), e);
        }
    }

    /**
     * Maps a headers-phase exchange failure onto the single-string
     * path's exact split: transport-unreachable per
     * {@link #isTransportUnreachable}, else application. A missing
     * cause (cannot happen for a failed exchange, but
     * {@link ExecutionException#getCause()} is platform-nullable) is
     * the plain application failure.
     */
    private static RuntimeException headersPhaseFailure(String providerLabel, URI uri,
                                                        ExecutionException execution) {
        Throwable cause = execution.getCause();
        if (cause == null) {
            return new LlmCallFailedException(
                providerLabel + ": HTTP call failed for " + uri, execution);
        }
        if (cause instanceof IOException io) {
            if (isTransportUnreachable(io)) {
                return new LlmCallFailedException.ProviderUnreachableException(
                    providerLabel + ": HTTP call failed for " + uri, io);
            }
            return new LlmCallFailedException(providerLabel + ": HTTP call failed for " + uri, io);
        }
        if (cause instanceof RuntimeException re) {
            return re;
        }
        return new LlmCallFailedException(providerLabel + ": HTTP call failed for " + uri,
            new IOException(cause));
    }

    private static long remainingMillisUntil(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        return remaining <= 0 ? 0 : Math.max(1, remaining / 1_000_000);
    }

    /**
     * Config-boundary validation of an operator-supplied LLM/embedding
     * base-url: the value must parse as a URI, carry an {@code http} or
     * {@code https} scheme, name a host, and carry no inline credentials
     * (userinfo). A malformed value dies here —
     * at startup, where every provider's config is asserted — naming
     * {@code propertyKey}, rather than throwing {@link IllegalArgumentException}
     * from a per-call {@code URI.create} deep inside a live call, where the
     * caller's retry-then-fallback catch would absorb a permanent
     * misconfiguration as a transient outage.
     *
     * @throws IllegalArgumentException when {@code baseUrl} does not parse,
     *     uses a non-http(s) scheme, names no host, or embeds userinfo; the
     *     message names {@code propertyKey} so the operator can find the
     *     offending property. The userinfo case is the only one whose message
     *     does NOT echo {@code baseUrl} — echoing it would leak the very
     *     credential the check exists to keep out of diagnostics (M1-330).
     */
    static void requireHttpBaseUrl(String baseUrl, String propertyKey) {
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            // Both echoes are userinfo-redacted: baseUrl directly, and
            // e.getMessage() because URISyntaxException re-quotes the raw input
            // verbatim ("...at index N: <input>"). The cause is dropped rather
            // than chained — its own getMessage() carries the unredacted input,
            // which Quarkus would print in the boot log's "Caused by:" chain,
            // re-opening the M1-330 credential leak on this sibling branch.
            throw new IllegalArgumentException(
                propertyKey + "='" + redactUserInfo(baseUrl) + "' is not a valid URI: "
                    + redactUserInfo(e.getMessage()));
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(
                propertyKey + "='" + redactUserInfo(baseUrl) + "' must use an http or https scheme");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException(
                propertyKey + "='" + redactUserInfo(baseUrl) + "' must name a host");
        }
        // Reject inline credentials (https://user:pass@host). The
        // OpenAI-compatible wire shape accepts userinfo in the URL, but the
        // uri then flows into per-call diagnostic messages; rejecting it here
        // at the config boundary makes that credential leak structurally
        // impossible and steers operators to the api-key property. The message
        // must NOT echo baseUrl — that would re-leak the userinfo it rejects.
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException(
                propertyKey + " must not embed credentials (userinfo) in the URL;"
                    + " supply the credential via the corresponding api-key property instead");
        }
    }

    /**
     * Config-boundary validation of an operator-supplied {@code timeout-ms}:
     * the value must be strictly positive. The sibling of
     * {@link #requireHttpBaseUrl} for the other property every HTTP provider
     * reads at the startup resolvability scan — a non-positive value dies here,
     * at startup, naming {@code propertyKey}, rather than reaching
     * {@link HttpRequest.Builder#timeout(java.time.Duration)} on the first live
     * call, where it throws {@link IllegalArgumentException} that the
     * Stage 2 / EmbeddingWorker retry-then-fallback catch absorbs as a recurring
     * transient outage — silently degrading a boot-time misconfiguration into a
     * permanent fake "outage" (M1-409). The timeout is a plain integer, not a
     * credential, so the message echoes the offending value directly.
     *
     * @throws IllegalArgumentException when {@code timeoutMs <= 0}; the message
     *     names {@code propertyKey} so the operator can find the offending
     *     property.
     */
    static void requirePositiveTimeoutMs(long timeoutMs, String propertyKey) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException(
                propertyKey + "=" + timeoutMs + " must be a positive duration in milliseconds");
        }
    }

    /**
     * Config-boundary validation of an operator-supplied {@code max-tokens}:
     * the value must be strictly positive. The sibling of
     * {@link #requirePositiveTimeoutMs} for the other numeric property
     * {@link AnthropicProvider} reads at the startup resolvability scan — a
     * non-positive value dies here, at startup, naming {@code propertyKey},
     * rather than reaching the Anthropic Messages API on the first live call,
     * where it returns a non-2xx that the Stage 2 retry-then-fallback catch
     * absorbs as a recurring transient outage — silently degrading a boot-time
     * misconfiguration into a permanent fake "outage" (M1-412, sibling of
     * M1-409's timeout-ms guard). Only {@link AnthropicProvider} reads
     * {@code max-tokens}; the OpenAI-compatible providers do not, so this guard
     * lives at that one call site. The token count is a plain integer, not a
     * credential, so the message echoes the offending value directly.
     *
     * @throws IllegalArgumentException when {@code maxTokens <= 0}; the message
     *     names {@code propertyKey} so the operator can find the offending
     *     property.
     */
    static void requirePositiveMaxTokens(int maxTokens, String propertyKey) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException(
                propertyKey + "=" + maxTokens + " must be a positive token count");
        }
    }

    /**
     * Mask the userinfo span of any {@code scheme://USER:PASS@host...}
     * substring before the value is echoed into a diagnostic message, so a
     * credential-bearing base-url cannot leak verbatim (M1-330). This is a
     * textual scrub, not a structural parse: it runs on the
     * {@link #requireHttpBaseUrl} failure branches and on
     * {@code LlmRouterStartupGuard}'s base-url log / exception lines (M1-423),
     * including ones where {@code new URI(...)} already refused the input, so it
     * cannot rely on a parsed authority. It masks the whole userinfo span (user AND password)
     * rather than just the password — the safe over-redaction direction when
     * the structure is untrusted.
     *
     * <p>Scope of the scrub: everything from {@code ://} up to the LAST
     * {@code @} is treated as userinfo and replaced with {@code ***}. The mask
     * deliberately does NOT first bound an "authority" at the first
     * {@code /}/{@code ?}/{@code #} — on the malformed inputs that reach the
     * parse-failure branch, a raw delimiter can sit INSIDE the userinfo (e.g.
     * {@code https://us er:pa/ss@host/v1}, where the space forces the parse
     * failure and the {@code /} would truncate an authority scan before the
     * real {@code @}), which would leave the credential un-masked. Masking to
     * the last {@code @} closes that gap; the only cost is over-masking a host
     * in the rare case an {@code @} appears in the path of a credential-free
     * URL, which is the safe direction for a diagnostic message. No {@code ://}
     * or no {@code @} after it means no userinfo to mask (the common
     * credential-free typo keeps its echo), and the value is returned
     * unchanged. {@code value} is {@code @Nullable} because one caller is
     * {@code URISyntaxException.getMessage()}, whose contract permits null; a
     * null in yields a null out unchanged.
     */
    public static @Nullable String redactUserInfo(@Nullable String value) {
        if (value == null) {
            return null;
        }
        int schemeEnd = value.indexOf("://");
        if (schemeEnd < 0) {
            return value;
        }
        int authorityStart = schemeEnd + 3;
        int at = value.lastIndexOf('@');
        if (at < authorityStart) {
            return value;
        }
        return value.substring(0, authorityStart) + "***@" + value.substring(at + 1);
    }

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

    /**
     * SSE line framing per the {@code text/event-stream} grammar this
     * package's two dialects need: {@code data:} field lines accumulate
     * (multi-line data joins with {@code \n}) and dispatch on the blank
     * line; comments and every other field ({@code event}, {@code id},
     * {@code retry}) are ignored — the {@code data} payload's own
     * {@code type} discriminates the frame, never the {@code event}
     * line.
     */
    static final class SseFramer {
        private final StringBuilder data = new StringBuilder();
        private boolean sawData;

        /** Feed one stream line; non-null when a complete frame dispatches. */
        @Nullable String feed(String line) {
            if (line.isEmpty()) {
                return dispatch();
            }
            if (line.startsWith(":")) {
                return null;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (!value.isEmpty() && value.charAt(0) == ' ') {
                value = value.substring(1);
            }
            if ("data".equals(field)) {
                if (sawData) {
                    data.append('\n');
                }
                data.append(value);
                sawData = true;
            }
            return null;
        }

        private @Nullable String dispatch() {
            if (!sawData) {
                return null;
            }
            String payload = data.toString();
            data.setLength(0);
            sawData = false;
            return payload;
        }
    }

    /**
     * The streaming counterpart of {@link #boundedStringHandler(long)}:
     * a body handler whose reader hands the caller one decoded body
     * line at a time as it arrives, under the same byte cap and with
     * the whole-call deadline the caller enforces per line.
     */
    static HttpResponse.BodyHandler<StreamLineReader> lineReaderHandler(long maxBytes) {
        return responseInfo -> new CappedLineSubscriber(maxBytes);
    }

    /**
     * Blocking, deadline-bounded line source over the live response
     * body. {@link #nextLine} surfaces a read timeout as
     * {@link HttpTimeoutException} (transport class), the cap abort as
     * {@link StreamBodyCapExceededException}, and honors interruption
     * — a blocking {@code poll} on an interrupted thread aborts
     * immediately, the M1-763 virtual-thread hazard.
     */
    static final class StreamLineReader implements AutoCloseable {
        private final LinkedBlockingQueue<Object> events;
        private final AtomicReference<Flow.Subscription> subscription;

        StreamLineReader(LinkedBlockingQueue<Object> events,
                         AtomicReference<Flow.Subscription> subscription) {
            this.events = events;
            this.subscription = subscription;
        }

        /**
         * Next body line, or null at a clean end of stream. Blocks up
         * to the whole-call {@code deadlineNanos}.
         */
        @Nullable String nextLine(long deadlineNanos) throws IOException, InterruptedException {
            long remaining = deadlineNanos - System.nanoTime();
            Object event = events.poll(remaining, TimeUnit.NANOSECONDS);
            if (event == null) {
                throw new HttpTimeoutException("LLM stream read timed out waiting for the next line");
            }
            if (event == STREAM_END) {
                return null;
            }
            if (event instanceof Throwable failure) {
                if (failure instanceof IOException io) {
                    throw io;
                }
                if (failure instanceof RuntimeException re) {
                    throw re;
                }
                throw new IOException(failure);
            }
            return decodeLine((byte[]) event);
        }

        /** Cancels the subscription; terminal frames leave the rest unread. */
        @Override
        public void close() {
            Flow.Subscription s = subscription.get();
            if (s != null) {
                s.cancel();
            }
        }
    }

    private static final Object STREAM_END = new Object();

    /**
     * Splits arriving bytes into lines ({@code \n}, with a trailing
     * {@code \r} stripped) and queues them for {@link StreamLineReader};
     * counts every received byte against the cap and cancels the
     * subscription the moment it is crossed. Line-splitting on the raw
     * {@code 0x0A} byte is UTF-8-safe by construction: no multibyte
     * sequence contains {@code 0x0A}, so a complete line is always a
     * complete UTF-8 sequence and can be decoded (reporting, not
     * replacing) on its own.
     */
    private static final class CappedLineSubscriber implements HttpResponse.BodySubscriber<StreamLineReader> {
        private final long maxBytes;
        private final LinkedBlockingQueue<Object> events = new LinkedBlockingQueue<>();
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final CompletableFuture<StreamLineReader> body = new CompletableFuture<>();
        private final ByteArrayOutputStream lineBytes = new ByteArrayOutputStream();
        private long byteCount = 0;

        CappedLineSubscriber(long maxBytes) {
            this.maxBytes = maxBytes;
            body.complete(new StreamLineReader(events, subscription));
        }

        @Override
        public CompletionStage<StreamLineReader> getBody() {
            // Complete immediately, not at body end: sendAsync's
            // response future (and so executeStreamingCall) must return
            // once headers arrive so the body streams line by line.
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription.set(subscription);
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                byteCount += buffer.remaining();
                if (byteCount > maxBytes) {
                    // onSubscribe precedes onNext by the reactive-streams
                    // contract; the null-check only satisfies the analysis.
                    Flow.Subscription s = subscription.get();
                    if (s != null) {
                        s.cancel();
                    }
                    events.clear();
                    events.offer(new StreamBodyCapExceededException(
                        "LLM stream body exceeded the " + maxBytes + "-byte cap"));
                    return;
                }
                while (buffer.hasRemaining()) {
                    int b = buffer.get() & 0xFF;
                    if (b == '\n') {
                        byte[] line = lineBytes.toByteArray();
                        lineBytes.reset();
                        int length = line.length;
                        if (length > 0 && line[length - 1] == '\r') {
                            length--;
                        }
                        events.offer(java.util.Arrays.copyOf(line, length));
                    } else {
                        lineBytes.write(b);
                    }
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            events.offer(throwable);
        }

        @Override
        public void onComplete() {
            if (lineBytes.size() > 0) {
                events.offer(lineBytes.toByteArray());
                lineBytes.reset();
            }
            events.offer(STREAM_END);
        }
    }

    /** Decode one complete body line; malformed UTF-8 is an application failure. */
    private static String decodeLine(byte[] line) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(line))
                .toString();
        } catch (CharacterCodingException e) {
            throw new LlmCallFailedException("LLM stream body is not valid UTF-8", e);
        }
    }
}
