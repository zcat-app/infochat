package app.zcat.infochat.provider.command.asset;

import app.zcat.infochat.provider.command.AssetCommandFamilyOracle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit test for the swapped {@link AssetCommandFamilyOracle}.
 * Exercises the registry-driven verdict with a hand-constructed
 * {@link AssetRegistry}. Plain JUnit per M1-049 test pyramid.
 */
class AssetCommandFamilyOracleTest {

    private static AssetRegistry registryWith(String asset, boolean enabled) {
        AssetRegistry.SubVerbEntry sv = new AssetRegistry.SubVerbEntry(
                "coingecko", enabled, true, "https://coingecko.com", "usd");
        AssetRegistry.AssetEntry entry = new AssetRegistry.AssetEntry(
                asset, capitalize(asset), List.of(sv), List.of("usd"));
        return new AssetRegistry(Map.of(asset, entry));
    }

    private static AssetRegistry registryWithMultiple() {
        AssetRegistry.SubVerbEntry zcashSv = new AssetRegistry.SubVerbEntry(
                "coingecko", true, true, "https://coingecko.com", "usd");
        AssetRegistry.AssetEntry zcash = new AssetRegistry.AssetEntry(
                "zcash", "Zcash", List.of(zcashSv), List.of("usd"));

        AssetRegistry.SubVerbEntry moneroSv = new AssetRegistry.SubVerbEntry(
                "coingecko", false, true, "https://coingecko.com", "usd");
        AssetRegistry.AssetEntry monero = new AssetRegistry.AssetEntry(
                "monero", "Monero", List.of(moneroSv), List.of("usd"));

        return new AssetRegistry(Map.of("zcash", zcash, "monero", monero));
    }

    @Test
    void enabledAssetReturnsTrue() {
        AssetCommandFamilyOracle oracle = new AssetCommandFamilyOracle(registryWith("zcash", true));
        assertTrue(oracle.isAssetCommand("zcash"));
    }

    @Test
    void disabledAssetReturnsFalse() {
        // All sub-verbs disabled → asset is not "enabled"
        AssetCommandFamilyOracle oracle = new AssetCommandFamilyOracle(registryWith("zcash", false));
        assertFalse(oracle.isAssetCommand("zcash"));
    }

    @Test
    void unknownAssetReturnsFalse() {
        AssetCommandFamilyOracle oracle = new AssetCommandFamilyOracle(registryWith("zcash", true));
        assertFalse(oracle.isAssetCommand("bitcoin"));
    }

    @Test
    void caseSensitiveMatch() {
        AssetCommandFamilyOracle oracle = new AssetCommandFamilyOracle(registryWith("zcash", true));
        assertFalse(oracle.isAssetCommand("Zcash"),
                "oracle must be case-sensitive — 'Zcash' != 'zcash'");
        assertFalse(oracle.isAssetCommand("ZCASH"));
    }

    @Test
    void noArgConstructorReturnsFalseForAll() {
        AssetCommandFamilyOracle oracle = new AssetCommandFamilyOracle();
        assertFalse(oracle.isAssetCommand("zcash"));
        assertFalse(oracle.isAssetCommand("monero"));
        assertFalse(oracle.isAssetCommand(""));
    }

    @Test
    void multipleAssetsEnabledAndDisabled() {
        AssetCommandFamilyOracle oracle = new AssetCommandFamilyOracle(registryWithMultiple());
        assertTrue(oracle.isAssetCommand("zcash"), "zcash has an enabled sub-verb");
        assertFalse(oracle.isAssetCommand("monero"), "monero's only sub-verb is disabled");
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
