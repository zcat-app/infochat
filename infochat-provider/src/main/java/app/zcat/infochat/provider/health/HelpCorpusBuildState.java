package app.zcat.infochat.provider.health;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Boot-time help-corpus build outcomes: written by each builder's
 * {@code onStart}, read by {@link HelpCorpusReadinessCheck}; entries state
 * build outcome, never backend liveness (deployment.md §Bootstrap behavior). */
@ApplicationScoped
public class HelpCorpusBuildState {

    private final Map<String, Boolean> builtByCorpus = new ConcurrentHashMap<>();

    /** The named corpus was built (or content-hash-skipped as unchanged). */
    public void reportBuilt(String corpus) {
        builtByCorpus.put(corpus, true);
    }

    /** The named corpus's build failed; its builder degraded per the failure posture. */
    public void reportFailed(String corpus) {
        builtByCorpus.put(corpus, false);
    }

    /** Immutable snapshot of corpus → built, for the readiness payload. */
    public Map<String, Boolean> snapshot() {
        return Map.copyOf(builtByCorpus);
    }
}
