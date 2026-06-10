package app.zcat.infochat.core.ingest;


import java.util.List;

/**
 * Polled, request/response ingest SPI. One {@code fetch} call against
 * one source descriptor produces zero or more {@link NormalizedPost}
 * rows that the caller (the FetchScheduler, landing in a later ticket)
 * hands to the outbox.
 *
 * <p>The SPI is deliberately minimal in v1: the source is identified
 * by a per-tick dispatch token and its source-side identifier string
 * (URL for HTTP-shaped sources, filter spec for stream-but-polled
 * sources).
 * Pagination, retry, backoff, and the asset-Fetcher output-type
 * discriminator are implementation concerns or follow-up tickets;
 * they are intentionally NOT method-shape commitments here.</p>
 */
public interface Fetcher {

    /**
     * Fetch the current batch of posts for one source.
     *
     * @param dispatchKey the per-tick opaque dispatch token for this
     *                    fetch, stamped onto every returned post. It is
     *                    NOT the {@code source.id} UUID and is not stable
     *                    across ticks (see {@link NormalizedPost}); the
     *                    FetchScheduler passes {@code row.dispatchKey()}.
     * @param identifier  the source-side identifier: URL for HTTP-shaped
     *                    sources, filter spec for stream-but-polled
     *                    sources.
     * @return zero or more normalized posts, in source-supplied order.
     *         Never null; an empty list means "no new items right now".
     */
    List<NormalizedPost> fetch(long dispatchKey, String identifier);
}
