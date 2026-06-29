package app.zcat.infochat.collector.testsupport;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Postgres LISTEN/NOTIFY await fixture shared by the collector NOTIFY ITs
 * (Stage 1 quarantine-pending, Stage 2 benign-close). The drain-the-pooled-
 * connection wire contract — pooled connections keep LISTEN registrations
 * across check-ins, so a stale registration accumulates other tests' commits
 * unless reset and drained first — lives here once rather than per IT.
 */
public final class PgNotifyFixture {

    private PgNotifyFixture() {
    }

    /**
     * LISTEN on a clean slate. Pooled connections keep their LISTEN
     * registrations across pool check-ins and accumulate notifications
     * from other tests' commits, so reset the registrations and drain
     * anything already delivered before the test acts.
     */
    public static PGConnection listenTo(Connection conn, String channel) throws Exception {
        conn.setAutoCommit(true);
        try (Statement s = conn.createStatement()) {
            s.execute("UNLISTEN *");
            s.execute("LISTEN " + channel);
        }
        PGConnection pg = conn.unwrap(PGConnection.class);
        pg.getNotifications();
        return pg;
    }

    /**
     * Poll {@code getNotifications} until at least {@code minimum}
     * notifications arrive OR the bounded wait elapses. Returns the
     * accumulated array (possibly more than {@code minimum} elements)
     * or null when nothing arrived.
     */
    public static PGNotification[] awaitNotifications(PGConnection pg, int minimum) throws Exception {
        long deadlineNanos = System.nanoTime() + 10_000_000_000L;
        List<PGNotification> collected = new ArrayList<>();
        while (System.nanoTime() < deadlineNanos) {
            PGNotification[] batch = pg.getNotifications(500);
            if (batch != null) {
                for (PGNotification n : batch) {
                    collected.add(n);
                }
                if (collected.size() >= minimum) {
                    return collected.toArray(new PGNotification[0]);
                }
            }
        }
        return collected.isEmpty() ? null : collected.toArray(new PGNotification[0]);
    }
}
