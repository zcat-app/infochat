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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RedditFetcher over the {@code /.rss} Atom transport (M1-915): the
 * {@code .json} endpoint is edge-blocked from prod's egress (live-probed
 * 2026-08-23 — 403 for every UA), so the fetcher requests
 * {@code identifier + "/.rss"} and parses via the shared RssFeedParser
 * Atom leg. The fixture is a byte-real capture of r/java/hot. Engagement
 * stays null: the Atom payload carries no engagement numbers (deferred
 * with the OAuth path — see the M1-915 ticket's out_of_scope).
 */
class RedditFetcherTest {

    private static final Path ATOM_FIXTURE =
        Path.of("src/test/resources/fixtures/reddit/atom-listing.rss");

    private static final String EMPTY_ATOM_FEED = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom"><title>empty</title>
        <updated>2026-08-23T00:00:00+00:00</updated>
        <id>/r/empty/hot/.rss</id></feed>""";

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();

        serve("/r/test/hot/.rss", Files.readAllBytes(ATOM_FIXTURE));
        serve("/r/empty/hot/.rss", EMPTY_ATOM_FEED.getBytes(StandardCharsets.UTF_8));
        server.createContext("/r/error/hot/.rss", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        server.start();
    }

    private void serve(String path, byte[] body) {
        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/atom+xml");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchReturnsParsedAtomEntries() {
        RedditFetcher fetcher = testModeFetcher();
        List<NormalizedPost> posts = fetcher.fetch(42L, baseUrl() + "/r/test/hot");

        // The byte-real r/java/hot capture's first three entries.
        assertEquals(3, posts.size());
        assertEquals("t3_j7h9er", posts.get(0).upstreamIdentifier());
        assertEquals("t3_1vw1fhg", posts.get(1).upstreamIdentifier());
        assertEquals("t3_1vwbi2e", posts.get(2).upstreamIdentifier());
    }

    @Test
    void fetchMapsFieldsCorrectly() {
        RedditFetcher fetcher = testModeFetcher();
        Instant beforeFetch = Instant.now();
        List<NormalizedPost> posts = fetcher.fetch(99L, baseUrl() + "/r/test/hot");
        Instant afterFetch = Instant.now();

        NormalizedPost first = posts.get(0);
        assertEquals(99L, first.dispatchKey());
        assertEquals("t3_j7h9er", first.upstreamIdentifier(),
            "the Atom <id> t3_ fullname is the upstream identifier (uid parity "
                + "with a future OAuth switch)");
        assertEquals("[PSA]/r/java is not for programming help, learning questions, "
            + "or installing Java questions", first.title());
        assertTrue(first.body().contains("do not belong here"),
            "Atom <content type=\"html\"> body is carried through raw");
        assertEquals("https://www.reddit.com/r/java/comments/j7h9er/"
            + "psarjava_is_not_for_programming_help_learning/", first.url());
        // The fixture entry's <published>2020-10-08T17:21:51+00:00</published>.
        assertEquals(Instant.ofEpochSecond(1602177711L), first.publishedAt());

        // fetchedAt captured once, before the HTTP call; all posts share it.
        assertTrue(first.fetchedAt().compareTo(beforeFetch) >= 0);
        assertTrue(first.fetchedAt().compareTo(afterFetch) <= 0);
        assertEquals(first.fetchedAt(), posts.get(1).fetchedAt());

        // Reddit's listing-Atom nodes that exist: author and category.
        assertEquals("/u/desrtfx", first.rawMetadata().get("author"));
        assertEquals("java", first.rawMetadata().get("category"));

        // Engagement: the Atom payload carries none — null (no signal),
        // never 0. Deferred with the OAuth path (M1-915 out_of_scope).
        assertNull(first.likes());
        assertNull(first.reposts());
        assertNull(first.comments());
        assertNull(first.socialScore(),
            "both engagement inputs null ⇒ derived score null, not 0");
    }

    @Test
    void fetchThrowsOnNon2xx() {
        RedditFetcher fetcher = testModeFetcher();
        RedditFetcher.RedditFetchException ex = assertThrows(
            RedditFetcher.RedditFetchException.class,
            () -> fetcher.fetch(42L, baseUrl() + "/r/error/hot"));

        assertTrue(ex.getMessage().contains("HTTP 500"));
    }

    @Test
    void fetchHandlesEmptyFeed() {
        RedditFetcher fetcher = testModeFetcher();
        List<NormalizedPost> posts = fetcher.fetch(42L, baseUrl() + "/r/empty/hot");

        assertTrue(posts.isEmpty());
    }

    @Test
    void redditFetchCarriesExactlyOneUserAgent() {
        // M1-704 regression guard, carried onto the .rss transport: the
        // fetcher supplies no User-Agent and inherits the wrapper's single
        // descriptive value (Reddit blocks the default JDK User-Agent).
        AtomicReference<List<String>> userAgents = new AtomicReference<>();
        byte[] body = EMPTY_ATOM_FEED.getBytes(StandardCharsets.UTF_8);
        server.createContext("/r/useragent/hot/.rss", exchange -> {
            userAgents.set(List.copyOf(exchange.getRequestHeaders().get("User-Agent")));
            exchange.getResponseHeaders().add("Content-Type", "application/atom+xml");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        RedditFetcher fetcher = testModeFetcher();
        fetcher.fetch(42L, baseUrl() + "/r/useragent/hot");

        assertEquals(1, userAgents.get().size(),
            "a Reddit fetch must carry exactly one User-Agent value, not the "
                + "wrapper default plus a fetcher-supplied duplicate: " + userAgents.get());
        assertTrue(userAgents.get().get(0).startsWith("infochat/"),
            "the single value must be the wrapper's product token, not the "
                + "default JDK User-Agent Reddit blocks: " + userAgents.get());
    }

    @Test
    void listingUriAppendsDotRssExactlyOnce() {
        // Bare subreddit and listing forms (V86's normalized shapes)…
        assertEquals(URI.create("https://www.reddit.com/r/java/.rss"),
            RedditFetcher.buildListingUri("https://www.reddit.com/r/java"));
        assertEquals(URI.create("https://www.reddit.com/r/java/hot/.rss"),
            RedditFetcher.buildListingUri("https://www.reddit.com/r/java/hot"));
        // …a trailing slash introduces no double slash…
        assertEquals(URI.create("https://www.reddit.com/r/java/hot/.rss"),
            RedditFetcher.buildListingUri("https://www.reddit.com/r/java/hot/"));
        // …and an identifier already ending in .rss (hand-registered, any
        // case) is used as-is, never double-suffixed.
        assertEquals(URI.create("https://www.reddit.com/r/java/hot/.rss"),
            RedditFetcher.buildListingUri("https://www.reddit.com/r/java/hot/.rss"));
        assertEquals(URI.create("https://www.reddit.com/r/java/hot/.RSS"),
            RedditFetcher.buildListingUri("https://www.reddit.com/r/java/hot/.RSS"));
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
