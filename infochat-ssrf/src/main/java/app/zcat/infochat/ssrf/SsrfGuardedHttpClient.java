package app.zcat.infochat.ssrf;


import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Fail-closed outbound HTTP gate for Collector and Provider per
 * {@code docs/spec/security.md} §SSRF and outbound connections
 * (decision D20). Every {@link #get(URI)} call runs a per-hop policy
 * pipeline:
 *
 * <ol>
 *   <li><strong>Scheme allowlist</strong> — {@link #get(URI)} dials
 *       only {@code http} and {@code https}. {@code ws} and
 *       {@code wss} run the same policy pipeline through the
 *       dedicated WebSocket entrypoints
 *       {@link #checkAndPinForWebSocket(URI)} and
 *       {@link #resolveForWebSocket(URI)}, which validate and pin
 *       but leave the dial to the caller.</li>
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
 *       pinned in the JVM-wide {@link PinnedDnsResolver.Provider}
 *       per-host pin map, keyed by the canonical host, so the
 *       subsequent JDK {@code HttpClient.send} resolves the host to
 *       those same IPs. The wrapper holds its pin across the
 *       redirect loop + the terminal hop's {@code httpClient.send}
 *       (which returns when headers are received) and RELEASES it
 *       BEFORE the body-read phase (M1-026 Finding 1). The JDK does
 *       not re-resolve DNS during body read on an already-established
 *       connection, so a pin held there would only be stale
 *       state.</li>
 *   <li><strong>HTTP send</strong> via the wrapper's shared
 *       {@link HttpClient} (one per wrapper instance — see the
 *       {@code httpClient} field for the lifecycle rationale) with
 *       non-zero connect + request timeouts, {@code Redirect.NEVER},
 *       a per-read length- AND wall-clock-bounded body reader, AND a
 *       total wall-clock {@code bodyReadDeadline} (M1-026 Finding 1
 *       drip-body-read remediation).</li>
 *   <li><strong>Redirect handling</strong> — on 3xx the wrapper
 *       parses the {@code Location} header, increments a per-call
 *       counter, and re-enters the pipeline from step 1,
 *       release-then-pin: the prior hop's pin is released and the
 *       freshly re-validated target pinned, so each hop's
 *       re-resolution REPLACES the previous one (the TOCTOU defense
 *       re-applies per hop).</li>
 * </ol>
 *
 * <p>Thread-safe: the JVM-wide pin map is per-host and refcounted,
 * so concurrent {@link #get(URI)} calls and WebSocket dials proceed
 * independently — a slow connect to one host does not stall fetches
 * to any other host, and overlapping pins of the SAME host stack
 * rather than disturb each other.
 */
public final class SsrfGuardedHttpClient {

    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

    // 2 minutes — large enough for a well-behaved feed to deliver a
    // full 5 MiB body over a slow but legitimate link; small enough
    // to bound the worst-case attacker-controlled body-read phase.
    // Without a total deadline, a drip attacker delivering one byte
    // every (readTimeout - epsilon) bypasses the per-read watchdog
    // and can hold the call open for ~9.6 years at default settings.
    private static final Duration DEFAULT_BODY_READ_DEADLINE = Duration.ofMinutes(2);

    // 5 MiB — the canonical outbound body cap, kept in lockstep with
    // docs/design/04-security.md §"Body size cap" (the
    // infochat.fetch.max-body-bytes default). Every no-arg consumer
    // inherits exactly this value via the no-arg constructor; code and
    // design state the same number so the documented exposure matches
    // the enforced one. Package-private so the test can pin the value.
    static final long DEFAULT_BODY_CAP = 5L * 1024 * 1024;

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

    private static final String USER_AGENT = "infochat/1.0.0-SNAPSHOT";

    private static final String ACCEPT_HEADER = "*/*";

    // One virtual thread per body read (B-READBOUNDED-EXECUTOR). Shared
    // factory; each read spins a fresh short-lived virtual thread, so no
    // pool / shutdown bookkeeping is needed.
    private static final ThreadFactory BODY_READER_THREAD_FACTORY =
        Thread.ofVirtual().name("ssrf-body-reader-", 0).factory();

    private final IpBlocklist blocklist;

    private final Duration requestTimeout;

    private final Duration readTimeout;

    private final Duration bodyReadDeadline;

    private final long bodyCap;

    private final int redirectCap;

    private final Function<String, List<InetAddress>> resolverSeam;

    // B-HTTP-CLIENT (M1-277, M-S1): ONE HttpClient per wrapper instance,
    // built at construction and reused by every get() call — previously
    // one client (and one SelectorManager thread) was created per call.
    // Lifecycle: the client lives as long as the wrapper and is never
    // closed — the terminal hop's body InputStream must stay readable
    // for readBounded after the hop loop exits, and production wrappers
    // are effectively JVM-lifetime, so there is no close point that
    // would not race a body read. Compatibility with the JVM-wide pin
    // map: pinning is resolver-level (PinnedDnsResolver SPI), consulted
    // whenever the client opens a NEW connection; every hop still runs
    // resolveAndValidate + pin before its send, and a pooled connection
    // reused without re-resolution is one whose peer IP passed
    // validation when the connection was established — DNS cannot
    // re-bind an already-open socket.
    private final HttpClient httpClient;

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
             SsrfGuardedHttpClient::defaultResolve);
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
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connect timeout must be configured");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("request timeout must be configured");
        }
        if (readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("read timeout must be configured");
        }
        if (bodyReadDeadline.isZero() || bodyReadDeadline.isNegative()) {
            throw new IllegalArgumentException("body read deadline must be configured");
        }
        if (bodyCap <= 0) {
            throw new IllegalArgumentException("body cap must be configured");
        }
        if (redirectCap <= 0) {
            throw new IllegalArgumentException("redirect cap must be configured");
        }
        this.blocklist = blocklist;
        this.requestTimeout = requestTimeout;
        this.readTimeout = readTimeout;
        this.bodyReadDeadline = bodyReadDeadline;
        this.bodyCap = bodyCap;
        this.redirectCap = redirectCap;
        this.resolverSeam = resolverSeam;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            // NO_PROXY pins egress to a direct dial. A default-built
            // client falls through to the JDK's non-exposed default
            // proxy selector, which honors http.proxyHost /
            // https.proxyHost / socksProxyHost. An ambient proxy would
            // re-resolve the target host itself, bypassing both the
            // validated peer IP and the DNS pin (the rebind defense),
            // so guarded egress must never inherit one.
            .proxy(HttpClient.Builder.NO_PROXY)
            .build();
    }

    /**
     * The wrapper's shared {@link HttpClient}. Package-private test
     * seam (matching {@code canonicalizeHost}'s same-package access
     * idiom) so the proxy posture can be asserted directly:
     * {@code httpClient().proxy()} is {@link HttpClient.Builder#NO_PROXY}.
     */
    HttpClient httpClient() {
        return httpClient;
    }

    private static List<InetAddress> defaultResolve(String host) {
        try {
            return Arrays.asList(InetAddress.getAllByName(host));
        } catch (UnknownHostException e) {
            throw new SsrfPolicyException(
                SsrfPolicyException.Reason.UNKNOWN_HOST, "unknown host: " + host, e);
        }
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
     * lookup side (the forwarding resolver inside
     * {@link PinnedDnsResolver.Provider}) MUST call this helper so the
     * pin keys match regardless of JDK transformation choices.
     *
     * <p>Transformations applied, in order:
     * <ol>
     *   <li>Reject null or blank input with
     *       {@link IllegalArgumentException}.</li>
     *   <li>IPv6 URL-literals arrive bracketed from {@code URI.getHost()}
     *       (e.g. {@code [::1]}); {@code IDN.toASCII} rejects the
     *       brackets. For a bracketed host, strip the brackets, case-fold
     *       the inner literal, and re-add the brackets so the value still
     *       reads as an IPv6 literal for the dial and the pin key — the
     *       remaining steps do not apply.</li>
     *   <li>{@code IDN.toASCII(host)} — convert Unicode / punycode to
     *       canonical ASCII Compatible Encoding. Applied first because
     *       lowercase-then-toASCII can mis-handle case-sensitive punycode
     *       constructs. {@code ALLOW_UNASSIGNED} is deliberately NOT
     *       passed: this is a security-critical path, so unassigned code
     *       points are rejected rather than passed through to the
     *       resolver.</li>
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
        // IPv6 URL-literals arrive bracketed from URI.getHost() (e.g.
        // "[::1]"); IDN.toASCII rejects the brackets. Strip them,
        // case-fold the inner literal, and re-add the brackets so the
        // pin key and the dial target agree on the IPv6 literal form.
        if (host.startsWith("[") && host.endsWith("]")) {
            String inner = host.substring(1, host.length() - 1);
            // Validate the inner is a real IPv6 literal before re-bracketing,
            // closing the asymmetry with the IDN branch (M1-345). ofLiteral is a
            // pure parse that never performs DNS for ANY input, so the rejection
            // path adds no I/O; it throws IllegalArgumentException for a
            // non-IPv6-literal, which the caller (resolveAndValidate) wraps as
            // INVALID_HOST. IPv4-mapped literals (::ffff:a.b.c.d) parse OK here
            // and still flow to the IpBlocklist embedded-v4 decode, so no
            // currently-correct caller changes. getByName is deliberately NOT
            // used: it would DNS-resolve a non-literal inner and normalize
            // ::ffff:a.b.c.d to an Inet4Address, which an instanceof Inet6Address
            // check would then wrongly reject. The parsed value is discarded —
            // the original cased string is re-bracketed so the pin key stays
            // byte-stable with the dial target.
            Inet6Address.ofLiteral(inner);
            return "[" + inner.toLowerCase(Locale.ROOT) + "]";
        }
        String ascii = IDN.toASCII(host);
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
    public HttpResponse<byte[]> get(URI uri) throws IOException, InterruptedException {
        return get(uri, Map.of());
    }

    /**
     * Issue a GET against {@code uri} with caller-supplied per-request
     * headers attached to each hop in addition to the default
     * {@code Accept} / {@code User-Agent}. The full SSRF guard
     * pipeline runs identically to {@link #get(URI)} — scheme
     * allowlist, userinfo gate, host canonicalization, DNS
     * resolution + {@link IpBlocklist} check, DNS pinning, redirect
     * loop with per-hop re-validation, pin-released body read with
     * the same bounds. The extra headers are attached on every
     * SAME-origin redirect hop; the first cross-origin hop drops all
     * of them — the cross-origin safe set is empty, so only the
     * wrapper's own {@code Accept} / {@code User-Agent} defaults
     * cross origins (M1-277, M-S3).
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
        // Mutable per-call copy of the caller's headers: caller-supplied
        // headers are origin-scoped. On a cross-origin redirect the copy
        // is cleared — the cross-origin safe set is EMPTY, so nothing
        // the caller injected (credentials, Range, anything) is replayed
        // to a different host/port/scheme; only the wrapper's own
        // Accept / User-Agent defaults ride every hop. The caller's map
        // is never mutated.
        Map<String, String> hopHeaders = new LinkedHashMap<>(extraHeaders);
        // One pin handle per hop: each hop's freshly validated
        // host REPLACES the previous hop's pin (release-then-pin),
        // and the finally releases whichever pin is held when the
        // loop exits or throws — in particular BEFORE readBounded
        // (M1-026 Finding 1: the JDK does not re-resolve DNS on an
        // established connection, so the body-read phase needs no
        // pin).
        PinnedDnsResolver.Provider.PinHandle hopPin = null;
        try {
            URI current = uri;
            int redirectCount = 0;
            while (true) {
                ResolvedHost resolved = resolveAndValidate(current, HTTP_SCHEMES);
                if (hopPin != null) {
                    hopPin.release();
                }
                hopPin = PinnedDnsResolver.Provider.pin(
                    resolved.canonicalHost(), resolved.addresses());

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(current)
                    .timeout(requestTimeout)
                    .header("Accept", ACCEPT_HEADER)
                    .header("User-Agent", USER_AGENT)
                    .GET();
                hopHeaders.forEach(reqBuilder::header);
                HttpRequest request = reqBuilder.build();
                HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

                int status = response.statusCode();
                if (isFollowableRedirect(status)) {
                    redirectCount++;
                    if (redirectCount > redirectCap) {
                        // Over the cap: this hop will NOT be followed, so there is
                        // nothing to drain (M1-345) — close the body explicitly to
                        // release the connection. VERIFIED (M1-355,
                        // SsrfGuardedHttpClientTest#ofInputStreamCloseDoesNotDrainPartiallyReadBody):
                        // JDK 25 close() on a partially-read ofInputStream() body does
                        // NOT read-and-discard the remainder — it cancels the
                        // subscription and resets the connection — so this close()
                        // performs no attacker-controlled read. This is the resolution
                        // of the old contradiction with the discardBounded javadoc,
                        // which wrongly claimed close() could drain the whole body;
                        // discardBounded's bounded drain exists for connection REUSE on
                        // FOLLOWED hops, not as a guard against an unbounded close().
                        response.body().close();
                        throw new SsrfPolicyException(
                            SsrfPolicyException.Reason.REDIRECT_CAP_EXCEEDED,
                            "redirect cap exceeded");
                    }
                    // Drain only the hops we actually follow. An over-cap
                    // redirect body aborts here with BODY_CAP_EXCEEDED rather
                    // than being read unbounded (M1-355).
                    discardBounded(response.body());
                    String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new SsrfPolicyException(
                            SsrfPolicyException.Reason.REDIRECT_LOCATION_MISSING,
                            "redirect response missing Location header"));
                    // URI.resolve raises IllegalArgumentException on a
                    // syntactically malformed Location; wrap it so the
                    // attacker-controlled header cannot escape the
                    // wrapper's SsrfPolicyException/IOException contract
                    // (matching the INVALID_HOST wrapping of IDN.toASCII).
                    URI next;
                    try {
                        next = current.resolve(location);
                    } catch (IllegalArgumentException e) {
                        throw new SsrfPolicyException(
                            SsrfPolicyException.Reason.REDIRECT_LOCATION_INVALID,
                            "invalid redirect Location: " + location, e);
                    }
                    if (isCrossOrigin(current, next)) {
                        // M1-277 (M-S3): the cross-origin safe set is
                        // empty — drop EVERY caller-supplied header, not
                        // just the three credential headers. Caller
                        // headers were injected for the original origin;
                        // a structurally unknown header (today only
                        // Range) must not leak to a host the caller
                        // never addressed.
                        hopHeaders.clear();
                    }
                    current = next;
                    continue;
                }

                terminalResponse = response;
                break;
            }
        } finally {
            if (hopPin != null) {
                hopPin.release();
            }
        }

        byte[] body = readBounded(terminalResponse);
        return new BoundedByteArrayResponse(terminalResponse, body);
    }

    // 3xx statuses this wrapper follows. 300 (Multiple Choices), 304
    // (Not Modified), 305 (Use Proxy) and 306 (unused) are NOT redirects
    // with a follow-able Location and must fall through to the terminal
    // response rather than being chased (C-SSRF-304).
    private static boolean isFollowableRedirect(int status) {
        return switch (status) {
            case 301, 302, 303, 307, 308 -> true;
            default -> false;
        };
    }

    // A redirect crosses origin when the scheme, host, or effective port
    // differs. {@code from} is always validated (it was resolved this
    // hop): callers pass an absolute URI, so from.getScheme() is non-null by
    // precondition and is dereferenced directly with no runtime null-guard —
    // adding one would be defensive code for a structurally impossible state
    // (CLAUDE.md §No-defensive-code). {@code to} is an as-yet-unvalidated
    // redirect target, so its scheme/host may be null. The host comparison
    // runs through
    // canonicalizeHost (via sameCanonicalHost) so case / trailing-dot /
    // IDN variants of the SAME host are not misread as cross-origin —
    // the same canonicalization the pin map keys by, so the credential
    // scrub fires on a genuine origin change and not on a normalization
    // artifact. A null {@code to} scheme still fails safe via
    // equalsIgnoreCase(null) == false, and a null or un-canonicalizable
    // {@code to} host fails safe inside sameCanonicalHost — both scrub
    // credentials when unsure. Package-private so the same-origin
    // canonicalization can be asserted directly (matching effectivePort's
    // same-package test access).
    static boolean isCrossOrigin(URI from, URI to) {
        return !from.getScheme().equalsIgnoreCase(to.getScheme())
            || !sameCanonicalHost(from.getHost(), to.getHost())
            || effectivePort(from) != effectivePort(to);
    }

    // True when both hosts canonicalize to the same value under the
    // module's host fold (IDN.toASCII -> lowercase(Locale.ROOT) ->
    // trailing-dot strip). A null host, or one canonicalizeHost rejects
    // as syntactically invalid, returns false so isCrossOrigin fails safe
    // to "different host" (scrub credentials when unsure), preserving the
    // prior getHost().equalsIgnoreCase(null) == false behavior.
    private static boolean sameCanonicalHost(String fromHost, String toHost) {
        if (fromHost == null || toHost == null) {
            return false;
        }
        try {
            return canonicalizeHost(fromHost).equals(canonicalizeHost(toHost));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // Package-private (not private) so EffectivePortWssTest can assert the
    // scheme→default-port mapping directly, matching canonicalizeHost's
    // same-package test-access idiom. The wss branch is unreachable through
    // get() (http/https only), so a direct call is the only clean way to
    // pin the mapping.
    static int effectivePort(URI uri) {
        int port = uri.getPort();
        if (port != -1) {
            return port;
        }
        // wss shares the 443 default with https; ws and http (and any other
        // scheme reaching here) default to 80. Aligning wss with https keeps
        // a future WS origin/credential-scrub reuse from misjudging
        // wss://h/ vs wss://h:443/ as cross-origin. {@code to} in
        // isCrossOrigin is an unvalidated redirect target, so its scheme may
        // be null — treat that as the 80 default, matching the prior
        // equalsIgnoreCase(null)==false behavior.
        String scheme = uri.getScheme();
        return switch (scheme == null ? "" : scheme.toLowerCase(Locale.ROOT)) {
            case "https", "wss" -> 443;
            default -> 80;
        };
    }

    private ResolvedHost resolveAndValidate(URI uri, Set<String> allowedSchemes) {
        // RFC 3986 §3.1: schemes are case-insensitive. Case-fold before
        // the allowlist check, consistent with isCrossOrigin (which
        // already compares schemes via equalsIgnoreCase) — the allowlist
        // sets hold the lowercase canonical forms.
        String scheme = uri.getScheme();
        if (scheme == null || !allowedSchemes.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new SsrfPolicyException(
                SsrfPolicyException.Reason.SCHEME_NOT_ALLOWED, "scheme not allowed: " + scheme);
        }
        if (uri.getRawUserInfo() != null) {
            throw new SsrfPolicyException(
                SsrfPolicyException.Reason.USERINFO_NOT_ALLOWED, "userinfo segment not allowed");
        }
        String rawHost = uri.getHost();
        if (rawHost == null) {
            throw new SsrfPolicyException(
                SsrfPolicyException.Reason.HOST_MISSING, "host missing from URI");
        }
        // M1-026 Finding 2: canonicalize before BOTH the seam call
        // AND the pin install. The same helper is invoked on the
        // lookup side by the forwarding resolver inside
        // PinnedDnsResolver.Provider, so the pin matches regardless of
        // JDK normalization choices.
        // IDN.toASCII throws IllegalArgumentException on invalid
        // input; wrap as SsrfPolicyException so the wrapper boundary
        // surfaces a clean policy exception (no JDK internals leak).
        String canonicalHost;
        try {
            canonicalHost = canonicalizeHost(rawHost);
        } catch (IllegalArgumentException e) {
            throw new SsrfPolicyException(
                SsrfPolicyException.Reason.INVALID_HOST, "invalid host: " + rawHost, e);
        }
        // Resolver-seam contract: the PRODUCTION seam (defaultResolve) never
        // returns null or empty — InetAddress.getAllByName either yields a
        // non-empty array or throws UnknownHostException, which defaultResolve
        // wraps as UNKNOWN_HOST. So on the production path this branch is
        // unreachable and the throw below is dead. It is retained to defend the
        // ALTERNATE seam: the resolver-seam constructor lets sibling-module
        // tests inject an arbitrary Function that may model an empty resolution,
        // and a null/empty result there must fail closed as UNKNOWN_HOST rather
        // than flow on to an empty blocklist check. The duplicated reason keeps
        // both seams' "host did not resolve" outcome identical.
        List<InetAddress> addresses = resolverSeam.apply(canonicalHost);
        if (addresses == null || addresses.isEmpty()) {
            throw new SsrfPolicyException(
                SsrfPolicyException.Reason.UNKNOWN_HOST, "unknown host: " + canonicalHost);
        }
        // Enumerate the host-interface set ONCE for this validation pass
        // (firstBlocked snapshots internally) rather than once per
        // resolved address: a k-address host triggers one enumeration,
        // not k. The block/allow decision is identical to a per-address
        // scan, and per-request freshness is preserved (each pass takes
        // its own fresh snapshot).
        //
        // HostInterfaceSet.enumerate() raises IllegalStateException when
        // the OS interface enumeration itself fails (SocketException) —
        // a system-boundary I/O failure, not a policy decision. Surface
        // it as a typed SsrfPolicyException so it stays inside get()'s
        // documented SsrfPolicyException / IOException contract rather
        // than escaping as an undocumented RuntimeException; fail closed
        // (a request whose host-interface clause cannot be evaluated is
        // rejected, not admitted).
        InetAddress blocked;
        try {
            blocked = blocklist.firstBlocked(addresses);
        } catch (IllegalStateException e) {
            throw new SsrfPolicyException(
                SsrfPolicyException.Reason.HOST_INTERFACE_UNAVAILABLE,
                "host interface enumeration failed", e);
        }
        if (blocked != null) {
            throw new SsrfPolicyException(
                SsrfPolicyException.Reason.BLOCKED_IP, "blocked IP: " + blocked.getHostAddress());
        }
        return new ResolvedHost(canonicalHost, addresses);
    }

    private byte[] readBounded(HttpResponse<InputStream> response)
            throws IOException, InterruptedException {
        // Terminal hop: accumulate the body the caller receives. The size cap,
        // per-read watchdog, and total deadline all live in supervisedDrain —
        // the single point of truth shared with the redirect-hop discard
        // (M1-355) — so this method only supplies the accumulating sink.
        // ByteArrayOutputStream.close() is a no-op, so it needs no
        // try-with-resources; supervisedDrain owns closing the InputStream.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        supervisedDrain(response.body(), (chunk, length) -> out.write(chunk, 0, length));
        return out.toByteArray();
    }

    /**
     * Read one buffer's worth of {@code in} under the body-read time
     * bounds, returning the byte count (or -1 at EOF). Both the
     * terminal-hop {@link #readBounded} and the redirect-hop
     * {@link #discardBounded} drains supervise every read through here, so
     * the wall-clock bound is identical on both paths — a redirect body is
     * thrown away, but it is not read un-timed.
     *
     * <p>Per-read wall-clock watchdog (M1-025, Finding 4): the JDK's
     * {@code HttpRequest.timeout()} bounds only the receipt of response
     * HEADERS; without this watchdog a malicious upstream can dribble body
     * bytes one per minute and hold a fetcher thread for hours. Each
     * {@code in.read(buf)} runs on its own virtual thread
     * (B-READBOUNDED-EXECUTOR: one per read, JDK 25 — cheap enough to spin
     * per read and needs no pool / shutdownNow bookkeeping), supervised
     * from the caller thread; on timeout the task is cancelled and a typed
     * {@link SsrfPolicyException} raised.
     *
     * <p>M1-026 Finding 1: beyond the per-read watchdog, the TOTAL
     * wall-clock time from {@code bodyReadStartNanos} is bounded by
     * {@code bodyReadDeadline}. A drip attacker that returns 1 byte per
     * (readTimeout - epsilon) defeats the per-read watchdog (each read
     * completes well under the window) but cannot defeat a total-elapsed
     * deadline.
     */
    private int supervisedReadChunk(InputStream in, byte[] buf, long bodyReadStartNanos)
            throws IOException, InterruptedException {
        long elapsedNanos = System.nanoTime() - bodyReadStartNanos;
        long remainingNanos = bodyReadDeadline.toNanos() - elapsedNanos;
        if (remainingNanos <= 0) {
            throw new SsrfPolicyException(
                SsrfPolicyException.Reason.BODY_READ_DEADLINE_EXCEEDED,
                "body read deadline exceeded after "
                + TimeUnit.NANOSECONDS.toMillis(elapsedNanos) + "ms");
        }
        // B-DEADLINE-TOCTOU: clamp each read to
        // min(readTimeout, remaining-until-deadline) so one read cannot
        // overshoot the total deadline by up to a full readTimeout.
        // remaining is rounded UP to whole ms so the clamped wait never
        // expires BEFORE the deadline — that keeps the classification below
        // correct (a clamp expiry at or past the deadline is a deadline
        // breach, not a per-read stall).
        long remainingMillisCeil = (remainingNanos + 999_999L) / 1_000_000L;
        long readBudgetMillis = Math.min(readTimeout.toMillis(), remainingMillisCeil);

        FutureTask<Integer> readTask = new FutureTask<>(() -> in.read(buf));
        BODY_READER_THREAD_FACTORY.newThread(readTask).start();
        try {
            return readTask.get(readBudgetMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            readTask.cancel(true);
            long elapsedAtTimeoutNanos = System.nanoTime() - bodyReadStartNanos;
            if (elapsedAtTimeoutNanos >= bodyReadDeadline.toNanos()) {
                throw new SsrfPolicyException(
                    SsrfPolicyException.Reason.BODY_READ_DEADLINE_EXCEEDED,
                    "body read deadline exceeded after "
                    + TimeUnit.NANOSECONDS.toMillis(elapsedAtTimeoutNanos) + "ms");
            }
            throw new SsrfPolicyException(
                SsrfPolicyException.Reason.BODY_READ_TIMEOUT,
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
            // Propagate the interrupt as InterruptedException (get() already
            // declares it) rather than masking it as IOException, so a caller
            // that cancels a fetch by interrupting its thread sees the
            // interrupt, not a spurious I/O error. Cancel the in-flight read
            // first. Do NOT also restore the interrupt flag: re-throwing the
            // InterruptedException IS the propagation, and re-interrupting on
            // top would double-signal it.
            readTask.cancel(true);
            throw e;
        }
    }

    /**
     * Drain a FOLLOWED redirect hop's response body through the shared
     * supervised-drain loop and return the number of bytes read. A redirect
     * carries no payload the wrapper retains, but the body must be consumed to
     * EOF for the underlying connection to be reusable for the next hop; this
     * consumes it through the SAME size cap, per-read watchdog, and total
     * {@code bodyReadDeadline} as the terminal {@link #readBounded}, discarding
     * each chunk instead of accumulating it.
     *
     * <p>Over-cap is a policy violation, not a stop condition (M1-355): a
     * redirect body that exceeds {@code bodyCap} aborts the call with
     * {@code BODY_CAP_EXCEEDED} (raised inside {@link #supervisedDrain}) rather
     * than breaking the loop and leaving the over-cap remainder to the
     * try-with-resources {@code close()}. A multi-megabyte redirect body is
     * anomalous; failing closed on it is the correct outbound-SSRF posture and
     * matches the terminal-body treatment. {@link #supervisedReadChunk} also
     * bounds the time of every read, so a slow-dribble redirect body that stays
     * under the size cap still cannot hold the fetcher thread past the deadline
     * — it aborts with the typed {@code BODY_READ_TIMEOUT} / {@code
     * BODY_READ_DEADLINE_EXCEEDED}. A size-only cap would reopen the M1-025
     * slow-dribble DoS on the redirect path; the redirect body is thrown away,
     * but it is not read un-timed.
     */
    long discardBounded(InputStream body)
            throws IOException, InterruptedException {
        return supervisedDrain(body, (chunk, length) -> { });
    }

    /**
     * The single supervised body-read loop behind both {@link #readBounded}
     * (terminal hop, accumulating sink) and {@link #discardBounded} (followed
     * redirect hop, no-op sink). Centralising the loop gives the size cap, the
     * per-read wall-clock watchdog, and the total {@code bodyReadDeadline}
     * exactly one definition, so the terminal and redirect paths cannot drift
     * apart (M1-355) — the two differ only in their {@code sink}.
     *
     * <p>Reads {@code body} to EOF, feeding each chunk to {@code sink}, and
     * returns the total bytes read. Once the running total exceeds
     * {@code bodyCap} it throws {@code BODY_CAP_EXCEEDED}; it never breaks the
     * loop and leans on the try-with-resources {@code close()} to bound an
     * over-cap body (see the verified close()-drain note on the redirect-cap
     * path in {@link #get(URI, Map)}). The {@code body} stream is always closed
     * on exit.
     */
    private long supervisedDrain(InputStream body, ChunkSink sink)
            throws IOException, InterruptedException {
        long bodyReadStartNanos = System.nanoTime();
        try (InputStream in = body) {
            // 64 KiB buffer: each in.read() runs on its own virtual thread
            // (see supervisedReadChunk), so a larger buffer means ~8x fewer
            // reads — and ~8x fewer thread spins / FutureTask allocations —
            // to drain the same body.
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            while (true) {
                int n = supervisedReadChunk(in, buf, bodyReadStartNanos);
                if (n == -1) {
                    break;
                }
                total += n;
                if (total > bodyCap) {
                    throw new SsrfPolicyException(
                        SsrfPolicyException.Reason.BODY_CAP_EXCEEDED,
                        "response body exceeded " + bodyCap + " bytes");
                }
                sink.accept(buf, n);
            }
            return total;
        }
    }

    /**
     * Per-chunk consumer for {@link #supervisedDrain}: accumulate (terminal
     * hop) or discard (followed redirect hop). {@code chunk[0..length)} holds
     * the bytes just read; the array is reused across reads, so a sink that
     * retains bytes must copy them out immediately (the terminal sink writes
     * them straight into its {@code ByteArrayOutputStream}).
     */
    @FunctionalInterface
    private interface ChunkSink {
        void accept(byte[] chunk, int length) throws IOException;
    }

    /**
     * Validate {@code uri} against the WebSocket SSRF policy (scheme
     * allowlist {@code {ws, wss}} + userinfo gate + host
     * canonicalization + DNS resolution + {@link IpBlocklist} check),
     * pin the resolved IPs in the JVM-wide
     * {@link PinnedDnsResolver.Provider} per-host pin map — the same
     * map {@link #get(URI)} pins through — and return a
     * {@link PinnedDial} handle whose {@link PinnedDial#close()}
     * releases the pin.
     *
     * <p>The caller dials the WebSocket (e.g. {@code
     * HttpClient.newWebSocketBuilder().buildAsync(uri, listener)}) and
     * waits for the connection to be established (the JDK
     * {@code WebSocket} handshake routes its DNS lookup through the
     * pinned resolver, so the TCP connection lands on a validated IP)
     * INSIDE the try-with-resources block, then exits the block so the
     * host's pin is released. The connection itself survives
     * close-of-the-PinnedDial; only the pin entry is wound down.
     *
     * <p>Concurrency: the pin is per-host and refcounted, so an open
     * dial blocks NO other outbound call — concurrent SSRF-checked
     * dials (HTTP and WebSocket, different hosts or the same) proceed
     * independently.
     *
     * <p>A policy violation raises {@link SsrfPolicyException} BEFORE
     * any pin is installed, so a thrown check leaves no stale pin
     * behind.
     */
    public PinnedDial checkAndPinForWebSocket(URI uri) {
        ResolvedHost resolved = resolveAndValidate(uri, WEBSOCKET_SCHEMES);
        return new PinnedDial(
            PinnedDnsResolver.Provider.pin(resolved.canonicalHost(), resolved.addresses()),
            resolved.addresses());
    }

    /**
     * Run the WebSocket SSRF check {@link #checkAndPinForWebSocket}
     * runs, BUT without pinning.
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
    public List<InetAddress> resolveForWebSocket(URI uri) {
        return resolveAndValidate(uri, WEBSOCKET_SCHEMES).addresses();
    }

    /**
     * Validation result: the canonical host form (used as the pin
     * map key) and the resolved + blocklist-passing IP set.
     */
    private record ResolvedHost(String canonicalHost, List<InetAddress> addresses) {}

    /**
     * Handle returned by {@link #checkAndPinForWebSocket(URI)}. Holds
     * one refcounted acquisition of the host's entry in the JVM-wide
     * {@link PinnedDnsResolver.Provider} pin map until {@link #close()}
     * runs, so the WebSocket dial executed inside a try-with-resources
     * block sees the validated IPs from {@link PinnedDnsResolver}'s
     * SPI lookups. Carries the validated address set for the caller's
     * peer-IP-change watcher (compare against
     * {@link SsrfGuardedHttpClient#resolveForWebSocket(URI)} on
     * subsequent ticks).
     *
     * <p>{@link #close()} is idempotent: it releases this handle's
     * acquisition exactly once, so a stray re-{@code close()} can
     * neither throw nor release a concurrent same-host holder's pin
     * early.
     */
    public static final class PinnedDial implements AutoCloseable {

        private final PinnedDnsResolver.Provider.PinHandle pin;

        private final List<InetAddress> addresses;

        PinnedDial(PinnedDnsResolver.Provider.PinHandle pin, List<InetAddress> addresses) {
            this.pin = pin;
            this.addresses = List.copyOf(addresses);
        }

        /**
         * The IP set this dial validated and pinned. Stable across
         * the life of this handle (a concurrent same-host pin may
         * update what the resolver serves — latest-wins — but never
         * what this handle captured at validation time).
         */
        public List<InetAddress> addresses() {
            return addresses;
        }

        @Override
        public void close() {
            pin.release();
        }
    }

    /**
     * Raised on any policy violation in the wrapper pipeline:
     * disallowed scheme, userinfo in URI, blocked IP, host-interface
     * enumeration failure, oversize body, exceeded redirect cap,
     * missing or unresolvable redirect {@code Location}, body-read
     * timeout, body-read deadline exceeded. {@link #reason()} carries the typed
     * failure mode — callers branch on it, never on message text
     * (the message is human-facing and free to reword).
     */
    public static final class SsrfPolicyException extends RuntimeException {

        /** Typed failure mode, one constant per policy violation. */
        public enum Reason {
            SCHEME_NOT_ALLOWED,
            USERINFO_NOT_ALLOWED,
            HOST_MISSING,
            INVALID_HOST,
            UNKNOWN_HOST,
            BLOCKED_IP,
            HOST_INTERFACE_UNAVAILABLE,
            REDIRECT_CAP_EXCEEDED,
            REDIRECT_LOCATION_MISSING,
            REDIRECT_LOCATION_INVALID,
            BODY_CAP_EXCEEDED,
            BODY_READ_TIMEOUT,
            BODY_READ_DEADLINE_EXCEEDED
        }

        private final Reason reason;

        public SsrfPolicyException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public SsrfPolicyException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
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
            // Always empty by design, even when the wrapper followed redirect
            // hops to reach this terminal response. The wrapper drives the
            // redirect loop itself (Redirect.NEVER on the JDK client) and
            // discards each intermediate hop's response after draining it, so
            // it holds no prior-hop HttpResponse to expose here. Callers needing
            // the final landing URI read uri(); the per-hop chain is internal.
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
