package app.zcat.infochat.provider.digest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test-only CDI observer that captures {@link DigestSlot} events
 * fired by {@link DigestScheduler} during integration tests.
 *
 * <p>Supports an optional one-shot gate that blocks inside the
 * observer on the first slot seen for a configured group set —
 * simulating a slow consumer so tests can assert that one group's
 * slow dispatch does not delay other groups' slot emissions.</p>
 */
@ApplicationScoped
public class DigestSlotObserver {

    private final List<DigestSlot> captured = Collections.synchronizedList(new ArrayList<>());

    private final Set<UUID> gatedGroups = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean gateArmed = new AtomicBoolean(false);
    private volatile CountDownLatch gateLatch = new CountDownLatch(0);

    void onSlot(@Observes DigestSlot slot) {
        // Capture BEFORE blocking so a gated slot is still visible to the
        // test while its consumer is parked.
        captured.add(slot);
        if (gateArmed.get() && gatedGroups.contains(slot.groupId())
                && gateArmed.compareAndSet(true, false)) {
            try {
                // Bounded await: a test that forgets releaseGate() must not
                // park a dispatch thread forever.
                gateLatch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public List<DigestSlot> getCaptured() {
        return List.copyOf(captured);
    }

    /**
     * Arm a one-shot gate: the first slot observed for one of the given
     * groups blocks inside the observer until {@link #releaseGate()}.
     */
    public void gateFirstSlotOf(Set<UUID> groupIds) {
        gatedGroups.clear();
        gatedGroups.addAll(groupIds);
        gateLatch = new CountDownLatch(1);
        gateArmed.set(true);
    }

    public void releaseGate() {
        gateArmed.set(false);
        gateLatch.countDown();
    }

    public void clear() {
        captured.clear();
        releaseGate();
    }
}
