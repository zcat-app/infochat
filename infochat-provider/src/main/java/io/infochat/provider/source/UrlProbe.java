package io.infochat.provider.source;

import io.infochat.provider.bundle.BundleKeys;
import io.infochat.ssrf.SsrfGuardedHttpClient;
import io.infochat.ssrf.SsrfGuardedHttpClient.SsrfPolicyException;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;

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
 */
@ApplicationScoped
public class UrlProbe {

    private static final Map<String, String> RANGE_FIRST_BYTE = Map.of("Range", "bytes=0-0");

    private final SsrfGuardedHttpClient httpClient;

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
            // The wrapper raises SsrfPolicyException for: blocked IP /
            // scheme / userinfo / oversize body / redirect cap / body-
            // read timeout / body-read deadline. The first three are
            // "blocked"; the last three are "timeout/unreachable".
            // Split on message prefix because the exception is a single
            // class.
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.startsWith("body read timeout") || message.startsWith("body read deadline")) {
                return ProbeResult.failure(BundleKeys.ERROR_ADD_SOURCE_URL_TIMEOUT, 0);
            }
            return ProbeResult.failure(BundleKeys.ERROR_ADD_SOURCE_URL_BLOCKED_SSRF, 0);
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
