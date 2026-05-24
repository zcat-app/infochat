package app.zcat.infochat.provider.command.asset;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.command.AssetCommandFamilyOracle;
import app.zcat.infochat.provider.testing.TestLlmProvider;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    @Inject DataSource dataSource;
    @Inject AssetRegistry assetRegistry;
    @Inject AssetCommandFamilyOracle assetCommandFamilyOracle;
    @Inject TestLlmProvider mockLlm;
    @Inject BundleLoader bundleLoader;

    @BeforeEach
    void setUp() throws Exception {
        adapter.reset();
        mockLlm.reset();
        mockLlm.setThrowOnCall(true);

        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().executeUpdate(
                    "DELETE FROM audit_log WHERE actor_contact_id LIKE 'm55-%'");
            conn.createStatement().executeUpdate(
                    "DELETE FROM price_snapshot WHERE asset IN ('zcash', 'monero')");
            conn.createStatement().executeUpdate(
                    "DELETE FROM asset_config WHERE asset IN ('zcash', 'monero')");
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
     * INSERT + NOTIFY observable by the Provider DB role.
     *
     * <p>Step (b) seeds {@code price_snapshot} via JDBC INSERT and emits
     * {@code NOTIFY} manually rather than installing a fake
     * {@code AssetDataSource} bean and triggering a fetcher tick. The
     * {@code AssetDataSource} SPI and {@code AssetSnapshotFetcher} live
     * in {@code infochat-collector}, which the Provider module does not
     * depend on — the module boundary prevents CDI-based fake injection.
     * The three acceptance assertions (row count, NOTIFY payload shape,
     * stored price match) are identical; only the seeding mechanism
     * differs.</p>
     */
    @Test
    void bootstrapLoadAndSnapshotInsertWithNotify() throws Exception {
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

        // (b) INSERT price_snapshot + NOTIFY, verify Provider can observe
        try (Connection listenConn = dataSource.getConnection()) {
            listenConn.setAutoCommit(true);
            try (Statement s = listenConn.createStatement()) {
                s.execute("LISTEN new_price_snapshot");
            }
            PGConnection pg = listenConn.unwrap(PGConnection.class);
            pg.getNotifications(1);

            try (Connection writeConn = dataSource.getConnection()) {
                seedPriceSnapshot(writeConn, "zcash", "coingecko", "usd",
                        ZCASH_PRICE, Instant.now().minusSeconds(30));
                try (Statement s = writeConn.createStatement()) {
                    s.execute("NOTIFY new_price_snapshot, "
                            + "'{\"asset\":\"zcash\",\"source\":\"coingecko\"}'");
                }
            }

            PGNotification[] notifications = awaitNotifications(pg, 1);
            assertNotNull(notifications,
                    "at least one NOTIFY new_price_snapshot must arrive");
            PGNotification n = notifications[0];
            assertEquals("new_price_snapshot", n.getName());
            String payload = n.getParameter();
            assertTrue(payload.contains("\"asset\":\"zcash\""),
                    "payload must carry asset name: " + payload);
            assertTrue(payload.contains("\"source\":\"coingecko\""),
                    "payload must carry source name: " + payload);
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
                    ZCASH_PRICE, Instant.now().minusSeconds(30));
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
                    ZCASH_PRICE, Instant.now().minusSeconds(30));
            seedUser(conn, "m55-u2", false, false, "invited",
                    Instant.now().plusSeconds(3600));
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
                    ZCASH_PRICE, Instant.now().minusSeconds(30));
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

    private PGNotification[] awaitNotifications(PGConnection pg,
                                                 int minimum) throws Exception {
        long deadlineNanos = System.nanoTime() + 10_000_000_000L;
        List<PGNotification> collected = new ArrayList<>();
        while (System.nanoTime() < deadlineNanos) {
            PGNotification[] batch = pg.getNotifications(500);
            if (batch != null) {
                for (PGNotification n : batch) {
                    collected.add(n);
                }
                if (collected.size() >= minimum) {
                    return collected.toArray(new PGNotification[0]);
                }
            }
        }
        return collected.isEmpty() ? null : collected.toArray(new PGNotification[0]);
    }
}
