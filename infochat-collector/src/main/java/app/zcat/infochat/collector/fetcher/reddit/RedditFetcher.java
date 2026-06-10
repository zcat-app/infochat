package app.zcat.infochat.collector.fetcher.reddit;

import app.zcat.infochat.collector.fetch.FetcherKind;
import app.zcat.infochat.collector.fetcher.PaginationSaturationTracker;
import app.zcat.infochat.core.ingest.Fetcher;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import app.zcat.infochat.ssrf.UrlRedactor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fetcher for Reddit subreddits via the public {@code .json} endpoint.
 * Appends {@code .json} to the source identifier (the subreddit URL)
 * and paginates using Reddit's {@code after} fullname cursor up to the
 * profile-driven page cap per {@code docs/design/01-architecture.md}
 * &sect;1.6 (5 on laptop/vps/remote-llm, 2 on pi).
 *
 * <p>Reddit blocks requests with the default JDK User-Agent, so every
 * request carries a descriptive {@code User-Agent} header.
 *
 * <p>{@code fetchedAt} is captured once before the first HTTP call —
 * all posts from one {@code fetch()} invocation share the same
 * timestamp (partition-key invariant, same rationale as RssFetcher).
 *
 * <p>Non-2xx responses and JSON parse failures propagate as unchecked
 * {@link RedditFetchException}s; the FetchScheduler's per-tick error
 * handler catches and logs them. Exception messages interpolate
 * {@link UrlRedactor#redact} of the identifier, never the raw URL.
 */
@FetcherKind("reddit")
@ApplicationScoped
public class RedditFetcher implements Fetcher {

    static final String USER_AGENT = "infochat/1.0 (news aggregator)";

    private final SsrfGuardedHttpClient client;
    private final int pageCap;

    @Inject
    RedditFetcher(@ConfigProperty(name = "infochat.fetch.reddit.page-cap") int pageCap) {
        this(new SsrfGuardedHttpClient(), pageCap);
    }

    // Package-private test seam — test supplies a SsrfGuardedHttpClient
    // with a permissive IpBlocklist and a controlled page cap.
    RedditFetcher(SsrfGuardedHttpClient client, int pageCap) {
        this.client = client;
        this.pageCap = pageCap;
    }

    @Override
    public List<NormalizedPost> fetch(long dispatchKey, String identifier) {
        Instant fetchedAt = Instant.now();
        List<NormalizedPost> allPosts = new ArrayList<>();
        String afterCursor = null;

        for (int page = 0; page < pageCap; page++) {
            URI uri = buildPageUri(identifier, afterCursor);

            HttpResponse<byte[]> response;
            try {
                response = client.get(uri, Map.of("User-Agent", USER_AGENT));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RedditFetchException(
                    "Reddit fetch interrupted for " + UrlRedactor.redact(identifier), e);
            } catch (IOException e) {
                throw new RedditFetchException(
                    "Reddit fetch I/O failure for " + UrlRedactor.redact(identifier)
                    + ": " + e.getMessage(), e);
            }

            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new RedditFetchException(
                    "Reddit fetch got HTTP " + status
                    + " for " + UrlRedactor.redact(identifier));
            }

            RedditResponseParser.ListingPage listing;
            try {
                listing = RedditResponseParser.parse(dispatchKey, response.body(), fetchedAt);
            } catch (IOException e) {
                throw new RedditFetchException(
                    "Reddit JSON parse failure for " + UrlRedactor.redact(identifier)
                    + ": " + e.getMessage(), e);
            }

            allPosts.addAll(listing.posts());
            afterCursor = listing.after();
            if (afterCursor == null) {
                break;
            }
        }
        if (afterCursor != null) {
            // Cap exhausted with a cursor still outstanding — the
            // source produced more pages than one tick may drain
            // (spec §Ingest SPIs saturation counter).
            PaginationSaturationTracker.signalCapHit();
        }
        return allPosts;
    }

    private static URI buildPageUri(String identifier, @Nullable String afterCursor) {
        String url = identifier + ".json";
        if (afterCursor != null) {
            // afterCursor is upstream-supplied (untrusted): encode so a value
            // containing & / # / ? cannot inject or truncate the query.
            url += "?after=" + URLEncoder.encode(afterCursor, StandardCharsets.UTF_8);
        }
        return URI.create(url);
    }

    /**
     * Unchecked transport or parse failure. The FetchScheduler's
     * per-tick error handler catches this and logs it.
     */
    public static final class RedditFetchException extends RuntimeException {
        public RedditFetchException(String message) {
            super(message);
        }

        public RedditFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
