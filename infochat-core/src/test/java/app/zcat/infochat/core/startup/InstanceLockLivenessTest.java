package app.zcat.infochat.core.startup;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

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

    @Test
    void probeScopesOwnershipToTheGateLockNotAnyAdvisoryLock() throws Exception {
        // M1-338: the probe must verify this session holds the SPECIFIC
        // single-instance gate lock, not merely "some advisory lock". A second,
        // unrelated advisory lock on the same session must NOT mask a
        // server-side release of the gate — under the pre-M1-338 bare EXISTS
        // predicate the lingering second lock would keep the probe reporting
        // owned=true after the gate was released, hiding a zombie. The scoped
        // predicate must report lost ownership and exit instead.
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);

            long gateLockId;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT hashtext('infochat.test')::int8")) {
                rs.next();
                gateLockId = rs.getLong(1);
            }
            // A distinct advisory key (+1 cannot collide with the gate's id) so
            // the second lock is a genuinely different (classid, objid) row.
            long otherLockId = gateLockId + 1;

            try {
                try (Statement st = conn.createStatement()) {
                    st.execute("SELECT pg_advisory_lock(" + gateLockId + ")");
                    st.execute("SELECT pg_advisory_lock(" + otherLockId + ")");
                }

                RecordingExitHook exit = new RecordingExitHook();
                TestLockGuard guard = new TestLockGuard();
                guard.primeForTest(conn, exit);

                // Gate lock present (alongside the unrelated lock): the probe
                // sees its own gate and must not exit.
                guard.probeHeldSession();
                assertFalse(exit.fired(),
                        "with the gate lock held the probe must not exit, even though a second advisory lock is also held");

                // Release ONLY the gate lock; the unrelated advisory lock stays
                // held on the same session.
                try (Statement st = conn.createStatement()) {
                    st.execute("SELECT pg_advisory_unlock(" + gateLockId + ")");
                }

                // The gate is gone; the scoped probe must now report lost
                // ownership and exit, even though the session still holds another
                // advisory lock. A bare EXISTS over any advisory row would still
                // see otherLockId and falsely stay owned=true here.
                guard.probeHeldSession();
                assertEquals(1, exit.lastCode(),
                        "releasing the gate lock must trigger exit(1) even while another advisory lock is held");
            } finally {
                // Release every advisory lock before the connection returns to
                // the Agroal pool, so neither lock lingers on the pooled session.
                try (Statement st = conn.createStatement()) {
                    st.execute("SELECT pg_advisory_unlock_all()");
                }
            }
        }
    }

    @Test
    void contentionFatalLineStripsControlCharsFromHostId() {
        char esc = (char) 0x1B;
        char csi = (char) 0x9B;
        String poisonedHostId = "evil" + esc + "[2J" + csi + "31mhost";

        TestLockGuard guard = new TestLockGuard();
        List<LogRecord> captured = captureFatalRecords(() ->
                guard.logContention(Optional.of(new AbstractInstanceLockGuard.Holder(
                        poisonedHostId, 42, Instant.now()))));

        assertEquals(1, captured.size(), "exactly one contention fatal line expected");
        String message = captured.get(0).getMessage();
        assertTrue(message.indexOf(esc) < 0 && message.indexOf(csi) < 0,
                "control characters from a poisoned heartbeat host_id must be stripped");
        assertTrue(message.contains("evil [2J 31mhost"),
                "host_id content must survive with controls replaced by spaces");
    }

    @Test
    void deadHeldConnectionFatalLineOmitsExceptionMessage() {
        String driverSecret = "FATAL: password=hunter2 rejected for db.internal";
        Connection throwingConnection = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    throw new SQLException(driverSecret);
                });

        RecordingExitHook exit = new RecordingExitHook();
        TestLockGuard guard = new TestLockGuard();
        guard.primeForTest(throwingConnection, exit);

        List<LogRecord> captured = captureFatalRecords(guard::probeHeldSession);

        assertEquals(1, exit.lastCode(), "a dead held session must still exit(1)");
        assertEquals(1, captured.size(), "exactly one dead-session fatal line expected");
        String message = captured.get(0).getMessage();
        assertFalse(message.contains(driverSecret),
                "raw SQLException message text must not reach the fatal line");
        assertFalse(message.contains("hunter2"),
                "driver-echoed secrets must not reach the fatal line");
        assertTrue(message.contains(SQLException.class.getName()),
                "the fatal line must still name the exception class");
    }

    // Captures records at SEVERE-and-above (JBoss FATAL sits above JUL
    // SEVERE) on the TestLockGuard category — the guard logs on
    // Logger.getLogger(getClass()), i.e. the concrete subclass.
    private List<LogRecord> captureFatalRecords(Runnable action) {
        List<LogRecord> captured = Collections.synchronizedList(new ArrayList<>());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
                    captured.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        // jboss-logging routes through the JBoss LogManager only when it
        // is installed as the JVM's LogManager; otherwise it falls back
        // to stock JUL. Attach to both hierarchies — the identity check
        // prevents double capture when they are the same logger object.
        java.util.logging.Logger jul = java.util.logging.Logger
                .getLogger(TestLockGuard.class.getName());
        java.util.logging.Logger ctx = org.jboss.logmanager.LogContext.getLogContext()
                .getLogger(TestLockGuard.class.getName());
        jul.addHandler(capture);
        if (ctx != jul) {
            ctx.addHandler(capture);
        }
        try {
            action.run();
        } finally {
            jul.removeHandler(capture);
            if (ctx != jul) {
                ctx.removeHandler(capture);
            }
        }
        return captured;
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
