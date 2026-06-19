package app.zcat.infochat.ssrf;

import com.sun.net.httpserver.HttpServer;
import app.zcat.infochat.ssrf.PinnedDnsResolver.Provider;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient.PinnedDial;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient.SsrfPolicyException;
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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * End-to-end concurrency contract of the wrapper through its public
 * API: outbound calls to DIFFERENT hosts no longer serialize on
 * JVM-wide state, and a WebSocket pin coexists with a concurrent HTTP
 * fetch. Assertions are latch/ordering-based against an in-process
 * {@code com.sun.net.httpserver.HttpServer} bound to {@code 127.0.0.1}
 * (the {@link SsrfGuardedHttpClientTest} fixture pattern) — no
 * wall-clock timing. The concurrent fetch runs on a worker thread
 * because the old serialization point was a reentrant lock: a
 * same-thread fetch would re-acquire it and prove nothing. Hostnames
 * are unique RFC 6761 {@code .invalid} names per test so the JDK's
 * positive lookup cache cannot leak resolutions between tests.
 */
class SsrfGuardedHttpClientConcurrencyTest {

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
    void fetchToDifferentHostCompletesWhilePinnedDialHeldOpen() throws Exception {
        String dialHost = "held-open-dial-a.invalid";
        String fetchHost = "concurrent-fetch-b.invalid";
        addOkContext("/concurrent-fetch");
        SsrfGuardedHttpClient client = loopbackClient(fixedResolution(Map.of(
            dialHost, "127.0.0.1",
            fetchHost, "127.0.0.1")));

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (PinnedDial dial = client.checkAndPinForWebSocket(
                URI.create("wss://" + dialHost + "/relay"))) {
            // The open dial is exactly the "slow connect in progress"
            // state: the pin for dialHost is installed and stays held
            // for the whole block. A fetch to a DIFFERENT host must
            // complete while it is held; the future's bound is a
            // failure cutoff, not a timing assertion — a regression to
            // cross-host serialization parks the fetch until the dial
            // closes, which never happens inside this block.
            Future<HttpResponse<byte[]>> fetch = worker.submit(() ->
                client.get(URI.create(
                    "http://" + fetchHost + ":" + port + "/concurrent-fetch")));
            HttpResponse<byte[]> response = fetch.get(10, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode(),
                "a fetch to a different host must complete while the pinned dial "
                + "is still open, without waiting for it");
        } finally {
            worker.shutdownNow();
        }
        assertFalse(Provider.activePinsSnapshot().containsKey(dialHost),
            "closing the dial must release its host's pin");
    }

    @Test
    void wsPinAndHttpFetchRunSimultaneously() throws Exception {
        String wsHost = "ws-simultaneous-a.invalid";
        String httpHost = "http-simultaneous-b.invalid";
        addOkContext("/simultaneous");
        SsrfGuardedHttpClient client = loopbackClient(fixedResolution(Map.of(
            wsHost, "127.0.0.1",
            httpHost, "127.0.0.1")));

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (PinnedDial dial = client.checkAndPinForWebSocket(
                URI.create("wss://" + wsHost + "/relay"))) {
            Future<HttpResponse<byte[]>> fetch = worker.submit(() ->
                client.get(URI.create(
                    "http://" + httpHost + ":" + port + "/simultaneous")));
            HttpResponse<byte[]> response = fetch.get(10, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode(),
                "the HTTP fetch must succeed while the WS pin is held");

            // The WS pin survived the concurrent HTTP fetch's per-hop
            // pin/release cycle: still installed, still serving exactly
            // the dial's validated set (transport-agnostic gating, D38).
            assertEquals(dial.addresses(), Provider.activePinsSnapshot().get(wsHost),
                "the WS pin must remain installed and valid throughout the concurrent fetch");
        } finally {
            worker.shutdownNow();
        }
        assertFalse(Provider.activePinsSnapshot().containsKey(wsHost),
            "closing the dial must release the WS host's pin");
    }

    @Test
    void throwOnRedirectHopReleasesPriorHopPin() {
        // First hop dials the loopback fixture; the redirect target
        // re-validates to a blocked address (169.254.169.254) and the
        // pipeline throws while hop 1's pin is held. The finally arm
        // must release it — a stale pin would survive as JVM-wide
        // resolver state.
        String firstHost = "redirect-throw-first-hop.invalid";
        String blockedHost = "redirect-throw-blocked-target.invalid";
        server.createContext("/bounce", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://" + blockedHost + "/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        SsrfGuardedHttpClient client = loopbackClient(fixedResolution(Map.of(
            firstHost, "127.0.0.1",
            blockedHost, "169.254.169.254")));

        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> client.get(URI.create("http://" + firstHost + ":" + port + "/bounce")));

        assertEquals(SsrfPolicyException.Reason.BLOCKED_IP, ex.reason(),
            "the redirect target must be rejected on the second hop's re-validation");
        assertFalse(Provider.activePinsSnapshot().containsKey(firstHost),
            "hop 1's pin must not survive the hop 2 policy throw");
        assertFalse(Provider.activePinsSnapshot().containsKey(blockedHost),
            "the rejected redirect target must never have been pinned");
    }

    private void addOkContext(String path) {
        server.createContext(path, exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    private static SsrfGuardedHttpClient loopbackClient(
            Function<String, List<InetAddress>> resolverSeam) {
        return new SsrfGuardedHttpClient(
            LoopbackPermittingBlocklist.create(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            Duration.ofMinutes(2),
            10L * 1024,
            3,
            resolverSeam);
    }

    /**
     * Validation-time seam with a fixed host → IP-literal table; an
     * unexpected host fails the test loudly rather than resolving.
     */
    private static Function<String, List<InetAddress>> fixedResolution(
            Map<String, String> hostToIpLiteral) {
        return host -> {
            String ipLiteral = hostToIpLiteral.get(host);
            if (ipLiteral == null) {
                throw new IllegalStateException("unexpected host in test seam: " + host);
            }
            try {
                return List.of(InetAddress.getByName(ipLiteral));
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        };
    }
}
