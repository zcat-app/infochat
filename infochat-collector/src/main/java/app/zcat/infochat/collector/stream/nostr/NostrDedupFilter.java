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
    private final Map<String, Boolean> seen;

    public NostrDedupFilter() {
        this(DEFAULT_MAX_ENTRIES);
    }

    NostrDedupFilter(int maxEntries) {
        this.maxEntries = maxEntries;
        // accessOrder=false → FIFO eviction. accept() puts only new keys
        // and never re-puts duplicates, so insertion order is the eviction
        // order. LRU would bias retention towards the spammiest ids, the
        // opposite of what cross-relay dedup needs.
        this.seen = new LinkedHashMap<>(maxEntries * 4 / 3 + 1, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > NostrDedupFilter.this.maxEntries;
            }
        };
    }

    /**
     * Returns {@code true} the first time {@code eventId} arrives within
     * the dedup window and records it; {@code false} on every subsequent
     * arrival until eviction. Callers that get {@code false} must drop
     * the event before the outbox write.
     */
    public boolean accept(String eventId) {
        synchronized (lock) {
            if (seen.containsKey(eventId)) {
                return false;
            }
            seen.put(eventId, Boolean.TRUE);
            return true;
        }
    }
}
