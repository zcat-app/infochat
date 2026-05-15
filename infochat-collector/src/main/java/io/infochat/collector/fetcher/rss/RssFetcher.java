package io.infochat.collector.fetcher.rss;

import io.infochat.core.ingest.Fetcher;
import io.infochat.core.ingest.NormalizedPost;
import io.infochat.ssrf.SsrfGuardedHttpClient;
import io.infochat.ssrf.UrlRedactor;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

/**
 * First concrete binding to {@link Fetcher} for {@code source.kind = 'rss'}.
 * Issues a single HTTP GET against the source's identifier URL through
 * the shared {@link SsrfGuardedHttpClient} (IP blocklist, DNS-rebind
 * defense, redirect cap, scheme allowlist, body-size cap per
 * {@code docs/spec/security.md} §SSRF and outbound connections), parses
 * the response as RSS 2.0 or Atom 1.0 via {@link RssFeedParser}, and
 * returns the resulting {@link NormalizedPost}s in source-supplied order.
 *
 * <p>RSS has no pagination per {@code docs/design/01-architecture.md}
 * §1.6 — per-tick pagination cap is 1, so this Fetcher is a one-request-
 * per-call shape. Retry / backoff / Retry-After honoring lives on the
 * FetchScheduler at the per-tick boundary (microprofile-faulttolerance),
 * not here; a non-2xx response propagates as an unchecked exception that
 * the scheduler's per-tick error handler catches and feeds into D42's
 * per-source failure-counter model.
 *
 * <p>{@code fetchedAt} is captured once, before the HTTP call. Every
 * {@link NormalizedPost} produced by one {@code fetch()} invocation
 * therefore shares the same {@code fetchedAt} — the partition key
 * downstream {@code post} writes depend on (M1-008c's V7 partitions
 * {@code post} by {@code RANGE (fetched_at)}). Capturing per-item would
 * scatter a slow parse across partitions; capturing once preserves the
 * "moment of fetch" semantics the schema expects.
 *
 * <p>Exception messages interpolate {@link UrlRedactor#redact} of the
 * identifier, never the raw value — feed URLs may carry credentials or
 * query-string tokens that must not reach exception traces.
 */
@ApplicationScoped
public class RssFetcher implements Fetcher {

    // SsrfGuardedHttpClient is thread-safe; the FetchScheduler in
    // T1-C shares one RssFetcher across ticks, so we share one client.
    private final SsrfGuardedHttpClient client;

    public RssFetcher() {
        this(new SsrfGuardedHttpClient());
    }

    // Package-private constructor injection — the test seam. The
    // RssFetcherTest fixture binds an HttpServer to 127.0.0.1 which
    // the strict production blocklist refuses to dial; the test
    // supplies a SsrfGuardedHttpClient configured with a permissive
    // IpBlocklist subclass.
    RssFetcher(SsrfGuardedHttpClient client) {
        this.client = client;
    }

    @Override
    public List<NormalizedPost> fetch(long sourceId, String identifier) {
        // Capture once, before the HTTP call — see the class-level
        // javadoc on partition-key semantics.
        Instant fetchedAt = Instant.now();

        HttpResponse<byte[]> response;
        try {
            response = client.get(URI.create(identifier));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RssFetchException(
                "RSS fetch interrupted for " + UrlRedactor.redact(identifier), e);
        } catch (IOException e) {
            throw new RssFetchException(
                "RSS fetch I/O failure for " + UrlRedactor.redact(identifier)
                + ": " + e.getMessage(), e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new RssFetchException(
                "RSS fetch got HTTP " + status + " for " + UrlRedactor.redact(identifier));
        }

        return RssFeedParser.parse(sourceId, response.body(), fetchedAt);
    }

    /**
     * Unchecked transport-layer failure. The Fetcher SPI does not declare
     * checked exceptions; the FetchScheduler's per-tick error handler in
     * T1-C catches this and runs D42's per-source failure-counter update.
     */
    public static final class RssFetchException extends RuntimeException {
        public RssFetchException(String message) {
            super(message);
        }

        public RssFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
