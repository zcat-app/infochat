package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.group.GroupInvitationHandler;
import app.zcat.infochat.provider.group.GroupJoinRepository;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.messaging.RegisteredContactSet;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link RecoverPoolCommandHandler} against the
 * DevServices Postgres container (M1-526). One {@code @Test} per acceptance
 * behavior: DM-only + admin-gate rejection (no mutation), list mode (active
 * pool, freed rows excluded), free-by-natural-key (removed_at + count
 * decrement + audit row), a no-op free leaving no trail, and the headline
 * saturated-pool recovery letting a previously-blocked auto-join succeed
 * (M1-519 redteam Finding 2).
 *
 * <p>Test isolation: seeded users carry a run-unique {@code CONTACT_PREFIX} and
 * auto-join rows a run-unique {@code GROUP_PREFIX}, so audit-row assertions can
 * filter on {@code target_id LIKE GROUP_PREFIX%}. {@code countJoins()} is a
 * GLOBAL count, so the cap tests first zero the inmemory pool via
 * {@link #clearJoinRows()} — the established total-cap-test idiom
 * ({@code GroupInvitationHandlerTest}). {@code audit_log} is append-only (V5),
 * so the cleanup disables its no-update/no-delete triggers (DB-owner role) to
 * remove this run's rows.
 */
@QuarkusTest
class RecoverPoolCommandHandlerIT {

    private static final String ADAPTER = "inmemory";
    private static final String CONTACT_PREFIX = "m1-526-rp-" + UUID.randomUUID() + "-";
    private static final String GROUP_PREFIX = "m1-526-rp-grp-" + UUID.randomUUID() + "-";
    // A permanent guardian admin outside CONTACT_PREFIX so deleting this run's
    // admin rows never trips last-admin protection. Mirrors BanCommandHandlerTest.
    private static final String GUARDIAN = "guardian-m1-526-recover-pool-permanent";

    @Inject RecoverPoolCommandHandler handler;
    @Inject GroupInvitationHandler invitationHandler;
    @Inject GroupJoinRepository groupJoinRepository;
    @Inject RegisteredContactSet registeredContactSet;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;

    @ConfigProperty(name = "infochat.groups.global-max-groups")
    int globalMaxGroups;

    @ConfigProperty(name = "infochat.groups.per-user-activation-cap")
    int perUserActivationCap;

    private final List<String> markedContacts = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                            + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, GUARDIAN);
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // auto_joined_group.inviter_user_id FK-references users(id), so the
            // join rows go first. This run is the only writer of these prefixes.
            exec(conn, "DELETE FROM auto_joined_group WHERE adapter = ? AND upstream_group_id LIKE ?",
                    ADAPTER, GROUP_PREFIX + "%");
            // audit_log is append-only (V5); disable the guard triggers (DB-owner
            // role) to remove this run's RECOVER rows, then re-enable in finally.
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn, "DELETE FROM audit_log WHERE actor_user_id IN "
                        + "(SELECT id FROM users WHERE contact_id LIKE ?)", CONTACT_PREFIX + "%");
                exec(conn, "DELETE FROM users WHERE adapter = ? AND contact_id LIKE ?",
                        ADAPTER, CONTACT_PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
        }
        for (String contactId : markedContacts) {
            registeredContactSet.invalidate(ADAPTER, contactId);
        }
        markedContacts.clear();
    }

    // ----- acceptance item 1: DM-only + admin gate, no mutation ---------------

    @Test
    void recoverPoolInGroupScopeReturnsCommandDmOnlyWithNoMutation() throws Exception {
        UUID inviter = seedInviter();
        String groupId = groupId("dm-only");
        groupJoinRepository.tryRecordJoin(ADAPTER, groupId, inviter);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Group(GROUP_PREFIX + "grp-scope"),
                "/recover-pool " + ADAPTER + " " + groupId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_COMMAND_DM_ONLY), reply.text(),
                "a group-scope /recover-pool must surface error.command_dm_only");
        assertFalse(isRemoved(groupId), "a group-scope invocation must not free any slot");
        assertEquals(0, countRecoverAuditForRun(), "a group-scope invocation writes no audit row");
    }

    @Test
    void nonAdminRecoverPoolReturnsAdminOnlyWithNoMutation() throws Exception {
        String nonAdmin = seedUser("vouched", false, false);
        UUID inviter = seedInviter();
        String groupId = groupId("non-admin");
        groupJoinRepository.tryRecordJoin(ADAPTER, groupId, inviter);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(nonAdmin),
                "/recover-pool " + ADAPTER + " " + groupId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), reply.text(),
                "a non-admin /recover-pool must surface error.admin_only");
        assertFalse(isRemoved(groupId),
                "a non-admin invocation must not write removed_at (acceptance item 1)");
        assertEquals(0, countRecoverAuditForRun(), "a non-admin invocation writes no audit row");
    }

    // ----- acceptance item 2: list mode (discovery) ---------------------------

    @Test
    void listModeOnEmptyPoolReportsEmpty() throws Exception {
        clearJoinRows();
        String admin = seedAdmin();

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(admin), "/recover-pool");

        assertEquals(bundleLoader.get(BundleKeys.REPLY_RECOVER_POOL_EMPTY), reply.text(),
                "an empty active pool must report reply.recover_pool.empty");
    }

    @Test
    void listModeReflectsActivePoolAndExcludesFreed() throws Exception {
        String admin = seedAdmin();
        UUID inviter = seedInviter();
        String active1 = groupId("list-active-1");
        String active2 = groupId("list-active-2");
        String freed = groupId("list-freed");
        groupJoinRepository.tryRecordJoin(ADAPTER, active1, inviter);
        groupJoinRepository.tryRecordJoin(ADAPTER, active2, inviter);
        groupJoinRepository.tryRecordJoin(ADAPTER, freed, inviter);
        assertTrue(groupJoinRepository.markRemovedByNaturalKey(ADAPTER, freed),
                "guard: the freed slot starts as a real, freeable row");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(admin), "/recover-pool");

        assertTrue(reply.text().contains(active1) && reply.text().contains(active2),
                "list mode must surface every active (non-freed) slot's natural key");
        assertFalse(reply.text().contains(freed),
                "list mode must exclude an already-freed slot (removed_at IS NOT NULL)");
    }

    // ----- acceptance items 3 + 4: free, count decrement, audit ---------------

    @Test
    void freeByNaturalKeySetsRemovedAtDecrementsCountAndWritesAudit() throws Exception {
        String admin = seedAdmin();
        UUID inviter = seedInviter();
        String groupId = groupId("free-one");
        groupJoinRepository.tryRecordJoin(ADAPTER, groupId, inviter);
        long countBefore = groupJoinRepository.countJoins();

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin),
                "/recover-pool " + ADAPTER + " " + groupId);

        assertEquals(MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_RECOVER_POOL_FREED), ADAPTER, groupId),
                reply.text(), "a successful free reports reply.recover_pool.freed");
        assertTrue(isRemoved(groupId), "the freed slot's removed_at must be set");
        assertEquals(countBefore - 1, groupJoinRepository.countJoins(),
                "freeing a slot decrements the global countJoins()");
        assertEquals(1, countRecoverAuditForGroup(groupId),
                "exactly one RECOVER_AUTO_JOINED_GROUP audit row is written for the freed slot");
    }

    @Test
    void freeUnknownOrAlreadyFreedReturnsNotFoundWithNoAudit() throws Exception {
        String admin = seedAdmin();
        String unknown = groupId("never-joined");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin),
                "/recover-pool " + ADAPTER + " " + unknown);

        assertEquals(MessageFormat.format(
                        bundleLoader.get(BundleKeys.ERROR_RECOVER_POOL_NOT_FOUND), ADAPTER, unknown),
                reply.text(), "freeing an unknown slot reports error.recover_pool.not_found");
        assertEquals(0, countRecoverAuditForGroup(unknown),
                "a no-op free rolls back, so it leaves no audit trail (audit-only-on-success)");
    }

    // ----- acceptance item 3: saturated-pool recovery -------------------------

    @Test
    void adminRecoversSaturatedPoolSoSubsequentAutoJoinSucceeds() throws Exception {
        clearJoinRows();
        String admin = seedAdmin();
        JoinCapturingAdapter source = new JoinCapturingAdapter(ADAPTER);
        List<String> filled = fillGlobalPoolToCap(source);
        assertEquals(globalMaxGroups, groupJoinRepository.countJoins(),
                "the pool starts saturated at the D47 global cap");

        // A fresh, under-own-cap inviter cannot auto-join while the pool is
        // saturated: the global cap is the gate.
        String blockedInviter = seedRegisteredInviter("vouched");
        String contested = groupId("contested");
        invitationHandler.handle(
                new MessagingAdapter.GroupInvitation(contested, blockedInviter), ADAPTER, source);
        assertFalse(source.joined.contains(contested),
                "a saturated global pool blocks the auto-join even for an under-cap inviter");

        // The admin frees one slot in-band.
        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin),
                "/recover-pool " + ADAPTER + " " + filled.get(0));
        assertEquals(MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_RECOVER_POOL_FREED), ADAPTER, filled.get(0)),
                reply.text(), "the in-band free succeeds");
        assertTrue(isRemoved(filled.get(0)), "the recovered slot's removed_at is set");
        assertEquals(globalMaxGroups - 1, groupJoinRepository.countJoins(),
                "recovery drops the global count below the cap");

        // The previously-blocked invitation now auto-joins (M1-519 Finding 2).
        invitationHandler.handle(
                new MessagingAdapter.GroupInvitation(contested, blockedInviter), ADAPTER, source);
        assertTrue(source.joined.contains(contested),
                "after recovery the subsequent auto-join succeeds");
        assertEquals(globalMaxGroups, groupJoinRepository.countJoins(),
                "the re-join refills the pool to the cap");
    }

    // ----- helpers ------------------------------------------------------------

    /** Fill the global pool to exactly the cap, spreading joins across enough
     * registered inviters that none exceeds the per-inviter activation cap. */
    private List<String> fillGlobalPoolToCap(JoinCapturingAdapter source) throws Exception {
        List<String> joinedGroups = new ArrayList<>();
        int remaining = globalMaxGroups;
        int seq = 0;
        while (remaining > 0) {
            String inviter = seedRegisteredInviter("vouched");
            int forThisInviter = Math.min(perUserActivationCap, remaining);
            for (int i = 0; i < forThisInviter; i++) {
                String gid = groupId("fill-" + (seq++));
                invitationHandler.handle(
                        new MessagingAdapter.GroupInvitation(gid, inviter), ADAPTER, source);
                joinedGroups.add(gid);
            }
            remaining -= forThisInviter;
        }
        return joinedGroups;
    }

    private static String groupId(String suffix) {
        return GROUP_PREFIX + suffix;
    }

    private String seedAdmin() throws Exception {
        return seedUser("vouched", false, true);
    }

    private UUID seedInviter() throws Exception {
        UUID id = UUID.randomUUID();
        insertUser(id, CONTACT_PREFIX + "inviter-" + id, "vouched", false, false);
        return id;
    }

    /** Seed a registered, non-banned inviter and mark it in the registered set so
     * the rate cap routes it to its own isolated per-contactId bucket. */
    private String seedRegisteredInviter(String registrationState) throws Exception {
        String contact = seedUser(registrationState, false, false);
        registeredContactSet.markRegistered(ADAPTER, contact);
        markedContacts.add(contact);
        return contact;
    }

    private String seedUser(String registrationState, boolean banned, boolean admin) throws Exception {
        UUID id = UUID.randomUUID();
        String contactId = CONTACT_PREFIX + registrationState + "-"
                + (admin ? "admin" : "user") + "-" + id;
        insertUser(id, contactId, registrationState, banned, admin);
        return contactId;
    }

    private void insertUser(UUID id, String contactId, String registrationState,
                            boolean banned, boolean admin) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (id, adapter, contact_id, registration_state, "
                             + "is_banned, is_admin) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, ADAPTER);
            ps.setString(3, contactId);
            ps.setString(4, registrationState);
            ps.setBoolean(5, banned);
            ps.setBoolean(6, admin);
            ps.executeUpdate();
        }
    }

    /** Zero the inmemory pool so the global countJoins() assertions are exact. */
    private void clearJoinRows() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM auto_joined_group WHERE adapter = ?")) {
            ps.setString(1, ADAPTER);
            ps.executeUpdate();
        }
    }

    private boolean isRemoved(String upstreamGroupId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT removed_at FROM auto_joined_group WHERE adapter = ? "
                             + "AND upstream_group_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "guard: the row must exist to check removed_at");
                return rs.getTimestamp("removed_at") != null;
            }
        }
    }

    private long countRecoverAuditForGroup(String upstreamGroupId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?")) {
            ps.setString(1, AuditAction.RECOVER_AUTO_JOINED_GROUP.name());
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countRecoverAuditForRun() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = ? AND target_id LIKE ?")) {
            ps.setString(1, AuditAction.RECOVER_AUTO_JOINED_GROUP.name());
            ps.setString(2, GROUP_PREFIX + "%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void exec(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    /** Minimal adapter double that records the group ids it was asked to join. */
    private static final class JoinCapturingAdapter implements MessagingAdapter {
        final List<String> joined = new ArrayList<>();
        private final String name;

        JoinCapturingAdapter(String name) {
            this.name = name;
        }

        @Override
        public void joinGroup(String adapterGroupId) {
            joined.add(adapterGroupId);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public CapabilityFlags capabilities() {
            throw new UnsupportedOperationException();
        }

        @Override
        public AdapterTrustLevel trustLevel() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWellFormedContactId(String contactId) {
            return true;
        }

        @Override
        public MessageHandle send(OutboundMessage msg) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update(MessageHandle handle, String body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void finalizeMessage(MessageHandle handle, String body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setTyping(ScopeRef scope, boolean typing) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setInboundHandler(InboundHandler handler) {
            throw new UnsupportedOperationException();
        }
    }
}
