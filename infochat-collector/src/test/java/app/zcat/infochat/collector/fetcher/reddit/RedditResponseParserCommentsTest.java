package app.zcat.infochat.collector.fetcher.reddit;

import app.zcat.infochat.core.ingest.NormalizedPost;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-914 reply-count mapping for the Reddit listing parser:
 * {@code num_comments} becomes the typed {@code comments} component through
 * the same saturate-never-narrow boundary helper as {@code score}, the
 * rawMetadata string entry is retired, and absent stays distinct from a
 * present zero. Fifth per-concern suite alongside the item-cap,
 * name-validation, permalink and social-signal suites.
 */
class RedditResponseParserCommentsTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-08-23T00:00:00Z");

    @Test
    void numCommentsLandsOnTheTypedFieldAbsentStaysNull() throws IOException {
        NormalizedPost present = parseSingle("""
            {"data":{"after":null,"children":[
              {"data":{"name":"t3_a1","title":"T","selftext":"B",
                       "permalink":"/r/x/comments/a1/t/","created_utc":1750000000,
                       "author":"user1","score":31,"num_comments":9,"subreddit":"x"}}
            ]}}
            """);
        assertEquals(9, present.comments(), "a present num_comments lands on the typed field");
        assertNull(present.reposts(), "a reply count is still never a repost");
        assertEquals(31, present.likes());
        assertEquals(31, present.socialScore(),
            "comments does not enter the social_score formula");

        NormalizedPost absent = parseSingle("""
            {"data":{"after":null,"children":[
              {"data":{"name":"t3_a1","title":"T","selftext":"B",
                       "permalink":"/r/x/comments/a1/t/","created_utc":1750000000,
                       "author":"user1","score":31,"subreddit":"x"}}
            ]}}
            """);
        assertNull(absent.comments(),
            "a listing without num_comments reports no reply signal, not zero");

        NormalizedPost zero = parseSingle("""
            {"data":{"after":null,"children":[
              {"data":{"name":"t3_a1","title":"T","selftext":"B",
                       "permalink":"/r/x/comments/a1/t/","created_utc":1750000000,
                       "author":"user1","score":31,"num_comments":0,"subreddit":"x"}}
            ]}}
            """);
        assertEquals(0, zero.comments(),
            "a present zero was seen and reported — it is not an absent signal");

        // The typed field replaces the string entry; the other rawMetadata
        // keys keep their M1-723 shape (the retarget-not-delete rule).
        assertFalse(present.rawMetadata().containsKey("num_comments"),
            "num_comments no longer rides the string map");
        assertFalse(absent.rawMetadata().containsKey("num_comments"),
            "the old asInt(0) default never fabricates a zero entry either");
        assertEquals("user1", present.rawMetadata().get("author"));
        assertEquals("x", present.rawMetadata().get("subreddit"));
    }

    @Test
    void commentsBeyondIntRangeSaturateRatherThanNarrowing() throws IOException {
        // asInt() is a truncating cast: 4294967296 narrows to exactly 0 and
        // 2147483648 to a negative — either would persist a fabricated
        // observation the listing never made (the M1-723 redteam shape).
        NormalizedPost wrappedToZero = parseSingle("""
            {"data":{"after":null,"children":[
              {"data":{"name":"t3_a1","title":"T","selftext":"B",
                       "permalink":"/r/x/comments/a1/t/","created_utc":1750000000,
                       "author":"user1","score":7,"num_comments":4294967296,"subreddit":"x"}}
            ]}}
            """);
        assertNotEquals(Integer.valueOf(0), wrappedToZero.comments(),
            "2^32 must not narrow to a fabricated seen-and-ignored zero");
        assertEquals(NormalizedPost.MAX_ENGAGEMENT_COUNT, wrappedToZero.comments(),
            "an out-of-range count saturates positive and is then magnitude-clamped");

        NormalizedPost wrappedNegative = parseSingle("""
            {"data":{"after":null,"children":[
              {"data":{"name":"t3_a1","title":"T","selftext":"B",
                       "permalink":"/r/x/comments/a1/t/","created_utc":1750000000,
                       "author":"user1","score":7,"num_comments":2147483648,"subreddit":"x"}}
            ]}}
            """);
        assertEquals(NormalizedPost.MAX_ENGAGEMENT_COUNT, wrappedNegative.comments(),
            "2^31 saturates positive, it does not wrap negative");
    }

    @Test
    void inRangeButOverBoundCommentsClampAtTheIngestBoundary() throws IOException {
        NormalizedPost huge = parseSingle("""
            {"data":{"after":null,"children":[
              {"data":{"name":"t3_a1","title":"T","selftext":"B",
                       "permalink":"/r/x/comments/a1/t/","created_utc":1750000000,
                       "author":"user1","score":7,"num_comments":2147483647,"subreddit":"x"}}
            ]}}
            """);
        assertEquals(NormalizedPost.MAX_ENGAGEMENT_COUNT, huge.comments(),
            "comments is magnitude-clamped like its engagement siblings");
    }

    @Test
    void nonFiniteCommentsDegradeToNullAndDoNotAbortTheListing() throws IOException {
        // 1e400 parses into a double holding infinity — not a representable
        // count. It must degrade to "no reply count reported" rather than
        // discard every well-formed sibling in the same listing.
        RedditResponseParser.ListingPage page = RedditResponseParser.parse(42L, """
            {"data":{"after":null,"children":[
              {"data":{"name":"t3_a1","title":"T","selftext":"B",
                       "permalink":"/r/x/comments/a1/t/","created_utc":1750000000,
                       "author":"user1","score":7,"num_comments":12,"subreddit":"x"}},
              {"data":{"name":"t3_a2","title":"T2","selftext":"B2",
                       "permalink":"/r/x/comments/a2/t/","created_utc":1750000001,
                       "author":"user2","score":3,"num_comments":1e400,"subreddit":"x"}}
            ]}}
            """.getBytes(StandardCharsets.UTF_8), FETCHED_AT);

        assertEquals(2, page.posts().size(),
            "a non-finite count must not discard the whole listing");
        assertEquals(12, page.posts().get(0).comments(), "the well-formed sibling is unaffected");
        assertNull(page.posts().get(1).comments(),
            "a non-finite count is 'no signal reported', not a fabricated maximum");
        assertTrue(page.posts().get(1).likes() > 0);
    }

    private static NormalizedPost parseSingle(String json) throws IOException {
        RedditResponseParser.ListingPage page = RedditResponseParser.parse(
            42L, json.getBytes(StandardCharsets.UTF_8), FETCHED_AT);
        assertEquals(1, page.posts().size(), "fixture must carry exactly one child");
        return page.posts().get(0);
    }
}
