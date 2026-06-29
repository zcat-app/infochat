package app.zcat.infochat.ssrf;

import com.sun.net.httpserver.HttpServer;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient.SsrfPolicyException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 unit tests for {@link SsrfGuardedHttpClient}: each
 * policy is exercised against an in-process
 * {@link com.sun.net.httpserver.HttpServer com.sun.net.httpserver.HttpServer}
 * fixture bound to {@code 127.0.0.1}. Because the strict
 * {@link IpBlocklist} blocks {@code 127.0.0.0/8}, every test that
 * needs to reach the loopback fixture constructs the wrapper with the
 * loopback-permitting blocklist from
 * {@link LoopbackPermittingBlocklist#create()}. The strict-mode tests
 * use the no-arg constructor.
 */
class SsrfGuardedHttpClientTest {

    private HttpServer server;

    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonHttpScheme() {
        SsrfGuardedHttpClient client = testModeClient();
        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.get(URI.create("ftp://example.com/")));
        assertEquals(SsrfPolicyException.Reason.SCHEME_NOT_ALLOWED, ex.reason(),
            "non-http(s) scheme must be rejected with SCHEME_NOT_ALLOWED");
    }

    @Test
    void getEntrypointRejectsWebsocketScheme() {
        // ws/wss are spec-allowed but run through the dedicated
        // WebSocket entrypoints (checkAndPinForWebSocket /
        // resolveForWebSocket) — the JDK's HttpClient.send cannot dial
        // ws/wss, so a WebSocket URI reaching get() is a misroute the
        // scheme gate must reject.
        SsrfGuardedHttpClient client = testModeClient();
        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.get(URI.create("wss://example.com/relay")));
        assertEquals(SsrfPolicyException.Reason.SCHEME_NOT_ALLOWED, ex.reason(),
            "ws/wss on the get() entrypoint must be rejected with "
            + "SCHEME_NOT_ALLOWED; the WebSocket entrypoints accept them");
    }

    @Test
    void checkAndPinForWebSocketAcceptsWssScheme() throws Exception {
        // The WS-scheme acceptance path: wss:// passes the WebSocket
        // entrypoint's scheme gate and proceeds through the IP checks
        // to a successful pin. The seam supplies the address set so no
        // real DNS lookup runs.
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        SsrfGuardedHttpClient client = new SsrfGuardedHttpClient(
            LoopbackPermittingBlocklist.create(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            10L * 1024,
            3,
            host -> List.of(loopback));

        try (SsrfGuardedHttpClient.PinnedDial dial =
                 client.checkAndPinForWebSocket(URI.create("wss://relay.example.test/"))) {
            assertEquals(List.of(loopback), dial.addresses(),
                "the validated address set must be the seam-supplied one");
        }
    }

    @Test
    void rejectsUserinfoSegment() {
        SsrfGuardedHttpClient client = testModeClient();
        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.get(URI.create("https://user:pw@example.com/")));
        assertEquals(SsrfPolicyException.Reason.USERINFO_NOT_ALLOWED, ex.reason(),
            "URIs carrying user:pw@ must be rejected with USERINFO_NOT_ALLOWED");
    }

    @Test
    void rejectsLoopbackUnderStrictBlocklist() {
        // No-arg constructor uses the strict production blocklist
        // which refuses to dial 127.0.0.0/8. The HttpServer is
        // bound to 127.0.0.1; the request must never leave the
        // wrapper.
        SsrfGuardedHttpClient strict = new SsrfGuardedHttpClient();
        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> strict.get(URI.create("http://127.0.0.1:" + port + "/never-dialed")));
        assertEquals(SsrfPolicyException.Reason.BLOCKED_IP, ex.reason(),
            "strict-mode wrapper must reject 127.0.0.1 with BLOCKED_IP");
    }

    @Test
    void rejectsResponseBodyOverCap() throws IOException {
        // Server emits 4 KiB; wrapper cap is 1 KiB. The wrapper must
        // tear down the read and raise.
        byte[] payload = new byte[4 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ('A' + (i % 26));
        }
        server.createContext("/big", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });

        SsrfGuardedHttpClient client = new SsrfGuardedHttpClient(
            LoopbackPermittingBlocklist.create(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofMinutes(2),
            1024L,
            3);

        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.get(URI.create("http://127.0.0.1:" + port + "/big")));
        assertEquals(SsrfPolicyException.Reason.BODY_CAP_EXCEEDED, ex.reason(),
            "oversize body must surface BODY_CAP_EXCEEDED");
    }

    @Test
    void happyPath2xxReturnsBodyBytes() throws Exception {
        String greeting = "hello, world";
        server.createContext("/hi", exchange -> {
            byte[] body = greeting.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        SsrfGuardedHttpClient client = testModeClient();
        HttpResponse<byte[]> response =
            client.get(URI.create("http://127.0.0.1:" + port + "/hi"));

        assertEquals(200, response.statusCode());
        assertEquals(greeting, new String(response.body(), StandardCharsets.UTF_8));
    }

    @Test
    void redirectCapExceededRaises() {
        // The server bounces /loop → /loop forever; the wrapper's
        // counter must trip on the 4th hop (cap = 3).
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/loop", exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        SsrfGuardedHttpClient client = testModeClient();
        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.get(URI.create("http://127.0.0.1:" + port + "/loop")));
        assertEquals(SsrfPolicyException.Reason.REDIRECT_CAP_EXCEEDED, ex.reason(),
            "exceeding the redirect cap must surface REDIRECT_CAP_EXCEEDED");
        assertEquals(4, hits.get(),
            "the wrapper must allow the initial dial + 3 redirect hops "
            + "before raising; the 4th hop triggers the cap");
    }

    @Test
    void redirectRevalidatesDnsForNewTarget() {
        // The server returns a 302 to https://169.254.169.254/ — a
        // strict-blocklist address. The wrapper must re-resolve and
        // reject on hop 2, NOT silently follow.
        server.createContext("/rebind", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        SsrfGuardedHttpClient client = testModeClient();
        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.get(URI.create("http://127.0.0.1:" + port + "/rebind")));
        assertEquals(SsrfPolicyException.Reason.BLOCKED_IP, ex.reason(),
            "redirect target whose IP is on the strict blocklist must "
            + "be rejected on the second hop's re-resolution with BLOCKED_IP");
    }

    @Test
    void malformedRedirectLocationRaisesPolicyException() {
        // The Location header is attacker-controlled; a value that
        // URI.resolve cannot parse (the space is illegal in a URI)
        // must surface through the wrapper's documented
        // SsrfPolicyException contract, not as a raw
        // IllegalArgumentException escaping the hop loop.
        server.createContext("/badlocation", exchange -> {
            exchange.getResponseHeaders().add("Location", "/bad path");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        SsrfGuardedHttpClient client = testModeClient();
        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.get(URI.create("http://127.0.0.1:" + port + "/badlocation")));
        assertEquals(SsrfPolicyException.Reason.REDIRECT_LOCATION_INVALID, ex.reason(),
            "a syntactically malformed Location must surface "
            + "REDIRECT_LOCATION_INVALID through the policy-exception "
            + "contract, not a raw IllegalArgumentException");
    }

    @Test
    void upperCaseSchemePassesSchemeGateAndProceedsToIpChecks() {
        // RFC 3986 schemes are case-insensitive; isCrossOrigin already
        // case-folds. The strict blocklist rejects 127.0.0.1, so a
        // BLOCKED_IP rejection (not SCHEME_NOT_ALLOWED) proves the
        // upper-cased scheme passed the scheme gate and the pipeline
        // reached the IP checks.
        SsrfGuardedHttpClient strict = new SsrfGuardedHttpClient();
        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> strict.get(URI.create("HTTP://127.0.0.1:" + port + "/never-dialed")));
        assertEquals(SsrfPolicyException.Reason.BLOCKED_IP, ex.reason(),
            "HTTP:// must pass the case-folded scheme allowlist and be "
            + "rejected at the IP check, not at the scheme gate");
    }

    @Test
    void constructorRejectsZeroTimeout() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> new SsrfGuardedHttpClient(
                new IpBlocklist(),
                Duration.ofSeconds(1),
                Duration.ZERO,
                Duration.ofSeconds(1),
                Duration.ofMinutes(2),
                1024L,
                3));
        assertTrue(ex.getMessage().contains("timeout must be configured"),
            "zero request-timeout must be rejected with the literal "
            + "\"timeout must be configured\" prefix");
    }

    // -----------------------------------------------------------------
    // M1-025 within-hop DNS pinning (Finding 2: INFO-LEAK / high). The
    // spec's "DNS-rebind defense" requires the IPs the wrapper
    // validated to be the SAME IPs the JDK HttpClient connects to —
    // no independent DNS lookup between validate() and send().
    // -----------------------------------------------------------------

    @Test
    void connectUsesValidationTimeIpsNotFreshLookup() throws Exception {
        // Server bound to 127.0.0.1 returns 200 OK + a small body.
        server.createContext("/pin", exchange -> {
            byte[] body = "pinned".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        // The seam returns [127.0.0.1] on the first invocation (the
        // wrapper's validate step) and [192.0.2.1] (TEST-NET-1,
        // unreachable) on any subsequent invocation. If the wrapper
        // calls the seam a second time, the test fails on the
        // exactly-once assertion. If the JDK performs an independent
        // DNS lookup for the .invalid hostname (rather than honoring
        // our pin), the connect fails with UnknownHostException and
        // the 200-status assertion fails.
        AtomicInteger seamCalls = new AtomicInteger();
        Function<String, List<InetAddress>> seam = host -> {
            int n = seamCalls.incrementAndGet();
            try {
                return List.of(n == 1
                    ? InetAddress.getByName("127.0.0.1")
                    : InetAddress.getByName("192.0.2.1"));
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        };

        SsrfGuardedHttpClient client = new SsrfGuardedHttpClient(
            LoopbackPermittingBlocklist.create(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            10L * 1024,
            3,
            seam);

        // .invalid TLD (RFC 6761) is reserved as never-resolvable, so
        // the JDK builtin would fail the lookup. Only the pin makes
        // this succeed.
        HttpResponse<byte[]> response = client.get(
            URI.create("http://ssrf-pin-test.invalid:" + port + "/pin"));

        assertEquals(200, response.statusCode(),
            "wrapper must have connected to 127.0.0.1 (the pin), not "
            + "performed an independent JDK lookup of the .invalid host");
        assertEquals(1, seamCalls.get(),
            "seam must be invoked exactly once (validate-time only); "
            + "a second invocation would indicate the wrapper called "
            + "the seam again rather than pinning the JDK lookup");
    }

    // -----------------------------------------------------------------
    // M1-025 per-read body wall-clock timeout (Finding 4: DOS /
    // medium). The JDK's HttpRequest.timeout() bounds only HEADERS;
    // a malicious upstream that dribbles body bytes one per minute
    // can hold a fetcher thread for ~19 years (within the 10 MiB
    // body cap). The wrapper's readBounded watchdog must fire when
    // an individual in.read() does not return within readTimeout.
    // -----------------------------------------------------------------

    @Test
    void bodyReadTimeoutFiresOnSlowUpstream() {
        Duration readTimeout = Duration.ofSeconds(1);
        server.createContext("/slow", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, 5_000_000);
            OutputStream out = exchange.getResponseBody();
            try {
                // Write headers + a handful of bytes immediately so
                // the JDK HttpClient receives the response headers
                // (HttpRequest.timeout() bounds headers only). Then
                // stall longer than readTimeout — the per-read body
                // watchdog must fire.
                out.write(new byte[16]);
                out.flush();
                Thread.sleep(readTimeout.multipliedBy(2).toMillis());
                // Writes after the wrapper closes the connection will
                // throw; that's fine, the wrapper has already raised.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // Expected: wrapper closed the connection after timeout.
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // Connection already gone.
                }
            }
        });

        // Long request-timeout so the failure cannot be the
        // request-level timeout instead of the per-read watchdog.
        // The assertion below uses requestTimeout.toMillis() as the
        // wall-clock upper bound so the meaningful correctness
        // boundary ("watchdog fires before request-level timeout
        // would have") is expressed directly, without a brittle
        // hand-picked tolerance against scheduler jitter.
        Duration requestTimeout = Duration.ofSeconds(30);
        SsrfGuardedHttpClient client = new SsrfGuardedHttpClient(
            LoopbackPermittingBlocklist.create(),
            Duration.ofSeconds(2),
            requestTimeout,
            readTimeout,
            Duration.ofMinutes(2),
            10L * 1024 * 1024,
            3);

        long start = System.currentTimeMillis();
        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.get(URI.create("http://127.0.0.1:" + port + "/slow")));
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(SsrfPolicyException.Reason.BODY_READ_TIMEOUT, ex.reason(),
            "must surface BODY_READ_TIMEOUT; got: " + ex.reason());
        assertTrue(elapsed < requestTimeout.toMillis(),
            "per-read watchdog must fire before the request-level "
            + "timeout (" + requestTimeout.toSeconds() + "s) — if elapsed "
            + "is near the request timeout, it's the wrong code path firing; "
            + "elapsed=" + elapsed + "ms (readTimeout="
            + readTimeout.toMillis() + "ms, requestTimeout="
            + requestTimeout.toMillis() + "ms)");
    }

    // -----------------------------------------------------------------
    // M1-026 total body-read deadline (Finding 1: DOS / high). The
    // M1-025 per-read watchdog covers stalled reads but a drip
    // attacker delivering 1 byte per (readTimeout - epsilon) keeps
    // each individual in.read() under the per-read window — the
    // watchdog never fires. The total wall-clock bodyReadDeadline
    // bounds the cumulative elapsed body-read time, terminating the
    // call even when each individual read returns promptly.
    // -----------------------------------------------------------------

    @Test
    void dripBodyReadHitsTotalDeadline() {
        Duration readTimeout = Duration.ofMillis(500);
        Duration bodyReadDeadline = Duration.ofSeconds(2);
        // 1 byte every (readTimeout / 4) = 125ms keeps each individual
        // in.read() returning well under the 500ms per-read window,
        // so the per-read watchdog NEVER fires; the TOTAL deadline
        // must be what terminates the call.
        Duration dripInterval = readTimeout.dividedBy(4);

        server.createContext("/drip", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, 5_000_000);
            OutputStream out = exchange.getResponseBody();
            try {
                while (true) {
                    out.write(new byte[] { 'A' });
                    out.flush();
                    Thread.sleep(dripInterval.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // Expected: wrapper closed the connection after deadline.
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // Connection already gone.
                }
            }
        });

        SsrfGuardedHttpClient client = new SsrfGuardedHttpClient(
            LoopbackPermittingBlocklist.create(),
            Duration.ofSeconds(2),
            // Long request-timeout so the failure cannot be the
            // request-level timeout instead of the body-read deadline.
            Duration.ofSeconds(30),
            readTimeout,
            bodyReadDeadline,
            10L * 1024 * 1024,
            3);

        long start = System.currentTimeMillis();
        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.get(URI.create("http://127.0.0.1:" + port + "/drip")));
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(SsrfPolicyException.Reason.BODY_READ_DEADLINE_EXCEEDED, ex.reason(),
            "must surface BODY_READ_DEADLINE_EXCEEDED; got: " + ex.reason());
        assertTrue(elapsed >= bodyReadDeadline.toMillis(),
            "deadline must not fire BEFORE the configured "
            + "bodyReadDeadline; elapsed=" + elapsed + "ms, "
            + "bodyReadDeadline=" + bodyReadDeadline.toMillis() + "ms");
        assertTrue(elapsed < bodyReadDeadline.toMillis() + 1000,
            "deadline must fire within bodyReadDeadline + 1s tolerance "
            + "(NOT after the full requestTimeout); elapsed=" + elapsed
            + "ms, bodyReadDeadline=" + bodyReadDeadline.toMillis() + "ms");
    }

    // -----------------------------------------------------------------
    // M1-470 shared body-read deadline across redirect hops (deep-review
    // ssrf F1, DOS / low). A single get() must bound the CUMULATIVE
    // body-read wall-clock across every followed redirect hop plus the
    // terminal read by ONE bodyReadDeadline — not grant each hop a fresh
    // one. Before the fix, supervisedDrain captured bodyReadStartNanos per
    // body-read phase, so a self-redirecting host got (redirectCap + 1)
    // independent full deadlines, holding a fetcher thread ~(redirectCap+1)x
    // the documented bound while each individual read stayed under the
    // per-read watchdog.
    // -----------------------------------------------------------------

    @Test
    void redirectChainSharesOneBodyReadDeadline() {
        // The server self-redirects /chain -> /chain, and each 302 carries a
        // small body that drips one byte per (readTimeout / 4) = 125ms — well
        // under the 500ms per-read watchdog, so ONLY the cumulative total
        // deadline can stop the call. Each hop's body drains in ~7 * 125ms ≈
        // 0.875s: comfortably under one 2s bodyReadDeadline (so a single hop
        // never trips it alone), but more than a third of it, so the cumulative
        // across 2-3 hops crosses the ONE shared deadline before the redirect
        // cap is reached. Thread.sleep is a floor, so 3 hops always sum to
        // ≥2.625s ≥ the deadline — the deadline therefore always fires before a
        // 4th request, regardless of CI speed.
        //
        // Reason cleanly distinguishes the fix from the bug: with one shared
        // budget the call aborts mid-chain with BODY_READ_DEADLINE_EXCEEDED;
        // with the pre-fix per-hop capture every hop got a fresh 2s, each
        // ~0.875s body drained fully to EOF, all redirectCap hops were
        // followed, and the call surfaced REDIRECT_CAP_EXCEEDED instead.
        Duration readTimeout = Duration.ofMillis(500);
        Duration bodyReadDeadline = Duration.ofSeconds(2);
        Duration dripInterval = readTimeout.dividedBy(4);
        int hopBodyBytes = 7;
        int redirectCap = 3;

        AtomicInteger hits = new AtomicInteger();
        server.createContext("/chain", exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "/chain");
            exchange.sendResponseHeaders(302, hopBodyBytes);
            OutputStream out = exchange.getResponseBody();
            try {
                for (int i = 0; i < hopBodyBytes; i++) {
                    out.write('A');
                    out.flush();
                    Thread.sleep(dripInterval.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // Expected: wrapper aborted mid-drain and reset the connection.
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // Connection already gone.
                }
            }
        });

        SsrfGuardedHttpClient client = new SsrfGuardedHttpClient(
            LoopbackPermittingBlocklist.create(),
            Duration.ofSeconds(2),
            // Long request-timeout so the abort cannot be the request-level
            // timeout instead of the shared body-read deadline.
            Duration.ofSeconds(30),
            readTimeout,
            bodyReadDeadline,
            10L * 1024 * 1024,
            redirectCap);

        long start = System.currentTimeMillis();
        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.get(URI.create("http://127.0.0.1:" + port + "/chain")));
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(SsrfPolicyException.Reason.BODY_READ_DEADLINE_EXCEEDED, ex.reason(),
            "the cumulative body-read across followed redirect hops must abort "
            + "with ONE shared BODY_READ_DEADLINE_EXCEEDED; a per-hop deadline "
            + "would let every hop drain fully and surface REDIRECT_CAP_EXCEEDED "
            + "instead. got: " + ex.reason());
        assertTrue(hits.get() >= 2,
            "the call must traverse more than one redirect hop before the shared "
            + "deadline fires — proving the budget spans hops rather than "
            + "resetting per hop; hits=" + hits.get());
        assertTrue(elapsed >= bodyReadDeadline.toMillis(),
            "the shared deadline must not fire BEFORE one bodyReadDeadline of "
            + "cumulative body-read; elapsed=" + elapsed + "ms, bodyReadDeadline="
            + bodyReadDeadline.toMillis() + "ms");
        assertTrue(elapsed < (long) (redirectCap + 1) * bodyReadDeadline.toMillis(),
            "the call must abort at ONE shared deadline, NOT after "
            + "(redirectCap + 1) x bodyReadDeadline of per-hop budgets; elapsed="
            + elapsed + "ms, (redirectCap+1)xdeadline="
            + ((redirectCap + 1) * bodyReadDeadline.toMillis()) + "ms");
    }

    // -----------------------------------------------------------------
    // M1-026 canonical-host pinning (Finding 2: INFO-LEAK / medium).
    // M1-025's pin map was keyed by raw URI.getHost(); the JDK's
    // HttpClient.send may pass a normalized form (case-fold,
    // trailing-dot strip, IDN ↔ punycode) to the resolver SPI,
    // causing pins.get(host) to miss and the resolver to fall
    // through to the unpinned builtin — defeating the rebind
    // defense. canonicalizeHost is invoked on BOTH install and
    // lookup sides so the keys match regardless of JDK choices.
    // -----------------------------------------------------------------

    @Test
    void pinSurvivesMixedCaseAndTrailingDot() throws Exception {
        server.createContext("/canonical", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        // Recording seam: captures the exact host string the wrapper
        // passed to validate-time DNS. Returns [127.0.0.1] for any
        // input so the connect succeeds against the loopback fixture.
        // If canonicalization were missing on the INSTALL side, the
        // recorded host would still carry case or the trailing dot.
        AtomicReference<String> recordedHost = new AtomicReference<>();
        AtomicInteger seamCalls = new AtomicInteger();
        Function<String, List<InetAddress>> recordingSeam = host -> {
            recordedHost.set(host);
            seamCalls.incrementAndGet();
            try {
                return List.of(InetAddress.getByName("127.0.0.1"));
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        };

        SsrfGuardedHttpClient client = new SsrfGuardedHttpClient(
            LoopbackPermittingBlocklist.create(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            10L * 1024,
            3,
            recordingSeam);

        // Mixed-case + trailing-dot. The .test TLD (RFC 6761) is
        // reserved as never-resolvable, so if canonicalization were
        // missing on the LOOKUP side the JDK builtin would attempt
        // to resolve it, fail, and the 200-status assertion would
        // fail on connection refused / UnknownHostException.
        HttpResponse<byte[]> response = client.get(
            URI.create("http://EVIL.Example.test.:" + port + "/canonical"));

        assertEquals(200, response.statusCode(),
            "wrapper must have connected to the pinned 127.0.0.1; if "
            + "canonicalization were missing on either side, the pin "
            + "would miss and the JDK builtin would try to resolve "
            + "the .test TLD which is RFC 6761-reserved as "
            + "never-resolvable");
        assertEquals(1, seamCalls.get(),
            "seam must be invoked exactly once (validate-time only)");
        assertEquals("evil.example.test", recordedHost.get(),
            "seam must have received the CANONICAL host form "
            + "(IDN.toASCII -> lowercase(Locale.ROOT) -> trailing-dot "
            + "stripped); a raw URI.getHost() would still carry case "
            + "or the trailing dot, indicating canonicalization was "
            + "missing on the install side");
    }

    // -----------------------------------------------------------------
    // IPv6 URL-literal canonicalization (C-IPV6-CANON). URI.getHost()
    // returns IPv6 literals bracketed ("[::1]"); IDN.toASCII rejects
    // the brackets, so canonicalizeHost must strip them, case-fold the
    // inner literal, and re-add the brackets for the dial and pin key.
    // -----------------------------------------------------------------

    @Test
    void canonicalizeHostStripsAndReAddsIpv6Brackets() {
        assertEquals("[::1]", SsrfGuardedHttpClient.canonicalizeHost("[::1]"),
            "bracketed IPv6 literal must round-trip through canonicalizeHost");
        assertEquals("[2606:4700::abcd]",
            SsrfGuardedHttpClient.canonicalizeHost("[2606:4700::ABCD]"),
            "IPv6 hex must be lowercased and the brackets preserved");
    }

    @Test
    void canonicalizeHostRejectsNonIpv6BracketedHost() {
        // M1-345: the bracket branch must validate the inner is a real IPv6
        // literal, mirroring the IDN branch's rejection of un-parseable hosts.
        // A bracketed hostname or a bracketed bare IPv4 is not an IPv6 literal;
        // Inet6Address.ofLiteral throws IllegalArgumentException for both, which
        // resolveAndValidate wraps as INVALID_HOST.
        assertThrows(IllegalArgumentException.class,
            () -> SsrfGuardedHttpClient.canonicalizeHost("[example.com]"),
            "a [...]-bracketed non-IPv6 host must be rejected, not re-bracketed");
        assertThrows(IllegalArgumentException.class,
            () -> SsrfGuardedHttpClient.canonicalizeHost("[1.2.3.4]"),
            "a [...]-bracketed bare IPv4 is not an IPv6 literal and must be rejected");
    }

    @Test
    void canonicalizeHostAcceptsBracketedIpv4MappedLiteral() {
        // M1-345 no-regression pin: URI.getHost() yields [::ffff:8.8.8.8] for an
        // IPv4-mapped destination, which today flows to the IpBlocklist
        // embedded-v4 decode. Inet6Address.ofLiteral accepts it as a valid IPv6
        // literal, so canonicalization still round-trips. (InetAddress.getByName
        // would have normalized it to an Inet4Address, which an
        // instanceof Inet6Address check would then have wrongly rejected.)
        assertEquals("[::ffff:8.8.8.8]",
            SsrfGuardedHttpClient.canonicalizeHost("[::ffff:8.8.8.8]"),
            "bracketed IPv4-mapped IPv6 literal must still canonicalize, not be rejected");
    }

    // -----------------------------------------------------------------
    // 3xx narrowing (C-SSRF-304). 304/305/306 are 3xx but are not
    // follow-able redirects; the wrapper must return them as the
    // terminal response rather than chasing a Location header.
    // -----------------------------------------------------------------

    @Test
    void status304IsNotTreatedAsRedirect() {
        server.createContext("/notmodified", exchange -> {
            // A Location header is present but must be ignored: 304 is
            // not a follow-able redirect.
            exchange.getResponseHeaders().add("Location", "/elsewhere");
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
        });

        SsrfGuardedHttpClient client = testModeClient();
        HttpResponse<byte[]> response = assertDoesNotThrow(
            () -> client.get(URI.create("http://127.0.0.1:" + port + "/notmodified")));
        assertEquals(304, response.statusCode(),
            "304 must be returned as the terminal response, not followed "
            + "(no \"redirect cap exceeded\" / \"missing Location\" error)");
    }

    // -----------------------------------------------------------------
    // Cross-origin header scrub (C-EXTRAHEADERS-REDIRECT, widened by
    // M1-277 M-S3). A redirect to a different host/port/scheme must NOT
    // replay ANY caller-supplied header injected for the original
    // origin — the cross-origin safe set is empty. Only the wrapper's
    // own Accept / User-Agent defaults ride every hop; caller headers
    // ride same-origin hops only.
    // -----------------------------------------------------------------

    @Test
    void crossOriginRedirectStripsCredentialHeaders() throws Exception {
        // Second loopback server on a DIFFERENT port: the redirect from
        // the first server to this one is cross-origin (port differs).
        HttpServer second = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int secondPort = second.getAddress().getPort();
        AtomicReference<String> authOnSecond = new AtomicReference<>("ABSENT-SENTINEL");
        AtomicReference<String> userAgentOnSecond = new AtomicReference<>();
        second.createContext("/end", exchange -> {
            authOnSecond.set(exchange.getRequestHeaders().getFirst("Authorization"));
            userAgentOnSecond.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        second.start();

        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location",
                "http://127.0.0.1:" + secondPort + "/end");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        try {
            SsrfGuardedHttpClient client = testModeClient();
            HttpResponse<byte[]> response = client.get(
                URI.create("http://127.0.0.1:" + port + "/start"),
                Map.of("Authorization", "Bearer secret-token"));

            assertEquals(200, response.statusCode(),
                "the cross-origin redirect must still be followed");
            assertNull(authOnSecond.get(),
                "Authorization must be stripped on the cross-origin redirect hop "
                + "(it was injected for the first origin only)");
            assertNotNull(userAgentOnSecond.get(),
                "non-credential headers (User-Agent) must still ride cross-origin");
        } finally {
            second.stop(0);
        }
    }

    @Test
    void extraHeaderDoesNotCrossOriginRedirect() throws Exception {
        // M1-277 (M-S3): a caller-supplied NON-credential header (Range)
        // must also be dropped on a cross-origin hop — the safe set is
        // empty, not "everything except the 3 credential headers".
        HttpServer second = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int secondPort = second.getAddress().getPort();
        AtomicReference<String> rangeOnSecond = new AtomicReference<>("ABSENT-SENTINEL");
        AtomicReference<String> userAgentOnSecond = new AtomicReference<>();
        second.createContext("/end", exchange -> {
            rangeOnSecond.set(exchange.getRequestHeaders().getFirst("Range"));
            userAgentOnSecond.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        second.start();

        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location",
                "http://127.0.0.1:" + secondPort + "/end");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        try {
            SsrfGuardedHttpClient client = testModeClient();
            HttpResponse<byte[]> response = client.get(
                URI.create("http://127.0.0.1:" + port + "/start"),
                Map.of("Range", "bytes=0-0"));

            assertEquals(200, response.statusCode(),
                "the cross-origin redirect must still be followed");
            assertNull(rangeOnSecond.get(),
                "Range must NOT cross origins — every caller-supplied "
                + "header is origin-scoped; the cross-origin safe set "
                + "is empty");
            assertNotNull(userAgentOnSecond.get(),
                "the wrapper's own User-Agent default must still ride "
                + "every hop");
        } finally {
            second.stop(0);
        }
    }

    @Test
    void extraHeadersRideSameOriginRedirects() throws Exception {
        // The complement that pins the contract's other half: a
        // SAME-origin redirect keeps caller-supplied headers (the scrub
        // fires on origin change only).
        AtomicReference<String> rangeOnTarget = new AtomicReference<>();
        server.createContext("/hopA", exchange -> {
            exchange.getResponseHeaders().add("Location", "/hopB");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/hopB", exchange -> {
            rangeOnTarget.set(exchange.getRequestHeaders().getFirst("Range"));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        SsrfGuardedHttpClient client = testModeClient();
        HttpResponse<byte[]> response = client.get(
            URI.create("http://127.0.0.1:" + port + "/hopA"),
            Map.of("Range", "bytes=0-0"));

        assertEquals(200, response.statusCode());
        assertEquals("bytes=0-0", rangeOnTarget.get(),
            "caller-supplied headers must ride same-origin redirect hops "
            + "(the cross-origin scrub must not fire on a same-origin hop)");
    }

    // -----------------------------------------------------------------
    // M1-277 (M-S1) shared HttpClient: one client per wrapper instance,
    // reused by every get() call. The lifecycle regression this guards:
    // if the client were closed after the first call (the old per-call
    // try-with-resources), the second get() on the same instance would
    // throw.
    // -----------------------------------------------------------------

    @Test
    void sharedClientServesSequentialGetCalls() throws Exception {
        server.createContext("/again", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        SsrfGuardedHttpClient client = testModeClient();
        URI target = URI.create("http://127.0.0.1:" + port + "/again");
        assertEquals(200, client.get(target).statusCode());
        assertEquals(200, client.get(target).statusCode(),
            "the second get() on the same wrapper instance must succeed "
            + "— the shared HttpClient is never closed between calls");
    }

    @Test
    void defaultConnectTimeoutIsFiveSeconds() {
        assertEquals(Duration.ofSeconds(5), SsrfGuardedHttpClient.DEFAULT_CONNECT_TIMEOUT);
    }

    // -----------------------------------------------------------------
    // T8 body-cap default reconciliation. DEFAULT_BODY_CAP must equal
    // the canonical 5 MiB value the design note documents
    // (docs/design/04-security.md §"Body size cap"), and the no-arg
    // constructor — which passes DEFAULT_BODY_CAP — must enforce that
    // exact cap: a body one byte over it is rejected. The no-arg client
    // blocks loopback, so the enforcement leg drives a loopback-permitting
    // client configured with the SAME DEFAULT_BODY_CAP the no-arg
    // constructor inherits.
    // -----------------------------------------------------------------

    @Test
    void noArgClientInheritsCanonicalBodyCapAndRejectsOneByteOver() throws IOException {
        assertEquals(5L * 1024 * 1024, SsrfGuardedHttpClient.DEFAULT_BODY_CAP,
            "the canonical default body cap is 5 MiB; the no-arg constructor "
            + "inherits exactly DEFAULT_BODY_CAP, which must match the "
            + "design-note infochat.fetch.max-body-bytes default");

        byte[] oneByteOverCap = new byte[(int) (SsrfGuardedHttpClient.DEFAULT_BODY_CAP + 1)];
        server.createContext("/atcap", exchange -> {
            exchange.sendResponseHeaders(200, oneByteOverCap.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(oneByteOverCap);
            } catch (IOException e) {
                // Expected once the wrapper trips the cap and closes the
                // connection mid-write — the wrapper has already raised.
            }
        });

        SsrfGuardedHttpClient client = new SsrfGuardedHttpClient(
            LoopbackPermittingBlocklist.create(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            Duration.ofMinutes(2),
            SsrfGuardedHttpClient.DEFAULT_BODY_CAP,
            3);

        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.get(URI.create("http://127.0.0.1:" + port + "/atcap")));
        assertEquals(SsrfPolicyException.Reason.BODY_CAP_EXCEEDED, ex.reason(),
            "a body one byte over the canonical cap must surface BODY_CAP_EXCEEDED");
    }

    // -----------------------------------------------------------------
    // T15 isCrossOrigin host canonicalization. The cross-origin check
    // must compare canonicalized hosts (the same IDN/case/trailing-dot
    // fold the pin map keys by) rather than raw getHost(), so a redirect
    // to the SAME host differing only by case or a trailing dot is not
    // misread as cross-origin and does not spuriously scrub credentials.
    // A genuinely different host must still read as cross-origin so the
    // scrub fires where it should.
    // -----------------------------------------------------------------

    @Test
    void sameHostDifferingByCaseOrTrailingDotIsNotCrossOrigin() {
        assertFalse(SsrfGuardedHttpClient.isCrossOrigin(
                URI.create("https://Example.COM/a"),
                URI.create("https://example.com./b")),
            "same host differing only by case + trailing dot must NOT be "
            + "treated as cross-origin (raw getHost() comparison would "
            + "spuriously scrub credentials)");
        assertTrue(SsrfGuardedHttpClient.isCrossOrigin(
                URI.create("https://example.com/a"),
                URI.create("https://evil.example.org/b")),
            "a genuinely different host must still be treated as cross-origin "
            + "so the credential scrub fires where it should");
    }

    // -----------------------------------------------------------------
    // U-09 proxy posture. A default-built HttpClient falls through to
    // the JDK's non-exposed default proxy selector, which honors
    // http.proxyHost / https.proxyHost / socksProxyHost. An ambient
    // proxy would re-resolve the target host itself, bypassing the
    // validated peer IP and the DNS pin (the rebind defense). The
    // guarded client must pin NO_PROXY so guarded egress is always a
    // direct dial.
    // -----------------------------------------------------------------

    @Test
    void guardedClientDisablesAmbientProxy() {
        SsrfGuardedHttpClient client = new SsrfGuardedHttpClient();
        assertEquals(Optional.of(HttpClient.Builder.NO_PROXY), client.httpClient().proxy(),
            "the guarded client must be built with NO_PROXY so ambient JVM "
            + "proxy settings cannot route guarded egress through a proxy that "
            + "re-resolves the target and voids the DNS pin");
    }

    // -----------------------------------------------------------------
    // U-37 host-interface enumeration failure. HostInterfaceSet
    // .enumerate() raises IllegalStateException when the OS interface
    // enumeration itself fails (SocketException). That must surface
    // through get()'s documented SsrfPolicyException / IOException
    // contract, not escape as a raw RuntimeException. A blocklist whose
    // per-call host-interface supplier throws simulates the failure
    // deterministically (a real SocketException is not reproducible).
    // -----------------------------------------------------------------

    @Test
    void hostInterfaceEnumerationFailureSurfacesAsPolicyException() {
        IpBlocklist enumerationFails = new IpBlocklist(() -> {
            throw new IllegalStateException("could not enumerate host network interfaces");
        });
        SsrfGuardedHttpClient client = new SsrfGuardedHttpClient(
            enumerationFails,
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            Duration.ofMinutes(2),
            10L * 1024,
            3,
            host -> {
                try {
                    return List.of(InetAddress.getByName("192.0.2.1"));
                } catch (UnknownHostException e) {
                    throw new IllegalStateException(e);
                }
            });

        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.get(URI.create("http://feed.example.test/")));
        assertEquals(SsrfPolicyException.Reason.HOST_INTERFACE_UNAVAILABLE, ex.reason(),
            "a host-interface enumeration failure must surface as "
            + "HOST_INTERFACE_UNAVAILABLE, not escape as IllegalStateException");
    }

    // -----------------------------------------------------------------
    // U-38 interrupt propagation. get() declares throws
    // InterruptedException; an interrupt while the wrapper is blocked
    // reading the body must surface as InterruptedException, not be
    // masked as IOException, so a caller that cancels a fetch by
    // interrupting its thread sees the cancellation.
    // -----------------------------------------------------------------

    @Test
    void interruptDuringBodyReadPropagatesInterruptedException() throws Exception {
        server.createContext("/stall", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, 5_000_000);
            OutputStream out = exchange.getResponseBody();
            try {
                // Send headers + a few bytes so the wrapper enters the
                // body-read phase, then stall well past the interrupt.
                out.write(new byte[16]);
                out.flush();
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // Expected once the wrapper tears the connection down.
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // Connection already gone.
                }
            }
        });

        // Long read timeout + deadline so neither the per-read watchdog
        // nor the total deadline can fire before the interrupt does.
        SsrfGuardedHttpClient client = new SsrfGuardedHttpClient(
            LoopbackPermittingBlocklist.create(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            10L * 1024 * 1024,
            3);

        AtomicReference<Throwable> caught = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                client.get(URI.create("http://127.0.0.1:" + port + "/stall"));
            } catch (Throwable t) {
                caught.set(t);
            }
        });
        worker.start();
        // Let the worker send, receive headers, and block in the body
        // read before interrupting it.
        Thread.sleep(1_000);
        worker.interrupt();
        worker.join(10_000);

        assertFalse(worker.isAlive(),
            "the worker must finish promptly after the interrupt");
        assertNotNull(caught.get(), "the interrupted get() must have thrown");
        assertTrue(caught.get() instanceof InterruptedException,
            "an interrupt during the body read must propagate as "
            + "InterruptedException, not be masked as IOException; got: "
            + caught.get());
    }

    // -----------------------------------------------------------------
    // U-40 / M1-355 bounded redirect-body discard. A followed redirect
    // hop's body is drained through discardBounded — the SIZE- and
    // TIME-bounded sink-parameterized sibling of readBounded, sharing the
    // single supervisedDrain loop. An over-cap redirect body is a policy
    // violation: discardBounded THROWS BODY_CAP_EXCEEDED (matching the
    // terminal readBounded path) rather than reading on unbounded, and it
    // supervises every read through the same per-read watchdog + total
    // bodyReadDeadline — so a slow-dribble redirect body that stays under
    // the size cap still cannot hold the fetcher thread past the deadline.
    // -----------------------------------------------------------------

    @Test
    void discardBoundedThrowsBodyCapExceededOnOverCap()
            throws IOException, InterruptedException {
        // M1-355: an over-cap followed-redirect body is a policy violation, not
        // a stop condition. discardBounded must THROW BODY_CAP_EXCEEDED (matching
        // the terminal readBounded path) rather than returning after a bounded
        // read — the prior behaviour broke the loop and leaned on close().
        int streamSize = 1024 * 1024;
        AtomicInteger bytesRead = new AtomicInteger();
        InputStream oversizedBody = new InputStream() {
            private int pos;

            @Override
            public int read() {
                if (pos >= streamSize) {
                    return -1;
                }
                pos++;
                bytesRead.incrementAndGet();
                return 'A';
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (pos >= streamSize) {
                    return -1;
                }
                int n = Math.min(len, streamSize - pos);
                pos += n;
                bytesRead.addAndGet(n);
                return n;
            }
        };

        // testModeClient bodyCap is 10 KiB; the 1 MiB body exceeds it. Generous
        // readTimeout (5s) and deadline (2min) mean the in-memory fast reads
        // never trip the time watchdog — only the size cap aborts the drain.
        SsrfGuardedHttpClient client = testModeClient();
        // M1-470: discardBounded now takes the call-wide bodyReadStartNanos
        // get() captures before the redirect loop; a direct unit call supplies
        // "now" to reproduce the prior at-drain-entry budget origin.
        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.discardBounded(oversizedBody, System.nanoTime()));
        assertEquals(SsrfPolicyException.Reason.BODY_CAP_EXCEEDED, ex.reason(),
            "an over-cap redirect-body drain must abort with BODY_CAP_EXCEEDED, "
            + "matching the terminal readBounded path");
        assertTrue(bytesRead.get() < streamSize,
            "the drain must NOT read the whole oversized body before aborting — "
            + "it stops within a buffer of the cap; bytesRead=" + bytesRead.get()
            + " of " + streamSize);
    }

    @Test
    void discardBoundedAbortsWhenRedirectBodyStallsPastReadTimeout() {
        // A hostile redirect whose body stalls indefinitely (under the size
        // cap, so a size-only drain would block forever). The per-read
        // watchdog must fire and abort with the typed timeout/deadline
        // reason instead of holding the fetcher thread — the slow-dribble
        // DoS the size cap alone does NOT close.
        SsrfGuardedHttpClient client = new SsrfGuardedHttpClient(
            LoopbackPermittingBlocklist.create(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofMillis(200),
            Duration.ofSeconds(1),
            10L * 1024,
            3);
        InputStream stallingBody = new InputStream() {
            @Override
            public int read() throws IOException {
                return blockUntilCancelled();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return blockUntilCancelled();
            }

            private int blockUntilCancelled() throws IOException {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("read interrupted", e);
                }
                return -1;
            }
        };

        // M1-470: supply "now" as the call-wide bodyReadStartNanos for this
        // direct unit call (get() captures it before the redirect loop).
        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.discardBounded(stallingBody, System.nanoTime()));
        // The very first read stalls; its per-read watchdog (readTimeout 200ms)
        // fires at ~200ms, far below the 1s body-read deadline, so the typed
        // reason is deterministically BODY_READ_TIMEOUT. The deadline branch is
        // unreachable under this config — assert the exact reason, not a
        // disjunction that would also pass if the classification regressed.
        assertEquals(SsrfPolicyException.Reason.BODY_READ_TIMEOUT, ex.reason(),
            "a stalled redirect-body drain must abort with the per-read timeout "
            + "reason (readTimeout fires before the deadline); got: " + ex.reason());
    }

    @Test
    void overCapFollowedRedirectAbortsWithBodyCapExceeded() {
        // M1-355: a within-cap 3xx hop (redirectCount 1 <= redirectCap 3) whose
        // body exceeds bodyCap. The wrapper drains the followed-redirect body
        // through discardBounded, which must abort the whole get() with
        // BODY_CAP_EXCEEDED — the over-cap redirect body is never read unbounded.
        // The throw fires before the Location header is parsed, so /never-reached
        // is never dialed.
        byte[] overCapBody = new byte[2 * 1024];
        for (int i = 0; i < overCapBody.length; i++) {
            overCapBody[i] = (byte) ('A' + (i % 26));
        }
        server.createContext("/redir", exchange -> {
            exchange.getResponseHeaders().add("Location", "/never-reached");
            exchange.sendResponseHeaders(302, overCapBody.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(overCapBody);
            }
        });

        SsrfGuardedHttpClient client = new SsrfGuardedHttpClient(
            LoopbackPermittingBlocklist.create(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofMinutes(2),
            1024L,
            3);

        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.get(URI.create("http://127.0.0.1:" + port + "/redir")));
        assertEquals(SsrfPolicyException.Reason.BODY_CAP_EXCEEDED, ex.reason(),
            "an over-cap followed-redirect body must abort the call with "
            + "BODY_CAP_EXCEEDED, not be read unbounded");
    }

    @Test
    void ofInputStreamCloseDoesNotDrainPartiallyReadBody() throws Exception {
        // M1-355 item 1: pin the JDK 25 HttpResponse.BodyHandlers.ofInputStream()
        // close()-after-partial-read behaviour empirically — the fact the wrapper's
        // close()/discardBounded comments could not establish by inspection. A
        // followed (within-cap) 3xx hop serves far more than a body cap's worth of
        // bytes; we read a little, close the stream, and observe via the server how
        // much it managed to write. If close() drained, the server completes the
        // whole body; if close() cancels, TCP backpressure stalls the server write
        // once the buffers fill and it fails with a connection reset well before the
        // end. totalSize is far larger than any autotuned socket buffer so the two
        // outcomes are unambiguous; the un-drained branch never actually transfers
        // totalSize bytes, so the large size costs nothing.
        long totalSize = 64L * 1024 * 1024;
        AtomicLong serverBytesWritten = new AtomicLong();
        AtomicReference<String> serverOutcome = new AtomicReference<>("incomplete");
        CountDownLatch serverDone = new CountDownLatch(1);
        server.createContext("/bigredirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/next");
            exchange.sendResponseHeaders(302, totalSize);
            byte[] chunk = new byte[64 * 1024];
            try (OutputStream out = exchange.getResponseBody()) {
                long written = 0;
                while (written < totalSize) {
                    out.write(chunk);
                    written += chunk.length;
                    serverBytesWritten.set(written);
                }
                serverOutcome.set("completed");
            } catch (IOException e) {
                // Client cancelled the body before draining: the connection is
                // reset, so the proactive write fails partway through.
                serverOutcome.set("reset");
            } finally {
                serverDone.countDown();
            }
        });

        HttpClient raw = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(HttpClient.Builder.NO_PROXY)
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/bigredirect"))
            .GET()
            .build();
        HttpResponse<InputStream> response =
            raw.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(302, response.statusCode(),
            "the probe fixture must return a followable 3xx hop");
        InputStream body = response.body();
        int firstRead = body.read(new byte[1024]);
        assertTrue(firstRead > 0, "the probe must read at least one body byte");
        body.close();

        assertTrue(serverDone.await(30, TimeUnit.SECONDS),
            "the server write must finish (complete or reset) within the timeout");

        // RECORDED FACT (this assertion IS the record, mirrored in the
        // SsrfGuardedHttpClient close()-drain comments): JDK 25 close() on a
        // partially-read ofInputStream() body does NOT drain the remainder — it
        // cancels the subscription and resets the connection, so the server
        // observes far fewer than totalSize bytes written. discardBounded's
        // bounded drain therefore exists for connection REUSE on followed hops,
        // not as a workaround for an unbounded close()-drain.
        assertEquals("reset", serverOutcome.get(),
            "JDK 25 ofInputStream() close() must NOT drain a partially-read body "
            + "(observed outcome=" + serverOutcome.get() + ", bytesWritten="
            + serverBytesWritten.get() + " of " + totalSize + ")");
        assertTrue(serverBytesWritten.get() < totalSize,
            "close() must abandon the connection before the full body is written; "
            + "bytesWritten=" + serverBytesWritten.get() + " of " + totalSize);
    }

    private SsrfGuardedHttpClient testModeClient() {
        return new SsrfGuardedHttpClient(
            LoopbackPermittingBlocklist.create(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            Duration.ofMinutes(2),
            10L * 1024,
            3);
    }
}
