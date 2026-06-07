package app.zcat.infochat.collector.fetcher;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-source pagination-cap saturation tracking per
 * {@code docs/spec/architecture.md} §Ingest SPIs: "Fetchers expose a
 * per-tick 'pagination cap hit per source' counter. When a single
 * source consistently saturates the cap across multiple ticks
 * (operators choose the threshold; design notes), a throttled admin
 * notification fires once per saturation transition."
 *
 * <h2>Signal hand-off</h2>
 * <p>A paginating Fetcher calls {@link #signalCapHit()} when its
 * per-tick page loop exhausts the cap with a pagination cursor still
 * outstanding (more upstream pages existed). The {@code Fetcher} SPI
 * returns only the post list, so the signal travels out-of-band via a
 * {@link ThreadLocal}: {@code fetch()} runs synchronously on the
 * scheduler's dispatch thread, and the scheduler consumes the flag
 * via {@link #consumeCapHit()} immediately after {@code fetch()}
 * returns — thread-confined, so overlapping heartbeats on other
 * threads cannot cross-talk. The signal is static so fetchers need
 * no CDI wiring (their test seams construct them with {@code new}).
 *
 * <h2>Transition semantics</h2>
 * <p>{@link #recordTick} returns {@code true} exactly when the
 * consecutive-saturated-tick streak reaches the configured threshold
 * — once per saturation transition. Further saturated ticks grow the
 * streak without re-firing; a non-saturated tick (including a failed
 * fetch) resets it, so the next sustained saturation is a new
 * transition.
 */
@ApplicationScoped
public class PaginationSaturationTracker {

    private static final ThreadLocal<Boolean> CAP_HIT =
        ThreadLocal.withInitial(() -> Boolean.FALSE);

    // Single-global tunable (no per-profile branching), so inline
    // defaultValue is allowed per the AssetSnapshotFetcher convention.
    // Operator override:
    // -Dinfochat.fetch.saturation-threshold=<consecutive ticks>.
    @ConfigProperty(name = "infochat.fetch.saturation-threshold", defaultValue = "3")
    int saturationThreshold;

    // The spec's "pagination cap hit per source" counter (cumulative)
    // plus the consecutive-tick streak the threshold is judged
    // against. In-memory like the scheduler's per-kind tick tracking —
    // saturation is an operational signal, not persisted state.
    private final Map<UUID, Long> capHitTotals = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveSaturatedTicks = new ConcurrentHashMap<>();

    /**
     * Called by a paginating Fetcher when its page loop ran the full
     * per-tick cap and the last page still carried a cursor — the
     * source produced more pages than one tick may drain.
     */
    public static void signalCapHit() {
        CAP_HIT.set(Boolean.TRUE);
    }

    /**
     * Consume-and-clear the current thread's cap-hit flag. Called by
     * the scheduler immediately after {@code fetch()} returns.
     */
    public boolean consumeCapHit() {
        boolean hit = CAP_HIT.get();
        CAP_HIT.remove();
        return hit;
    }

    /**
     * Record one tick outcome for {@code sourceId}.
     *
     * @return {@code true} exactly on the non-saturated → saturated
     *         transition (streak == threshold); {@code false} on
     *         every other tick, including sustained saturation past
     *         the threshold.
     */
    public boolean recordTick(UUID sourceId, boolean capHit) {
        if (!capHit) {
            consecutiveSaturatedTicks.remove(sourceId);
            return false;
        }
        capHitTotals.merge(sourceId, 1L, Long::sum);
        int streak = consecutiveSaturatedTicks.merge(sourceId, 1, Integer::sum);
        return streak == saturationThreshold;
    }

    /** Cumulative cap-hit count for {@code sourceId} (0 if never hit). */
    public long capHitCount(UUID sourceId) {
        return capHitTotals.getOrDefault(sourceId, 0L);
    }

    /** The configured consecutive-tick saturation threshold. */
    public int saturationThreshold() {
        return saturationThreshold;
    }
}
