package app.zcat.infochat.provider.chat;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user sliding-window rate cap for LLM-triggering operations.
 * {@code docs/spec/security.md} §Rate limiting names chat replies,
 * on-demand {@code /summary}, and {@code /retry} re-rolls as ONE
 * bucket, so all three surfaces consult this single collaborator —
 * a caller cannot bypass the cap by switching surfaces.
 *
 * <p>Extracted from {@code InboundRouter} (where only the chat path
 * consulted it): the command handlers live in a different package,
 * and injecting the router into handlers it dispatches to would be a
 * dependency cycle, so the cap lives here alongside
 * {@link InFlightTracker}, which the same surfaces consume.</p>
 */
@ApplicationScoped
public class LlmRateCap {

    private final int capPerMinute;

    // Per-user LLM call timestamps. Keyed by users.id; each deque
    // holds call epoch-millis within the last 60 s. Synchronized on
    // the deque instance per entry.
    private final ConcurrentHashMap<UUID, Deque<Long>> llmCallTimestamps =
            new ConcurrentHashMap<>();

    @Inject
    public LlmRateCap(
            @ConfigProperty(name = "infochat.chat.llm-rate-cap-per-minute", defaultValue = "10")
            int capPerMinute) {
        this.capPerMinute = capPerMinute;
    }

    /**
     * Prune the caller's call timestamps older than 60 s, then either
     * record this call (true) or reject it as over-cap (false). A
     * rejected call records nothing — rejection never consumes budget,
     * which is what lets the next under-cap request succeed.
     */
    public boolean tryAcquire(UUID userId) {
        Deque<Long> timestamps = llmCallTimestamps.computeIfAbsent(
                userId, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        long windowStart = now - 60_000;
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= capPerMinute) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    /**
     * Undo the caller's most recent {@link #tryAcquire}: remove the
     * newest recorded timestamp so a downstream rejection (the
     * per-group LLM backstop, D47) consumes no per-user budget —
     * without this, a group whose aggregate bucket is pinned empty
     * would drain every member's personal budget on fixed rate-limit
     * replies (redteam M1-222 finding 3). The map-miss / empty-deque
     * no-op covers the eviction-sweep shape, not an illegal call.
     */
    public void refund(UUID userId) {
        Deque<Long> timestamps = llmCallTimestamps.get(userId);
        if (timestamps == null) {
            return;
        }
        synchronized (timestamps) {
            timestamps.pollLast();
        }
    }

    @Scheduled(every = "{infochat.chat.llm-rate-cap-sweep-interval:5m}")
    void evictIdleEntries() {
        evictIdleEntries(System.currentTimeMillis());
    }

    // 2x the 60 s rate-cap window: timestamps older than this are pruned;
    // entries whose deque is then empty are removed from the map.
    public void evictIdleEntries(long nowMillis) {
        long cutoff = nowMillis - 120_000;
        llmCallTimestamps.entrySet().removeIf(entry -> {
            Deque<Long> timestamps = entry.getValue();
            synchronized (timestamps) {
                while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                    timestamps.pollFirst();
                }
                return timestamps.isEmpty();
            }
        });
    }

    public int entryCount() {
        return llmCallTimestamps.size();
    }
}
