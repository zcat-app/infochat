package app.zcat.infochat.collector.fetcher.bluesky;

import app.zcat.infochat.core.ingest.NormalizedPost;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void engagementCountsLandOnTypedFieldsNotRawMetadata() {
        // M1-723: likeCount/repostCount stop being stringified into
        // rawMetadata and become typed columns. socialScore is derived
        // by NormalizedPost as 2*reposts + likes.
        String json = """
            {
              "feed": [
                {"post": {"uri": "at://did:plc:a/app.bsky.feed.post/p1",
                          "author": {"handle": "a.bsky.social"},
                          "record": {"text": "engaged"},
                          "likeCount": 42,
                          "repostCount": 7}}
              ]
            }
            """;

        NormalizedPost post = parseSingle(json);

        assertEquals(42, post.likes());
        assertEquals(7, post.reposts());
        assertEquals(2 * 7 + 42, post.socialScore(),
            "socialScore is the canonical 2*reposts + likes");
        assertFalse(post.rawMetadata().containsKey("likeCount"),
            "engagement no longer smuggles through the string map");
        assertFalse(post.rawMetadata().containsKey("repostCount"),
            "engagement no longer smuggles through the string map");
        assertEquals("a.bsky.social", post.rawMetadata().get("handle"),
            "the non-engagement rawMetadata keys are untouched");
    }

    @Test
    void absentCountsAreNullNotZero() {
        // The distinction the whole ticket exists to preserve: a post
        // whose counts the API did not report must stay distinguishable
        // from one that was seen and ignored. The former asInt(0)
        // defaulting collapsed both onto 0.
        String json = """
            {
              "feed": [
                {"post": {"uri": "at://did:plc:a/app.bsky.feed.post/p1",
                          "author": {"handle": "a.bsky.social"},
                          "record": {"text": "no counts reported"}}}
              ]
            }
            """;

        NormalizedPost post = parseSingle(json);

        assertNull(post.likes(), "an absent likeCount is null, not 0");
        assertNull(post.reposts(), "an absent repostCount is null, not 0");
        assertNull(post.socialScore(),
            "with both inputs absent the score is null — 'no social signal', not 'nobody engaged'");
    }

    @Test
    void zeroCountsAreZeroNotNull() {
        // The other half of the same distinction: an explicitly-reported
        // 0 is a real observation and must survive as 0, yielding a
        // socialScore of 0 rather than null.
        String json = """
            {
              "feed": [
                {"post": {"uri": "at://did:plc:a/app.bsky.feed.post/p1",
                          "author": {"handle": "a.bsky.social"},
                          "record": {"text": "seen and ignored"},
                          "likeCount": 0,
                          "repostCount": 0}}
              ]
            }
            """;

        NormalizedPost post = parseSingle(json);

        assertEquals(0, post.likes());
        assertEquals(0, post.reposts());
        assertEquals(0, post.socialScore(),
            "a post nobody engaged with scores 0, distinct from the null no-signal case");
    }

    @Test
    void oneAbsentCountStillScoresFromTheOther() {
        // Only BOTH-null yields a null score; one present input is a
        // social signal and the missing side coalesces to 0.
        String json = """
            {
              "feed": [
                {"post": {"uri": "at://did:plc:a/app.bsky.feed.post/p1",
                          "author": {"handle": "a.bsky.social"},
                          "record": {"text": "likes only"},
                          "likeCount": 5}}
              ]
            }
            """;

        NormalizedPost post = parseSingle(json);

        assertEquals(5, post.likes());
        assertNull(post.reposts());
        assertEquals(5, post.socialScore(), "the null repost side coalesces to 0");
    }

    @Test
    void nonNumericCountIsNullNotZero() {
        // A hostile or malformed upstream can send a string where a
        // number belongs. asInt() would silently yield 0 and fabricate a
        // "seen and ignored" observation that never happened.
        String json = """
            {
              "feed": [
                {"post": {"uri": "at://did:plc:a/app.bsky.feed.post/p1",
                          "author": {"handle": "a.bsky.social"},
                          "record": {"text": "malformed"},
                          "likeCount": "not-a-number",
                          "repostCount": null}}
              ]
            }
            """;

        NormalizedPost post = parseSingle(json);

        assertNull(post.likes(), "a non-numeric likeCount is null, not 0");
        assertNull(post.reposts(), "a JSON-null repostCount is null, not 0");
        assertNull(post.socialScore());
    }

    @Test
    void hugeRepostCountIsClampedSoTheScoreStaysPositive() {
        // Unclamped, 2 * Integer.MAX_VALUE overflows to -2, so a hostile
        // upstream could mint a maximally-NEGATIVE social score by
        // reporting a maximally-large repost count.
        String json = """
            {
              "feed": [
                {"post": {"uri": "at://did:plc:a/app.bsky.feed.post/p1",
                          "author": {"handle": "a.bsky.social"},
                          "record": {"text": "overflow attempt"},
                          "likeCount": 2147483647,
                          "repostCount": 2147483647}}
              ]
            }
            """;

        NormalizedPost post = parseSingle(json);

        assertEquals(NormalizedPost.MAX_ENGAGEMENT_COUNT, post.reposts(),
            "an over-bound count is clamped at the ingest boundary");
        assertEquals(NormalizedPost.MAX_ENGAGEMENT_COUNT, post.likes());
        assertTrue(post.socialScore() > 0,
            "the clamp keeps 2*reposts + likes from overflowing negative, got "
                + post.socialScore());
        assertEquals(3 * NormalizedPost.MAX_ENGAGEMENT_COUNT, post.socialScore(),
            "the clamped worst case is 2*MAX/4 + MAX/4");
    }

    @Test
    void countBeyondIntRangeSaturatesRatherThanWrappingNegative() {
        // The clamp bounds an int, so it can only work on a value that
        // reached int intact. asInt() is a TRUNCATING cast: 2147483648
        // wraps to -2147483648, which clamps to -MAX_ENGAGEMENT_COUNT
        // and yields socialScore -1610612733 — precisely the negative
        // the bound exists to forbid. One above the previous test's
        // 2147483647, which is the largest value that does NOT wrap.
        String json = """
            {
              "feed": [
                {"post": {"uri": "at://did:plc:a/app.bsky.feed.post/p1",
                          "author": {"handle": "a.bsky.social"},
                          "record": {"text": "wrap attempt"},
                          "likeCount": 2147483648,
                          "repostCount": 2147483648}}
              ]
            }
            """;

        NormalizedPost post = parseSingle(json);

        assertEquals(NormalizedPost.MAX_ENGAGEMENT_COUNT, post.likes(),
            "an out-of-int-range count saturates positive, it does not wrap negative");
        assertEquals(NormalizedPost.MAX_ENGAGEMENT_COUNT, post.reposts());
        assertTrue(post.socialScore() > 0,
            "a count beyond int range must not produce a negative social score, got "
                + post.socialScore());
    }

    @Test
    void countAtExactWrapToZeroDoesNotFabricateAZeroObservation() {
        // 4294967296 == 2^32 narrows to exactly 0. A stored non-NULL 0
        // asserts "seen and ignored" — an observation the upstream never
        // made — which is the same null-vs-zero conflation the absent
        // and non-numeric cases above defend against, arriving by a
        // different route.
        String json = """
            {
              "feed": [
                {"post": {"uri": "at://did:plc:a/app.bsky.feed.post/p1",
                          "author": {"handle": "a.bsky.social"},
                          "record": {"text": "wrap to zero"},
                          "likeCount": 4294967296}}
              ]
            }
            """;

        NormalizedPost post = parseSingle(json);

        assertNotEquals(Integer.valueOf(0), post.likes(),
            "2^32 must not narrow to a fabricated zero-likes observation");
        assertEquals(NormalizedPost.MAX_ENGAGEMENT_COUNT, post.likes(),
            "it saturates to the bound instead");
    }

    @Test
    void hugeNegativeCountSaturatesNegativeRatherThanFlippingPositive() {
        // The mirror case: -2147483649 narrows to +2147483647, flipping
        // an implausible negative into a maximal POSITIVE score.
        String json = """
            {
              "feed": [
                {"post": {"uri": "at://did:plc:a/app.bsky.feed.post/p1",
                          "author": {"handle": "a.bsky.social"},
                          "record": {"text": "sign flip attempt"},
                          "likeCount": -2147483649}}
              ]
            }
            """;

        NormalizedPost post = parseSingle(json);

        assertEquals(-NormalizedPost.MAX_ENGAGEMENT_COUNT, post.likes(),
            "an out-of-range negative saturates negative, it does not flip positive");
        assertTrue(post.socialScore() < 0,
            "the sign survives the boundary, got " + post.socialScore());
    }

    @Test
    void nonFiniteCountDegradesToNullAndDoesNotAbortTheBatch() {
        // JSON permits 1e400; Jackson parses it into a double holding
        // infinity. It is not a representable count, so it takes the
        // "no count reported" branch. Critically the whole PAGE must
        // still parse — the same per-entry degradation the malformed
        // indexedAt case above pins. Three entries so a page-level abort
        // is visible as a missing sibling, not just a null field.
        String json = """
            {
              "feed": [
                {"post": {"uri": "at://did:plc:a/app.bsky.feed.post/p1",
                          "author": {"handle": "a.bsky.social"},
                          "record": {"text": "first"}, "likeCount": 3}},
                {"post": {"uri": "at://did:plc:b/app.bsky.feed.post/p2",
                          "author": {"handle": "b.bsky.social"},
                          "record": {"text": "infinite"}, "likeCount": 1e400}},
                {"post": {"uri": "at://did:plc:c/app.bsky.feed.post/p3",
                          "author": {"handle": "c.bsky.social"},
                          "record": {"text": "third"}, "likeCount": 5}}
              ]
            }
            """;

        BlueskyResponseParser.Page page = BlueskyResponseParser.parse(
            42L, json.getBytes(StandardCharsets.UTF_8), Instant.parse("2026-06-08T00:00:00Z"));

        assertEquals(3, page.posts().size(),
            "a non-finite count must not discard the whole page");
        assertNull(page.posts().get(1).likes(),
            "a non-finite count is 'no signal reported', not a fabricated maximum");
        assertNull(page.posts().get(1).socialScore());
        assertEquals(3, page.posts().get(0).likes(), "well-formed siblings are unaffected");
        assertEquals(5, page.posts().get(2).likes());
    }

    /** Parse a one-entry feed body and return its single post. */
    private static NormalizedPost parseSingle(String json) {
        BlueskyResponseParser.Page page = BlueskyResponseParser.parse(
            42L, json.getBytes(StandardCharsets.UTF_8), Instant.parse("2026-06-08T00:00:00Z"));
        assertEquals(1, page.posts().size(), "fixture must carry exactly one entry");
        return page.posts().get(0);
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
