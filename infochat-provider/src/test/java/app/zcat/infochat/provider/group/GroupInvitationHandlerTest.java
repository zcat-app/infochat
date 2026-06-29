package app.zcat.infochat.provider.group;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.messaging.RegisteredContactSet;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the D47 registered-only auto-join gate (M1-515). A registered,
 * non-banned inviter triggers a {@code joinGroup} on the source adapter; an
 * unregistered or banned inviter triggers no join (redteam vector 3: the bot
 * cannot be made to join an arbitrary group by an unauthorized inviter).
 */
@QuarkusTest
class GroupInvitationHandlerTest {

    private static final String TEST_ADAPTER = "inmemory";
    // Unique per run so parallel/repeat runs never collide on the natural key.
    private static final String CONTACT_PREFIX = "gih-" + UUID.randomUUID() + "-";
    private static final String GROUP_ID = "2";
    // Distinct, run-unique upstream group ids for the M1-519 total-cap tests so
    // each auto-join records its own auto_joined_group row (the natural key is
    // (adapter, upstream_group_id)); the per-run UUID avoids colliding with a
    // leftover row from a crashed prior run.
    private static final String GROUP_PREFIX = "gih-grp-" + UUID.randomUUID() + "-";

    @Inject @SeedDataSource DataSource dataSource;
    @Inject GroupInvitationHandler handler;
    @Inject RegisteredContactSet registeredContactSet;

    private final List<String> seededUserIds = new ArrayList<>();
    private final List<String> markedContacts = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // Delete the auto_joined_group rows FIRST: inviter_user_id has a FK
            // to users(id), so the users delete below would otherwise fail. All
            // rows this class records use TEST_ADAPTER, and it is the only writer
            // of inmemory join rows, so an adapter-scoped delete is exact.
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM auto_joined_group WHERE adapter = ?")) {
                ps.setString(1, TEST_ADAPTER);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM users WHERE adapter = ? AND contact_id LIKE ?")) {
                ps.setString(1, TEST_ADAPTER);
                ps.setString(2, CONTACT_PREFIX + "%");
                ps.executeUpdate();
            }
        }
        // Drop any in-memory registered-set entries this test added so the
        // rate-cap split state never leaks into another test class's bucket.
        for (String contactId : markedContacts) {
            registeredContactSet.invalidate(TEST_ADAPTER, contactId);
        }
        seededUserIds.clear();
        markedContacts.clear();
    }

    @Test
    void vouchedInviterTriggersJoin() throws Exception {
        String inviter = seedRegisteredInviter("vouched");
        JoinCapturingAdapter source = new JoinCapturingAdapter(TEST_ADAPTER);

        handler.handle(new MessagingAdapter.GroupInvitation(GROUP_ID, inviter), TEST_ADAPTER, source);

        assertEquals(List.of(GROUP_ID), source.joined,
                "a vouched, non-banned inviter must trigger /_join on the source adapter");
    }

    @Test
    void invitedInviterTriggersJoin() throws Exception {
        String inviter = seedRegisteredInviter("invited");
        JoinCapturingAdapter source = new JoinCapturingAdapter(TEST_ADAPTER);

        handler.handle(new MessagingAdapter.GroupInvitation(GROUP_ID, inviter), TEST_ADAPTER, source);

        assertEquals(List.of(GROUP_ID), source.joined,
                "an invited (registered, non-banned) inviter must trigger /_join");
    }

    @Test
    void unregisteredInviterDoesNotJoin() throws Exception {
        // No users row seeded for this inviter — the natural-key lookup misses.
        String inviter = CONTACT_PREFIX + "unregistered";
        JoinCapturingAdapter source = new JoinCapturingAdapter(TEST_ADAPTER);

        handler.handle(new MessagingAdapter.GroupInvitation(GROUP_ID, inviter), TEST_ADAPTER, source);

        assertTrue(source.joined.isEmpty(),
                "an invitation from an unregistered inviter must NOT auto-join");
    }

    @Test
    void bannedInviterDoesNotJoin() throws Exception {
        String inviter = seedUser("vouched", true);
        JoinCapturingAdapter source = new JoinCapturingAdapter(TEST_ADAPTER);

        handler.handle(new MessagingAdapter.GroupInvitation(GROUP_ID, inviter), TEST_ADAPTER, source);

        assertTrue(source.joined.isEmpty(),
                "an invitation from a banned inviter must NOT auto-join, even when registered");
    }

    @Test
    void prebanInviterDoesNotJoin() throws Exception {
        // 'preban' is a valid registration_state that is NOT in the registered
        // gate set, so it must not auto-join (boundary of REGISTERED_STATES).
        String inviter = seedUser("preban", false);
        JoinCapturingAdapter source = new JoinCapturingAdapter(TEST_ADAPTER);

        handler.handle(new MessagingAdapter.GroupInvitation(GROUP_ID, inviter), TEST_ADAPTER, source);

        assertTrue(source.joined.isEmpty(),
                "a non-registered (preban) inviter must NOT auto-join");
    }

    @Test
    void overCapInvitationFloodStopsJoining() throws Exception {
        // Redteam DoS finding (M1-515 round 2): a registered, non-banned inviter
        // must not be able to flood invitations into unbounded /_join outbound.
        // The §step-1.5 per-(adapter, contactId) cap defaults to 60/min; a
        // registered inviter (own bucket, marked below) drains it after 60
        // invitations within the test's sub-second window (no refill), after
        // which further invitations are rate-capped — no join.
        String inviter = seedRegisteredInviter("vouched");
        JoinCapturingAdapter source = new JoinCapturingAdapter(TEST_ADAPTER);

        int cap = 60;     // infochat.rate-cap.inbound-per-minute default
        int flood = cap + 20;
        for (int i = 0; i < flood; i++) {
            handler.handle(new MessagingAdapter.GroupInvitation(GROUP_ID, inviter), TEST_ADAPTER, source);
        }

        // Core security assertion: the flood does NOT all join — the cap drops
        // the excess. The +5 slack tolerates token refill if the loop somehow
        // runs multiple seconds on a very slow box (refill is ~1 token/sec); the
        // flood (cap+20) far exceeds any plausible refill, so the cap is proven
        // to bound joins regardless of timing.
        assertTrue(source.joined.size() < flood,
                "the rate cap drops part of the flood (joined " + source.joined.size()
                        + " of " + flood + ")");
        assertTrue(source.joined.size() <= cap + 5,
                "joins stay bounded at the per-(adapter, contactId) cap (~" + cap
                        + "), not the full flood of " + flood);
    }

    @Test
    void perInviterActivationCapStopsJoiningNewGroups() throws Exception {
        // M1-519 total cap: once one inviter has pulled the bot into
        // per-user-activation-cap (%test = 3) DISTINCT groups, a further
        // invitation to a NEW group from that inviter does NOT auto-join — even
        // though the transport rate cap still has tokens (only cap+1 handle()
        // calls, far under the 60/min rate cap). This is the TOTAL cap, distinct
        // from the rate cap exercised by overCapInvitationFloodStopsJoining.
        clearJoinRows();
        String inviter = seedRegisteredInviter("vouched");
        JoinCapturingAdapter source = new JoinCapturingAdapter(TEST_ADAPTER);

        int cap = 3; // %test infochat.groups.per-user-activation-cap
        for (int i = 0; i <= cap; i++) { // cap + 1 distinct groups
            handler.handle(new MessagingAdapter.GroupInvitation(groupId("p" + i), inviter),
                    TEST_ADAPTER, source);
        }

        assertEquals(cap, source.joined.size(),
                "exactly the per-inviter activation cap (" + cap + ") groups join; the "
                        + (cap + 1) + "th invitation is dropped while the rate cap still has tokens");
    }

    @Test
    void globalMaxGroupsCapStopsJoiningEvenWhenInviterUnderOwnCap() throws Exception {
        // M1-519 global cap: two inviters, each individually UNDER the per-inviter
        // cap of 3, collectively fill the global cap (%test = 5). Inviter A joins
        // 3, inviter B joins 2 (total 5). B's next invitation to a NEW group — B
        // is still under its own cap (2 < 3) — is NOT joined because the GLOBAL
        // cap is reached. Few handle() calls, so the rate cap is never the gate.
        clearJoinRows();
        String inviterA = seedRegisteredInviter("vouched");
        String inviterB = seedRegisteredInviter("invited");
        JoinCapturingAdapter source = new JoinCapturingAdapter(TEST_ADAPTER);

        for (int i = 0; i < 3; i++) { // A fills its own per-inviter cap
            handler.handle(new MessagingAdapter.GroupInvitation(groupId("a" + i), inviterA),
                    TEST_ADAPTER, source);
        }
        for (int i = 0; i < 2; i++) { // B adds 2 → global now at the cap of 5
            handler.handle(new MessagingAdapter.GroupInvitation(groupId("b" + i), inviterB),
                    TEST_ADAPTER, source);
        }
        assertEquals(5, source.joined.size(), "5 joins across two inviters fill the global cap");

        handler.handle(new MessagingAdapter.GroupInvitation(groupId("b-overflow"), inviterB),
                TEST_ADAPTER, source);

        assertEquals(5, source.joined.size(),
                "the global max-groups cap blocks a further join even though inviter B "
                        + "is under its own per-inviter cap");
    }

    /** Run-unique upstream group id for a total-cap test case. */
    private static String groupId(String suffix) {
        return GROUP_PREFIX + suffix;
    }

    /** Establish a zero global-count baseline for the total-cap tests: countJoins()
     * is a global SELECT COUNT(*), so a leftover inmemory row would skew the
     * global-cap assertion. This class is the only writer of inmemory join rows. */
    private void clearJoinRows() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM auto_joined_group WHERE adapter = ?")) {
            ps.setString(1, TEST_ADAPTER);
            ps.executeUpdate();
        }
    }

    /** Seed a registered, non-banned inviter and mark it in the in-memory
     * registered set so the rate cap routes it to its own (isolated, per-
     * contactId) bucket rather than the shared stranger limiter. */
    private String seedRegisteredInviter(String registrationState) throws Exception {
        String inviter = seedUser(registrationState, false);
        registeredContactSet.markRegistered(TEST_ADAPTER, inviter);
        markedContacts.add(inviter);
        return inviter;
    }

    private String seedUser(String registrationState, boolean banned) throws Exception {
        UUID id = UUID.randomUUID();
        // Unique per call (includes the user id) so each test gets a fresh,
        // isolated per-(adapter, contactId) rate-cap bucket — the RateCapBucket
        // bean is app-scoped and not reset between test methods.
        String contactId = CONTACT_PREFIX + registrationState + "-"
                + (banned ? "banned" : "ok") + "-" + id;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (id, adapter, contact_id, registration_state, is_banned) "
                             + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, TEST_ADAPTER);
            ps.setString(3, contactId);
            ps.setString(4, registrationState);
            ps.setBoolean(5, banned);
            ps.executeUpdate();
        }
        seededUserIds.add(id.toString());
        return contactId;
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
