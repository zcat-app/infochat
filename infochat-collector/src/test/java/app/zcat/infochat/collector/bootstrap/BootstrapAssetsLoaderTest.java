package app.zcat.infochat.collector.bootstrap;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link BootstrapAssetsLoader} against the
 * Quarkus DevServices Postgres.
 *
 * <p>The collector module's base {@code application.properties} does
 * NOT set {@code infochat.bootstrap.assets-file}, so every
 * {@code @QuarkusTest} in the module (this one included) boots with
 * an empty {@code @ConfigProperty Optional<Path>} — the loader's
 * {@code @PostConstruct} skips silently at startup. This lets
 * {@code absentFileDisablesCommands} below assert the no-INSERT/no-throw
 * branch via the no-arg {@link BootstrapAssetsLoader#runLoad()}, while
 * the other four scenarios exercise the loaded path via the
 * package-private {@link BootstrapAssetsLoader#runLoad(Path)} test
 * seam against the canonical fixture (or a per-test tempfile for the
 * fixture-swap scenarios).
 */
@QuarkusTest
class BootstrapAssetsLoaderTest {

    private static final Path FIXTURE_A = Paths.get(
        "src/test/resources/bootstrap/bootstrap-assets-fixture.json");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    BootstrapAssetsLoader loader;

    @BeforeEach
    void truncateAssetConfig() throws Exception {
        // asset_config is this ticket's new table; safe to TRUNCATE
        // between tests because no other suite depends on its content.
        // audit_log is intentionally NOT cleared — the BEFORE-DELETE
        // trigger trg_audit_log_no_delete (V5, Invariant 10) rejects
        // any row delete, and a sibling test (BootstrapLoaderIT) reads
        // BOOTSTRAP_SOURCE_LOAD rows the boot @PostConstruct wrote.
        // Per-test audit-row assertions use pre/post deltas instead.
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate("TRUNCATE asset_config");
        }
    }

    private long countAssetLoadAuditRows() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT count(*) FROM audit_log WHERE action = 'BOOTSTRAP_ASSET_LOAD'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void freshInsertWritesOneRowPerSubVerbWithEnabledTrueAndCorrectDefault() throws Exception {
        long auditBefore = countAssetLoadAuditRows();
        loader.runLoad(FIXTURE_A);

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {

            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM asset_config")) {
                rs.next();
                assertEquals(6, rs.getInt(1),
                    "fixture has 2 assets × 3 sub_verbs = 6 rows");
            }

            // Every fresh-INSERT row carries enabled=true (column
            // DEFAULT) and status='active'.
            try (ResultSet rs = st.executeQuery(
                "SELECT count(*) FROM asset_config WHERE enabled = true AND status = 'active'")) {
                rs.next();
                assertEquals(6, rs.getInt(1),
                    "every fresh-INSERT row must be enabled and status='active'");
            }

            // is_default=true on exactly one row per asset (the
            // entry's default_sub_verb='coingecko' for both fixture
            // entries).
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sub_verb, is_default FROM asset_config WHERE asset = ?")) {
                for (String asset : new String[]{"zcash", "monero"}) {
                    ps.setString(1, asset);
                    int defaultCount = 0;
                    String defaultSubVerb = null;
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            if (rs.getBoolean("is_default")) {
                                defaultCount++;
                                defaultSubVerb = rs.getString("sub_verb");
                            }
                        }
                    }
                    assertEquals(1, defaultCount, asset + " must have exactly one is_default row");
                    assertEquals("coingecko", defaultSubVerb,
                        asset + "'s default sub_verb must match the fixture's default_sub_verb");
                }
            }

            // Audit row written inside the same transaction (delta
            // against the pre-test count — audit_log accumulates
            // across tests because the append-only trigger forbids
            // cleanup between methods).
            assertEquals(auditBefore + 1, countAssetLoadAuditRows(),
                "fresh load must add exactly one BOOTSTRAP_ASSET_LOAD audit row");
        }
    }

    @Test
    void idempotentRerunPreservesFetcherManagedColumnsAndRowCount() throws Exception {
        loader.runLoad(FIXTURE_A);

        // Between loads, seed fetcher-managed columns on one row. The
        // loader's ON CONFLICT branch MUST NOT clobber these — the
        // fetcher (M1-055b) owns them, and resetting
        // consecutive_failures on every bootstrap would lose D42's
        // per-source failure history.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE asset_config "
                     + "   SET consecutive_failures = 3, "
                     + "       last_success_at = now(), "
                     + "       last_failure_at = now() "
                     + " WHERE asset = 'zcash' AND sub_verb = 'kraken'")) {
            assertEquals(1, ps.executeUpdate(),
                "seed UPDATE must hit the existing zcash/kraken row");
        }

        loader.runLoad(FIXTURE_A);

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {

            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM asset_config")) {
                rs.next();
                assertEquals(6, rs.getInt(1),
                    "re-run on the same fixture must not change row count");
            }

            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT consecutive_failures, last_success_at, last_failure_at "
                    + "  FROM asset_config WHERE asset = ? AND sub_verb = ?")) {
                ps.setString(1, "zcash");
                ps.setString(2, "kraken");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(3, rs.getInt("consecutive_failures"),
                        "consecutive_failures must survive the idempotent re-run");
                    assertNotNull(rs.getTimestamp("last_success_at"),
                        "last_success_at must survive the idempotent re-run");
                    assertNotNull(rs.getTimestamp("last_failure_at"),
                        "last_failure_at must survive the idempotent re-run");
                }
            }
        }
    }

    @Test
    void softDisableMarksAbsentEntriesDisabledWithoutHardDelete(@TempDir Path tempDir) throws Exception {
        loader.runLoad(FIXTURE_A);

        // Fixture B drops monero entirely and keeps only zcash/coingecko;
        // the loader must soft-disable monero's three rows AND the
        // two zcash sub_verbs no longer listed (kraken, bitfinex).
        Path fixtureB = tempDir.resolve("bootstrap-assets-fixture-zcash-only.json");
        Files.writeString(fixtureB,
            """
            {
              "default_vs": "usd",
              "assets": [
                {
                  "id": "zcash",
                  "display_name": "Zcash",
                  "ticker": "ZEC",
                  "default_sub_verb": "coingecko",
                  "sub_verbs": [
                    { "id": "coingecko", "external_id": "zcash" }
                  ]
                }
              ]
            }
            """,
            StandardCharsets.UTF_8);

        loader.runLoad(fixtureB);

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {

            // Soft-disable, not hard-delete: monero rows must remain
            // queryable so any historical price_snapshot (M1-055b)
            // keeps its FK target.
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM asset_config")) {
                rs.next();
                assertEquals(6, rs.getInt(1),
                    "soft-disable must NOT hard-delete rows");
            }

            try (ResultSet rs = st.executeQuery(
                "SELECT count(*) FROM asset_config WHERE asset = 'monero' AND enabled = false")) {
                rs.next();
                assertEquals(3, rs.getInt(1),
                    "every monero sub_verb absent from fixture B must be soft-disabled");
            }

            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sub_verb, enabled FROM asset_config WHERE asset = ?")) {
                ps.setString(1, "zcash");
                Map<String, Boolean> enabledBySubVerb = new HashMap<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        enabledBySubVerb.put(rs.getString("sub_verb"), rs.getBoolean("enabled"));
                    }
                }
                assertEquals(Boolean.TRUE,  enabledBySubVerb.get("coingecko"),
                    "zcash/coingecko remains in fixture B; must stay enabled");
                assertEquals(Boolean.FALSE, enabledBySubVerb.get("kraken"),
                    "zcash/kraken absent from fixture B; must be soft-disabled");
                assertEquals(Boolean.FALSE, enabledBySubVerb.get("bitfinex"),
                    "zcash/bitfinex absent from fixture B; must be soft-disabled");
            }
        }
    }

    @Test
    void rejectsDefaultButDisabledAtBootstrapTimeBeforeAnyInsert() throws Exception {
        // Pre-seed a soft-disabled zcash/coingecko row (operator
        // mistake: disabled the default sub-verb instead of moving
        // the default flag elsewhere). The loader's pre-check must
        // catch this before any INSERT or UPDATE runs and must abort
        // with a message naming the (asset, sub_verb) pair.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO asset_config "
                     + "(asset, sub_verb, enabled, default_quote_currency, attribution_url, is_default) "
                     + "VALUES (?, ?, false, 'usd', 'https://example.invalid/', false)")) {
            ps.setString(1, "zcash");
            ps.setString(2, "coingecko");
            assertEquals(1, ps.executeUpdate());
        }

        long auditBefore = countAssetLoadAuditRows();

        // FIXTURE_A's zcash entry sets default_sub_verb='coingecko'.
        // The pre-check sees (zcash, coingecko).enabled = false in
        // DB and rejects the load.
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> loader.runLoad(FIXTURE_A),
            "loader must reject a bootstrap that would land default-but-disabled");
        assertTrue(ex.getMessage().contains("zcash") && ex.getMessage().contains("coingecko"),
            "rejection message must name the offending (asset, sub_verb) pair; got: " + ex.getMessage());

        // The transaction rolled back: only the seeded zcash/coingecko
        // row remains, no BOOTSTRAP_ASSET_LOAD audit row was written,
        // and the seeded row's is_default flag is intact (the
        // clear-defaults step rolled back too).
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {

            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM asset_config")) {
                rs.next();
                assertEquals(1, rs.getInt(1),
                    "loader must abort before inserting any row beyond the seeded one");
            }

            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT is_default FROM asset_config WHERE asset = ? AND sub_verb = ?")) {
                ps.setString(1, "zcash");
                ps.setString(2, "coingecko");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertFalse(rs.getBoolean("is_default"),
                        "the rolled-back clear-defaults step must leave the seeded row's flag intact");
                }
            }

            // Audit-row delta is zero: the would-be BOOTSTRAP_ASSET_LOAD
            // row rolled back along with the load.
            assertEquals(auditBefore, countAssetLoadAuditRows(),
                "audit row must roll back along with the rejected load");
        }
    }

    @Test
    void absentFileDisablesCommandsSilentlyWithNoInsertAndNoThrow() throws Exception {
        // The base test-resource application.properties does NOT set
        // `infochat.bootstrap.assets-file`, so the @ConfigProperty
        // field is injected as Optional.empty(). The no-arg runLoad()
        // must take the absent-file branch: log + return, no INSERT,
        // no throw.
        long auditBefore = countAssetLoadAuditRows();
        loader.runLoad();

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM asset_config")) {
            rs.next();
            assertEquals(0, rs.getInt(1),
                "absent assets-file property must NOT INSERT any asset_config row");
        }

        assertEquals(auditBefore, countAssetLoadAuditRows(),
            "absent assets-file property must NOT add a BOOTSTRAP_ASSET_LOAD audit row");
    }
}
