package app.zcat.infochat.provider.group;

import app.zcat.infochat.core.notifier.AdminNotificationRecord;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D47 acceptance gate IT (M1-114). Exercises the full D47 group
 * authorization flow end-to-end through {@link InMemoryAdapter} →
 * {@code InboundRouter} → {@code GroupApprovalCheck} →
 * {@code GroupApprovalService} → admin handlers, verifying the
 * security boundary the per-component tests cannot see in isolation.
 *
 * <p>The IT covers five separable acceptance scenarios as discrete
 * @Test methods sharing the same per-test cleanup. Splitting them out
 * keeps each failure narrowly attributable; the InviteIntakeRoundtripIT
 * single-method shape was appropriate when each step strictly required
 * the prior step's state, which is not the case here.</p>
 *
 * <ol>
 *   <li>{@link #fullD47RoundtripFromInviteToApprovalToRejection} — the
 *       eight-step roundtrip: DM invite-consume registration → first
 *       group @mention creates pending row + admin notification → second
 *       @mention in same pending group returns fixed reply with no
 *       re-notification → unregistered contact @mention silently dropped
 *       → admin {@code /approve-group} transitions to approved →
 *       registered user @mention processed normally → admin
 *       {@code /reject-group} (confirm-gated) transitions to rejected →
 *       registered user @mention returns the rejected fixed reply.</li>
 *   <li>{@link #perGroupReplyRateCapSilentDropAfterExhaustion} —
 *       acceptance item 3: per-group reply bucket exhausts after the
 *       configured cap; subsequent @mentions silently dropped with no
 *       outbound.</li>
 *   <li>{@link #perUserActivationCapReturnsLimitReached} — acceptance
 *       item 4: per-user activation cap exceeded → fixed
 *       {@link BundleKeys#GROUP_ACTIVATION_LIMIT} reply.</li>
 *   <li>{@link #digestEligibilityQueryReturnsOnlyApprovedAndNotRemoved} —
 *       acceptance item 5: direct SQL query (not the digest worker)
 *       returns only approved + not-removed groups.</li>
 * </ol>
 *
 * <p>The IT uses a permanent {@code GUARDIAN} bot-admin row so the V5
 * {@code trg_last_admin_protection_update} trigger does not refuse the
 * per-test DELETE on admin rows (mirrors the
 * {@code InviteIntakeRoundtripIT} convention).</p>
 */
@QuarkusTest
@TestProfile(GroupAuthorizationRoundtripIT.Profile.class)
class GroupAuthorizationRoundtripIT {

    private static final String ADAPTER = "inmemory";
    private static final String PREFIX = "m1-114-d47-it-";
    private static final String GUARDIAN = "guardian-m1-114-d47-it-permanent";

    @Inject InMemoryAdapter adapter;
    @Inject DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject ThrottledAdminNotifier throttledAdminNotifier;

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                  + "VALUES (?, ?, TRUE, 'vouched') "
                  + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                  + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, GUARDIAN);

            // Wipe in dependency order: group_membership (FK → groups,
            // users) → invite_code (FK → users.id via created_by) →
            // audit_log (FK → users.id via actor_user_id) → groups
            // (FK → users.id via activated_by) → users. The users
            // DELETE comes last because groups.activated_by holds
            // references to users that would otherwise block the cascade.
            exec(conn,
                    "DELETE FROM group_membership WHERE group_id IN ("
                  + "SELECT id FROM groups WHERE upstream_group_id LIKE ?)",
                    PREFIX + "%");

            exec(conn,
                    "DELETE FROM invite_code WHERE expected_contact_id LIKE ? "
                  + "OR created_by IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                    PREFIX + "%", PREFIX + "%");

            // audit_log carries no-update + no-delete triggers (V5);
            // disable both for the per-test wipe then re-enable.
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE actor_user_id IN ("
                      + "SELECT id FROM users WHERE contact_id LIKE ? AND contact_id != ?)",
                        PREFIX + "%", GUARDIAN);
                // Drop our groups BEFORE users so groups.activated_by FK
                // does not block the users DELETE.
                exec(conn, "DELETE FROM groups WHERE upstream_group_id LIKE ?",
                        PREFIX + "%");
                exec(conn,
                        "UPDATE users SET banned_by = NULL WHERE contact_id LIKE ?",
                        PREFIX + "%");
                exec(conn,
                        "DELETE FROM users WHERE contact_id LIKE ? AND contact_id != ?",
                        PREFIX + "%", GUARDIAN);
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }

            // Wipe the ADMIN-NOTIFY rows that prior tests minted for our
            // group-pending keys. Otherwise the notification_count
            // assertion in step (3) reads a stale row from a prior IT run.
            exec(conn,
                    "DELETE FROM admin_notification_state WHERE notification_key LIKE ?",
                    "group-pending:" + ADAPTER + ":" + PREFIX + "%");
        }
    }

    @Test
    void fullD47RoundtripFromInviteToApprovalToRejection() throws Exception {
        String admin = PREFIX + "admin";
        String u = PREFIX + "u-1";
        String unregistered = PREFIX + "u-unreg";
        String pendingGroup = PREFIX + "g-roundtrip";
        String unregisteredGroup = PREFIX + "g-unreg";

        UUID adminId = seedBotAdmin(admin);

        // ----- Step (1) — register u via DM invite-code consume -----
        UUID code = UUID.randomUUID();
        seedPendingInvite(code, u, adminId);
        adapter.deliverDm(u, code.toString());
        assertEquals(bundleLoader.get(BundleKeys.REPLY_WELCOME_DM_FRESH),
                lastReplyText(),
                "step (1): invite-consume must return the welcome reply");
        assertEquals("invited", registrationStateOf(u),
                "step (1): consumer must transition to registration_state='invited'");
        // Clear probation so non-probation-allowed commands (none used here)
        // would also pass; /help is in the probation-allowed set so this is
        // belt-and-braces for any future test extensions.
        clearProbation(u);
        adapter.reset();

        // Pre-seed the upstream group at the adapter side. The provider
        // does not know about the group until the first @mention.
        adapter.createGroup(pendingGroup);
        adapter.addMember(pendingGroup, u);
        adapter.addMember(pendingGroup, admin);

        // ----- Step (2) — first @mention from registered u → pending row + admin notify -----
        adapter.deliverGroupMention(pendingGroup, u, "/help");
        assertEquals(bundleLoader.get(BundleKeys.GROUP_PENDING),
                lastReplyText(),
                "step (2): first @mention must return GROUP_PENDING fixed reply");
        assertEquals("pending", approvalStatusOf(pendingGroup),
                "step (2): groups row must be created with approval_status='pending'");
        String notifyKey = "group-pending:" + ADAPTER + ":" + pendingGroup;
        Optional<AdminNotificationRecord> notify2 = throttledAdminNotifier.getState(notifyKey);
        assertTrue(notify2.isPresent(),
                "step (2): admin-notify row must exist for the new pending group");
        assertEquals(1L, notify2.get().notificationCount(),
                "step (2): notification_count must be 1 after the creation @mention");
        UUID groupRowId = groupIdOf(pendingGroup);
        adapter.reset();

        // ----- Step (3) — second @mention in same pending group → fixed reply, no re-notify -----
        adapter.deliverGroupMention(pendingGroup, u, "/help");
        assertEquals(bundleLoader.get(BundleKeys.GROUP_PENDING),
                lastReplyText(),
                "step (3): subsequent pending-group @mention returns GROUP_PENDING fixed reply");
        Optional<AdminNotificationRecord> notify3 = throttledAdminNotifier.getState(notifyKey);
        assertTrue(notify3.isPresent(),
                "step (3): admin-notify row remains after subsequent @mention");
        assertEquals(1L, notify3.get().notificationCount(),
                "step (3): notification_count must NOT increment on subsequent @mentions "
                        + "(exactly-once-per-creation invariant)");
        adapter.reset();

        // ----- Step (4) — unregistered contact @mention → silent drop -----
        adapter.createGroup(unregisteredGroup);
        adapter.addMember(unregisteredGroup, unregistered);
        int sentBefore4 = adapter.sentMessages().size();
        adapter.deliverGroupMention(unregisteredGroup, unregistered, "/help");
        assertEquals(sentBefore4, adapter.sentMessages().size(),
                "step (4): unregistered group @mention must produce NO outbound (step 3 drop)");
        assertEquals(0L, countUsersByContact(unregistered),
                "step (4): unregistered group @mention must NOT register the user");
        assertEquals(0L, countGroupsByUpstreamId(unregisteredGroup),
                "step (4): unregistered group @mention must NOT create the groups row");
        adapter.reset();

        // ----- Step (5) — admin /approve-group <id> → approved + group notified -----
        adapter.deliverDm(admin, "/approve-group " + groupRowId);
        assertEquals("approved", approvalStatusOf(pendingGroup),
                "step (5): /approve-group must transition approval_status to 'approved'");
        List<OutboundMessage> outAfterApprove = adapter.sentMessages();
        // Expect TWO outbounds: the success reply to the admin (DM scope)
        // plus the one-time group_approved_message addressed to the group.
        boolean groupApprovedMessageSent = outAfterApprove.stream()
                .anyMatch(m -> bundleLoader.get(BundleKeys.GROUP_APPROVED_MESSAGE)
                        .equals(m.text()));
        assertTrue(groupApprovedMessageSent,
                "step (5): the group must receive the one-time group_approved_message; outbound bodies were: "
                        + outAfterApprove);
        adapter.reset();

        // ----- Step (6) — registered user @mention now processed normally -----
        adapter.deliverGroupMention(pendingGroup, u, "/help");
        String reply6 = lastReplyText();
        assertNotEquals(bundleLoader.get(BundleKeys.GROUP_PENDING), reply6,
                "step (6): approved-group @mention must NOT return GROUP_PENDING");
        assertNotEquals(bundleLoader.get(BundleKeys.GROUP_REJECTED), reply6,
                "step (6): approved-group @mention must NOT return GROUP_REJECTED");
        assertTrue(reply6.startsWith(bundleLoader.get(BundleKeys.HELP_HEADER_GROUP)),
                "step (6): /help must render the group help header on the approved-group path; got: "
                        + reply6);
        adapter.reset();

        // ----- Step (7) — admin /reject-group <id> (confirm-gated) → rejected -----
        adapter.deliverDm(admin, "/reject-group " + groupRowId);
        // First call returns the confirm prompt — verify by snippet, not full text.
        assertTrue(lastReplyText().contains(groupRowId.toString()),
                "step (7): /reject-group first call must emit the confirm prompt naming the group id");
        adapter.deliverDm(admin, "/reject-group " + groupRowId + " confirm");
        assertEquals("rejected", approvalStatusOf(pendingGroup),
                "step (7): /reject-group confirm must transition approval_status to 'rejected'");
        adapter.reset();

        // ----- Step (8) — registered user @mention → GROUP_REJECTED fixed reply -----
        adapter.deliverGroupMention(pendingGroup, u, "/help");
        assertEquals(bundleLoader.get(BundleKeys.GROUP_REJECTED),
                lastReplyText(),
                "step (8): rejected-group @mention must return GROUP_REJECTED fixed reply");
    }

    @Test
    void perGroupReplyRateCapSilentDropAfterExhaustion() throws Exception {
        // The Profile pins infochat.ratelimit.group-reply-per-15min=3
        // so the bucket exhausts after exactly three consumed tokens.
        // The creation @mention does NOT consume a token (the bucket
        // is consulted only for existing rows — see GroupApprovalCheck
        // class-level Javadoc); subsequent @mentions on the existing
        // pending row each consume one. The fifth @mention finds an
        // empty bucket and is silently dropped.
        String admin = PREFIX + "admin";
        String u = PREFIX + "u-rate";
        String group = PREFIX + "g-rate";

        UUID adminId = seedBotAdmin(admin);
        UUID code = UUID.randomUUID();
        seedPendingInvite(code, u, adminId);
        adapter.deliverDm(u, code.toString());
        clearProbation(u);
        adapter.reset();

        adapter.createGroup(group);
        adapter.addMember(group, u);

        // Creation @mention — no bucket token consumed; returns GROUP_PENDING.
        adapter.deliverGroupMention(group, u, "/help");
        assertEquals(bundleLoader.get(BundleKeys.GROUP_PENDING),
                lastReplyText(),
                "creation @mention: GROUP_PENDING returned");

        // Three more @mentions — each consumes one bucket token (cap=3).
        for (int i = 1; i <= 3; i++) {
            adapter.deliverGroupMention(group, u, "/help");
            assertEquals(bundleLoader.get(BundleKeys.GROUP_PENDING),
                    lastReplyText(),
                    "@mention consuming token " + i + "/3 must still return GROUP_PENDING");
        }

        // Fifth @mention finds the bucket empty → silent drop, no outbound.
        int sentBeforeDrop = adapter.sentMessages().size();
        adapter.deliverGroupMention(group, u, "/help");
        assertEquals(sentBeforeDrop, adapter.sentMessages().size(),
                "post-exhaustion @mention must be silently dropped (bucket empty)");
    }

    @Test
    void perUserActivationCapReturnsLimitReached() throws Exception {
        // The Profile pins infochat.groups.per-user-activation-cap=2 so
        // the third activation by the same user crosses the cap.
        String admin = PREFIX + "admin";
        String u = PREFIX + "u-act";
        String g1 = PREFIX + "g-act-1";
        String g2 = PREFIX + "g-act-2";
        String g3 = PREFIX + "g-act-3";

        UUID adminId = seedBotAdmin(admin);
        UUID code = UUID.randomUUID();
        seedPendingInvite(code, u, adminId);
        adapter.deliverDm(u, code.toString());
        clearProbation(u);
        adapter.reset();

        // Activate g1 + g2 — within cap (2/2).
        for (String g : List.of(g1, g2)) {
            adapter.createGroup(g);
            adapter.addMember(g, u);
            adapter.deliverGroupMention(g, u, "/help");
            assertEquals(bundleLoader.get(BundleKeys.GROUP_PENDING),
                    lastReplyText(),
                    "activation within cap: GROUP_PENDING returned for " + g);
        }

        // g3 would exceed the cap — must return GROUP_ACTIVATION_LIMIT
        // and NOT create a row.
        adapter.createGroup(g3);
        adapter.addMember(g3, u);
        adapter.deliverGroupMention(g3, u, "/help");
        assertEquals(bundleLoader.get(BundleKeys.GROUP_ACTIVATION_LIMIT),
                lastReplyText(),
                "over-cap activation: GROUP_ACTIVATION_LIMIT returned");
        assertEquals(0L, countGroupsByUpstreamId(g3),
                "over-cap activation: groups row must NOT be created for " + g3);
    }

    @Test
    void digestEligibilityQueryReturnsOnlyApprovedAndNotRemoved() throws Exception {
        // Seed three groups directly: one approved (eligible), one
        // pending (ineligible — not yet approved), one approved-but-
        // removed (ineligible — soft-deleted). The IT asserts the
        // digest eligibility SQL predicate directly rather than running
        // the scheduler (per ticket Notes: keeps the IT focused on D47
        // without pulling in the digest worker dependency chain).
        String g1 = PREFIX + "g-digest-approved";
        String g2 = PREFIX + "g-digest-pending";
        String g3 = PREFIX + "g-digest-removed";

        try (Connection conn = dataSource.getConnection()) {
            insertGroup(conn, g1, "approved", false);
            insertGroup(conn, g2, "pending", false);
            insertGroup(conn, g3, "approved", true);
        }

        List<String> eligible = queryEligibleDigestGroups();
        assertTrue(eligible.contains(g1),
                "approved + not removed group must be eligible: " + g1);
        assertFalse(eligible.contains(g2),
                "pending group must NOT be eligible: " + g2);
        assertFalse(eligible.contains(g3),
                "approved + removed group must NOT be eligible: " + g3);
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedBotAdmin(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                   + "VALUES (?, ?, TRUE, 'vouched') "
                   + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                   + "  SET is_admin = TRUE, is_banned = FALSE, "
                   + "    registration_state = 'vouched', probation_until = NULL "
                   + "RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedPendingInvite(UUID code, String expectedContactId, UUID createdBy)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO invite_code (code, invite_type, adapter, "
                   + "expected_contact_id, status, created_by) "
                   + "VALUES (?, 'CONTACT_BOUND', ?, ?, 'PENDING', ?)")) {
            ps.setObject(1, code);
            ps.setString(2, ADAPTER);
            ps.setString(3, expectedContactId);
            ps.setObject(4, createdBy);
            ps.executeUpdate();
        }
    }

    private void clearProbation(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET probation_until = NULL "
                   + "WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.executeUpdate();
        }
    }

    private void insertGroup(Connection conn, String upstreamId, String approvalStatus,
                             boolean removed) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO groups (adapter, upstream_group_id, approval_status, removed_at) "
              + "VALUES (?, ?, ?, CASE WHEN ? THEN NOW() ELSE NULL END)")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, upstreamId);
            ps.setString(3, approvalStatus);
            ps.setBoolean(4, removed);
            ps.executeUpdate();
        }
    }

    private List<String> queryEligibleDigestGroups() throws Exception {
        // Mirrors the per-digest selector predicate spec'd in
        // docs/spec/commands.md §Periodic group digests: approved groups
        // with NULL removed_at. Returns the upstream_group_id values so
        // the assertion can name groups by test-fixture id.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT upstream_group_id FROM groups "
                   + "WHERE approval_status = 'approved' "
                   + "  AND removed_at IS NULL "
                   + "  AND upstream_group_id LIKE ?")) {
            ps.setString(1, PREFIX + "%");
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new java.util.ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return out;
            }
        }
    }

    private String approvalStatusOf(String upstreamGroupId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT approval_status FROM groups "
                   + "WHERE adapter = ? AND upstream_group_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(),
                        "groups row must exist for upstream_group_id=" + upstreamGroupId);
                return rs.getString("approval_status");
            }
        }
    }

    private UUID groupIdOf(String upstreamGroupId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(),
                        "groups row must exist for upstream_group_id=" + upstreamGroupId);
                return (UUID) rs.getObject("id");
            }
        }
    }

    private String registrationStateOf(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT registration_state FROM users "
                   + "WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(),
                        "users row must exist for contact_id=" + contactId);
                return rs.getString("registration_state");
            }
        }
    }

    private long countUsersByContact(String contactId) throws Exception {
        return queryLong(
                "SELECT COUNT(*) FROM users WHERE adapter = ? AND contact_id = ?",
                ADAPTER, contactId);
    }

    private long countGroupsByUpstreamId(String upstreamGroupId) throws Exception {
        return queryLong(
                "SELECT COUNT(*) FROM groups WHERE adapter = ? AND upstream_group_id = ?",
                ADAPTER, upstreamGroupId);
    }

    private long queryLong(String sql, Object... args) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private String lastReplyText() {
        var sent = adapter.sentMessages();
        assertFalse(sent.isEmpty(), "Expected at least one reply");
        return sent.getLast().text();
    }

    private static void exec(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    /**
     * Test profile: in-memory adapter only; pins the per-group rate
     * cap to 3 and per-user activation cap to 2. The rate cap of 3
     * lets the roundtrip test consume three tokens (steps 3, 6, 8) on
     * the same group without hitting the bucket floor, AND lets the
     * rate-cap test exhaust the bucket in five deliveries (creation +
     * three tokens consumed + one silent drop). The global max-groups
     * cap is raised above the IT's needs so no scenario hits it
     * accidentally.
     */
    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true",
                    "infochat.ratelimit.group-reply-per-15min", "3",
                    "infochat.groups.per-user-activation-cap", "2",
                    "infochat.groups.global-max-groups", "100");
        }
    }
}
