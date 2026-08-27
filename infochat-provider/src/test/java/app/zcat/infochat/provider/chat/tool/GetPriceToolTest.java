package app.zcat.infochat.provider.chat.tool;

import app.zcat.infochat.provider.command.asset.AssetRegistry;
import app.zcat.infochat.provider.command.asset.AssetSnapshotReader;
import org.jspecify.annotations.Nullable;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Resolution failure modes of getPrice (plain JUnit, no container): every bad input returns a typed self-correctable IllegalArgumentException enumerating the enabled set, so the model recovers within the turn. The registry seam overrides the two public lookups (the map constructor is package-private to command.asset); the reader is stubbed at readLatest. */
class GetPriceToolTest {

    private static final UUID USER = UUID.randomUUID();

    private static AssetRegistry.SubVerbEntry subVerb(String name, boolean enabled,
                                                      boolean isDefault) {
        return new AssetRegistry.SubVerbEntry(
            name, enabled, isDefault, "https://example/attr", "usd");
    }

    private static AssetRegistry registry(Map<String, AssetRegistry.AssetEntry> assets) {
        return new AssetRegistry() {
            @Override
            public @Nullable AssetEntry getAsset(String name) {
                return assets.get(name);
            }

            @Override
            public Set<String> getEnabledAssetNames() {
                Set<String> names = new java.util.LinkedHashSet<>();
                for (Map.Entry<String, AssetEntry> e : assets.entrySet()) {
                    if (!e.getValue().enabledSubVerbNames().isEmpty()) {
                        names.add(e.getKey());
                    }
                }
                return names;
            }
        };
    }

    private static GetPriceTool tool(AssetRegistry registry) {
        return new GetPriceTool(registry, new AssetSnapshotReader() {
            @Override
            public @Nullable SnapshotResult readLatest(String asset, String subVerb,
                                                       String vsCurrency) {
                throw new AssertionError("resolution failures must not reach the reader");
            }
        }, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    @Test
    void unknownAssetErrorListsEnabledAssets() {
        Map<String, AssetRegistry.AssetEntry> assets = new LinkedHashMap<>();
        assets.put("zcash", new AssetRegistry.AssetEntry(
            "zcash", "Zcash", List.of(subVerb("coingecko", true, true))));
        assets.put("monero", new AssetRegistry.AssetEntry(
            "monero", "Monero", List.of(subVerb("coingecko", true, true))));
        GetPriceTool getPrice = tool(registry(assets));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> getPrice.execute(USER, "dm", USER, Map.of("asset", "xmr")));

        assertTrue(e.getMessage().contains("zcash"), e.getMessage());
        assertTrue(e.getMessage().contains("monero"), e.getMessage());
    }

    @Test
    void unsupportedQuoteCurrencyNamesTheOnlyAvailableCurrency() {
        Map<String, AssetRegistry.AssetEntry> assets = new LinkedHashMap<>();
        assets.put("zcash", new AssetRegistry.AssetEntry(
            "zcash", "Zcash", List.of(subVerb("coingecko", true, true))));
        GetPriceTool getPrice = tool(registry(assets));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> getPrice.execute(USER, "dm", USER,
                Map.of("asset", "zcash", "vs_currency", "eur")));

        assertTrue(e.getMessage().contains("usd"), e.getMessage());
    }

    @Test
    void defaultPairAbsentOrDisabledListsEnabledSubVerbs() {
        // Default disabled, another sub-verb enabled.
        Map<String, AssetRegistry.AssetEntry> assets = new LinkedHashMap<>();
        assets.put("zcash", new AssetRegistry.AssetEntry(
            "zcash", "Zcash", List.of(
                subVerb("coingecko", false, true),
                subVerb("kraken", true, false))));
        IllegalArgumentException disabled = assertThrows(IllegalArgumentException.class,
            () -> tool(registry(assets)).execute(USER, "dm", USER, Map.of("asset", "zcash")));
        assertTrue(disabled.getMessage().contains("kraken"), disabled.getMessage());

        // No default at all.
        Map<String, AssetRegistry.AssetEntry> noDefault = new LinkedHashMap<>();
        noDefault.put("monero", new AssetRegistry.AssetEntry(
            "monero", "Monero", List.of(subVerb("coingecko", true, false))));
        IllegalArgumentException absent = assertThrows(IllegalArgumentException.class,
            () -> tool(registry(noDefault)).execute(USER, "dm", USER, Map.of("asset", "monero")));
        assertTrue(absent.getMessage().contains("coingecko"), absent.getMessage());
    }
}
