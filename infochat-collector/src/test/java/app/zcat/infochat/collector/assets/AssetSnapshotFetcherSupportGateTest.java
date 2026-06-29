package app.zcat.infochat.collector.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import app.zcat.infochat.collector.assets.source.AssetDataSource.FetchException;
import app.zcat.infochat.collector.assets.source.KrakenSnapshotSource;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Pins the SPI supported-asset / supported-quote gate (M1-484 / F2):
 * {@link AssetSnapshotFetcher#runHostTick} must consult each
 * {@code AssetDataSource}'s supported sets BEFORE calling
 * {@code fetchSnapshot}, so an asset/quote the source does not support is
 * rejected as an operator-config mismatch rather than misrouted through
 * the D42 upstream-health ladder (which would wrongly degrade a healthy
 * source).
 *
 * <p>The Kraken source is swapped for a {@link FakeKraken} that counts
 * {@code fetchSnapshot} invocations. Crucially the fake inherits the REAL
 * {@code supportedAssets} / {@code supportedQuoteCurrencies} (Kraken
 * supports {@code usd/eur/btc}, never {@code czk}), so the gate decision
 * under test is the production one. The call counter is the direct witness
 * of whether the gate routed the pair onward; the D42 state columns
 * corroborate that a rejected pair never touched the health ladder.
 */
@QuarkusTest
class AssetSnapshotFetcherSupportGateTest {

    private static final AtomicInteger KRAKEN_CALLS = new AtomicInteger();

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    AssetSnapshotFetcher fetcher;

    @BeforeEach
    void reset() throws Exception {
        KRAKEN_CALLS.set(0);
        QuarkusMock.installMockForType(new FakeKraken(), KrakenSnapshotSource.class);
        truncate("asset_config");
        truncate("price_snapshot");
        truncate("admin_notification_state");
        // Drop any cached source map so resolveSource rebuilds it against
        // the mock installed above (matches AssetSnapshotFetcherTest).
        ClientProxy.unwrap(fetcher).resetSourceCacheForTest();
    }

    @Test
    void unsupportedQuoteRejectedAtGateNotRoutedToFetchOrHealthLadder() throws Exception {
        // Kraken does not support czk; a misconfigured asset_config row
        // names it as the default quote currency.
        seedAssetConfig("zcash", "kraken", "czk");

        fetcher.runHostTick("kraken");

        assertEquals(0, KRAKEN_CALLS.get(),
            "an unsupported asset/quote must be rejected at the gate and never "
                + "reach fetchSnapshot");
        assertEquals(0, countSnapshots("zcash", "kraken"),
            "a gate-rejected pair must not write a price_snapshot row");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT consecutive_failures, last_failure_at, last_success_at, status "
                     + "FROM asset_config WHERE asset = 'zcash' AND sub_verb = 'kraken'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "the seeded asset_config row must still exist");
            assertEquals(0, rs.getInt("consecutive_failures"),
                "gate rejection must NOT engage the D42 ladder — a misrouted "
                    + "fetch failure would bump consecutive_failures to 1");
            assertNull(rs.getTimestamp("last_failure_at"),
                "gate rejection must not stamp last_failure_at");
            assertNull(rs.getTimestamp("last_success_at"),
                "gate rejection must not stamp last_success_at");
            assertEquals("active", rs.getString("status"),
                "a healthy source must stay 'active' on a config mismatch");
        }
    }

    @Test
    void supportedPairPassesGateAndReachesFetch() throws Exception {
        // zcash/usd IS supported by Kraken, so the gate must let it through
        // to fetchSnapshot — the complement that proves the gate does not
        // reject everything.
        seedAssetConfig("zcash", "kraken", "usd");

        fetcher.runHostTick("kraken");

        assertEquals(1, KRAKEN_CALLS.get(),
            "a supported asset/quote must pass the gate and reach fetchSnapshot");
        assertEquals(1, countSnapshots("zcash", "kraken"),
            "the snapshot returned by the supported fetch must be stored");
    }

    private void seedAssetConfig(String asset, String subVerb, String defaultQuote)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO asset_config ("
                     + "  asset, sub_verb, enabled, default_quote_currency,"
                     + "  attribution_url, is_default, status"
                     + ") VALUES (?, ?, true, ?, ?, true, 'active')")) {
            ps.setString(1, asset);
            ps.setString(2, subVerb);
            ps.setString(3, defaultQuote);
            ps.setString(4, "https://example.test/" + subVerb + "/" + asset);
            ps.executeUpdate();
        }
    }

    private int countSnapshots(String asset, String subVerb) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count(*) FROM price_snapshot WHERE asset = ? AND sub_verb = ?")) {
            ps.setString(1, asset);
            ps.setString(2, subVerb);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private void truncate(String table) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("TRUNCATE " + table);
        }
    }

    // Subclass of the concrete Kraken bean so QuarkusMock can install it
    // for that exact type. fetchSnapshot is counted and returns a fixed
    // snapshot; supportedAssets / supportedQuoteCurrencies are inherited
    // unchanged so the gate exercises Kraken's real support sets.
    static final class FakeKraken extends KrakenSnapshotSource {
        @Override
        public PriceSnapshot fetchSnapshot(String asset, String vs) throws FetchException {
            KRAKEN_CALLS.incrementAndGet();
            return new PriceSnapshot(
                asset, "kraken", vs, new BigDecimal("100.0"),
                null, null, null, null, null, null,
                Instant.parse("2026-05-15T10:00:00Z"),
                "https://example.test/kraken/" + asset,
                null);
        }
    }
}
