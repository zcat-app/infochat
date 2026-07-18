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
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit test for {@link AssetHandler}. Exercises parsing,
 * sub-verb validation, bare invocation, error paths, and the
 * no-LLM-call invariant. No {@code @QuarkusTest}.
 */
class AssetHandlerTest {

    private static final ScopeRef SCOPE = new ScopeRef.Dm("test-contact-id");

    private AssetRegistry registry;
    private StubSnapshotReader snapshotReader;
    private AssetReplyRenderer renderer;
    private BundleLoader bundleLoader;
    private AssetHandler handler;

    @BeforeEach
    void setUp() {
        bundleLoader = initBundleLoader();

        // Zcash: coingecko (default, enabled), kraken (enabled), bitfinex (enabled)
        AssetRegistry.SubVerbEntry zcashCg = new AssetRegistry.SubVerbEntry(
                "coingecko", true, true, "coingecko.com/en/coins/zcash", "usd");
        AssetRegistry.SubVerbEntry zcashKr = new AssetRegistry.SubVerbEntry(
                "kraken", true, false, "kraken.com/prices/zec-usd", "usd");
        AssetRegistry.SubVerbEntry zcashBf = new AssetRegistry.SubVerbEntry(
                "bitfinex", true, false, "bitfinex.com/t/ZECUSD", "usd");
        AssetRegistry.AssetEntry zcash = new AssetRegistry.AssetEntry(
                "zcash", "Zcash",
                List.of(zcashCg, zcashKr, zcashBf),
                List.of("usd", "eur", "czk", "btc"));

        // Monero: coingecko (default, enabled), kraken (enabled), bitfinex (enabled)
        // binance is NOT in monero's sub-verb set (XMR not listed on Binance)
        AssetRegistry.SubVerbEntry moneroCg = new AssetRegistry.SubVerbEntry(
                "coingecko", true, true, "coingecko.com/en/coins/monero", "usd");
        AssetRegistry.SubVerbEntry moneroKr = new AssetRegistry.SubVerbEntry(
                "kraken", true, false, "kraken.com/prices/xmr-usd", "usd");
        AssetRegistry.SubVerbEntry moneroBf = new AssetRegistry.SubVerbEntry(
                "bitfinex", true, false, "bitfinex.com/t/XMRUSD", "usd");
        AssetRegistry.AssetEntry monero = new AssetRegistry.AssetEntry(
                "monero", "Monero",
                List.of(moneroCg, moneroKr, moneroBf),
                List.of("usd", "eur", "czk", "btc"));

        registry = new AssetRegistry(Map.of("zcash", zcash, "monero", monero));
        snapshotReader = new StubSnapshotReader();
        renderer = new AssetReplyRenderer(bundleLoader);
        handler = new AssetHandler(registry, snapshotReader, renderer, bundleLoader, new InboundContext());
    }

    @Test
    void bareInvocationDefaultSubVerb() {
        snapshotReader.setResult(coingeckoSnapshot("zcash"));
        OutboundMessage reply = handler.handle("zcash", SCOPE, "/zcash");
        assertTrue(reply.text().contains("Zcash (coingecko)"),
                "bare /zcash resolves to the default sub-verb (coingecko)");
        assertTrue(reply.text().contains("$42.18"), "rendered snapshot price");
    }

    @Test
    void bareInvocationAbsentDefault() {
        // Registry with no is_default=true row
        AssetRegistry.SubVerbEntry sv = new AssetRegistry.SubVerbEntry(
                "coingecko", true, false, "coingecko.com", "usd");
        AssetRegistry noDefault = new AssetRegistry(Map.of(
                "zcash", new AssetRegistry.AssetEntry("zcash", "Zcash",
                        List.of(sv), List.of("usd"))));
        AssetHandler h = new AssetHandler(noDefault, snapshotReader, renderer, bundleLoader, new InboundContext());

        OutboundMessage reply = h.handle("zcash", SCOPE, "/zcash");
        assertTrue(reply.text().contains("No default sub-verb"),
                "error.asset.not_configured bundle value");
    }

    @Test
    void bareInvocationDefaultButDisabled() {
        // Default sub-verb exists but enabled=false
        AssetRegistry.SubVerbEntry defaultDisabled = new AssetRegistry.SubVerbEntry(
                "coingecko", false, true, "coingecko.com", "usd");
        AssetRegistry.SubVerbEntry krakenEnabled = new AssetRegistry.SubVerbEntry(
                "kraken", true, false, "kraken.com", "usd");
        AssetRegistry withDisabledDefault = new AssetRegistry(Map.of(
                "zcash", new AssetRegistry.AssetEntry("zcash", "Zcash",
                        List.of(defaultDisabled, krakenEnabled), List.of("usd"))));
        AssetHandler h = new AssetHandler(withDisabledDefault, snapshotReader, renderer, bundleLoader, new InboundContext());

        OutboundMessage reply = h.handle("zcash", SCOPE, "/zcash");
        assertTrue(reply.text().contains("default sub-verb") && reply.text().contains("disabled"),
                "error.asset.default_disabled bundle value");
        assertTrue(reply.text().contains("kraken"), "lists enabled sub-verbs");
    }

    @Test
    void unknownSubVerbFuzzy() {
        OutboundMessage reply = handler.handle("zcash", SCOPE, "/zcash krakn");
        // M1-656: the raw sub-verb is no longer echoed; the bot-authored
        // suggestion and available-list carry the message instead.
        assertFalse(reply.text().contains("krakn"),
                "unknown sub-verb reply must NOT echo the raw token — got: " + reply.text());
        assertTrue(reply.text().contains("Did you mean: kraken"),
                "fuzzy suggestion for closest match");
        assertTrue(reply.text().contains("Available:"),
                "available sub-verbs listed");
    }

    @Test
    void assetErrorRepliesDoNotReflectInboundText() {
        // M1-656: /zcash and friends are admitted during slow-start probation
        // (CommandPermissions:80), making this the widest-reachable of the
        // reflecting surfaces. Neither the sub-verb nor the --vs value is
        // charset-validated at parse (AssetHandler.parseArgs only lowercases).
        OutboundMessage subVerb = handler.handle("zcash", SCOPE, "/zcash /grant-admin");
        assertFalse(subVerb.text().contains("grant-admin"),
                "unknown sub-verb reply must not reflect inbound text — got: " + subVerb.text());

        OutboundMessage currency = handler.handle("zcash", SCOPE, "/zcash --vs /grant-admin");
        assertFalse(currency.text().contains("grant-admin"),
                "unsupported-currency reply must not reflect inbound text — got: " + currency.text());
    }

    @Test
    void subVerbNotEnabledForAsset() {
        // binance exists as a sub-verb for monero but with enabled=false
        // (XMR not listed on Binance per spec §Asset commands)
        AssetRegistry.SubVerbEntry moneroCg = new AssetRegistry.SubVerbEntry(
                "coingecko", true, true, "coingecko.com/en/coins/monero", "usd");
        AssetRegistry.SubVerbEntry moneroBinance = new AssetRegistry.SubVerbEntry(
                "binance", false, false, "binance.com", "usd");
        AssetRegistry withBinance = new AssetRegistry(Map.of(
                "monero", new AssetRegistry.AssetEntry("monero", "Monero",
                        List.of(moneroCg, moneroBinance), List.of("usd"))));
        AssetHandler h = new AssetHandler(withBinance, snapshotReader, renderer, bundleLoader, new InboundContext());

        OutboundMessage reply = h.handle("monero", SCOPE, "/monero binance");
        assertTrue(reply.text().contains("not enabled"),
                "error.asset.sub_verb_not_enabled bundle value");
        assertTrue(reply.text().contains("coingecko"),
                "lists enabled sub-verbs");
    }

    @Test
    void unsupportedQuoteCurrency() {
        snapshotReader.setResult(coingeckoSnapshot("zcash"));
        OutboundMessage reply = handler.handle("zcash", SCOPE, "/zcash --vs jpy");
        // M1-656: the raw currency token is no longer echoed; the "not enabled"
        // framing and the bot-authored available-list are retained.
        assertFalse(reply.text().contains("jpy"),
                "unsupported-currency reply must NOT echo the raw token — got: " + reply.text());
        assertTrue(reply.text().contains("not enabled"),
                "unsupported quote currency error");
        assertTrue(reply.text().contains("Did you mean: "),
                "fuzzy suggestion for quote currency");
        assertTrue(reply.text().contains("Available:"),
                "available currencies listed");
    }

    @Test
    void valuelessVsFlagRepliesWithUsage() {
        // A trailing --vs with no currency value must not be silently
        // dropped; the handler replies with the usage message instead.
        OutboundMessage reply = handler.handle("zcash", SCOPE, "/zcash --vs");
        assertTrue(reply.text().contains("Usage:"),
                "value-less --vs yields the usage message; got: " + reply.text());
        assertTrue(reply.text().contains("--vs"),
                "usage message names the --vs flag; got: " + reply.text());
    }

    @Test
    void noLlmCall() {
        // The handler path makes ZERO LLM calls. This test verifies
        // the handler completes a happy-path /zcash invocation without
        // touching any LLM SPI. Since the handler does not inject any
        // LLM-related bean (no TranslationProvider, no LlmAdapter, no
        // ChatDispatcher), a successful invocation is proof of zero
        // LLM calls — if an LLM dependency were introduced, the test
        // constructor would fail to compile (missing parameter).
        snapshotReader.setResult(coingeckoSnapshot("zcash"));
        OutboundMessage reply = handler.handle("zcash", SCOPE, "/zcash");
        assertTrue(reply.text().contains("Zcash (coingecko)"),
                "happy path completes with no LLM call");
    }

    @Test
    void explicitSubVerbAndVsCurrency() {
        snapshotReader.setResult(krakenSnapshot("zcash"));
        OutboundMessage reply = handler.handle("zcash", SCOPE, "/zcash kraken --vs eur");
        assertTrue(reply.text().contains("Zcash (kraken)"), "explicit sub-verb used");
    }

    @Test
    void noSnapshotReturnsNoDataError() {
        snapshotReader.setResult(null);
        OutboundMessage reply = handler.handle("zcash", SCOPE, "/zcash");
        assertTrue(reply.text().contains("No price data"),
                "error.asset.no_data bundle value");
    }

    /**
     * Acceptance item 3: argument tokens are lowercased with
     * {@link java.util.Locale#ROOT}, not the JVM default locale. Under a
     * Turkish default locale {@code "I".toLowerCase()} yields the dotless
     * {@code 'ı'} (U+0131) while ROOT yields ASCII {@code 'i'}; parseArgs must
     * produce ASCII-lowercased tokens regardless of the default locale so
     * sub-verb and quote-currency matching is locale-independent.
     */
    @Test
    void parseArgsLowercasesWithRootLocaleNotDefault() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            AssetHandler.ParsedArgs args = AssetHandler.parseArgs("/zcash BINANCE --vs XIB");
            assertEquals("binance", args.subVerb(),
                    "sub-verb token lowercased via Locale.ROOT (ASCII 'i'), not Turkish 'ı'");
            assertEquals("xib", args.vsCurrency(),
                    "quote-currency token lowercased via Locale.ROOT (ASCII 'i'), not Turkish 'ı'");
        } finally {
            Locale.setDefault(previous);
        }
    }

    // --- Test doubles ---

    private static AssetSnapshotReader.SnapshotResult coingeckoSnapshot(String asset) {
        AssetSnapshotReader.Snapshot snap = new AssetSnapshotReader.Snapshot(
                asset, "coingecko", "usd",
                new BigDecimal("42.18"),
                new BigDecimal("12345678"),
                new BigDecimal("43.91"),
                new BigDecimal("41.07"),
                new BigDecimal("0.3"),
                new BigDecimal("-2.4"),
                new BigDecimal("5.1"),
                Instant.now().minusSeconds(41),
                "coingecko.com/en/coins/zcash"
        );
        return new AssetSnapshotReader.SnapshotResult(snap, false, Duration.ofSeconds(90));
    }

    private static AssetSnapshotReader.SnapshotResult krakenSnapshot(String asset) {
        AssetSnapshotReader.Snapshot snap = new AssetSnapshotReader.Snapshot(
                asset, "kraken", "eur",
                new BigDecimal("38.50"),
                null, new BigDecimal("39.80"), new BigDecimal("37.10"),
                null, null, null,
                Instant.now().minusSeconds(30),
                "kraken.com/prices/zec-eur"
        );
        return new AssetSnapshotReader.SnapshotResult(snap, false, Duration.ofSeconds(90));
    }

    private static BundleLoader initBundleLoader() {
        BundleLoader bl = new BundleLoader();
        try {
            var method = BundleLoader.class.getDeclaredMethod("load");
            method.setAccessible(true);
            method.invoke(bl);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize BundleLoader for test", e);
        }
        return bl;
    }

    /**
     * Stub snapshot reader that returns a fixed result regardless
     * of input parameters. Package-private for test use.
     */
    static class StubSnapshotReader extends AssetSnapshotReader {
        private AssetSnapshotReader.SnapshotResult result;

        StubSnapshotReader() {
            // no-arg: bypasses CDI DataSource injection
        }

        void setResult(AssetSnapshotReader.SnapshotResult result) {
            this.result = result;
        }

        @Override
        public SnapshotResult readLatest(String asset, String subVerb, String vsCurrency) {
            return result;
        }
    }
}
