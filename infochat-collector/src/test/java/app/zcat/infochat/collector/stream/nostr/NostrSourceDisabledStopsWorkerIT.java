package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.collector.eval.reeval.PerSourceUnknownTracker;
import app.zcat.infochat.collector.stream.StreamDispatchKey;
import app.zcat.infochat.collector.stream.StreamSourceSupervisor;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.core.ingest.StreamSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the U-03 fix wiring: a {@link PerSourceUnknownTracker.SourceDisabled}
 * signal reaches {@code supervisor.stop} for the right dispatch key — stopping
 * exactly the disabled source's stream worker — and is a logged no-op for a
 * source with no registered worker.
 *
 * <p>The observer is driven on a directly-constructed {@code Registrar} wired
 * to the real (CDI) supervisor: {@link StreamSourceSupervisor}'s constructors
 * are package-private to the {@code stream} package, so a unit-constructed
 * supervisor is not reachable from here — the container supplies it. The
 * Registrar's {@code dispatchKeyBySource} map is populated directly (no CDI
 * proxy) to stand in for the startup registration a live nostr source would
 * have performed.
 */
@QuarkusTest
class NostrSourceDisabledStopsWorkerIT {

    // Dispatch keys well above any the startup Registrar mints (monotonic from
    // 1) so these test workers never collide with bootstrap-seeded nostr sources
    // registered with the same shared supervisor.
    private static final StreamDispatchKey DISABLED_KEY = new StreamDispatchKey(9101L);
    private static final StreamDispatchKey OTHER_KEY = new StreamDispatchKey(9102L);

    @Inject
    StreamSourceSupervisor supervisor;

    // The supervisor is an application-scoped CDI singleton shared across the
    // whole @QuarkusTest run; disableStopsOnlyTheMatchingWorker stops only
    // DISABLED_KEY's worker, leaving OTHER_KEY registered. Deregister both keys
    // here (stop() is an idempotent no-op for an already-removed/absent key) so
    // the shared bean is clean between tests.
    @AfterEach
    void deregisterTestWorkers() {
        supervisor.stop(DISABLED_KEY);
        supervisor.stop(OTHER_KEY);
    }

    @Test
    void disableStopsOnlyTheMatchingWorker() throws InterruptedException {
        NostrStreamSource.Registrar registrar = new NostrStreamSource.Registrar();
        registrar.supervisor = supervisor;

        UUID disabledSource = UUID.randomUUID();
        UUID otherSource = UUID.randomUUID();
        RecordingStreamSource disabledWorker = new RecordingStreamSource();
        RecordingStreamSource otherWorker = new RecordingStreamSource();
        supervisor.register(DISABLED_KEY, "spec", disabledWorker, noopDeliver());
        supervisor.register(OTHER_KEY, "spec", otherWorker, noopDeliver());
        registrar.dispatchKeyBySource.put(disabledSource, DISABLED_KEY);
        registrar.dispatchKeyBySource.put(otherSource, OTHER_KEY);
        assertTrue(disabledWorker.startEntered.await(2, TimeUnit.SECONDS), "disabled worker started");
        assertTrue(otherWorker.startEntered.await(2, TimeUnit.SECONDS), "other worker started");

        registrar.onSourceDisabled(new PerSourceUnknownTracker.SourceDisabled(disabledSource));

        assertTrue(disabledWorker.stopped(), "the disabled source's worker was stopped");
        assertFalse(otherWorker.stopped(), "an unrelated source's worker keeps running");
    }

    @Test
    void disableForUnregisteredSourceIsNoOp() {
        NostrStreamSource.Registrar registrar = new NostrStreamSource.Registrar();
        registrar.supervisor = supervisor;

        // No map entry for this source — the common case (a polled, non-stream
        // source crossing the threshold) and the already-stopped case. The
        // boundary lookup misses and the observer no-ops without throwing.
        registrar.onSourceDisabled(new PerSourceUnknownTracker.SourceDisabled(UUID.randomUUID()));
    }

    private static Consumer<NormalizedPost> noopDeliver() {
        return post -> { };
    }
}

/**
 * Minimal {@link StreamSource} double that records whether {@code stop()} ran.
 * Top-level package-private per the module's test-double convention (the
 * {@code stream}-package {@code FakeStreamSource} is not visible from this
 * sub-package). {@code start()} returns at once after signalling entry.
 */
final class RecordingStreamSource implements StreamSource {

    final CountDownLatch startEntered = new CountDownLatch(1);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    @Override
    public void start(long dispatchKey, String filterSpec, Consumer<NormalizedPost> deliver) {
        startEntered.countDown();
    }

    @Override
    public void stop() {
        stopped.set(true);
    }

    boolean stopped() {
        return stopped.get();
    }
}
