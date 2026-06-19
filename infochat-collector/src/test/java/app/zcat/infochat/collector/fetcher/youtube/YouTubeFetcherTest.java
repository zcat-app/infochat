package app.zcat.infochat.collector.fetcher.youtube;

import com.sun.net.httpserver.HttpServer;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.ssrf.LoopbackPermittingBlocklist;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 unit test for {@link YouTubeFetcher}. Spins up an
 * in-process {@link HttpServer} on a localhost ephemeral port, serves
 * a YouTube-style Atom 1.0 fixture, and exercises the Fetcher
 * end-to-end. No Quarkus container.
 */
class YouTubeFetcherTest {

    private static final Path YOUTUBE_FIXTURE =
        Paths.get("src/test/resources/fixtures/youtube/youtube-atom-sample.xml");

    private HttpServer server;

    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/feeds/videos.xml", exchange -> {
            byte[] body = Files.readAllBytes(YOUTUBE_FIXTURE);
            exchange.getResponseHeaders().add("Content-Type", "application/atom+xml; charset=UTF-8");
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
    void fetchReturnsParsedPosts() {
        List<NormalizedPost> posts =
            testModeFetcher().fetch(42L, "http://127.0.0.1:" + port + "/feeds/videos.xml");

        assertEquals(3, posts.size(),
            "the fixture has three <entry> elements; fetch returns one NormalizedPost per entry");

        for (NormalizedPost post : posts) {
            assertEquals(42L, post.dispatchKey(),
                "every returned post must carry the caller-supplied sourceId");
            assertNotNull(post.upstreamIdentifier(),
                "upstreamIdentifier must be non-null per SPI contract");
            assertNotNull(post.fetchedAt(),
                "fetchedAt must be non-null per SPI contract");
        }

        // All posts from one fetch() call share the same fetchedAt
        Instant shared = posts.get(0).fetchedAt();
        for (NormalizedPost post : posts) {
            assertEquals(shared, post.fetchedAt(),
                "every post from one fetch() invocation shares the same fetchedAt — "
                + "post partition-key invariant per docs/spec/schema.md");
        }
    }

    @Test
    void fetchMapsVideoFieldsCorrectly() {
        List<NormalizedPost> posts =
            testModeFetcher().fetch(1L, "http://127.0.0.1:" + port + "/feeds/videos.xml");

        // First entry in the fixture: yt:video:dQw4w9WgXcQ
        NormalizedPost first = posts.get(0);
        assertEquals("yt:video:dQw4w9WgXcQ", first.upstreamIdentifier(),
            "upstreamIdentifier must be the Atom <id> element");
        assertEquals("Understanding Quarkus Reactive Messaging", first.title(),
            "title must be the Atom <title> element");
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", first.url(),
            "url must be the Atom <link rel=\"alternate\"> href");
        assertNotNull(first.body(),
            "body must be populated from the Atom <content> element");
        assertTrue(first.body().contains("SmallRye Reactive Messaging"),
            "body must contain the video description text from <content>");
        assertNotNull(first.publishedAt(),
            "publishedAt must be populated from the Atom <published> element");
    }

    @Test
    void fetchThrowsOnNon2xx() {
        YouTubeFetcher.YouTubeFetchException ex = assertThrows(
            YouTubeFetcher.YouTubeFetchException.class,
            () -> testModeFetcher().fetch(1L, "http://127.0.0.1:" + port + "/notfound"));

        assertTrue(ex.getMessage().contains("404"),
            "exception must surface the upstream HTTP status code");
    }

    private YouTubeFetcher testModeFetcher() {
        SsrfGuardedHttpClient client = new SsrfGuardedHttpClient(
            LoopbackPermittingBlocklist.create(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            10L * 1024 * 1024,
            3);
        return new YouTubeFetcher(client);
    }
}
