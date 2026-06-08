package app.zcat.infochat.collector.assets.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import app.zcat.infochat.collector.assets.PriceSnapshot;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Integration test for {@link PriceSnapshotStore} — exercises the
 * INSERT, the partition-routing, and the same-transaction rollback
 * contract. Pattern mirrors {@code ReadyPromoterIT} for the
 * {@code afterInsertHook} test seam.
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
    void insertLandsRowInParentTable() throws Exception {
        String asset = TEST_ASSET_PREFIX + "insert";
        PriceSnapshot snap = newSnapshot(asset, "coingecko", "usd", new BigDecimal("123.456"));

        store.store(snap);

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
    void duplicateTripleInsertsExactlyOneRow() throws Exception {
        String asset = TEST_ASSET_PREFIX + "dedup";
        PriceSnapshot first = newSnapshot(asset, "coingecko", "usd", new BigDecimal("123.456"));
        // Same (asset, sub_verb, captured_at) triple — CAPTURED_AT is a
        // shared constant — with a divergent price: ON CONFLICT DO
        // NOTHING must drop it, never update the first row.
        PriceSnapshot duplicate = newSnapshot(asset, "coingecko", "usd", new BigDecimal("999.0"));

        store.store(first);
        store.store(duplicate);

        assertEquals(1, countRowsByAsset("price_snapshot", asset),
            "exactly one row for the duplicate triple (V38 UNIQUE + ON CONFLICT DO NOTHING)");
    }

    @Test
    void transactionFailureRollsBackInsert() throws Exception {
        String asset = TEST_ASSET_PREFIX + "rollback";
        PriceSnapshot snap = newSnapshot(asset, "bitfinex", "usd", new BigDecimal("99.0"));

        // Inject a throwing hook after the INSERT — the @Transactional
        // boundary must roll the INSERT back so no row remains.
        ClientProxy.unwrap(store).afterInsertHook = () -> {
            throw new RuntimeException("simulated failure after INSERT");
        };

        assertThrows(RuntimeException.class,
            () -> store.store(snap),
            "the test hook's throw must propagate through @Transactional");

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

}
