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

    @Inject @SeedDataSource DataSource dataSource;
    @Inject GroupInvitationHandler handler;
    @Inject RegisteredContactSet registeredContactSet;

    private final List<String> seededUserIds = new ArrayList<>();
    private final List<String> markedContacts = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM users WHERE adapter = ? AND contact_id LIKE ?")) {
            ps.setString(1, TEST_ADAPTER);
            ps.setString(2, CONTACT_PREFIX + "%");
            ps.executeUpdate();
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
