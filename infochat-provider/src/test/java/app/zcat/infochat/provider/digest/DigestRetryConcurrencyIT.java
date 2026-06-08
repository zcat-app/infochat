package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.digest.DigestRetryService.RetryResult;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration coverage for the {@code /retry --digest} atomicity contract
 * (M1-232) against a real database and a real {@link DigestWorker}:
 *
 * <ul>
 *   <li>When a concurrent run already holds the worker's in-flight guard,
 *       the retry leaves the cached digest intact and returns
 *       {@code ALREADY_IN_PROGRESS} — never SUCCESS.</li>
 *   <li>The normal retry path overwrites the cache row in place via the
 *       worker's atomic UPSERT (exercising the V46 {@code GRANT UPDATE} under
 *       the weak {@code infochat_provider} role the worker connects as) and
 *       returns SUCCESS.</li>
 * </ul>
 *
 * The cache rows are seeded and read through the owner-role seam
 * ({@code @SeedDataSource}); the worker's own writes go through the default
 * service-role datasource, which is the path the grant must cover.
 */
@QuarkusTest
@TestProfile(DigestRetryConcurrencyIT.Profile.class)
class DigestRetryConcurrencyIT {

    static final UUID GROUP = UUID.fromString("d1e52320-0001-4000-8000-000000000001");
    static final String UPSTREAM_GROUP = "retry-concurrency-it-g1";
    static final String SLOT_KIND = "morning";
    static final Instant SLOT_FIRED_AT = Instant.parse("2026-06-01T00:00:00Z");

    @Inject DigestWorker worker;
    @Inject DigestRetryService retryService;
    @Inject BundleLoader bundleLoader;
    @Inject @SeedDataSource DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM summary_cache WHERE group_id = ?", GROUP);
            exec(conn, "DELETE FROM scope_preferences WHERE scope_id = ?", GROUP);
            exec(conn, "DELETE FROM groups WHERE id = ?", GROUP);
            exec(conn,
                    "INSERT INTO groups (id, adapter, upstream_group_id, display_name,"
                            + " timezone, approval_status)"
                            + " VALUES (?, 'inmemory', ?, 'Retry Concurrency IT Group', 'UTC', 'approved')",
                    GROUP, UPSTREAM_GROUP);
            exec(conn,
                    "INSERT INTO scope_preferences (scope_kind, scope_id, tag_mode,"
                            + " tag_subscription_version, source_subscription_version)"
                            + " VALUES ('group', ?, 'ALL', 1, 1)",
                    GROUP);
        }
    }

    @Test
    void retryWhileSlotInFlight_leavesCacheIntact_returnsAlreadyInProgress() throws Exception {
        String original = "original morning digest";
        seedCacheRow(original, false);

        // A concurrent scheduled run for the same (group, slotKind) owns the
        // worker's in-flight guard. Reach the real bean behind the Arc proxy
        // and hold its guard key for the duration of the retry.
        DigestWorker realWorker = ClientProxy.unwrap(worker);
        Set<String> inFlightSlots = inFlightSlots(realWorker);
        String inFlightKey = GROUP + ":" + SLOT_KIND;
        inFlightSlots.add(inFlightKey);
        try {
            RetryResult result = retryService.retryDigest(GROUP);

            assertEquals(RetryResult.ALREADY_IN_PROGRESS, result,
                    "a retry that loses to an in-flight run must not report SUCCESS");
        } finally {
            inFlightSlots.remove(inFlightKey);
        }

        Optional<CacheRow> row = readCacheRow();
        assertTrue(row.isPresent(), "the existing cache row must still exist after a skipped retry");
        assertEquals(original, row.get().content(),
                "the cached digest must be left byte-for-byte intact when the retry is skipped");
        assertFalse(row.get().isDegraded(), "the cached degraded flag must be untouched");
        assertEquals(1, rowCount(), "no duplicate cache row may be created");
    }

    @Test
    void normalRetry_overwritesCacheRowViaUpsert_returnsSuccess() throws Exception {
        seedCacheRow("STALE ORIGINAL", true);

        // No posts are subscribed, so the worker regenerates the fixed
        // no-posts reply — enough to prove the row is overwritten in place
        // through the service-role UPSERT (V46 grant) rather than deleted.
        RetryResult result = retryService.retryDigest(GROUP);

        assertEquals(RetryResult.SUCCESS, result, "a normal retry must report SUCCESS");

        Optional<CacheRow> row = readCacheRow();
        assertTrue(row.isPresent(), "the regenerated cache row must exist");
        assertNotEquals("STALE ORIGINAL", row.get().content(),
                "the stale content must be overwritten by the regeneration");
        assertEquals(bundleLoader.get(BundleKeys.REPLY_SUMMARY_NO_POSTS_YET, "en"),
                row.get().content(),
                "the regenerated content must be the fresh no-posts reply");
        assertFalse(row.get().isDegraded(), "the no-posts reply is not a degraded result");
        assertEquals(1, rowCount(),
                "the UPSERT must overwrite the existing row, not insert a duplicate");
    }

    // -- helpers ----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Set<String> inFlightSlots(DigestWorker realWorker) throws Exception {
        Field field = DigestWorker.class.getDeclaredField("inFlightSlots");
        field.setAccessible(true);
        return (Set<String>) field.get(realWorker);
    }

    private void seedCacheRow(String content, boolean isDegraded) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO summary_cache"
                             + " (group_id, slot_kind, slot_fired_at,"
                             + "  tag_subscription_version, source_subscription_version,"
                             + "  content, is_degraded, expires_at)"
                             + " VALUES (?, ?, ?, 1, 1, ?, ?, ?)")) {
            ps.setObject(1, GROUP);
            ps.setString(2, SLOT_KIND);
            ps.setTimestamp(3, Timestamp.from(SLOT_FIRED_AT));
            ps.setString(4, content);
            ps.setBoolean(5, isDegraded);
            ps.setTimestamp(6, Timestamp.from(Instant.now().plusSeconds(3600)));
            ps.executeUpdate();
        }
    }

    private Optional<CacheRow> readCacheRow() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT content, is_degraded FROM summary_cache"
                             + " WHERE group_id = ? AND slot_kind = ? AND slot_fired_at = ?")) {
            ps.setObject(1, GROUP);
            ps.setString(2, SLOT_KIND);
            ps.setTimestamp(3, Timestamp.from(SLOT_FIRED_AT));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new CacheRow(rs.getString("content"), rs.getBoolean("is_degraded")));
            }
        }
    }

    private int rowCount() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM summary_cache"
                             + " WHERE group_id = ? AND slot_kind = ? AND slot_fired_at = ?")) {
            ps.setObject(1, GROUP);
            ps.setString(2, SLOT_KIND);
            ps.setTimestamp(3, Timestamp.from(SLOT_FIRED_AT));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void exec(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    record CacheRow(String content, boolean isDegraded) {}

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true",
                    "infochat.digest.retry-cooldown", "PT0S"
            );
        }
    }
}
