package app.zcat.infochat.provider.chat.tool;

import app.zcat.infochat.provider.chat.ChatToolDispatcher;
import app.zcat.infochat.provider.command.asset.AssetRegistry;
import app.zcat.infochat.provider.command.asset.AssetRegistryTestRefresh;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins getPrice end-to-end through the REAL dispatcher: latest-row dispatch, serve-stale-with-age, and the typed no-data error. Fixed instants stay inside the May 2026 partition (M1-740); each arm reads its own asset so the reader's TTL cache cannot bleed a verdict between arms. Price data is deployment-global operator config — no user or scope seeding needed. */
@QuarkusTest
class GetPriceToolIT {

    /** Fixed capture instant inside the migration-provisioned May 2026 partition (M1-740). */
    private static final Instant CAPTURED_NEW = Instant.parse("2026-05-22T12:00:00Z");
    private static final Instant CAPTURED_OLD = Instant.parse("2026-05-22T11:00:00Z");
    private static final String ATTRIBUTION = "coingecko.com/en/coins/zcash";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    ChatToolDispatcher toolDispatcher;

    @Inject
    AssetRegistry assetRegistry;

    @BeforeEach
    void seed() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().executeUpdate(
                "DELETE FROM price_snapshot WHERE asset IN ('zcash','monero','dash')");
            conn.createStatement().executeUpdate(
                "DELETE FROM asset_config WHERE asset IN ('zcash','monero','dash')");

            insertAssetConfig(conn, "zcash", ATTRIBUTION);
            insertAssetConfig(conn, "monero", "coingecko.com/en/coins/monero");
            insertAssetConfig(conn, "dash", "coingecko.com/en/coins/dash");

            insertSnapshot(conn, "zcash", CAPTURED_OLD, "40.123456789012",
                null, null, null, null, null);
            // The newer zcash row: extras partially NULL so the null-emission
            // rule is pinned (absent numerics emit null, never invented zeros).
            insertSnapshot(conn, "zcash", CAPTURED_NEW, "42.180000000000",
                null, "43.910000000000", "41.070000000000",
                "0.3000", "-2.4000");
            insertSnapshot(conn, "monero", CAPTURED_NEW, "180.250000000000",
                null, null, null, null, null);
            // dash: enabled default pair, ZERO price_snapshot rows.
        }
        AssetRegistryTestRefresh.refresh(assetRegistry);
    }

    @AfterEach
    void clean() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().executeUpdate(
                "DELETE FROM price_snapshot WHERE asset IN ('zcash','monero','dash')");
            conn.createStatement().executeUpdate(
                "DELETE FROM asset_config WHERE asset IN ('zcash','monero','dash')");
        }
        AssetRegistryTestRefresh.refresh(assetRegistry);
    }

    @Test
    void dispatchReturnsLatestSnapshotForFixtureAsset() {
        pinClock(CAPTURED_NEW.plusSeconds(30));

        ChatToolDispatcher.ToolResult result = toolDispatcher.dispatch(
            "getPrice", Map.of("asset", "zcash"),
            UUID.randomUUID(), "dm", UUID.randomUUID());

        String json = assertSuccess(result);
        assertTrue(json.contains("\"asset\":\"zcash\""), json);
        assertTrue(json.contains("\"name\":\"Zcash\""), json);
        assertTrue(json.contains("\"source\":\"coingecko\""), json);
        assertTrue(json.contains("\"vs_currency\":\"usd\""), json);
        assertTrue(json.contains("\"price\":42.180000000000"), json);
        assertTrue(json.contains("\"volume_24h\":null"), json);
        assertTrue(json.contains("\"high_24h\":43.910000000000"), json);
        assertTrue(json.contains("\"low_24h\":41.070000000000"), json);
        assertTrue(json.contains("\"change_1h_pct\":0.3000"), json);
        assertTrue(json.contains("\"change_24h_pct\":-2.4000"), json);
        assertTrue(json.contains("\"change_7d_pct\":null"), json);
        assertTrue(json.contains("\"captured_at\":\"" + CAPTURED_NEW + "\""), json);
        assertTrue(json.contains("\"age_seconds\":30"), json);
        assertTrue(json.contains("\"stale\":false"), json);
        assertTrue(json.contains("\"source_url\":\"" + ATTRIBUTION + "\""), json);
        assertFalse(json.contains("40.123456789012"),
            "the OLDER row's price must not be served: " + json);
        assertFalse(json.contains("ignored-snapshot-url"),
            "the feed-written price_snapshot.source_url must not be the attribution: " + json);
    }

    @Test
    void staleSnapshotIsServedWithAgeDisclosed() {
        // Clock pinned past the 180s freshness window: the row is stale but
        // MUST still be served, with stale:true and its exact age.
        pinClock(CAPTURED_NEW.plusSeconds(3600));

        ChatToolDispatcher.ToolResult result = toolDispatcher.dispatch(
            "getPrice", Map.of("asset", "monero"),
            UUID.randomUUID(), "dm", UUID.randomUUID());

        String json = assertSuccess(result);
        assertTrue(json.contains("\"price\":180.250000000000"),
            "a stale row is served, never suppressed: " + json);
        assertTrue(json.contains("\"stale\":true"), json);
        assertTrue(json.contains("\"age_seconds\":3600"), json);
        assertTrue(json.contains("\"captured_at\":\"" + CAPTURED_NEW + "\""), json);
    }

    @Test
    void pairWithNoRowReturnsTypedNoDataError() {
        pinClock(CAPTURED_NEW.plusSeconds(30));

        ChatToolDispatcher.ToolResult result = toolDispatcher.dispatch(
            "getPrice", Map.of("asset", "dash"),
            UUID.randomUUID(), "dm", UUID.randomUUID());

        ChatToolDispatcher.ToolResult.ValidationError error =
            assertInstanceOf(ChatToolDispatcher.ToolResult.ValidationError.class, result);
        assertTrue(error.reason().contains("dash"), error.reason());
        assertTrue(error.reason().contains("coingecko"), error.reason());
    }

    // ---------- helpers ----------

    private static void pinClock(Instant now) {
        QuarkusMock.installMockForType(Clock.fixed(now, ZoneOffset.UTC), Clock.class);
    }

    private static String assertSuccess(ChatToolDispatcher.ToolResult result) {
        ChatToolDispatcher.ToolResult.Success success =
            assertInstanceOf(ChatToolDispatcher.ToolResult.Success.class, result);
        return success.content();
    }

    private void insertAssetConfig(Connection conn, String asset, String attributionUrl)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO asset_config (asset, sub_verb, enabled, default_quote_currency, "
                    + "attribution_url, is_default, status) "
                    + "VALUES (?, 'coingecko', true, 'usd', ?, true, 'active')")) {
            ps.setString(1, asset);
            ps.setString(2, attributionUrl);
            ps.executeUpdate();
        }
    }

    private void insertSnapshot(Connection conn, String asset, Instant capturedAt,
                                String price, String volume24h, String high24h,
                                String low24h, String change1hPct, String change24hPct)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO price_snapshot (asset, sub_verb, vs_currency, price, "
                    + "volume_24h, high_24h, low_24h, change_1h_pct, change_24h_pct, "
                    + "captured_at, source_url) "
                    + "VALUES (?, 'coingecko', 'usd', ?, ?, ?, ?, ?, ?, ?, "
                    + "'ignored-snapshot-url.example/')")) {
            ps.setString(1, asset);
            ps.setBigDecimal(2, new java.math.BigDecimal(price));
            setNullableDecimal(ps, 3, volume24h);
            setNullableDecimal(ps, 4, high24h);
            setNullableDecimal(ps, 5, low24h);
            setNullableDecimal(ps, 6, change1hPct);
            setNullableDecimal(ps, 7, change24hPct);
            ps.setTimestamp(8, Timestamp.from(capturedAt));
            ps.executeUpdate();
        }
    }

    private static void setNullableDecimal(PreparedStatement ps, int index, String value)
            throws Exception {
        if (value == null) {
            ps.setNull(index, java.sql.Types.NUMERIC);
        } else {
            ps.setBigDecimal(index, new java.math.BigDecimal(value));
        }
    }
}
