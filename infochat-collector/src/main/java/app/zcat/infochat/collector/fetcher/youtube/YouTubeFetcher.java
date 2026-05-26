package app.zcat.infochat.collector.fetcher.youtube;

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
 * Fetcher for YouTube channel Atom feeds. YouTube exposes recent uploads
 * at {@code https://www.youtube.com/feeds/videos.xml?channel_id=<id>}
 * as standard Atom 1.0. The identifier stored in {@code source.identifier}
 * is this full feed URL; this Fetcher GETs it through
 * {@link SsrfGuardedHttpClient} and delegates parsing to
 * {@link RssFeedParser}, which already handles Atom 1.0.
 *
 * <p>No pagination — YouTube channel Atom feeds are single-request
 * (typically the 15 most recent uploads).
 *
 * <p>{@code fetchedAt} is captured once before the HTTP call so every
 * returned {@link NormalizedPost} shares the same partition-key value,
 * matching the semantics documented in {@link app.zcat.infochat.collector.fetcher.rss.RssFetcher}.
 */
@FetcherKind("youtube")
@ApplicationScoped
public class YouTubeFetcher implements Fetcher {

    private final SsrfGuardedHttpClient client;

    public YouTubeFetcher() {
        this(new SsrfGuardedHttpClient());
    }

    YouTubeFetcher(SsrfGuardedHttpClient client) {
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
            throw new YouTubeFetchException(
                "YouTube fetch interrupted for " + UrlRedactor.redact(identifier), e);
        } catch (IOException e) {
            throw new YouTubeFetchException(
                "YouTube fetch I/O failure for " + UrlRedactor.redact(identifier)
                + ": " + e.getMessage(), e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new YouTubeFetchException(
                "YouTube fetch got HTTP " + status + " for " + UrlRedactor.redact(identifier));
        }

        return RssFeedParser.parse(sourceId, response.body(), fetchedAt);
    }

    /**
     * Unchecked transport-layer failure for YouTube fetches. Propagates
     * through the Fetcher SPI to FetchScheduler's per-tick error handler.
     */
    public static final class YouTubeFetchException extends RuntimeException {
        public YouTubeFetchException(@NonNull String message) {
            super(message);
        }

        public YouTubeFetchException(@NonNull String message, @NonNull Throwable cause) {
            super(message, cause);
        }
    }
}
