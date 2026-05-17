package io.infochat.provider.messaging;

import io.infochat.messaging.OutboundMessage;
import io.infochat.messaging.ScopeRef;
import io.infochat.messaging.impl.inmemory.InMemoryAdapter;
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
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral tests for {@link InboundRouter}'s entry-point dispatch.
 * Drives inbound messages through the real production wiring
 * (Quarkus boots the registry via {@link MessagingStartup}; the
 * single InMemoryAdapter is bound to the router) and asserts the
 * five branches from acceptance items 8–12:
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
 * </ol>
 *
 * <p>The exception path test installs a test-only
 * {@link CommandHandler} bean ({@link BoomHandler}) that throws on
 * invocation; the inbound body {@code "/boom"} routes to it.</p>
 */
@QuarkusTest
@TestProfile(InboundRouterTest.Profile.class)
class InboundRouterTest {

    @Inject
    InMemoryAdapter inMemoryAdapter;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void resetAdapterState() throws Exception {
        inMemoryAdapter.reset();
        // M1-035d wired AutoRegisterService into the router, so every
        // inbound now inserts a users row. Clean up the contact_ids
        // this class uses so accumulated rows do not race the M1-007a
        // bootstrap-admin row (deferred) or other test classes' fixtures.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM users WHERE adapter = 'inmemory' AND ("
                             + "contact_id = 'alice' "
                             + "OR contact_id LIKE 'fresh-%' "
                             + "OR contact_id LIKE 'same-contact-%')")) {
            ps.executeUpdate();
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
    void chatModeBodyProducesDeterministicNotInMvpReply() {
        inMemoryAdapter.deliverDm("alice", "hello there");

        List<OutboundMessage> sent = inMemoryAdapter.sentMessages();
        assertEquals(1, sent.size(),
                "chat-mode body should produce exactly one outbound reply");
        assertEquals(InboundRouter.CHAT_MODE_REPLY, sent.get(0).text());
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
    void firstDmSlashInsertsUsersRowBeforeDispatchUnderInmemoryAdapterName() throws Exception {
        inMemoryAdapter.deliverDm("fresh-contact-1", "/xyz");

        assertEquals(1, inMemoryAdapter.sentMessages().size(),
                "router must still reply on the dispatch path");
        assertEquals(1L, countUsersRows("inmemory", "fresh-contact-1"),
                "auto-register must insert a row keyed on (adapter='inmemory', "
                        + "contact_id='fresh-contact-1') — the lambda in AdapterRegistry "
                        + "passes the real adapter.name(), not a hardcoded literal");
    }

    @Test
    void firstDmChatModeInsertsUsersRowBeforeChatModeReply() throws Exception {
        inMemoryAdapter.deliverDm("fresh-contact-2", "hello there");

        List<OutboundMessage> sent = inMemoryAdapter.sentMessages();
        assertEquals(1, sent.size(), "chat-mode reply must still fire");
        assertEquals(InboundRouter.CHAT_MODE_REPLY, sent.get(0).text(),
                "the existing chat-mode-not-in-MVP reply contract is unchanged");
        assertEquals(1L, countUsersRows("inmemory", "fresh-contact-2"),
                "auto-register must run on chat-mode inbound too — the wiring "
                        + "sits upstream of the slash-vs-chat split");
    }

    @Test
    void repeatedDmsFromSameContactProduceExactlyOneUsersRow() throws Exception {
        inMemoryAdapter.deliverDm("same-contact-1", "/xyz");
        inMemoryAdapter.deliverDm("same-contact-1", "hello there");

        assertEquals(2, inMemoryAdapter.sentMessages().size(),
                "both inbounds must still produce their replies (no short-circuit)");
        assertEquals(1L, countUsersRows("inmemory", "same-contact-1"),
                "two consecutive DMs from the same contact must produce exactly "
                        + "one users row — AutoRegisterService's ON CONFLICT DO NOTHING "
                        + "absorbs the second insert");
    }

    private long countUsersRows(String adapter, String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
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
