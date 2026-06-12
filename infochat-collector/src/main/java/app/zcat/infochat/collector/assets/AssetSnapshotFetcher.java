package app.zcat.infochat.collector.assets;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import app.zcat.infochat.collector.assets.source.AssetDataSource;
import app.zcat.infochat.collector.assets.source.AssetDataSource.FetchException;
import app.zcat.infochat.collector.assets.store.PriceSnapshotStore;
import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Drives the per-host asset-snapshot fetch loop. One {@code @Scheduled}
 * tick per data-source host (CoinGecko / Kraken / Bitfinex in v1) per
 * spec §Asset commands — "All {@code kraken} snapshots across every
 * enabled asset share one tick cadence; same for {@code coingecko}
 * and {@code bitfinex}". Per-pair scheduling would multiply outbound
 * traffic by N and break the upstream rate-limit budget.
 *
 * <h2>Per-tick flow</h2>
 * For the host that fired the tick: enumerate enabled {@code asset_config}
 * rows whose {@code sub_verb = <host>}, look up the matching
 * {@link AssetDataSource} bean by {@link AssetDataSource#id}, invoke
 * {@link AssetDataSource#fetchSnapshot} for each pair, and hand each
 * successful result to {@link PriceSnapshotStore#store}.
 *
 * <h2>D42 failure-counter state machine</h2>
 * Each successful fetch resets {@code asset_config.consecutive_failures}
 * to 0 and updates {@code last_success_at}. Each {@link FetchException}
 * increments the counter and updates {@code last_failure_at}. On
 * threshold breach the row's {@code status} flips
 * {@code active → failed} (guarded by {@code AND status='active'} so a
 * second threshold breach against an already-failed row is a no-op)
 * and exactly ONE throttled admin notification fires via
 * {@link ThrottledAdminNotifier} (M1-058). Recovery from {@code failed}
 * is operator-side per docs/design/10-asset-commands.md §10.8b.
 *
 * <h2>Profile-driven cadence</h2>
 * The three {@code infochat.assets.refresh.<host>} keys are profile-
 * driven per design §10.4 (laptop 60s, vps 90s, pi 300s, remote-llm
 * 90s). Following the codebase convention enforced at
 * {@code FetchScheduler.java}:95-100, the {@code @ConfigProperty}
 * fields carry NO inline {@code defaultValue} — defaults live in
 * {@code application.properties}. The {@code @ConfigProperty} fields
 * also satisfy the M1-055b acceptance contract that mandates both
 * a {@code @Scheduled(every=...)} reference and a backing
 * {@code @ConfigProperty} declaration per host; Quarkus binds the
 * Duration value into each field even though the per-tick logic
 * reads the configured cadence indirectly via the
 * {@code @Scheduled} expression.
 *
 * <h2>Startup ordering</h2>
 * {@code @Priority(400)} per
 * {@code docs/design/01-architecture.md} §1.4.3 — same tier as
 * {@code FetchScheduler}: runs after Flyway (100), BootstrapLoaders
 * (200), and OutboxRehydrator (300), so the {@code asset_config}
 * rows the bootstrap loader writes are visible when the first tick
 * fires.
 *
 * <h2>Scope discipline</h2>
 * Asset snapshots are NOT posts (spec §Asset commands — "Data is not
 * posts"). The fetcher never delegates to OutboxRehydrator,
 * NewPostHandler, PostEvalPipeline, or any Stage 1/2 component —
 * snapshots write directly to {@code price_snapshot} via
 * {@link PriceSnapshotStore}.
 */
@Startup
@Priority(400)
@ApplicationScoped
public class AssetSnapshotFetcher {

    private static final Logger LOG = Logger.getLogger(AssetSnapshotFetcher.class);

    private static final String COINGECKO = "coingecko";
    private static final String KRAKEN = "kraken";
    private static final String BITFINEX = "bitfinex";

    @Inject
    DataSource dataSource;

    @Inject
    PriceSnapshotStore snapshotStore;

    @Inject
    ThrottledAdminNotifier adminNotifier;

    // CDI discovers all three concrete AssetDataSource beans here.
    // The matching to a particular asset_config.sub_verb row happens
    // via AssetDataSource.id() at tick time (see resolveSource).
    @Inject
    Instance<AssetDataSource> sources;

    // Profile-driven cadences per design §10.4. NO inline defaultValue
    // — the per-profile blocks in application.properties are the
    // source of truth; the base value is the test-time fallback.
    // (FetchScheduler.java:95-100 codifies this rule.) The @Scheduled
    // expression below resolves the same property string at deploy
    // time, so the per-tick logic never reads these fields directly;
    // they are kept because M1-055b's acceptance contract mandates a
    // backing @ConfigProperty declaration per host alongside the
    // @Scheduled(every=...) reference (see the class javadoc
    // §Profile-driven cadence).
    @SuppressWarnings("unused")
    @ConfigProperty(name = "infochat.assets.refresh.coingecko")
    Duration coingeckoRefresh;

    @SuppressWarnings("unused")
    @ConfigProperty(name = "infochat.assets.refresh.kraken")
    Duration krakenRefresh;

    @SuppressWarnings("unused")
    @ConfigProperty(name = "infochat.assets.refresh.bitfinex")
    Duration bitfinexRefresh;

    // Single-global-default property: inline defaultValue is permitted
    // per the codebase's split convention (FetchScheduler.java:95-100
    // forbids inline defaults on profile-driven keys only). Operator
    // override: -Dinfochat.assets.failure-threshold=N.
    @ConfigProperty(name = "infochat.assets.failure-threshold", defaultValue = "5")
    int failureThreshold;

    // Per-host source map cached at first tick; the Instance<>
    // resolution is non-deterministic ordering but stable per JVM.
    private volatile @Nullable Map<String, AssetDataSource> sourcesById;

    @Scheduled(every = "{infochat.assets.refresh.coingecko}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void onCoingeckoTick() {
        runHostTick(COINGECKO);
    }

    @Scheduled(every = "{infochat.assets.refresh.kraken}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void onKrakenTick() {
        runHostTick(KRAKEN);
    }

    @Scheduled(every = "{infochat.assets.refresh.bitfinex}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void onBitfinexTick() {
        runHostTick(BITFINEX);
    }

    /**
     * Run one tick for the named host: enumerate enabled rows, fetch
     * each one, hand off on success, update the D42 counters on
     * failure. Package-private factored so each per-host tick reads
     * uniformly. Visible-for-test so unit tests can fire ticks
     * deterministically against a halted scheduler.
     */
    public void runHostTick(String host) {
        List<EnabledPair> rows;
        try {
            rows = enumerateEnabled(host);
        } catch (SQLException e) {
            LOG.warnf(e, "AssetSnapshotFetcher: failed to enumerate asset_config for host=%s", host);
            return;
        }
        for (EnabledPair row : rows) {
            try {
                tickOnePair(host, row);
            } catch (RuntimeException e) {
                // Impl bug in a data source or store — NOT an upstream-
                // fetch failure. Logged with the exception's own class
                // and kept OUT of the D42 ladder so the failure counters
                // reflect only genuine upstream health; the loop keeps
                // ticking so one pair's bug cannot starve sibling pairs.
                LOG.errorf(e, "AssetSnapshotFetcher: %s while ticking host=%s asset=%s sub_verb=%s; skipping pair",
                    e.getClass().getSimpleName(), host, row.asset(), row.subVerb());
            }
        }
    }

    private void tickOnePair(String host, EnabledPair row) {
        AssetDataSource source = resolveSource(host);
        if (source == null) {
            LOG.warnf("AssetSnapshotFetcher: no AssetDataSource bean for host=%s; skipping", host);
            return;
        }
        PriceSnapshot snapshot;
        try {
            snapshot = source.fetchSnapshot(row.asset(), row.defaultQuoteCurrency());
        } catch (FetchException e) {
            recordFailure(row, e);
            return;
        }
        try {
            snapshotStore.store(snapshot);
            recordSuccess(row);
        } catch (RuntimeException e) {
            // Snapshot store failure is a DB problem, NOT a fetch
            // problem; the D42 counter is for upstream fetch health.
            // Surface and keep ticking.
            LOG.warnf(e, "AssetSnapshotFetcher: snapshot store failed for asset=%s sub_verb=%s",
                row.asset(), row.subVerb());
        }
    }

    private void recordSuccess(EnabledPair row) {
        final String sql =
            "UPDATE asset_config "
            + "   SET consecutive_failures = 0, "
            + "       last_success_at = NOW() "
            + " WHERE asset = ? AND sub_verb = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, row.asset());
            ps.setString(2, row.subVerb());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf(e, "AssetSnapshotFetcher: success-counter UPDATE failed for asset=%s sub_verb=%s",
                row.asset(), row.subVerb());
        }
    }

    private void recordFailure(EnabledPair row, FetchException cause) {
        // This is the D42 failure ladder for asset_config. It is deliberately
        // NOT commonized with SourceRepository's D42 ladder for the `source`
        // table: the two share only an incidental shape. They key on different
        // columns (source.id UUID vs (asset, sub_verb)), track different
        // timestamps (source bumps last_fetch_at every tick + last_success_at
        // on success; asset_config bumps last_failure_at on failure +
        // last_success_at on success, with no per-tick fetch timestamp), and
        // split the notify differently (SourceRepository.recordFailure returns
        // a FailureOutcome for its caller to notify; this method fires
        // notifyOnce inline). Asset snapshots are not posts (spec §Asset
        // commands), so the two ladders live in independent domains; unifying
        // them would need a table/column/notify-parameterized helper more
        // complex than either concrete method and would couple assets to fetch.
        //
        // Step 1: bump the per-pair counter, capture the post-bump
        // value via RETURNING. The atomic increment guarantees N
        // concurrent ticks (shouldn't happen — single scheduler — but
        // defensive in case of operator-side concurrent runs) produce
        // exactly N increments.
        final String bumpSql =
            "UPDATE asset_config "
            + "   SET consecutive_failures = consecutive_failures + 1, "
            + "       last_failure_at = NOW() "
            + " WHERE asset = ? AND sub_verb = ? "
            + "RETURNING consecutive_failures";
        int newCount;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(bumpSql)) {
            ps.setString(1, row.asset());
            ps.setString(2, row.subVerb());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // Row vanished between enumerate and bump
                    // (operator-side disable). No-op.
                    return;
                }
                newCount = rs.getInt(1);
            }
        } catch (SQLException e) {
            LOG.warnf(e, "AssetSnapshotFetcher: failure-counter bump failed for asset=%s sub_verb=%s",
                row.asset(), row.subVerb());
            return;
        }

        // The FetchException message can carry untrusted upstream bytes
        // (e.g. a data source's API error body). Log the control-stripped,
        // truncated message rather than handing the raw throwable to the
        // formatter, which would render its unsanitized message + stack.
        LOG.warnf("AssetSnapshotFetcher: fetch failed for asset=%s sub_verb=%s (count=%d): %s",
            row.asset(), row.subVerb(), newCount, stripAndTruncate(cause.getMessage()));

        if (newCount < failureThreshold) {
            return;
        }

        // Step 2: flip status active → failed, guarded so a second
        // threshold breach against an already-failed row updates 0
        // rows and skips the notifier. The single notifyOnce per
        // active→failed transition is the spec-committed invariant
        // (security.md §Per-source health — "exactly one admin
        // notification on transition into failed").
        final String flipSql =
            "UPDATE asset_config "
            + "   SET status = 'failed' "
            + " WHERE asset = ? AND sub_verb = ? AND status = 'active'";
        int flipped;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(flipSql)) {
            ps.setString(1, row.asset());
            ps.setString(2, row.subVerb());
            flipped = ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf(e, "AssetSnapshotFetcher: status-flip UPDATE failed for asset=%s sub_verb=%s",
                row.asset(), row.subVerb());
            return;
        }
        if (flipped == 0) {
            return;
        }

        String key = "asset-source-failed:" + row.asset() + ":" + row.subVerb();
        String errorClass = cause.getCause() == null
            ? cause.getClass().getSimpleName()
            : cause.getCause().getClass().getSimpleName();
        String message = stripAndTruncate(cause.getMessage());
        adminNotifier.notifyOnce(key, errorClass, message);
    }

    private List<EnabledPair> enumerateEnabled(String host) throws SQLException {
        final String sql =
            "SELECT asset, sub_verb, default_quote_currency "
            + "  FROM asset_config "
            + " WHERE sub_verb = ? AND enabled = true AND status = 'active'";
        List<EnabledPair> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, host);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new EnabledPair(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
        return out;
    }

    private @Nullable AssetDataSource resolveSource(String host) {
        Map<String, AssetDataSource> snapshot = sourcesById;
        if (snapshot == null) {
            snapshot = buildSourceMap();
            sourcesById = snapshot;
        }
        return snapshot.get(host);
    }

    private synchronized Map<String, AssetDataSource> buildSourceMap() {
        if (sourcesById != null) {
            return sourcesById;
        }
        Map<String, AssetDataSource> built = new HashMap<>();
        for (AssetDataSource s : sources) {
            built.put(s.id(), s);
        }
        return Map.copyOf(built);
    }

    /**
     * Test-only hook to drop the cached source map. Tests that swap
     * {@link AssetDataSource} beans via {@code QuarkusMock} after the
     * first tick has populated the cache need to reset it; production
     * code never calls this. Package-private so cross-package tests
     * cannot reach in.
     */
    void resetSourceCacheForTest() {
        sourcesById = null;
    }

    // Bound on upstream-derived bytes admitted into the WARN log and the
    // forwarded admin-notification message. The notifier re-sanitizes its
    // own inputs, but the log line at recordFailure bypasses it, so the
    // stripping happens here at the point the bytes leave the fetcher.
    private static final int MAX_UPSTREAM_CHARS = 200;

    // Package-private so a unit test can pin the control-strip + truncation
    // shape directly; the notifier re-sanitizes its inputs and the WARN log
    // line is not observable through the public API.
    static String stripAndTruncate(@Nullable String upstream) {
        if (upstream == null) {
            return "";
        }
        String stripped = SafeLog.stripControls(upstream);
        if (stripped.length() <= MAX_UPSTREAM_CHARS) {
            return stripped;
        }
        return stripped.substring(0, MAX_UPSTREAM_CHARS) + "…";
    }

    record EnabledPair(String asset, String subVerb, String defaultQuoteCurrency) {}
}
