package app.zcat.infochat.provider.digest;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.testing.TestLlmProvider;
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
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end digest lifecycle: scheduler fires slot, worker generates
 * prose, cache hit/miss by subscription version, degraded fallback on
 * LLM failure, /retry --digest replaces degraded, and concurrent retry
 * serialization. Exercises the full cross-cutting path that the
 * per-class unit tests in M1-080a/b/c cannot verify in isolation.
 */
@QuarkusTest
@TestProfile(DigestRoundtripIT.Profile.class)
class DigestRoundtripIT {

    static final UUID GROUP_1 = UUID.fromString("d1e50001-0001-4000-8000-000000000001");
    static final UUID GROUP_2 = UUID.fromString("d1e50002-0002-4000-8000-000000000002");
    static final UUID USER_1 = UUID.fromString("d1e50003-0003-4000-8000-000000000003");
    static final UUID SOURCE_1 = UUID.fromString("d1e50004-0004-4000-8000-000000000004");
    static final UUID POST_1 = UUID.fromString("d1e50005-0005-4000-8000-000000000005");
    // Pending group (M1-129): approval_status='pending', otherwise fully
    // subscribed like GROUP_1, so the scheduler's approval filter is the
    // only thing keeping it from receiving a digest.
    static final UUID GROUP_3 = UUID.fromString("d1e50006-0006-4000-8000-000000000006");

    static final String UPSTREAM_G1 = "digest-it-g1";
    static final String UPSTREAM_G2 = "digest-it-g2";
    static final String UPSTREAM_G3 = "digest-it-g3";
    static final String ADMIN_CONTACT = "digest-it-admin";

    // Window width from Profile: 60 minutes. Morning center hour: 0.
    // Computed dynamically in the test so windowEnd is always in the future.
    static final int WINDOW_HALF = 30;

    @Inject DataSource dataSource;
    @Inject InMemoryAdapter adapter;
    @Inject DigestScheduler scheduler;
    @Inject DigestWorker worker;
    @Inject SummaryCacheRepository cacheRepository;
    @Inject DigestRetryService retryService;
    @Inject BundleLoader bundleLoader;
    @Inject TestLlmProvider testLlmProvider;

    @BeforeEach
    void setUp() throws Exception {
        adapter.reset();
        testLlmProvider.reset();
        try (Connection conn = dataSource.getConnection()) {
            cleanTestData(conn);
            seedTestData(conn);
        }
    }

    @Test
    void digestLifecycleRoundtrip() throws Exception {
        testLlmProvider.setResponseText("Full prose digest summary.");

        // Dynamic window computation: use a morning slot 24h in the future
        // so the windowEnd is guaranteed to be after the real clock.
        ZonedDateTime futureMidnight = Instant.now().atZone(ZoneOffset.UTC)
                .toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC);
        Instant morningWindowStart = futureMidnight.minusMinutes(WINDOW_HALF).toInstant();
        Instant morningWindowEnd = futureMidnight.plusMinutes(WINDOW_HALF).toInstant();
        // Tick one second before window end — past any stagger offset
        Instant morningTick = morningWindowEnd.minusSeconds(1);

        // Seed the post with published_at inside the morning window
        updatePostPublishedAt(morningWindowStart.plusSeconds(60));

        // ---- Step (a): scheduler fires slot for group with active subscriptions ----
        scheduler.tickAt(morningTick);

        var cacheA = readCacheRow(GROUP_1, "morning", morningWindowStart);
        assertTrue(cacheA.isPresent(), "Step (a): summary_cache row must exist for group 1");
        assertFalse(cacheA.get().isDegraded(), "Step (a): must not be degraded");
        assertFalse(cacheA.get().content().isEmpty(), "Step (a): content must be non-empty");

        // ---- Step (b): digest message delivered to group ----
        var group1Messages = sentToGroup(UPSTREAM_G1);
        assertEquals(1, group1Messages.size(), "Step (b): exactly one message to group 1");
        assertEquals(cacheA.get().content(), group1Messages.get(0).text(),
                "Step (b): delivered body must match cached content");

        // ---- Step (c): zero-eligible-posts produces fixed reply ----
        String noPostsText = bundleLoader.get(BundleKeys.REPLY_SUMMARY_NO_POSTS_YET, "en");
        var group2Messages = sentToGroup(UPSTREAM_G2);
        assertEquals(1, group2Messages.size(), "Step (c): exactly one message to group 2");
        assertEquals(noPostsText, group2Messages.get(0).text(),
                "Step (c): group 2 must receive 'no posts yet'");
        var cacheC = readCacheRow(GROUP_2, "morning", morningWindowStart);
        assertTrue(cacheC.isPresent(), "Step (c): cache row must exist for group 2");
        assertEquals(noPostsText, cacheC.get().content(),
                "Step (c): cache content must match fixed reply");

        // ---- Step (c2): pending group receives no digest (M1-129) ----
        // GROUP_3 is approval_status='pending' but otherwise subscribed
        // exactly like GROUP_1 (same source, same eligible post). The
        // scheduler's approval_status filter must exclude it, so no slot
        // fires, no summary_cache row is written, and no message is
        // delivered — the negative path the original fixture never seeded.
        assertTrue(sentToGroup(UPSTREAM_G3).isEmpty(),
                "Step (c2): pending group must receive no digest delivery");
        assertTrue(readCacheRow(GROUP_3, "morning", morningWindowStart).isEmpty(),
                "Step (c2): pending group must have no summary_cache row");

        // ---- Step (d): scheduler slot deduplication (cache hit) ----
        int llmCountAfterMorning = testLlmProvider.callCount();
        int messagesAfterMorning = sentToGroup(UPSTREAM_G1).size();

        // Re-fire for the same morning slot — existsByGroupAndSlot
        // guard must prevent re-execution.
        scheduler.tickAt(morningTick);

        assertEquals(llmCountAfterMorning, testLlmProvider.callCount(),
                "Step (d): LLM call count must NOT increment on duplicate slot");
        assertEquals(messagesAfterMorning, sentToGroup(UPSTREAM_G1).size(),
                "Step (d): no new message delivered on duplicate slot");

        // ---- Step (e): subscription-version capture on new slot ----
        updateTagSubscriptionVersion(GROUP_1, 2);
        int llmBefore = testLlmProvider.callCount();

        // Evening slot: center=12:00 UTC (Profile config), same day as morning.
        // Move the post's published_at into the evening window so the
        // collector picks it up — the morning window is 12h earlier.
        ZonedDateTime futureNoon = futureMidnight.plusHours(12);
        Instant eveningWindowStart = futureNoon.minusMinutes(WINDOW_HALF).toInstant();
        Instant eveningWindowEnd = futureNoon.plusMinutes(WINDOW_HALF).toInstant();
        Instant eveningTick = eveningWindowEnd.minusSeconds(1);
        updatePostPublishedAt(eveningWindowStart.plusSeconds(60));

        scheduler.tickAt(eveningTick);

        var cacheE = readCacheRow(GROUP_1, "evening", eveningWindowStart);
        assertTrue(cacheE.isPresent(), "Step (e): new evening cache entry must exist");
        assertTrue(testLlmProvider.callCount() > llmBefore,
                "Step (e): LLM must be called for fresh generation");
        assertEquals(2, cacheE.get().tagSubscriptionVersion(),
                "Step (e): cache must reflect updated subscription version");

        // ---- Step (f): degraded fallback ----
        // DigestWorker sets is_degraded=true when remaining ≤ 0 (window
        // expired). Trigger it with a past windowEnd. slot_fired_at must
        // be the latest GROUP_1 entry so the retry in step (g) targets it.
        Instant degradedFiredAt = eveningWindowStart.plusSeconds(3600);
        updatePostPublishedAt(degradedFiredAt.plusSeconds(60));
        Instant degradedWindowEnd = Instant.now().minusSeconds(1);
        DigestSlot degradedSlot = new DigestSlot(
                GROUP_1, "UTC", "morning", degradedFiredAt, degradedWindowEnd);
        worker.execute(degradedSlot);

        var cacheF = readCacheRow(GROUP_1, "morning", degradedFiredAt);
        assertTrue(cacheF.isPresent(), "Step (f): degraded cache entry must exist");
        assertTrue(cacheF.get().isDegraded(), "Step (f): is_degraded must be true");
        assertTrue(cacheF.get().content().contains("Test Digest Post"),
                "Step (f): degraded content must contain post headline");
        assertFalse(cacheF.get().content().contains("Full prose"),
                "Step (f): degraded content must not contain LLM prose");
        List<OutboundMessage> afterF = sentToGroup(UPSTREAM_G1);
        boolean hasDegradedDelivery = afterF.stream()
                .anyMatch(m -> m.text().contains("Test Digest Post")
                        && !m.text().contains("Full prose"));
        assertTrue(hasDegradedDelivery, "Step (f): degraded digest must be delivered to group");

        // Patch expires_at to future so the retry in step (g) gets a
        // viable windowEnd — DigestRetryService reads expires_at as the
        // synthetic slot's windowEnd.
        Instant retryWindowEnd = Instant.now().plusSeconds(600);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE summary_cache SET expires_at = ?"
                             + " WHERE group_id = ? AND slot_kind = 'morning'"
                             + " AND slot_fired_at = ?")) {
            ps.setTimestamp(1, Timestamp.from(retryWindowEnd));
            ps.setObject(2, GROUP_1);
            ps.setTimestamp(3, Timestamp.from(degradedFiredAt));
            ps.executeUpdate();
        }

        // ---- Step (g): /retry --digest replaces degraded with full prose ----
        testLlmProvider.setResponseText("Fresh retry full prose.");
        int sentBefore = adapter.sentMessages().size();
        adapter.deliverGroupMention(UPSTREAM_G1, ADMIN_CONTACT, "/retry --digest");

        var cacheG = readCacheRow(GROUP_1, "morning", degradedFiredAt);
        assertTrue(cacheG.isPresent(), "Step (g): cache entry must exist after retry");
        assertFalse(cacheG.get().isDegraded(), "Step (g): is_degraded must be false after retry");
        assertTrue(cacheG.get().content().contains("Fresh retry"),
                "Step (g): cache must contain full prose from retry");

        var newMessages = adapter.sentMessages().subList(sentBefore, adapter.sentMessages().size());
        String successText = bundleLoader.get(BundleKeys.REPLY_RETRY_DIGEST_SUCCESS);
        boolean hasSuccessReply = newMessages.stream()
                .anyMatch(m -> m.text().contains(successText));
        assertTrue(hasSuccessReply, "Step (g): success reply must be sent");

        // ---- Step (h): /retry --digest serialization ----
        var barrier = new CyclicBarrier(2);
        var results = new CopyOnWriteArrayList<DigestRetryService.RetryResult>();

        Thread t1 = Thread.startVirtualThread(() -> {
            try {
                barrier.await();
                results.add(retryService.retryDigest(GROUP_1));
            } catch (Exception ignored) {
                // barrier interrupt — not under test
            }
        });
        Thread t2 = Thread.startVirtualThread(() -> {
            try {
                barrier.await();
                results.add(retryService.retryDigest(GROUP_1));
            } catch (Exception ignored) {
                // barrier interrupt — not under test
            }
        });

        t1.join(10_000);
        t2.join(10_000);

        assertEquals(2, results.size(), "Step (h): both threads must complete");
        assertTrue(results.contains(DigestRetryService.RetryResult.ALREADY_IN_PROGRESS),
                "Step (h): one retry must be rejected as already in progress. Got: " + results);
    }

    // -- helpers ----------------------------------------------------------------

    /**
     * Reads a summary_cache row without the expires_at TTL filter that
     * {@link SummaryCacheRepository#findByGroupAndSlot} applies. The
     * scheduler tick uses a future date's window, so expires_at may not
     * have passed yet — but this helper ensures the test is robust to
     * timing regardless.
     */
    private Optional<CacheRow> readCacheRow(UUID groupId, String slotKind,
                                            Instant slotFiredAt) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT content, is_degraded, tag_subscription_version,"
                             + " source_subscription_version"
                             + " FROM summary_cache"
                             + " WHERE group_id = ? AND slot_kind = ? AND slot_fired_at = ?"
                             + " ORDER BY created_at DESC LIMIT 1")) {
            ps.setObject(1, groupId);
            ps.setString(2, slotKind);
            ps.setTimestamp(3, Timestamp.from(slotFiredAt));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new CacheRow(
                        rs.getString("content"),
                        rs.getBoolean("is_degraded"),
                        rs.getLong("tag_subscription_version"),
                        rs.getLong("source_subscription_version")));
            }
        }
    }

    record CacheRow(String content, boolean isDegraded,
                    long tagSubscriptionVersion, long sourceSubscriptionVersion) {}

    private List<OutboundMessage> sentToGroup(String upstreamGroupId) {
        return adapter.sentMessages().stream()
                .filter(m -> m.scope() instanceof ScopeRef.Group g
                        && g.adapterGroupId().equals(upstreamGroupId))
                .toList();
    }

    private void updateTagSubscriptionVersion(UUID groupId, long version) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE scope_preferences SET tag_subscription_version = ?"
                             + " WHERE scope_kind = 'group' AND scope_id = ?")) {
            ps.setLong(1, version);
            ps.setObject(2, groupId);
            ps.executeUpdate();
        }
    }

    private void updatePostPublishedAt(Instant publishedAt) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE post SET published_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, Timestamp.from(publishedAt));
            ps.setObject(2, POST_1);
            ps.executeUpdate();
        }
    }

    private void seedTestData(Connection conn) throws SQLException {
        exec(conn,
                "INSERT INTO source (id, kind, identifier, display_name, category, status)"
                        + " VALUES (?, 'rss', 'http://digest-roundtrip-it.example.com/feed',"
                        + " 'DigestIT Source', 'news', 'active')"
                        + " ON CONFLICT (kind, identifier) DO UPDATE SET id = EXCLUDED.id",
                SOURCE_1);

        exec(conn,
                "INSERT INTO users (id, adapter, contact_id, display_name,"
                        + " is_admin, registration_state)"
                        + " VALUES (?, 'inmemory', ?, 'Digest IT Admin', TRUE, 'vouched')"
                        + " ON CONFLICT (adapter, contact_id)"
                        + " DO UPDATE SET id = EXCLUDED.id, is_admin = TRUE, is_banned = FALSE",
                USER_1, ADMIN_CONTACT);

        // approval_status='approved' (M1-112): bypass the D47 step-3.5
        // gate so /retry --digest reaches dispatch. ON CONFLICT
        // overwrites the column so a row carried over from a prior
        // test run is also normalized to approved.
        exec(conn,
                "INSERT INTO groups (id, adapter, upstream_group_id, display_name, timezone, approval_status)"
                        + " VALUES (?, 'inmemory', ?, 'Digest IT Group 1', 'UTC', 'approved')"
                        + " ON CONFLICT (adapter, upstream_group_id)"
                        + " DO UPDATE SET id = EXCLUDED.id, removed_at = NULL, approval_status = 'approved'",
                GROUP_1, UPSTREAM_G1);
        exec(conn,
                "INSERT INTO groups (id, adapter, upstream_group_id, display_name, timezone, approval_status)"
                        + " VALUES (?, 'inmemory', ?, 'Digest IT Group 2', 'UTC', 'approved')"
                        + " ON CONFLICT (adapter, upstream_group_id)"
                        + " DO UPDATE SET id = EXCLUDED.id, removed_at = NULL, approval_status = 'approved'",
                GROUP_2, UPSTREAM_G2);

        // Pending group (M1-129): seeded as a full GROUP_1 twin except for
        // approval_status='pending'. ON CONFLICT re-normalizes a carried-over
        // row back to pending so the negative assertion stays meaningful.
        exec(conn,
                "INSERT INTO groups (id, adapter, upstream_group_id, display_name, timezone, approval_status)"
                        + " VALUES (?, 'inmemory', ?, 'Digest IT Group 3', 'UTC', 'pending')"
                        + " ON CONFLICT (adapter, upstream_group_id)"
                        + " DO UPDATE SET id = EXCLUDED.id, removed_at = NULL, approval_status = 'pending'",
                GROUP_3, UPSTREAM_G3);

        exec(conn,
                "INSERT INTO group_membership (group_id, user_id, is_group_admin)"
                        + " VALUES (?, ?, TRUE)"
                        + " ON CONFLICT (group_id, user_id)"
                        + " DO UPDATE SET is_group_admin = TRUE, removed_at = NULL",
                GROUP_1, USER_1);

        exec(conn,
                "INSERT INTO group_membership (group_id, user_id, is_group_admin)"
                        + " VALUES (?, ?, TRUE)"
                        + " ON CONFLICT (group_id, user_id)"
                        + " DO UPDATE SET is_group_admin = TRUE, removed_at = NULL",
                GROUP_3, USER_1);

        exec(conn,
                "INSERT INTO scope_preferences (scope_kind, scope_id, tag_mode,"
                        + " tag_subscription_version, source_subscription_version)"
                        + " VALUES ('group', ?, 'ALL', 1, 1)"
                        + " ON CONFLICT (scope_kind, scope_id)"
                        + " DO UPDATE SET tag_mode = 'ALL',"
                        + " tag_subscription_version = 1, source_subscription_version = 1",
                GROUP_1);

        exec(conn,
                "INSERT INTO scope_preferences (scope_kind, scope_id, tag_mode,"
                        + " tag_subscription_version, source_subscription_version)"
                        + " VALUES ('group', ?, 'ALL', 1, 1)"
                        + " ON CONFLICT (scope_kind, scope_id)"
                        + " DO UPDATE SET tag_mode = 'ALL',"
                        + " tag_subscription_version = 1, source_subscription_version = 1",
                GROUP_3);

        exec(conn,
                "INSERT INTO source_subscription (scope_kind, scope_id, source_id)"
                        + " VALUES ('group', ?, ?)"
                        + " ON CONFLICT DO NOTHING",
                GROUP_1, SOURCE_1);

        exec(conn,
                "INSERT INTO source_subscription (scope_kind, scope_id, source_id)"
                        + " VALUES ('group', ?, ?)"
                        + " ON CONFLICT DO NOTHING",
                GROUP_3, SOURCE_1);

        // published_at is updated dynamically in the test to match the window
        Instant seedTime = Instant.parse("2026-05-26T06:00:00Z");
        exec(conn,
                "INSERT INTO post (id, uid, source_id, title, body, status,"
                        + " published_at, fetched_at, ready_at, tags,"
                        + " stage1_done, stage2_done, tagger_done, embedding_done)"
                        + " VALUES (?, 'p-digestit-1', ?, 'Test Digest Post',"
                        + " 'Test post body.', 'READY', ?, ?, ?, ARRAY['crypto']::TEXT[],"
                        + " TRUE, TRUE, TRUE, TRUE)"
                        + " ON CONFLICT (id, fetched_at) DO NOTHING",
                POST_1, SOURCE_1, Timestamp.from(seedTime),
                Timestamp.from(seedTime), Timestamp.from(seedTime));
    }

    private void cleanTestData(Connection conn) throws SQLException {
        exec(conn, "DELETE FROM summary_cache WHERE group_id IN (?, ?, ?)", GROUP_1, GROUP_2, GROUP_3);
        exec(conn, "DELETE FROM source_subscription WHERE scope_id IN (?, ?, ?)", GROUP_1, GROUP_2, GROUP_3);
        exec(conn, "DELETE FROM scope_tag WHERE scope_id IN (?, ?, ?)", GROUP_1, GROUP_2, GROUP_3);
        exec(conn, "DELETE FROM scope_preferences WHERE scope_id IN (?, ?, ?)", GROUP_1, GROUP_2, GROUP_3);
        exec(conn,
                "DELETE FROM audit_log WHERE scope_id IN (?, ?, ?)"
                        + " OR (actor_user_id = ? AND action IN ('DIGEST_RETRY', 'DIGEST_SLOT_MISSED'))",
                GROUP_1, GROUP_2, GROUP_3, USER_1);
        exec(conn, "DELETE FROM group_membership WHERE group_id IN (?, ?, ?)", GROUP_1, GROUP_2, GROUP_3);
        exec(conn, "DELETE FROM post WHERE id = ?", POST_1);
    }

    private static void exec(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true",
                    "infochat.digest.morning-slot-hour", "0",
                    "infochat.digest.evening-slot-hour", "12",
                    "infochat.digest.window-width-minutes", "60",
                    "infochat.digest.retry-cooldown", "PT0S"
            );
        }
    }
}
