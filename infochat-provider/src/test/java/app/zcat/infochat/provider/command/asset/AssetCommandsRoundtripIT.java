package app.zcat.infochat.provider.command.asset;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.command.AssetCommandFamilyOracle;
import app.zcat.infochat.provider.testing.TestLlmProvider;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-cutting roundtrip IT for the asset-commands vertical.
 * Verifies the full path from {@code asset_config} + {@code price_snapshot}
 * DB state through Provider-side command dispatch to rendered reply, exercising
 * the probation carve-out and ban short-circuit interactions end-to-end.
 *
 * <p>Seeding is JDBC-driven (the Collector-side {@code BootstrapAssetsLoader}
 * and {@code PriceSnapshotStore} live in {@code infochat-collector}, which
 * the Provider module does not depend on). The IT verifies the schema contracts
 * those components write against and the Provider-side read path that consumes
 * them. The {@code infochat.bootstrap.assets-file} config property points
 * {@link AssetRegistry} at the test fixture for display-name + supported-vs
 * metadata.</p>
 */
@QuarkusTest
@TestProfile(AssetCommandsRoundtripIT.Profile.class)
class AssetCommandsRoundtripIT {

    private static final String ADAPTER = "inmemory";
    private static final BigDecimal ZCASH_PRICE = new BigDecimal("42.18");
    private static final String ZCASH_SOURCE_URL = "coingecko.com/en/coins/zcash";
    /**
     * Fixed snapshot capture instant inside the migration-provisioned May 2026
     * partition (M1-740: a wall-clock {@code captured_at} breaks on every
     * unprovisioned month boundary). The injected Clock is pinned 30s after it,
     * so the seeded snapshot reads FRESH — the same staleness verdict a
     * wall-clock {@code now().minusSeconds(30)} seed produced.
     */
    private static final Instant CAPTURED_AT = Instant.parse("2026-05-22T12:00:00Z");

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true",
                    "infochat.bootstrap.assets-file", "src/test/resources/bootstrap-assets-it.json"
            );
        }
    }

    @Inject InMemoryAdapter adapter;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject AssetRegistry assetRegistry;
    @Inject AssetCommandFamilyOracle assetCommandFamilyOracle;
    @Inject TestLlmProvider mockLlm;
    @Inject BundleLoader bundleLoader;

    @BeforeEach
    void setUp() throws Exception {
        adapter.reset();
        mockLlm.reset();
        mockLlm.setThrowOnCall(true);
        // AssetSnapshotReader's staleness verdict reads the injected Clock —
        // pin it 30s past CAPTURED_AT so the seeded snapshot is fresh.
        QuarkusMock.installMockForType(
                Clock.fixed(CAPTURED_AT.plusSeconds(30), ZoneOffset.UTC), Clock.class);

        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().executeUpdate(
                    "DELETE FROM audit_log WHERE actor_contact_id LIKE 'm55-%'");
            conn.createStatement().executeUpdate(
                    "DELETE FROM price_snapshot WHERE asset IN ('zcash', 'monero', 'litecoin')");
            conn.createStatement().executeUpdate(
                    "DELETE FROM asset_config WHERE asset IN ('zcash', 'monero', 'litecoin')");
            conn.createStatement().executeUpdate(
                    "DELETE FROM users WHERE contact_id LIKE 'm55-%'");

            seedAssetConfig(conn, "zcash", "coingecko", true, ZCASH_SOURCE_URL, true);
            seedAssetConfig(conn, "monero", "coingecko", true,
                    "coingecko.com/en/coins/monero", true);
        }

        assetRegistry.refresh();
    }

    /**
     * Steps (a) + (b): bootstrap-load schema contract and price_snapshot
     * INSERT observable by the Provider DB role via a direct table read.
     *
     * <p>Step (b) seeds {@code price_snapshot} via JDBC INSERT rather
     * than installing a fake {@code AssetDataSource} bean and triggering
     * a fetcher tick. The {@code AssetDataSource} SPI and
     * {@code AssetSnapshotFetcher} live in {@code infochat-collector},
     * which the Provider module does not depend on — the module boundary
     * prevents CDI-based fake injection. The Provider reads the latest
     * snapshot directly from the table (no notification path), so the
     * acceptance assertions are the row count and the stored-price
     * match.</p>
     */
    @Test
    void bootstrapLoadAndSnapshotInsert() throws Exception {
        // (a) asset_config rows exist after seeding
        try (Connection conn = dataSource.getConnection()) {
            assertTrue(countRows(conn,
                            "SELECT COUNT(*) FROM asset_config "
                                    + "WHERE asset = 'zcash' AND enabled = true") >= 1,
                    "zcash must be enabled in asset_config");
            assertTrue(countRows(conn,
                            "SELECT COUNT(*) FROM asset_config "
                                    + "WHERE asset = 'monero' AND enabled = true") >= 1,
                    "monero must be enabled in asset_config");
        }

        // (b) INSERT price_snapshot
        try (Connection writeConn = dataSource.getConnection()) {
            seedPriceSnapshot(writeConn, "zcash", "coingecko", "usd",
                    ZCASH_PRICE, CAPTURED_AT);
        }

        // Verify the row landed
        try (Connection conn = dataSource.getConnection()) {
            assertTrue(countRows(conn,
                            "SELECT COUNT(*) FROM price_snapshot "
                                    + "WHERE asset = 'zcash'") >= 1,
                    "price_snapshot must contain a zcash row");

            BigDecimal storedPrice;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT price FROM price_snapshot "
                            + "WHERE asset = 'zcash' AND sub_verb = 'coingecko' "
                            + "ORDER BY captured_at DESC LIMIT 1")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "must find latest zcash snapshot");
                    storedPrice = rs.getBigDecimal("price");
                }
            }
            assertEquals(0, ZCASH_PRICE.compareTo(storedPrice),
                    "stored price must match seeded value");
        }

        assertEquals(0, mockLlm.callCount(), "no LLM call on bootstrap/snapshot path");
    }

    /**
     * Step (c): {@code /zcash coingecko} produces a reply with the
     * display name, attribution URL (bare, no markdown link), capture
     * timestamp, and cache-age line.
     */
    @Test
    void zcashCommandReturnsRenderedReply() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            seedPriceSnapshot(conn, "zcash", "coingecko", "usd",
                    ZCASH_PRICE, CAPTURED_AT);
            seedUser(conn, "m55-u1", false, false, "vouched", null);
        }

        adapter.deliverDm("m55-u1", "/zcash coingecko");
        List<OutboundMessage> replies = adapter.sentMessages();

        assertEquals(1, replies.size(), "exactly one reply");
        String body = replies.getFirst().text();

        // (1) display name + sub-verb header
        assertTrue(body.contains("Zcash"), "reply contains display name");
        assertTrue(body.contains("coingecko"), "reply contains source name");

        // (2) attribution URL bare — no markdown link syntax
        assertTrue(body.contains(ZCASH_SOURCE_URL),
                "reply contains attribution URL");
        assertFalse(body.matches("(?s).*\\[.*\\]\\(http.*"),
                "reply must NOT use markdown link syntax");

        // (3) capture timestamp
        assertTrue(body.contains("as of"), "reply contains capture timestamp line");

        // (4) cache age
        assertTrue(body.contains("cached"), "reply contains cache-age indicator");

        assertEquals(0, mockLlm.callCount(), "no LLM call on asset-command path");
    }

    /**
     * Step (d): {@link AssetCommandFamilyOracle#isAssetCommand} reflects
     * the loaded registry after bootstrap.
     */
    @Test
    void oracleReflectsLoadedRegistry() {
        assertTrue(assetCommandFamilyOracle.isAssetCommand("zcash"),
                "zcash must be recognized");
        assertTrue(assetCommandFamilyOracle.isAssetCommand("monero"),
                "monero must be recognized");
        assertFalse(assetCommandFamilyOracle.isAssetCommand("bitcoin"),
                "bitcoin must NOT be recognized (unknown asset)");
    }

    /**
     * Step (e): a probation user can invoke an asset command — the
     * slow-start carve-out permits asset commands during probation.
     */
    @Test
    void probationUserCanInvokeAssetCommand() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            seedPriceSnapshot(conn, "zcash", "coingecko", "usd",
                    ZCASH_PRICE, CAPTURED_AT);
            seedUser(conn, "m55-u2", false, false, "invited",
                    CAPTURED_AT.plusSeconds(3600));
        }

        adapter.deliverDm("m55-u2", "/zcash coingecko");
        List<OutboundMessage> replies = adapter.sentMessages();

        assertEquals(1, replies.size(), "exactly one reply");
        String body = replies.getFirst().text();
        assertTrue(body.contains("Zcash"),
                "probation user must receive asset reply, not probation error");
        assertTrue(body.contains(ZCASH_SOURCE_URL),
                "probation user reply must contain attribution URL");

        assertEquals(0, mockLlm.callCount(),
                "no LLM call on probation asset-command path");
    }

    /**
     * Step (f): a banned user receives the fixed ban reply and never
     * reaches asset-command dispatch.
     */
    @Test
    void bannedUserHitsBanCheckBeforeDispatch() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            seedPriceSnapshot(conn, "zcash", "coingecko", "usd",
                    ZCASH_PRICE, CAPTURED_AT);
            seedUser(conn, "m55-u3", false, true, "vouched", null);
        }

        adapter.deliverDm("m55-u3", "/zcash coingecko");
        List<OutboundMessage> replies = adapter.sentMessages();

        assertEquals(1, replies.size(), "exactly one reply");
        String body = replies.getFirst().text();
        String expectedBanReply = bundleLoader.get(BundleKeys.ERROR_BAN_FIXED);
        assertEquals(expectedBanReply, body,
                "banned user must receive the fixed ban reply");

        // No SLASH_DISPATCH audit row — ban short-circuits before dispatch
        try (Connection conn = dataSource.getConnection()) {
            int auditCount = countRows(conn,
                    "SELECT COUNT(*) FROM audit_log "
                            + "WHERE action = 'SLASH_DISPATCH' "
                            + "AND actor_contact_id = 'm55-u3'");
            assertEquals(0, auditCount,
                    "banned user must not generate a SLASH_DISPATCH audit row");
        }

        assertEquals(0, mockLlm.callCount(),
                "no LLM call on banned-user path");
    }

    /**
     * Acceptance item 1: a third asset configured at runtime ({@code litecoin}),
     * absent from every hardcoded handler, is dispatchable as {@code /litecoin}
     * with no new {@link app.zcat.infochat.provider.messaging.CommandHandler}.
     * The slash dispatcher routes it to the shared {@link AssetHandler} via the
     * operator-config-driven fallback, producing a rendered reply rather than
     * the unknown-command response. The display name comes from the registry's
     * capitalize fallback (litecoin is not in the bootstrap fixture).
     */
    @Test
    void thirdAssetDispatchableWithoutHardcodedHandler() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            seedAssetConfig(conn, "litecoin", "coingecko", true,
                    "coingecko.com/en/coins/litecoin", true);
            seedPriceSnapshot(conn, "litecoin", "coingecko", "usd",
                    new BigDecimal("88.40"), CAPTURED_AT);
            seedUser(conn, "m55-u4", false, false, "vouched", null);
        }
        // The registry loaded in @BeforeEach without litecoin; refreshing picks
        // up the newly configured asset — no code change, no redeploy.
        assetRegistry.refresh();

        assertTrue(assetCommandFamilyOracle.isAssetCommand("litecoin"),
                "registry must recognize the runtime-added third asset");

        adapter.deliverDm("m55-u4", "/litecoin");
        List<OutboundMessage> replies = adapter.sentMessages();

        assertEquals(1, replies.size(), "exactly one reply");
        String body = replies.getFirst().text();
        assertTrue(body.contains("Litecoin"),
                "the dispatcher routed /litecoin to the shared asset handler, "
                        + "producing a rendered reply rather than the unknown-command response");
        assertTrue(body.contains("coingecko.com/en/coins/litecoin"),
                "rendered reply carries the litecoin attribution URL");

        assertEquals(0, mockLlm.callCount(), "no LLM call on asset-command path");
    }

    // ---- helpers ----

    private void seedAssetConfig(Connection conn, String asset, String subVerb,
                                 boolean enabled, String attributionUrl,
                                 boolean isDefault) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO asset_config (asset, sub_verb, enabled, "
                        + "default_quote_currency, attribution_url, is_default, status) "
                        + "VALUES (?, ?, ?, 'usd', ?, ?, 'active')")) {
            ps.setString(1, asset);
            ps.setString(2, subVerb);
            ps.setBoolean(3, enabled);
            ps.setString(4, attributionUrl);
            ps.setBoolean(5, isDefault);
            ps.executeUpdate();
        }
    }

    private void seedPriceSnapshot(Connection conn, String asset, String subVerb,
                                   String vsCurrency, BigDecimal price,
                                   Instant capturedAt) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO price_snapshot (asset, sub_verb, vs_currency, price, "
                        + "high_24h, low_24h, change_1h_pct, change_24h_pct, "
                        + "captured_at, source_url) "
                        + "VALUES (?, ?, ?, ?, 43.91, 41.07, 0.3, -2.4, ?, ?)")) {
            ps.setString(1, asset);
            ps.setString(2, subVerb);
            ps.setString(3, vsCurrency);
            ps.setBigDecimal(4, price);
            ps.setTimestamp(5, Timestamp.from(capturedAt));
            ps.setString(6, ZCASH_SOURCE_URL);
            ps.executeUpdate();
        }
    }

    private void seedUser(Connection conn, String contactId, boolean isAdmin,
                          boolean isBanned, String registrationState,
                          Instant probationUntil) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                        + "registration_state, probation_until) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            ps.setBoolean(4, isBanned);
            ps.setString(5, registrationState);
            ps.setTimestamp(6, probationUntil != null
                    ? Timestamp.from(probationUntil) : null);
            ps.executeUpdate();
        }
    }

    private int countRows(Connection conn, String sql) throws Exception {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
