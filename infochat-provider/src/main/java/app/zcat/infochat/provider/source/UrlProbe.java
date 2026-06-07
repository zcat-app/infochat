package app.zcat.infochat.provider.source;

import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient.SsrfPolicyException;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reachability + content-type probe for an {@code /add-source} URL,
 * issued via {@link SsrfGuardedHttpClient} so the same SSRF guards
 * Collector applies to feed fetches apply here. Per
 * {@code docs/design/03-commands.md} §{@code /add-source}: the spec
 * permits a lightweight {@code HEAD} or, for servers that reject
 * {@code HEAD}, a small-range {@code GET}. This implementation chooses
 * the small-range GET as the single probe shape to stay within the
 * existing SSRF-client surface + one additive overload
 * ({@link SsrfGuardedHttpClient#get(URI, java.util.Map)} with
 * {@code Range: bytes=0-0}).
 *
 * <p>Failure modes map to dedicated bundle keys so the handler can
 * surface a specific friendly error without interpolating exception
 * messages:
 * <ul>
 *   <li>SSRF rejection → {@link BundleKeys#ERROR_ADD_SOURCE_URL_BLOCKED_SSRF}</li>
 *   <li>HTTP 4xx/5xx → {@link BundleKeys#ERROR_ADD_SOURCE_URL_UNREACHABLE}</li>
 *   <li>Read timeout / deadline → {@link BundleKeys#ERROR_ADD_SOURCE_URL_TIMEOUT}</li>
 *   <li>Other I/O failure → {@link BundleKeys#ERROR_ADD_SOURCE_URL_UNREACHABLE}</li>
 * </ul>
 *
 * <p>StreamSource-shaped kinds (Nostr in v1) are probed by
 * {@link #probeRelay(URI)} instead: per {@code docs/spec/commands.md}
 * §Source management the equivalent check is a single WebSocket
 * connection attempt against the relay, gated by the same SSRF
 * allowlist via {@link SsrfGuardedHttpClient#checkAndPinForWebSocket}.
 * Failure modes map to the same bundle keys as the HTTP probe ("the
 * same friendly error" per spec).</p>
 */
@ApplicationScoped
public class UrlProbe {

    private static final Map<String, String> RANGE_FIRST_BYTE = Map.of("Range", "bytes=0-0");

    /**
     * Opening-handshake cap for the relay probe — mirrors the
     * collector's {@code NostrRelayConnection.CONNECT_TIMEOUT} so the
     * probe runs under the same timeout cap as stream-source fetcher
     * traffic (spec §Source management).
     */
    private static final Duration RELAY_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final SsrfGuardedHttpClient httpClient;

    /**
     * Plain JDK client for the relay-probe WebSocket handshake. DNS
     * pinning is process-wide ({@code PinnedDnsResolver}), so the dial
     * connects to the addresses {@link SsrfGuardedHttpClient}
     * validated — same pattern as the collector's
     * {@code NostrRelayConnection}.
     */
    private final HttpClient relayDialClient = HttpClient.newHttpClient();

    /**
     * CDI no-arg constructor. {@link SsrfGuardedHttpClient} is not
     * itself a CDI bean (matches the Collector's
     * {@code RssFetcher.RssFetcher()} pattern); the wrapper is
     * thread-safe so a single instance per Provider is correct.
     */
    public UrlProbe() {
        this(new SsrfGuardedHttpClient());
    }

    /**
     * Test-only constructor that lets test fixtures supply an
     * {@link SsrfGuardedHttpClient} configured with a loopback-
     * permitting blocklist so the in-process HttpServer fixture can
     * be dialed. Production never invokes this constructor — the
     * CDI no-arg constructor builds the strict default client. Public
     * (not package-private) so {@code AddSourceCommandHandlerTest}
     * and {@code AddSourceIT} (which live in a sibling package) can
     * register a CDI {@link jakarta.enterprise.inject.Alternative}
     * that subclasses {@link UrlProbe}.
     */
    public UrlProbe(SsrfGuardedHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Probe {@code url}. On success the result carries the response
     * status code and the {@code Content-Type} header (if present);
     * on failure it carries one of the {@code error.add_source.*}
     * bundle keys above and an empty content-type.
     */
    public ProbeResult probe(URI url) {
        try {
            HttpResponse<byte[]> response = httpClient.get(url, RANGE_FIRST_BYTE);
            int status = response.statusCode();
            Optional<String> contentType = response.headers().firstValue("Content-Type");
            // 200 OK (server ignoring Range) and 206 Partial Content are
            // both probe-success indicators. 3xx is unreachable here —
            // SsrfGuardedHttpClient follows redirects internally; if the
            // redirect cap is exceeded the wrapper raises
            // SsrfPolicyException (caught below).
            if (status >= 200 && status < 300) {
                return ProbeResult.success(status, contentType);
            }
            return ProbeResult.failure(BundleKeys.ERROR_ADD_SOURCE_URL_UNREACHABLE, status);
        } catch (SsrfPolicyException e) {
            // Body-read stalls are slow-server symptoms, not policy
            // rejections — surface them as TIMEOUT. Every other reason
            // (and any future one, via the default arm) is a policy
            // violation → BLOCKED_SSRF, the conservative bucket.
            return switch (e.reason()) {
                case BODY_READ_TIMEOUT, BODY_READ_DEADLINE_EXCEEDED ->
                        ProbeResult.failure(BundleKeys.ERROR_ADD_SOURCE_URL_TIMEOUT, 0);
                default -> ProbeResult.failure(BundleKeys.ERROR_ADD_SOURCE_URL_BLOCKED_SSRF, 0);
            };
        } catch (java.net.http.HttpTimeoutException e) {
            return ProbeResult.failure(BundleKeys.ERROR_ADD_SOURCE_URL_TIMEOUT, 0);
        } catch (IOException e) {
            // Network unreachable, DNS failure (when not raised via the
            // SSRF seam), TLS handshake failure, etc. Treat as unreachable.
            return ProbeResult.failure(BundleKeys.ERROR_ADD_SOURCE_URL_UNREACHABLE, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProbeResult.failure(BundleKeys.ERROR_ADD_SOURCE_URL_TIMEOUT, 0);
        }
    }

    /**
     * Single connection attempt against {@code relayUri} per
     * {@code docs/spec/commands.md} §Source management ("For
     * StreamSource-shaped kinds (Nostr in v1) the equivalent check is
     * a single connection attempt against the first relay in the
     * supplied {@code config}"). The SSRF check + per-host DNS pin
     * happens via {@link SsrfGuardedHttpClient#checkAndPinForWebSocket}
     * before any dial; the WebSocket opening handshake then runs
     * against the pinned, validated addresses. The socket is aborted
     * immediately on success — the probe proves reachability, nothing
     * more.
     *
     * <p>On success the result carries status 101 (the Switching
     * Protocols status of a completed WebSocket handshake) and no
     * content-type. Failure mapping mirrors {@link #probe(URI)}:
     * policy rejection → BLOCKED_SSRF, handshake refusal / I/O →
     * UNREACHABLE, handshake timeout → TIMEOUT.</p>
     */
    public ProbeResult probeRelay(URI relayUri) {
        // The pin must outlive the handshake (try-with-resources), but
        // not the probe — the connection is aborted before the pin is
        // released, so no post-pin traffic exists to protect.
        try (SsrfGuardedHttpClient.PinnedDial dial = httpClient.checkAndPinForWebSocket(relayUri)) {
            WebSocket webSocket = relayDialClient.newWebSocketBuilder()
                    .connectTimeout(RELAY_CONNECT_TIMEOUT)
                    .buildAsync(relayUri, new WebSocket.Listener() {})
                    // +1s buffer over the handshake's own cap, mirroring
                    // NostrRelayConnection.connectAndSubscribe.
                    .get(RELAY_CONNECT_TIMEOUT.toMillis() + 1_000, TimeUnit.MILLISECONDS);
            webSocket.abort();
            return ProbeResult.success(101, Optional.empty());
        } catch (SsrfPolicyException e) {
            // Only the resolve-time reasons (scheme, userinfo, host,
            // DNS, blocked IP) occur on the WebSocket path — all are
            // genuine policy rejections, never timeouts.
            return ProbeResult.failure(BundleKeys.ERROR_ADD_SOURCE_URL_BLOCKED_SSRF, 0);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof java.net.http.HttpTimeoutException) {
                return ProbeResult.failure(BundleKeys.ERROR_ADD_SOURCE_URL_TIMEOUT, 0);
            }
            // Connection refused, handshake rejected (non-WS endpoint),
            // TLS failure — ordinary unreachability, not SSRF policy.
            return ProbeResult.failure(BundleKeys.ERROR_ADD_SOURCE_URL_UNREACHABLE, 0);
        } catch (TimeoutException e) {
            return ProbeResult.failure(BundleKeys.ERROR_ADD_SOURCE_URL_TIMEOUT, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProbeResult.failure(BundleKeys.ERROR_ADD_SOURCE_URL_TIMEOUT, 0);
        }
    }

    /**
     * Probe outcome. Success carries the HTTP status code and the
     * {@code Content-Type} response header (if the server sent one);
     * failure carries the bundle key the handler should surface.
     */
    public record ProbeResult(
            boolean ok,
            int httpStatus,
            Optional<String> contentType,
            String failureBundleKey) {

        public static ProbeResult success(int httpStatus, Optional<String> contentType) {
            return new ProbeResult(true, httpStatus, contentType, "");
        }

        public static ProbeResult failure(String bundleKey, int httpStatus) {
            return new ProbeResult(false, httpStatus, Optional.empty(), bundleKey);
        }
    }
}
