package app.zcat.infochat.collector.fetcher.reddit;

import app.zcat.infochat.collector.fetch.FetcherKind;
import app.zcat.infochat.collector.fetcher.rss.RssFeedParser;
import app.zcat.infochat.core.ingest.Fetcher;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import app.zcat.infochat.ssrf.UrlRedactor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

/**
 * Fetcher for Reddit listings via the public {@code /.rss} Atom endpoint
 * (M1-915). Live-probed 2026-08-23 from prod's egress: the {@code .json}
 * endpoint is edge-blocked there (403 for every user agent, browser UAs
 * and logged-in feed tokens included), while {@code identifier + "/.rss"}
 * answers 200 — the transport this fetcher rides. The payload is parsed
 * by the shared {@link RssFeedParser} Atom leg: the {@code <id>} t3_
 * fullname is the upstream identifier (uid parity with a future OAuth
 * switch), and the engagement trio stays null because the Atom payload
 * carries no engagement numbers (see docs/design/01-architecture.md
 * &sect;1.6 and the M1-915 ticket's out_of_scope for the deferral).
 *
 * <p>One request per tick: the listing Atom feed has no after-cursor
 * (no {@code rel="next"} link), so there is no pagination and no
 * page-cap — reddit sits in the no-pagination row next to RSS.
 *
 * <p>No {@code User-Agent} override is needed:
 * {@link SsrfGuardedHttpClient} already sets the single descriptive
 * {@code User-Agent} on every hop of every request (M1-704); declaring
 * one locally would append a second value.
 *
 * <p>{@code fetchedAt} is captured once before the HTTP call — all
 * posts from one {@code fetch()} invocation share the same timestamp
 * (partition-key invariant, same rationale as RssFetcher).
 *
 * <p>Non-2xx responses and XML parse failures propagate as unchecked
 * {@link RedditFetchException}s; the FetchScheduler's per-tick error
 * handler catches and logs them. Exception messages interpolate
 * {@link UrlRedactor#redact} of the identifier, never the raw URL.
 */
@FetcherKind("reddit")
@ApplicationScoped
public class RedditFetcher implements Fetcher {

    private final SsrfGuardedHttpClient client;

    @Inject
    RedditFetcher() {
        this(new SsrfGuardedHttpClient());
    }

    // Package-private test seam — test supplies a SsrfGuardedHttpClient
    // with a permissive IpBlocklist.
    RedditFetcher(SsrfGuardedHttpClient client) {
        this.client = client;
    }

    @Override
    public List<NormalizedPost> fetch(long dispatchKey, String identifier) {
        Instant fetchedAt = Instant.now();
        URI uri = buildListingUri(identifier);

        HttpResponse<byte[]> response;
        try {
            response = client.get(uri);
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

        try {
            return RssFeedParser.parse(dispatchKey, response.body(), fetchedAt);
        } catch (RssFeedParser.RssFeedParseException e) {
            throw new RedditFetchException(
                "Reddit feed parse failure for " + UrlRedactor.redact(identifier)
                + ": " + e.getMessage(), e);
        }
    }

    /**
     * The listing's Atom URL. V86-normalized identifiers are bare listing
     * URLs ({@code …/r/<sub>}, {@code …/r/<sub>/hot}); the suffix is
     * appended with at most one slash. An identifier already ending in
     * {@code .rss} (case-insensitive — a hand-registered
     * {@code …/r/<sub>/hot/.rss}) is used as-is, never double-suffixed.
     */
    static URI buildListingUri(String identifier) {
        String url = identifier;
        if (!endsWithRssSuffix(url)) {
            url = url.endsWith("/") ? url + ".rss" : url + "/.rss";
        }
        return URI.create(url);
    }

    private static boolean endsWithRssSuffix(String url) {
        int slash = url.lastIndexOf('/');
        return url.regionMatches(true, slash + 1, ".rss", 0, 4)
            && url.length() == slash + 5;
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
