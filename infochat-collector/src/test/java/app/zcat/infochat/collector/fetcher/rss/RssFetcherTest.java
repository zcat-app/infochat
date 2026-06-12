package app.zcat.infochat.collector.fetcher.rss;

import com.sun.net.httpserver.HttpServer;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.ssrf.IpBlocklist;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient.SsrfPolicyException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 unit test for {@link RssFetcher}. Spins up an
 * in-process {@link HttpServer} on a localhost ephemeral port, serves
 * the RSS 2.0 fixture, and exercises the Fetcher end-to-end. No
 * Quarkus container — the Fetcher has no CDI dependencies in v1.
 *
 * <p>Because the strict production {@link IpBlocklist} refuses to dial
 * {@code 127.0.0.0/8}, every test that needs to reach the loopback
 * fixture constructs the {@link RssFetcher} with a test-mode
 * {@link SsrfGuardedHttpClient} configured with a
 * {@link LoopbackPermittingBlocklist}. The carve-out is a deliberate
 * API surface — passing a non-default {@link IpBlocklist} is the only
 * way to engage it, and accidentally enabling it in production
 * requires writing visibly non-default code.
 */
class RssFetcherTest {

    private static final Path RSS_FIXTURE =
        Paths.get("src/test/resources/fixtures/rss/rss20-sample.xml");

    private HttpServer server;

    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/feed.xml", exchange -> {
            byte[] body = Files.readAllBytes(RSS_FIXTURE);
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/notfound", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchReturnsOneNormalizedPostPerItem() {
        Instant before = Instant.now();
        List<NormalizedPost> posts =
            testModeFetcher().fetch(1L, "http://127.0.0.1:" + port + "/feed.xml");
        Instant after = Instant.now();

        assertEquals(3, posts.size(),
            "the fixture has three <item> elements; fetch returns one NormalizedPost per item");

        for (NormalizedPost post : posts) {
            assertEquals(1L, post.dispatchKey(),
                "every returned post must carry the caller-supplied sourceId");
            assertNotNull(post.upstreamIdentifier(),
                "upstreamIdentifier must be non-null per SPI contract");
            assertNotNull(post.fetchedAt(),
                "fetchedAt must be non-null per SPI contract");

            // fetchedAt must lie within the test wall-clock window plus
            // a small slack for the HTTP round-trip. Per DoD, within 5s.
            Duration delta = Duration.between(before, post.fetchedAt());
            assertTrue(!delta.isNegative(),
                "fetchedAt must be >= the wall clock before fetch()");
            Duration window = Duration.between(before, after).plusSeconds(5);
            assertTrue(delta.compareTo(window) <= 0,
                "fetchedAt must be within 5 seconds of the wall clock");
        }
    }

    @Test
    void fetchSharesOneFetchedAtAcrossAllPosts() {
        List<NormalizedPost> posts =
            testModeFetcher().fetch(7L, "http://127.0.0.1:" + port + "/feed.xml");

        Instant shared = posts.get(0).fetchedAt();
        for (NormalizedPost post : posts) {
            assertEquals(shared, post.fetchedAt(),
                "every post from one fetch() invocation shares the same fetchedAt — "
                + "post partition-key invariant per docs/spec/schema.md");
        }
    }

    @Test
    void fetchRaisesOnNon2xxResponse() {
        RssFetcher.RssFetchException ex = assertThrows(
            RssFetcher.RssFetchException.class,
            () -> testModeFetcher().fetch(1L, "http://127.0.0.1:" + port + "/notfound"));

        assertTrue(ex.getMessage().contains("404"),
            "exception must surface the upstream HTTP status code");
    }

    @Test
    void fetchRejectsIdentifierWithEmbeddedCredentials() {
        // Identifier carries user:secret@ — the SsrfGuardedHttpClient's
        // userinfo gate must reject before the dial. The raised
        // exception must NOT contain the secret token; redaction
        // applies whether the policy raised first or the Fetcher's
        // own catch interpolated the identifier.
        String identifier = "https://user:secret@127.0.0.1:" + port + "/feed.xml";
        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> testModeFetcher().fetch(1L, identifier));

        assertTrue(ex.getMessage().contains("userinfo segment not allowed"),
            "wrapper-level userinfo gate must surface the literal "
            + "\"userinfo segment not allowed\" prefix");
        assertFalse(ex.getMessage().contains("secret"),
            "the credential token must NOT leak into the exception message — "
            + "the userinfo gate runs before any rendering that includes the raw URI");
    }

    @Test
    void fetchRaisesOnOversizeResponseBody() throws IOException {
        // Server returns a 4 KiB body; wrapper cap is 1 KiB. The
        // wrapper raises SsrfPolicyException inside RssFetcher.fetch(),
        // which propagates as an unchecked RuntimeException through
        // the Fetcher SPI.
        byte[] payload = new byte[4 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ('A' + (i % 26));
        }
        server.createContext("/big", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });

        RssFetcher fetcher = new RssFetcher(new SsrfGuardedHttpClient(
            new LoopbackPermittingBlocklist(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            1024L,
            3));

        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
            () -> fetcher.fetch(1L, "http://127.0.0.1:" + port + "/big"));
        assertTrue(ex.getMessage().contains("response body exceeded"),
            "oversize body must surface the literal \"response body exceeded\" prefix");
    }

    private RssFetcher testModeFetcher() {
        SsrfGuardedHttpClient client = new SsrfGuardedHttpClient(
            new LoopbackPermittingBlocklist(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            10L * 1024 * 1024,
            3);
        return new RssFetcher(client);
    }

    /**
     * Test-only {@link IpBlocklist} subclass that permits loopback
     * addresses so the in-process {@link HttpServer} fixture can be
     * dialed, while still blocking every other range. Subclassing is
     * the explicit override surface; the no-arg
     * {@link SsrfGuardedHttpClient} constructor wires the strict
     * production blocklist.
     */
    private static final class LoopbackPermittingBlocklist extends IpBlocklist {

        @Override
        protected boolean isBlockedAgainst(InetAddress addr, Set<InetAddress> hostInterfaces) {
            if (addr.isLoopbackAddress()) {
                return false;
            }
            return super.isBlockedAgainst(addr, hostInterfaces);
        }
    }
}
