package app.zcat.infochat.provider.command.asset;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Reads the latest {@code price_snapshot} row for a given
 * {@code (asset, sub_verb, vs_currency)} triple. Provider has
 * SELECT-only on {@code price_snapshot} per V17 GRANTs.
 *
 * <p>The stale-marker threshold is {@code 2 * refresh_interval}
 * for the source's host family (per design §10.4). Refresh
 * intervals are per-host, not per-asset, matching the
 * Collector's per-host tick cadence.</p>
 */
@ApplicationScoped
public class AssetSnapshotReader {

    /** Snapshot data from a single {@code price_snapshot} row. */
    public record Snapshot(
            String asset,
            String subVerb,
            String vsCurrency,
            BigDecimal price,
            @Nullable BigDecimal volume24h,
            @Nullable BigDecimal high24h,
            @Nullable BigDecimal low24h,
            @Nullable BigDecimal change1hPct,
            @Nullable BigDecimal change24hPct,
            @Nullable BigDecimal change7dPct,
            Instant capturedAt,
            @Nullable String sourceUrl
    ) {}

    /** Result of a snapshot lookup: the data plus staleness info. */
    public record SnapshotResult(
            Snapshot snapshot,
            boolean stale,
            Duration refreshInterval
    ) {}

    @Inject
    DataSource dataSource;

    @ConfigProperty(name = "infochat.assets.refresh.coingecko", defaultValue = "90")
    long refreshCoingeckoSeconds;

    @ConfigProperty(name = "infochat.assets.refresh.kraken", defaultValue = "90")
    long refreshKrakenSeconds;

    @ConfigProperty(name = "infochat.assets.refresh.bitfinex", defaultValue = "90")
    long refreshBitfinexSeconds;

    /** CDI-required no-arg constructor. */
    public AssetSnapshotReader() {}

    /** Test constructor — bypasses CDI injection. */
    AssetSnapshotReader(DataSource dataSource, long refreshCoingecko,
                        long refreshKraken, long refreshBitfinex) {
        this.dataSource = dataSource;
        this.refreshCoingeckoSeconds = refreshCoingecko;
        this.refreshKrakenSeconds = refreshKraken;
        this.refreshBitfinexSeconds = refreshBitfinex;
    }

    /**
     * Reads the latest {@code price_snapshot} row for the given triple.
     *
     * @return the snapshot with staleness metadata, or null if no row exists
     */
    public @Nullable SnapshotResult readLatest(String asset,
                                                String subVerb,
                                                String vsCurrency) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT asset, sub_verb, vs_currency, price, volume_24h, "
                             + "high_24h, low_24h, change_1h_pct, change_24h_pct, "
                             + "change_7d_pct, captured_at, source_url "
                             + "FROM price_snapshot "
                             + "WHERE asset = ? AND sub_verb = ? AND vs_currency = ? "
                             + "ORDER BY captured_at DESC "
                             + "LIMIT 1")) {
            ps.setString(1, asset);
            ps.setString(2, subVerb);
            ps.setString(3, vsCurrency);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Snapshot snapshot = mapRow(rs);
                Duration interval = refreshIntervalFor(subVerb);
                boolean stale = Duration.between(snapshot.capturedAt, Instant.now())
                        .compareTo(interval.multipliedBy(2)) > 0;
                return new SnapshotResult(snapshot, stale, interval);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to read price_snapshot for " + asset + "/" + subVerb + "/" + vsCurrency, e);
        }
    }

    private Snapshot mapRow(ResultSet rs) throws SQLException {
        Timestamp capturedTs = rs.getTimestamp("captured_at");
        return new Snapshot(
                rs.getString("asset"),
                rs.getString("sub_verb"),
                rs.getString("vs_currency"),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("volume_24h"),
                rs.getBigDecimal("high_24h"),
                rs.getBigDecimal("low_24h"),
                rs.getBigDecimal("change_1h_pct"),
                rs.getBigDecimal("change_24h_pct"),
                rs.getBigDecimal("change_7d_pct"),
                capturedTs.toInstant(),
                rs.getString("source_url")
        );
    }

    private Duration refreshIntervalFor(String subVerb) {
        long seconds = switch (subVerb) {
            case "coingecko" -> refreshCoingeckoSeconds;
            case "kraken" -> refreshKrakenSeconds;
            case "bitfinex" -> refreshBitfinexSeconds;
            default -> 90;
        };
        return Duration.ofSeconds(seconds);
    }
}
