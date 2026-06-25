package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the injected {@link Clock} to a FIXED instant and asserts the
 * {@code /vouch} past-probation permission pre-check
 * ({@code isAlreadyPastProbation(row, now)}) decides against that instant —
 * not the wall-clock run date (M1-451).
 *
 * <p>The discriminating case is the still-in-probation target: its
 * {@code probation_until} is a FIXED instant one minute AFTER
 * {@code PINNED_NOW}. Under the injected fixed clock it is in the future
 * (still in probation → /vouch proceeds and clears it → REPLY_VOUCH_SUCCESS);
 * under the real wall clock it would be far in the past (past probation →
 * /vouch would no-op → REPLY_VOUCH_NOOP). Asserting "success" therefore
 * proves the gate reads the injected Clock, not {@code Instant.now()}.
 * Mirrors {@code GroupAutoPromoteServiceClockIT}.
 */
@QuarkusTest
class VouchCommandHandlerProbationClockTest {

    private static final String ADAPTER = "inmemory";
    private static final String PREFIX = "m1-451-vouch-clock-";
    private static final Instant PINNED_NOW = Instant.parse("2026-05-25T09:00:00Z");

    @Inject VouchCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;

    @BeforeEach
    void setup() {
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        inboundContext.setAdapterName(ADAPTER);
    }

    @Test
    void probationGate_decidesOnInjectedClock_proceedsWhenStillInProbation() throws Exception {
        // probation_until one minute AFTER the pinned instant: still in
        // probation under the fixed clock, but in the past under the real clock.
        String actor = PREFIX + "proceed-actor-" + UUID.randomUUID();
        String target = PREFIX + "proceed-target-" + UUID.randomUUID();
        seedUser(actor, true, "vouched", null);
        UUID targetId = seedUser(target, false, "invited", PINNED_NOW.plusSeconds(60));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/vouch " + target);

        assertEquals(bundleLoader.get(BundleKeys.REPLY_VOUCH_SUCCESS), reply.text(),
                "a target whose probation_until is after the injected now is still in probation, so "
                        + "/vouch must proceed and clear it — even though it is in the past on the wall clock");
        assertNull(readProbationUntil(targetId),
                "probation_until must be NULL after the proceeding /vouch");
    }

    @Test
    void probationGate_decidesOnInjectedClock_noOpWhenPastProbation() throws Exception {
        // probation_until one minute BEFORE the pinned instant: probation
        // elapsed under the fixed clock → the pre-check short-circuits to no-op.
        String actor = PREFIX + "noop-actor-" + UUID.randomUUID();
        String target = PREFIX + "noop-target-" + UUID.randomUUID();
        seedUser(actor, true, "vouched", null);
        UUID targetId = seedUser(target, false, "invited", PINNED_NOW.minusSeconds(60));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/vouch " + target);

        assertEquals(bundleLoader.get(BundleKeys.REPLY_VOUCH_NOOP), reply.text(),
                "a target whose probation_until is before the injected now is past probation → no-op");
        assertEquals(PINNED_NOW.minusSeconds(60), readProbationUntil(targetId).toInstant(),
                "the no-op path must leave probation_until untouched");
    }

    private UUID seedUser(String contactId, boolean isAdmin, String registrationState,
                          Instant probationUntil) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state, "
                             + "probation_until) VALUES (?, ?, ?, ?, ?) RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            ps.setString(4, registrationState);
            if (probationUntil == null) {
                ps.setNull(5, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                ps.setTimestamp(5, Timestamp.from(probationUntil));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private Timestamp readProbationUntil(UUID id) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT probation_until FROM users WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp("probation_until");
            }
        }
    }
}
