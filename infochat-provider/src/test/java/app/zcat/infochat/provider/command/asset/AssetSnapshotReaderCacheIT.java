package app.zcat.infochat.provider.command.asset;

import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the M1-365 read-cache: a repeated {@code readLatest} of the same
 * {@code (asset, sub_verb, vs_currency)} within the cache TTL is served from
 * memory, not the DB.
 *
 * <p>Proof without a JDBC spy: seed one snapshot, read it (populating the
 * cache), then DELETE the row. A second read within the TTL can only return a
 * non-null value if it came from the cache — a fresh DB read of the now-empty
 * table would return null. The asset key is unique to this IT so the
 * module-wide {@code @ApplicationScoped} cache cannot collide with the
 * {@code zcash} fixtures other asset ITs seed in the same JVM.</p>
 */
@QuarkusTest
class AssetSnapshotReaderCacheIT {

    private static final String ASSET = "m365cache";
    private static final String SUB_VERB = "coingecko";
    private static final String VS = "usd";
    private static final BigDecimal PRICE = new BigDecimal("12.34");
    /**
     * Fixed capture instant inside the migration-provisioned May 2026
     * partition (M1-740: a wall-clock {@code captured_at} breaks on every
     * unprovisioned month boundary). The staleness flag is not this test's
     * verdict — the cache-vs-DB discrimination is — so no Clock pin is needed.
     */
    private static final Instant CAPTURED_AT = Instant.parse("2026-05-22T12:00:00Z");

    @Inject AssetSnapshotReader reader;
    @Inject @SeedDataSource DataSource dataSource;

    @BeforeEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM price_snapshot WHERE asset = ?")) {
            ps.setString(1, ASSET);
            ps.executeUpdate();
        }
    }

    @Test
    void repeatedReadWithinTtlIsServedFromCacheNotDb() throws Exception {
        seedSnapshot();

        AssetSnapshotReader.SnapshotResult first = reader.readLatest(ASSET, SUB_VERB, VS);
        assertNotNull(first, "first read must hit the DB and return the seeded snapshot");
        assertEquals(0, PRICE.compareTo(first.snapshot().price()),
                "first read returns the seeded price");

        // Remove the row: a fresh DB read would now return null.
        deleteSnapshot();

        AssetSnapshotReader.SnapshotResult second = reader.readLatest(ASSET, SUB_VERB, VS);
        assertNotNull(second,
                "second read within the TTL must be served from cache, not the now-empty DB");
        assertEquals(0, PRICE.compareTo(second.snapshot().price()),
                "the cached first-read value is returned, not a fresh (null) DB hit");
    }

    private void seedSnapshot() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO price_snapshot (asset, sub_verb, vs_currency, price, "
                             + "high_24h, low_24h, change_1h_pct, change_24h_pct, "
                             + "captured_at, source_url) "
                             + "VALUES (?, ?, ?, ?, 13.00, 11.50, 0.2, -1.1, ?, "
                             + "'coingecko.com/en/coins/m365cache')")) {
            ps.setString(1, ASSET);
            ps.setString(2, SUB_VERB);
            ps.setString(3, VS);
            ps.setBigDecimal(4, PRICE);
            ps.setTimestamp(5, Timestamp.from(CAPTURED_AT));
            ps.executeUpdate();
        }
    }

    private void deleteSnapshot() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM price_snapshot WHERE asset = ?")) {
            ps.setString(1, ASSET);
            ps.executeUpdate();
        }
    }
}
