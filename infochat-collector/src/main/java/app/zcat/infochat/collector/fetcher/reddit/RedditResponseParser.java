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

    // Per-response item-count cap, parity with RssFeedParser.MAX_ITEMS
    // (M1-409). A single listing page carries far fewer than 1000 children;
    // the cap bounds the per-response allocation against a hostile listing
    // serving an unbounded children array — defense in depth above the SSRF
    // 5 MiB body cap, which already bounds it absolutely. Checked on the raw
    // children count (before the per-entry skip) so a malformed-heavy reply
    // is rejected on size, not silently shrunk to an empty post list.
    private static final int MAX_ITEMS = 1000;

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

        if (children.size() > MAX_ITEMS) {
            throw new IOException("listing item count exceeded " + MAX_ITEMS);
        }

        List<NormalizedPost> posts = new ArrayList<>(children.size());
        for (JsonNode child : children) {
            NormalizedPost post = mapPost(dispatchKey, child.path("data"), fetchedAt);
            if (post != null) {
                posts.add(post);
            }
        }
        return new ListingPage(List.copyOf(posts), after);
    }

    private static @Nullable NormalizedPost mapPost(long dispatchKey, JsonNode data, Instant fetchedAt) {
        // Validate the upstream identifier at the parse boundary, the way
        // RssFeedParser rejects an item with neither <guid> nor <link>.
        // asText() maps a missing/null "name" node to "", which downstream
        // trips PostPersister's NormalizedPost-SPI non-empty-identifier
        // assertion and aborts the WHOLE tick. Skip the single malformed
        // entry here instead so the rest of the listing still persists.
        // Only dispatchKey in the log — the item's fields are
        // upstream-controlled text.
        String name = data.path("name").asText();
        if (name.isEmpty()) {
            LOG.warn("Reddit listing entry missing name for source_id={}; skipping entry",
                dispatchKey);
            return null;
        }
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
        // Missing/empty permalink: the post still carries title + selftext, so
        // the content-free bare-domain fallback is tolerated rather than
        // skipping the item — a skip would drop that content permanently, since
        // a re-fetch sees the same malformed item again (the created_utc
        // reasoning above). Logged so the bare-domain URL is a deliberate,
        // observable substitution, not a silent one. Only dispatchKey in the
        // log — the item's fields are upstream-controlled text.
        String permalink = data.path("permalink").asText();
        final String url;
        if (permalink.isEmpty()) {
            LOG.warn("Reddit item missing permalink for source_id={}; using bare-domain URL",
                dispatchKey);
            url = "https://www.reddit.com";
        } else {
            url = "https://www.reddit.com" + permalink;
        }
        return new NormalizedPost(
            dispatchKey,
            name,
            data.path("title").asText(),
            data.path("selftext").asText(""),
            url,
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
