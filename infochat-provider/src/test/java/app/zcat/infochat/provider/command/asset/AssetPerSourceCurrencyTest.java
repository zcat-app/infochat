package app.zcat.infochat.provider.command.asset;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-671: {@code --vs} must be validated against what each (asset, sub_verb)
 * pair actually FETCHES, not against what its upstream is CAPABLE of quoting.
 *
 * <p>The collector fetches exactly {@code asset_config.default_quote_currency}
 * for each pair and the provider has no on-demand fetch, so a currency outside
 * that single configured value can never have a {@code price_snapshot} row.
 * Validating against a capability list — the upstream's, or the per-asset
 * quote-currency allowlist the handler used to check — therefore only defers
 * the failure to a misleading "the fetcher may not have run" reply. That
 * allowlist is gone as of this ticket: it had no reader left once availability
 * became the test, and it advertised currencies no deployment could serve.</p>
 *
 * <p>The fixture mirrors the shipped {@code bootstrap-assets.json}: every pair
 * is configured for {@code usd}, while {@code czk} — the currency the old
 * asset-level list advertised and CoinGecko genuinely quotes — is served by
 * nothing.</p>
 */
class AssetPerSourceCurrencyTest {

    private static final ScopeRef SCOPE = new ScopeRef.Dm("test-contact-id");

    private AssetHandlerTest.StubSnapshotReader snapshotReader;
    private AssetHandler handler;

    @BeforeEach
    void setUp() {
        BundleLoader bundleLoader = AssetHandlerTest.initBundleLoader();

        AssetRegistry.AssetEntry zcash = new AssetRegistry.AssetEntry(
                "zcash", "Zcash",
                List.of(
                        new AssetRegistry.SubVerbEntry(
                                "coingecko", true, true, "coingecko.com/en/coins/zcash", "usd"),
                        new AssetRegistry.SubVerbEntry(
                                "kraken", true, false, "kraken.com/prices/zec-usd", "usd"),
                        new AssetRegistry.SubVerbEntry(
                                "bitfinex", true, false, "bitfinex.com/t/ZECUSD", "usd")));

        AssetRegistry.AssetEntry monero = new AssetRegistry.AssetEntry(
                "monero", "Monero",
                List.of(
                        new AssetRegistry.SubVerbEntry(
                                "coingecko", true, true, "coingecko.com/en/coins/monero", "usd"),
                        new AssetRegistry.SubVerbEntry(
                                "kraken", true, false, "kraken.com/prices/xmr-usd", "usd"),
                        new AssetRegistry.SubVerbEntry(
                                "bitfinex", true, false, "bitfinex.com/t/XMRUSD", "usd")));

        AssetRegistry registry = new AssetRegistry(Map.of("zcash", zcash, "monero", monero));
        snapshotReader = new AssetHandlerTest.StubSnapshotReader();
        handler = new AssetHandler(registry, snapshotReader,
                new AssetReplyRenderer(bundleLoader), bundleLoader, new InboundContext());
    }

    /**
     * The case a per-SOURCE capability check would let through: {@code czk} is
     * in {@code CoingeckoSnapshotSource.SUPPORTED_VS}, so a check against the
     * upstream's capability would accept it — yet the coingecko pair is
     * configured for {@code usd}, so no czk row can exist and accepting it only
     * defers the misleading no-data reply. The stub returns no snapshot so a
     * failure to reject surfaces as exactly that reply.
     */
    @Test
    void rejectsSupportedByUpstreamButNeverFetched() {
        snapshotReader.setResult(null);
        for (String asset : List.of("zcash", "monero")) {
            OutboundMessage reply = handler.handle(asset, SCOPE, "/" + asset + " coingecko --vs czk");
            assertFalse(reply.text().contains("No price data"),
                    "/" + asset + " coingecko --vs czk must not reach the no-data reply — got: "
                            + reply.text());
            assertTrue(reply.text().contains("not enabled"),
                    "/" + asset + " coingecko --vs czk is refused at the command boundary — got: "
                            + reply.text());
            assertTrue(reply.text().contains("Available: usd"),
                    "names the currency the coingecko pair actually serves — got: " + reply.text());
        }
    }

    /** The pair's own configured currency still resolves end-to-end — no false rejection. */
    @Test
    void servesTheCurrencyThePairActuallyFetches() {
        snapshotReader.setResult(snapshot("zcash", "bitfinex", "usd", "bitfinex.com/t/ZECUSD"));
        assertTrue(handler.handle("zcash", SCOPE, "/zcash bitfinex --vs usd").text()
                        .contains("Zcash (bitfinex)"),
                "/zcash bitfinex --vs usd renders a price card");

        snapshotReader.setResult(snapshot("monero", "kraken", "usd", "kraken.com/prices/xmr-usd"));
        assertTrue(handler.handle("monero", SCOPE, "/monero kraken --vs usd").text()
                        .contains("Monero (kraken)"),
                "/monero kraken --vs usd renders a price card");

        snapshotReader.setResult(snapshot("zcash", "coingecko", "usd", "coingecko.com/en/coins/zcash"));
        assertTrue(handler.handle("zcash", SCOPE, "/zcash").text().contains("Zcash (coingecko)"),
                "bare /zcash (default sub-verb, no --vs) renders a price card");
    }

    /**
     * The no-data reply keeps its one legitimate meaning: an AVAILABLE currency
     * with no row yet (genuine cold start), never a currency mismatch.
     */
    @Test
    void noDataReplyReservedForGenuineColdStart() {
        snapshotReader.setResult(null);
        OutboundMessage reply = handler.handle("zcash", SCOPE, "/zcash bitfinex --vs usd");
        assertTrue(reply.text().contains("No price data"),
                "a missing row on the pair's own currency still yields the no-data reply — got: "
                        + reply.text());
    }

    private static AssetSnapshotReader.SnapshotResult snapshot(String asset,
                                                               String subVerb,
                                                               String vsCurrency,
                                                               String attributionUrl) {
        AssetSnapshotReader.Snapshot snap = new AssetSnapshotReader.Snapshot(
                asset, subVerb, vsCurrency,
                new BigDecimal("42.18"),
                new BigDecimal("12345678"),
                new BigDecimal("43.91"),
                new BigDecimal("41.07"),
                new BigDecimal("0.3"),
                new BigDecimal("-2.4"),
                new BigDecimal("5.1"),
                Instant.now().minusSeconds(41),
                attributionUrl);
        return new AssetSnapshotReader.SnapshotResult(snap, false, Duration.ofSeconds(90));
    }
}
