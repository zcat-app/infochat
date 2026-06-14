package app.zcat.infochat.provider.command.asset;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the M1-340 decoupling: staleness is judged solely against the
 * Provider-owned freshness window, with no reference to the Collector's
 * {@code infochat.assets.refresh.*} cadence keys (which no longer exist on
 * the Provider side). A snapshot older than the window is stale; one within
 * it is fresh. No {@code @QuarkusTest} — the staleness decision is a pure
 * function of (capturedAt, now, window).
 */
class AssetSnapshotReaderTest {

    private static final Duration WINDOW = Duration.ofSeconds(180);

    @Test
    void snapshotOlderThanFreshnessWindowIsStale() {
        Instant now = Instant.now();
        Instant capturedAt = now.minus(WINDOW).minusSeconds(1);
        assertTrue(AssetSnapshotReader.isStale(capturedAt, now, WINDOW),
                "a snapshot older than the freshness window must be reported stale");
    }

    @Test
    void snapshotWithinFreshnessWindowIsFresh() {
        Instant now = Instant.now();
        Instant capturedAt = now.minus(WINDOW).plusSeconds(1);
        assertFalse(AssetSnapshotReader.isStale(capturedAt, now, WINDOW),
                "a snapshot within the freshness window must be reported fresh");
    }
}
