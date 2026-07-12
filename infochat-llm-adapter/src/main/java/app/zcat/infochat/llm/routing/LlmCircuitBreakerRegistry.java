package app.zcat.infochat.llm.routing;

import app.zcat.infochat.llm.ModelTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-endpoint circuit breaker state for every LLM-transport SPI call
 * (M1-606): fail-FAST on a provider already known to be unreachable —
 * never fail-OVER ({@code docs/spec/security.md} §Failure handling, "No
 * router-side fallback in v1"; the task still degrades to its own failure
 * path, it is never re-routed).
 *
 * <h2>Keying</h2>
 * <p>State is keyed by resolved provider ENDPOINT (base-url), not by
 * {@link ModelTask}: one deployment runs one LLM service in practice
 * (D56), so all tasks routed to one endpoint share its breaker — the
 * first worker that discovers an outage spares every other consumer the
 * doomed connect wait. Task → endpoint resolution mirrors, read-only,
 * the providers' own {@code configFor} precedence (per-task
 * {@code infochat.llm.<task>.base-url} wins, else the shared
 * {@link LlmRouter#CONFIG_KEY_DEFAULT_BASE_URL}); the precedence is
 * intentionally duplicated here rather than extracted from the two
 * providers — a shared-helper refactor is out of this ticket's scope.
 * The embedding SPI is a single per-deployment endpoint
 * ({@code infochat.embeddings.base-url}), resolved separately. A task
 * (or deployment) whose endpoint does not resolve — the test-stub case:
 * no HTTP transport at all — bypasses the breaker entirely, so
 * stub-backed environments keep today's behaviour byte-identical.
 *
 * <h2>State machine (per endpoint)</h2>
 * <p>CLOSED by default; {@code failureThreshold} CONSECUTIVE
 * transport-unreachable failures trip it to OPEN; while OPEN every call
 * is denied ({@link #tryAcquireForTask} false → the decorator throws the
 * typed unreachable signal WITHOUT an HTTP attempt); after
 * {@code cooldown} the next acquire is admitted as the single HALF-OPEN
 * probe; a reachable outcome closes the breaker, an unreachable one
 * re-opens it with a fresh cooldown. Only classified transport failures
 * ({@code LlmCallFailedException.ProviderUnreachableException} /
 * {@code EmbeddingProviderUnreachableException}) advance the counter —
 * an application error (non-2xx, parse, wrong shape) proves the endpoint
 * IS reachable and counts as reachable evidence. Breaker state is
 * in-memory only; a restart resets to CLOSED and the first call
 * re-probes.
 *
 * <h2>Time</h2>
 * <p>The cooldown / HALF-OPEN decision reads the injected {@link Clock}
 * (engineering-rules §9), pinned in this module's plain-JUnit tests via
 * the seam constructor — infochat-llm-adapter has no Quarkus test
 * harness, so the {@code QuarkusMock} route is unavailable here; the
 * managed bean receives the app-wide UTC producer.
 */
@ApplicationScoped
public class LlmCircuitBreakerRegistry {

    private static final Logger LOG = Logger.getLogger(LlmCircuitBreakerRegistry.class);

    /**
     * Consecutive transport-unreachable failures on one endpoint that
     * trip its breaker CLOSED → OPEN.
     */
    public static final String CONFIG_KEY_FAILURE_THRESHOLD =
        "infochat.llm.breaker.failure-threshold";

    /**
     * Milliseconds an OPEN breaker denies calls before admitting the
     * single HALF-OPEN probe.
     */
    public static final String CONFIG_KEY_COOLDOWN_MS =
        "infochat.llm.breaker.cooldown-ms";

    /**
     * The embedding SPI's single per-deployment endpoint key — mirrored
     * from {@code OpenAiCompatibleEmbeddingProvider}'s
     * {@code @ConfigProperty} (annotation values must be literals there,
     * so the constant cannot be shared in that direction).
     */
    static final String CONFIG_KEY_EMBEDDINGS_BASE_URL = "infochat.embeddings.base-url";

    /**
     * Code-level defaults (threshold 3, cooldown 30s) so the registry
     * never fails a boot the undecorated providers would survive; the
     * shipped {@code application.properties} restates them for operator
     * visibility. 3 consecutive failures tolerates a transient blip
     * without tripping; 30s is long enough to spare an outage's doomed
     * connect waits and short enough that recovery is noticed promptly.
     */
    static final int DEFAULT_FAILURE_THRESHOLD = 3;
    static final long DEFAULT_COOLDOWN_MS = 30_000;

    private final int failureThreshold;
    private final Duration cooldown;
    private final Clock clock;
    private final LlmRouter.ConfigReader config;
    private final ConcurrentHashMap<String, EndpointBreaker> breakersByEndpoint =
        new ConcurrentHashMap<>();

    /**
     * Seam constructor: hand-supplied sizing + {@link Clock} +
     * config reader, for plain-JUnit tests (fixed clock, map-backed
     * config) and for test doubles that subclass — the same two-ctor
     * shape as {@link LlmRouter}.
     */
    public LlmCircuitBreakerRegistry(int failureThreshold, long cooldownMs,
                                     Clock clock, LlmRouter.ConfigReader config) {
        this.failureThreshold = failureThreshold;
        this.cooldown = Duration.ofMillis(cooldownMs);
        this.clock = clock;
        this.config = config;
    }

    /**
     * CDI constructor. The {@link Clock} resolves against the app-wide
     * UTC producer ({@code ThrottledAdminNotifier.systemUtcClock()});
     * every container that instantiates this bean has it on classpath
     * (infochat-core depends on this module, and both services depend on
     * core).
     */
    @Inject
    public LlmCircuitBreakerRegistry(Config mpConfig, Clock clock) {
        this(
            mpConfig.getOptionalValue(CONFIG_KEY_FAILURE_THRESHOLD, Integer.class)
                .orElse(DEFAULT_FAILURE_THRESHOLD),
            mpConfig.getOptionalValue(CONFIG_KEY_COOLDOWN_MS, Long.class)
                .orElse(DEFAULT_COOLDOWN_MS),
            clock,
            key -> mpConfig.getOptionalValue(key, String.class));
    }

    /**
     * Whether a {@code generate()} call for {@code task} issued right now
     * would be denied by its endpoint's breaker — the pre-flight check
     * {@code ChatAgent} runs to skip the deterministic semantic pre-fetch
     * on a doomed turn (M1-606). False the moment the cooldown has
     * elapsed, even though the internal state is still OPEN: the next
     * call becomes the HALF-OPEN probe, a real turn that deserves its
     * grounding. Read-only — never transitions state.
     */
    public boolean wouldShortCircuit(ModelTask task) {
        Optional<String> endpoint = endpointForTask(task);
        if (endpoint.isEmpty()) {
            return false;
        }
        EndpointBreaker breaker = breakersByEndpoint.get(endpoint.get());
        return breaker != null && breaker.wouldDeny(clock.instant());
    }

    /**
     * Gate for the LLM-side decorator, called before the delegate's HTTP
     * attempt: true admits the call (CLOSED, or the single HALF-OPEN
     * probe), false means short-circuit. A task with no resolvable
     * endpoint bypasses (always true).
     */
    public boolean tryAcquireForTask(ModelTask task) {
        return endpointForTask(task)
            .map(endpoint -> breakerFor(endpoint).tryAcquire(clock.instant()))
            .orElse(true);
    }

    /** Reachability evidence for {@code task}'s endpoint: closes its breaker. */
    public void recordReachableForTask(ModelTask task) {
        endpointForTask(task).ifPresent(this::recordReachable);
    }

    /** Classified transport failure for {@code task}'s endpoint. */
    public void recordUnreachableForTask(ModelTask task) {
        endpointForTask(task).ifPresent(this::recordUnreachable);
    }

    /** Embedding-side twin of {@link #tryAcquireForTask}. */
    public boolean tryAcquireForEmbeddings() {
        return embeddingsEndpoint()
            .map(endpoint -> breakerFor(endpoint).tryAcquire(clock.instant()))
            .orElse(true);
    }

    /** Embedding-side twin of {@link #recordReachableForTask}. */
    public void recordReachableForEmbeddings() {
        embeddingsEndpoint().ifPresent(this::recordReachable);
    }

    /** Embedding-side twin of {@link #recordUnreachableForTask}. */
    public void recordUnreachableForEmbeddings() {
        embeddingsEndpoint().ifPresent(this::recordUnreachable);
    }

    private void recordReachable(String endpoint) {
        // No breakerFor() here: reachability evidence on an endpoint with
        // no breaker entry has nothing to close — don't allocate state
        // for the healthy steady-state path.
        EndpointBreaker breaker = breakersByEndpoint.get(endpoint);
        if (breaker != null) {
            breaker.recordReachable();
        }
    }

    private void recordUnreachable(String endpoint) {
        breakerFor(endpoint).recordUnreachable(clock.instant());
    }

    private EndpointBreaker breakerFor(String endpoint) {
        return breakersByEndpoint.computeIfAbsent(endpoint,
            key -> new EndpointBreaker(key, failureThreshold, cooldown));
    }

    /**
     * The effective endpoint {@code task}'s provider will call: per-task
     * base-url wins, else the shared deployment default — the same
     * precedence the providers' own {@code configFor} applies, mirrored
     * read-only (see class javadoc). Empty when neither key is set (the
     * stub-provider test topology): the breaker then bypasses.
     */
    private Optional<String> endpointForTask(ModelTask task) {
        return config.get(task.baseUrlKey())
            .filter(url -> !url.isEmpty())
            .or(() -> config.get(LlmRouter.CONFIG_KEY_DEFAULT_BASE_URL)
                .filter(url -> !url.isEmpty()));
    }

    private Optional<String> embeddingsEndpoint() {
        return config.get(CONFIG_KEY_EMBEDDINGS_BASE_URL)
            .filter(url -> !url.isEmpty());
    }

    /**
     * One endpoint's breaker state. All transitions are synchronized on
     * the instance — the critical sections are a handful of field reads
     * and writes, contention-irrelevant next to the HTTP calls they
     * gate.
     */
    private static final class EndpointBreaker {

        private enum State { CLOSED, OPEN, HALF_OPEN }

        private final String endpoint;
        private final int failureThreshold;
        private final Duration cooldown;

        private State state = State.CLOSED;
        private int consecutiveUnreachableFailures = 0;
        // When OPEN: the instant the single HALF-OPEN probe becomes
        // admissible. When HALF_OPEN: the instant the in-flight probe's
        // slot expires — a probe whose thread never reports (killed
        // mid-call by an Error) would otherwise deny the endpoint until
        // restart, so the slot self-frees after one further cooldown and
        // the next call probes again.
        private Instant deadline = Instant.EPOCH;

        EndpointBreaker(String endpoint, int failureThreshold, Duration cooldown) {
            this.endpoint = endpoint;
            this.failureThreshold = failureThreshold;
            this.cooldown = cooldown;
        }

        synchronized boolean tryAcquire(Instant now) {
            return switch (state) {
                case CLOSED -> true;
                case OPEN, HALF_OPEN -> {
                    if (now.isBefore(deadline)) {
                        yield false;
                    }
                    // Cooldown elapsed (or a stale probe slot expired):
                    // this caller becomes the single HALF-OPEN probe.
                    state = State.HALF_OPEN;
                    deadline = now.plus(cooldown);
                    yield true;
                }
            };
        }

        synchronized boolean wouldDeny(Instant now) {
            return switch (state) {
                case CLOSED -> false;
                case OPEN, HALF_OPEN -> now.isBefore(deadline);
            };
        }

        synchronized void recordReachable() {
            // Unconditional close: any response — success or application
            // error — is live evidence the endpoint answers, whatever
            // state the breaker sat in (an in-flight straggler finishing
            // after the trip closes it too, correctly).
            if (state != State.CLOSED) {
                LOG.infof("LLM circuit breaker CLOSED for %s (endpoint reachable again)",
                    endpoint);
            }
            state = State.CLOSED;
            consecutiveUnreachableFailures = 0;
        }

        synchronized void recordUnreachable(Instant now) {
            switch (state) {
                case CLOSED -> {
                    consecutiveUnreachableFailures++;
                    if (consecutiveUnreachableFailures >= failureThreshold) {
                        state = State.OPEN;
                        deadline = now.plus(cooldown);
                        LOG.warnf("LLM circuit breaker OPEN for %s after %d consecutive "
                                + "transport failures; short-circuiting calls for %d ms",
                            endpoint, consecutiveUnreachableFailures, cooldown.toMillis());
                    }
                }
                case HALF_OPEN -> {
                    // The probe failed: re-open with a fresh cooldown.
                    state = State.OPEN;
                    deadline = now.plus(cooldown);
                    LOG.warnf("LLM circuit breaker re-OPENED for %s (probe failed); "
                            + "short-circuiting calls for %d ms",
                        endpoint, cooldown.toMillis());
                }
                case OPEN -> {
                    // An in-flight straggler failing after the trip: the
                    // breaker is already open; refreshing the deadline
                    // would let stragglers extend the outage window
                    // indefinitely, so leave it.
                }
            }
        }
    }
}
