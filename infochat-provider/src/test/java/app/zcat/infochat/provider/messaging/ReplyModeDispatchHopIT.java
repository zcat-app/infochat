package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.ChatReplyModeRegistry;
import app.zcat.infochat.provider.testing.TestLlmProvider;
import app.zcat.infochat.provider.testsupport.DispatchAwaits;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * D79 dispatch-hop pin: the reply mode resolved at
 * intake must survive the M1-634 worker hop. Every interruptible chat
 * turn runs on a pool worker under a FRESH request context seeded with
 * the captured plain values; the resolved {@code ChatReplyMode} crosses
 * the hop as one of them and is re-seeded before the stage runs, so the
 * worker-side {@code ChatAgent} generates under the intake-resolved mode
 * — not the field default.
 */
@QuarkusTest
class ReplyModeDispatchHopIT {

    private static final String ADAPTER = "inmemory";
    private static final String CONTACT_PREFIX = "reply-mode-hop-it-";
    private static final String GUARDIAN = "reply-mode-hop-it-guardian-permanent";
    private static final String GENERATED_REPLY = "English generated reply prose.";
    private static final String TRANSLATED_REPLY = "Cesky prelozeny text odpovedi.";

    @Inject InMemoryAdapter adapter;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject TestLlmProvider testLlmProvider;
    @Inject RegisteredContactSet registeredContactSet;

    @ConfigProperty(name = "infochat.llm.chat.model")
    String chatModel;

    @BeforeEach
    void setUp() throws Exception {
        adapter.reset();
        testLlmProvider.reset();
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                  + "VALUES (?, ?, TRUE, 'vouched') "
                  + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                  + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, GUARDIAN);
            exec(conn,
                    "DELETE FROM scope_preferences WHERE scope_kind = 'dm' AND scope_id IN ("
                  + "SELECT id FROM users WHERE contact_id LIKE ? AND contact_id != ?)",
                    CONTACT_PREFIX + "%", GUARDIAN);
            exec(conn,
                    "DELETE FROM chat_session WHERE user_id IN ("
                  + "SELECT id FROM users WHERE contact_id LIKE ? AND contact_id != ?)",
                    CONTACT_PREFIX + "%", GUARDIAN);
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE actor_user_id IN ("
                      + "SELECT id FROM users WHERE contact_id LIKE ? AND contact_id != ?)",
                        CONTACT_PREFIX + "%", GUARDIAN);
                exec(conn,
                        "DELETE FROM users WHERE contact_id LIKE ? AND contact_id != ?",
                        CONTACT_PREFIX + "%", GUARDIAN);
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
            // Clear the help corpora so the step-3c probes match nothing
            // and the exact-body assertion holds (InboundRouterChatModeIT).
            exec(conn,
                    "DELETE FROM doc_embedding WHERE doc_kind IN ('topic', 'command_intent')");
        }
    }

    @Test
    void aClearedNativeScopeSkipsTheDisplayLegAcrossTheHop() throws Exception {
        String contact = seedCsNativeScope("cleared");
        QuarkusMock.installMockForType(
                new ChatReplyModeRegistry(Set.of(
                        new ChatReplyModeRegistry.ClearedPair(chatModel, "cs"))),
                ChatReplyModeRegistry.class);
        testLlmProvider.setResponseText(GENERATED_REPLY);
        testLlmProvider.setTranslatorResponseText(TRANSLATED_REPLY);

        adapter.deliverDm(contact, "ahoj, co je noveho?");

        DispatchAwaits.await(() -> !adapter.finalizedBodies().isEmpty(),
                "the cleared native scope's chat terminal");
        String expected = GENERATED_REPLY + "\n\n"
                + bundleLoader.get(BundleKeys.CHAT_PROVENANCE_GENERAL_KNOWLEDGE, "cs");
        assertEquals(expected, adapter.finalizedBodies().getLast(),
                "the worker-side agent must run the intake-resolved NATIVE mode across the "
                        + "hop: no display leg, the generated text IS the delivered text");
    }

    @Test
    void anUnclearedNativeScopeKeepsTheDisplayLegAcrossTheHop() throws Exception {
        String contact = seedCsNativeScope("uncleared");
        // The shipped registry clears gemma × cs, but this profile's chat
        // model (infochat.llm.chat.model) is not gemma, so the pair is
        // uncleared and the stored native override resolves translate.
        testLlmProvider.setResponseText(GENERATED_REPLY);
        testLlmProvider.setTranslatorResponseText(TRANSLATED_REPLY);

        adapter.deliverDm(contact, "ahoj, co je noveho?");

        DispatchAwaits.await(() -> !adapter.finalizedBodies().isEmpty(),
                "the uncleared native scope's chat terminal");
        String expected = TRANSLATED_REPLY + "\n\n"
                + bundleLoader.get(BundleKeys.CHAT_PROVENANCE_GENERAL_KNOWLEDGE, "cs");
        assertEquals(expected, adapter.finalizedBodies().getLast(),
                "an uncleared pair resolves translate across the hop even with the "
                        + "native override stored");
    }

    // --- helpers ---

    /**
     * Seed a vouched DM user and run the REAL {@code /lang cs} and
     * {@code /reply-mode native} dispatches; both confirmations are
     * consumed so the chat turn starts from an empty outbound queue with
     * the language + native override persisted.
     */
    private String seedCsNativeScope(String suffix) throws Exception {
        String contact = CONTACT_PREFIX + suffix;
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, registration_state) "
                  + "VALUES (?, ?, 'vouched') "
                  + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                  + "  SET registration_state = 'vouched', is_banned = FALSE, "
                  + "    probation_until = NULL",
                    ADAPTER, contact);
        }
        registeredContactSet.markRegistered(ADAPTER, contact);
        adapter.deliverDm(contact, "/lang cs");
        assertFalse(adapter.sentMessages().isEmpty(),
                "/lang cs must produce a confirmation reply");
        adapter.reset();
        adapter.deliverDm(contact, "/reply-mode native");
        assertFalse(adapter.sentMessages().isEmpty(),
                "/reply-mode native must produce a confirmation reply (stored either way)");
        adapter.reset();
        return contact;
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
