package app.zcat.infochat.collector.fetcher.odysee;

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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 unit test for {@link OdyseeFetcher}. Spins up an
 * in-process {@link HttpServer} on a localhost ephemeral port, serves
 * an Odysee-style RSS 2.0 fixture, and exercises the Fetcher end-to-end.
 * No Quarkus container — the Fetcher has no CDI dependencies in v1.
 */
class OdyseeFetcherTest {

    // Odysee RSS 2.0 fixture inlined — Odysee serves standard RSS 2.0
    // at https://odysee.com/$/rss/@ChannelName, so the shape matches
    // what RssFeedParser expects.
    private static final byte[] ODYSEE_RSS_FIXTURE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0">
          <channel>
            <title>@TechChannel on Odysee</title>
            <link>https://odysee.com/@TechChannel</link>
            <description>Latest videos from @TechChannel on Odysee</description>
            <item>
              <title>Introduction to LBRY Protocol</title>
              <link>https://odysee.com/@TechChannel/intro-to-lbry</link>
              <guid isPermaLink="false">odysee:abcdef123456</guid>
              <description>A walkthrough of the LBRY protocol and how Odysee uses it for decentralized content hosting.</description>
              <pubDate>Sat, 01 Mar 2025 14:00:00 +0000</pubDate>
            </item>
            <item>
              <title>Self-Hosting Your Own Odysee Node</title>
              <link>https://odysee.com/@TechChannel/self-hosting-odysee</link>
              <guid isPermaLink="false">odysee:789ghi012345</guid>
              <description><![CDATA[<p>Step-by-step guide to running your own <a href="https://lbry.com">LBRY node</a>.</p>]]></description>
              <pubDate>Sun, 02 Mar 2025 10:30:00 +0000</pubDate>
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
        server.createContext("/$/rss/@TechChannel", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml; charset=UTF-8");
            exchange.sendResponseHeaders(200, ODYSEE_RSS_FIXTURE.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(ODYSEE_RSS_FIXTURE);
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
            testModeFetcher().fetch(7L, "http://127.0.0.1:" + port + "/$/rss/@TechChannel");

        assertEquals(2, posts.size(),
            "the Odysee fixture has two <item> elements; RssFeedParser returns one NormalizedPost per item");

        // All posts share the same fetchedAt — partition key invariant.
        var shared = posts.get(0).fetchedAt();
        for (NormalizedPost post : posts) {
            assertEquals(shared, post.fetchedAt(),
                "every post from one fetch() invocation shares the same fetchedAt — "
                + "post partition-key invariant per docs/spec/schema.md");
        }

        // Verify RssFeedParser produced the expected upstream identifiers.
        assertEquals("odysee:abcdef123456", posts.get(0).upstreamIdentifier());
        assertEquals("odysee:789ghi012345", posts.get(1).upstreamIdentifier());
    }

    @Test
    void fetchDelegatesToRssFeedParser() {
        List<NormalizedPost> posts =
            testModeFetcher().fetch(1L, "http://127.0.0.1:" + port + "/$/rss/@TechChannel");

        assertEquals(2, posts.size(),
            "the Odysee fixture has two <item> elements; RssFeedParser returns one NormalizedPost per item");

        NormalizedPost first = posts.get(0);
        assertEquals(1L, first.dispatchKey(),
            "every returned post must carry the caller-supplied sourceId");
        assertNotNull(first.upstreamIdentifier(),
            "upstreamIdentifier must be non-null — RssFeedParser extracts guid or link");
        assertNotNull(first.body(),
            "body must be non-null — RssFeedParser extracts description");
    }

    @Test
    void fetchThrowsOnNon2xx() {
        OdyseeFetcher.OdyseeFetchException ex = assertThrows(
            OdyseeFetcher.OdyseeFetchException.class,
            () -> testModeFetcher().fetch(1L, "http://127.0.0.1:" + port + "/notfound"));

        assertTrue(ex.getMessage().contains("404"),
            "exception must surface the upstream HTTP status code");
    }

    private OdyseeFetcher testModeFetcher() {
        SsrfGuardedHttpClient client = new SsrfGuardedHttpClient(
            LoopbackPermittingBlocklist.create(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            10L * 1024 * 1024,
            3);
        return new OdyseeFetcher(client);
    }
}
