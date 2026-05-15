package io.infochat.ssrf;

import com.sun.net.httpserver.HttpServer;
import io.infochat.ssrf.SsrfGuardedHttpClient.SsrfPolicyException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 unit tests for {@link SsrfGuardedHttpClient}: each
 * policy is exercised against an in-process
 * {@link com.sun.net.httpserver.HttpServer com.sun.net.httpserver.HttpServer}
 * fixture bound to {@code 127.0.0.1}. Because the strict
 * {@link IpBlocklist} blocks {@code 127.0.0.0/8}, every test that
 * needs to reach the loopback fixture constructs the wrapper via the
 * package-private constructor with a {@link LoopbackPermitting}
 * blocklist. The strict-mode tests use the no-arg constructor.
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
        assertTrue(ex.getMessage().contains("scheme not allowed"),
            "non-http(s) scheme must be rejected with the literal "
            + "\"scheme not allowed\" prefix");
    }

    @Test
    void rejectsWebsocketSchemeForNow() {
        // ws/wss are spec-allowed but carved out of this ticket per
        // out_of_scope. The wrapper must reject them with the same
        // literal so the future StreamSource ticket can widen the
        // allowlist without contradicting committed test text.
        SsrfGuardedHttpClient client = testModeClient();
        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.get(URI.create("wss://example.com/relay")));
        assertTrue(ex.getMessage().contains("scheme not allowed"),
            "ws/wss is rejected by this wrapper; the StreamSource "
            + "ticket lands a separate WebSocket-aware wrapper");
    }

    @Test
    void rejectsUserinfoSegment() {
        SsrfGuardedHttpClient client = testModeClient();
        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.get(URI.create("https://user:pw@example.com/")));
        assertTrue(ex.getMessage().contains("userinfo segment not allowed"),
            "URIs carrying user:pw@ must be rejected with the literal "
            + "\"userinfo segment not allowed\" prefix");
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
        assertTrue(ex.getMessage().contains("blocked IP"),
            "strict-mode wrapper must reject 127.0.0.1 with the literal "
            + "\"blocked IP\" prefix");
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
            new LoopbackPermitting(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            1024L,
            3);

        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.get(URI.create("http://127.0.0.1:" + port + "/big")));
        assertTrue(ex.getMessage().contains("response body exceeded"),
            "oversize body must surface the literal \"response body exceeded\" prefix");
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
        assertTrue(ex.getMessage().contains("redirect cap exceeded"),
            "exceeding the redirect cap must surface the literal "
            + "\"redirect cap exceeded\" prefix");
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
        assertTrue(ex.getMessage().contains("blocked IP"),
            "redirect target whose IP is on the strict blocklist must "
            + "be rejected on the second hop's re-resolution");
    }

    @Test
    void constructorRejectsNullTimeout() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> new SsrfGuardedHttpClient(
                new IpBlocklist(),
                null,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                1024L,
                3));
        assertTrue(ex.getMessage().contains("timeout must be configured"),
            "null connect-timeout must be rejected with the literal "
            + "\"timeout must be configured\" prefix");
    }

    @Test
    void constructorRejectsZeroTimeout() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> new SsrfGuardedHttpClient(
                new IpBlocklist(),
                Duration.ofSeconds(1),
                Duration.ZERO,
                Duration.ofSeconds(1),
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
            new LoopbackPermitting(),
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

        SsrfGuardedHttpClient client = new SsrfGuardedHttpClient(
            new LoopbackPermitting(),
            Duration.ofSeconds(2),
            // Long request-timeout so the failure cannot be the
            // request-level timeout instead of the per-read watchdog.
            Duration.ofSeconds(30),
            readTimeout,
            10L * 1024 * 1024,
            3);

        long start = System.currentTimeMillis();
        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.get(URI.create("http://127.0.0.1:" + port + "/slow")));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(ex.getMessage().startsWith("body read timeout"),
            "must surface the literal \"body read timeout\" prefix; "
            + "got: " + ex.getMessage());
        assertTrue(elapsed < (readTimeout.toMillis() + 500),
            "timeout must fire within readTimeout + 500ms; elapsed="
            + elapsed + "ms (readTimeout=" + readTimeout.toMillis() + "ms)");
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
            new LoopbackPermitting(),
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

        assertTrue(ex.getMessage().startsWith("body read deadline exceeded"),
            "must surface the literal \"body read deadline exceeded\" "
            + "prefix; got: " + ex.getMessage());
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
            new LoopbackPermitting(),
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

    private SsrfGuardedHttpClient testModeClient() {
        return new SsrfGuardedHttpClient(
            new LoopbackPermitting(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            10L * 1024,
            3);
    }

    /**
     * Test-only {@link IpBlocklist} subclass that permits 127.0.0.1
     * so the localhost {@link com.sun.net.httpserver.HttpServer
     * com.sun.net.httpserver.HttpServer} fixture can be dialed,
     * while still blocking every other range (e.g.
     * {@code 169.254.169.254}, which the redirect re-validation
     * test depends on).
     */
    private static final class LoopbackPermitting extends IpBlocklist {

        @Override
        public boolean isBlocked(InetAddress addr) {
            if (addr.isLoopbackAddress()) {
                return false;
            }
            return super.isBlocked(addr);
        }
    }
}
