package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Umbrella integration test for MVP exit criterion §3 per
 * {@code docs/design/00-mvp.md} §6: a non-admin, invite-registered
 * user sending a DM via {@code InMemoryAdapter} reaches the
 * {@code /help} handler and receives its reply. M1-035a's per-class
 * adapter test, M1-035b's per-class registry/router/startup-gate
 * tests, and M1-035c's per-class handler/bundle tests each verify
 * their own slice in isolation. The full chain — adapter → registry
 * → router → CommandHandler lookup → HelpCommandHandler →
 * BundleLoader → OutboundMessage → adapter outbound queue — is
 * asserted here against the production CDI graph. The {@code users}
 * row is pre-seeded {@code 'invited'} in {@code @BeforeEach}
 * (mirroring the post-step-2 successful-invite state, D44), so the
 * DM dispatch reaches the handler rather than the invite gate.
 *
 * <p>The asserted invariants:</p>
 * <ol>
 *   <li>DM /help reply: a registered ({@code 'invited'}) contact
 *       sending {@code /help} produces exactly one outbound message
 *       whose body equals the bundle-composed /help reply, with the
 *       pre-seeded row intact ({@code is_admin=FALSE},
 *       {@code registration_state='invited'},
 *       {@code probation_until ≈ NOW() + 24h}).</li>
 *   <li>Idempotency: a second {@code /help} from the same contact id
 *       does NOT insert a second users row but DOES produce a second
 *       outbound /help reply.</li>
 *   <li>Unknown-command friendly reply: an unrecognised slash
 *       command produces exactly one outbound message whose body
 *       equals the bundle value for {@code error.unknown_command} —
 *       not a silent drop, not an exception trace.</li>
 * </ol>
 *
 * <p>The expected /help body is composed via {@link BundleLoader}
 * (same source the {@link HelpCommandHandler} reads) so a future
 * en.properties edit does not require an IT change. The expected
 * unknown-command body is read from the same bundle for the same
 * reason.</p>
 *
 * <p>The {@link MvpProfile} below is redundant with the
 * {@code %test.infochat.adapters=inmemory} declarations in
 * {@code application.properties} but is declared explicitly here so
 * the IT's adapter-configuration dependency is self-contained — a
 * reader of this file sees which properties the test relies on
 * without cross-referencing the production properties file.</p>
 */
@QuarkusTest
@TestProfile(AdapterRouterIT.MvpProfile.class)
class AdapterRouterIT {

    @Inject InMemoryAdapter adapter;

    @Inject BundleLoader bundleLoader;

    @Inject DataSource dataSource;

    @BeforeEach
    void resetAdapterAndCleanMvpContacts() throws Exception {
        adapter.reset();
        // Each @Test seeds contact ids in the "mvp-" namespace; clean only
        // those so the umbrella does not race other tests' fixtures or the
        // bootstrap-admin row (deferred).
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM users WHERE contact_id LIKE 'mvp-%'")) {
            ps.executeUpdate();
        }
        // M1-044b: pre-seed INSERT INTO users for mvp-user-1 + mvp-user-2
        // with registration_state='invited' — the post-step-2 successful-
        // invite state, mirroring what InviteCodeConsumer.Accepted would
        // have written — so step 2 (DM unknown) skips and dispatch reaches
        // the handler. probation_until is set to NOW()+24h to satisfy
        // assertUsersRowMatchesMvpDefaults's ±30s window check.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, "
                             + "registration_state, probation_until) "
                             + "VALUES (?, ?, FALSE, 'invited', ?) "
                             + "ON CONFLICT (adapter, contact_id) DO NOTHING")) {
            OffsetDateTime probationOneDayOut = OffsetDateTime.now().plusHours(24);
            ps.setString(1, "inmemory");
            ps.setString(2, "mvp-user-1");
            ps.setObject(3, probationOneDayOut);
            ps.executeUpdate();
            ps.setString(1, "inmemory");
            ps.setString(2, "mvp-user-2");
            ps.setObject(3, probationOneDayOut);
            ps.executeUpdate();
        }
    }

    @Test
    void firstDmAutoRegistersUserAndRepliesWithHelp() throws Exception {
        Instant before = Instant.now();
        adapter.deliverDm("mvp-user-1", "/help");
        Instant after = Instant.now();

        assertUsersRowMatchesMvpDefaults("mvp-user-1", before, after);

        List<OutboundMessage> sent = adapter.sentMessages();
        assertEquals(1, sent.size(),
                "exactly one outbound reply must be produced for the first /help");
        assertEquals(expectedHelpBody(), sent.get(0).text(),
                "outbound body must equal the bundle-composed /help reply");
    }

    @Test
    void secondHelpFromSameContactIdIsIdempotentForUsersRowButRepliesAgain() throws Exception {
        adapter.deliverDm("mvp-user-1", "/help");
        adapter.deliverDm("mvp-user-1", "/help");

        assertEquals(1L, countUsersRows("mvp-user-1"),
                "second /help from the same contact must NOT insert a second users row "
                        + "(the @BeforeEach seed is the only insert; DM dispatch writes no users row)");
        assertEquals(2, adapter.sentMessages().size(),
                "second /help must still produce its own outbound reply");
    }

    @Test
    void unknownCommandProducesBundleKeyedFriendlyReply() throws Exception {
        // M1-045: the @BeforeEach pre-seeds mvp-user-2 with
        // probation_until = NOW() + 24h (kept to satisfy the
        // firstDmAutoRegistersUserAndRepliesWithHelp assertion that
        // pins the auto-register defaults). Unknown commands fail
        // closed at step 5 — the probation gate returns its blocked
        // reply BEFORE step 6 (parse → unknown-command), so the
        // bundle-keyed error.unknown_command reply this test asserts
        // would never fire. Promote mvp-user-2 past probation for
        // this scenario so the unknown-command parse path still
        // exercises end-to-end. The other tests in this file send
        // /help (allowed during probation) and remain untouched.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET probation_until = NULL WHERE contact_id = ?")) {
            ps.setString(1, "mvp-user-2");
            ps.executeUpdate();
        }
        adapter.deliverDm("mvp-user-2", "/unknown-command");

        List<OutboundMessage> sent = adapter.sentMessages();
        assertEquals(1, sent.size(),
                "unknown command must produce exactly one outbound reply (no silent drop)");
        assertEquals(bundleLoader.get(BundleKeys.ERROR_UNKNOWN_COMMAND), sent.get(0).text(),
                "outbound body must equal the bundle value for error.unknown_command "
                        + "(not an exception trace, not a hardcoded English string drift)");
    }

    private String expectedHelpBody() {
        return bundleLoader.get(BundleKeys.HELP_HEADER_DM_USER)
                + "\n" + bundleLoader.get(BundleKeys.HELP_CMD_HELP_SHORT)
                + "\n" + bundleLoader.get(BundleKeys.HELP_CMD_ADD_SOURCE_SHORT)
                + "\n" + bundleLoader.get(BundleKeys.HELP_CMD_SUMMARY_SHORT);
    }

    private void assertUsersRowMatchesMvpDefaults(String contactId, Instant before, Instant after)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_admin, registration_state, probation_until "
                             + "FROM users WHERE adapter = 'inmemory' AND contact_id = ?")) {
            ps.setString(1, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(),
                        "users row must exist for adapter='inmemory' contact_id='" + contactId + "'");
                assertFalse(rs.getBoolean("is_admin"),
                        "is_admin must be FALSE for invite-registered users");
                assertEquals("invited", rs.getString("registration_state"),
                        "registration_state must be 'invited' — the @BeforeEach pre-seeds the "
                                + "row in the post-step-2 successful-invite state, mirroring what "
                                + "InviteCodeConsumer.Accepted would have written; a DM inbound "
                                + "performs no registration write");
                Timestamp probation = rs.getTimestamp("probation_until");
                assertNotNull(probation,
                        "probation_until must be populated to NOW() + slow_start_window "
                                + "(default infochat.probation.duration=24h)");
                Instant actual = probation.toInstant();
                Instant expectedMin = before.plus(Duration.ofHours(24)).minusSeconds(30);
                Instant expectedMax = after.plus(Duration.ofHours(24)).plusSeconds(30);
                assertTrue(!actual.isBefore(expectedMin) && !actual.isAfter(expectedMax),
                        "probation_until=" + actual + " must lie in ["
                                + expectedMin + ", " + expectedMax + "]");
                assertFalse(rs.next(),
                        "exactly one users row must match (adapter='inmemory', contact_id='"
                                + contactId + "')");
            }
        }
    }

    private long countUsersRows(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM users WHERE adapter = 'inmemory' AND contact_id = ?")) {
            ps.setString(1, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    /**
     * Pins the minimum properties AdapterRegistry's gate 1 (non-empty
     * list), gate 2 (name resolves), and gate 6 (LOW-trust opt-in)
     * need to pass for the {@code inmemory} adapter. Redundant with
     * {@code %test.*} in {@code application.properties} but declared
     * here so the IT's dependency on
     * {@code infochat.adapters=inmemory} and
     * {@code infochat.adapters.inmemory.allow-low-trust=true} is
     * visible to a reader of this single file.
     */
    public static final class MvpProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true");
        }
    }
}
