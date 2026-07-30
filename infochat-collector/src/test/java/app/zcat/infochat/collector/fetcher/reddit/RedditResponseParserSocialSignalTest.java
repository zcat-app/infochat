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
 * M1-723 social-signal mapping for the Reddit listing parser: {@code score}
 * becomes the typed {@code likes} column, {@code reposts} stays null
 * (Reddit exposes no repost count), and absent is never confused with zero.
 * Fourth per-concern suite alongside the item-cap, name-validation and
 * permalink suites.
 */
class RedditResponseParserSocialSignalTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-06-08T00:00:00Z");

    @Test
    void scoreLandsOnLikesAndRepostsStaysNull() throws IOException {
        NormalizedPost post = parseSingle("""
            {"data":{"after":null,"children":[
              {"data":{"name":"t3_a1","title":"T","selftext":"B",
                       "permalink":"/r/x/comments/a1/t/","created_utc":1750000000,
                       "author":"user1","score":31,"num_comments":9,"subreddit":"x"}}
            ]}}
            """);

        assertEquals(31, post.likes(), "Reddit's net vote count maps to likes");
        assertNull(post.reposts(),
            "Reddit exposes no repost count; num_comments is a reply count, not a repost");
        assertEquals(31, post.socialScore(),
            "with reposts null the score coalesces to just the likes");
    }

    @Test
    void numCommentsIsNotMappedToReposts() throws IOException {
        // Guards the specific mis-mapping the ticket calls out: a reply
        // count is not an amplification signal, and folding it into
        // reposts would double-weight it through the 2*reposts term.
        NormalizedPost post = parseSingle("""
            {"data":{"after":null,"children":[
              {"data":{"name":"t3_a1","title":"T","selftext":"B",
                       "permalink":"/r/x/comments/a1/t/","created_utc":1750000000,
                       "author":"user1","score":4,"num_comments":900,"subreddit":"x"}}
            ]}}
            """);

        assertNull(post.reposts(), "num_comments must not become reposts");
        assertEquals(4, post.socialScore(),
            "the 900 comments must not inflate the social score");
        assertEquals("900", post.rawMetadata().get("num_comments"),
            "num_comments stays in rawMetadata untouched");
    }

    @Test
    void scoreStopsBeingWrittenToRawMetadata() throws IOException {
        NormalizedPost post = parseSingle("""
            {"data":{"after":null,"children":[
              {"data":{"name":"t3_a1","title":"T","selftext":"B",
                       "permalink":"/r/x/comments/a1/t/","created_utc":1750000000,
                       "author":"user1","score":12,"num_comments":3,"subreddit":"x"}}
            ]}}
            """);

        assertFalse(post.rawMetadata().containsKey("score"),
            "score is now a typed column, no longer smuggled through the string map");
        assertEquals("user1", post.rawMetadata().get("author"));
        assertEquals("x", post.rawMetadata().get("subreddit"));
    }

    @Test
    void negativeScorePersistsNegative() throws IOException {
        // Reddit's score is a NET vote count. A heavily-downvoted post is
        // real information; clamping it to 0 would make it indistinguishable
        // from an unengaged post.
        NormalizedPost post = parseSingle("""
            {"data":{"after":null,"children":[
              {"data":{"name":"t3_a1","title":"T","selftext":"B",
                       "permalink":"/r/x/comments/a1/t/","created_utc":1750000000,
                       "author":"user1","score":-250,"num_comments":80,"subreddit":"x"}}
            ]}}
            """);

        assertEquals(-250, post.likes(), "a negative net score is preserved in sign");
        assertEquals(-250, post.socialScore(),
            "the formula is applied unchanged; a downvoted post scores negative");
    }

    @Test
    void absentScoreIsNullNotZero() throws IOException {
        NormalizedPost post = parseSingle("""
            {"data":{"after":null,"children":[
              {"data":{"name":"t3_a1","title":"T","selftext":"B",
                       "permalink":"/r/x/comments/a1/t/","created_utc":1750000000,
                       "author":"user1","num_comments":3,"subreddit":"x"}}
            ]}}
            """);

        assertNull(post.likes(), "an absent score is null, not 0");
        assertNull(post.socialScore(),
            "no reported signal at all leaves the score null, not 0");
    }

    @Test
    void hugeScoreIsClampedSoTheScoreStaysBounded() throws IOException {
        NormalizedPost post = parseSingle("""
            {"data":{"after":null,"children":[
              {"data":{"name":"t3_a1","title":"T","selftext":"B",
                       "permalink":"/r/x/comments/a1/t/","created_utc":1750000000,
                       "author":"user1","score":2147483647,"num_comments":3,"subreddit":"x"}}
            ]}}
            """);

        assertEquals(NormalizedPost.MAX_ENGAGEMENT_COUNT, post.likes(),
            "an over-bound upstream count is clamped at the ingest boundary");
        assertTrue(post.socialScore() > 0);
    }

    @Test
    void scoreBeyondIntRangeSaturatesRatherThanWrapping() throws IOException {
        // asInt() is a truncating cast, so 2147483648 would wrap to
        // -2147483648 and clamp to a maximally-negative social score —
        // the bound NormalizedPost's contract says cannot be reached.
        // One above the previous test's 2147483647, the largest value
        // that does not wrap.
        NormalizedPost post = parseSingle("""
            {"data":{"after":null,"children":[
              {"data":{"name":"t3_a1","title":"T","selftext":"B",
                       "permalink":"/r/x/comments/a1/t/","created_utc":1750000000,
                       "author":"user1","score":2147483648,"num_comments":3,"subreddit":"x"}}
            ]}}
            """);

        assertEquals(NormalizedPost.MAX_ENGAGEMENT_COUNT, post.likes(),
            "an out-of-int-range score saturates positive, it does not wrap negative");
        assertTrue(post.socialScore() > 0,
            "got " + post.socialScore());
    }

    @Test
    void scoreAtExactWrapToZeroDoesNotFabricateAZeroObservation() throws IOException {
        // 2^32 narrows to exactly 0, storing a non-NULL "post nets zero
        // votes" that the listing never reported.
        NormalizedPost post = parseSingle("""
            {"data":{"after":null,"children":[
              {"data":{"name":"t3_a1","title":"T","selftext":"B",
                       "permalink":"/r/x/comments/a1/t/","created_utc":1750000000,
                       "author":"user1","score":4294967296,"num_comments":3,"subreddit":"x"}}
            ]}}
            """);

        assertNotEquals(Integer.valueOf(0), post.likes(),
            "2^32 must not narrow to a fabricated zero-score observation");
        assertEquals(NormalizedPost.MAX_ENGAGEMENT_COUNT, post.likes());
    }

    @Test
    void hugeNegativeScoreSaturatesNegativeRatherThanFlippingPositive() throws IOException {
        // A downvote brigade claim beyond int range must not present as
        // a maximal POSITIVE score. Reddit's score is the one legitimately
        // negative input, so the sign is load-bearing here.
        NormalizedPost post = parseSingle("""
            {"data":{"after":null,"children":[
              {"data":{"name":"t3_a1","title":"T","selftext":"B",
                       "permalink":"/r/x/comments/a1/t/","created_utc":1750000000,
                       "author":"user1","score":-99999999999999999999,"num_comments":3,
                       "subreddit":"x"}}
            ]}}
            """);

        assertEquals(-NormalizedPost.MAX_ENGAGEMENT_COUNT, post.likes(),
            "an out-of-range negative saturates negative, it does not flip positive");
        assertTrue(post.socialScore() < 0, "got " + post.socialScore());
    }

    @Test
    void nonFiniteScoreDegradesToNullAndDoesNotAbortTheListing() throws IOException {
        // 1e400 parses into a double holding infinity — not a
        // representable count. It must degrade to "no score reported"
        // rather than letting a coercion failure discard every
        // well-formed entry in the same listing.
        RedditResponseParser.ListingPage page = RedditResponseParser.parse(42L, """
            {"data":{"after":null,"children":[
              {"data":{"name":"t3_a1","title":"T","selftext":"B",
                       "permalink":"/r/x/comments/a1/t/","created_utc":1750000000,
                       "author":"user1","score":7,"num_comments":3,"subreddit":"x"}},
              {"data":{"name":"t3_a2","title":"T2","selftext":"B2",
                       "permalink":"/r/x/comments/a2/t/","created_utc":1750000001,
                       "author":"user2","score":1e400,"num_comments":4,"subreddit":"x"}}
            ]}}
            """.getBytes(StandardCharsets.UTF_8), FETCHED_AT);

        assertEquals(2, page.posts().size(),
            "a non-finite score must not discard the whole listing");
        assertEquals(7, page.posts().get(0).likes(), "the well-formed sibling is unaffected");
        assertNull(page.posts().get(1).likes(),
            "a non-finite score is 'no signal reported', not a fabricated maximum");
        assertNull(page.posts().get(1).socialScore());
    }

    private static NormalizedPost parseSingle(String json) throws IOException {
        RedditResponseParser.ListingPage page = RedditResponseParser.parse(
            42L, json.getBytes(StandardCharsets.UTF_8), FETCHED_AT);
        assertEquals(1, page.posts().size(), "fixture must carry exactly one child");
        return page.posts().get(0);
    }
}
