package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the injected {@link Clock} to a FIXED instant and asserts that
 * {@link InviteCommandHandler}'s invite-expiry CAP gate and the
 * {@code expires_at} WRITE both decide against that instant — the app-vs-DB
 * split M1-490 closed. Before M1-490, {@code expires_at} was written on
 * {@code OffsetDateTime.now()} but the open/contact cap counts compared it with
 * SQL {@code NOW()}, so the cap gate was unpinnable. The cap-count cutoff is now
 * bound from the injected Clock, the same Clock the write samples.
 *
 * <p>Each test is <em>discriminating</em>: its verdict under the pinned clock is
 * the OPPOSITE of the verdict under the wall clock, so a gate still reading SQL
 * {@code NOW()} would fail the assertion. The consume-time
 * {@code expires_at > NOW()} check (InviteCodeConsumer) and the {@code /invite
 * list} display filter intentionally stay on the DB clock — see
 * {@code docs/plan/m1/now-clock-audit.md} — so they are not asserted here.
 */
@QuarkusTest
class InviteExpiryClockIT {

    private static final String PREFIX = "m1-490-invite-expiry-";
    private static final String ADAPTER = "inmemory";
    private static final Instant PINNED_NOW = Instant.parse("2026-05-25T09:00:00Z");
    // Far future so a seeded code whose expires_at sits between the wall clock
    // (2026) and this instant reads as EXPIRED under the pinned clock but ACTIVE
    // under SQL NOW() — the discriminating condition for the cap-count test.
    private static final Instant PINNED_FUTURE = Instant.parse("2200-01-01T00:00:00Z");
    private static final Instant SEED_EXPIRES_AT = Instant.parse("2100-01-01T00:00:00Z");

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Inject
    InviteCommandHandler handler;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @ConfigProperty(name = "infochat.invite.ttl", defaultValue = "7d")
    Duration inviteTtl;

    @ConfigProperty(name = "infochat.invite.open-cap-per-adapter", defaultValue = "3")
    int openCap;

    private String adminContact;

    @BeforeEach
    void setUp() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        adminContact = PREFIX + "admin";
        try (Connection conn = dataSource.getConnection()) {
            // Permanent bot admin (upserted, never deleted) so the audit rows
            // InviteCommandHandler writes — audit_log.actor_user_id is an FK to
            // users(id) — always keep a valid referent. That lets cleanup skip the
            // audit table (and its append-only triggers) entirely: only this IT's
            // invite_code rows need clearing, and invite_code has no inbound FK
            // from audit_log (audit target_id is plain text).
            exec(conn, "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                    + "VALUES (?, ?, TRUE, 'vouched') "
                    + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                    + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, adminContact);
            exec(conn, "DELETE FROM invite_code WHERE created_by IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE ?)", PREFIX + "%");
            exec(conn, "DELETE FROM invite_code WHERE expected_contact_id LIKE ?", PREFIX + "%");
        }
    }

    @Test
    void expiresAtWrite_usesInjectedClock() throws Exception {
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        String target = PREFIX + "write-target";

        // --contact executes immediately (no confirm gate). With zero pending
        // contact codes the global cap is not in play; the create succeeds.
        OutboundMessage reply = handler.handle(new ScopeRef.Dm(adminContact),
                "/invite create --adapter " + ADAPTER + " --contact " + target);
        assertNotNull(extractUuid(reply.text()),
                "/invite create --contact must succeed and return the new code");

        // expires_at must be PINNED_NOW + ttl exactly — proving the write sampled
        // the injected Clock, not Instant.now() (which would land on the wall date).
        assertEquals(PINNED_NOW.plus(inviteTtl), readExpiresAtForExpectedContact(target),
                "expires_at must be the injected now + invite TTL");
    }

    @Test
    void openCapCount_usesInjectedClock() throws Exception {
        QuarkusMock.installMockForType(Clock.fixed(PINNED_FUTURE, ZoneOffset.UTC), Clock.class);
        // Fill the per-adapter open cap with PENDING codes whose expires_at is
        // BEFORE the pinned now (expired → must NOT count) but AFTER the wall
        // clock (active → WOULD count under SQL NOW()).
        for (int i = 0; i < openCap; i++) {
            seedOpenInvite(SEED_EXPIRES_AT);
        }

        // --open is confirm-gated: the first call prompts, the second (… confirm)
        // runs createOpen's cap check under the pinned clock.
        handler.handle(new ScopeRef.Dm(adminContact),
                "/invite create --adapter " + ADAPTER + " --open");
        OutboundMessage reply = handler.handle(new ScopeRef.Dm(adminContact),
                "/invite create --open confirm");

        assertNotNull(extractUuid(reply.text()),
                "the open cap is not met under the pinned clock (every seeded code is "
                        + "expired), so the confirm creates a new code; under SQL NOW() the seeded "
                        + "codes would count, the cap would be met, and no code returned");
    }

    private void seedOpenInvite(Instant expiresAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO invite_code (code, invite_type, adapter, expected_contact_id, "
                     + "status, created_by, expires_at) "
                     + "VALUES (?, 'OPEN_ADAPTER', ?, NULL, 'PENDING', "
                     + "(SELECT id FROM users WHERE adapter = ? AND contact_id = ?), ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, ADAPTER);
            ps.setString(3, ADAPTER);
            ps.setString(4, adminContact);
            ps.setObject(5, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
            ps.executeUpdate();
        }
    }

    private Instant readExpiresAtForExpectedContact(String expectedContactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT expires_at FROM invite_code WHERE expected_contact_id = ?")) {
            ps.setString(1, expectedContactId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the created CONTACT_BOUND invite row must exist");
                return rs.getTimestamp("expires_at").toInstant();
            }
        }
    }

    private static @Nullable UUID extractUuid(String text) {
        Matcher matcher = UUID_PATTERN.matcher(text);
        return matcher.find() ? UUID.fromString(matcher.group()) : null;
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
