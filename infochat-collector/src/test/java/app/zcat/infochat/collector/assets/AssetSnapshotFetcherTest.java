package app.zcat.infochat.collector.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import app.zcat.infochat.collector.assets.source.AssetDataSource.FetchException;
import app.zcat.infochat.collector.assets.source.BitfinexSnapshotSource;
import app.zcat.infochat.collector.assets.source.CoingeckoSnapshotSource;
import app.zcat.infochat.collector.assets.source.KrakenSnapshotSource;
import app.zcat.infochat.collector.notifier.AdminNotificationRecord;
import app.zcat.infochat.collector.notifier.ThrottledAdminNotifier;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Integration test for {@link AssetSnapshotFetcher} — the per-host
 * tick loop, the D42 failure-counter state machine, the threshold
 * breach → {@code asset_config.status='failed'} + single throttled
 * admin notification path, and the {@code enabled=false} skip path.
 *
 * <h2>Fixture shape</h2>
 *
 * <p>{@link QuarkusMock#installMockForType} swaps each concrete
 * {@code AssetDataSource} bean with a stub subclass that records call
 * counts and emits configurable responses. The stubs share the
 * static {@code snapshotResponse} / {@code exceptionResponse} pair
 * below; each test's {@code @BeforeEach} resets all counters and
 * responses, then seeds {@code asset_config}.
 *
 * <h2>Tick invocation</h2>
 *
 * <p>{@code %test.quarkus.scheduler.start-mode=halted} prevents
 * background {@code @Scheduled} ticks; the tests invoke
 * {@code AssetSnapshotFetcher.runHostTick} directly to drive the
 * loop deterministically.
 */
@QuarkusTest
class AssetSnapshotFetcherTest {

    private static final BigDecimal PRICE = new BigDecimal("100.0");

    // Per-test stub state. Held as test-class statics rather than a
    // separate inner holder type so the inner-class count stays at
    // three (FakeCoingecko / FakeKraken / FakeBitfinex), matching
    // the test_doubles memory's >3-inner-class rule of thumb.
    static final AtomicInteger coingeckoCalls = new AtomicInteger();
    static final AtomicInteger krakenCalls = new AtomicInteger();
    static final AtomicInteger bitfinexCalls = new AtomicInteger();
    static final AtomicReference<PriceSnapshot> snapshotResponse = new AtomicReference<>();
    static final AtomicReference<FetchException> exceptionResponse = new AtomicReference<>();

    @Inject
    DataSource dataSource;

    @Inject
    AssetSnapshotFetcher fetcher;

    @Inject
    ThrottledAdminNotifier notifier;

    @BeforeEach
    void reset() throws Exception {
        coingeckoCalls.set(0);
        krakenCalls.set(0);
        bitfinexCalls.set(0);
        snapshotResponse.set(null);
        exceptionResponse.set(null);
        installMocks();
        truncate("asset_config");
        truncate("price_snapshot");
        truncate("admin_notification_state");
        ClientProxy.unwrap(fetcher).resetSourceCacheForTest();
    }

    @Test
    void happyPathStoresSnapshot() throws Exception {
        seedAssetConfig("zcash", "coingecko", true, "usd", true, "active");
        Instant capturedAt = Instant.parse("2026-05-15T10:00:00Z");
        snapshotResponse.set(snapshot("zcash", "coingecko", "usd", capturedAt));

        fetcher.runHostTick("coingecko");

        int rowCount = countSnapshots("zcash", "coingecko");
        assertEquals(1, rowCount, "happy path must INSERT exactly one price_snapshot row");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT consecutive_failures, last_success_at, last_failure_at, status "
                     + "FROM asset_config WHERE asset = 'zcash' AND sub_verb = 'coingecko'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "asset_config row must exist after seed");
            assertEquals(0, rs.getInt("consecutive_failures"),
                "consecutive_failures must reset to 0 on success");
            assertNotNull(rs.getTimestamp("last_success_at"),
                "last_success_at must be set on success");
            assertEquals("active", rs.getString("status"),
                "status must remain 'active' on success");
        }
    }

    @Test
    void fetchExceptionIncrementsCounter() throws Exception {
        seedAssetConfig("zcash", "coingecko", true, "usd", true, "active");
        exceptionResponse.set(new FetchException("simulated network failure"));

        fetcher.runHostTick("coingecko");

        int rowCount = countSnapshots("zcash", "coingecko");
        assertEquals(0, rowCount, "no price_snapshot row may be written on FetchException");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT consecutive_failures, last_success_at, last_failure_at, status "
                     + "FROM asset_config WHERE asset = 'zcash' AND sub_verb = 'coingecko'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("consecutive_failures"),
                "consecutive_failures must increment by 1 on FetchException");
            assertNotNull(rs.getTimestamp("last_failure_at"),
                "last_failure_at must be set on failure");
            assertNull(rs.getTimestamp("last_success_at"),
                "last_success_at must remain NULL on a failure-only run");
            assertEquals("active", rs.getString("status"),
                "status must remain 'active' before threshold breach");
        }
    }

    @Test
    void failureCounterThresholdBreach() throws Exception {
        seedAssetConfig("zcash", "coingecko", true, "usd", true, "active");
        exceptionResponse.set(new FetchException("simulated upstream outage"));

        // Default threshold per AssetSnapshotFetcher.failureThreshold
        // = 5. Five consecutive failing ticks must trip the
        // active → failed transition and fire exactly ONE notifyOnce.
        for (int i = 0; i < 5; i++) {
            fetcher.runHostTick("coingecko");
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status, consecutive_failures FROM asset_config "
                     + "WHERE asset = 'zcash' AND sub_verb = 'coingecko'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("failed", rs.getString("status"),
                "status must flip to 'failed' once consecutive_failures reaches the threshold");
            assertEquals(5, rs.getInt("consecutive_failures"),
                "consecutive_failures must equal 5 after 5 failing ticks");
        }

        String key = "asset-source-failed:zcash:coingecko";
        Optional<AdminNotificationRecord> record = notifier.getState(key);
        assertTrue(record.isPresent(),
            "notifier must have recorded a notification for key=" + key);
        assertEquals(1L, record.get().notificationCount(),
            "exactly one notifyOnce per active→failed transition");

        // Sixth tick: status='failed' filters the row out of
        // enumerateEnabled, so no further bumps and no second
        // notification. Pins the invariant against future regression
        // where a status guard accidentally drops out.
        fetcher.runHostTick("coingecko");
        AdminNotificationRecord recordAfterSixth = notifier.getState(key).orElseThrow();
        assertEquals(1L, recordAfterSixth.notificationCount(),
            "a tick after 'failed' must NOT re-fire notifyOnce; status='failed' filters the row out");
    }

    @Test
    void perHostScheduling() throws Exception {
        seedAssetConfig("zcash", "coingecko", true, "usd", true, "active");
        seedAssetConfig("zcash", "kraken", true, "usd", false, "active");
        seedAssetConfig("zcash", "bitfinex", true, "usd", false, "active");
        Instant capturedAt = Instant.parse("2026-05-15T10:00:00Z");
        snapshotResponse.set(snapshot("zcash", "coingecko", "usd", capturedAt));

        fetcher.runHostTick("coingecko");

        // Only the matching-host stub is invoked. Other hosts'
        // call counts remain 0 — the spec invariant that each host's
        // tick is independent.
        assertEquals(1, coingeckoCalls.get(),
            "coingecko stub must be called exactly once on a coingecko tick");
        assertEquals(0, krakenCalls.get(),
            "kraken stub must NOT be called on a coingecko tick");
        assertEquals(0, bitfinexCalls.get(),
            "bitfinex stub must NOT be called on a coingecko tick");
    }

    @Test
    void disabledRowSkipped() throws Exception {
        // zcash:coingecko ENABLED — should fire. monero:coingecko
        // DISABLED — must be skipped at enumeration time.
        seedAssetConfig("zcash", "coingecko", true, "usd", true, "active");
        seedAssetConfig("monero", "coingecko", false, "usd", true, "active");
        Instant capturedAt = Instant.parse("2026-05-15T10:00:00Z");
        snapshotResponse.set(snapshot("zcash", "coingecko", "usd", capturedAt));

        fetcher.runHostTick("coingecko");

        assertEquals(1, coingeckoCalls.get(),
            "fetchSnapshot must be called for enabled rows only");
        int monRows = countSnapshots("monero", "coingecko");
        assertEquals(0, monRows,
            "no price_snapshot row may be written for the disabled monero:coingecko row");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT consecutive_failures, last_success_at, last_failure_at "
                     + "FROM asset_config WHERE asset = 'monero' AND sub_verb = 'coingecko'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("consecutive_failures"),
                "the disabled row's consecutive_failures must remain at its seeded value (0)");
            assertNull(rs.getTimestamp("last_success_at"),
                "the disabled row must not have last_success_at touched");
            assertNull(rs.getTimestamp("last_failure_at"),
                "the disabled row must not have last_failure_at touched");
        }
    }

    // ---------- mocks ----------

    private static void installMocks() {
        QuarkusMock.installMockForType(new FakeCoingecko(), CoingeckoSnapshotSource.class);
        QuarkusMock.installMockForType(new FakeKraken(), KrakenSnapshotSource.class);
        QuarkusMock.installMockForType(new FakeBitfinex(), BitfinexSnapshotSource.class);
    }

    private static PriceSnapshot resolveStubResponse() throws FetchException {
        FetchException ex = exceptionResponse.get();
        if (ex != null) {
            throw ex;
        }
        PriceSnapshot snap = snapshotResponse.get();
        if (snap == null) {
            throw new FetchException("AssetSnapshotFetcherTest: no stub response configured");
        }
        return snap;
    }

    // ---------- seed / read / cleanup ----------

    private void seedAssetConfig(String asset, String subVerb, boolean enabled,
                                  String defaultQuote, boolean isDefault, String status)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO asset_config ("
                     + "  asset, sub_verb, enabled, default_quote_currency,"
                     + "  attribution_url, is_default, status"
                     + ") VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, asset);
            ps.setString(2, subVerb);
            ps.setBoolean(3, enabled);
            ps.setString(4, defaultQuote);
            ps.setString(5, "https://example.test/" + subVerb + "/" + asset);
            ps.setBoolean(6, isDefault);
            ps.setString(7, status);
            ps.executeUpdate();
        }
    }

    private int countSnapshots(String asset, String subVerb) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count(*) FROM price_snapshot WHERE asset = ? AND sub_verb = ?")) {
            ps.setString(1, asset);
            ps.setString(2, subVerb);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private void truncate(String table) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("TRUNCATE " + table);
        }
    }

    private static PriceSnapshot snapshot(String asset, String subVerb, String vs,
                                           Instant capturedAt) {
        return new PriceSnapshot(
            asset, subVerb, vs, PRICE,
            null, null, null, null, null, null,
            capturedAt,
            "https://example.test/" + subVerb + "/" + asset,
            null);
    }

    // ---------- in-test stubs (3 inner classes — at the
    // test_doubles memory's >3 rule-of-thumb line, not over it).
    // Each is a subclass of the matching concrete CDI bean so
    // QuarkusMock can install it for that exact type; behavior
    // delegates to the test-class statics above.

    static final class FakeCoingecko extends CoingeckoSnapshotSource {
        @Override
        public PriceSnapshot fetchSnapshot(String asset, String vs) throws FetchException {
            coingeckoCalls.incrementAndGet();
            return resolveStubResponse();
        }
    }

    static final class FakeKraken extends KrakenSnapshotSource {
        @Override
        public PriceSnapshot fetchSnapshot(String asset, String vs) throws FetchException {
            krakenCalls.incrementAndGet();
            return resolveStubResponse();
        }
    }

    static final class FakeBitfinex extends BitfinexSnapshotSource {
        @Override
        public PriceSnapshot fetchSnapshot(String asset, String vs) throws FetchException {
            bitfinexCalls.incrementAndGet();
            return resolveStubResponse();
        }
    }
}
