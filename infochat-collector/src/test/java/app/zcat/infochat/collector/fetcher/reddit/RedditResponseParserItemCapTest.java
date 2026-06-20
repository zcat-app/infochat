package app.zcat.infochat.collector.fetcher.reddit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * M1-409: {@link RedditResponseParser} caps per-response item count at
 * parity with {@code RssFeedParser.MAX_ITEMS}. A listing carrying more than
 * MAX_ITEMS children is rejected with the parser's parse-failure type
 * ({@link IOException}); a listing at or below the cap parses normally —
 * the same per-response bound the single-GET RSS path already enforces.
 */
class RedditResponseParserItemCapTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-06-20T09:00:00Z");

    // Mirrors the private RedditResponseParser.MAX_ITEMS (M1-409): a listing
    // with exactly MAX_ITEMS children parses, the cap+1-th raises.
    private static final int MAX_ITEMS = 1000;

    @Test
    void overCapListing_isRejected() {
        byte[] body = listingWith(MAX_ITEMS + 1).getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class,
            () -> RedditResponseParser.parse(42L, body, FETCHED_AT),
            "a listing with more than MAX_ITEMS children must be rejected");
    }

    @Test
    void atCapListing_parsesNormally() throws IOException {
        byte[] body = listingWith(MAX_ITEMS).getBytes(StandardCharsets.UTF_8);

        RedditResponseParser.ListingPage page =
            assertDoesNotThrow(() -> RedditResponseParser.parse(42L, body, FETCHED_AT));

        assertEquals(MAX_ITEMS, page.posts().size(),
            "a listing at the cap must parse every child");
    }

    /** Build a Reddit listing JSON body carrying {@code count} minimal children. */
    private static String listingWith(int count) {
        StringBuilder sb = new StringBuilder("{\"kind\":\"Listing\",\"data\":{\"after\":null,\"children\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"kind\":\"t3\",\"data\":{\"name\":\"t3_p")
              .append(i)
              .append("\",\"title\":\"t\",\"permalink\":\"/r/s/comments/p")
              .append(i)
              .append("/t/\",\"created_utc\":1700000000.0}}");
        }
        return sb.append("]}}").toString();
    }
}
