package app.zcat.infochat.ssrf;

import org.jspecify.annotations.NonNull;

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
import java.util.Set;
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

    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

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

    // Scheme allowlists for the two transport entrypoints. HTTP_SCHEMES
    // gates {@link #get}; WEBSOCKET_SCHEMES gates {@link #checkAndPinForWebSocket}
    // and {@link #resolveForWebSocket}. The two surfaces deliberately
    // do not overlap — the JDK's {@code HttpClient.send} cannot dial
    // ws/wss, and {@code WebSocket.Builder.buildAsync} cannot dial
    // http/https, so a misrouted scheme is a programming error rather
    // than a policy choice. The IP-blocklist + DNS-pinning pipeline is
    // identical regardless of which transport runs after the check.
    private static final Set<String> HTTP_SCHEMES = Set.of("http", "https");

    private static final Set<String> WEBSOCKET_SCHEMES = Set.of("ws", "wss");

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
     * Resolver-seam constructor. Lets callers (typically tests in
     * sibling modules — the original M1-025 unit tests, M1-101's
     * {@code NostrSsrfTest} and {@code NostrSsrfIT}) replace the
     * validation-time DNS lookup with a deterministic
     * {@link Function} without installing a JVM-global resolver
     * provider. Production callers use the no-arg or seven-arg
     * constructors which wire the real JDK resolver.
     */
    public SsrfGuardedHttpClient(IpBlocklist blocklist,
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
     * Issue a GET against {@code uri} with no extra headers.
     * Delegates to {@link #get(URI, Map)} with an empty header map;
     * see that method for the full SSRF guard pipeline description.
     */
    public HttpResponse<byte[]> get(@NonNull URI uri) throws IOException, InterruptedException {
        return get(uri, Map.of());
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
    public HttpResponse<byte[]> get(@NonNull URI uri, @NonNull Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> terminalResponse;
        ReentrantLock lock = PinnedDnsResolver.Provider.lock();
        lock.lock();
        try {
            URI current = uri;
            int redirectCount = 0;
            while (true) {
                ResolvedHost resolved = resolveAndValidate(current, HTTP_SCHEMES);
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

    private ResolvedHost resolveAndValidate(URI uri, Set<String> allowedSchemes) {
        String scheme = uri.getScheme();
        if (scheme == null || !allowedSchemes.contains(scheme)) {
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
     * Validate {@code uri} against the WebSocket SSRF policy (scheme
     * allowlist {@code {ws, wss}} + userinfo gate + host
     * canonicalization + DNS resolution + {@link IpBlocklist} check),
     * install the resolved IPs in the JVM-wide
     * {@link PinnedDnsResolver.Provider} pin slot under the same
     * cross-call lock that {@link #get(URI)} uses, and return a
     * {@link PinnedDial} handle whose {@link PinnedDial#close()}
     * releases the pin and the lock.
     *
     * <p>The caller dials the WebSocket (e.g. {@code
     * HttpClient.newWebSocketBuilder().buildAsync(uri, listener)}) and
     * waits for the connection to be established (the JDK
     * {@code WebSocket} handshake routes its DNS lookup through the
     * pinned resolver, so the TCP connection lands on a validated IP)
     * INSIDE the try-with-resources block, then exits the block so the
     * pin and lock are released for the next dial. The connection
     * itself survives close-of-the-PinnedDial; only the JVM-wide
     * pin/lock state is wound down.
     *
     * <p>The blocking semantics match {@link #get(URI)}: concurrent
     * SSRF-checked dials (HTTP and WebSocket) serialize on the same
     * {@link PinnedDnsResolver.Provider} lock during their
     * connection-establishment phase. The WebSocket post-handshake
     * data path then runs lock-released, like {@link #get}'s body
     * read.
     */
    public @NonNull PinnedDial checkAndPinForWebSocket(@NonNull URI uri) {
        ReentrantLock lock = PinnedDnsResolver.Provider.lock();
        lock.lock();
        try {
            ResolvedHost resolved = resolveAndValidate(uri, WEBSOCKET_SCHEMES);
            PinnedDnsResolver.Provider.installPins(
                Map.of(resolved.canonicalHost(), resolved.addresses()));
            return new PinnedDial(lock, resolved.addresses());
        } catch (RuntimeException | Error e) {
            // Release the lock if the validation or pin install threw —
            // a thrown checkAndPinForWebSocket must not leave the
            // JVM-wide lock held.
            lock.unlock();
            throw e;
        }
    }

    /**
     * Run the WebSocket SSRF check {@link #checkAndPinForWebSocket}
     * runs, BUT without pinning and without taking the JVM-wide lock.
     * Returns the validated address set. Callers use this for
     * mid-session peer-IP-change detection on already-established
     * WebSocket connections (per {@code security.md} §SSRF: "any
     * peer-IP change observed at the socket layer is a hard close"):
     * after the connection is up, periodically re-resolve and compare
     * the returned set against the addresses captured at connect time
     * (via {@link PinnedDial#addresses()}); divergence is the signal
     * to {@code WebSocket.abort()}.
     *
     * <p>Raises {@link SsrfPolicyException} on any policy violation
     * (scheme not in {@code {ws, wss}}, userinfo present, blocked IP,
     * invalid host). A thrown re-resolve is itself a peer-IP-change
     * signal — the caller should hard-close the connection.
     */
    public @NonNull List<InetAddress> resolveForWebSocket(@NonNull URI uri) {
        return resolveAndValidate(uri, WEBSOCKET_SCHEMES).addresses();
    }

    /**
     * Validation result: the canonical host form (used as the pin
     * map key) and the resolved + blocklist-passing IP set.
     */
    private record ResolvedHost(String canonicalHost, List<InetAddress> addresses) {}

    /**
     * Handle returned by {@link #checkAndPinForWebSocket(URI)}. Holds
     * the JVM-wide {@link PinnedDnsResolver.Provider} lock and the
     * installed pin until {@link #close()} runs, so the WebSocket
     * dial executed inside a try-with-resources block sees the
     * validated IPs from {@link PinnedDnsResolver}'s SPI lookups.
     * Carries the validated address set for the caller's
     * peer-IP-change watcher (compare against
     * {@link SsrfGuardedHttpClient#resolveForWebSocket(URI)} on
     * subsequent ticks).
     *
     * <p>Single-shot: {@link #close()} clears the pin and releases
     * the lock exactly once. Re-{@code close()} would unlock an
     * already-released {@link ReentrantLock} and throw; standard
     * try-with-resources usage does not re-close.
     */
    public static final class PinnedDial implements AutoCloseable {

        private final ReentrantLock lock;

        private final List<InetAddress> addresses;

        PinnedDial(ReentrantLock lock, List<InetAddress> addresses) {
            this.lock = lock;
            this.addresses = List.copyOf(addresses);
        }

        /**
         * The validated IP set that {@link PinnedDnsResolver.Provider}
         * is currently serving for the host. Stable across the life
         * of this handle.
         */
        public @NonNull List<InetAddress> addresses() {
            return addresses;
        }

        @Override
        public void close() {
            PinnedDnsResolver.Provider.clearPins();
            lock.unlock();
        }
    }

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
