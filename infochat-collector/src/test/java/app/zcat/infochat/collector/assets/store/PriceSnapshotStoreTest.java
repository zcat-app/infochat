package app.zcat.infochat.collector.assets.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import app.zcat.infochat.collector.assets.PriceSnapshot;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Integration test for {@link PriceSnapshotStore} — exercises the
 * INSERT, the NOTIFY, the partition-routing, and the same-transaction
 * rollback contract. Pattern mirrors {@code ReadyPromoterIT} for the
 * LISTEN/NOTIFY fixture and the {@code afterInsertHook} test seam.
 *
 * <p>Each test uses a fixed {@code captured_at} of 2026-05-15
 * (within the {@code price_snapshot_p202605} bootstrap partition
 * declared in {@code V17__price_snapshot.sql}). A later partition
 * rotator ticket will add subsequent monthly partitions; this test
 * pins the May-2026 partition for determinism across calendar drift.
 */
@QuarkusTest
class PriceSnapshotStoreTest {

    private static final Instant CAPTURED_AT = Instant.parse("2026-05-15T12:00:00Z");
    private static final String TEST_ASSET_PREFIX = "snapshot-test-";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    PriceSnapshotStore store;

    @BeforeEach
    void resetHook() throws Exception {
        ClientProxy.unwrap(store).afterInsertHook = () -> {};
        clearTestRows();
    }

    @AfterEach
    void clearAfter() throws Exception {
        ClientProxy.unwrap(store).afterInsertHook = () -> {};
        clearTestRows();
    }

    @Test
    void insertEmitsNotify() throws Exception {
        String asset = TEST_ASSET_PREFIX + "notify";
        PriceSnapshot snap = newSnapshot(asset, "coingecko", "usd", new BigDecimal("123.456"));

        try (Connection listenConn = dataSource.getConnection()) {
            listenConn.setAutoCommit(true);
            try (Statement s = listenConn.createStatement()) {
                s.execute("LISTEN new_price_snapshot");
            }
            PGConnection pg = listenConn.unwrap(PGConnection.class);
            drainQueuedNotifications(pg);

            store.store(snap);

            PGNotification[] notifications = awaitNotifications(pg, 1);
            assertNotNull(notifications,
                "at least one NOTIFY new_price_snapshot must arrive");
            assertEquals(1, notifications.length,
                "exactly one NOTIFY per store(...)");
            PGNotification n = notifications[0];
            assertEquals("new_price_snapshot", n.getName());
            String payload = n.getParameter();
            assertTrue(payload.contains("\"asset\":\"" + asset + "\""),
                "payload must carry the asset name: " + payload);
            assertTrue(payload.contains("\"source\":\"coingecko\""),
                "payload must carry the source (sub_verb) key: " + payload);
        }

        // Row landed in parent table → visible via parent SELECT.
        int countParent = countRowsByAsset("price_snapshot", asset);
        assertEquals(1, countParent, "exactly one parent-table row for asset=" + asset);
    }

    @Test
    void appendsToCurrentPartition() throws Exception {
        String asset = TEST_ASSET_PREFIX + "partition";
        PriceSnapshot snap = newSnapshot(asset, "kraken", "usd", new BigDecimal("42.0"));

        store.store(snap);

        // CAPTURED_AT is 2026-05-15 which falls inside the bootstrap
        // partition price_snapshot_p202605 (FOR VALUES FROM
        // 2026-05-01 TO 2026-06-01). Direct SELECT against the
        // partition by name proves the partition routing — the
        // INSERT against the parent table flows into this child.
        int countPartition = countRowsByAsset("price_snapshot_p202605", asset);
        assertEquals(1, countPartition,
            "snapshot with captured_at=2026-05-15 must land in price_snapshot_p202605");
    }

    @Test
    void duplicateTripleInsertsExactlyOneRowAndNoSecondNotify() throws Exception {
        String asset = TEST_ASSET_PREFIX + "dedup";
        PriceSnapshot first = newSnapshot(asset, "coingecko", "usd", new BigDecimal("123.456"));
        // Same (asset, sub_verb, captured_at) triple — CAPTURED_AT is a
        // shared constant — with a divergent price: ON CONFLICT DO
        // NOTHING must drop it, never update the first row.
        PriceSnapshot duplicate = newSnapshot(asset, "coingecko", "usd", new BigDecimal("999.0"));

        try (Connection listenConn = dataSource.getConnection()) {
            listenConn.setAutoCommit(true);
            try (Statement s = listenConn.createStatement()) {
                s.execute("LISTEN new_price_snapshot");
            }
            PGConnection pg = listenConn.unwrap(PGConnection.class);
            drainQueuedNotifications(pg);

            store.store(first);
            PGNotification[] firstNotify = awaitNotifications(pg, 1);
            assertNotNull(firstNotify, "the first insert must emit NOTIFY");

            store.store(duplicate);
            PGNotification[] dupNotify = pg.getNotifications(500);
            assertTrue(dupNotify == null || dupNotify.length == 0,
                "a duplicate (asset, sub_verb, captured_at) must not emit NOTIFY; got: "
                    + java.util.Arrays.toString(dupNotify));
        }

        assertEquals(1, countRowsByAsset("price_snapshot", asset),
            "exactly one row for the duplicate triple (V38 UNIQUE + ON CONFLICT DO NOTHING)");
    }

    @Test
    void transactionRollbackSuppressesNotify() throws Exception {
        String asset = TEST_ASSET_PREFIX + "rollback";
        PriceSnapshot snap = newSnapshot(asset, "bitfinex", "usd", new BigDecimal("99.0"));

        // Inject a throwing hook between INSERT and NOTIFY — the
        // same-transaction rule must roll back BOTH so the listening
        // connection sees no NOTIFY AND no row remains in the table.
        ClientProxy.unwrap(store).afterInsertHook = () -> {
            throw new RuntimeException("simulated failure between INSERT and NOTIFY");
        };

        try (Connection listenConn = dataSource.getConnection()) {
            listenConn.setAutoCommit(true);
            try (Statement s = listenConn.createStatement()) {
                s.execute("LISTEN new_price_snapshot");
            }
            PGConnection pg = listenConn.unwrap(PGConnection.class);

            // A pooled JDBC connection may have queued notifications
            // from a SIBLING test (insertEmitsNotify /
            // appendsToCurrentPartition) that LISTENed on this same
            // physical connection earlier. Postgres keeps queueing
            // for any backend whose session is still subscribed
            // (the listen registration survives JDBC connection
            // close → pool release). Drain those before exercising
            // the rollback assertion; the invariant under test is
            // "NO NEW NOTIFY arrives from this rolled-back store()
            // call", not "the per-backend queue is empty at start".
            drainQueuedNotifications(pg);

            assertThrows(RuntimeException.class,
                () -> store.store(snap),
                "the test hook's throw must propagate through @Transactional");

            // Bounded wait — the correctness invariant is that
            // NO NOTIFY arrives after the drain; getNotifications
            // returns null or empty depending on the driver path.
            PGNotification[] notifications = pg.getNotifications(500);
            assertTrue(notifications == null || notifications.length == 0,
                "no NOTIFY may be observable when the @Transactional rolled back; got: "
                    + java.util.Arrays.toString(notifications));
        }

        int countParent = countRowsByAsset("price_snapshot", asset);
        assertEquals(0, countParent,
            "INSERT must roll back: zero rows after the simulated mid-transaction failure");
    }

    // ---------- helpers ----------

    private PriceSnapshot newSnapshot(String asset, String subVerb, String vs, BigDecimal price) {
        return new PriceSnapshot(
            asset,
            subVerb,
            vs,
            price,
            new BigDecimal("1000.0"),
            new BigDecimal("130.0"),
            new BigDecimal("110.0"),
            new BigDecimal("0.1234"),
            new BigDecimal("1.2345"),
            new BigDecimal("-2.3456"),
            CAPTURED_AT,
            "https://example.test/" + asset,
            "{\"asset\":\"" + asset + "\"}"
        );
    }

    private int countRowsByAsset(String table, String asset) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count(*) FROM " + table + " WHERE asset = ?")) {
            ps.setString(1, asset);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "SELECT count must yield a row");
                return rs.getInt(1);
            }
        }
    }

    private void clearTestRows() throws Exception {
        // V17 REVOKEs DELETE from infochat_collector / infochat_provider
        // / PUBLIC. The test JDBC user is the bootstrap `infochat`
        // superuser per application.properties (line 13), which is
        // unaffected by those REVOKEs.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM price_snapshot WHERE asset LIKE ?")) {
            ps.setString(1, TEST_ASSET_PREFIX + "%");
            ps.executeUpdate();
        }
    }

    private void drainQueuedNotifications(PGConnection pg) throws Exception {
        // Non-blocking poll — drain any notifications already queued
        // from a prior test that emitted NOTIFY on this channel via
        // a pooled connection that remained LISTENing at the Postgres
        // backend level. pgjdbc treats timeout=0 as "block forever";
        // use 1ms for a bounded near-instant drain.
        pg.getNotifications(1);
    }

    private PGNotification[] awaitNotifications(PGConnection pg, int minimum) throws Exception {
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
