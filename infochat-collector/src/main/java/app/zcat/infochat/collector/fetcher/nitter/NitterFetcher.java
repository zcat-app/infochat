package app.zcat.infochat.collector.fetcher.nitter;

import app.zcat.infochat.collector.fetch.FetcherKind;
import app.zcat.infochat.collector.fetcher.rss.RssFeedParser;
import app.zcat.infochat.core.ingest.Fetcher;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import app.zcat.infochat.ssrf.UrlRedactor;
import jakarta.enterprise.context.ApplicationScoped;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Instant;
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
    public List<NormalizedPost> fetch(long sourceId, @NonNull String identifier) {
        Instant fetchedAt = Instant.now();

        HttpResponse<byte[]> response;
        try {
            response = client.get(URI.create(identifier));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NitterFetchException(
                "Nitter fetch interrupted for " + UrlRedactor.redact(identifier), e);
        } catch (IOException e) {
            throw new NitterFetchException(
                "Nitter fetch I/O failure for " + UrlRedactor.redact(identifier)
                + ": " + e.getMessage(), e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new NitterFetchException(
                "Nitter fetch got HTTP " + status + " for " + UrlRedactor.redact(identifier));
        }

        return RssFeedParser.parse(sourceId, response.body(), fetchedAt);
    }

    /**
     * Unchecked transport-layer failure. The FetchScheduler's per-tick
     * error handler catches this and runs D42's per-source failure-counter
     * update.
     */
    public static final class NitterFetchException extends RuntimeException {
        public NitterFetchException(@NonNull String message) {
            super(message);
        }

        public NitterFetchException(@NonNull String message, @NonNull Throwable cause) {
            super(message, cause);
        }
    }
}
