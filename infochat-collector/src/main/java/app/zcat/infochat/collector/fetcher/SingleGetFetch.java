package app.zcat.infochat.collector.fetcher;

import app.zcat.infochat.collector.fetcher.rss.RssFeedParser;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.ssrf.UrlRedactor;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

/**
 * The single transport-and-parse path shared by the four byte-identical
 * single-GET fetchers — RSS, Nitter, Odysee, YouTube. Each kind class keeps
 * its own CDI identity, its own {@code @FetcherKind} binding, and its own
 * nested exception type; this helper holds the one copy of the
 * redaction / interrupt / status-code logic those classes formerly
 * duplicated four times.
 *
 * <p>Keeping the redaction in one place is the security-relevant point: feed
 * URLs may carry credentials or query-string tokens, so every failure message
 * interpolates {@link UrlRedactor#redact} of the identifier, never the raw
 * value. The URL-redaction lesson (M1-023) is now patched here once instead of
 * in four copies that can drift apart.
 *
 * <p>{@code fetchedAt} is captured once, before the HTTP call, so every
 * {@link NormalizedPost} produced by one invocation shares the same
 * {@code fetchedAt} — the partition key downstream {@code post} writes depend
 * on ({@code post} is partitioned by {@code RANGE (fetched_at)}). Capturing
 * per-item would scatter a slow parse across partitions.
 */
public final class SingleGetFetch {

    private SingleGetFetch() {
    }

    /**
     * The SSRF-guarded GET the helper invokes. Modelled as a functional seam
     * (the kind classes pass {@code client::get}) so the interrupt and I/O
     * branches below are testable directly — {@link SingleGetFetch} never sees
     * the concrete, {@code final} client type.
     */
    @FunctionalInterface
    public interface GuardedGet {
        HttpResponse<byte[]> get(URI uri) throws IOException, InterruptedException;
    }

    /**
     * Builds the caller's kind-specific unchecked exception. {@code cause} is
     * null for the status-code branch (no underlying throwable) and non-null
     * for the interrupt and I/O branches; the kind class selects the matching
     * constructor.
     */
    @FunctionalInterface
    public interface FetchExceptionFactory {
        RuntimeException create(String message, @Nullable Throwable cause);
    }

    /**
     * Issues one SSRF-guarded GET against {@code identifier} and parses a 2xx
     * body as RSS/Atom via {@link RssFeedParser}. {@code label} prefixes every
     * failure message (e.g. {@code "RSS"}); {@code exceptionFactory} wraps each
     * failure in the kind-specific exception type so existing per-fetcher
     * exception contracts are preserved.
     */
    public static List<NormalizedPost> fetchAndParse(
            GuardedGet get,
            String label,
            long dispatchKey,
            String identifier,
            FetchExceptionFactory exceptionFactory) {
        // Capture once, before the HTTP call — see the partition-key note above.
        Instant fetchedAt = Instant.now();

        HttpResponse<byte[]> response;
        try {
            response = get.get(URI.create(identifier));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw exceptionFactory.create(
                label + " fetch interrupted for " + UrlRedactor.redact(identifier), e);
        } catch (IOException e) {
            throw exceptionFactory.create(
                label + " fetch I/O failure for " + UrlRedactor.redact(identifier)
                + ": " + e.getMessage(), e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw exceptionFactory.create(
                label + " fetch got HTTP " + status + " for " + UrlRedactor.redact(identifier),
                null);
        }

        return RssFeedParser.parse(dispatchKey, response.body(), fetchedAt);
    }
}
