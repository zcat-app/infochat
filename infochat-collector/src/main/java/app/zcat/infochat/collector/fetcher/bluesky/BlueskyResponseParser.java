package app.zcat.infochat.collector.fetcher.bluesky;

import app.zcat.infochat.core.ingest.NormalizedPost;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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

    // Per-response item-count cap, parity with RssFeedParser.MAX_ITEMS
    // (M1-409). A single getAuthorFeed page carries far fewer than 1000
    // entries; the cap bounds the per-response allocation against a hostile
    // feed serving an unbounded array — defense in depth above the SSRF
    // 5 MiB body cap, which already bounds it absolutely. Checked on the
    // JSON array size up front (the count is known before parsing): a
    // response with exactly MAX_ITEMS entries parses, the cap+1-th raises.
    private static final int MAX_ITEMS = 1000;

    private BlueskyResponseParser() {}

    /**
     * Parse a single page of an {@code app.bsky.feed.getAuthorFeed}
     * response into {@link NormalizedPost} records.
     *
     * @param dispatchKey the per-tick dispatch token stamped onto every post
     *                    (NOT the {@code source.id} UUID)
     * @param body      raw JSON response bytes
     * @param fetchedAt shared timestamp for the entire fetch batch
     * @return a {@link Page} containing the parsed posts and the cursor
     *         for the next page (null when this is the last page)
     */
    public static Page parse(long dispatchKey, byte [] body, Instant fetchedAt) {
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

        if (feedNode.size() > MAX_ITEMS) {
            throw new BlueskyParseException("feed item count exceeded " + MAX_ITEMS);
        }

        List<NormalizedPost> posts = new ArrayList<>(feedNode.size());
        for (JsonNode entry : feedNode) {
            posts.add(parseEntry(dispatchKey, entry, fetchedAt));
        }

        String cursor = root.has("cursor") && !root.get("cursor").isNull()
            ? root.get("cursor").asText()
            : null;

        return new Page(Collections.unmodifiableList(posts), cursor);
    }

    private static NormalizedPost parseEntry(long dispatchKey, JsonNode entry, Instant fetchedAt) {
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
        Instant publishedAt = parseIndexedAtOrNull(indexedAtRaw);

        // AT URI: at://<did>/<collection>/<rkey> — extract rkey for web URL
        String webUrl = buildWebUrl(uri, handle);

        Map<String, String> rawMetadata = new LinkedHashMap<>(4);
        if (handle != null) {
            rawMetadata.put("handle", handle);
        }
        if (displayName != null) {
            rawMetadata.put("displayName", displayName);
        }
        return new NormalizedPost(
            dispatchKey,
            uri,
            null,
            text != null ? text : "",
            webUrl,
            publishedAt,
            fetchedAt,
            Collections.unmodifiableMap(rawMetadata),
            intOrNull(postNode, "likeCount"),
            intOrNull(postNode, "repostCount")
        );
    }

    /**
     * Read an engagement count as a nullable {@link Integer}. Absent,
     * null and non-numeric all yield null, NOT 0 — this is an untrusted
     * upstream boundary where "the API did not report a count" and "the
     * post has zero likes" are different facts, and the ingest columns
     * must keep them apart (M1-723). The former {@code asInt(0)}
     * defaulting collapsed both onto 0.
     *
     * <p>An out-of-{@code int}-range value SATURATES rather than
     * narrowing. {@link JsonNode#asInt()} is a truncating cast, so a
     * JSON integer beyond {@code int} wraps modulo 2^32 —
     * {@code 2147483648} becomes {@code -2147483648},
     * {@code -2147483649} becomes {@code +2147483647}, and
     * {@code 4294967296} becomes exactly {@code 0}. Narrowing first
     * would hand {@link NormalizedPost}'s magnitude bound an
     * already-corrupt value, so the clamp could not restore either the
     * sign or the null/zero distinction: the wrapped negative survives
     * as a maximally-negative social score, and the wrapped zero is
     * persisted as a non-NULL 0 — a "seen and ignored" observation the
     * upstream never made. {@link JsonNode#canConvertToInt()} is false
     * for exactly the values that would wrap, so saturating on it keeps
     * the bound meaningful for any count a hostile feed can express.
     *
     * <p>A non-finite value degrades to null rather than throwing. JSON
     * permits {@code 1e400}, which Jackson parses into a double holding
     * infinity; that is not a representable count, so it takes the same
     * "the API did not report a count" branch as a non-numeric node.
     * Degrading per-entry — rather than letting a coercion failure
     * escape — is the discipline {@link #parseIndexedAtOrNull} already
     * applies to the sibling untrusted field: one malformed entry must
     * not kill every well-formed post in the same page.
     */
    private static @Nullable Integer intOrNull(JsonNode node, String field) {
        JsonNode child = node.path(field);
        if (!child.isNumber()) {
            return null;
        }
        if (!child.canConvertToInt()) {
            double magnitude = child.doubleValue();
            if (!Double.isFinite(magnitude)) {
                return null;
            }
            return magnitude < 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        }
        return child.asInt();
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

    /**
     * Parse the upstream {@code indexedAt} into an {@link Instant}, degrading a
     * single malformed timestamp to {@code null} rather than aborting the whole
     * batch. The Bluesky response is an untrusted system boundary: one feed
     * entry with a non-ISO {@code indexedAt} must not throw and kill every
     * well-formed post in the same page. A null {@code published_at} is already
     * a valid state (it is also what an absent {@code indexedAt} yields);
     * downstream ordering falls back to {@code fetched_at}.
     */
    private static @Nullable Instant parseIndexedAtOrNull(@Nullable String indexedAtRaw) {
        if (indexedAtRaw == null) {
            return null;
        }
        try {
            return Instant.parse(indexedAtRaw);
        } catch (DateTimeParseException e) {
            return null;
        }
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
