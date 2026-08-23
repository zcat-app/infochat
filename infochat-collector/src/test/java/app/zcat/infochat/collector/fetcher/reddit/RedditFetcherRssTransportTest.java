package app.zcat.infochat.collector.fetcher.reddit;

import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.ssrf.LoopbackPermittingBlocklist;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduction (M1 reddit transport): the reddit fetch path must ride
 * the {@code /.rss} listing endpoint with the shared Atom parser —
 * {@code identifier + ".json"} is 403-blocked at reddit's edge for every
 * UA (live-probed 2026-08-23), while {@code /.rss} answers 200 with the
 * shared outbound UA. The fixture is a byte-real capture of
 * r/java/hot's Atom feed. Engagement (likes/reposts/comments) is null
 * by recorded decision: the Atom payload carries no engagement numbers.
 */
class RedditFetcherRssTransportTest {

    private static final Path ATOM_FIXTURE =
        Path.of("src/test/resources/fixtures/reddit/atom-listing.rss");

    private HttpServer server;
    private int port;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> requestedPath = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();

        // ONLY the /.rss shape is served, mirroring prod egress: the
        // .json sibling path does not answer.
        server.createContext("/r/real/hot/.rss", exchange -> {
            requestCount.incrementAndGet();
            requestedPath.set(exchange.getRequestURI().getPath());
            byte[] body = Files.readAllBytes(ATOM_FIXTURE);
            exchange.getResponseHeaders().add("Content-Type", "application/atom+xml");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
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
    void redditListingFetchesRssEndpointAndParsesAtomEntries() {
        RedditFetcher fetcher = testModeFetcher();
        List<NormalizedPost> posts = fetcher.fetch(42L, baseUrl() + "/r/real/hot");

        assertEquals("/r/real/hot/.rss", requestedPath.get(),
            "the reddit transport must request identifier + '/.rss', not the edge-blocked .json");
        assertEquals(1, requestCount.get(),
            "the .rss listing has no after-cursor — one tick is one request");

        assertEquals(3, posts.size());
        NormalizedPost first = posts.get(0);
        assertEquals(42L, first.dispatchKey());
        assertEquals("t3_j7h9er", first.upstreamIdentifier(),
            "the Atom <id> t3_ fullname is the upstream identifier (uid-parity with a "
                + "future OAuth switch)");
        assertEquals("[PSA]/r/java is not for programming help, learning questions, "
            + "or installing Java questions", first.title());
        assertEquals("https://www.reddit.com/r/java/comments/j7h9er/"
            + "psarjava_is_not_for_programming_help_learning/", first.url());
        assertEquals(java.time.Instant.parse("2020-10-08T17:21:51Z"), first.publishedAt());
        assertTrue(first.body().contains("do not belong here"),
            "Atom <content type=\"html\"> body must be carried through");

        assertEquals("t3_1vw1fhg", posts.get(1).upstreamIdentifier());
        assertEquals("Java 27 features overview", posts.get(1).title());
        assertEquals("t3_1vwbi2e", posts.get(2).upstreamIdentifier());
    }

    @Test
    void engagementStaysNullOnRssTransport() {
        RedditFetcher fetcher = testModeFetcher();
        List<NormalizedPost> posts = fetcher.fetch(42L, baseUrl() + "/r/real/hot");

        for (NormalizedPost post : posts) {
            assertNull(post.likes(),
                "the .rss payload carries no score — null (no signal), never 0");
            assertNull(post.reposts());
            assertNull(post.comments());
            assertNull(post.socialScore(),
                "both engagement inputs null ⇒ derived score null, not 0");
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static RedditFetcher testModeFetcher() {
        SsrfGuardedHttpClient client = new SsrfGuardedHttpClient(
            LoopbackPermittingBlocklist.create(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            10L * 1024 * 1024,
            3);
        return new RedditFetcher(client);
    }
}
