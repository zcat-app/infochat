package app.zcat.infochat.core.startup;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link AbstractInstanceLockGuard#probeHeldSession()} against a real
 * Postgres via Quarkus dev-services, driving the probe directly with real held
 * connections (the {@code %test} scheduler is halted, so a tick-based probe
 * never fires on its own). Asserts the probe's exit path via an injected
 * {@link AbstractInstanceLockGuard.ExitHook} rather than killing the test JVM.
 */
@QuarkusTest
class InstanceLockLivenessTest {

    @Inject
    DataSource dataSource;

    @Test
    void deadHeldConnectionTriggersExit() throws Exception {
        Connection conn = dataSource.getConnection();
        conn.close(); // simulate a held session whose connection died

        RecordingExitHook exit = new RecordingExitHook();
        TestLockGuard guard = new TestLockGuard();
        guard.primeForTest(conn, exit);

        guard.probeHeldSession();

        assertEquals(1, exit.lastCode(), "dead held connection must trigger exit(1)");
    }

    @Test
    void lostOwnershipTriggersExit() throws Exception {
        // A live session that never acquired the advisory lock stands in for a
        // held session whose lock was released server-side.
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);

            RecordingExitHook exit = new RecordingExitHook();
            TestLockGuard guard = new TestLockGuard();
            guard.primeForTest(conn, exit);

            guard.probeHeldSession();

            assertEquals(1, exit.lastCode(), "lost advisory-lock ownership must trigger exit(1)");
        }
    }

    @Test
    void aliveOwningHeldConnectionDoesNotExit() throws Exception {
        // A live session that owns an advisory lock is the healthy case. This
        // also proves the probe consults the held connection (which owns the
        // session-scoped lock) rather than a transient pool connection (which
        // would not own it and would falsely trigger exit).
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.execute("SELECT pg_advisory_lock(hashtext('infochat.test'))");
            }
            try {
                RecordingExitHook exit = new RecordingExitHook();
                TestLockGuard guard = new TestLockGuard();
                guard.primeForTest(conn, exit);

                guard.probeHeldSession();

                assertFalse(exit.fired(), "a live, lock-owning held session must not exit");
            } finally {
                // Release before the connection returns to the Agroal pool, so
                // the advisory lock does not linger on the pooled session.
                try (Statement st = conn.createStatement()) {
                    st.execute("SELECT pg_advisory_unlock(hashtext('infochat.test'))");
                }
            }
        }
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

        int lastCode() {
            assertTrue(fired(), "exit hook was expected to fire");
            return code.get();
        }
    }
}
