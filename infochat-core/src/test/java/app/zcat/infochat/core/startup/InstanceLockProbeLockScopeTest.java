package app.zcat.infochat.core.startup;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins U-60: {@link AbstractInstanceLockGuard#probeHeldSession()} snapshots the
 * held connection under {@code connectionLock} and then runs its blocking
 * {@code SELECT 1} round-trip <em>outside</em> the lock, so {@code @PreDestroy}
 * ({@code onShutdown}, which synchronizes on the same lock) is never stalled
 * behind the probe's up-to-10s network timeout.
 *
 * <p>Pure unit test, no Postgres: the held connection is a {@link Proxy} whose
 * {@code SELECT 1} blocks on a latch, simulating a probe stuck on a slow/dead
 * socket. The test asserts that {@code onShutdown} completes while the probe is
 * still parked in that SELECT. Under the pre-U-60 shape (probe holds
 * connectionLock across the SELECT) {@code onShutdown} would block on the lock
 * until the probe returned, so the completion assertion would fail.
 */
class InstanceLockProbeLockScopeTest {

    @Test
    void shutdownIsNotBlockedByAnInFlightProbe() throws Exception {
        CountDownLatch probeInSelect = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);

        Connection blockingConnection = blockingConnection(probeInSelect, releaseProbe);
        RecordingExitHook exit = new RecordingExitHook();
        TestLockGuard guard = new TestLockGuard();
        guard.primeForTest(blockingConnection, exit);

        Thread probeThread = new Thread(guard::probeHeldSession, "probe");
        probeThread.start();

        assertTrue(probeInSelect.await(5, TimeUnit.SECONDS),
                "probe must reach the blocking SELECT 1");

        // onShutdown synchronizes on connectionLock. If the probe held that lock
        // across its blocking SELECT (the pre-U-60 shape) this thread would not
        // finish until releaseProbe fires; with the fix it returns promptly.
        Thread shutdownThread = new Thread(guard::onShutdown, "shutdown");
        shutdownThread.start();
        shutdownThread.join(2_000);
        boolean shutdownCompleted = !shutdownThread.isAlive();

        // Unblock the probe regardless, so no thread leaks past the test.
        releaseProbe.countDown();
        probeThread.join(5_000);
        shutdownThread.join(5_000);

        assertTrue(shutdownCompleted,
                "onShutdown must not block behind the probe's in-flight SELECT "
                        + "(U-60: the probe runs its round-trips outside connectionLock)");
        assertFalse(exit.fired(),
                "a probe that completes normally on a live, lock-owning session must not exit");
    }

    // A Connection whose first statement's SELECT 1 parks until releaseProbe,
    // signalling probeInSelect on entry; every query otherwise reports a live,
    // lock-owning session (next() true, getBoolean() true) so the probe's
    // ownership re-check passes and it exits cleanly.
    private static Connection blockingConnection(CountDownLatch probeInSelect, CountDownLatch releaseProbe) {
        ClassLoader cl = InstanceLockProbeLockScopeTest.class.getClassLoader();
        ResultSet liveResultSet = (ResultSet) Proxy.newProxyInstance(cl,
                new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next", "getBoolean" -> true;
                    case "close" -> null;
                    default -> defaultValue(method.getReturnType());
                });
        Statement statement = (Statement) Proxy.newProxyInstance(cl,
                new Class<?>[] {Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "executeQuery" -> {
                        if ("SELECT 1".equals(args[0])) {
                            probeInSelect.countDown();
                            releaseProbe.await();
                        }
                        yield liveResultSet;
                    }
                    case "close" -> null;
                    default -> defaultValue(method.getReturnType());
                });
        return (Connection) Proxy.newProxyInstance(cl,
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createStatement" -> statement;
                    case "close" -> null;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == float.class) {
            return 0.0f;
        }
        return 0;
    }

    private static final class TestLockGuard extends AbstractInstanceLockGuard {
        @Override
        protected String serviceName() {
            return "test";
        }
    }

    private static final class RecordingExitHook implements AbstractInstanceLockGuard.ExitHook {
        private final AtomicInteger code = new AtomicInteger(Integer.MIN_VALUE);

        @Override
        public void exit(int code) {
            this.code.set(code);
        }

        boolean fired() {
            return code.get() != Integer.MIN_VALUE;
        }
    }
}
