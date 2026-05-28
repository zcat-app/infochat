package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral tests for {@link InboundRouter}'s entry-point dispatch.
 * Drives inbound messages through the real production wiring
 * (Quarkus boots the registry via {@link MessagingStartup}; the
 * single InMemoryAdapter is bound to the router) and asserts the
 * five M1-035b branches plus the M1-044b silent-rate-cap-drop:
 *
 * <ol>
 *   <li>Empty / whitespace / bidi-only / zero-width-only bodies →
 *       no outbound reply.</li>
 *   <li>Leading-whitespace {@code "  /help"} → same reply as
 *       {@code "/help"} (both produce the unknown-command literal
 *       since no {@code /help} handler is registered in this
 *       subticket).</li>
 *   <li>Non-slash body → chat-mode-not-in-MVP reply.</li>
 *   <li>Unknown slash command → unknown-command reply.</li>
 *   <li>Command-handler exception → internal-error reply that does
 *       NOT interpolate the exception's text.</li>
 *   <li>(M1-044b) Rate-cap silent drop — when
 *       {@link RateCapBucket#tryAcquire} returns false (bucket
 *       drained), no reply is sent and no downstream service runs.</li>
 * </ol>
 *
 * <p>The M1-035b methods are kept green by an @BeforeEach pre-seed
 * of an {@code alice} users row with
 * {@code registration_state='vouched'} — the post-M1-044b splice
 * routes unknown DM contacts through the invite gate (step 2), so
 * the deterministic UNKNOWN / CHAT_MODE / INTERNAL_ERROR replies the
 * five M1-035b methods assert only fire when the dispatch reaches
 * {@code handleSlash}. Pre-seeding the contact as a known
 * {@code 'vouched'} user makes step 2 (DM unknown) skip.</p>
 *
 * <p>M1-035d's three first-DM auto-register tests were REMOVED by
 * M1-044b: their premise (a DM from an unknown contact auto-creates
 * a users row) is spec-invalidated by the splice. Replacement
 * coverage lives in
 * {@link InboundRouterIntakeOrderingTest} scenarios (d) DM unknown +
 * valid invite → welcome and (e) DM unknown + invalid invite →
 * invite-required.</p>
 */
@QuarkusTest
@TestProfile(InboundRouterTest.Profile.class)
class InboundRouterTest {

    @Inject
    InMemoryAdapter inMemoryAdapter;

    @Inject
    DataSource dataSource;

    @Inject
    InboundRouter inboundRouter;

    @Inject
    RateCapBucket rateCapBucket;

    @BeforeEach
    void resetAdapterState() throws Exception {
        inMemoryAdapter.reset();
        // Clean up any users rows this class touches before each test,
        // then pre-seed the alice contact as a vouched user so the
        // M1-044b splice's step 2 (DM unknown → invite gate) does not
        // short-circuit the deterministic UNKNOWN / CHAT_MODE /
        // INTERNAL_ERROR reply assertions in the M1-035b methods.
        // Clean chat_session rows first (FK from chat_session → users
        // blocks DELETE FROM users when chat-mode dispatch has persisted
        // session rows for these contacts).
        try (Connection conn = dataSource.getConnection();
             PreparedStatement cleanSessions = conn.prepareStatement(
                     "DELETE FROM chat_session WHERE user_id IN ("
                             + "SELECT id FROM users WHERE adapter = 'inmemory' AND ("
                             + "contact_id = 'alice' "
                             + "OR contact_id LIKE 'rate-overflow-%'))")) {
            cleanSessions.executeUpdate();
        }
        // Delete rate-overflow contacts only; alice cannot be DELETEd
        // when audit_log rows reference her id (audit_log is append-only,
        // Invariant 10). The upsert below resets her state instead.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement clean = conn.prepareStatement(
                     "DELETE FROM users WHERE adapter = 'inmemory' "
                             + "AND contact_id LIKE 'rate-overflow-%'")) {
            clean.executeUpdate();
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement seed = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, "
                             + "registration_state, probation_until) "
                             + "VALUES ('inmemory', 'alice', FALSE, 'vouched', ?) "
                             + "ON CONFLICT (adapter, contact_id) DO UPDATE SET "
                             + "registration_state = 'vouched', "
                             + "probation_until = EXCLUDED.probation_until, "
                             + "is_admin = FALSE, is_banned = FALSE")) {
            seed.setObject(1, OffsetDateTime.now().minusHours(24));
            seed.executeUpdate();
        }
    }

    @Test
    void emptyAndWhitespaceAndInvisibleOnlyBodiesAreDropped() {
        inMemoryAdapter.deliverDm("alice", "");
        inMemoryAdapter.deliverDm("alice", "   ");
        inMemoryAdapter.deliverDm("alice", "​");    // zero-width space
        inMemoryAdapter.deliverDm("alice", "‮");    // right-to-left override

        List<OutboundMessage> sent = inMemoryAdapter.sentMessages();
        assertTrue(sent.isEmpty(),
                "empty / whitespace / bidi-only / zero-width-only bodies must produce no outbound, got: "
                        + sent);
    }

    @Test
    void leadingWhitespaceBeforeSlashCommandParsesAsTheCommand() {
        inMemoryAdapter.deliverDm("alice", "/help");
        List<OutboundMessage> baseline = inMemoryAdapter.sentMessages();
        assertEquals(1, baseline.size());
        String baselineReply = baseline.get(0).text();

        inMemoryAdapter.reset();
        inMemoryAdapter.deliverDm("alice", "  /help");
        List<OutboundMessage> indented = inMemoryAdapter.sentMessages();
        assertEquals(1, indented.size());
        assertEquals(baselineReply, indented.get(0).text(),
                "leading whitespace before /help must produce the same reply as /help");
    }

    @Test
    void chatModeBodyDispatchesToChatAgent() {
        inMemoryAdapter.deliverDm("alice", "hello there");

        List<OutboundMessage> sent = inMemoryAdapter.sentMessages();
        assertEquals(1, sent.size(),
                "chat-mode body should produce exactly one outbound reply");
        // Chat mode now dispatches to ChatAgent (M1-063); the reply comes
        // from TestLlmProvider, not the old CHAT_MODE_REPLY constant.
        assertNotEquals(InboundRouter.CHAT_MODE_REPLY, sent.get(0).text(),
                "Should dispatch to ChatAgent, not return the static sentinel");
    }

    @Test
    void unknownCommandProducesFriendlyUnknownCommandReply() {
        inMemoryAdapter.deliverDm("alice", "/xyz");

        List<OutboundMessage> sent = inMemoryAdapter.sentMessages();
        assertEquals(1, sent.size());
        assertEquals(InboundRouter.UNKNOWN_COMMAND_REPLY, sent.get(0).text());
    }

    @Test
    void commandHandlerExceptionProducesInternalErrorReplyWithoutLeakingMessage() {
        inMemoryAdapter.deliverDm("alice", "/boom");

        List<OutboundMessage> sent = inMemoryAdapter.sentMessages();
        assertEquals(1, sent.size(),
                "exception path must still produce one user-visible reply");
        String body = sent.get(0).text();
        assertEquals(InboundRouter.INTERNAL_ERROR_REPLY, body);
        assertFalse(body.contains(BoomHandler.SECRET_LEAK_TEXT),
                "exception's getMessage() must NOT be interpolated into the reply, got: " + body);
    }

    @Test
    void rateCapOverflowDropsSilentlyWithoutOutbound() {
        // Drain the bucket for a unique contact id by calling tryAcquire
        // directly until it returns false (≤ infochat.rate-cap.inbound-per-minute
        // iterations). The router-driven deliverDm below then finds the
        // bucket empty and exercises the spec §Authorization model
        // step 1.5 "drop silently" branch — no outbound, no downstream
        // service consulted.
        String overflowContact = "rate-overflow-1";
        int safetyCap = 1000; // > any reasonable infochat.rate-cap.inbound-per-minute
        int i = 0;
        while (rateCapBucket.tryAcquire("inmemory", overflowContact) && i < safetyCap) {
            i++;
        }
        assertTrue(i < safetyCap,
                "tryAcquire should have returned false within " + safetyCap + " iterations");

        // Reset the adapter so any spurious previous outbound does not
        // mask a regression in the drop-silently branch.
        inMemoryAdapter.reset();

        inMemoryAdapter.deliverDm(overflowContact, "/help");

        assertTrue(inMemoryAdapter.sentMessages().isEmpty(),
                "over-rate-cap inbound must produce zero outbound; got: "
                        + inMemoryAdapter.sentMessages());
        // "No downstream service consulted" — observable proxy: no users
        // row was inserted (InviteCodeConsumer's invite_consume path
        // would have INSERTed even on a Rejected outcome via the
        // invite_code_attempt table; step 4 banCheck reads users; both
        // are silent if rate cap drops before them).
        assertEquals(0L, countUsersRows("inmemory", overflowContact),
                "rate-cap silent drop must not write any users row "
                        + "(no invite consume, no ban-check write side effect)");
    }

    @Test
    void llmRateCapEvictsIdleEntries() {
        UUID userId = UUID.randomUUID();
        int before = inboundRouter.llmRateCapEntryCount();
        assertTrue(inboundRouter.tryAcquireLlmRateCap(userId));
        assertEquals(before + 1, inboundRouter.llmRateCapEntryCount());

        // Simulate time advancing past 2x the 60 s window so the
        // sweep prunes the timestamp and finds the deque empty.
        inboundRouter.evictIdleLlmRateCapEntries(
                System.currentTimeMillis() + 200_000);
        assertEquals(0, inboundRouter.llmRateCapEntryCount(),
                "All entries should be evicted after timestamps age past 2x the window");
    }

    @Test
    void bodySizeCheckDoesNotAllocateArray() {
        // ASCII: 1 byte per char
        assertFalse(InboundRouter.exceedsUtf8ByteLength("hello", 5));
        assertTrue(InboundRouter.exceedsUtf8ByteLength("hello", 4));

        // U+00E9 (é): 2 bytes in UTF-8
        assertFalse(InboundRouter.exceedsUtf8ByteLength("é", 2));
        assertTrue(InboundRouter.exceedsUtf8ByteLength("é", 1));

        // U+20AC (€): 3 bytes in UTF-8
        assertFalse(InboundRouter.exceedsUtf8ByteLength("€", 3));
        assertTrue(InboundRouter.exceedsUtf8ByteLength("€", 2));

        // U+1D11E (𝄞): 4 bytes in UTF-8 (surrogate pair in Java)
        assertFalse(InboundRouter.exceedsUtf8ByteLength("𝄞", 4));
        assertTrue(InboundRouter.exceedsUtf8ByteLength("𝄞", 3));
    }

    private long countUsersRows(String adapter, String contactId) throws RuntimeException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true"
            );
        }
    }

    /**
     * Test-only {@link CommandHandler} that throws on invocation so
     * the router's exception-handling path is exercised. The thrown
     * message includes a recognizable substring that the test
     * assertion confirms is NOT present in the user-visible reply.
     */
    @ApplicationScoped
    public static class BoomHandler implements CommandHandler {
        static final String SECRET_LEAK_TEXT = "SECRET_DB_PASSWORD=hunter2";

        @Override
        public String name() {
            return "boom";
        }

        @Override
        public OutboundMessage handle(ScopeRef scope, String rawText) {
            throw new RuntimeException(SECRET_LEAK_TEXT);
        }
    }
}
