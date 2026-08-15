package app.zcat.infochat.llm.routing;

import app.zcat.infochat.llm.ModelTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Per-endpoint circuit breaker state for every LLM-transport SPI call
 * (M1-606): fail-FAST on a provider already known to be unreachable —
 * never fail-OVER ({@code docs/spec/security.md} §Failure handling, "No
 * router-side fallback in v1"; the task still degrades to its own failure
 * path, it is never re-routed).
 *
 * <h2>Keying</h2>
 * <p>State is keyed by (transport KIND, resolved provider ENDPOINT), not
 * by {@link ModelTask}: one deployment runs one LLM service in practice
 * (D56), so all generative tasks routed to one endpoint share its
 * breaker — the first worker that discovers an outage spares every other
 * consumer the doomed connect wait. Task → endpoint resolution mirrors,
 * read-only, the providers' own {@code configFor} precedence (per-task
 * {@code infochat.llm.<task>.base-url} wins, else the shared
 * {@link LlmRouter#CONFIG_KEY_DEFAULT_BASE_URL}); the precedence is
 * intentionally duplicated here rather than extracted from the two
 * providers — a shared-helper refactor is out of this ticket's scope.
 *
 * <p>The embedding SPI is a single per-deployment endpoint
 * ({@code infochat.embeddings.base-url}) and gets its OWN breaker even
 * when that URL equals the generative one — which under the shipped
 * defaults it does on every profile. The KIND is part of the key because
 * the endpoint alone is not the failure domain: the two SPIs apply
 * different patience to the same URL ({@code
 * infochat.embeddings.timeout-ms} defaults to 30s and is unprofiled,
 * against {@code infochat.llm.chat.timeout-ms}'s 120s) and a read
 * timeout classifies as transport-unreachable, so a live-but-slow
 * backend times out embeddings while chat still answers well inside its
 * own budget. Sharing one key would let those embedding timeouts trip
 * the breaker chat depends on and deny chat WITHOUT an HTTP attempt
 * against an endpoint that is demonstrably answering.
 * {@code docs/spec/security.md} §Failure handling states the separation
 * ("the embedding endpoint is tracked separately"); D54/D56 keep
 * embeddings off the LLM routing defaults for the same independence
 * reason. (M1-769)
 *
 * <p>A task (or deployment) whose endpoint does not resolve — the
 * test-stub case: no HTTP transport at all — bypasses the breaker
 * entirely, so stub-backed environments keep today's behaviour
 * byte-identical.
 *
 * <h2>State machine (per key)</h2>
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
 * <p>The CLOSED→OPEN trip and a failed probe's HALF_OPEN→OPEN re-open fire
 * {@link LlmCircuitBreakerOpenedEvent} through the sink, outside the breaker
 * monitor; denied acquisitions and the close emit nothing.
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
    private final Consumer<LlmCircuitBreakerOpenedEvent> sink;
    private final ConcurrentHashMap<BreakerKey, EndpointBreaker> breakersByKey =
        new ConcurrentHashMap<>();

    /**
     * Seam constructor: hand-supplied sizing + {@link Clock} +
     * config reader, for plain-JUnit tests (fixed clock, map-backed
     * config) and for test doubles that subclass — the same two-ctor
     * shape as {@link LlmRouter}. Delegates with a no-op sink: no
     * event emission.
     */
    public LlmCircuitBreakerRegistry(int failureThreshold, long cooldownMs,
                                     Clock clock, LlmRouter.ConfigReader config) {
        this(failureThreshold, cooldownMs, clock, config, event -> { });
    }

    /** The seam constructor plus the sink the registry fires open transitions to. */
    public LlmCircuitBreakerRegistry(int failureThreshold, long cooldownMs,
                                     Clock clock, LlmRouter.ConfigReader config,
                                     Consumer<LlmCircuitBreakerOpenedEvent> sink) {
        this.failureThreshold = failureThreshold;
        this.cooldown = Duration.ofMillis(cooldownMs);
        this.clock = clock;
        this.config = config;
        this.sink = sink;
    }

    /**
     * CDI constructor. The {@link Clock} resolves against the app-wide
     * UTC producer ({@code ThrottledAdminNotifier.systemUtcClock()});
     * every container that instantiates this bean has it on classpath
     * (both services depend on infochat-core, where the producer lives,
     * and on this module). The opened-event sink is the container's event bus.
     */
    @Inject
    public LlmCircuitBreakerRegistry(Config mpConfig, Clock clock,
                                     Event<LlmCircuitBreakerOpenedEvent> breakerOpenedEvent) {
        this(
            mpConfig.getOptionalValue(CONFIG_KEY_FAILURE_THRESHOLD, Integer.class)
                .orElse(DEFAULT_FAILURE_THRESHOLD),
            mpConfig.getOptionalValue(CONFIG_KEY_COOLDOWN_MS, Long.class)
                .orElse(DEFAULT_COOLDOWN_MS),
            clock,
            key -> mpConfig.getOptionalValue(key, String.class),
            breakerOpenedEvent::fire);
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
        Optional<BreakerKey> key = llmKey(task);
        if (key.isEmpty()) {
            return false;
        }
        EndpointBreaker breaker = breakersByKey.get(key.get());
        return breaker != null && breaker.wouldDeny(clock.instant());
    }

    /**
     * Gate for the LLM-side decorator, called before the delegate's HTTP
     * attempt: true admits the call (CLOSED, or the single HALF-OPEN
     * probe), false means short-circuit. A task with no resolvable
     * endpoint bypasses (always true).
     */
    public boolean tryAcquireForTask(ModelTask task) {
        return llmKey(task)
            .map(key -> breakerFor(key).tryAcquire(clock.instant()))
            .orElse(true);
    }

    /** Reachability evidence for {@code task}'s endpoint: closes its breaker. */
    public void recordReachableForTask(ModelTask task) {
        llmKey(task).ifPresent(this::recordReachable);
    }

    /** Classified transport failure for {@code task}'s endpoint. */
    public void recordUnreachableForTask(ModelTask task) {
        llmKey(task).ifPresent(this::recordUnreachable);
    }

    /**
     * Return the HALF-OPEN probe {@link #tryAcquireForTask} handed out,
     * for a call that produced NO evidence about the endpoint (M1-769).
     *
     * <p>Acquiring the probe is a state change — it spends the single
     * slot and pushes the deadline a full cooldown forward — so a call
     * that reports neither reachable nor unreachable leaves the breaker
     * denying an endpoint nobody has observed since it tripped. The
     * digest's per-call spend cap makes that reachable: a budget refusal
     * is deliberately outside the {@code LlmCallFailedException} family
     * (it is evidence about our own spend, not about the provider), so
     * it passes both recording catches in the decorator and would
     * otherwise burn one recovery probe per cooldown for the whole
     * length of a refused render, extending every LLM surface's outage
     * against a provider that has already recovered.
     *
     * <p>Restores the pre-acquire state rather than closing or
     * re-opening the breaker: the endpoint is still unobserved, so the
     * NEXT caller should become the probe. A no-op unless the caller
     * actually HOLDS the probe (see
     * {@link EndpointBreaker#releaseProbe}), so releasing after an
     * ordinary CLOSED-breaker call costs nothing.
     */
    public void releaseProbeForTask(ModelTask task) {
        llmKey(task).ifPresent(this::releaseProbe);
    }

    /** Embedding-side twin of {@link #tryAcquireForTask}. */
    public boolean tryAcquireForEmbeddings() {
        return embeddingsKey()
            .map(key -> breakerFor(key).tryAcquire(clock.instant()))
            .orElse(true);
    }

    /** Embedding-side twin of {@link #recordReachableForTask}. */
    public void recordReachableForEmbeddings() {
        embeddingsKey().ifPresent(this::recordReachable);
    }

    /** Embedding-side twin of {@link #recordUnreachableForTask}. */
    public void recordUnreachableForEmbeddings() {
        embeddingsKey().ifPresent(this::recordUnreachable);
    }

    /** Embedding-side twin of {@link #releaseProbeForTask}. */
    public void releaseProbeForEmbeddings() {
        embeddingsKey().ifPresent(this::releaseProbe);
    }

    private void recordReachable(BreakerKey key) {
        // No breakerFor() here: reachability evidence on an endpoint with
        // no breaker entry has nothing to close — don't allocate state
        // for the healthy steady-state path.
        EndpointBreaker breaker = breakersByKey.get(key);
        if (breaker != null) {
            breaker.recordReachable();
        }
    }

    private void releaseProbe(BreakerKey key) {
        // get() rather than breakerFor(): a key with no breaker entry has
        // no probe to return — the same reason recordReachable does not
        // allocate on the healthy path.
        EndpointBreaker breaker = breakersByKey.get(key);
        if (breaker != null) {
            breaker.releaseProbe(clock.instant());
        }
    }

    private EndpointBreaker breakerFor(BreakerKey key) {
        return breakersByKey.computeIfAbsent(key,
            absent -> new EndpointBreaker(absent, failureThreshold, cooldown));
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

    private Optional<BreakerKey> llmKey(ModelTask task) {
        return endpointForTask(task).map(endpoint -> new BreakerKey(TransportKind.LLM, endpoint));
    }

    private Optional<BreakerKey> embeddingsKey() {
        return embeddingsEndpoint()
            .map(endpoint -> new BreakerKey(TransportKind.EMBEDDINGS, endpoint));
    }

    /**
     * Which SPI's transport a breaker guards. Part of the map key, not a
     * label: see the class javadoc §Keying for why one URL serving both
     * SPIs still needs two breakers.
     */
    private enum TransportKind { LLM, EMBEDDINGS }

    /** What an {@link EndpointBreaker#recordUnreachable} call changed. */
    private enum OpenedTransition { NONE, TRIPPED, REOPENED }

    /**
     * One breaker's identity. {@code endpoint} is the resolved base-url;
     * {@code kind} keeps the two SPIs' state apart on a shared URL.
     */
    private record BreakerKey(TransportKind kind, String endpoint) {

        /** Log-line identity — both components, since either can differ. */
        String label() {
            return endpoint + " (" + kind + ")";
        }
    }

    /**
     * One {@link BreakerKey}'s breaker state. All transitions are
     * synchronized on the instance — the critical sections are a handful
     * of field reads and writes, contention-irrelevant next to the HTTP
     * calls they gate.
     */
    private static final class EndpointBreaker {

        private enum State { CLOSED, OPEN, HALF_OPEN }

        private final String label;
        private final int failureThreshold;
        private final Duration cooldown;

        private State state = State.CLOSED;
        private int consecutiveUnreachableFailures = 0;
        // The thread admitted as the current HALF-OPEN probe, or null
        // when no probe is outstanding. Identifies the probe HOLDER so a
        // release can only return the slot its own caller took — see
        // releaseProbe.
        private @Nullable Thread probeOwner = null;
        // When OPEN: the instant the single HALF-OPEN probe becomes
        // admissible. When HALF_OPEN: the instant the in-flight probe's
        // slot expires — a probe whose thread never reports (killed
        // mid-call by an Error) would otherwise deny the endpoint until
        // restart, so the slot self-frees after one further cooldown and
        // the next call probes again.
        private Instant deadline = Instant.EPOCH;

        EndpointBreaker(BreakerKey key, int failureThreshold, Duration cooldown) {
            this.label = key.label();
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
                    probeOwner = Thread.currentThread();
                    yield true;
                }
            };
        }

        /**
         * Undo an acquisition that observed nothing: back to OPEN with the
         * probe admissible again from {@code now}. The caller is handing
         * back a slot it never used, so the endpoint is exactly as overdue
         * for a probe as it was an instant ago — hence no fresh cooldown.
         *
         * <p>Gated on OWNERSHIP, not merely on the state being HALF_OPEN:
         * a call admitted while the breaker was still CLOSED can outlive a
         * trip plus a whole cooldown (an LLM call's budget runs to 120s
         * against a 30s cooldown), and if it then reports no evidence — a
         * {@code /stop} interrupt, a budget refusal — a state-only check
         * would let it hand back a probe a DIFFERENT, newer call is
         * holding. The newer probe would keep running while the freed slot
         * admitted a second one, so two concurrent probes would hit an
         * endpoint the breaker is meant to be touching once. (M1-769)
         *
         * <p>Thread identity is a sound token here because acquire and
         * release are the same synchronous decorator invocation on one
         * thread ({@code CircuitBreakingLlmProvider.generate} /
         * {@code CircuitBreakingEmbeddingProvider.embed} wrap a blocking
         * call). Should that ever stop holding, the mismatch degrades to a
         * no-op release — the pre-M1-769 behaviour of burning the probe,
         * which is the safe direction.
         */
        synchronized void releaseProbe(Instant now) {
            if (state == State.HALF_OPEN && probeOwner == Thread.currentThread()) {
                state = State.OPEN;
                deadline = now;
                probeOwner = null;
            }
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
                    label);
            }
            state = State.CLOSED;
            consecutiveUnreachableFailures = 0;
            // The probe (if this call was one) has settled — drop the
            // owner so the reference does not outlive its thread.
            probeOwner = null;
        }

        synchronized OpenedTransition recordUnreachable(Instant now) {
            switch (state) {
                case CLOSED -> {
                    consecutiveUnreachableFailures++;
                    if (consecutiveUnreachableFailures >= failureThreshold) {
                        state = State.OPEN;
                        deadline = now.plus(cooldown);
                        LOG.warnf("LLM circuit breaker OPEN for %s after %d consecutive "
                                + "transport failures; short-circuiting calls for %d ms",
                            label, consecutiveUnreachableFailures, cooldown.toMillis());
                        return OpenedTransition.TRIPPED;
                    }
                }
                case HALF_OPEN -> {
                    // The probe failed: re-open with a fresh cooldown.
                    state = State.OPEN;
                    deadline = now.plus(cooldown);
                    probeOwner = null;
                    LOG.warnf("LLM circuit breaker re-OPENED for %s (probe failed); "
                            + "short-circuiting calls for %d ms",
                        label, cooldown.toMillis());
                    return OpenedTransition.REOPENED;
                }
                case OPEN -> {
                    // An in-flight straggler failing after the trip: the
                    // breaker is already open; refreshing the deadline
                    // would let stragglers extend the outage window
                    // indefinitely, so leave it.
                }
            }
            return OpenedTransition.NONE;
        }
    }

    private void recordUnreachable(BreakerKey key) {
        OpenedTransition transition = breakerFor(key).recordUnreachable(clock.instant());
        if (transition != OpenedTransition.NONE) {
            sink.accept(new LlmCircuitBreakerOpenedEvent(
                key.kind().name(), key.endpoint(), transition == OpenedTransition.REOPENED));
        }
    }
}
