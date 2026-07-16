package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
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
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end pin for the chat-turn write-ordering contract (M1-313): on a
 * PERMANENT outbound-delivery failure the chat turn is NOT persisted (spec
 * {@code messaging.md} §Failure handling — "the context window remains as
 * if the message was never generated, and chat_memory is not written"), and
 * on a successful delivery both turns persist exactly as before. Drives the
 * real ChatAgent → InboundRouter → StageProgressNotifier → OutboundDelivery
 * chokepoint path against the seeded DB; permanent failure is injected by
 * killing the adapter's message handles mid-generation so the notifier's
 * terminal finalize aborts PERMANENT (M1-607).
 */
@QuarkusTest
class InboundRouterChatDeliveryOrderingIT {

    private static final String ADAPTER = "inmemory";
    private static final String CONTACT_PREFIX = "chat-order-it-";

    @Inject InMemoryAdapter adapter;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject TestLlmProvider testLlmProvider;
    @Inject InterruptibleDispatcher interruptibleDispatcher;

    @BeforeEach
    void setUp() throws Exception {
        adapter.reset();
        testLlmProvider.reset();
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "DELETE FROM chat_message WHERE user_id IN ("
                  + "SELECT id FROM users WHERE contact_id LIKE ?)",
                    CONTACT_PREFIX + "%");
            exec(conn,
                    "DELETE FROM chat_session WHERE user_id IN ("
                  + "SELECT id FROM users WHERE contact_id LIKE ?)",
                    CONTACT_PREFIX + "%");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE actor_user_id IN ("
                      + "SELECT id FROM users WHERE contact_id LIKE ?)",
                        CONTACT_PREFIX + "%");
                exec(conn, "DELETE FROM users WHERE contact_id LIKE ?", CONTACT_PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
        }
    }

    @Test
    void permanentDeliveryFailureWritesNoChatMessageRows() throws Exception {
        UUID userId = seedVouchedUser("perm-fail");
        testLlmProvider.setResponseText("Reply that never reaches the user.");

        // Inject a permanent delivery failure into the notifier-driven
        // self-delivery path (M1-607): the chat reply now REPLACES the D31
        // placeholder via finalizeMessage on the adapter the AdapterRegistry
        // resolves, so the old reply-target rebind can no longer intercept
        // it. Instead, kill the adapter's message handles mid-generation
        // (adapter.reset() clears the handle map) — the terminal finalize
        // then raises the adapter's PERMANENT unknown-handle failure through
        // the real chokepoint, simulating "the channel died while the LLM
        // was generating".
        testLlmProvider.setOnGenerate(adapter::reset);
        try {
            adapter.deliverDm(CONTACT_PREFIX + "perm-fail", "tell me something");
            // Await the M1-634 worker INSIDE the try: resetting the
            // provider before the worker reaches generate() would clear
            // the handle-killing hook and turn this into a successful
            // delivery — the drained pool is also what makes the
            // zero-rows negative assert below race-free.
            DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                    "failed turn's worker fully complete");
        } finally {
            // The provider bean is shared JVM-wide across @QuarkusTest
            // classes — never leak the handle-killing hook past this test.
            testLlmProvider.reset();
        }

        assertEquals(0, countChatMessages(userId),
                "a permanently-failed chat reply must persist NO chat_message rows "
                        + "(the turn is rolled back as if never generated)");
    }

    @Test
    void successfulDeliveryPersistsBothTurnsAndAdvancesNextSeq() throws Exception {
        UUID userId = seedVouchedUser("success");
        testLlmProvider.setResponseText("Here is your answer.");

        adapter.deliverDm(CONTACT_PREFIX + "success", "ask a question");

        // Persistence is the worker's post-delivery commit (M1-634) —
        // await pool quiescence before reading the rows.
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "chat turn fully complete (incl. post-delivery commit)");

        assertEquals(2, countChatMessages(userId),
                "a delivered chat reply must persist both the user and assistant turns");
        assertEquals(2, readNextSeq(userId),
                "chat_session.next_seq must advance past both persisted turns");
    }

    // --- helpers ---

    private int countChatMessages(UUID userId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM chat_message "
                   + "WHERE user_id = ? AND scope_kind = 'dm' AND scope_id = ?")) {
            ps.setObject(1, userId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int readNextSeq(UUID userId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT next_seq FROM chat_session "
                   + "WHERE user_id = ? AND scope_kind = 'dm' AND scope_id = ?")) {
            ps.setObject(1, userId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt("next_seq");
            }
        }
    }

    private UUID seedVouchedUser(String suffix) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, registration_state) "
                   + "VALUES (?, ?, 'vouched') "
                   + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                   + "  SET registration_state = 'vouched', is_banned = FALSE, "
                   + "    probation_until = NULL "
                   + "RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, CONTACT_PREFIX + suffix);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
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
