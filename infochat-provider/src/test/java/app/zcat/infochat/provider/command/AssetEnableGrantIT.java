package app.zcat.infochat.provider.command;

import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the V80 column-scoped Provider grant on {@code asset_config}
 * under the REAL {@code infochat_provider} role via {@code SET ROLE}
 * (the SourceEnableParkResetIT idiom): the /asset-enable reset UPDATE
 * succeeds while the operator-curated config columns stay denied.
 * Booting the fresh Testcontainers DB through Flyway also proves the
 * migration applies cleanly.
 */
@QuarkusTest
class AssetEnableGrantIT {

    private static final String PREFIX = "m1-836-grant-";

    /** SQLState insufficient_privilege — raised by the ACL check itself. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Inject @SeedDataSource DataSource dataSource;

    @BeforeEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM asset_config WHERE asset LIKE ?")) {
            ps.setString(1, PREFIX + "%");
            ps.executeUpdate();
        }
    }

    @Test
    void providerRoleCanUpdateStatusAndCounterColumns() throws Exception {
        seedPair(PREFIX + "zcash", "coingecko");

        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_provider");
                try {
                    // Allow direction: the grant must cover every column
                    // the reset writes — without it the handler dies with
                    // 42501 under the real role, invisibly to owner-role tests.
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE asset_config SET status = 'active', "
                                    + "consecutive_failures = 0 "
                                    + "WHERE asset = ? AND sub_verb = ?")) {
                        ps.setString(1, PREFIX + "zcash");
                        ps.setString(2, "coingecko");
                        assertEquals(1, ps.executeUpdate(),
                                "the /asset-enable reset UPDATE must succeed as "
                                        + "infochat_provider (V80 column-scoped grant)");
                    }
                } finally {
                    st.execute("RESET ROLE");
                }
            }
        }
    }

    @Test
    void providerRoleCannotUpdateIdentityOrConfigColumns() throws Exception {
        seedPair(PREFIX + "zcash", "coingecko");

        // Deny direction: enablement, default-pair placement, and attribution
        // are operator-curated (D39) — a Provider foothold must not flip them.
        String[] deniedColumns = {"enabled", "is_default", "attribution_url"};
        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_provider");
                try {
                    for (String column : deniedColumns) {
                        String value = "attribution_url".equals(column)
                                ? "'https://attacker.example/'" : "FALSE";
                        SQLException denied = assertThrows(SQLException.class, () -> {
                            try (PreparedStatement ps = conn.prepareStatement(
                                    "UPDATE asset_config SET " + column + " = " + value
                                            + " WHERE asset = ? AND sub_verb = ?")) {
                                ps.setString(1, PREFIX + "zcash");
                                ps.setString(2, "coingecko");
                                ps.executeUpdate();
                            }
                        }, "UPDATE asset_config." + column + " as infochat_provider must be denied");
                        assertEquals(INSUFFICIENT_PRIVILEGE, denied.getSQLState(),
                                "the " + column + " update must fail on the column ACL (42501); was: "
                                        + denied.getSQLState() + " — " + denied.getMessage());
                    }
                } finally {
                    st.execute("RESET ROLE");
                }
            }
        }
    }

    // ----- helpers ---------------------------------------------------------

    private void seedPair(String asset, String subVerb) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO asset_config (asset, sub_verb, enabled, "
                             + "  default_quote_currency, attribution_url, consecutive_failures, "
                             + "  is_default, status) "
                             + "VALUES (?, ?, TRUE, 'usd', 'https://example.com/', 5, TRUE, 'failed') "
                             + "ON CONFLICT (asset, sub_verb) DO UPDATE SET status = 'failed', "
                             + "  consecutive_failures = 5")) {
            ps.setString(1, asset);
            ps.setString(2, subVerb);
            ps.executeUpdate();
        }
    }
}
