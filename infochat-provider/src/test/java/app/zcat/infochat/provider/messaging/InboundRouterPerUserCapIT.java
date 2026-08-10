package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.InFlightTracker;
import app.zcat.infochat.provider.testing.TestLlmProvider;
import app.zcat.infochat.provider.testsupport.DispatchAwaits;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-636 per-user cross-scope concurrency-cap proofs: one sender's
 * concurrent interruptible turns are bounded ACROSS scopes (DM + groups),
 * requests beyond the cap are rejected at intake with fixed guidance and
 * consume nothing, and the budget frees on every terminal (completion,
 * failure, {@code /stop}). Every case drives the REAL intake path
 * ({@code InMemoryAdapter.deliverDm} / {@code deliverGroupMention} →
 * registry handler → {@code InboundRouter.onMessage}); both deliver
 * methods are synchronous through {@code onMessage} and the cap reject is
 * an intake-time plain send, so rejects are asserted the moment deliver
 * returns, with no await.
 *
 * <p>Determinism (the M1-634/M1-635/M1-638 IT rig): {@code
 * TestLlmProvider.setOnGenerate} latches turns INSIDE {@code generate()},
 * so held-turn windows are open by construction, not by sleeps; negative
 * asserts run only after {@code inFlightTaskCount() == 0} makes "no
 * further outbound can arrive" a happens-before fact.</p>
 */
@QuarkusTest
class InboundRouterPerUserCapIT {

    private static final String ADAPTER = "inmemory";
    private static final String CONTACT_PREFIX = "per-user-cap-it-";
    private static final String GROUP_PREFIX = "per-user-cap-it-g-";
    private static final String GUARDIAN = "per-user-cap-it-guardian-permanent";

    @Inject InMemoryAdapter adapter;
    @Inject TestLlmProvider testLlmProvider;
    @Inject BundleLoader bundleLoader;
    @Inject InFlightTracker inFlightTracker;
    @Inject InterruptibleDispatcher interruptibleDispatcher;
    @Inject RegisteredContactSet registeredContactSet;
    @Inject @SeedDataSource DataSource dataSource;

    // defaultValue mirrors InterruptibleDispatcher's own — the property is
    // unset in %test so both resolve the same baked default. A drift (bean
    // default changed, this one stale) fails LOUDLY: either the at-cap
    // latch times out or the reject assert trips, never a silent pass.
    @ConfigProperty(name = "infochat.chat.dispatch.per-user-cap", defaultValue = "2")
    int perUserCap;

    @BeforeEach
    void setUp() throws Exception {
        testLlmProvider.reset();
        // Held-turn cases need every worker they latch free on entry — a
        // foreign task still holding one could push this class's turns into
        // the pool queue and starve an inside-generate latch (see
        // InboundRouterQueuedFeedbackIT for the full rationale).
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "interruptible dispatch pool quiescent before test");
        adapter.reset();
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                  + "VALUES (?, ?, TRUE, 'vouched') "
                  + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                  + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, GUARDIAN);
            exec(conn,
                    "DELETE FROM group_membership WHERE group_id IN ("
                  + "SELECT id FROM groups WHERE upstream_group_id LIKE ?)",
                    GROUP_PREFIX + "%");
            exec(conn,
                    "DELETE FROM scope_preferences WHERE scope_id IN ("
                  + "SELECT id FROM users WHERE contact_id LIKE ? AND contact_id != ?) "
                  + "OR scope_id IN ("
                  + "SELECT id FROM groups WHERE upstream_group_id LIKE ?)",
                    CONTACT_PREFIX + "%", GUARDIAN, GROUP_PREFIX + "%");
            exec(conn,
                    "DELETE FROM chat_session WHERE user_id IN ("
                  + "SELECT id FROM users WHERE contact_id LIKE ? AND contact_id != ?)",
                    CONTACT_PREFIX + "%", GUARDIAN);
            exec(conn, "DELETE FROM groups WHERE upstream_group_id LIKE ?",
                    GROUP_PREFIX + "%");
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

    @AfterEach
    void drainWorkers() {
        // A stray latched worker must never bleed into the next class's
        // @BeforeEach DB cleanup: clear the generate hook (reset() nulls
        // it internally) and await pool quiescence before handing back.
        testLlmProvider.reset();
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "interruptible dispatch pool quiescent after test");
    }

    /**
     * Acceptance 1 — the cap binds per sender across scopes, never
     * globally: with alice's DM turn and group-1 turn both held inside
     * generate() (at the default cap of 2), her request in a THIRD scope
     * is rejected at intake with the fixed guidance, while bob's own DM
     * request is admitted concurrently and runs to its reply.
     */
    @Test
    void requestsBeyondCapRejectedAcrossScopesWhileOtherSenderAdmitted() throws Exception {
        UUID aliceId = seedVouchedUserReturningId("alice");
        seedVouchedUser("bob");
        String groupOne = insertApprovedGroup(GROUP_PREFIX + "one");
        String groupTwo = insertApprovedGroup(GROUP_PREFIX + "two");

        CountDownLatch atCap = new CountDownLatch(perUserCap);
        CountDownLatch bobInsideGenerate = new CountDownLatch(perUserCap + 1);
        CountDownLatch gate = new CountDownLatch(1);
        testLlmProvider.setResponseText("held reply");
        testLlmProvider.setOnGenerate(() -> {
            atCap.countDown();
            bobInsideGenerate.countDown();
            awaitLatch(gate);
        });

        adapter.deliverDm(CONTACT_PREFIX + "alice", "alice dm question");
        adapter.deliverGroupMention(groupOne, CONTACT_PREFIX + "alice", "alice group-1 question");
        assertTrue(atCap.await(15, TimeUnit.SECONDS),
                "alice's DM and group-1 turns must both be held inside generate() "
                        + "before the over-cap request is driven");

        adapter.deliverGroupMention(groupTwo, CONTACT_PREFIX + "alice", "alice group-2 question");

        // The cap reject is an intake-time plain send on the transport
        // thread — deliverGroupMention is synchronous through onMessage,
        // so the reject exists the moment it returns, addressed to the
        // scope the over-cap request arrived in.
        String capReject = bundleLoader.get(BundleKeys.ERROR_CHAT_PER_USER_CAP);
        assertTrue(sentToGroup(groupTwo).stream()
                        .anyMatch(outbound -> outbound.text().equals(capReject)),
                "the over-cap request must be rejected with the fixed guidance "
                        + "in the scope it arrived in");

        // The cap binds per sender: bob is admitted WHILE alice is at cap.
        adapter.deliverDm(CONTACT_PREFIX + "bob", "bob question");
        assertTrue(bobInsideGenerate.await(15, TimeUnit.SECONDS),
                "bob's turn must be admitted and reach generate() while alice "
                        + "sits at her cap");

        gate.countDown();
        DispatchAwaits.await(
                () -> adapter.finalizedBodies().stream()
                        .filter(body -> body.startsWith("held reply")).count() == perUserCap + 1,
                "the admitted turns' terminals (alice's two + bob's one)");
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "pool quiescent — no further outbound can arrive");
        assertEquals(perUserCap + 1, testLlmProvider.callCount(),
                "alice's rejected third request must never reach the LLM");
        assertEquals(0, inFlightTracker.countNonTerminalTurns(aliceId),
                "all of alice's turns must be terminal after the drain");
    }

    /** M1-803 item 6 / round-1 FINDING 3 — the image case: at ceiling,
     * {@code /image a cat} is rejected at intake like any other interruptible
     * (the cap is classification-driven, before the D73 config gate). */
    @Test
    void imageRequestBeyondCapRejectedLikeAnyOtherInterruptible() throws Exception {
        seedVouchedUser("alice");
        String groupOne = insertApprovedGroup(GROUP_PREFIX + "one");

        CountDownLatch atCap = new CountDownLatch(perUserCap);
        CountDownLatch gate = new CountDownLatch(1);
        testLlmProvider.setResponseText("held reply");
        testLlmProvider.setOnGenerate(() -> {
            atCap.countDown();
            awaitLatch(gate);
        });

        adapter.deliverDm(CONTACT_PREFIX + "alice", "alice dm question");
        adapter.deliverGroupMention(groupOne, CONTACT_PREFIX + "alice", "alice group-1 question");
        assertTrue(atCap.await(15, TimeUnit.SECONDS),
                "alice must be at cap before the /image request is driven");

        adapter.deliverDm(CONTACT_PREFIX + "alice", "/image a cat");

        String capReject = bundleLoader.get(BundleKeys.ERROR_CHAT_PER_USER_CAP);
        ScopeRef dm = new ScopeRef.Dm(CONTACT_PREFIX + "alice");
        assertTrue(adapter.sentMessages().stream()
                        .filter(outbound -> outbound.scope().equals(dm))
                        .anyMatch(outbound -> outbound.text().equals(capReject)),
                "an over-cap /image request must be rejected at intake "
                        + "with the fixed guidance in the scope it arrived in");

        gate.countDown();
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "held turns drained");
    }

    /**
     * Acceptance 2 — a cap rejection consumes nothing, matching the
     * check-order discipline documented at SummaryCommandHandler's
     * slot-before-bucket acquire: the reject fires at intake BEFORE the
     * turn is registered and BEFORE any handler code that could draw an
     * LlmRateCap token (callCount pins that no LLM call — and so no
     * adjacent token draw — ever happened), so the sender's registry
     * count is unchanged by the rejection and their next permitted
     * request succeeds.
     */
    @Test
    void capRejectionConsumesNoBudgetSoNextPermittedRequestSucceeds() throws Exception {
        UUID aliceId = seedVouchedUserReturningId("alice");
        String groupOne = insertApprovedGroup(GROUP_PREFIX + "one");
        String groupTwo = insertApprovedGroup(GROUP_PREFIX + "two");

        CountDownLatch atCap = new CountDownLatch(perUserCap);
        CountDownLatch gate = new CountDownLatch(1);
        testLlmProvider.setResponseText("held reply");
        testLlmProvider.setOnGenerate(() -> {
            atCap.countDown();
            awaitLatch(gate);
        });

        adapter.deliverDm(CONTACT_PREFIX + "alice", "alice dm question");
        adapter.deliverGroupMention(groupOne, CONTACT_PREFIX + "alice", "alice group-1 question");
        assertTrue(atCap.await(15, TimeUnit.SECONDS),
                "alice must be at cap before the over-cap request is driven");

        adapter.deliverGroupMention(groupTwo, CONTACT_PREFIX + "alice", "alice group-2 question");

        String capReject = bundleLoader.get(BundleKeys.ERROR_CHAT_PER_USER_CAP);
        assertTrue(sentToGroup(groupTwo).stream()
                        .anyMatch(outbound -> outbound.text().equals(capReject)),
                "precondition: the over-cap request was rejected");
        assertEquals(perUserCap, inFlightTracker.countNonTerminalTurns(aliceId),
                "the rejection must leave NO registry entry — no in-flight slot, "
                        + "no queued turn");
        assertEquals(perUserCap, testLlmProvider.callCount(),
                "the rejection must trigger NO LLM call (and so no token draw)");

        // Free the held turns; once they are terminal, the same sender's
        // next request in the previously-rejected scope must succeed —
        // the rejection burned none of their budget.
        gate.countDown();
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "held turns drained");
        testLlmProvider.reset();
        testLlmProvider.setResponseText("follow-up reply");

        adapter.deliverGroupMention(groupTwo, CONTACT_PREFIX + "alice", "alice retries group-2");
        DispatchAwaits.await(
                () -> adapter.finalizedBodies().stream()
                        .anyMatch(body -> body.startsWith("follow-up reply")),
                "the sender's next permitted request must succeed");
    }

    /**
     * Acceptance 3 (failure path) — a turn whose LLM call throws frees the
     * sender's budget: the count returns to zero and a follow-up request
     * is admitted. Completion-path release is pinned by the two cases
     * above; the /stop path by the case below.
     */
    @Test
    void failedTurnFreesTheSendersBudget() throws Exception {
        UUID aliceId = seedVouchedUserReturningId("alice");
        testLlmProvider.setThrowOnCall(true);

        adapter.deliverDm(CONTACT_PREFIX + "alice", "doomed question");

        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "failed turn drained");
        assertEquals(0, inFlightTracker.countNonTerminalTurns(aliceId),
                "a failed turn must free the sender's budget");

        testLlmProvider.reset();
        testLlmProvider.setResponseText("recovery reply");
        adapter.deliverDm(CONTACT_PREFIX + "alice", "recovery question");
        DispatchAwaits.await(
                () -> adapter.finalizedBodies().stream()
                        .anyMatch(body -> body.startsWith("recovery reply")),
                "a request after the failure must be admitted");
    }

    /**
     * Acceptance 3 (/stop path) — a turn cancelled by /stop frees the
     * sender's budget without waiting for the worker to unwind past the
     * point of cancellation: once the pool drains, the count is zero and
     * a follow-up request is admitted.
     */
    @Test
    void stoppedTurnFreesTheSendersBudget() throws Exception {
        UUID aliceId = seedVouchedUserReturningId("alice");
        CountDownLatch insideGenerate = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        testLlmProvider.setOnGenerate(() -> {
            insideGenerate.countDown();
            awaitLatch(neverReleased);
        });

        adapter.deliverDm(CONTACT_PREFIX + "alice", "long-running question");
        assertTrue(insideGenerate.await(15, TimeUnit.SECONDS),
                "the turn must be inside generate() before /stop is driven");

        adapter.deliverDm(CONTACT_PREFIX + "alice", "/stop");

        // The /stop interrupt unwinds the latched worker; after the drain
        // the cancelled turn is terminal and the budget is free.
        testLlmProvider.reset();
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "cancelled turn drained");
        assertEquals(0, inFlightTracker.countNonTerminalTurns(aliceId),
                "a /stop-cancelled turn must free the sender's budget");

        testLlmProvider.setResponseText("post-stop reply");
        adapter.deliverDm(CONTACT_PREFIX + "alice", "are you back?");
        DispatchAwaits.await(
                () -> adapter.finalizedBodies().stream()
                        .anyMatch(body -> body.startsWith("post-stop reply")),
                "a request after /stop must be admitted");
    }

    // --- helpers ---

    /** Every outbound SEND addressed to the given group scope. */
    private List<OutboundMessage> sentToGroup(String groupId) {
        ScopeRef scope = new ScopeRef.Group(groupId);
        return adapter.sentMessages().stream()
                .filter(outbound -> outbound.scope().equals(scope))
                .toList();
    }

    /** Latch await inside the generate() hook: interruption is the D35 cancellation landing — restore and rethrow. */
    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch never released within 15s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("latched generate() interrupted", e);
        }
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
        // Isolated per-contact rate bucket (the LanguageThreadingIT
        // precedent): a direct-SQL seed never reaches the in-memory
        // M1-229 RegisteredContactSet, so without this the user is a
        // STRANGER at intake step 1.5 and every case here shares the one
        // per-adapter stranger bucket other suites drain — late in the
        // full suite, part of the driven requests gets silently dropped
        // and the held-turn latch times out.
        registeredContactSet.markRegistered(ADAPTER, CONTACT_PREFIX + suffix);
    }

    private UUID seedVouchedUserReturningId(String suffix) throws Exception {
        seedVouchedUser(suffix);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, CONTACT_PREFIX + suffix);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    /** Returns the upstream group id usable with deliverGroupMention. */
    private String insertApprovedGroup(String upstreamGroupId) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO groups (adapter, upstream_group_id, approval_status, removed_at) "
                  + "VALUES (?, ?, 'approved', NULL)",
                    ADAPTER, upstreamGroupId);
        }
        return upstreamGroupId;
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
