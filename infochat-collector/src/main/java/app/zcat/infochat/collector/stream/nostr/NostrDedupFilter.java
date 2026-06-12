package app.zcat.infochat.collector.stream.nostr;


import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory deduplicator for Nostr event ids. One filter per
 * {@link NostrStreamSource}; the same source's multiple relay connections
 * can deliver the same event id concurrently and only the first arrival
 * is forwarded to the outbox.
 *
 * <p>The post table's UNIQUE constraint is
 * {@code (source_id, upstream_identifier, fetched_at)} — it does NOT
 * catch two writes for the same upstream id with different fetched_at
 * timestamps, which is what concurrent relay deliveries produce. The
 * in-memory filter is therefore the authoritative cross-relay dedup
 * (architecture.md §Ingest SPIs: "one event = one posts row regardless
 * of how many relays delivered it"), not a performance optimization on
 * top of a DB-level gate.</p>
 *
 * <p>State is a bounded FIFO set of recently-seen event ids: the most
 * recently inserted {@link #DEFAULT_MAX_ENTRIES} ids are remembered, and
 * older ids fall out as new ones arrive. Memory is bounded at ~1MB per
 * source (10K ids * 64 hex chars * Java String overhead). State is
 * intentionally per-process: on restart, the {@code since} cursor on the
 * REQ subscription is the dedup horizon for already-persisted events,
 * and the same id may legitimately enter a fresh filter again.</p>
 *
 * <p>Thread-safe via a private monitor; the hot path is a single
 * LinkedHashMap lookup-or-insert, sub-microsecond under low contention.
 * Each per-relay WebSocket listener thread is the caller.</p>
 */
public final class NostrDedupFilter {

    static final int DEFAULT_MAX_ENTRIES = 10_000;

    private final int maxEntries;
    private final Object lock = new Object();
    private final Map<String, Boolean> seenIds;

    public NostrDedupFilter() {
        this(DEFAULT_MAX_ENTRIES);
    }

    NostrDedupFilter(int maxEntries) {
        this.maxEntries = maxEntries;
        // accessOrder=false → FIFO eviction. record() puts only new keys
        // (putIfAbsent) and never re-puts duplicates, so insertion order is
        // the eviction order. LRU would bias retention towards the spammiest
        // ids, the opposite of what cross-relay dedup needs.
        this.seenIds = new LinkedHashMap<>(maxEntries * 4 / 3 + 1, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > NostrDedupFilter.this.maxEntries;
            }
        };
    }

    /**
     * Returns {@code true} if {@code eventId} is already recorded within the
     * dedup window, {@code false} if it is new. A read-only probe: it does
     * NOT record the id. Split from the former single {@code accept} so the
     * caller commits the id via {@link #record} only after the event has been
     * accepted by the outbox queue — a queue-full drop between {@code seen}
     * and {@code record} therefore leaves the id un-recorded and replayable
     * on the relay's reconnect, rather than poisoning the dedup set.
     */
    public boolean seen(String eventId) {
        synchronized (lock) {
            return seenIds.containsKey(eventId);
        }
    }

    /**
     * Records {@code eventId} as delivered, evicting the eldest id once the
     * window is full. Idempotent: re-recording an id already in the window is
     * a no-op that leaves the FIFO insertion (hence eviction) order unchanged,
     * so the brief two-relay window where both deliveries record the same id
     * does not corrupt the eviction order. Call only after the event was
     * accepted by the outbox queue.
     */
    public void record(String eventId) {
        synchronized (lock) {
            seenIds.putIfAbsent(eventId, Boolean.TRUE);
        }
    }
}
