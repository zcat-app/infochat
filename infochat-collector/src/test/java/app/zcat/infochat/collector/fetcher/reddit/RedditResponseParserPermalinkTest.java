package app.zcat.infochat.collector.fetcher.reddit;

import app.zcat.infochat.core.ingest.NormalizedPost;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M1-462 (F2): {@link RedditResponseParser} handles an empty/missing
 * {@code permalink} deliberately. Such an item still carries title +
 * selftext, so the parser keeps it (a skip would drop that content
 * permanently on every re-fetch, mirroring the {@code created_utc}
 * substitution) and emits the bare-domain {@code https://www.reddit.com}
 * URL with a logged warning rather than silently. These tests pin that
 * the bare-domain fallback is the chosen behaviour for both the
 * empty-string and the missing-node shapes.
 */
class RedditResponseParserPermalinkTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-06-26T09:00:00Z");

    // First child has an empty-string permalink; second child omits the
    // permalink field entirely. Both must map to the bare-domain URL.
    private static final String EMPTY_AND_MISSING_PERMALINK_JSON = """
        {"kind":"Listing","data":{"after":null,"children":[
          {"kind":"t3","data":{"name":"t3_empty01","title":"Empty Permalink Post",
           "selftext":"body with empty permalink","permalink":"",
           "created_utc":1700001000.0,
           "author":"user1","score":3,"num_comments":2,
           "subreddit":"testsub"}},
          {"kind":"t3","data":{"name":"t3_missing01","title":"Missing Permalink Post",
           "selftext":"body with no permalink field",
           "created_utc":1700002000.0,
           "author":"user2","score":5,"num_comments":1,
           "subreddit":"testsub"}}
        ]}}""";

    @Test
    void emptyOrMissingPermalinkFallsBackToBareDomainUrl() throws IOException {
        RedditResponseParser.ListingPage page = RedditResponseParser.parse(
            7L,
            EMPTY_AND_MISSING_PERMALINK_JSON.getBytes(StandardCharsets.UTF_8),
            FETCHED_AT);

        List<NormalizedPost> posts = page.posts();
        assertEquals(2, posts.size(),
            "both permalink-less items are kept (their content survives), not skipped");
        assertEquals("https://www.reddit.com", posts.get(0).url(),
            "an empty permalink yields the bare-domain fallback URL");
        assertEquals("https://www.reddit.com", posts.get(1).url(),
            "a missing permalink node yields the same bare-domain fallback URL");
    }

    @Test
    void presentPermalinkIsAppendedToTheDomain() throws IOException {
        String json = """
            {"kind":"Listing","data":{"after":null,"children":[
              {"kind":"t3","data":{"name":"t3_ok01","title":"Normal Post",
               "selftext":"body","permalink":"/r/testsub/comments/ok01/normal_post/",
               "created_utc":1700003000.0,
               "author":"user3","score":9,"num_comments":4,
               "subreddit":"testsub"}}
            ]}}""";

        RedditResponseParser.ListingPage page = RedditResponseParser.parse(
            8L, json.getBytes(StandardCharsets.UTF_8), FETCHED_AT);

        assertEquals("https://www.reddit.com/r/testsub/comments/ok01/normal_post/",
            page.posts().get(0).url(),
            "a present permalink is appended to the domain, unchanged");
    }
}
