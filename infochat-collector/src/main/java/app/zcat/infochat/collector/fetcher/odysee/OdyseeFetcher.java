package app.zcat.infochat.collector.fetcher.odysee;

import app.zcat.infochat.collector.fetch.FetcherKind;
import app.zcat.infochat.collector.fetcher.rss.RssFeedParser;
import app.zcat.infochat.core.ingest.Fetcher;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import app.zcat.infochat.ssrf.UrlRedactor;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

/**
 * Fetcher for {@code source.kind = 'odysee'}. Issues a single HTTP GET
 * against the Odysee channel RSS endpoint
 * ({@code https://odysee.com/$/rss/@ChannelName}) through the shared
 * {@link SsrfGuardedHttpClient}, then delegates parsing to
 * {@link RssFeedParser} — Odysee serves standard RSS 2.0.
 *
 * <p>No pagination: Odysee RSS endpoints return the full recent feed in
 * a single response.
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
@FetcherKind("odysee")
@ApplicationScoped
public class OdyseeFetcher implements Fetcher {

    private final SsrfGuardedHttpClient client;

    public OdyseeFetcher() {
        this(new SsrfGuardedHttpClient());
    }

    // Package-private test seam — the test supplies a SsrfGuardedHttpClient
    // configured with a loopback-permitting blocklist.
    OdyseeFetcher(SsrfGuardedHttpClient client) {
        this.client = client;
    }

    @Override
    public List<NormalizedPost> fetch(long dispatchKey, String identifier) {
        Instant fetchedAt = Instant.now();

        HttpResponse<byte[]> response;
        try {
            response = client.get(URI.create(identifier));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OdyseeFetchException(
                "Odysee fetch interrupted for " + UrlRedactor.redact(identifier), e);
        } catch (IOException e) {
            throw new OdyseeFetchException(
                "Odysee fetch I/O failure for " + UrlRedactor.redact(identifier)
                + ": " + e.getMessage(), e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new OdyseeFetchException(
                "Odysee fetch got HTTP " + status + " for " + UrlRedactor.redact(identifier));
        }

        return RssFeedParser.parse(dispatchKey, response.body(), fetchedAt);
    }

    /**
     * Unchecked transport-layer failure. The FetchScheduler's per-tick
     * error handler catches this and runs D42's per-source failure-counter
     * update.
     */
    public static final class OdyseeFetchException extends RuntimeException {
        public OdyseeFetchException(String message) {
            super(message);
        }

        public OdyseeFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
