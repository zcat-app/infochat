package app.zcat.infochat.collector.fetcher.reddit;

import app.zcat.infochat.core.ingest.NormalizedPost;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * U-36: {@link RedditResponseParser} validates the {@code name}
 * (upstream identifier) at the parse boundary. A listing child with a
 * missing/empty {@code name} would otherwise map to {@code ""}, which
 * downstream trips {@code PostPersister}'s NormalizedPost-SPI
 * non-empty-identifier assertion and aborts the WHOLE tick. The parser
 * skips the single malformed entry instead, so the rest of the page
 * still persists — the same parse-boundary discipline
 * {@code RssFeedParser} applies to an item with no {@code <guid>}/{@code <link>}.
 */
class RedditResponseParserNameValidationTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-06-11T09:00:00Z");

    // A listing whose FIRST child has no "name" field and whose SECOND
    // child is well-formed. Pre-fix, the first child mapped to an
    // empty identifier and aborted the tick at PostPersister; post-fix
    // it is skipped and only the well-formed child survives.
    private static final String NAME_LESS_THEN_VALID_JSON = """
        {"kind":"Listing","data":{"after":null,"children":[
          {"kind":"t3","data":{"title":"No Name Post",
           "selftext":"body with no fullname",
           "permalink":"/r/testsub/comments/noname/no_name_post/",
           "created_utc":1700001000.0,
           "author":"user1","score":3,"num_comments":2,
           "subreddit":"testsub"}},
          {"kind":"t3","data":{"name":"t3_valid01","title":"Valid Post",
           "selftext":"valid body",
           "permalink":"/r/testsub/comments/valid01/valid_post/",
           "created_utc":1700002000.0,
           "author":"user2","score":5,"num_comments":1,
           "subreddit":"testsub"}}
        ]}}""";

    @Test
    void nameLessEntryIsSkippedAndRestOfPageSurvives() throws IOException {
        RedditResponseParser.ListingPage page = assertDoesNotThrow(() ->
            RedditResponseParser.parse(
                42L,
                NAME_LESS_THEN_VALID_JSON.getBytes(StandardCharsets.UTF_8),
                FETCHED_AT));

        List<NormalizedPost> posts = page.posts();
        assertEquals(1, posts.size(),
            "the name-less child must be skipped at the parse boundary, "
                + "leaving only the well-formed child");
        assertEquals("t3_valid01", posts.get(0).upstreamIdentifier(),
            "the surviving post is the well-formed entry, with its fullname intact");
    }
}
