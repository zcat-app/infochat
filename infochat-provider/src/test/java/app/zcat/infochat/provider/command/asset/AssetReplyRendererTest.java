package app.zcat.infochat.provider.command.asset;

import app.zcat.infochat.provider.bundle.BundleLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit test for {@link AssetReplyRenderer}. Exercises the
 * reply layout shapes for coingecko (full fields), exchange
 * (asymmetric fields), and stale marker. No {@code @QuarkusTest}.
 */
class AssetReplyRendererTest {

    private AssetReplyRenderer renderer;

    @BeforeEach
    void setUp() {
        BundleLoader bundleLoader = new BundleLoader();
        // Trigger PostConstruct manually to load bundles
        try {
            var method = BundleLoader.class.getDeclaredMethod("load");
            method.setAccessible(true);
            method.invoke(bundleLoader);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize BundleLoader for test", e);
        }
        renderer = new AssetReplyRenderer(bundleLoader);
    }

    @Test
    void coingeckoLayout() {
        AssetSnapshotReader.Snapshot snap = new AssetSnapshotReader.Snapshot(
                "zcash", "coingecko", "usd",
                new BigDecimal("42.18"),
                new BigDecimal("12345678.50"),
                new BigDecimal("43.91"),
                new BigDecimal("41.07"),
                new BigDecimal("0.3"),
                new BigDecimal("-2.4"),
                new BigDecimal("5.1"),
                Instant.now().minusSeconds(41),
                "coingecko.com/en/coins/zcash"
        );
        Duration interval = Duration.ofSeconds(90);
        AssetSnapshotReader.SnapshotResult result =
                new AssetSnapshotReader.SnapshotResult(snap, false, interval);

        String rendered = renderer.render(result, "Zcash", "coingecko.com/en/coins/zcash", "en");

        assertTrue(rendered.contains("Zcash (coingecko)"), "header: display name + source");
        assertTrue(rendered.contains("$42.18"), "price line");
        assertTrue(rendered.contains("1h:"), "1h delta present for coingecko");
        assertTrue(rendered.contains("24h:"), "24h delta present for coingecko");
        assertTrue(rendered.contains("high $43.91"), "24h high");
        assertTrue(rendered.contains("low $41.07"), "24h low");
        assertTrue(rendered.contains("cached"), "cache age line");
        assertTrue(rendered.contains("coingecko.com/en/coins/zcash"), "attribution URL bare");
        assertTrue(rendered.contains("source:"), "source label");
        // No markdown link syntax per D30
        assertFalse(rendered.matches("(?s).*\\[.*\\]\\(http.*"),
                "reply must not contain markdown link syntax");
    }

    @Test
    void exchangeAsymmetricFields() {
        // Kraken/Bitfinex snapshots omit delta fields
        AssetSnapshotReader.Snapshot snap = new AssetSnapshotReader.Snapshot(
                "zcash", "kraken", "usd",
                new BigDecimal("42.15"),
                new BigDecimal("9876543.20"),
                new BigDecimal("43.88"),
                new BigDecimal("41.02"),
                null,  // no change_1h_pct
                null,  // no change_24h_pct
                null,  // no change_7d_pct
                Instant.now().minusSeconds(38),
                "kraken.com/prices/zec-usd-zcash-price-chart"
        );
        Duration interval = Duration.ofSeconds(90);
        AssetSnapshotReader.SnapshotResult result =
                new AssetSnapshotReader.SnapshotResult(snap, false, interval);

        String rendered = renderer.render(result, "Zcash", "kraken.com/prices/zec-usd-zcash-price-chart", "en");

        assertTrue(rendered.contains("Zcash (kraken)"), "header");
        assertTrue(rendered.contains("$42.15"), "price line");
        // Delta lines should be absent (exchange sources don't provide them)
        assertFalse(rendered.contains("1h:"), "1h delta must be absent for exchanges");
        // Spread line should be present without delta percentage
        assertTrue(rendered.contains("high $43.88"), "24h high in spread");
        assertTrue(rendered.contains("low $41.02"), "24h low in spread");
        assertTrue(rendered.contains("kraken.com/prices/zec-usd-zcash-price-chart"), "attribution URL");
    }

    @Test
    void staleMarker() {
        // Snapshot older than 2 * refresh_interval → stale marker fires
        Duration interval = Duration.ofSeconds(90);
        AssetSnapshotReader.Snapshot snap = new AssetSnapshotReader.Snapshot(
                "zcash", "coingecko", "usd",
                new BigDecimal("42.18"),
                null, null, null, null, null, null,
                Instant.now().minus(interval.multipliedBy(5)),
                "coingecko.com/en/coins/zcash"
        );
        AssetSnapshotReader.SnapshotResult result =
                new AssetSnapshotReader.SnapshotResult(snap, true, interval);

        String rendered = renderer.render(result, "Zcash", "coingecko.com/en/coins/zcash", "en");

        assertTrue(rendered.contains("⚠ stale"),
                "stale marker must appear when captured_at > 2 * refresh_interval");
        assertTrue(rendered.contains("Zcash (coingecko)"), "header still present");
    }

    @Test
    void nonStaleDoesNotShowMarker() {
        AssetSnapshotReader.Snapshot snap = new AssetSnapshotReader.Snapshot(
                "zcash", "coingecko", "usd",
                new BigDecimal("42.18"),
                null, null, null, null, null, null,
                Instant.now().minusSeconds(10),
                "coingecko.com/en/coins/zcash"
        );
        AssetSnapshotReader.SnapshotResult result =
                new AssetSnapshotReader.SnapshotResult(snap, false, Duration.ofSeconds(90));

        String rendered = renderer.render(result, "Zcash", "coingecko.com/en/coins/zcash", "en");

        assertFalse(rendered.contains("⚠ stale"), "fresh snapshot should not show stale marker");
    }

    @Test
    void nonUsdVsCurrenciesRenderIsoCodeSuffix() {
        AssetSnapshotReader.Snapshot eurSnap = new AssetSnapshotReader.Snapshot(
                "zcash", "coingecko", "eur",
                new BigDecimal("38.74"),
                null, null, null, null, null, null,
                Instant.now().minusSeconds(10),
                "coingecko.com/en/coins/zcash"
        );
        AssetSnapshotReader.Snapshot czkSnap = new AssetSnapshotReader.Snapshot(
                "zcash", "coingecko", "czk",
                new BigDecimal("961.30"),
                null, null, null, null, null, null,
                Instant.now().minusSeconds(10),
                "coingecko.com/en/coins/zcash"
        );
        Duration interval = Duration.ofSeconds(90);

        String eurRendered = renderer.render(
                new AssetSnapshotReader.SnapshotResult(eurSnap, false, interval),
                "Zcash", "coingecko.com/en/coins/zcash", "en");
        String czkRendered = renderer.render(
                new AssetSnapshotReader.SnapshotResult(czkSnap, false, interval),
                "Zcash", "coingecko.com/en/coins/zcash", "en");

        assertTrue(eurRendered.contains("38.74 EUR"), "eur price gets ISO-code suffix");
        assertFalse(eurRendered.contains("$38.74"), "eur price must not have $ prefix");
        assertTrue(czkRendered.contains("961.3 CZK"), "czk price gets ISO-code suffix");
        assertFalse(czkRendered.contains("$961.3"), "czk price must not have $ prefix");
    }

    @Test
    void btcQuoteCurrencyOmitsDollarSign() {
        AssetSnapshotReader.Snapshot snap = new AssetSnapshotReader.Snapshot(
                "zcash", "coingecko", "btc",
                new BigDecimal("0.000651"),
                null, null, null, null, null, null,
                Instant.now().minusSeconds(10),
                "coingecko.com/en/coins/zcash"
        );
        AssetSnapshotReader.SnapshotResult result =
                new AssetSnapshotReader.SnapshotResult(snap, false, Duration.ofSeconds(90));

        String rendered = renderer.render(result, "Zcash", "coingecko.com/en/coins/zcash", "en");

        assertTrue(rendered.contains("0.000651 BTC"), "BTC price format");
        assertFalse(rendered.contains("$0.000651"), "BTC price should not have $ prefix");
    }
}
