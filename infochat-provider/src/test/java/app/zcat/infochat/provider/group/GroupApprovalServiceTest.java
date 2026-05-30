package app.zcat.infochat.provider.group;

import app.zcat.infochat.core.notifier.AdminNotificationRecord;
import app.zcat.infochat.core.notifier.NotifyOutcome;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.provider.bundle.BundleKeys;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit-test coverage for {@link GroupApprovalService}, exercising the
 * D47 step 3.5 decision logic against a real Flyway-migrated DataSource.
 *
 * <p>Scenarios mirror acceptance item 9:</p>
 * <ul>
 *   <li>(a) first registered @mention creates pending group + admin notification</li>
 *   <li>(b) second @mention in same pending group → pending reply, no re-notification</li>
 *   <li>(c) rejected group → rejected reply</li>
 *   <li>(d) approved group → Approved outcome (router falls through)</li>
 *   <li>(e) per-user activation cap exceeded → activation-limit reply</li>
 *   <li>(f) global max-groups cap exceeded → global-limit reply</li>
 *   <li>(g) concurrent INSERT race → loser re-reads existing row (stub-based)</li>
 * </ul>
 *
 * <p>Test isolation: every {@code @Test} uses an upstream group id
 * seeded with the test-run-scoped {@link #TEST_UPSTREAM_ID_PREFIX} so
 * cross-test cleanup is a single DELETE by LIKE. The @BeforeEach
 * cleanup runs against every prefix-matching row to keep cap counts
 * deterministic between scenarios.</p>
 */
@QuarkusTest
class GroupApprovalServiceTest {

    private static final String TEST_ADAPTER = "inmemory";
    private static final String TEST_UPSTREAM_ID_PREFIX =
            "approval-test-" + UUID.randomUUID() + "-";
    private static final String REDACTED_CONTACT = "abcdef12...wxyz";

    @Inject
    DataSource dataSource;

    @Inject
    GroupRepository groupRepository;

    @Inject
    GroupApprovalService service;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @ConfigProperty(name = "infochat.groups.per-user-activation-cap")
    int perUserActivationCap;

    @ConfigProperty(name = "infochat.groups.global-max-groups")
    int globalMaxGroups;

    private UUID activatorUserId;
    private UUID otherUserId;

    @BeforeEach
    void cleanAndSeed() throws Exception {
        activatorUserId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();
        try (Connection conn = dataSource.getConnection()) {
            // Wipe groups (and FK dependents) unconditionally so the
            // global/per-user cap counters start from zero — earlier
            // test classes in the same run leave behind rows that
            // would otherwise saturate the small %test
            // infochat.groups.global-max-groups cap and turn cases
            // (a)/(b) into spurious GROUP_GLOBAL_LIMIT outcomes.
            // Cascade-safe order: summary_cache → group_membership →
            // groups. Each later test class reseeds its own rows in
            // its own @BeforeEach, so this isolation does not
            // cascade.
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM summary_cache")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM group_membership")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM groups")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM admin_notification_state WHERE notification_key LIKE ?")) {
                ps.setString(1, "group-pending:%");
                ps.executeUpdate();
            }
            seedUser(conn, activatorUserId, "activator-" + activatorUserId);
            seedUser(conn, otherUserId, "other-" + otherUserId);
        }
    }

    @Test
    void firstMentionCreatesPendingGroupAndFiresAdminNotification() {
        String upstream = TEST_UPSTREAM_ID_PREFIX + "first";

        GroupApprovalCheck.Outcome outcome = service.evaluate(
                TEST_ADAPTER, upstream, activatorUserId, REDACTED_CONTACT);

        // Outcome is the GROUP_PENDING fixed reply.
        assertEquals(
                new GroupApprovalCheck.Outcome.FixedReply(BundleKeys.GROUP_PENDING),
                outcome);
        // Row exists with approval_status=pending and activated_by=our user.
        Optional<GroupRepository.GroupApprovalRow> row =
                groupRepository.findApprovalRow(TEST_ADAPTER, upstream);
        assertTrue(row.isPresent(), "expected groups row to exist after first @mention");
        assertEquals("pending", row.get().approvalStatus());
        assertEquals(activatorUserId, row.get().activatedBy());
        // Admin notification fired exactly once on the EMITTED branch.
        Optional<AdminNotificationRecord> notif = throttledAdminNotifier.getState(
                "group-pending:" + TEST_ADAPTER + ":" + upstream);
        assertTrue(notif.isPresent(), "expected admin_notification_state row");
        assertEquals(1L, notif.get().notificationCount());
        assertEquals(0L, notif.get().suppressedCount());
    }

    @Test
    void secondMentionInPendingGroupDoesNotReNotify() {
        String upstream = TEST_UPSTREAM_ID_PREFIX + "second";

        // First @mention creates the pending row + fires notification.
        service.evaluate(TEST_ADAPTER, upstream, activatorUserId, REDACTED_CONTACT);
        // Second @mention on the same pending group.
        GroupApprovalCheck.Outcome outcome = service.evaluate(
                TEST_ADAPTER, upstream, otherUserId, REDACTED_CONTACT);

        // Second outcome is still the pending reply.
        assertEquals(
                new GroupApprovalCheck.Outcome.FixedReply(BundleKeys.GROUP_PENDING),
                outcome);
        // Notification count remains 1 — the second @mention hits the
        // existing-row dispatch branch, never reaching notifyAdmin.
        Optional<AdminNotificationRecord> notif = throttledAdminNotifier.getState(
                "group-pending:" + TEST_ADAPTER + ":" + upstream);
        assertTrue(notif.isPresent());
        assertEquals(1L, notif.get().notificationCount());
    }

    @Test
    void rejectedGroupReturnsRejectedReply() throws Exception {
        String upstream = TEST_UPSTREAM_ID_PREFIX + "rejected";
        seedGroup(upstream, "rejected", activatorUserId);

        GroupApprovalCheck.Outcome outcome = service.evaluate(
                TEST_ADAPTER, upstream, otherUserId, REDACTED_CONTACT);

        assertEquals(
                new GroupApprovalCheck.Outcome.FixedReply(BundleKeys.GROUP_REJECTED),
                outcome);
    }

    @Test
    void approvedGroupReturnsApprovedOutcome() throws Exception {
        String upstream = TEST_UPSTREAM_ID_PREFIX + "approved";
        seedGroup(upstream, "approved", activatorUserId);

        GroupApprovalCheck.Outcome outcome = service.evaluate(
                TEST_ADAPTER, upstream, otherUserId, REDACTED_CONTACT);

        assertInstanceOf(GroupApprovalCheck.Outcome.Approved.class, outcome);
    }

    @Test
    void perUserCapExceededReturnsActivationLimit() throws Exception {
        // Seed the user up to the cap with pending groups.
        for (int i = 0; i < perUserActivationCap; i++) {
            seedGroup(TEST_UPSTREAM_ID_PREFIX + "user-cap-pre-" + i,
                    "pending", activatorUserId);
        }

        // The (cap+1)th activation attempt must be rejected by the
        // per-user cap; no new row is created.
        String upstream = TEST_UPSTREAM_ID_PREFIX + "user-cap-overflow";
        GroupApprovalCheck.Outcome outcome = service.evaluate(
                TEST_ADAPTER, upstream, activatorUserId, REDACTED_CONTACT);

        assertEquals(
                new GroupApprovalCheck.Outcome.FixedReply(BundleKeys.GROUP_ACTIVATION_LIMIT),
                outcome);
        assertTrue(groupRepository.findApprovalRow(TEST_ADAPTER, upstream).isEmpty(),
                "expected no row to be inserted on cap-exceeded path");
    }

    @Test
    void globalCapExceededReturnsGlobalLimit() throws Exception {
        // Self-correct for baseline rows that other tests in the same
        // run may have left behind: seed only enough fresh groups to
        // bring the live count up to the cap. UUID-suffixed contact_ids
        // and upstream ids stay collision-free across test runs.
        long baseline = groupRepository.countActiveGroups();
        long toSeed = Math.max(0L, globalMaxGroups - baseline);
        for (long i = 0; i < toSeed; i++) {
            UUID activator = UUID.randomUUID();
            try (Connection conn = dataSource.getConnection()) {
                seedUser(conn, activator, "global-cap-user-" + activator);
            }
            seedGroup(TEST_UPSTREAM_ID_PREFIX + "global-cap-pre-" + i,
                    "pending", activator);
        }

        // The (cap+1)th activation attempt must be rejected by the
        // global cap.
        String upstream = TEST_UPSTREAM_ID_PREFIX + "global-cap-overflow";
        GroupApprovalCheck.Outcome outcome = service.evaluate(
                TEST_ADAPTER, upstream, activatorUserId, REDACTED_CONTACT);

        assertEquals(
                new GroupApprovalCheck.Outcome.FixedReply(BundleKeys.GROUP_GLOBAL_LIMIT),
                outcome);
        assertTrue(groupRepository.findApprovalRow(TEST_ADAPTER, upstream).isEmpty(),
                "expected no row to be inserted on global-cap-exceeded path");
    }

    @Test
    void raceLoserReReadsExistingRowAndDispatches() {
        // Plain-JUnit construction with a stubbed GroupRepository that
        // simulates the rare TOCTOU race window: findApprovalRow returns
        // empty on the first call (no row yet), the cap-counters return
        // zero, tryInsertPending returns empty (the winner's INSERT
        // arrived between this thread's lookup and INSERT), then the
        // subsequent re-read findApprovalRow returns the winner's row.
        String upstream = TEST_UPSTREAM_ID_PREFIX + "race";
        UUID winnerRowId = UUID.randomUUID();
        GroupRepository.GroupApprovalRow winnerRow =
                new GroupRepository.GroupApprovalRow(
                        winnerRowId, "pending", UUID.randomUUID(), null);
        RaceLosingGroupRepository raceRepo = new RaceLosingGroupRepository(winnerRow);
        RecordingNoopNotifier notifier = new RecordingNoopNotifier();

        GroupApprovalService localService = new GroupApprovalService();
        localService.groupRepository = raceRepo;
        localService.throttledAdminNotifier = notifier;
        localService.perUserActivationCap = 100;
        localService.globalMaxGroups = 100;

        GroupApprovalCheck.Outcome outcome = localService.evaluate(
                TEST_ADAPTER, upstream, activatorUserId, REDACTED_CONTACT);

        // The race-loser branch re-read the winner's row and dispatched
        // on its approval_status ('pending' → GROUP_PENDING fixed reply).
        assertEquals(
                new GroupApprovalCheck.Outcome.FixedReply(BundleKeys.GROUP_PENDING),
                outcome);
        // tryInsertPending was invoked exactly once (the conflict) and
        // the follow-up findApprovalRow returned the winner's row.
        assertEquals(1, raceRepo.tryInsertCalls);
        assertEquals(2, raceRepo.findCalls);
        // Race loser MUST NOT fire the admin notification — only the
        // winner does. The notifier records zero calls on this path.
        assertEquals(0, notifier.notifyCalls);
    }

    private void seedUser(Connection conn, UUID userId, String contactId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (id, adapter, contact_id, registration_state, is_banned) "
                        + "VALUES (?, ?, ?, 'vouched', false) "
                        + "ON CONFLICT (id) DO NOTHING")) {
            ps.setObject(1, userId);
            ps.setString(2, TEST_ADAPTER);
            ps.setString(3, contactId);
            ps.executeUpdate();
        }
    }

    private void seedGroup(String upstream, String approvalStatus, UUID activatedBy)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO groups (adapter, upstream_group_id, approval_status, activated_by) "
                             + "VALUES (?, ?, ?::varchar, ?)")) {
            ps.setString(1, TEST_ADAPTER);
            ps.setString(2, upstream);
            ps.setString(3, approvalStatus);
            ps.setObject(4, activatedBy);
            ps.executeUpdate();
        }
    }

    /**
     * Stubbed {@link GroupRepository} that simulates the race-loser
     * code path: the initial {@code findApprovalRow} returns empty,
     * the {@code tryInsertPending} returns empty (CONFLICT), then the
     * re-read returns the winner's row. The cap counters return zero
     * so the test exercises the post-cap INSERT path.
     */
    static final class RaceLosingGroupRepository extends GroupRepository {
        private final GroupApprovalRow winnerRow;
        int findCalls = 0;
        int tryInsertCalls = 0;

        RaceLosingGroupRepository(@NonNull GroupApprovalRow winnerRow) {
            // Pass a never-used DataSource so the parent's @NonNull
            // contract is satisfied; the overrides below intercept every
            // SQL path so the parent's DataSource never executes a query.
            super(noopDataSource());
            this.winnerRow = winnerRow;
        }

        @Override
        public @NonNull Optional<GroupApprovalRow> findApprovalRow(
                @NonNull String adapter, @NonNull String upstreamGroupId) {
            findCalls++;
            return findCalls == 1 ? Optional.empty() : Optional.of(winnerRow);
        }

        @Override
        public @NonNull Optional<UUID> tryInsertPending(
                @NonNull String adapter,
                @NonNull String upstreamGroupId,
                @NonNull UUID activatedByUserId) {
            tryInsertCalls++;
            return Optional.empty();
        }

        @Override
        public long countGroupsActivatedBy(@NonNull UUID userId) {
            return 0L;
        }

        @Override
        public long countActiveGroups() {
            return 0L;
        }
    }

    /**
     * Never-used {@link DataSource} that satisfies the
     * {@link GroupRepository} parent constructor's @NonNull contract
     * without booting a real pool. The {@code group/} tests that
     * subclass {@code GroupRepository} all override every SQL path,
     * so the parent's {@code dataSource} field is bound but never
     * dereferenced. Package-private so {@code GroupApprovalCheckTest}
     * reuses it.
     */
    static DataSource noopDataSource() {
        return new javax.sql.DataSource() {
            @Override public java.sql.Connection getConnection() {
                throw new UnsupportedOperationException("stub");
            }
            @Override public java.sql.Connection getConnection(String u, String p) {
                throw new UnsupportedOperationException("stub");
            }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) {}
            @Override public void setLoginTimeout(int seconds) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return null; }
            @Override public <T> T unwrap(Class<T> iface) { return null; }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }

    /**
     * Stub {@link ThrottledAdminNotifier} that records notifyOnce
     * invocations without writing to the database. Used in the
     * race-loser test where the real notifier is not under test.
     */
    static final class RecordingNoopNotifier extends ThrottledAdminNotifier {
        int notifyCalls = 0;

        @Override
        public NotifyOutcome notifyOnce(@NonNull String key,
                                        @NonNull String errorClass,
                                        @NonNull String message) {
            notifyCalls++;
            return NotifyOutcome.EMITTED;
        }
    }
}
