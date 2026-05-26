package app.zcat.infochat.collector.fetcher.nitter;

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
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 unit test for {@link NitterFetcher}. Spins up an
 * in-process {@link HttpServer} on a localhost ephemeral port, serves
 * a Nitter-style RSS 2.0 fixture, and exercises the Fetcher end-to-end.
 * No Quarkus container — the Fetcher has no CDI dependencies in v1.
 */
class NitterFetcherTest {

    // Nitter RSS 2.0 fixture inlined to stay within files_scope — Nitter
    // serves standard RSS 2.0, so the shape matches what RssFeedParser expects.
    private static final byte[] NITTER_RSS_FIXTURE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0">
          <channel>
            <title>@nitteruser / Nitter</title>
            <link>https://nitter.example.com/nitteruser</link>
            <description>Feed for @nitteruser on Nitter</description>
            <item>
              <title>RT @someone: First retweet content</title>
              <link>https://nitter.example.com/nitteruser/status/100001</link>
              <guid isPermaLink="false">nitter:100001</guid>
              <description>First retweet content with a link https://example.com</description>
              <pubDate>Mon, 06 Sep 2010 00:01:00 +0000</pubDate>
            </item>
            <item>
              <title>Original post by nitteruser</title>
              <link>https://nitter.example.com/nitteruser/status/100002</link>
              <guid isPermaLink="false">nitter:100002</guid>
              <description><![CDATA[<p>HTML body with <a href="https://example.com">link</a></p>]]></description>
              <pubDate>Tue, 07 Sep 2010 12:30:45 +0000</pubDate>
            </item>
          </channel>
        </rss>
        """.getBytes(StandardCharsets.UTF_8);

    private HttpServer server;

    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/nitteruser/rss", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml; charset=UTF-8");
            exchange.sendResponseHeaders(200, NITTER_RSS_FIXTURE.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(NITTER_RSS_FIXTURE);
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
    void fetchDelegatesToRssFeedParser() {
        List<NormalizedPost> posts =
            testModeFetcher().fetch(1L, "http://127.0.0.1:" + port + "/nitteruser/rss");

        assertEquals(2, posts.size(),
            "the Nitter fixture has two <item> elements; RssFeedParser returns one NormalizedPost per item");

        NormalizedPost first = posts.get(0);
        assertEquals(1L, first.sourceId(),
            "every returned post must carry the caller-supplied sourceId");
        assertNotNull(first.upstreamIdentifier(),
            "upstreamIdentifier must be non-null — RssFeedParser extracts guid or link");
        assertNotNull(first.body(),
            "body must be non-null — RssFeedParser extracts description");
    }

    @Test
    void fetchReturnsParsedPosts() {
        List<NormalizedPost> posts =
            testModeFetcher().fetch(7L, "http://127.0.0.1:" + port + "/nitteruser/rss");

        // All posts share the same fetchedAt — partition key invariant.
        var shared = posts.get(0).fetchedAt();
        for (NormalizedPost post : posts) {
            assertEquals(shared, post.fetchedAt(),
                "every post from one fetch() invocation shares the same fetchedAt — "
                + "post partition-key invariant per docs/spec/schema.md");
        }

        // Verify RssFeedParser produced the expected upstream identifiers.
        assertEquals("nitter:100001", posts.get(0).upstreamIdentifier());
        assertEquals("nitter:100002", posts.get(1).upstreamIdentifier());
    }

    @Test
    void fetchThrowsOnNon2xx() {
        NitterFetcher.NitterFetchException ex = assertThrows(
            NitterFetcher.NitterFetchException.class,
            () -> testModeFetcher().fetch(1L, "http://127.0.0.1:" + port + "/notfound"));

        assertTrue(ex.getMessage().contains("404"),
            "exception must surface the upstream HTTP status code");
    }

    private NitterFetcher testModeFetcher() {
        SsrfGuardedHttpClient client = new SsrfGuardedHttpClient(
            new LoopbackPermittingBlocklist(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            10L * 1024 * 1024,
            3);
        return new NitterFetcher(client);
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
