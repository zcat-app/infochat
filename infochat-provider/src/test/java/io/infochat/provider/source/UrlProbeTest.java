package io.infochat.provider.source;

import com.sun.net.httpserver.HttpServer;
import io.infochat.provider.bundle.BundleKeys;
import io.infochat.provider.source.UrlProbe.ProbeResult;
import io.infochat.ssrf.IpBlocklist;
import io.infochat.ssrf.SsrfGuardedHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 unit tests for {@link UrlProbe}. Mirrors the
 * {@code SsrfGuardedHttpClientTest} fixture pattern — an in-process
 * {@link com.sun.net.httpserver.HttpServer com.sun.net.httpserver.HttpServer}
 * bound to {@code 127.0.0.1:0} with per-test contexts emitting the
 * status code and {@code Content-Type} each acceptance row pins. The
 * client uses a {@link LoopbackPermitting} blocklist so the localhost
 * fixture can be dialed; the SSRF-rejection test swaps in the strict
 * production blocklist to verify the wrapper still rejects loopback
 * by default and the probe surfaces the
 * {@link BundleKeys#ERROR_ADD_SOURCE_URL_BLOCKED_SSRF} key.
 *
 * <p>Acceptance row map:
 * <ul>
 *   <li>(a) 206/200 + {@code application/rss+xml} → SUCCESS with the
 *       detected content-type echoed back.</li>
 *   <li>(b) 4xx / 5xx →
 *       {@link BundleKeys#ERROR_ADD_SOURCE_URL_UNREACHABLE}.</li>
 *   <li>(c) SSRF rejection (loopback under strict blocklist) →
 *       {@link BundleKeys#ERROR_ADD_SOURCE_URL_BLOCKED_SSRF}.</li>
 *   <li>(d) timeout (server sleeps past the request timeout) →
 *       {@link BundleKeys#ERROR_ADD_SOURCE_URL_TIMEOUT}.</li>
 * </ul>
 */
class UrlProbeTest {

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

    // (a) Range-GET returning 206 with Content-Type: application/rss+xml
    @Test
    void rangeGet206WithRssContentTypeReportsSuccess() throws Exception {
        server.createContext("/feed.xml", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
            byte[] body = new byte[] { (byte) 'x' };
            // 206 Partial Content — the server honored the Range header.
            exchange.sendResponseHeaders(206, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        UrlProbe probe = new UrlProbe(loopbackPermittingClient());
        ProbeResult result = probe.probe(URI.create("http://127.0.0.1:" + port + "/feed.xml"));

        assertTrue(result.ok(), "206 + rss Content-Type must report SUCCESS");
        assertEquals(206, result.httpStatus());
        assertTrue(result.contentType().isPresent(),
                "Content-Type header must be surfaced for caller's confirm-or-contradict check");
        assertEquals("application/rss+xml", result.contentType().get());
    }

    @Test
    void rangeGet200IgnoringRangeWithRssContentTypeReportsSuccess() throws Exception {
        server.createContext("/full.xml", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
            byte[] body = "<rss/>".getBytes();
            // 200 OK — server ignored the Range header and returned full body.
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        UrlProbe probe = new UrlProbe(loopbackPermittingClient());
        ProbeResult result = probe.probe(URI.create("http://127.0.0.1:" + port + "/full.xml"));

        assertTrue(result.ok(), "200 OK is still SUCCESS (server may ignore Range)");
        assertEquals(200, result.httpStatus());
        assertEquals("application/rss+xml", result.contentType().orElseThrow());
    }

    // (b) 4xx / 5xx → url_unreachable
    @Test
    void rangeGet404ReportsFailureWithUrlUnreachableBundleKey() {
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        UrlProbe probe = new UrlProbe(loopbackPermittingClient());
        ProbeResult result = probe.probe(URI.create("http://127.0.0.1:" + port + "/missing"));

        assertFalse(result.ok(), "404 must report FAILURE");
        assertEquals(BundleKeys.ERROR_ADD_SOURCE_URL_UNREACHABLE, result.failureBundleKey());
    }

    @Test
    void rangeGet500ReportsFailureWithUrlUnreachableBundleKey() {
        server.createContext("/oops", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        UrlProbe probe = new UrlProbe(loopbackPermittingClient());
        ProbeResult result = probe.probe(URI.create("http://127.0.0.1:" + port + "/oops"));

        assertFalse(result.ok(), "500 must report FAILURE");
        assertEquals(BundleKeys.ERROR_ADD_SOURCE_URL_UNREACHABLE, result.failureBundleKey());
    }

    // (c) SSRF rejection
    @Test
    void ssrfRejectionPropagatesAsUrlBlockedSsrf() {
        // Strict blocklist (no-arg constructor) refuses 127.0.0.1.
        // The HttpServer is bound to 127.0.0.1; the request must never
        // reach the wire — the wrapper raises SsrfPolicyException("blocked IP ...")
        // and UrlProbe maps it to ERROR_ADD_SOURCE_URL_BLOCKED_SSRF.
        UrlProbe probe = new UrlProbe(new SsrfGuardedHttpClient());
        ProbeResult result = probe.probe(
                URI.create("http://127.0.0.1:" + port + "/never-dialed"));

        assertFalse(result.ok(), "loopback under strict blocklist must FAIL");
        assertEquals(BundleKeys.ERROR_ADD_SOURCE_URL_BLOCKED_SSRF, result.failureBundleKey());
    }

    // (d) timeout
    @Test
    void requestTimeoutPropagatesAsUrlTimeout() {
        // Server sleeps 2 seconds before sending headers. Client's
        // requestTimeout is 300ms; HttpClient.send raises
        // HttpTimeoutException long before the server replies.
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        SsrfGuardedHttpClient slowTimingClient = new SsrfGuardedHttpClient(
                new LoopbackPermitting(),
                /* connectTimeout */ Duration.ofMillis(500),
                /* requestTimeout */ Duration.ofMillis(300),
                /* readTimeout    */ Duration.ofMillis(500),
                /* bodyCap        */ 10L * 1024,
                /* redirectCap    */ 3);
        UrlProbe probe = new UrlProbe(slowTimingClient);

        ProbeResult result = probe.probe(URI.create("http://127.0.0.1:" + port + "/slow"));

        assertFalse(result.ok(), "request that exceeds the timeout must FAIL");
        assertEquals(BundleKeys.ERROR_ADD_SOURCE_URL_TIMEOUT, result.failureBundleKey());
    }

    private SsrfGuardedHttpClient loopbackPermittingClient() {
        return new SsrfGuardedHttpClient(
                new LoopbackPermitting(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                10L * 1024,
                3);
    }

    /**
     * Test-only blocklist subclass that permits loopback addresses so
     * the in-process {@link HttpServer} fixture can be dialed; every
     * other range still routes through the strict
     * {@link IpBlocklist}. Mirrors the same-named inner class in
     * {@code SsrfGuardedHttpClientTest}.
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
