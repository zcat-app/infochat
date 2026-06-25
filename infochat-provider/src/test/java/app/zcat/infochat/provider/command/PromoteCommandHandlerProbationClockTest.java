package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.group.GroupRepository;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the injected {@link Clock} to a FIXED instant and asserts the
 * {@code /promote} target-probation gate ({@code TargetRow.inProbation(now)})
 * decides against that instant — not the wall-clock run date (M1-451).
 *
 * <p>The discriminating case is the still-in-probation target: its
 * {@code probation_until} is a FIXED instant one minute AFTER
 * {@code PINNED_NOW}. Under the injected fixed clock it is in the future
 * (still in probation → promote refused); under the real wall clock it would
 * be far in the past (past probation → promote would succeed). Asserting
 * "refused" therefore proves the gate reads the injected Clock, not
 * {@code Instant.now()}. Mirrors {@code GroupAutoPromoteServiceClockIT}.
 */
@QuarkusTest
class PromoteCommandHandlerProbationClockTest {

    private static final String ADAPTER = "inmemory";
    private static final String PREFIX = "m1-451-promote-clock-";
    private static final Instant PINNED_NOW = Instant.parse("2026-05-25T09:00:00Z");
    private final String upstreamGroupId = PREFIX + "group-" + UUID.randomUUID();

    @Inject PromoteCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject GroupRepository groupRepository;

    private String adminContactId;
    private UUID groupId;

    @BeforeEach
    void setup() throws Exception {
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        inboundContext.setAdapterName(ADAPTER);

        adminContactId = PREFIX + "admin-" + UUID.randomUUID();
        try (Connection conn = dataSource.getConnection()) {
            // Guardian admin keeps the last-admin protection trigger satisfied.
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE SET is_admin = TRUE",
                    ADAPTER, "guardian-" + PREFIX + "permanent");
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE SET is_admin = TRUE",
                    ADAPTER, adminContactId);
        }
        groupId = groupRepository.findOrCreateByAdapterAndUpstreamId(ADAPTER, upstreamGroupId);
    }

    @Test
    void probationGate_decidesOnInjectedClock_refusesTargetStillInProbation() throws Exception {
        // probation_until one minute AFTER the pinned instant: still in
        // probation under the fixed clock, but in the past under the real clock.
        String targetContactId = PREFIX + "target-inprobation-" + UUID.randomUUID();
        UUID targetId = seedMember(targetContactId, PINNED_NOW.plusSeconds(60));

        OutboundMessage result = promote(targetContactId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_PROMOTE_TARGET_PROBATION), result.text(),
                "a target whose probation_until is after the injected now must be refused, "
                        + "even though it is in the past on the wall clock");
        assertFalse(isGroupAdmin(targetId),
                "the refused target must not have been promoted");
    }

    @Test
    void probationGate_decidesOnInjectedClock_promotesTargetPastProbation() throws Exception {
        // probation_until one minute BEFORE the pinned instant: probation
        // elapsed under the fixed clock → the gate allows the promotion.
        String targetContactId = PREFIX + "target-pastprobation-" + UUID.randomUUID();
        UUID targetId = seedMember(targetContactId, PINNED_NOW.minusSeconds(60));

        OutboundMessage result = promote(targetContactId);

        assertTrue(isGroupAdmin(targetId),
                "a target whose probation_until is before the injected now must be promoted; reply was: "
                        + result.text());
    }

    private OutboundMessage promote(String targetContactId) {
        inboundContext.setSenderContactId(adminContactId);
        return handler.handle(new ScopeRef.Group(upstreamGroupId), "/promote " + targetContactId);
    }

    private UUID seedMember(String contactId, Instant probationUntil) throws Exception {
        UUID userId = UUID.randomUUID();
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (id, adapter, contact_id, is_admin, is_banned, "
                            + "registration_state, probation_until) "
                            + "VALUES (?, ?, ?, FALSE, FALSE, 'invited', ?)")) {
                ps.setObject(1, userId);
                ps.setString(2, ADAPTER);
                ps.setString(3, contactId);
                ps.setTimestamp(4, Timestamp.from(probationUntil));
                ps.executeUpdate();
            }
            exec(conn, "INSERT INTO group_membership (group_id, user_id) VALUES (?, ?) "
                    + "ON CONFLICT DO NOTHING", groupId, userId);
        }
        return userId;
    }

    private boolean isGroupAdmin(UUID userId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_group_admin FROM group_membership "
                             + "WHERE group_id = ? AND user_id = ? AND removed_at IS NULL")) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean("is_group_admin");
            }
        }
    }

    private static void exec(Connection conn, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }
}
