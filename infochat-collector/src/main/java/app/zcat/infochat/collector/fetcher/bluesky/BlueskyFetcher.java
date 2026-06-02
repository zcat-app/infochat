package app.zcat.infochat.collector.fetcher.bluesky;

import app.zcat.infochat.collector.fetch.FetcherKind;
import app.zcat.infochat.core.ingest.Fetcher;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import app.zcat.infochat.ssrf.UrlRedactor;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Polled {@link Fetcher} for Bluesky feeds via the AT Protocol public
 * API ({@code app.bsky.feed.getAuthorFeed}). The source's identifier
 * is the DID or handle of the account to follow; no authentication is
 * required for public feeds in v1.
 *
 * <p>Unlike {@link app.zcat.infochat.collector.fetcher.rss.RssFetcher}
 * (which issues a single GET per tick), this fetcher paginates
 * cursor-based within a single tick up to the profile-driven page cap
 * (5 on laptop/vps/remote-llm, 2 on pi per design §1.6).
 *
 * <p>{@code fetchedAt} is captured once, before the first HTTP call.
 * Every {@link NormalizedPost} produced by one {@code fetch()} invocation
 * shares the same {@code fetchedAt} — partition-key semantics per the
 * schema (same invariant as RssFetcher).
 */
@FetcherKind("bluesky")
@ApplicationScoped
public class BlueskyFetcher implements Fetcher {

    private static final String DEFAULT_XRPC_BASE =
        "https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed";

    private final SsrfGuardedHttpClient client;
    private final int pageCap;
    private final String xrpcBase;

    public BlueskyFetcher() {
        this(new SsrfGuardedHttpClient(), resolvePageCap(), resolveXrpcBase());
    }

    BlueskyFetcher(SsrfGuardedHttpClient client, int pageCap, String xrpcBase) {
        this.client = client;
        this.pageCap = pageCap;
        this.xrpcBase = xrpcBase;
    }

    private static int resolvePageCap() {
        return ConfigProvider.getConfig()
            .getOptionalValue("infochat.fetch.bluesky.page-cap", Integer.class)
            .orElse(5);
    }

    private static String resolveXrpcBase() {
        return ConfigProvider.getConfig()
            .getOptionalValue("infochat.fetch.bluesky.api-base-url", String.class)
            .orElse(DEFAULT_XRPC_BASE);
    }

    @Override
    public List<NormalizedPost> fetch(long sourceId, @NonNull String identifier) {
        Instant fetchedAt = Instant.now();
        List<NormalizedPost> allPosts = new ArrayList<>();
        String cursor = null;

        for (int page = 0; page < pageCap; page++) {
            URI uri = buildUri(identifier, cursor);

            HttpResponse<byte[]> response;
            try {
                response = client.get(uri);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BlueskyFetchException(
                    "Bluesky fetch interrupted for " + UrlRedactor.redact(identifier), e);
            } catch (IOException e) {
                throw new BlueskyFetchException(
                    "Bluesky fetch I/O failure for " + UrlRedactor.redact(identifier)
                    + ": " + e.getMessage(), e);
            }

            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new BlueskyFetchException(
                    "Bluesky fetch got HTTP " + status + " for " + UrlRedactor.redact(identifier));
            }

            BlueskyResponseParser.Page parsed =
                BlueskyResponseParser.parse(sourceId, response.body(), fetchedAt);
            allPosts.addAll(parsed.posts());

            cursor = parsed.cursor();
            if (cursor == null) {
                break;
            }
        }

        return Collections.unmodifiableList(allPosts);
    }

    private URI buildUri(String actor, String cursor) {
        StringBuilder sb = new StringBuilder(xrpcBase)
            .append("?actor=").append(actor);
        if (cursor != null) {
            // cursor is upstream-supplied (untrusted): encode so a value
            // containing & / # / ? cannot inject or truncate the query.
            sb.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        }
        return URI.create(sb.toString());
    }

    public static final class BlueskyFetchException extends RuntimeException {
        public BlueskyFetchException(@NonNull String message) {
            super(message);
        }

        public BlueskyFetchException(@NonNull String message, @NonNull Throwable cause) {
            super(message, cause);
        }
    }
}
