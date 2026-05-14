package io.infochat.collector.fetcher.rss;

import com.sun.net.httpserver.HttpServer;
import io.infochat.core.ingest.NormalizedPost;
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
 * Plain JUnit 5 unit test for {@link RssFetcher}. Spins up an
 * in-process {@link HttpServer} on a localhost ephemeral port, serves
 * the RSS 2.0 fixture, and exercises the Fetcher end-to-end. No
 * Quarkus container — the Fetcher has no CDI dependencies in v1.
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
            new RssFetcher().fetch(1L, "http://127.0.0.1:" + port + "/feed.xml");
        Instant after = Instant.now();

        assertEquals(3, posts.size(),
            "the fixture has three <item> elements; fetch returns one NormalizedPost per item");

        for (NormalizedPost post : posts) {
            assertEquals(1L, post.sourceId(),
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
            new RssFetcher().fetch(7L, "http://127.0.0.1:" + port + "/feed.xml");

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
            () -> new RssFetcher().fetch(1L, "http://127.0.0.1:" + port + "/notfound"));

        assertTrue(ex.getMessage().contains("404"),
            "exception must surface the upstream HTTP status code");
    }
}
