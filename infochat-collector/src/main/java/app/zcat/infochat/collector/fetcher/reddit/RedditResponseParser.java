package app.zcat.infochat.collector.fetcher.reddit;

import app.zcat.infochat.core.ingest.NormalizedPost;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a Reddit listing JSON response into {@link NormalizedPost}
 * instances plus the pagination cursor ({@code after}). Stateless;
 * each call produces an immutable {@link ListingPage}.
 *
 * <p>Reddit's listing envelope is {@code {kind:"Listing", data:{after, children:[{kind, data}]}}}.
 * The {@code after} value is the fullname cursor for the next page
 * ({@code null} when no more pages exist).
 */
final class RedditResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * One page of a Reddit listing: the parsed posts plus the
     * pagination cursor for the next page (null = last page).
     */
    record ListingPage(List<NormalizedPost> posts, @Nullable String after) {}

    private RedditResponseParser() {}

    /**
     * Parse a Reddit listing JSON response body.
     *
     * @throws IOException if the body is not valid JSON or does not
     *         contain the expected listing structure
     */
    static ListingPage parse(long sourceId, byte [] body, Instant fetchedAt)
            throws IOException {
        JsonNode root = MAPPER.readTree(body);
        JsonNode data = root.path("data");
        // textValue() returns null for JSON null and for missing nodes
        String after = data.path("after").textValue();
        JsonNode children = data.path("children");

        List<NormalizedPost> posts = new ArrayList<>(children.size());
        for (JsonNode child : children) {
            posts.add(mapPost(sourceId, child.path("data"), fetchedAt));
        }
        return new ListingPage(List.copyOf(posts), after);
    }

    private static NormalizedPost mapPost(long sourceId, JsonNode data, Instant fetchedAt) {
        return new NormalizedPost(
            sourceId,
            data.path("name").asText(),
            data.path("title").asText(),
            data.path("selftext").asText(""),
            "https://www.reddit.com" + data.path("permalink").asText(),
            Instant.ofEpochSecond((long) data.path("created_utc").asDouble()),
            fetchedAt,
            buildRawMetadata(data)
        );
    }

    private static Map<String, String> buildRawMetadata(JsonNode data) {
        Map<String, String> metadata = new LinkedHashMap<>(4);
        metadata.put("author", data.path("author").asText(""));
        metadata.put("score", String.valueOf(data.path("score").asInt(0)));
        metadata.put("num_comments", String.valueOf(data.path("num_comments").asInt(0)));
        metadata.put("subreddit", data.path("subreddit").asText(""));
        return Map.copyOf(metadata);
    }
}
