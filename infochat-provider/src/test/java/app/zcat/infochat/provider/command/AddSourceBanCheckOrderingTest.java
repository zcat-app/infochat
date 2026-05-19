package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.source.UrlProbe;
import app.zcat.infochat.provider.source.UrlProbe.ProbeResult;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the in-handler ban-check ordering for
 * {@link AddSourceCommandHandler}: actor lookup AND the
 * {@code is_banned} check run BEFORE the scope discriminator
 * (the {@code if (scope instanceof ScopeRef.Group)} branch).
 * Implements the M1-039 remediation of M1-036's red-team finding 2
 * (medium INFO-LEAK — group-scope banned-user reply leak).
 *
 * <p><b>Coverage at the current SPI shape.</b> Two scenarios are
 * testable here:
 * <ul>
 *   <li>{@link #bannedDmUserReceivesFixedBanReply()} — banned DM
 *       caller receives the {@code error.add_source.banned} bundle
 *       literal; the URL probe is never invoked.</li>
 *   <li>{@link #groupScopeNonAdminReceivesGroupAdminOnly()} —
 *       non-banned non-group-admin caller in group scope receives
 *       the {@code error.add_source.group_admin_only} bundle
 *       literal; the discriminator is preserved after the reorder.</li>
 * </ul>
 *
 * <p>Scenarios (b) banned-user-in-group and (d) group-admin-proceeds
 * are NOT covered here because {@link ScopeRef.Group} carries only
 * {@code adapterGroupId} (no contact id); {@code lookupActor}
 * returns {@link Optional#empty()} for group scope, so the
 * in-handler ban check is observably a no-op there and the
 * group-admin proceed path cannot be exercised without the
 * actor-seam SPI widening that T2-F lands. The structural reorder
 * pinned by acceptance item 1 is what makes those two future
 * scenarios fire correctly the day T2-F arrives.</p>
 */
@QuarkusTest
@TestProfile(AddSourceBanCheckOrderingTest.MvpProfile.class)
class AddSourceBanCheckOrderingTest {

    @Inject InMemoryAdapter adapter;

    @Inject AddSourceCommandHandler handler;

    @Inject MockUrlProbe mockProbe;

    @Inject DataSource dataSource;

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
        mockProbe.reset();
        try (Connection conn = dataSource.getConnection()) {
            // Re-affirm a guardian admin BEFORE deleting test users so
            // the V5 last-admin-protection trigger does not block the
            // delete of the test admin set. Same pattern as
            // AddSourceCommandHandlerTest.
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES ('inmemory', 'm1-039-guardian-permanent', TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                            + "  SET is_admin = TRUE, is_banned = FALSE");
            exec(conn,
                    "DELETE FROM users "
                            + "WHERE contact_id LIKE 'm1-039-%' "
                            + "  AND contact_id <> 'm1-039-guardian-permanent'");
        }
    }

    @Test
    void bannedDmUserReceivesFixedBanReply() throws Exception {
        insertBannedUser("m1-039-banned-dm");

        adapter.deliverDm("m1-039-banned-dm",
                "/add-source https://example.com/m1-039-banned.xml --tags m1-039-tag");

        assertEquals(1, adapter.sentMessages().size(),
                "exactly one outbound reply must be produced");
        String body = adapter.sentMessages().get(0).text();
        assertTrue(body.contains("not permitted"),
                "banned DM caller must see the error.add_source.banned bundle literal "
                        + "(\"You are not permitted to add sources.\") — got: " + body);
        assertEquals(0, mockProbe.callCount(),
                "the in-handler ban check must short-circuit BEFORE UrlProbe is invoked");
    }

    @Test
    void groupScopeNonAdminReceivesGroupAdminOnly() {
        // Group scope, non-banned caller (no users row at all — the
        // SPI does not carry an actor id in group scope, so lookupActor
        // returns Optional.empty() and the ban check is a no-op).
        // The discriminator below the ban check must catch this and
        // return the group_admin_only friendly error.
        UUID groupId = UUID.randomUUID();
        OutboundMessage reply = handler.handle(
                new ScopeRef.Group(groupId.toString()),
                "/add-source https://example.com/m1-039-group.xml --tags m1-039-tag");

        assertTrue(reply.text().contains("Only group admins"),
                "non-admin caller in group scope must see the "
                        + "error.add_source.group_admin_only bundle literal — got: "
                        + reply.text());
        assertEquals(0, mockProbe.callCount(),
                "the discriminator must short-circuit BEFORE UrlProbe is invoked");
    }

    // --- helpers ---------------------------------------------------------

    private void insertBannedUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state) "
                             + "VALUES ('inmemory', ?, FALSE, TRUE, 'invited')")) {
            ps.setString(1, contactId);
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /**
     * CDI {@link Alternative} mirror of
     * {@code AddSourceCommandHandlerTest.MockUrlProbe}. Activation is
     * scoped via {@link MvpProfile#getEnabledAlternatives()}; no
     * {@code @Priority} so this mock does not conflict with sibling
     * tests' alternatives outside this profile.
     */
    @Alternative
    @ApplicationScoped
    public static class MockUrlProbe extends UrlProbe {

        private final Map<String, ProbeResult> canned = new ConcurrentHashMap<>();

        private int callCount;

        @Override
        public ProbeResult probe(URI url) {
            callCount++;
            return canned.getOrDefault(
                    url.toString(),
                    ProbeResult.success(200, Optional.empty()));
        }

        int callCount() {
            return callCount;
        }

        void reset() {
            canned.clear();
            callCount = 0;
        }
    }

    /** Same shape as the sibling test profiles in this module. */
    public static final class MvpProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true");
        }

        @Override
        public java.util.Set<Class<?>> getEnabledAlternatives() {
            return java.util.Set.of(MockUrlProbe.class);
        }
    }
}
