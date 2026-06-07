package app.zcat.infochat.collector.eval;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared sleep-before-retry backoff for the eval-pipeline workers'
 * single LLM retry. {@code docs/spec/security.md} §Failure handling
 * mandates the retry COUNT — exactly one — not its timing; an
 * immediate re-invocation against a rate-limited endpoint (429/503)
 * is near-certain to fail milliseconds after the first attempt,
 * converting every rate-limit burst into the stage-specific degraded
 * failure path even though waiting a couple of seconds would have
 * let the common short burst clear.
 *
 * <p>The delay is {@code base + uniform(0, base/2)} jitter. Stage 2
 * can hold several concurrent in-flight calls (its semaphore allows
 * up to {@code infochat.llm.security.max-concurrency}); a burst that
 * fails them together would otherwise re-fire them together into the
 * same rate-limit window.
 *
 * <p>Callers invoke {@link #sleepBeforeRetry()} only on the
 * infrastructure-shaped failure arm (the provider call threw) —
 * schema-garbage / zero-valid retry shapes re-issue immediately:
 * those replies prove the endpoint is reachable and not
 * rate-limiting, so there is nothing to wait out.
 */
@ApplicationScoped
public class RetryBackoff {

    @ConfigProperty(name = "infochat.llm.retry-backoff-ms")
    long backoffMs;

    @PostConstruct
    void init() {
        if (backoffMs < 0) {
            throw new IllegalStateException(
                "RetryBackoff: infochat.llm.retry-backoff-ms must be >= 0; got " + backoffMs);
        }
    }

    /**
     * Sleep the configured base delay plus jitter. A zero base is a
     * no-op — the operator's "disable backoff" escape hatch.
     *
     * <p>On interrupt the flag is restored and the method returns:
     * the remaining wait is skipped but the caller's spec-mandated
     * single retry still fires — interruption must never silently
     * swallow the retry the spec promises.
     */
    public void sleepBeforeRetry() {
        if (backoffMs == 0) {
            return;
        }
        long jitterMs = ThreadLocalRandom.current().nextLong(backoffMs / 2 + 1);
        try {
            Thread.sleep(backoffMs + jitterMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
