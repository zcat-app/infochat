package app.zcat.infochat.collector.stream;

import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.core.ingest.StreamSource;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Lifecycle container for the Collector's {@link StreamSource} workers:
 * async registration/start, single-source stop, and coordinated drain on
 * graceful shutdown. It implements no specific StreamSource (Nostr is a
 * later ticket); it owns only the supervision shell.
 *
 * <h2>Startup ordering</h2>
 * <p>{@code @Priority(450)} per {@code docs/design/01-architecture.md}
 * §1.4.3 — runs after Flyway (100), BootstrapLoader (200),
 * OutboxRehydrator (300), and FetchScheduler (400). BootstrapLoader's
 * javadoc names this bean as the {@code @Priority(450)} integration point.
 *
 * <h2>Asynchronous startup</h2>
 * <p>{@link #register} submits the source's {@code start()} to a
 * virtual-thread executor and returns immediately; the registration is
 * tracked as started the moment the task is submitted. A relay unreachable
 * at boot therefore does not fail Collector startup or the readiness probe
 * (architecture.md §Ingest SPIs) — {@link #isReady} reports healthy once
 * the supervisor is accepting registrations, NOT once every relay is
 * connected.
 *
 * <h2>Drain on shutdown</h2>
 * <p>{@code @PreDestroy} calls {@link #drainAll} with the profile-driven
 * {@code infochat.stream.drain-timeout}. Each source's {@code stop()} is
 * where the impl flushes in-flight events to the outbox; the supervisor
 * bounds the TOTAL flush by a single shared deadline. Sources that do not
 * flush in time have their in-flight events dropped and their per-source
 * "events lost on shutdown" counter incremented for operator monitoring.
 */
@Startup
@Priority(450)
@ApplicationScoped
public class StreamSourceSupervisor {

    private static final Logger LOG = Logger.getLogger(StreamSourceSupervisor.class);

    @ConfigProperty(name = "infochat.stream.drain-timeout")
    Duration drainTimeout;

    private final Map<StreamDispatchKey, StreamSourceRegistration> registrations = new ConcurrentHashMap<>();

    // One virtual-thread-per-task executor runs both the long-lived
    // start() workers and the transient stop() drain tasks. Created in
    // init() so the bean is usable both under CDI (@PostConstruct) and in
    // a plain unit test (the package-private test constructor).
    @SuppressWarnings("NullAway.Init")
    private ExecutorService workerExecutor;

    // True once init() has run. Registration acceptance — not relay
    // connectivity — is the readiness condition the probe reads.
    private volatile boolean accepting;

    /** CDI constructor; {@code drainTimeout} is injected and {@link #onStartup} runs init. */
    StreamSourceSupervisor() {
    }

    /**
     * Test constructor: bypasses CDI injection of {@code drainTimeout} and
     * runs {@link #init} eagerly so a plain JUnit test can register, drain,
     * and stop without a Quarkus container.
     */
    StreamSourceSupervisor(Duration drainTimeout) {
        this.drainTimeout = drainTimeout;
        init();
    }

    @PostConstruct
    void onStartup() {
        init();
    }

    private void init() {
        workerExecutor = Executors.newVirtualThreadPerTaskExecutor();
        accepting = true;
    }

    /**
     * Register a StreamSource and start its background worker
     * asynchronously. Returns once the {@code start()} task is submitted —
     * it does not wait for the subscription to establish, so a relay that
     * is slow or unreachable at boot never blocks the caller.
     *
     * <p>Carries {@code filterSpec} and {@code deliver} beyond the
     * {@code (dispatchKey, source)} identity because the {@link StreamSource}
     * SPI's {@code start} needs both: the caller owns the outbox-writing
     * delivery callback, the supervisor owns only the worker lifecycle.</p>
     */
    public void register(StreamDispatchKey dispatchKey, String filterSpec,
                         StreamSource source, Consumer<NormalizedPost> deliver) {
        StreamSourceRegistration registration =
                new StreamSourceRegistration(dispatchKey, filterSpec, source, deliver);
        registrations.put(dispatchKey, registration);
        registration.startOn(workerExecutor);
    }

    /**
     * Stop a single stream source — used when its {@code source.status}
     * transitions to {@code 'failed'} (operator {@code /source-disable} or
     * the UNKNOWN-rate auto-disable). Accepts
     * <strong>only a dispatch key registered with this same supervisor instance</strong>
     * via {@link #register}. The {@link StreamDispatchKey} type makes that a
     * compile-time guarantee: stream dispatch keys and the polled
     * FetchScheduler's source keys are both monotonic from 1, but the
     * FetchScheduler's keys are bare {@code long}s and can no longer be passed
     * here — the keyspace collision that prose used to warn about is now a type
     * error (M1-371). For a key this supervisor did register, an absent one
     * (never registered, or already stopped) is a genuine no-op.
     */
    public void stop(StreamDispatchKey dispatchKey) {
        StreamSourceRegistration registration = registrations.remove(dispatchKey);
        if (registration == null) {
            return;
        }
        registration.stopNow();
    }

    /**
     * Drain every registered source: submit each {@code stop()} (where the
     * impl flushes in-flight events to the outbox) and wait for all of them
     * up to {@code timeout} TOTAL. Sources that do not flush within the
     * budget have their in-flight events dropped and their lost-events
     * counter incremented. Returns the per-source drain outcome
     * ({@code true} = flushed cleanly).
     */
    public Map<StreamDispatchKey, Boolean> drainAll(Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        List<StreamSourceDrainHandle> handles = new ArrayList<>();
        for (StreamSourceRegistration registration : registrations.values()) {
            handles.add(registration.beginDrain(workerExecutor));
        }
        Map<StreamDispatchKey, Boolean> outcomes = new HashMap<>();
        for (StreamSourceDrainHandle handle : handles) {
            boolean drained = handle.awaitUntil(deadline);
            outcomes.put(handle.dispatchKey(), drained);
            if (!drained) {
                LOG.warnf("StreamSource %d did not flush within drain timeout %s; in-flight events dropped",
                        handle.dispatchKey().value(), timeout);
            }
        }
        return outcomes;
    }

    /** Whether the supervisor is accepting registrations — the readiness condition. */
    public boolean isReady() {
        return accepting;
    }

    /**
     * Per-source "events lost on shutdown" counter for operator monitoring.
     * Keyed on {@link StreamDispatchKey}, not a bare {@code long}: a bare long
     * would still compile against the handle-keyed {@code registrations} map
     * ({@code Map.get} takes {@code Object}) but always miss and report 0, so
     * the type is what keeps the lookup correct (M1-371).
     */
    public long eventsLostOnShutdown(StreamDispatchKey dispatchKey) {
        StreamSourceRegistration registration = registrations.get(dispatchKey);
        return registration == null ? 0L : registration.eventsLostOnShutdown();
    }

    @PreDestroy
    void onShutdown() {
        drainAll(drainTimeout);
        workerExecutor.shutdownNow();
    }
}
