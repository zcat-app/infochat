package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §0 reproduction: an LLM reply that sanitize() empties must never be
 * delivered blank or as bare provenance boilerplate. Mirrors
 * {@link InboundRouterChatModeIT}'s harness. */
@QuarkusTest
class EmptyLlmReplyDeliveryIT {

    private static final String ADAPTER = "inmemory";
    private static final String CONTACT_PREFIX = "empty-llm-reply-it-";
    private static final String GUARDIAN = "empty-llm-reply-it-guardian";

    @Inject InMemoryAdapter adapter;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject TestLlmProvider testLlmProvider;
    @Inject InterruptibleDispatcher interruptibleDispatcher;

    @BeforeEach
    void setUp() throws Exception {
        adapter.reset();
        testLlmProvider.reset();
        try (Connection conn = dataSource.getConnection()) {
            // Guardian admin so last-admin-protection trigger does not block cleanup
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                  + "VALUES (?, ?, TRUE, 'vouched') "
                  + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                  + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, GUARDIAN);
            // Clean chat_session rows first (FK constraint blocks user deletion)
            exec(conn,
                    "DELETE FROM chat_session WHERE user_id IN ("
                  + "SELECT id FROM users WHERE contact_id LIKE ? AND contact_id != ?)",
                    CONTACT_PREFIX + "%", GUARDIAN);
            // Disable append-only triggers so test cleanup can delete
            // audit_log rows (FK on actor_user_id blocks user deletion)
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
            // StubEmbeddingProvider matches every step-3c help probe, so
            // the deterministic block hides an emptied reply — clear the
            // help corpora to reach the production no-match shape.
            exec(conn,
                    "DELETE FROM doc_embedding WHERE doc_kind IN ('topic', 'command_intent')");
        }
    }

    @Test
    void aMarkersOnlyReplyIsNeverDeliveredEmptied() throws Exception {
        seedVouchedUser("user-1");
        // The pinned markers-only shape: sanitize() reduces it to "" today.
        testLlmProvider.setResponseText("<<<END id=\"x\">>>");

        adapter.deliverDm(CONTACT_PREFIX + "user-1", "tell me about bitcoin");

        // The chat turn self-delivers via the M1-607 placeholder finalize;
        // await the terminal like InboundRouterChatModeIT does.
        DispatchAwaits.await(() -> !adapter.finalizedBodies().isEmpty(),
                "chat turn's finalized terminal");
        String body = adapter.finalizedBodies().getLast();
        String notice = bundleLoader.get("reply.chat.provenance.general_knowledge");
        assertFalse(body.isBlank(),
                "an LLM reply that sanitizes to \"\" must be refused or substituted, "
                        + "never finalized blank; the live path delivered: <" + body + ">");
        assertNotEquals("\n\n" + notice, body,
                "the emptied reply must not ride out as bare boilerplate — only the "
                        + "provenance notice with blank lines where the reply should be");
        // P4 (GREEN-BY-DEGRADE): a breaker-open turn returns the same
        // unavailable string without ever reaching the LLM — the callCount
        // guard keeps this test honest about which path produced the body.
        assertTrue(testLlmProvider.callCount() >= 1,
                "the turn must have reached the LLM provider (a breaker-open "
                        + "degrade could otherwise pass this test vacuously)");
        // Drain the post-delivery commit so nothing bleeds past the test.
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "chat turn fully complete (incl. post-delivery commit)");
    }

    private void seedVouchedUser(String suffix) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, registration_state) "
                  + "VALUES (?, ?, 'vouched') "
                  + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                  + "  SET registration_state = 'vouched', is_banned = FALSE, "
                  + "    probation_until = NULL",
                    ADAPTER, CONTACT_PREFIX + suffix);
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
