package app.zcat.infochat.core.ingest;


import java.util.function.Consumer;

/**
 * Long-lived, event-driven ingest SPI. Implementations open a
 * subscription (Nostr relay, Bluesky firehose, etc.) on {@link #start}
 * and push each new {@link NormalizedPost} through the supplied
 * delivery callback. {@link #stop} tears the subscription down.
 *
 * <p>Lifecycle ownership (when to start, who calls stop on shutdown,
 * how many concurrent {@code StreamSource}s the host runs) belongs to
 * the implementation-side ticket that introduces a concrete impl;
 * v1's SPI commits only to the two-method shape.</p>
 */
public interface StreamSource {

    /**
     * Open the subscription. Implementations are expected to start a
     * background worker (virtual thread, scheduled task, etc.) that
     * pushes new posts through {@code deliver} as they arrive. The
     * call itself returns once the subscription is established; it
     * does NOT block while events flow.
     *
     * @param dispatchKey the per-tick opaque dispatch token for this
     *                    stream, stamped onto every delivered post. It is
     *                    NOT the {@code source.id} UUID and is not stable
     *                    across ticks (see {@link NormalizedPost}).
     * @param filterSpec  the source-side filter / topic / relay set
     *                    describing what to subscribe to. Format is
     *                    per-source (Nostr filter JSON, Bluesky DID
     *                    list, etc.).
     * @param deliver     callback invoked once per new post. The impl
     *                    must NOT call {@code deliver} after {@link
     *                    #stop} returns.
     */
    void start(long dispatchKey, String filterSpec, Consumer<NormalizedPost> deliver);

    /**
     * Close the subscription, draining in-flight events first. On
     * graceful shutdown the implementation MUST aggressively flush every
     * in-flight event through the {@code deliver} callback supplied to
     * {@link #start} — i.e. to the outbox — before returning, rather than
     * dropping them (architecture.md §Ingest SPIs, "Drain on shutdown").
     * The supervisor treats {@code stop()} returning as the signal the
     * flush completed; it bounds the wait by a profile-driven drain
     * timeout, and events still buffered when that timeout fires are
     * dropped (and counted on the per-source lost-events counter).
     *
     * <p>Implementations must release any network / thread resources
     * before returning. After {@code stop} returns, no further delivery
     * callbacks may fire for this stream.</p>
     */
    void stop();
}
