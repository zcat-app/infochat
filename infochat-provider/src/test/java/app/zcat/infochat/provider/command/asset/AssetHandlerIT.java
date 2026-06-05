package app.zcat.infochat.provider.command.asset;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider-internal IT for the asset command surface. Exercises the
 * bare {@code /zcash} → seeded {@code price_snapshot} row → rendered
 * reply path via the in-memory adapter (test-time deployment shape
 * per {@code docs/spec/deployment.md} §Deployment scenarios).
 *
 * <p>Seeds {@code asset_config} + {@code price_snapshot} via JDBC
 * (no fetcher tick — that's the umbrella's IT) and re-triggers the
 * {@link AssetRegistry} refresh so the Provider-side registry picks
 * up the seeded rows.</p>
 */
@QuarkusTest
class AssetHandlerIT {

    private static final String PREFIX = "m1-055c-asset-";
    private static final String ADAPTER = "inmemory";

    @Inject InMemoryAdapter adapter;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject AssetRegistry assetRegistry;

    @BeforeEach
    void setUp() throws Exception {
        adapter.reset();
        try (Connection conn = dataSource.getConnection()) {
            // Clean up test data from prior runs
            conn.createStatement().executeUpdate(
                    "DELETE FROM price_snapshot WHERE asset = 'zcash' AND sub_verb = 'coingecko'");
            conn.createStatement().executeUpdate(
                    "DELETE FROM asset_config WHERE asset = 'zcash'");
            conn.createStatement().executeUpdate(
                    "DELETE FROM users WHERE contact_id LIKE '" + PREFIX + "%'");

            // Seed asset_config
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO asset_config (asset, sub_verb, enabled, default_quote_currency, "
                            + "attribution_url, is_default, status) "
                            + "VALUES ('zcash', 'coingecko', true, 'usd', "
                            + "'coingecko.com/en/coins/zcash', true, 'active')")) {
                ps.executeUpdate();
            }

            // Seed price_snapshot
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO price_snapshot (asset, sub_verb, vs_currency, price, "
                            + "high_24h, low_24h, change_1h_pct, change_24h_pct, "
                            + "captured_at, source_url) "
                            + "VALUES ('zcash', 'coingecko', 'usd', 42.18, "
                            + "43.91, 41.07, 0.3, -2.4, ?, "
                            + "'coingecko.com/en/coins/zcash')")) {
                ps.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(30)));
                ps.executeUpdate();
            }

            // Seed a registered non-banned user past probation
            seedUser(conn, PREFIX + "user", false, false);
        }

        // Re-trigger registry load so it picks up the seeded asset_config
        assetRegistry.refresh();
    }

    @Test
    void bareZcashReturnsRenderedReply() throws Exception {
        adapter.deliverDm(PREFIX + "user", "/zcash");
        List<OutboundMessage> replies = adapter.sentMessages();

        assertEquals(1, replies.size(), "exactly one reply");
        String text = replies.getFirst().text();
        assertTrue(text.contains("Zcash"), "reply contains display name");
        assertTrue(text.contains("coingecko"), "reply contains source name");
        assertTrue(text.contains("42.18"), "reply contains price");
        assertTrue(text.contains("source:"), "reply contains attribution");
    }

    private void seedUser(Connection conn, String contactId, boolean isAdmin,
                          boolean isBanned) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                        + "registration_state, probation_until) "
                        + "VALUES (?, ?, ?, ?, 'vouched', NULL)")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            ps.setBoolean(4, isBanned);
            ps.executeUpdate();
        }
    }
}
