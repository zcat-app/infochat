package app.zcat.infochat.collector.fetcher.nitter;

import app.zcat.infochat.collector.fetch.FetcherKind;
import app.zcat.infochat.collector.fetcher.SingleGetFetch;
import app.zcat.infochat.core.ingest.Fetcher;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
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

    @Override
    public List<NormalizedPost> fetch(long dispatchKey, String identifier) {
        return SingleGetFetch.fetchAndParse(
            client::get, "Nitter", dispatchKey, identifier,
            (message, cause) -> cause == null
                ? new NitterFetchException(message)
                : new NitterFetchException(message, cause));
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
