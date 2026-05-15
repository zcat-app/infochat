package io.infochat.ssrf;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Fail-closed outbound HTTP gate for Collector and Provider per
 * {@code docs/spec/security.md} §SSRF and outbound connections
 * (decision D20). Every {@link #get(URI)} call runs a per-hop policy
 * pipeline:
 *
 * <ol>
 *   <li><strong>Scheme allowlist</strong> — only {@code http} and
 *       {@code https} are dialed. {@code ws} and {@code wss} are
 *       deliberately rejected for now: the WebSocket transport
 *       wrapper for {@code StreamSource} consumes the same
 *       {@link IpBlocklist} policy class but is its own
 *       implementation (carved out per the ticket's
 *       {@code out_of_scope}).</li>
 *   <li><strong>Userinfo gate</strong> — any URI carrying
 *       {@code user[:password]@} is rejected before the dial. The
 *       {@link UrlRedactor} exists for log/exception output; this
 *       gate prevents credential laundering at intake.</li>
 *   <li><strong>DNS resolution</strong> — every resolved address is
 *       checked against {@link IpBlocklist}; ANY blocked address in
 *       the result set rejects the request.</li>
 *   <li><strong>HTTP send</strong> with non-zero connect + request
 *       timeouts, {@code Redirect.NEVER}, and a length-bounded body
 *       reader (default 10 MiB).</li>
 *   <li><strong>Redirect handling</strong> — on 3xx the wrapper
 *       parses the {@code Location} header, increments a per-call
 *       counter, and re-enters the pipeline from step 1. JDK
 *       {@code Redirect.NORMAL} does NOT re-resolve DNS on follow,
 *       which leaves a DNS-rebind window the spec explicitly
 *       closes ("DNS is re-resolved after every redirect"). The
 *       redirect cap (default 3) bounds the attacker window.</li>
 * </ol>
 *
 * <p>Production callers always use the no-arg constructor, which
 * wires the strict {@link IpBlocklist}. The package-private
 * constructor accepting an arbitrary {@link IpBlocklist} is for the
 * {@code SsrfGuardedHttpClientTest} fixture only: tests that need
 * to dial a localhost {@code HttpServer} construct an explicit
 * non-default {@link IpBlocklist} that permits 127.0.0.1. The
 * carve-out is a deliberate API surface, NOT a global flag —
 * accidentally enabling it in production requires passing a custom
 * {@link IpBlocklist} instance, which is impossible to do by
 * configuration mistake.
 *
 * <p>This class is thread-safe by sharing one immutable
 * {@link HttpClient} and stateless {@link IpBlocklist}; one wrapper
 * instance can be reused across calls.
 */
public final class SsrfGuardedHttpClient {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    // 10 MiB — large enough for the longest RSS / Atom feeds observed
    // in the wild, small enough to bound the worst-case allocation
    // against a hostile feed serving an unbounded payload.
    private static final long DEFAULT_BODY_CAP = 10L * 1024 * 1024;

    private static final int DEFAULT_REDIRECT_CAP = 3;

    private static final String USER_AGENT = "infochat/0.0.1-SNAPSHOT";

    private static final String ACCEPT_HEADER = "*/*";

    private final IpBlocklist blocklist;

    private final Duration requestTimeout;

    private final long bodyCap;

    private final int redirectCap;

    private final HttpClient httpClient;

    /**
     * Production constructor. Wires the strict {@link IpBlocklist}
     * and the profile-driven default timeouts / caps.
     */
    public SsrfGuardedHttpClient() {
        this(new IpBlocklist(),
             DEFAULT_CONNECT_TIMEOUT,
             DEFAULT_REQUEST_TIMEOUT,
             DEFAULT_BODY_CAP,
             DEFAULT_REDIRECT_CAP);
    }

    /**
     * Parameterized constructor — the test-mode seam and the
     * production future-knob surface. The strict production
     * blocklist refuses to dial 127.0.0.1 (the
     * {@code com.sun.net.httpserver.HttpServer} fixture's bind
     * address); tests pass a permissive {@link IpBlocklist}
     * subclass that carves out the loopback range. Engaging the
     * carve-out requires deliberately constructing a non-default
     * {@link IpBlocklist} and passing it — accidentally enabling
     * it is impossible without writing code that visibly subclasses
     * the production policy.
     */
    public SsrfGuardedHttpClient(IpBlocklist blocklist,
                                 Duration connectTimeout,
                                 Duration requestTimeout,
                                 long bodyCap,
                                 int redirectCap) {
        if (blocklist == null) {
            throw new IllegalArgumentException("blocklist must be configured");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be configured");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be configured");
        }
        if (bodyCap <= 0) {
            throw new IllegalArgumentException("body cap must be configured");
        }
        if (redirectCap <= 0) {
            throw new IllegalArgumentException("redirect cap must be configured");
        }
        this.blocklist = blocklist;
        this.requestTimeout = requestTimeout;
        this.bodyCap = bodyCap;
        this.redirectCap = redirectCap;
        // Redirect.NEVER: we handle redirects manually so each hop
        // re-runs the scheme / userinfo / DNS pipeline. NORMAL would
        // follow without re-resolving DNS, leaving a rebind window
        // the spec explicitly closes.
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    /**
     * Issue a GET against {@code uri}, returning the response with
     * the body materialized as a bounded byte array. Throws
     * {@link SsrfPolicyException} on any policy violation; the
     * underlying JDK contract for {@link HttpClient#send} otherwise
     * applies (checked {@link IOException} on transport failure,
     * {@link InterruptedException} on thread interrupt).
     */
    public HttpResponse<byte[]> get(URI uri) throws IOException, InterruptedException {
        URI current = uri;
        int redirectCount = 0;
        while (true) {
            validate(current);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(current)
                .timeout(requestTimeout)
                .header("Accept", ACCEPT_HEADER)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
            HttpResponse<InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                try (InputStream discard = response.body()) {
                    // Drain via close(); redirect bodies carry no
                    // payload we need.
                }
                redirectCount++;
                if (redirectCount > redirectCap) {
                    throw new SsrfPolicyException("redirect cap exceeded");
                }
                String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new SsrfPolicyException(
                        "redirect response missing Location header"));
                current = current.resolve(location);
                continue;
            }

            byte[] body = readBounded(response);
            return new BoundedByteArrayResponse(response, body);
        }
    }

    private void validate(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new SsrfPolicyException("scheme not allowed: " + scheme);
        }
        if (uri.getRawUserInfo() != null) {
            throw new SsrfPolicyException("userinfo segment not allowed");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new SsrfPolicyException("host missing from URI");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new SsrfPolicyException("unknown host: " + host, e);
        }
        for (InetAddress addr : addresses) {
            if (blocklist.isBlocked(addr)) {
                throw new SsrfPolicyException("blocked IP: " + addr.getHostAddress());
            }
        }
    }

    private byte[] readBounded(HttpResponse<InputStream> response) throws IOException {
        try (InputStream in = response.body();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > bodyCap) {
                    // Closing the InputStream via try-with-resources
                    // cancels the underlying socket so the hostile
                    // peer cannot continue streaming.
                    throw new SsrfPolicyException(
                        "response body exceeded " + bodyCap + " bytes");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /**
     * Raised on any policy violation in the wrapper pipeline:
     * disallowed scheme, userinfo in URI, blocked IP, oversize
     * body, exceeded redirect cap. Unchecked because the
     * {@code Fetcher} SPI signature ({@code fetch(long, String)}
     * returning {@code List<NormalizedPost>}) does not declare
     * checked exceptions, so a checked policy exception would
     * force an SPI change that is out of scope here.
     */
    public static final class SsrfPolicyException extends RuntimeException {

        public SsrfPolicyException(String message) {
            super(message);
        }

        public SsrfPolicyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Adapter from the {@code HttpResponse<InputStream>} the
     * wrapper drives internally to the
     * {@code HttpResponse<byte[]>} the wrapper's public surface
     * promises. Delegates every method except {@link #body()} to
     * the underlying response.
     */
    private static final class BoundedByteArrayResponse implements HttpResponse<byte[]> {

        private final HttpResponse<InputStream> delegate;

        private final byte[] body;

        BoundedByteArrayResponse(HttpResponse<InputStream> delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return delegate.statusCode();
        }

        @Override
        public HttpRequest request() {
            return delegate.request();
        }

        @Override
        public Optional<HttpResponse<byte[]>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return delegate.headers();
        }

        @Override
        public byte[] body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return delegate.sslSession();
        }

        @Override
        public URI uri() {
            return delegate.uri();
        }

        @Override
        public HttpClient.Version version() {
            return delegate.version();
        }
    }
}
