package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.testing.TestLlmProvider;
import app.zcat.infochat.provider.testsupport.DispatchAwaits;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end pin for D43 language threading: a user who ran
 * {@code /lang cs} receives the CZECH bundle string from one
 * representative handler in each handler group —
 *
 * <ul>
 *   <li><b>command replies</b>: {@code /help} renders the cs DM-user
 *       header;</li>
 *   <li><b>chat-path notices</b>: an unreachable LLM yields the cs
 *       {@code error.chat.unavailable} reply;</li>
 *   <li><b>error replies</b>: an unknown slash command yields the cs
 *       {@code error.unknown_command} reply.</li>
 * </ul>
 *
 * <p>The language is set through the real {@code /lang cs} dispatch
 * (not a raw {@code scope_preferences} INSERT) so the whole chain —
 * handler write, router-side per-dispatch resolution, bundle lookup —
 * is exercised end to end on the production wiring.</p>
 */
@QuarkusTest
class LanguageThreadingIT {

    private static final String ADAPTER = "inmemory";
    private static final String CONTACT_PREFIX = "lang-threading-it-";
    private static final String GUARDIAN = "lang-threading-it-guardian-permanent";

    @Inject InMemoryAdapter adapter;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject TestLlmProvider testLlmProvider;
    @Inject RegisteredContactSet registeredContactSet;

    @BeforeEach
    void setUp() throws Exception {
        adapter.reset();
        testLlmProvider.reset();
        try (Connection conn = dataSource.getConnection()) {
            // Guardian admin so last-admin-protection does not block cleanup.
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
        }
    }

    @Test
    void csScopeCommandReplyRendersCzechHelpHeader() throws Exception {
        String contact = seedCsUser("help");

        adapter.deliverDm(contact, "/help");

        String reply = lastReply().text();
        assertTrue(reply.startsWith(bundleLoader.get(BundleKeys.HELP_HEADER_DM_USER, "cs")),
                "/lang cs user's /help must open with the Czech DM-user header; got: " + reply);
    }

    @Test
    void csScopeChatPathNoticeRendersCzechUnavailableReply() throws Exception {
        String contact = seedCsUser("chat");
        testLlmProvider.setThrowOnCall(true);

        adapter.deliverDm(contact, "ahoj, co je nového?");

        // The chat turn self-delivers via the ProgressNotifier (M1-607): the
        // friendly-error reply REPLACES the D31 placeholder, so it is read
        // from the finalize event, not the last plain send — awaited across
        // the M1-634 worker hop.
        DispatchAwaits.await(() -> !adapter.finalizedBodies().isEmpty(),
                "cs chat turn's finalized terminal");
        var finalized = adapter.finalizedBodies();
        assertEquals(bundleLoader.get(BundleKeys.ERROR_CHAT_UNAVAILABLE, "cs"), finalized.getLast(),
                "/lang cs user's chat-path notice must be the Czech unavailable reply");
    }

    @Test
    void csScopeErrorReplyRendersCzechUnknownCommand() throws Exception {
        String contact = seedCsUser("unknown");

        adapter.deliverDm(contact, "/xyzzy");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_UNKNOWN_COMMAND, "cs"), lastReply().text(),
                "/lang cs user's unknown-command reply must be the Czech bundle string");
    }

    // --- helpers ---

    /**
     * Seed a vouched DM user and run the REAL {@code /lang cs} dispatch
     * for it; the confirmation reply is consumed so each test starts
     * from an empty outbound queue with the cs preference persisted.
     */
    private String seedCsUser(String suffix) throws Exception {
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
        // Isolated per-contact rate bucket — keeps this IT independent
        // of the shared stranger limiter other suites may drain.
        registeredContactSet.markRegistered(ADAPTER, contact);
        adapter.deliverDm(contact, "/lang cs");
        assertFalse(adapter.sentMessages().isEmpty(),
                "/lang cs must produce a confirmation reply");
        adapter.reset();
        return contact;
    }

    private OutboundMessage lastReply() {
        var sent = adapter.sentMessages();
        assertFalse(sent.isEmpty(), "Expected at least one reply");
        return sent.getLast();
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
