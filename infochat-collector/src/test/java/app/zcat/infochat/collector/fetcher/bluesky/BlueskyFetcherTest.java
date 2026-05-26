package app.zcat.infochat.collector.fetcher.bluesky;

import com.sun.net.httpserver.HttpServer;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.ssrf.IpBlocklist;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 unit test for {@link BlueskyFetcher}. Spins up an
 * in-process {@link HttpServer} on a localhost ephemeral port, serves
 * canned Bluesky API JSON responses, and exercises the Fetcher
 * end-to-end. No Quarkus container — parallel to RssFetcherTest.
 */
class BlueskyFetcherTest {

    private static final Path FEED_FIXTURE =
        Paths.get("src/test/resources/fixtures/bluesky/author-feed.json");

    private static final String EMPTY_FEED = "{\"feed\":[]}";

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();

        byte[] fixtureBody = Files.readAllBytes(FEED_FIXTURE);

        server.createContext("/xrpc/app.bsky.feed.getAuthorFeed", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            byte[] body;
            if (query != null && query.contains("cursor=")) {
                // Second page: return posts without cursor (end of pagination)
                body = secondPageJson();
            } else {
                body = fixtureBody;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        server.createContext("/empty", exchange -> {
            byte[] body = EMPTY_FEED.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        server.createContext("/error", exchange -> {
            exchange.sendResponseHeaders(429, -1);
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
        List<NormalizedPost> posts = testModeFetcher(5).fetch(
            1L, "alice.bsky.social");

        // Fixture has 3 posts on page 1 plus 1 on page 2 = 4 total
        // (first page returns cursor → fetcher requests second page)
        assertEquals(4, posts.size());

        for (NormalizedPost post : posts) {
            assertEquals(1L, post.sourceId());
            assertNotNull(post.upstreamIdentifier());
            assertNotNull(post.fetchedAt());
        }
    }

    @Test
    void fetchPaginatesUpToCap() {
        // Page cap of 1: fetcher stops after the first page even though
        // the fixture returns a cursor inviting a second request.
        List<NormalizedPost> posts = testModeFetcher(1).fetch(
            2L, "alice.bsky.social");

        assertEquals(3, posts.size(),
            "page cap 1 means only the first page (3 posts from fixture); "
            + "the cursor is ignored");
    }

    @Test
    void fetchMapsFieldsCorrectly() {
        List<NormalizedPost> posts = testModeFetcher(1).fetch(
            5L, "alice.bsky.social");

        NormalizedPost first = posts.get(0);
        assertEquals(5L, first.sourceId());
        assertEquals("at://did:plc:abc111/app.bsky.feed.post/post001",
            first.upstreamIdentifier());
        assertNull(first.title(), "Bluesky posts have no title");
        assertEquals("Hello from Bluesky! This is a test post.", first.body());
        assertEquals("https://bsky.app/profile/alice.bsky.social/post/post001",
            first.url());
        assertEquals(Instant.parse("2026-01-15T10:30:01.000Z"), first.publishedAt());

        Map<String, String> meta = first.rawMetadata();
        assertEquals("alice.bsky.social", meta.get("handle"));
        assertEquals("Alice Example", meta.get("displayName"));
        assertEquals("42", meta.get("likeCount"));
        assertEquals("7", meta.get("repostCount"));
    }

    @Test
    void fetchThrowsOnNon2xx() {
        // Point at the /error endpoint which returns 429
        BlueskyFetcher fetcher = new BlueskyFetcher(
            testModeClient(),
            5,
            "http://127.0.0.1:" + port + "/error");

        BlueskyFetcher.BlueskyFetchException ex = assertThrows(
            BlueskyFetcher.BlueskyFetchException.class,
            () -> fetcher.fetch(1L, "alice.bsky.social"));

        assertTrue(ex.getMessage().contains("429"),
            "exception must surface the upstream HTTP status code");
    }

    @Test
    void fetchHandlesEmptyFeed() {
        BlueskyFetcher fetcher = new BlueskyFetcher(
            testModeClient(),
            5,
            "http://127.0.0.1:" + port + "/empty");

        List<NormalizedPost> posts = fetcher.fetch(1L, "alice.bsky.social");

        assertTrue(posts.isEmpty(), "empty feed must return an empty list");
    }

    private BlueskyFetcher testModeFetcher(int pageCap) {
        return new BlueskyFetcher(
            testModeClient(),
            pageCap,
            "http://127.0.0.1:" + port + "/xrpc/app.bsky.feed.getAuthorFeed");
    }

    private SsrfGuardedHttpClient testModeClient() {
        return new SsrfGuardedHttpClient(
            new LoopbackPermittingBlocklist(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            10L * 1024 * 1024,
            3);
    }

    /**
     * Second page response: one post, no cursor (signals end of feed).
     */
    private static byte[] secondPageJson() {
        return """
            {
              "feed": [
                {
                  "post": {
                    "uri": "at://did:plc:abc111/app.bsky.feed.post/post004",
                    "cid": "bafyreiabc444",
                    "author": {
                      "did": "did:plc:abc111",
                      "handle": "alice.bsky.social",
                      "displayName": "Alice Example"
                    },
                    "record": {
                      "$type": "app.bsky.feed.post",
                      "text": "An older post from page two.",
                      "createdAt": "2026-01-13T08:00:00.000Z"
                    },
                    "likeCount": 1,
                    "repostCount": 0,
                    "indexedAt": "2026-01-13T08:00:01.000Z"
                  }
                }
              ]
            }
            """.getBytes(StandardCharsets.UTF_8);
    }

    private static final class LoopbackPermittingBlocklist extends IpBlocklist {
        @Override
        public boolean isBlocked(InetAddress addr) {
            if (addr.isLoopbackAddress()) {
                return false;
            }
            return super.isBlocked(addr);
        }
    }
}
