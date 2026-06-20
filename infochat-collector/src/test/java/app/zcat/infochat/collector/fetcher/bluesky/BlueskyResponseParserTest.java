package app.zcat.infochat.collector.fetcher.bluesky;

import app.zcat.infochat.core.ingest.NormalizedPost;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlueskyResponseParserTest {

    // Mirrors the private BlueskyResponseParser.MAX_ITEMS (M1-409). The
    // parser caps per-response items at parity with RssFeedParser: a feed
    // with exactly MAX_ITEMS entries parses, the cap+1-th raises.
    private static final int MAX_ITEMS = 1000;

    @Test
    void oneMalformedIndexedAt_doesNotAbortBatch() {
        // A feed with three entries: well-formed, malformed indexedAt,
        // well-formed. Before the fix, Instant.parse on the middle entry threw
        // DateTimeParseException and killed the entire parse() — the two
        // well-formed posts never came back.
        String json = """
            {
              "feed": [
                {"post": {"uri": "at://did:plc:a/app.bsky.feed.post/p1",
                          "author": {"handle": "a.bsky.social"},
                          "record": {"text": "first"},
                          "indexedAt": "2026-01-01T00:00:00Z"}},
                {"post": {"uri": "at://did:plc:b/app.bsky.feed.post/p2",
                          "author": {"handle": "b.bsky.social"},
                          "record": {"text": "broken ts"},
                          "indexedAt": "not-a-timestamp"}},
                {"post": {"uri": "at://did:plc:c/app.bsky.feed.post/p3",
                          "author": {"handle": "c.bsky.social"},
                          "record": {"text": "third"},
                          "indexedAt": "2026-01-02T00:00:00Z"}}
              ]
            }
            """;
        Instant fetchedAt = Instant.parse("2026-06-08T00:00:00Z");

        BlueskyResponseParser.Page page =
            BlueskyResponseParser.parse(42L, json.getBytes(StandardCharsets.UTF_8), fetchedAt);

        List<NormalizedPost> posts = page.posts();
        // All three entries come back — the malformed timestamp did not abort
        // the batch.
        assertEquals(3, posts.size());
        // The two well-formed posts keep their parsed published_at.
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), posts.get(0).publishedAt());
        assertEquals(Instant.parse("2026-01-02T00:00:00Z"), posts.get(2).publishedAt());
        // The malformed entry degrades to a null published_at but still ingests
        // with its body intact.
        assertNull(posts.get(1).publishedAt());
        assertEquals("broken ts", posts.get(1).body());
    }

    @Test
    void overCapResponse_isRejected() {
        // A single response carrying more than MAX_ITEMS feed entries is
        // rejected with the parser's parse-failure type, parity with the RSS
        // per-response cap.
        byte[] body = feedWith(MAX_ITEMS + 1).getBytes(StandardCharsets.UTF_8);
        Instant fetchedAt = Instant.parse("2026-06-08T00:00:00Z");

        assertThrows(BlueskyResponseParser.BlueskyParseException.class,
            () -> BlueskyResponseParser.parse(42L, body, fetchedAt),
            "a feed with more than MAX_ITEMS entries must be rejected");
    }

    @Test
    void atCapResponse_parsesNormally() {
        // A response with exactly MAX_ITEMS entries is under the cap and
        // parses to all MAX_ITEMS posts.
        byte[] body = feedWith(MAX_ITEMS).getBytes(StandardCharsets.UTF_8);
        Instant fetchedAt = Instant.parse("2026-06-08T00:00:00Z");

        BlueskyResponseParser.Page page = BlueskyResponseParser.parse(42L, body, fetchedAt);

        assertEquals(MAX_ITEMS, page.posts().size(),
            "a response at the cap must parse every entry");
    }

    /** Build a getAuthorFeed JSON body carrying {@code count} minimal entries. */
    private static String feedWith(int count) {
        StringBuilder sb = new StringBuilder("{\"feed\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"post\":{\"uri\":\"at://did:plc:a/app.bsky.feed.post/p")
              .append(i)
              .append("\",\"record\":{\"text\":\"t\"}}}");
        }
        return sb.append("]}").toString();
    }
}
