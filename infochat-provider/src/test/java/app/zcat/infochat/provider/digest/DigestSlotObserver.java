package app.zcat.infochat.provider.digest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test-only CDI observer that captures {@link DigestSlot} events
 * fired by {@link DigestScheduler} during integration tests.
 */
@ApplicationScoped
public class DigestSlotObserver {

    private final List<DigestSlot> captured = Collections.synchronizedList(new ArrayList<>());

    void onSlot(@Observes DigestSlot slot) {
        captured.add(slot);
    }

    public List<DigestSlot> getCaptured() {
        return List.copyOf(captured);
    }

    public void clear() {
        captured.clear();
    }
}
