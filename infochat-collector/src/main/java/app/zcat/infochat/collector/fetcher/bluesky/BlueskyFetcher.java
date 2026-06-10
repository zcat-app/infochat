package app.zcat.infochat.collector.fetcher.bluesky;

import app.zcat.infochat.collector.fetch.FetcherKind;
import app.zcat.infochat.collector.fetcher.PaginationSaturationTracker;
import app.zcat.infochat.core.ingest.Fetcher;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import app.zcat.infochat.ssrf.UrlRedactor;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jspecify.annotations.Nullable;

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
 * is the full XRPC {@code getAuthorFeed} URL carrying the account's
 * {@code actor} query parameter — the URL form per decision D38 and the
 * §2.2.1 decision record in design 02-schema, e.g.
 * {@code https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed?actor=example.dev}.
 * The fetcher requests that URL directly (same shape as the sibling
 * HTTP-shaped fetchers); no authentication is required for public
 * feeds in v1.
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

    private final SsrfGuardedHttpClient client;
    private final int pageCap;

    public BlueskyFetcher() {
        this(new SsrfGuardedHttpClient(), resolvePageCap());
    }

    BlueskyFetcher(SsrfGuardedHttpClient client, int pageCap) {
        this.client = client;
        this.pageCap = pageCap;
    }

    private static int resolvePageCap() {
        return ConfigProvider.getConfig()
            .getOptionalValue("infochat.fetch.bluesky.page-cap", Integer.class)
            .orElse(5);
    }

    @Override
    public List<NormalizedPost> fetch(long dispatchKey, String identifier) {
        Instant fetchedAt = Instant.now();
        List<NormalizedPost> allPosts = new ArrayList<>();
        String cursor = null;

        for (int page = 0; page < pageCap; page++) {
            URI uri = buildPageUri(identifier, cursor);

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
                BlueskyResponseParser.parse(dispatchKey, response.body(), fetchedAt);
            allPosts.addAll(parsed.posts());

            cursor = parsed.cursor();
            if (cursor == null) {
                break;
            }
        }
        if (cursor != null) {
            // Cap exhausted with a cursor still outstanding — the
            // source produced more pages than one tick may drain
            // (spec §Ingest SPIs saturation counter).
            PaginationSaturationTracker.signalCapHit();
        }

        return Collections.unmodifiableList(allPosts);
    }

    private static URI buildPageUri(String identifier, @Nullable String cursor) {
        if (cursor == null) {
            return URI.create(identifier);
        }
        // cursor is upstream-supplied (untrusted): encode so a value
        // containing & / # / ? cannot inject or truncate the query.
        char separator = identifier.indexOf('?') >= 0 ? '&' : '?';
        return URI.create(identifier + separator + "cursor="
            + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
    }

    public static final class BlueskyFetchException extends RuntimeException {
        public BlueskyFetchException(String message) {
            super(message);
        }

        public BlueskyFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
