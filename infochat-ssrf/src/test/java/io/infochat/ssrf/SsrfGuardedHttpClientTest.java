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
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

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
                1024L,
                3));
        assertTrue(ex.getMessage().contains("timeout must be configured"),
            "zero request-timeout must be rejected with the literal "
            + "\"timeout must be configured\" prefix");
    }

    private SsrfGuardedHttpClient testModeClient() {
        return new SsrfGuardedHttpClient(
            new LoopbackPermitting(),
            Duration.ofSeconds(2),
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
