package app.zcat.infochat.provider.journey;

import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Test-only stand-in for the Collector's {@code @Startup}
 * {@code BootstrapAssetsLoader}, so {@link app.zcat.infochat.provider.command.asset.AssetRegistry}
 * loads an enabled {@code zcash} asset through its real production path in the
 * {@code GoldenPathJourneyIT} asset hop (M1-415, hop 12).
 *
 * <p>In production the asset list is not loaded by the Provider in isolation:
 * the Collector's bootstrap loader fills {@code asset_config} at its startup,
 * and the Provider's {@code AssetRegistry} then reads that table once at its own
 * {@code StartupEvent}. A Provider-only integration test boots no Collector, so
 * nothing fills {@code asset_config} and the registry boots empty. Rather than
 * widen the registry's package-private {@code refresh()} seam (a visibility
 * change purely to satisfy a test) or reach it by reflection, this bean
 * <em>simulates the missing producer</em>: it writes the row that the Collector
 * would have written, on the same {@code StartupEvent}, ordered ahead of the
 * registry's own observer so the registry reads populated data — exercising the
 * genuine load path with no production change.
 *
 * <p>Gated by {@code @IfBuildProperty} so it exists only for the journey test's
 * app instance (its {@code TestProfile} sets the flag); every other
 * {@code @QuarkusTest} boots without it, so no foreign {@code asset_config} row
 * leaks into their startup state. Same test-only-startup-bean pattern as the
 * M1-414 dev terminal harness; seeding goes through the owner-role
 * {@code @SeedDataSource} because the Provider service role is SELECT-only on
 * {@code asset_config} (V14 GRANTs).
 */
@IfBuildProperty(name = "infochat.journey.seed-assets", stringValue = "true")
@ApplicationScoped
public class JourneyAssetConfigSeeder {

    /** Attribution URL the journey asserts in the rendered /zcash reply. */
    static final String ZCASH_SOURCE_URL = "coingecko.com/en/coins/zcash";

    private final DataSource dataSource;

    @Inject
    JourneyAssetConfigSeeder(@SeedDataSource DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Runs ahead of {@code AssetRegistry.onStartup} (which observes
     * {@code StartupEvent} at the default observer priority 2500); the explicit
     * lower priority guarantees this seed commits before the registry reads
     * {@code asset_config}. Idempotent across reboots via delete-then-insert.
     */
    void seedAssetConfigBeforeRegistryLoads(@Observes @Priority(2000) StartupEvent event) {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement delete = conn.prepareStatement(
                    "DELETE FROM asset_config WHERE asset = 'zcash'")) {
                delete.executeUpdate();
            }
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO asset_config (asset, sub_verb, enabled, "
                            + "default_quote_currency, attribution_url, is_default, status) "
                            + "VALUES ('zcash', 'coingecko', TRUE, 'usd', ?, TRUE, 'active')")) {
                insert.setString(1, ZCASH_SOURCE_URL);
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            // Startup-seed failure is a hard test-environment fault — surface it.
            throw new IllegalStateException(
                    "JourneyAssetConfigSeeder failed to seed asset_config", e);
        }
    }
}
