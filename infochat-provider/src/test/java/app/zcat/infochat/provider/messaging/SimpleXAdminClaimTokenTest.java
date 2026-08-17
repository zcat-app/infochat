package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-506 / decision D50 — SimpleX bootstrap-admin claim-token, driven
 * through the real {@link InboundRouter#onMessage} intake so the test
 * exercises the step-2 wiring (claim BEFORE invite consume) together with
 * {@link SimpleXAdminClaim}.
 *
 * <p>Identity is the connection-based {@code contactId}: the test
 * synthesises an {@link InboundMessage} the way the SimpleX codec would
 * and dispatches it with {@code adapterName = "simplex"}. Replies are
 * captured by a {@link RecordingSimplexAdapter} bound as the router's
 * reply target for the {@code "simplex"} name (the package-private
 * {@link InboundRouter#setReplyTarget} is reachable because this test
 * lives in the router's package). The real {@code "inmemory"} adapter
 * remains the activated/booted adapter — this test never activates a real
 * SimpleX transport.</p>
 *
 * <p><b>Single-use is global per simplex admin row</b> (the
 * {@code WHERE NOT EXISTS (… adapter='simplex' AND is_admin)} gate), so
 * {@link #cleanup()} removes every {@code adapter='simplex'} users row (and
 * its audit / attempt rows) before each test, making the "first claim"
 * deterministic regardless of run order. The boot-seeded inmemory
 * bootstrap admin remains the global last-admin guardian, so deleting the
 * simplex admin rows never trips last-admin protection.</p>
 */
@QuarkusTest
@TestProfile(SimpleXAdminClaimTokenTest.Profile.class)
class SimpleXAdminClaimTokenTest {

    private static final String SIMPLEX = "simplex";
    private static final String ADMIN_TOKEN = "s3cr3t-simplex-admin-claim-token-001";
    private static final String PREFIX = "m1-506-claim-";

    @Inject InboundRouter inboundRouter;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;

    private final RecordingSimplexAdapter replyTarget = new RecordingSimplexAdapter();

    @BeforeEach
    void cleanup() throws Exception {
        replyTarget.reset();
        inboundRouter.setReplyTarget(replyTarget);
        try (var conn = dataSource.getConnection()) {
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                // The claim audit row records actor_adapter='simplex'; clear
                // both that and any prefix-scoped target rows from prior runs.
                exec(conn,
                        "DELETE FROM audit_log WHERE actor_adapter = ? OR target_contact_id LIKE ?",
                        SIMPLEX, PREFIX + "%");
                exec(conn, "DELETE FROM invite_code_attempt WHERE adapter = ?", SIMPLEX);
                exec(conn, "UPDATE users SET banned_by = NULL WHERE adapter = ?", SIMPLEX);
                // Remove every simplex users row: single-use is gated on the
                // global presence of a (simplex, is_admin) row, so a leftover
                // would make the "first claim" non-deterministic.
                exec(conn, "DELETE FROM users WHERE adapter = ?", SIMPLEX);
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
        }
    }

    @Test
    void firstDmMatchingTokenRegistersContactAndSetsAdmin() throws Exception {
        String contactId = PREFIX + "first-admin";

        deliverSimplexDm(contactId, ADMIN_TOKEN);

        // The connection-based contactId becomes a bootstrap-shaped admin row.
        assertTrue(isAdminAt(contactId),
                "the first DM presenting the token must set is_admin=true on the sender's row");
        assertEquals("vouched", registrationStateAt(contactId),
                "a claimed admin is bootstrap-shaped: registration_state='vouched'");
        assertNull(probationUntilEpochAt(contactId),
                "a claimed admin skips the slow-start tier: probation_until IS NULL");
        // The user-visible outcome is the admin-claim welcome — a distinct
        // reply that states the sender is now the administrator, NOT the
        // shared probation welcome an invite-accept sends (M1-624). A claimed
        // admin has full access with no probation, so reusing the probation
        // welcome (REPLY_WELCOME_DM_FRESH) would misstate it.
        assertEquals(List.of(bundleLoader.get(BundleKeys.REPLY_WELCOME_ADMIN_CLAIM)),
                replyTarget.sentBodies(),
                "a successful claim sends the distinct admin-claim welcome");
    }

    @Test
    void secondPresentationFromDifferentContactGrantsNothingAndRepliesLikeInvalid() throws Exception {
        String firstContact = PREFIX + "claimer";
        String secondContact = PREFIX + "would-be-admin";

        deliverSimplexDm(firstContact, ADMIN_TOKEN);
        assertTrue(isAdminAt(firstContact), "precondition: first contact claimed admin");
        replyTarget.reset();

        // A different contact presenting the SAME (now-used) token.
        deliverSimplexDm(secondContact, ADMIN_TOKEN);

        assertFalse(userRowExistsAt(secondContact),
                "a used token must grant nothing — no row for the second contact");
        assertFalse(isAdminAt(secondContact),
                "a used token must not make anyone else admin");
        // No oracle on token validity: the reply is the SAME fixed reply an
        // invalid/absent invite produces (the claim falls through to the
        // invite path, which Rejects a non-UUID body).
        assertEquals(List.of(bundleLoader.get(BundleKeys.ERROR_INVITE_REQUIRED)),
                replyTarget.sentBodies(),
                "a used token surfaces the same fixed reply an invalid invite would");
    }

    @Test
    void nearMissTokenDoesNotClaimAndRepliesLikeInvalid() throws Exception {
        String contactId = PREFIX + "near-miss";

        // A near-miss (token plus one trailing char) must not claim — the
        // constant-time compare rejects it — and must give the invalid-invite
        // reply, leaking no signal that it was "close".
        deliverSimplexDm(contactId, ADMIN_TOKEN + "x");

        assertFalse(userRowExistsAt(contactId),
                "a near-miss token must grant nothing and create no row");
        assertEquals(List.of(bundleLoader.get(BundleKeys.ERROR_INVITE_REQUIRED)),
                replyTarget.sentBodies(),
                "a near-miss token surfaces the same fixed reply an invalid invite would");
    }

    // ----- helpers ---------------------------------------------------------

    private void deliverSimplexDm(String contactId, String body) {
        Identity sender = new Identity(contactId, contactId, Instant.now());
        InboundMessage msg = new InboundMessage(
                sender,
                new ScopeRef.Dm(contactId),
                body,
                Instant.now(),
                "simplex-msg-" + contactId);
        inboundRouter.onMessage(msg, SIMPLEX);
    }

    private boolean isAdminAt(String contactId) throws Exception {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT is_admin FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, SIMPLEX);
            ps.setString(2, contactId);
            try (var rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private boolean userRowExistsAt(String contactId) throws Exception {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT 1 FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, SIMPLEX);
            ps.setString(2, contactId);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String registrationStateAt(String contactId) throws Exception {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT registration_state FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, SIMPLEX);
            ps.setString(2, contactId);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a users row at (simplex, " + contactId + ")");
                return rs.getString(1);
            }
        }
    }

    private Long probationUntilEpochAt(String contactId) throws Exception {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT probation_until FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, SIMPLEX);
            ps.setString(2, contactId);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a users row at (simplex, " + contactId + ")");
                var ts = rs.getTimestamp(1);
                return ts == null ? null : ts.getTime();
            }
        }
    }

    private static void exec(java.sql.Connection conn, String sql, Object... args) throws Exception {
        try (var ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    /**
     * Minimal recording reply target with {@code name() = "simplex"} so the
     * router routes claim/invite replies to it. Only {@link #send} is
     * exercised (the reply path); the edit/typing methods are unused.
     */
    static final class RecordingSimplexAdapter implements MessagingAdapter {
        private static final CapabilityFlags CAPS = new CapabilityFlags(
                true, false, false, false, 16384, 5, true, false, false, Duration.ofMillis(600),
                false, 0); // supportsOutboundAttachments=false, maxOutboundAttachmentBytes=0

        private final List<OutboundMessage> sent = new CopyOnWriteArrayList<>();

        void reset() {
            sent.clear();
        }

        List<String> sentBodies() {
            return sent.stream().map(OutboundMessage::text).toList();
        }

        @Override
        public String name() {
            return SIMPLEX;
        }

        @Override
        public CapabilityFlags capabilities() {
            return CAPS;
        }

        @Override
        public AdapterTrustLevel trustLevel() {
            return AdapterTrustLevel.HIGH;
        }

        @Override
        public boolean isWellFormedContactId(String contactId) {
            return true;
        }

        @Override
        public MessageHandle send(OutboundMessage msg) {
            sent.add(msg);
            return new MessageHandle("rec-" + sent.size());
        }

        @Override
        public void update(MessageHandle handle, String body) {
            throw new UnsupportedOperationException("recording reply target: update unused");
        }

        @Override
        public void finalizeMessage(MessageHandle handle, String body) {
            throw new UnsupportedOperationException("recording reply target: finalize unused");
        }

        @Override
        public void setTyping(ScopeRef scope, boolean typing) {
            // no-op
        }

        @Override
        public void setInboundHandler(InboundHandler handler) {
            // no-op: this fake is a reply target only; inbound is driven by
            // calling inboundRouter.onMessage directly.
        }
    }

    /**
     * Adds only the SimpleX admin-token; the base {@code %test} profile
     * supplies {@code infochat.adapters=inmemory} and the inmemory
     * bootstrap admin, so the deployment boots normally and the inmemory
     * admin is the global last-admin guardian.
     */
    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("infochat.adapters.simplex.admin-token", ADMIN_TOKEN);
        }
    }
}
