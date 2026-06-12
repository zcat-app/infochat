package app.zcat.infochat.collector.stream.nostr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests for {@link NostrDedupFilter}: the in-memory FIFO set
 * that suppresses redundant deliveries of the same Nostr event id from
 * different relays of the same source.
 */
class NostrDedupFilterTest {

    // 64 hex chars matches the real Nostr event-id shape, but the filter
    // treats ids as opaque keys — single-char "a"/"b"/"c" would work
    // equally well and keep test failures readable.
    private static final String ID_A = "a".repeat(64);
    private static final String ID_B = "b".repeat(64);
    private static final String ID_C = "c".repeat(64);

    @Test
    void sameEventFromTwoRelays_onlyOneDelivered() {
        NostrDedupFilter filter = new NostrDedupFilter();

        assertFalse(filter.seen(ID_A), "first arrival (from relay A) is new");
        filter.record(ID_A);
        assertTrue(filter.seen(ID_A), "second arrival (from relay B) is a duplicate, suppressed");
    }

    @Test
    void distinctEvents_bothDelivered() {
        NostrDedupFilter filter = new NostrDedupFilter();

        assertFalse(filter.seen(ID_A), "id A is new");
        filter.record(ID_A);
        assertFalse(filter.seen(ID_B), "distinct id B is new");
        filter.record(ID_B);
    }

    @Test
    void windowEviction_allowsRedelivery() {
        // Tiny window so the eviction path fires within the test.
        NostrDedupFilter filter = new NostrDedupFilter(2);

        assertFalse(filter.seen(ID_A), "id A is new, window = {A}");
        filter.record(ID_A);
        assertFalse(filter.seen(ID_B), "id B is new, window = {A,B}");
        filter.record(ID_B);
        // Recording C overflows the 2-entry FIFO window; A (the eldest
        // insertion) is evicted, window = {B,C}.
        assertFalse(filter.seen(ID_C), "id C is new, window = {A,B}");
        filter.record(ID_C);
        // B is still inside the window — duplicates still suppressed. Checked
        // before re-recording A so the FIFO state is unambiguous: recording A
        // would evict B and obscure this check.
        assertTrue(filter.seen(ID_B), "id B still within window, suppressed");
        // A re-arrives — same id, different relay — and is new again because
        // the in-memory filter has forgotten it. This is the core
        // "windowEviction_allowsRedelivery" behavior the ticket names.
        assertFalse(filter.seen(ID_A), "id A re-admitted after eviction");
        filter.record(ID_A);
    }
}
