package app.zcat.infochat.collector.fetcher.nitter;

import app.zcat.infochat.collector.fetch.FetcherKind;
import app.zcat.infochat.collector.fetcher.SingleGetFetch;
import app.zcat.infochat.core.ingest.Fetcher;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import app.zcat.infochat.ssrf.UrlRedactor;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Fetcher for {@code source.kind = 'nitter'}. Issues a single HTTP GET
 * against a Nitter instance's RSS endpoint ({@code /<username>/rss})
 * through the shared {@link SsrfGuardedHttpClient}, then delegates
 * parsing to {@link RssFeedParser} — Nitter serves standard RSS 2.0.
 *
 * <p>No pagination: Nitter RSS endpoints return the full recent feed in
 * a single response. The spec's §Ingest SPIs pagination note refers to
 * Nitter's web/API layer; the RSS endpoint has no pagination cursor.
 *
 * <p>{@code fetchedAt} is captured once, before the HTTP call. Every
 * {@link NormalizedPost} produced by one {@code fetch()} invocation
 * shares the same {@code fetchedAt} — the partition key downstream
 * {@code post} writes depend on.
 *
 * <p>Exception messages interpolate {@link UrlRedactor#redact} of the
 * identifier, never the raw value — feed URLs may carry credentials or
 * query-string tokens that must not reach exception traces.
 */
@FetcherKind("nitter")
@ApplicationScoped
public class NitterFetcher implements Fetcher {

    private final SsrfGuardedHttpClient client;

    public NitterFetcher() {
        this(new SsrfGuardedHttpClient());
    }

    // Package-private test seam — the test supplies a SsrfGuardedHttpClient
    // configured with a loopback-permitting blocklist.
    NitterFetcher(SsrfGuardedHttpClient client) {
        this.client = client;
    }

    // Sole item of xcancel's anti-scraping placeholder feed served when our
    // RSS reader is not whitelisted. The parser does not trim <title>, so the
    // stripped title is compared; the notice substring is stable while the
    // per-reader hex id in the rest of the body varies (M1-588).
    private static final String XCANCEL_PLACEHOLDER_TITLE = "RSS reader not yet whitelisted!";
    private static final String XCANCEL_PLACEHOLDER_NOTICE = "get your RSS feed reader whitelisted";

    @Override
    public List<NormalizedPost> fetch(long dispatchKey, String identifier) {
        List<NormalizedPost> posts = SingleGetFetch.fetchAndParse(
            client::get, "Nitter", dispatchKey, identifier,
            (message, cause) -> cause == null
                ? new NitterFetchException(message)
                : new NitterFetchException(message, cause));

        // A xcancel instance that has not whitelisted our reader returns HTTP
        // 200 + a well-formed RSS feed whose sole item is a whitelist sentinel,
        // not tweets. That parses cleanly, so without this guard the collector
        // would record a successful fetch and ingest the stub — a dead source
        // would look healthy. Signal a Fetcher failure so FetchScheduler runs
        // D42's per-source counter and the source eventually flips to
        // status='failed' (M1-588). The match is exact — sole item, exact
        // title, whitelist notice — so a real tweet mentioning "whitelist" is
        // never dropped.
        if (isXcancelWhitelistPlaceholder(posts)) {
            throw new NitterFetchException(
                "Nitter feed for " + UrlRedactor.redact(identifier)
                + " is the xcancel whitelist placeholder, not content — treating as a degraded feed (D42)");
        }

        return posts;
    }

    private static boolean isXcancelWhitelistPlaceholder(List<NormalizedPost> posts) {
        if (posts.size() != 1) {
            return false;
        }
        NormalizedPost only = posts.get(0);
        return only.title() != null
            && XCANCEL_PLACEHOLDER_TITLE.equals(only.title().strip())
            && only.body().contains(XCANCEL_PLACEHOLDER_NOTICE);
    }

    /**
     * Unchecked transport-layer failure. The FetchScheduler's per-tick
     * error handler catches this and runs D42's per-source failure-counter
     * update.
     */
    public static final class NitterFetchException extends RuntimeException {
        public NitterFetchException(String message) {
            super(message);
        }

        public NitterFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
