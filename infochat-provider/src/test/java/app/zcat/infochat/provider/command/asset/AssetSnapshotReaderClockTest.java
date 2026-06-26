package app.zcat.infochat.provider.command.asset;

import app.zcat.infochat.provider.command.asset.AssetSnapshotReader.SnapshotResult;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link AssetSnapshotReader} freshness/TTL verdict against an
 * injected {@link Clock} (M1-454, engineering-rules §9). {@code loadLatest}
 * now feeds {@code clock.instant()} into the {@code isStale(capturedAt, now,
 * window)} comparison, so fixing the Clock decides the verdict: a snapshot age
 * of {@code window + 1s} is stale, {@code window - 1s} is fresh. The snapshot is
 * seeded ~30s old on the wall clock (fresh either way), so a stale verdict can
 * only come from the pinned Clock. Distinct asset keys per test sidestep the
 * module-wide {@code @ApplicationScoped} read cache.
 */
@QuarkusTest
class AssetSnapshotReaderClockTest {

    private static final String SUB_VERB = "coingecko";
    private static final String VS = "usd";
    private static final BigDecimal PRICE = new BigDecimal("9.99");
    /** Matches infochat.assets.freshness-window default in application.properties. */
    private static final Duration FRESHNESS_WINDOW = Duration.ofSeconds(180);

    @Inject AssetSnapshotReader reader;
    @Inject @SeedDataSource DataSource dataSource;

    @BeforeEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM price_snapshot WHERE asset LIKE 'm454clock-%'")) {
            ps.executeUpdate();
        }
    }

    @Test
    void staleVerdictTrueWhenInjectedNowPastFreshnessBoundary() throws Exception {
        String asset = "m454clock-stale";
        Instant capturedAt = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.SECONDS);
        seedSnapshot(asset, capturedAt);
        // Pin one second past the freshness boundary: age = window + 1s.
        QuarkusMock.installMockForType(
                Clock.fixed(capturedAt.plus(FRESHNESS_WINDOW).plusSeconds(1), ZoneOffset.UTC),
                Clock.class);

        SnapshotResult result = reader.readLatest(asset, SUB_VERB, VS);

        assertNotNull(result, "the seeded snapshot must be read");
        assertTrue(result.stale(),
            "a snapshot whose age exceeds the freshness window under the injected clock "
                + "must be reported stale (it is fresh on the wall clock)");
    }

    @Test
    void staleVerdictFalseWhenInjectedNowWithinFreshnessBoundary() throws Exception {
        String asset = "m454clock-fresh";
        Instant capturedAt = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.SECONDS);
        seedSnapshot(asset, capturedAt);
        // Pin one second inside the freshness boundary: age = window - 1s.
        QuarkusMock.installMockForType(
                Clock.fixed(capturedAt.plus(FRESHNESS_WINDOW).minusSeconds(1), ZoneOffset.UTC),
                Clock.class);

        SnapshotResult result = reader.readLatest(asset, SUB_VERB, VS);

        assertNotNull(result, "the seeded snapshot must be read");
        assertFalse(result.stale(),
            "a snapshot whose age is within the freshness window under the injected clock "
                + "must be reported fresh");
    }

    private void seedSnapshot(String asset, Instant capturedAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO price_snapshot (asset, sub_verb, vs_currency, price, "
                             + "high_24h, low_24h, change_1h_pct, change_24h_pct, "
                             + "captured_at, source_url) "
                             + "VALUES (?, ?, ?, ?, 13.00, 11.50, 0.2, -1.1, ?, "
                             + "'coingecko.com/en/coins/m454clock')")) {
            ps.setString(1, asset);
            ps.setString(2, SUB_VERB);
            ps.setString(3, VS);
            ps.setBigDecimal(4, PRICE);
            ps.setTimestamp(5, Timestamp.from(capturedAt));
            ps.executeUpdate();
        }
    }
}
