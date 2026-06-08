package app.zcat.infochat.collector.fetcher.bluesky;

import app.zcat.infochat.core.ingest.NormalizedPost;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BlueskyResponseParserTest {

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
}
