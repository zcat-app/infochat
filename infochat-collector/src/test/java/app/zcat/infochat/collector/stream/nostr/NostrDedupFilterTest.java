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

        assertTrue(filter.accept(ID_A), "first arrival (from relay A) admitted");
        assertFalse(filter.accept(ID_A), "second arrival (from relay B) suppressed");
    }

    @Test
    void distinctEvents_bothDelivered() {
        NostrDedupFilter filter = new NostrDedupFilter();

        assertTrue(filter.accept(ID_A), "id A admitted");
        assertTrue(filter.accept(ID_B), "distinct id B admitted");
    }

    @Test
    void windowEviction_allowsRedelivery() {
        // Tiny window so the eviction path fires within the test.
        NostrDedupFilter filter = new NostrDedupFilter(2);

        assertTrue(filter.accept(ID_A), "id A admitted, window = {A}");
        assertTrue(filter.accept(ID_B), "id B admitted, window = {A,B}");
        // Inserting C overflows the 2-entry FIFO window; A (the eldest
        // insertion) is evicted, window = {B,C}.
        assertTrue(filter.accept(ID_C), "id C admitted, evicting eldest (A)");
        // B is still inside the window — duplicates still suppressed.
        // (Asserted before the next accept so the FIFO state is unambiguous:
        // re-admitting A would evict B and obscure this check.)
        assertFalse(filter.accept(ID_B), "id B still within window, suppressed");
        // A re-arrives — same id, different relay — and is admitted again
        // because the in-memory filter has forgotten it. This is the core
        // "windowEviction_allowsRedelivery" behavior the ticket names.
        assertTrue(filter.accept(ID_A), "id A re-admitted after eviction");
    }
}
