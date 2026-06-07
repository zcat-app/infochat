package app.zcat.infochat.provider.health;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-adapter transport start outcome, written once at Provider startup
 * when each activated adapter's transport {@code start()} resolves and
 * read by the readiness probe.
 *
 * <p>"Connected" in v1 is the startup definition: the adapter's
 * transport {@code start()} returned without throwing
 * ({@code docs/spec/deployment.md} §Bootstrap behavior on startup).
 * Mid-session disconnects are owned by the per-adapter subprocess
 * supervisors' restart machinery and do not flow into this holder.</p>
 */
@ApplicationScoped
public class AdapterConnectionState {

    private final Map<String, Boolean> connectedByAdapter = new ConcurrentHashMap<>();

    /** The named adapter's transport start() returned without throwing. */
    public void reportStarted(String adapterName) {
        connectedByAdapter.put(adapterName, true);
    }

    /** The named adapter's transport start() threw. */
    public void reportFailed(String adapterName) {
        connectedByAdapter.put(adapterName, false);
    }

    /**
     * Drop all recorded outcomes. The adapter-start loop is documented
     * as idempotently re-runnable in the same JVM (the registry clears
     * and rebuilds its activated set); clearing here keeps a re-run
     * from leaving stale connected flags for adapters that are no
     * longer in the activated set.
     */
    public void reset() {
        connectedByAdapter.clear();
    }

    /** Immutable snapshot of adapter name → connected, for the readiness payload. */
    public Map<String, Boolean> snapshot() {
        return Map.copyOf(connectedByAdapter);
    }
}
