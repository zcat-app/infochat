package app.zcat.infochat.collector.fetcher.reddit;

import app.zcat.infochat.core.ingest.NormalizedPost;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(RedditResponseParser.class);

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
    static ListingPage parse(long dispatchKey, byte [] body, Instant fetchedAt)
            throws IOException {
        JsonNode root = MAPPER.readTree(body);
        JsonNode data = root.path("data");
        // textValue() returns null for JSON null and for missing nodes
        String after = data.path("after").textValue();
        JsonNode children = data.path("children");

        List<NormalizedPost> posts = new ArrayList<>(children.size());
        for (JsonNode child : children) {
            posts.add(mapPost(dispatchKey, child.path("data"), fetchedAt));
        }
        return new ListingPage(List.copyOf(posts), after);
    }

    private static NormalizedPost mapPost(long dispatchKey, JsonNode data, Instant fetchedAt) {
        // Missing/non-numeric created_utc: substitute the fetch time
        // instead of silently storing epoch 0 (a missing node's
        // asDouble() is 0.0 → every malformed item dated 1970-01-01,
        // sorting to the bottom of every published_at window forever).
        // Substitution is pinned over skipping: a skip would drop the
        // item's content permanently, since a re-fetch sees the same
        // malformed item again. Only dispatchKey in the log — the item's
        // fields are upstream-controlled text.
        JsonNode createdUtc = data.path("created_utc");
        final Instant publishedAt;
        if (createdUtc.isNumber()) {
            publishedAt = Instant.ofEpochSecond(createdUtc.asLong());
        } else {
            LOG.warn("Reddit item missing created_utc for source_id={}; substituting fetch time",
                dispatchKey);
            publishedAt = fetchedAt;
        }
        return new NormalizedPost(
            dispatchKey,
            data.path("name").asText(),
            data.path("title").asText(),
            data.path("selftext").asText(""),
            "https://www.reddit.com" + data.path("permalink").asText(),
            publishedAt,
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
