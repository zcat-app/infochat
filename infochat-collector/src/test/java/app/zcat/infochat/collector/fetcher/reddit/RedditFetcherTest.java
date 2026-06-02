package app.zcat.infochat.collector.fetcher.reddit;

import app.zcat.infochat.core.ingest.NormalizedPost;
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
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedditFetcherTest {

    private static final Path LISTING_FIXTURE =
        Path.of("src/test/resources/fixtures/reddit/listing.json");

    private static final String LAST_PAGE_JSON = """
        {"kind":"Listing","data":{"after":null,"children":[
          {"kind":"t3","data":{"name":"t3_abc003","title":"Third Post",
           "selftext":"page two body",
           "permalink":"/r/testsub/comments/abc003/third_post/",
           "created_utc":1700002000.0,
           "author":"user3","score":1,"num_comments":0,
           "subreddit":"testsub"}}
        ]}}""";

    private static final String EMPTY_LISTING_JSON =
        """
        {"kind":"Listing","data":{"after":null,"children":[]}}""";

    // An `after` cursor whose value contains the URL metacharacters & # ?
    // that would inject or truncate the next-page query if unencoded.
    private static final String CRAFTED_CURSOR = "next&after=evil#?x";

    private static final String INJECT_FIRST_PAGE_JSON =
        "{\"kind\":\"Listing\",\"data\":{\"after\":\"" + CRAFTED_CURSOR
        + "\",\"children\":[]}}";

    private HttpServer server;
    private int port;
    private final AtomicReference<String> injectSecondPageRawQuery = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();

        // Normal listing — first page returns fixture, second page (with after) returns last page
        server.createContext("/r/test/new.json", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            byte[] body;
            if (query != null && query.contains("after=")) {
                body = LAST_PAGE_JSON.getBytes(StandardCharsets.UTF_8);
            } else {
                body = Files.readAllBytes(LISTING_FIXTURE);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        // Empty listing
        server.createContext("/r/empty/new.json", exchange -> {
            byte[] body = EMPTY_LISTING_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        // Error endpoint
        server.createContext("/r/error/new.json", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        // First page hands back CRAFTED_CURSOR as `after`; the fetcher's
        // second request is captured raw so the test can inspect how the
        // cursor was placed into the query string.
        server.createContext("/r/inject/new.json", exchange -> {
            String rawQuery = exchange.getRequestURI().getRawQuery();
            byte[] body;
            if (rawQuery != null && rawQuery.contains("after=")) {
                injectSecondPageRawQuery.set(rawQuery);
                body = EMPTY_LISTING_JSON.getBytes(StandardCharsets.UTF_8);
            } else {
                body = INJECT_FIRST_PAGE_JSON.getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
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
    void fetchReturnsParsedPosts() {
        RedditFetcher fetcher = testModeFetcher(1);
        List<NormalizedPost> posts = fetcher.fetch(42L, baseUrl() + "/r/test/new");

        assertEquals(2, posts.size());
        assertEquals("t3_abc001", posts.get(0).upstreamIdentifier());
        assertEquals("t3_abc002", posts.get(1).upstreamIdentifier());
    }

    @Test
    void fetchPaginatesUpToCap() {
        RedditFetcher fetcher = testModeFetcher(2);
        List<NormalizedPost> posts = fetcher.fetch(42L, baseUrl() + "/r/test/new");

        // Page 1: 2 posts (fixture has after=t3_after123)
        // Page 2: 1 post (last page, after=null)
        assertEquals(3, posts.size());
        assertEquals("t3_abc001", posts.get(0).upstreamIdentifier());
        assertEquals("t3_abc002", posts.get(1).upstreamIdentifier());
        assertEquals("t3_abc003", posts.get(2).upstreamIdentifier());
    }

    @Test
    void fetchMapsFieldsCorrectly() {
        RedditFetcher fetcher = testModeFetcher(1);
        Instant beforeFetch = Instant.now();
        List<NormalizedPost> posts = fetcher.fetch(99L, baseUrl() + "/r/test/new");
        Instant afterFetch = Instant.now();

        NormalizedPost first = posts.get(0);
        assertEquals(99L, first.sourceId());
        assertEquals("t3_abc001", first.upstreamIdentifier());
        assertEquals("First Post Title", first.title());
        assertEquals("Body of the first post", first.body());
        assertEquals("https://www.reddit.com/r/testsub/comments/abc001/first_post_title/",
            first.url());
        assertEquals(Instant.ofEpochSecond(1700000000L), first.publishedAt());

        // fetchedAt captured once, before the HTTP call
        assertTrue(first.fetchedAt().compareTo(beforeFetch) >= 0);
        assertTrue(first.fetchedAt().compareTo(afterFetch) <= 0);

        // Both posts share the same fetchedAt
        assertEquals(first.fetchedAt(), posts.get(1).fetchedAt());

        // rawMetadata
        Map<String, String> meta = first.rawMetadata();
        assertEquals("user1", meta.get("author"));
        assertEquals("42", meta.get("score"));
        assertEquals("10", meta.get("num_comments"));
        assertEquals("testsub", meta.get("subreddit"));

        // Link-only post: selftext is empty
        assertEquals("", posts.get(1).body());
    }

    @Test
    void fetchThrowsOnNon2xx() {
        RedditFetcher fetcher = testModeFetcher(1);
        RedditFetcher.RedditFetchException ex = assertThrows(
            RedditFetcher.RedditFetchException.class,
            () -> fetcher.fetch(42L, baseUrl() + "/r/error/new"));

        assertTrue(ex.getMessage().contains("HTTP 500"));
    }

    @Test
    void fetchHandlesEmptyListing() {
        RedditFetcher fetcher = testModeFetcher(1);
        List<NormalizedPost> posts = fetcher.fetch(42L, baseUrl() + "/r/empty/new");

        assertTrue(posts.isEmpty());
    }

    @Test
    void afterCursorWithSpecialCharsIsEncodedNotInterpreted() {
        RedditFetcher fetcher = testModeFetcher(5);
        fetcher.fetch(42L, baseUrl() + "/r/inject/new");

        String rawQuery = injectSecondPageRawQuery.get();
        assertNotNull(rawQuery, "fetcher must have requested a second page using the cursor");
        // The crafted & # ? are percent-encoded, leaving `after` a single
        // opaque token rather than three injected/truncating fragments.
        assertTrue(rawQuery.contains("after=next%26after%3Devil%23%3Fx"),
            "crafted cursor must be percent-encoded: " + rawQuery);
        // The injection collapses to exactly one `after` parameter.
        long afterParams = Arrays.stream(rawQuery.split("&"))
            .filter(part -> part.startsWith("after="))
            .count();
        assertEquals(1, afterParams,
            "cursor injection must not introduce a second after parameter: " + rawQuery);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static RedditFetcher testModeFetcher(int pageCap) {
        SsrfGuardedHttpClient client = new SsrfGuardedHttpClient(
            new LoopbackPermittingBlocklist(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            10L * 1024 * 1024,
            3);
        return new RedditFetcher(client, pageCap);
    }
}
