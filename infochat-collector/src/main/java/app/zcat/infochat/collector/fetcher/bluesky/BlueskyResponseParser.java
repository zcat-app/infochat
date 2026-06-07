package app.zcat.infochat.collector.fetcher.bluesky;

import app.zcat.infochat.core.ingest.NormalizedPost;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateless parser for Bluesky {@code app.bsky.feed.getAuthorFeed}
 * responses. Parallel to {@link app.zcat.infochat.collector.fetcher.rss.RssFeedParser}
 * — a static {@code parse()} entry point that maps the upstream JSON
 * into {@link NormalizedPost} records.
 *
 * <p>Returns a {@link Page} that bundles the parsed posts with the
 * optional pagination cursor, because the Bluesky API uses cursor-based
 * pagination and the caller ({@link BlueskyFetcher}) needs both.
 */
public final class BlueskyResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BlueskyResponseParser() {}

    /**
     * Parse a single page of an {@code app.bsky.feed.getAuthorFeed}
     * response into {@link NormalizedPost} records.
     *
     * @param sourceId  the {@code source.id} stamped onto every post
     * @param body      raw JSON response bytes
     * @param fetchedAt shared timestamp for the entire fetch batch
     * @return a {@link Page} containing the parsed posts and the cursor
     *         for the next page (null when this is the last page)
     */
    public static Page parse(long sourceId, byte [] body, Instant fetchedAt) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (IOException e) {
            throw new BlueskyParseException("Failed to parse Bluesky response as JSON", e);
        }

        JsonNode feedNode = root.path("feed");
        if (feedNode.isMissingNode() || !feedNode.isArray()) {
            return new Page(Collections.emptyList(), null);
        }

        List<NormalizedPost> posts = new ArrayList<>(feedNode.size());
        for (JsonNode entry : feedNode) {
            posts.add(parseEntry(sourceId, entry, fetchedAt));
        }

        String cursor = root.has("cursor") && !root.get("cursor").isNull()
            ? root.get("cursor").asText()
            : null;

        return new Page(Collections.unmodifiableList(posts), cursor);
    }

    private static NormalizedPost parseEntry(long sourceId, JsonNode entry, Instant fetchedAt) {
        JsonNode postNode = entry.path("post");
        if (postNode.isMissingNode()) {
            throw new BlueskyParseException("Feed entry missing 'post' field");
        }

        String uri = requireText(postNode, "uri");
        JsonNode authorNode = postNode.path("author");
        String handle = textOrNull(authorNode, "handle");
        String displayName = textOrNull(authorNode, "displayName");

        JsonNode recordNode = postNode.path("record");
        String text = textOrNull(recordNode, "text");

        String indexedAtRaw = textOrNull(postNode, "indexedAt");
        Instant publishedAt = indexedAtRaw != null ? Instant.parse(indexedAtRaw) : null;

        // AT URI: at://<did>/<collection>/<rkey> — extract rkey for web URL
        String webUrl = buildWebUrl(uri, handle);

        Map<String, String> rawMetadata = new LinkedHashMap<>(4);
        if (handle != null) {
            rawMetadata.put("handle", handle);
        }
        if (displayName != null) {
            rawMetadata.put("displayName", displayName);
        }
        rawMetadata.put("likeCount", String.valueOf(postNode.path("likeCount").asInt(0)));
        rawMetadata.put("repostCount", String.valueOf(postNode.path("repostCount").asInt(0)));

        return new NormalizedPost(
            sourceId,
            uri,
            null,
            text != null ? text : "",
            webUrl,
            publishedAt,
            fetchedAt,
            Collections.unmodifiableMap(rawMetadata)
        );
    }

    /**
     * Build the Bluesky web URL from an AT URI and the author handle.
     * AT URI format: {@code at://<did>/<collection>/<rkey>}.
     * Web URL format: {@code https://bsky.app/profile/<handle>/post/<rkey>}.
     */
    private static @Nullable String buildWebUrl(String atUri, @Nullable String handle) {
        if (handle == null) {
            return null;
        }
        int lastSlash = atUri.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == atUri.length() - 1) {
            return null;
        }
        String rkey = atUri.substring(lastSlash + 1);
        return "https://bsky.app/profile/" + handle + "/post/" + rkey;
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode child = node.path(field);
        if (child.isMissingNode() || child.isNull() || !child.isTextual()) {
            throw new BlueskyParseException("Missing or non-text field '" + field + "' in post");
        }
        return child.asText();
    }

    private static @Nullable String textOrNull(JsonNode node, String field) {
        JsonNode child = node.path(field);
        if (child.isMissingNode() || child.isNull()) {
            return null;
        }
        return child.asText();
    }

    /**
     * One page of parsed Bluesky feed results.
     *
     * @param posts  the parsed posts in source-supplied order
     * @param cursor the pagination cursor for the next page, or null if
     *               this is the last page
     */
    public record Page(List<NormalizedPost> posts, @Nullable String cursor) {}

    public static final class BlueskyParseException extends RuntimeException {
        public BlueskyParseException(String message) {
            super(message);
        }

        public BlueskyParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
