package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape B (Thin-SQL) tests for {@code /asset-enable} (commands.md §Asset
 * commands). Boots with NO {@code UrlProbe} alternative — the handler must
 * not depend on a probe. The {@link IsolatedProfile} gives this class its
 * own Quarkus boot and DevServices container, so the cleanup's permanent
 * guardian admin cannot leak into default-profile tests that count admins
 * (BanCommandHandlerTest's last-admin case).
 */
@QuarkusTest
@TestProfile(AssetEnableCommandHandlerTest.IsolatedProfile.class)
class AssetEnableCommandHandlerTest {

    private static final String PREFIX = "m1-836-";
    private static final String ADAPTER = "inmemory";

    @Inject AssetEnableCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject InboundContext inboundContext;
    @Inject BundleLoader bundleLoader;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            // Permanent guardian admin so the V5 last-admin-protection
            // trigger does not refuse the per-test DELETE on admin rows.
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                            + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, "guardian-m1-836-permanent");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE target_kind = 'asset' AND target_id LIKE ?",
                        PREFIX + "%");
                exec(conn,
                        "DELETE FROM audit_log WHERE actor_user_id IN ("
                                + "  SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
            exec(conn, "DELETE FROM asset_config WHERE asset LIKE ?", PREFIX + "%");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE ?", PREFIX + "%");
        }
    }

    @Test
    void failedPairResetWritesActiveStatusZeroCounterAndAuditRow() throws Exception {
        String actor = PREFIX + "reset-actor";
        UUID actorId = seedUser(actor, true);
        String asset = PREFIX + "reset-zcash";
        seedPair(asset, "coingecko", true, true, "failed", 5);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/asset-enable " + asset + " coingecko");

        assertTrue(reply.text().contains(asset),
                "/asset-enable success must name the asset — got: " + reply.text());
        assertTrue(reply.text().contains("coingecko"),
                "/asset-enable success must name the sub-verb — got: " + reply.text());
        assertEquals("active", readText(asset, "coingecko", "status"),
                "/asset-enable must transition the failed pair to status='active'");
        assertEquals(0, readInt(asset, "coingecko", "consecutive_failures"),
                "/asset-enable must zero consecutive_failures");
        assertEquals(1L, countAudit("ASSET_ENABLE", asset + "/coingecko"),
                "/asset-enable must write exactly one ASSET_ENABLE audit row");
        assertEquals(actorId, auditActor("ASSET_ENABLE", asset + "/coingecko"),
                "the ASSET_ENABLE audit row's actor must be the issuing admin");
    }

    // ----- failure modes ---------------------------------------------------

    @Test
    void nonAdminGetsAdminOnlyErrorAndNoStateChange() throws Exception {
        String actor = PREFIX + "nonAdmin-actor";
        seedUser(actor, false);
        String asset = PREFIX + "nonadmin-zcash";
        seedPair(asset, "coingecko", true, true, "failed", 5);
        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/asset-enable " + asset + " coingecko");

        // The fixed admin-only reply leaks nothing about pair existence.
        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), reply.text(),
                "non-admin /asset-enable must surface error.admin_only");
        assertEquals("failed", readText(asset, "coingecko", "status"),
                "non-admin /asset-enable must not change asset_config.status");
        assertEquals(5, readInt(asset, "coingecko", "consecutive_failures"),
                "non-admin /asset-enable must not change consecutive_failures");
        assertEquals(0L, countAudit("ASSET_ENABLE", asset + "/coingecko"),
                "non-admin /asset-enable must write zero ASSET_ENABLE rows");
    }

    @Test
    void enabledFalsePairRefusedNamingBootstrapPath() throws Exception {
        String actor = PREFIX + "notEnabled-actor";
        seedUser(actor, true);
        String asset = PREFIX + "notenabled-zcash";
        seedPair(asset, "coingecko", false, false, "failed", 5);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/asset-enable " + asset + " coingecko");

        assertEquals(
                java.text.MessageFormat.format(
                        bundleLoader.get(BundleKeys.ERROR_ASSET_ENABLE_NOT_ENABLED),
                        asset, "coingecko"),
                reply.text(),
                "enabled=false pair must be refused with the not_enabled error");
        assertTrue(reply.text().contains("bootstrap-assets.json"),
                "the refusal must name the bootstrap re-list path — got: " + reply.text());
        assertEquals("failed", readText(asset, "coingecko", "status"),
                "the refusal must leave status untouched");
        assertEquals(false, readBoolean(asset, "coingecko", "enabled"),
                "the command must never write enabled");
        assertEquals(0L, countAudit("ASSET_ENABLE", asset + "/coingecko"),
                "the refusal must write no audit row");
    }

    @Test
    void bareFormResolvesDefaultSubVerb() throws Exception {
        String actor = PREFIX + "bare-actor";
        seedUser(actor, true);
        String asset = PREFIX + "bare-zcash";
        seedPair(asset, "coingecko", true, true, "failed", 5);
        seedPair(asset, "kraken", true, false, "failed", 4);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/asset-enable " + asset);

        assertTrue(reply.text().contains(asset),
                "bare /asset-enable success must name the asset — got: " + reply.text());
        assertTrue(reply.text().contains("coingecko"),
                "bare /asset-enable success must name the resolved default sub-verb — got: "
                        + reply.text());
        assertEquals("active", readText(asset, "coingecko", "status"),
                "bare /asset-enable must reset ONLY the is_default pair");
        assertEquals(0, readInt(asset, "coingecko", "consecutive_failures"),
                "the default pair's counter must be zeroed");
        assertEquals("failed", readText(asset, "kraken", "status"),
                "the non-default sibling pair must be untouched (per-pair blast radius)");
        assertEquals(4, readInt(asset, "kraken", "consecutive_failures"),
                "the non-default sibling pair's counter must be untouched");
        assertEquals(1L, countAudit("ASSET_ENABLE", asset + "/coingecko"),
                "exactly one ASSET_ENABLE row for the default pair");
        assertEquals(0L, countAudit("ASSET_ENABLE", asset + "/kraken"),
                "no ASSET_ENABLE row for the sibling pair");
    }

    @Test
    void bareFormNoDefaultReturnsNotConfigured() throws Exception {
        String actor = PREFIX + "noDefault-actor";
        seedUser(actor, true);
        String asset = PREFIX + "nodefault-zcash";
        seedPair(asset, "coingecko", true, false, "failed", 5);
        seedPair(asset, "kraken", true, false, "failed", 4);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/asset-enable " + asset);

        assertEquals(
                java.text.MessageFormat.format(
                        bundleLoader.get(BundleKeys.ERROR_ASSET_NOT_CONFIGURED),
                        asset, "coingecko, kraken"),
                reply.text(),
                "bare /asset-enable with no is_default pair must surface the not-configured "
                        + "friendly error with the sub-verb list");
        assertEquals("failed", readText(asset, "coingecko", "status"),
                "no default → no state change");
        assertEquals("failed", readText(asset, "kraken", "status"),
                "no default → no state change");
        assertEquals(0L, countAudit("ASSET_ENABLE", asset + "/coingecko")
                + countAudit("ASSET_ENABLE", asset + "/kraken"),
                "no default → no audit rows");
    }

    @Test
    void alreadyActivePairReturnsErrorNoAuditNoStateChange() throws Exception {
        String actor = PREFIX + "active-actor";
        seedUser(actor, true);
        String asset = PREFIX + "active-zcash";
        seedPair(asset, "coingecko", true, true, "active", 2);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/asset-enable " + asset + " coingecko");

        assertEquals(
                java.text.MessageFormat.format(
                        bundleLoader.get(BundleKeys.ERROR_ASSET_ENABLE_ALREADY_ACTIVE),
                        asset, "coingecko"),
                reply.text(),
                "already-active pair must surface the already_active error");
        assertEquals("active", readText(asset, "coingecko", "status"),
                "already-active pair must see no state change");
        assertEquals(2, readInt(asset, "coingecko", "consecutive_failures"),
                "already-active pair must keep its counter");
        assertEquals(0L, countAudit("ASSET_ENABLE", asset + "/coingecko"),
                "already-active pair must write no audit row");
    }

    @Test
    void confirmShapedArgumentRoutesToUnknownPairError() throws Exception {
        String actor = PREFIX + "confirm-actor";
        seedUser(actor, true);
        String asset = PREFIX + "confirm-zcash";
        seedPair(asset, "coingecko", true, true, "failed", 5);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/asset-enable " + asset + " confirm");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ASSET_ENABLE_UNKNOWN_PAIR), reply.text(),
                "a confirm-shaped argument is ordinary trailing text routed to the "
                        + "unknown-pair error (no confirm flow exists; the unvalidated "
                        + "token is never echoed)");
        assertEquals("failed", readText(asset, "coingecko", "status"),
                "unknown pair must see no state change");
        assertEquals(0L, countAudit("ASSET_ENABLE", asset + "/coingecko"),
                "unknown pair must write no audit row");
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedUser(String contactId, boolean isAdmin) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                             + "VALUES (?, ?, ?, 'vouched') RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedPair(String asset, String subVerb, boolean enabled, boolean isDefault,
                          String status, int consecutiveFailures) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO asset_config (asset, sub_verb, enabled, "
                             + "  default_quote_currency, attribution_url, consecutive_failures, "
                             + "  is_default, status) "
                             + "VALUES (?, ?, ?, 'usd', ?, ?, ?, ?)")) {
            ps.setString(1, asset);
            ps.setString(2, subVerb);
            ps.setBoolean(3, enabled);
            ps.setString(4, "https://example.com/" + PREFIX + "attribution");
            ps.setInt(5, consecutiveFailures);
            ps.setBoolean(6, isDefault);
            ps.setString(7, status);
            ps.executeUpdate();
        }
    }

    private String readText(String asset, String subVerb, String column) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT " + column + "::TEXT FROM asset_config "
                             + "WHERE asset = ? AND sub_verb = ?")) {
            ps.setString(1, asset);
            ps.setString(2, subVerb);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private int readInt(String asset, String subVerb, String column) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT " + column + " FROM asset_config "
                             + "WHERE asset = ? AND sub_verb = ?")) {
            ps.setString(1, asset);
            ps.setString(2, subVerb);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private boolean readBoolean(String asset, String subVerb, String column) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT " + column + " FROM asset_config "
                             + "WHERE asset = ? AND sub_verb = ?")) {
            ps.setString(1, asset);
            ps.setString(2, subVerb);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private long countAudit(String action, String targetId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = ? "
                             + "AND target_kind = 'asset' AND target_id = ?")) {
            ps.setString(1, action);
            ps.setString(2, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private UUID auditActor(String action, String targetId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT actor_user_id FROM audit_log WHERE action = ? "
                             + "AND target_kind = 'asset' AND target_id = ?")) {
            ps.setString(1, action);
            ps.setString(2, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("actor_user_id");
            }
        }
    }

    private static void exec(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    /**
     * Empty profile: no alternatives, no UrlProbe stub — exists only to
     * isolate this class's fixtures in their own Quarkus boot/container.
     */
    public static class IsolatedProfile implements QuarkusTestProfile {
    }
}
