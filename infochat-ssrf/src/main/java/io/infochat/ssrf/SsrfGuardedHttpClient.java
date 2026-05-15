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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

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
 *   <li><strong>DNS resolution</strong> — the resolver seam returns
 *       the candidate {@link InetAddress} set; every entry is
 *       checked against {@link IpBlocklist}; ANY blocked address
 *       rejects the request.</li>
 *   <li><strong>DNS pinning (M1-025, Finding 2 remediation)</strong>
 *       — the IP set just validated is installed in the JVM-wide
 *       {@link PinnedDnsResolver.Provider}'s pin slot for the
 *       hostname, so the subsequent JDK {@code HttpClient.send}
 *       resolves the host to those same IPs. The wrapper holds the
 *       provider's {@link ReentrantLock} for the duration of the
 *       call, serializing concurrent wrapper invocations JVM-wide.
 *       This closes the within-hop DNS-rebind window the M1-024
 *       redteam flagged: validate-time and connect-time DNS results
 *       are now provably identical.</li>
 *   <li><strong>HTTP send</strong> via a per-call
 *       {@link HttpClient} (no connection pool reuse across calls,
 *       so cached connections cannot leak DNS state) with non-zero
 *       connect + request timeouts, {@code Redirect.NEVER}, and a
 *       per-read length- AND wall-clock-bounded body reader
 *       (default 10 MiB + 30 s per read).</li>
 *   <li><strong>Redirect handling</strong> — on 3xx the wrapper
 *       parses the {@code Location} header, increments a per-call
 *       counter, and re-enters the pipeline from step 1, REPLACING
 *       the pin slot (still under the same lock).</li>
 * </ol>
 *
 * <p>Production callers always use the no-arg constructor. The
 * parameterized constructor is the test-mode seam plus a knob
 * surface for future per-source policy. The package-private
 * resolver-seam constructor lets tests substitute the validation-
 * time DNS resolver (separate from the JVM-wide pinning resolver).
 *
 * <p>This class is NOT itself thread-safe for the pinning surface
 * — the static pin slot is JVM-wide. Multiple wrapper instances can
 * coexist, but concurrent {@link #get(URI)} calls (across instances
 * or threads) serialize on the {@link PinnedDnsResolver.Provider}
 * lock.
 */
public final class SsrfGuardedHttpClient {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

    // 10 MiB — large enough for the longest RSS / Atom feeds observed
    // in the wild, small enough to bound the worst-case allocation
    // against a hostile feed serving an unbounded payload.
    private static final long DEFAULT_BODY_CAP = 10L * 1024 * 1024;

    private static final int DEFAULT_REDIRECT_CAP = 3;

    private static final String USER_AGENT = "infochat/0.0.1-SNAPSHOT";

    private static final String ACCEPT_HEADER = "*/*";

    private final IpBlocklist blocklist;

    private final Duration connectTimeout;

    private final Duration requestTimeout;

    private final Duration readTimeout;

    private final long bodyCap;

    private final int redirectCap;

    private final Function<String, List<InetAddress>> resolverSeam;

    /**
     * Production constructor. Wires the strict {@link IpBlocklist},
     * profile-driven default timeouts / caps, and
     * {@link InetAddress#getAllByName} as the validation resolver.
     */
    public SsrfGuardedHttpClient() {
        this(new IpBlocklist(),
             DEFAULT_CONNECT_TIMEOUT,
             DEFAULT_REQUEST_TIMEOUT,
             DEFAULT_READ_TIMEOUT,
             DEFAULT_BODY_CAP,
             DEFAULT_REDIRECT_CAP);
    }

    /**
     * M1-024 parameterized constructor — preserved as a stable
     * public API surface for cross-module test fixtures
     * (notably {@code infochat-collector}'s {@code RssFetcherTest})
     * that constructed the wrapper before M1-025 introduced
     * {@code readTimeout}. Supplies the default read timeout so
     * existing M1-024 call sites continue to compile and behave
     * identically. New M1-025 call sites that need to customize
     * the read-timeout use the 6-arg form below.
     */
    public SsrfGuardedHttpClient(IpBlocklist blocklist,
                                 Duration connectTimeout,
                                 Duration requestTimeout,
                                 long bodyCap,
                                 int redirectCap) {
        this(blocklist, connectTimeout, requestTimeout,
             DEFAULT_READ_TIMEOUT, bodyCap, redirectCap,
             defaultResolverSeam());
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
                                 Duration readTimeout,
                                 long bodyCap,
                                 int redirectCap) {
        this(blocklist, connectTimeout, requestTimeout, readTimeout,
             bodyCap, redirectCap, defaultResolverSeam());
    }

    /**
     * Package-private resolver-seam constructor. Lets tests replace
     * the validation-time DNS lookup with a deterministic
     * {@link Function} without installing a JVM-global resolver
     * provider — the seam covers the wrapper's own resolve-and-
     * validate step. The JVM-wide pinning resolver remains in
     * effect and forces the JDK {@link HttpClient}'s actual
     * connect-time DNS to match what the seam returned.
     */
    SsrfGuardedHttpClient(IpBlocklist blocklist,
                          Duration connectTimeout,
                          Duration requestTimeout,
                          Duration readTimeout,
                          long bodyCap,
                          int redirectCap,
                          Function<String, List<InetAddress>> resolverSeam) {
        if (blocklist == null) {
            throw new IllegalArgumentException("blocklist must be configured");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be configured");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be configured");
        }
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("read timeout must be configured");
        }
        if (bodyCap <= 0) {
            throw new IllegalArgumentException("body cap must be configured");
        }
        if (redirectCap <= 0) {
            throw new IllegalArgumentException("redirect cap must be configured");
        }
        if (resolverSeam == null) {
            throw new IllegalArgumentException("resolver seam must be configured");
        }
        this.blocklist = blocklist;
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.readTimeout = readTimeout;
        this.bodyCap = bodyCap;
        this.redirectCap = redirectCap;
        this.resolverSeam = resolverSeam;
    }

    private static Function<String, List<InetAddress>> defaultResolverSeam() {
        return host -> {
            try {
                return Arrays.asList(InetAddress.getAllByName(host));
            } catch (UnknownHostException e) {
                throw new SsrfPolicyException("unknown host: " + host, e);
            }
        };
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
        ReentrantLock lock = PinnedDnsResolver.Provider.lock();
        lock.lock();
        try {
            while (true) {
                List<InetAddress> addresses = resolveAndValidate(current);

                // Install the per-hop pin BEFORE constructing the
                // per-call HttpClient. The JVM-wide resolver consults
                // the pin slot on every lookup, so the connect's DNS
                // lookup will return our pinned IPs.
                PinnedDnsResolver.Provider.installPins(
                    Map.of(current.getHost(), addresses));

                // Redirect.NEVER — we handle redirects manually so each
                // hop re-runs scheme / userinfo / DNS pipeline AND
                // updates the pin slot.
                HttpClient perCallClient = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(current)
                    .timeout(requestTimeout)
                    .header("Accept", ACCEPT_HEADER)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
                HttpResponse<InputStream> response =
                    perCallClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

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
        } finally {
            PinnedDnsResolver.Provider.clearPins();
            lock.unlock();
        }
    }

    private List<InetAddress> resolveAndValidate(URI uri) {
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
        List<InetAddress> addresses = resolverSeam.apply(host);
        if (addresses == null || addresses.isEmpty()) {
            throw new SsrfPolicyException("unknown host: " + host);
        }
        for (InetAddress addr : addresses) {
            if (blocklist.isBlocked(addr)) {
                throw new SsrfPolicyException("blocked IP: " + addr.getHostAddress());
            }
        }
        return addresses;
    }

    private byte[] readBounded(HttpResponse<InputStream> response) throws IOException {
        // Per-read wall-clock watchdog (M1-025, Finding 4 remediation).
        // The JDK's HttpRequest.timeout() bounds only the receipt of
        // response HEADERS; without this watchdog a malicious upstream
        // can dribble body bytes one per minute and hold a fetcher
        // thread for hours. We run each in.read(buf) call on a
        // dedicated single-thread executor and supervise it from the
        // caller thread; on timeout we cancel the future (interrupting
        // the read) and raise SsrfPolicyException.
        ExecutorService readerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ssrf-body-reader");
            t.setDaemon(true);
            return t;
        });
        try (InputStream in = response.body();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            long total = 0;
            while (true) {
                Future<Integer> readFuture = readerExecutor.submit(() -> in.read(buf));
                int n;
                try {
                    n = readFuture.get(readTimeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    readFuture.cancel(true);
                    throw new SsrfPolicyException(
                        "body read timeout after " + readTimeout.toMillis() + "ms");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException io) {
                        throw io;
                    }
                    if (cause instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new IOException("body read failed", cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    readFuture.cancel(true);
                    throw new IOException("interrupted during body read", e);
                }
                if (n == -1) {
                    break;
                }
                total += n;
                if (total > bodyCap) {
                    throw new SsrfPolicyException(
                        "response body exceeded " + bodyCap + " bytes");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            readerExecutor.shutdownNow();
        }
    }

    /**
     * Raised on any policy violation in the wrapper pipeline:
     * disallowed scheme, userinfo in URI, blocked IP, oversize
     * body, exceeded redirect cap, body-read timeout. Unchecked
     * because the {@code Fetcher} SPI signature
     * ({@code fetch(long, String)} returning
     * {@code List<NormalizedPost>}) does not declare checked
     * exceptions, so a checked policy exception would force an SPI
     * change that is out of scope here.
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
