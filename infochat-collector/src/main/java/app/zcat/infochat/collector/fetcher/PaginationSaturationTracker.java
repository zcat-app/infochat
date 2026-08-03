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
 * <p>This class carries a SECOND, independent out-of-band signal on
 * the same channel: {@link #signalTruncation()} /
 * {@link #consumeTruncation()}, raised by a parser that clipped a feed
 * at its per-parse item cap (M1-753). It shares the hand-off mechanism
 * because the constraint is identical — the SPI returns only the post
 * list — but NOT the notification policy: saturation is streak-gated,
 * truncation is reported per occurrence. Keeping them as two flags is
 * what lets the scheduler apply the right policy to each.
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

    // Feed-truncation is a SEPARATE signal from pagination saturation,
    // deliberately not folded into CAP_HIT (M1-753). The two conditions
    // look alike — "this source had more than one tick could take" — but
    // they want opposite notification policies. Pagination saturation is
    // streak-gated: a paginating source that saturates once is
    // unremarkable, so only sustained saturation is worth an operator's
    // attention. Truncation is not, because the streak is defeated by the
    // very feeds that need reporting: a source oscillating across the cap
    // (1001/1001/999, repeating) resets the streak on every third tick and
    // would never notify, while silently discarding content on the other
    // two. Sharing one flag would force one policy onto both.
    private static final ThreadLocal<Boolean> TRUNCATED =
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
     * Called by a parser that clipped a feed at its per-parse item cap
     * and returned a prefix instead of the whole payload. Unlike
     * {@link #signalCapHit()} this is reported on every occurrence, not
     * on a saturation streak — see {@code TRUNCATED} for why the two
     * signals cannot share a policy.
     */
    public static void signalTruncation() {
        TRUNCATED.set(Boolean.TRUE);
    }

    /**
     * Consume-and-clear the current thread's truncation flag. The
     * scheduler MUST call this on both the success and the failure path
     * of a tick: the flag's lifetime is one tick, and a dispatch thread
     * is reused across sources, so a flag left set by a tick that threw
     * after parsing would be read as the NEXT source's truncation and
     * notify against the wrong uuid.
     */
    public boolean consumeTruncation() {
        boolean truncated = TRUNCATED.get();
        TRUNCATED.remove();
        return truncated;
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
