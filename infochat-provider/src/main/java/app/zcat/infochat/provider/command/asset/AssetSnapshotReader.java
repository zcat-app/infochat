package app.zcat.infochat.provider.command.asset;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Reads the latest {@code price_snapshot} row for a given
 * {@code (asset, sub_verb, vs_currency)} triple. Provider has
 * SELECT-only on {@code price_snapshot} per V17 GRANTs.
 *
 * <p>Staleness is judged against a single Provider-owned,
 * profile-driven freshness window (commands.md §Asset commands):
 * a snapshot is stale once its age exceeds that window. The window
 * is independent of the Collector's per-host fetch cadence — the
 * Provider has no fetch loop and no longer mirrors the Collector's
 * {@code infochat.assets.refresh.*} keys, so tightening the
 * Collector cadence cannot desync the Provider's staleness contract
 * (M1-340).</p>
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
            Duration freshnessWindow
    ) {}

    @Inject
    DataSource dataSource;

    // The "now" fed into the isStale freshness/TTL comparison is a decision-
    // gate read, so it comes from the injected Clock to stay pinnable in tests
    // (M1-454, engineering-rules §9). The isStale static keeps taking `now` as
    // a parameter (still pure-function unit-testable); only this caller's now
    // source moves. CDI overrides the systemUTC() default at runtime, and the
    // initialiser keeps the test-constructor instances non-null (M1-444 reference).
    @Inject
    Clock clock = Clock.systemUTC();

    // Provider-owned, profile-driven staleness threshold (commands.md
    // §Asset commands): a snapshot older than this window renders the stale
    // marker. No inline defaultValue — application.properties is the source of
    // truth, matching the FetchScheduler.java:140 profile-driven-key
    // convention. Deliberately NOT derived from the Collector's per-host
    // infochat.assets.refresh.* cadence: the Provider window is independent, so
    // a one-sided Collector-cadence override cannot desync staleness (M1-340).
    @ConfigProperty(name = "infochat.assets.freshness-window")
    Duration freshnessWindow;

    // Short-TTL read cache (M1-365): repeated /zcash|/monero invocations of the
    // same (asset, sub_verb, vs_currency) within the window are served from
    // memory so a burst of identical commands does not take a pool connection
    // each. The TTL is set well below every profile's fetch cadence
    // (application.properties), so the cached staleness verdict cannot drift
    // meaningfully from a live read — the M1-340 freshness window is unchanged.
    @ConfigProperty(name = "infochat.assets.snapshot-cache-ttl")
    Duration snapshotCacheTtl;

    // Memory belt, not a spec commitment: the live key space is the operator-
    // configured (asset, sub_verb, vs_currency) triples — a few dozen entries.
    private static final long MAX_CACHE_ENTRIES = 256;

    // Built lazily on first read, not as a final inline field: the TTL is a
    // @ConfigProperty injected AFTER construction, so it is unavailable at
    // field-initialization time. @Nullable + volatile keeps the double-build
    // race (two concurrent first-readers) benign — both build idempotent caches,
    // last write wins, the loser is GC'd; no entry is ever lost.
    private volatile @Nullable Cache<CacheKey, SnapshotResult> snapshotCache;

    /** Cache key: the (asset, sub_verb, vs_currency) triple the read selects on. */
    private record CacheKey(String asset, String subVerb, String vsCurrency) {}

    /** CDI-required no-arg constructor. */
    public AssetSnapshotReader() {}

    /** Test constructor — bypasses CDI injection. */
    AssetSnapshotReader(DataSource dataSource, Duration freshnessWindow) {
        this.dataSource = dataSource;
        this.freshnessWindow = freshnessWindow;
    }

    /**
     * Reads the latest {@code price_snapshot} row for the given triple,
     * serving repeated reads within the cache TTL from memory.
     *
     * <p>A DB miss (no row) is NOT cached — so a snapshot that lands after a
     * miss becomes visible on the next read rather than being masked by a
     * cached null. Mirrors the {@code TranslationCache} getIfPresent/put idiom.
     *
     * @return the snapshot with staleness metadata, or null if no row exists
     */
    public @Nullable SnapshotResult readLatest(String asset,
                                                String subVerb,
                                                String vsCurrency) {
        CacheKey key = new CacheKey(asset, subVerb, vsCurrency);
        SnapshotResult cached = snapshotCache().getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        SnapshotResult loaded = loadLatest(asset, subVerb, vsCurrency);
        if (loaded != null) {
            snapshotCache().put(key, loaded);
        }
        return loaded;
    }

    private Cache<CacheKey, SnapshotResult> snapshotCache() {
        Cache<CacheKey, SnapshotResult> existing = snapshotCache;
        if (existing != null) {
            return existing;
        }
        Cache<CacheKey, SnapshotResult> built = Caffeine.newBuilder()
                .expireAfterWrite(snapshotCacheTtl)
                .maximumSize(MAX_CACHE_ENTRIES)
                .build();
        snapshotCache = built;
        return built;
    }

    private @Nullable SnapshotResult loadLatest(String asset,
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
                boolean stale = isStale(snapshot.capturedAt, clock.instant(), freshnessWindow);
                return new SnapshotResult(snapshot, stale, freshnessWindow);
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

    /**
     * A snapshot is stale once its age exceeds the Provider-owned freshness
     * window. Package-private + static so the window-vs-age decision is unit
     * testable without a {@code DataSource} (M1-340 acceptance).
     */
    static boolean isStale(Instant capturedAt, Instant now, Duration freshnessWindow) {
        return Duration.between(capturedAt, now).compareTo(freshnessWindow) > 0;
    }
}
