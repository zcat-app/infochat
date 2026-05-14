package io.infochat.collector.fetcher.rss;

import io.infochat.core.ingest.Fetcher;
import io.infochat.core.ingest.NormalizedPost;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * First concrete binding to {@link Fetcher} for {@code source.kind = 'rss'}.
 * Issues a single HTTP GET against the source's identifier URL, parses
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
 * <p><strong>SSRF GATE TODO</strong>: this Fetcher's outbound HTTP path
 * is NOT yet routed through {@code infochat-ssrf} (the shared
 * IP-blocklist + DNS-rebind defense + redirect-cap module per
 * {@code docs/spec/security.md} §SSRF and outbound connections). A
 * follow-up ticket lands {@code infochat-ssrf} before the FetchScheduler
 * in T1-C wires this Fetcher to production traffic. Until that follow-up
 * lands, this class is instantiable but is NOT injected into any
 * scheduler; the only production callers post-T1-C must route through
 * the as-yet-unauthored SSRF gate. See M1-023 Big-picture notes.
 */
public class RssFetcher implements Fetcher {

    private static final String USER_AGENT = "infochat/0.0.1-SNAPSHOT";

    private static final String ACCEPT_HEADER =
        "application/rss+xml, application/atom+xml, application/xml;q=0.9, */*;q=0.8";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    // HttpClient is thread-safe per JDK contract; the FetchScheduler in
    // T1-C shares one RssFetcher across ticks, so we share one client.
    private final HttpClient httpClient;

    public RssFetcher() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    @Override
    public List<NormalizedPost> fetch(long sourceId, String identifier) {
        // Capture once, before the HTTP call — see the class-level
        // javadoc on partition-key semantics.
        Instant fetchedAt = Instant.now();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(identifier))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", ACCEPT_HEADER)
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RssFetchException(
                "RSS fetch interrupted for " + identifier, e);
        } catch (IOException e) {
            throw new RssFetchException(
                "RSS fetch I/O failure for " + identifier + ": " + e.getMessage(), e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new RssFetchException(
                "RSS fetch got HTTP " + status + " for " + identifier);
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
