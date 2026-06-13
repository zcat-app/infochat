package app.zcat.infochat.provider.outbox;

import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.postgresql.PGNotification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Lifecycle integration test for {@link AbstractPgListener} itself —
 * exercises start, NOTIFY dispatch, forced reconnect, and stop directly
 * through the shared base machinery (not via a production listener), so
 * the extracted worker/connection lifecycle is pinned in isolation.
 *
 * <p>A minimal {@link RecordingListener} subclass owns its own private
 * test channel and counts catch-up invocations and dispatched payloads:
 *
 * <ol>
 *   <li>{@link AbstractPgListener#start()} opens the LISTEN connection and
 *       starts the worker — the worker reports alive and, because the
 *       connection is already open on the first loop iteration, no
 *       catch-up has run yet.</li>
 *   <li>A NOTIFY on the test channel is dispatched to the subclass.</li>
 *   <li>{@link AbstractPgListener#closeListenConnectionForTest()} severs
 *       the connection; the worker reconnects, re-issues LISTEN, and runs
 *       {@link AbstractPgListener#runCatchUp()} once — proven by the
 *       catch-up counter advancing AND a post-reconnect NOTIFY still being
 *       delivered (only possible if LISTEN was re-issued on the fresh
 *       session).</li>
 *   <li>{@link AbstractPgListener#stop()} drains the worker thread.</li>
 * </ol>
 */
@QuarkusTest
class AbstractPgListenerLifecycleIT {

    private static final String TEST_CHANNEL = "abstract_pg_listener_test";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final long AWAIT_POLL_MS = 100;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    /**
     * Minimal concrete listener that records the base machinery's
     * observable effects. Instantiated directly (not a CDI bean): the
     * package-private {@code dataSource} field is set by the test before
     * {@link #start()}.
     */
    static final class RecordingListener extends AbstractPgListener {
        private static final Logger LOG = Logger.getLogger(RecordingListener.class);

        final AtomicInteger catchUps = new AtomicInteger();
        final List<String> dispatched = new CopyOnWriteArrayList<>();

        @Override
        String channelName() {
            return TEST_CHANNEL;
        }

        @Override
        String workerThreadName() {
            return "abstract-pg-listener-test";
        }

        @Override
        Logger log() {
            return LOG;
        }

        @Override
        void dispatch(PGNotification notification) {
            if (TEST_CHANNEL.equals(notification.getName())) {
                dispatched.add(notification.getParameter());
            }
        }

        @Override
        void runCatchUp() {
            catchUps.incrementAndGet();
        }
    }

    @Test
    void startDispatchReconnectStopThroughBase() throws Exception {
        RecordingListener listener = new RecordingListener();
        listener.dataSource = dataSource;
        try {
            listener.start();
            assertTrue(listener.isWorkerAlive(),
                "worker must be alive after start()");
            // The first loop iteration sees the connection start() already
            // opened, so the catch-up path has not run yet — catch-up is a
            // reconnect-only recovery step.
            assertEquals(0, listener.catchUps.get(),
                "no catch-up should run while the initial connection stays open");

            emitNotify("before-reconnect");
            await(() -> listener.dispatched.contains("before-reconnect"),
                "the NOTIFY emitted on the live connection must be dispatched");

            // Sever the connection: the worker must reconnect, re-issue
            // LISTEN, and run catch-up exactly once on the fresh session.
            listener.closeListenConnectionForTest();
            await(() -> listener.catchUps.get() >= 1,
                "the reconnect must run runCatchUp() once the connection is severed");
            assertTrue(listener.isWorkerAlive(),
                "worker must remain alive across the forced reconnect");

            emitNotify("after-reconnect");
            await(() -> listener.dispatched.contains("after-reconnect"),
                "a post-reconnect NOTIFY must still be dispatched — only possible "
                    + "if LISTEN was re-issued on the reconnected session");
        } finally {
            listener.stop();
        }

        await(() -> !listener.isWorkerAlive(),
            "worker must drain after stop()");
    }

    private void emitNotify(String payload) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT pg_notify('" + TEST_CHANNEL + "', ?)")) {
            ps.setString(1, payload);
            ps.executeQuery().close();
        }
    }

    private void await(BooleanSupplier condition, String failureMessage) throws InterruptedException {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(AWAIT_POLL_MS);
        }
        fail(failureMessage);
    }
}
