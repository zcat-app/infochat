package io.infochat.ssrf;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
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
import java.util.Locale;
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
 *       {@code user[:password]@} is rejected before the dial.</li>
 *   <li><strong>Host canonicalization (M1-026, Finding 2
 *       remediation)</strong> — every host (initial + each redirect
 *       hop) is canonicalized via {@link #canonicalizeHost(String)}:
 *       {@code IDN.toASCII} → {@code toLowerCase(Locale.ROOT)} → a
 *       single trailing-dot strip. The pin map is always keyed by
 *       the canonical form so a JDK normalization mismatch
 *       (case-fold, IDN ↔ punycode, trailing-dot strip) cannot
 *       cause the pin to miss.</li>
 *   <li><strong>DNS resolution</strong> — the resolver seam returns
 *       the candidate {@link InetAddress} set for the canonical
 *       host; every entry is checked against {@link IpBlocklist};
 *       ANY blocked address rejects the request.</li>
 *   <li><strong>DNS pinning</strong> — the IP set just validated is
 *       installed in the JVM-wide {@link PinnedDnsResolver.Provider}
 *       pin slot, keyed by the canonical host, so the subsequent
 *       JDK {@code HttpClient.send} resolves the host to those same
 *       IPs. The wrapper holds the provider's {@link ReentrantLock}
 *       across the redirect loop + the terminal hop's
 *       {@code httpClient.send} (which returns when headers are
 *       received) and RELEASES the lock BEFORE the body-read phase
 *       (M1-026 Finding 1 lock-starvation arm). The JDK does not
 *       re-resolve DNS during body read on an already-established
 *       connection, so releasing the lock there is safe.</li>
 *   <li><strong>HTTP send</strong> via a per-call
 *       {@link HttpClient} with non-zero connect + request timeouts,
 *       {@code Redirect.NEVER}, a per-read length- AND wall-clock-
 *       bounded body reader, AND a total wall-clock
 *       {@code bodyReadDeadline} (M1-026 Finding 1 drip-body-read
 *       remediation).</li>
 *   <li><strong>Redirect handling</strong> — on 3xx the wrapper
 *       parses the {@code Location} header, increments a per-call
 *       counter, and re-enters the pipeline from step 1, REPLACING
 *       the pin slot (still under the same lock).</li>
 * </ol>
 *
 * <p>This class is NOT itself thread-safe for the pinning surface
 * — the static pin slot is JVM-wide. Concurrent {@link #get(URI)}
 * calls serialize on the {@link PinnedDnsResolver.Provider} lock
 * for the connection-establishment phase. The body-read phase runs
 * unlocked, so concurrent fetches to different hosts can interleave
 * their body reads.
 */
public final class SsrfGuardedHttpClient {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

    // 2 minutes — large enough for a well-behaved feed to deliver a
    // full 10 MiB body over a slow but legitimate link; small enough
    // to bound the worst-case attacker-controlled body-read phase.
    // Without a total deadline, a drip attacker delivering one byte
    // every (readTimeout - epsilon) bypasses the per-read watchdog
    // and can hold the call open for ~9.6 years at default settings.
    private static final Duration DEFAULT_BODY_READ_DEADLINE = Duration.ofMinutes(2);

    private static final long DEFAULT_BODY_CAP = 10L * 1024 * 1024;

    private static final int DEFAULT_REDIRECT_CAP = 3;

    private static final String USER_AGENT = "infochat/0.0.1-SNAPSHOT";

    private static final String ACCEPT_HEADER = "*/*";

    private final IpBlocklist blocklist;

    private final Duration connectTimeout;

    private final Duration requestTimeout;

    private final Duration readTimeout;

    private final Duration bodyReadDeadline;

    private final long bodyCap;

    private final int redirectCap;

    private final Function<String, List<InetAddress>> resolverSeam;

    public SsrfGuardedHttpClient() {
        this(new IpBlocklist(),
             DEFAULT_CONNECT_TIMEOUT,
             DEFAULT_REQUEST_TIMEOUT,
             DEFAULT_READ_TIMEOUT,
             DEFAULT_BODY_READ_DEADLINE,
             DEFAULT_BODY_CAP,
             DEFAULT_REDIRECT_CAP);
    }

    /**
     * M1-024 parameterized constructor — preserved as a stable public
     * API surface. Supplies default read-timeout AND default
     * body-read-deadline so existing M1-024 call sites continue to
     * compile and behave identically.
     */
    public SsrfGuardedHttpClient(IpBlocklist blocklist,
                                 Duration connectTimeout,
                                 Duration requestTimeout,
                                 long bodyCap,
                                 int redirectCap) {
        this(blocklist, connectTimeout, requestTimeout,
             DEFAULT_READ_TIMEOUT, DEFAULT_BODY_READ_DEADLINE,
             bodyCap, redirectCap);
    }

    /**
     * M1-025 parameterized constructor — preserved as a stable public
     * API surface. Supplies a default body-read-deadline so existing
     * M1-025 call sites continue to compile and behave identically.
     */
    public SsrfGuardedHttpClient(IpBlocklist blocklist,
                                 Duration connectTimeout,
                                 Duration requestTimeout,
                                 Duration readTimeout,
                                 long bodyCap,
                                 int redirectCap) {
        this(blocklist, connectTimeout, requestTimeout, readTimeout,
             DEFAULT_BODY_READ_DEADLINE, bodyCap, redirectCap);
    }

    /**
     * M1-026 parameterized constructor — exposes the new
     * {@code bodyReadDeadline} knob alongside the M1-025 read
     * timeout. {@code readTimeout} bounds each individual
     * {@code in.read()} call (covers the slow-loris vector);
     * {@code bodyReadDeadline} bounds the TOTAL wall-clock time of
     * the body-read phase (covers the drip vector that passes
     * per-read but accumulates).
     */
    public SsrfGuardedHttpClient(IpBlocklist blocklist,
                                 Duration connectTimeout,
                                 Duration requestTimeout,
                                 Duration readTimeout,
                                 Duration bodyReadDeadline,
                                 long bodyCap,
                                 int redirectCap) {
        this(blocklist, connectTimeout, requestTimeout, readTimeout,
             bodyReadDeadline, bodyCap, redirectCap,
             defaultResolverSeam());
    }

    /**
     * Package-private resolver-seam constructor. Lets tests replace
     * the validation-time DNS lookup with a deterministic
     * {@link Function} without installing a JVM-global resolver
     * provider.
     */
    SsrfGuardedHttpClient(IpBlocklist blocklist,
                          Duration connectTimeout,
                          Duration requestTimeout,
                          Duration readTimeout,
                          Duration bodyReadDeadline,
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
        if (bodyReadDeadline == null || bodyReadDeadline.isZero() || bodyReadDeadline.isNegative()) {
            throw new IllegalArgumentException("body read deadline must be configured");
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
        this.bodyReadDeadline = bodyReadDeadline;
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
     * Canonicalize a hostname for stable equality across the JDK
     * resolver SPI. M1-025's pin map was keyed by raw
     * {@code URI.getHost()}; the JDK's {@code HttpClient.send} may
     * pass a normalized form (case-fold, trailing-dot strip,
     * IDN ↔ punycode) to the resolver SPI, causing
     * {@code pins.get(host)} to miss and the resolver to fall
     * through to the unpinned builtin — defeating the rebind
     * defense. Both the install side ({@link #get(URI)}) and the
     * lookup side ({@link PinnedDnsResolver#lookupByName}) MUST call
     * this helper so the pin keys match regardless of JDK
     * transformation choices.
     *
     * <p>Transformations applied, in order:
     * <ol>
     *   <li>Reject null or blank input with
     *       {@link IllegalArgumentException}.</li>
     *   <li>{@code IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)} — convert
     *       Unicode / punycode to canonical ASCII Compatible Encoding.
     *       Applied first because lowercase-then-toASCII can mis-handle
     *       case-sensitive punycode constructs.</li>
     *   <li>{@code toLowerCase(Locale.ROOT)} — case-fold without the
     *       Turkish-dotless-i hazard of the default-locale form.</li>
     *   <li>Strip a single trailing {@code .} if present — the FQDN
     *       trailing-dot variant; some HTTP clients and DNS resolvers
     *       preserve the dot, others strip it, so pinning against the
     *       dot-less form makes the pin key stable.</li>
     * </ol>
     *
     * <p>Package-private so {@link PinnedDnsResolver} (same package)
     * can call it directly. A sibling utility class would push the
     * implementation to 6 files and breach the M1-026 5-file
     * {@code files_budget}.
     */
    static String canonicalizeHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be null or blank");
        }
        String ascii = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
        String lower = ascii.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".")) {
            return lower.substring(0, lower.length() - 1);
        }
        return lower;
    }

    /**
     * Issue a GET against {@code uri}. Throws
     * {@link SsrfPolicyException} on any policy violation.
     *
     * <p>The JVM-wide {@link PinnedDnsResolver.Provider} lock is held
     * across the redirect loop + the terminal hop's
     * {@code httpClient.send}. It is RELEASED before the body-read
     * phase — concurrent fetches to different hosts can interleave
     * body reads without serializing on the JVM-wide lock (M1-026
     * Finding 1 lock-starvation remediation).
     */
    public HttpResponse<byte[]> get(URI uri) throws IOException, InterruptedException {
        HttpResponse<InputStream> terminalResponse;
        ReentrantLock lock = PinnedDnsResolver.Provider.lock();
        lock.lock();
        try {
            URI current = uri;
            int redirectCount = 0;
            while (true) {
                ResolvedHost resolved = resolveAndValidate(current);
                // Pin the per-hop canonical host -> validated IPs map
                // BEFORE constructing the per-call HttpClient. The
                // JVM-wide resolver consults the pin slot on every
                // lookup, so the connect's DNS will return our IPs.
                PinnedDnsResolver.Provider.installPins(
                    Map.of(resolved.canonicalHost(), resolved.addresses()));

                // Redirect.NEVER — we handle redirects manually so
                // each hop re-runs the pipeline AND updates the pin.
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
                        // Drain via close(); redirect bodies carry
                        // no payload we need.
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

                terminalResponse = response;
                break;
            }
        } finally {
            PinnedDnsResolver.Provider.clearPins();
            lock.unlock();
        }

        // Lock released; pins cleared. JDK does not re-resolve DNS
        // during body read on an already-established connection, so
        // releasing here is safe — and crucial for F1: concurrent
        // get(uri) calls to different hosts can interleave body
        // reads instead of serializing on the JVM-wide lock for the
        // entire (drip-attacker-controllable) body-read phase.
        byte[] body = readBounded(terminalResponse);
        return new BoundedByteArrayResponse(terminalResponse, body);
    }

    /**
     * Issue a GET against {@code uri} with caller-supplied per-request
     * headers attached to each hop in addition to the default
     * {@code Accept} / {@code User-Agent}. The full SSRF guard
     * pipeline runs identically to {@link #get(URI)} — scheme
     * allowlist, userinfo gate, host canonicalization, DNS
     * resolution + {@link IpBlocklist} check, DNS pinning, redirect
     * loop with per-hop re-validation, lock-released body read with
     * the same bounds. The extra headers are attached on every
     * redirect hop (matching how the default {@code Accept} /
     * {@code User-Agent} propagate).
     *
     * <p>The JDK's {@link HttpRequest.Builder#header(String, String)}
     * is additive: passing {@code Map.of("Accept", "text/xml")} adds
     * a second {@code Accept} header rather than replacing the
     * default. This overload exists for header INJECTION (the
     * primary caller is the Provider's URL probe injecting
     * {@code Range: bytes=0-0} to avoid downloading a full feed
     * body), not for header overrides. {@link SsrfPolicyException}
     * is raised on any policy violation, matching {@link #get(URI)}.
     */
    public HttpResponse<byte[]> get(URI uri, Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> terminalResponse;
        ReentrantLock lock = PinnedDnsResolver.Provider.lock();
        lock.lock();
        try {
            URI current = uri;
            int redirectCount = 0;
            while (true) {
                ResolvedHost resolved = resolveAndValidate(current);
                PinnedDnsResolver.Provider.installPins(
                    Map.of(resolved.canonicalHost(), resolved.addresses()));

                HttpClient perCallClient = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(current)
                    .timeout(requestTimeout)
                    .header("Accept", ACCEPT_HEADER)
                    .header("User-Agent", USER_AGENT)
                    .GET();
                extraHeaders.forEach(reqBuilder::header);
                HttpRequest request = reqBuilder.build();
                HttpResponse<InputStream> response =
                    perCallClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    try (InputStream discard = response.body()) {
                        // Drain via close(); redirect bodies carry
                        // no payload we need.
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

                terminalResponse = response;
                break;
            }
        } finally {
            PinnedDnsResolver.Provider.clearPins();
            lock.unlock();
        }

        byte[] body = readBounded(terminalResponse);
        return new BoundedByteArrayResponse(terminalResponse, body);
    }

    private ResolvedHost resolveAndValidate(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new SsrfPolicyException("scheme not allowed: " + scheme);
        }
        if (uri.getRawUserInfo() != null) {
            throw new SsrfPolicyException("userinfo segment not allowed");
        }
        String rawHost = uri.getHost();
        if (rawHost == null) {
            throw new SsrfPolicyException("host missing from URI");
        }
        // M1-026 Finding 2: canonicalize before BOTH the seam call
        // AND the pin install. The same helper is invoked on the
        // lookup side by PinnedDnsResolver.lookupByName, so the pin
        // matches regardless of JDK normalization choices.
        // IDN.toASCII throws IllegalArgumentException on invalid
        // input; wrap as SsrfPolicyException so the wrapper boundary
        // surfaces a clean policy exception (no JDK internals leak).
        String canonicalHost;
        try {
            canonicalHost = canonicalizeHost(rawHost);
        } catch (IllegalArgumentException e) {
            throw new SsrfPolicyException("invalid host: " + rawHost, e);
        }
        List<InetAddress> addresses = resolverSeam.apply(canonicalHost);
        if (addresses == null || addresses.isEmpty()) {
            throw new SsrfPolicyException("unknown host: " + canonicalHost);
        }
        for (InetAddress addr : addresses) {
            if (blocklist.isBlocked(addr)) {
                throw new SsrfPolicyException("blocked IP: " + addr.getHostAddress());
            }
        }
        return new ResolvedHost(canonicalHost, addresses);
    }

    private byte[] readBounded(HttpResponse<InputStream> response) throws IOException {
        // Per-read wall-clock watchdog (M1-025, Finding 4): the JDK's
        // HttpRequest.timeout() bounds only the receipt of response
        // HEADERS; without this watchdog a malicious upstream can
        // dribble body bytes one per minute and hold a fetcher thread
        // for hours. We run each in.read(buf) call on a single-thread
        // executor and supervise from the caller thread; on timeout
        // we cancel the future and raise SsrfPolicyException.
        //
        // M1-026 Finding 1: in addition to the per-read watchdog,
        // the TOTAL wall-clock time from the start of body-read is
        // bounded by bodyReadDeadline. A drip attacker that returns
        // 1 byte per (readTimeout - epsilon) defeats the per-read
        // watchdog (each individual read completes well under the
        // window) but cannot defeat a total-elapsed deadline.
        ExecutorService readerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ssrf-body-reader");
            t.setDaemon(true);
            return t;
        });
        long bodyReadStartNanos = System.nanoTime();
        try (InputStream in = response.body();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            long total = 0;
            while (true) {
                long elapsedNanos = System.nanoTime() - bodyReadStartNanos;
                if (elapsedNanos > bodyReadDeadline.toNanos()) {
                    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
                    throw new SsrfPolicyException(
                        "body read deadline exceeded after " + elapsedMs + "ms");
                }
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
     * Validation result: the canonical host form (used as the pin
     * map key) and the resolved + blocklist-passing IP set.
     */
    private record ResolvedHost(String canonicalHost, List<InetAddress> addresses) {}

    /**
     * Raised on any policy violation in the wrapper pipeline:
     * disallowed scheme, userinfo in URI, blocked IP, oversize
     * body, exceeded redirect cap, body-read timeout, body-read
     * deadline exceeded.
     */
    public static final class SsrfPolicyException extends RuntimeException {

        public SsrfPolicyException(String message) {
            super(message);
        }

        public SsrfPolicyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

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
