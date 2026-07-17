package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-638 queued-turn cancellation proofs: {@code /stop} reaches an
 * interruptible turn that is still sitting in the {@link
 * InterruptibleDispatcher} pool queue — the D35 "immediately" no longer
 * depends on the turn having been lucky enough to reach a worker. Every
 * case drives the REAL intake path ({@code InMemoryAdapter.deliverDm} →
 * registry handler → {@code InboundRouter.onMessage}); {@code deliverDm}
 * is synchronous through {@code onMessage} and {@code /stop} is
 * non-interruptible (dispatched inline), so /stop's reply is asserted the
 * moment it returns, with no await.
 *
 * <p>Determinism (the M1-634/M1-635 IT rig): {@code
 * TestLlmProvider.setOnGenerate} latches turns INSIDE {@code generate()},
 * so saturation windows are held open by construction, not by sleeps;
 * negative asserts run only after {@code inFlightTaskCount() == 0} makes
 * "no further outbound can arrive" a happens-before fact.</p>
 */
@QuarkusTest
class QueuedTurnCancellationIT {

    private static final String ADAPTER = "inmemory";
    private static final String CONTACT_PREFIX = "queued-cancel-it-";
    private static final String GUARDIAN = "queued-cancel-it-guardian-permanent";

    @Inject InMemoryAdapter adapter;
    @Inject TestLlmProvider testLlmProvider;
    @Inject BundleLoader bundleLoader;
    @Inject InterruptibleDispatcher interruptibleDispatcher;
    @Inject RegisteredContactSet registeredContactSet;
    @Inject @SeedDataSource DataSource dataSource;

    // defaultValue mirrors InterruptibleDispatcher's own — the property is
    // unset in %test so both resolve the same baked default. A drift (bean
    // default changed, this one stale) fails LOUDLY: a saturation latch
    // times out, never a silent pass.
    @ConfigProperty(name = "infochat.chat.dispatch.max-concurrency", defaultValue = "4")
    int dispatchBound;

    @BeforeEach
    void setUp() throws Exception {
        testLlmProvider.reset();
        // Saturating cases need all dispatchBound workers free on entry — a
        // foreign task still holding one starves the saturation latch and
        // the case times out on a precondition, not on its subject (see
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
     * Acceptance 1 — /stop cancels a QUEUED turn: the sender gets the
     * cancelled reply synchronously (pre-M1-638 this was the no-op
     * guidance while the turn later ran anyway), the turn never reaches
     * the LLM, and its M1-635 acknowledgement placeholder is finalized
     * with the D35 stopped terminal instead of stranding "working on it".
     */
    @Test
    void stopCancelsQueuedTurnBeforeItReachesLlm() throws Exception {
        for (int i = 0; i < dispatchBound; i++) {
            seedVouchedUser("sat-" + i);
        }
        seedVouchedUser("victim");
        CountDownLatch boundReached = new CountDownLatch(dispatchBound);
        CountDownLatch gate = new CountDownLatch(1);
        testLlmProvider.setResponseText("saturation reply");
        testLlmProvider.setOnGenerate(() -> {
            boundReached.countDown();
            awaitLatch(gate);
        });
        for (int i = 0; i < dispatchBound; i++) {
            adapter.deliverDm(CONTACT_PREFIX + "sat-" + i, "saturating question " + i);
        }
        assertTrue(boundReached.await(15, TimeUnit.SECONDS),
                "all pool workers must be latched inside generate() before the queued "
                        + "turn is driven");

        adapter.deliverDm(CONTACT_PREFIX + "victim", "queued question");
        String startedAck = bundleLoader.get(BundleKeys.PROGRESS_STARTED);
        assertTrue(sentTo("victim").stream().anyMatch(sent -> sent.text().equals(startedAck)),
                "the queued turn must be acknowledged at submit time");

        adapter.deliverDm(CONTACT_PREFIX + "victim", "/stop");

        // /stop is non-interruptible (inline dispatch), so its reply exists
        // the moment deliverDm returns — and it must be the CANCELLED arm,
        // not the pre-M1-638 no-op guidance for a turn no worker holds yet.
        String stopCancelled = bundleLoader.get(BundleKeys.REPLY_STOP_CANCELLED);
        assertTrue(sentTo("victim").stream().anyMatch(sent -> sent.text().equals(stopCancelled)),
                "/stop against a QUEUED turn must reply with the cancelled arm "
                        + "synchronously");

        gate.countDown();
        String stoppedTerminal = bundleLoader.get(BundleKeys.PROGRESS_STOPPED);
        DispatchAwaits.await(() -> adapter.finalizedBodies().contains(stoppedTerminal),
                "the cancelled queued turn's ack placeholder must finalize with the "
                        + "D35 stopped terminal");
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "pool quiescent — no further outbound can arrive");
        assertEquals(dispatchBound, testLlmProvider.callCount(),
                "the cancelled queued turn must never reach the LLM");
    }

    /**
     * Acceptance 2 — cancellation stays keyed per-(user, scope) at every
     * lifecycle state: user A's /stop never cancels user B's QUEUED turn.
     * B's turn still runs and finalizes with its own reply; A receives the
     * no-op guidance.
     */
    @Test
    void stopByUserANeverCancelsUserBsQueuedTurn() throws Exception {
        for (int i = 0; i < dispatchBound; i++) {
            seedVouchedUser("fill-" + i);
        }
        seedVouchedUser("user-a");
        seedVouchedUser("user-b");
        CountDownLatch boundReached = new CountDownLatch(dispatchBound);
        CountDownLatch gate = new CountDownLatch(1);
        testLlmProvider.setResponseText("cross-user reply");
        testLlmProvider.setOnGenerate(() -> {
            boundReached.countDown();
            awaitLatch(gate);
        });
        for (int i = 0; i < dispatchBound; i++) {
            adapter.deliverDm(CONTACT_PREFIX + "fill-" + i, "filler question " + i);
        }
        assertTrue(boundReached.await(15, TimeUnit.SECONDS),
                "all pool workers must be latched before B's turn is driven");

        adapter.deliverDm(CONTACT_PREFIX + "user-b", "b's queued question");

        adapter.deliverDm(CONTACT_PREFIX + "user-a", "/stop");
        String stopNoop = bundleLoader.get(BundleKeys.REPLY_STOP_NOOP);
        assertTrue(sentTo("user-a").stream().anyMatch(sent -> sent.text().equals(stopNoop)),
                "A has nothing in flight or queued — /stop must reply the no-op "
                        + "guidance and touch nothing of B's");

        gate.countDown();
        DispatchAwaits.await(
                () -> adapter.finalizedBodies().stream()
                        .filter(body -> body.startsWith("cross-user reply"))
                        .count() == dispatchBound + 1,
                "B's queued turn must run to its own reply despite A's /stop");
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "pool quiescent — no further outbound can arrive");
        assertEquals(dispatchBound + 1, testLlmProvider.callCount(),
                "B's turn must have reached the LLM exactly once");
    }

    /**
     * Acceptance 4 — a cancelled queued turn produces EXACTLY ONE terminal
     * message: the acknowledgement placeholder is finalized in place and no
     * second bubble is sent. Pins the single-publisher invariant that
     * {@code StageProgressNotifier.terminate}'s no-state branch (a fresh
     * send) makes load-bearing — a duplicate terminal would surface here as
     * a third send to the victim or a second stopped finalize.
     */
    @Test
    void cancelledQueuedTurnProducesExactlyOneTerminalMessage() throws Exception {
        for (int i = 0; i < dispatchBound; i++) {
            seedVouchedUser("one-" + i);
        }
        seedVouchedUser("solo");
        CountDownLatch boundReached = new CountDownLatch(dispatchBound);
        CountDownLatch gate = new CountDownLatch(1);
        testLlmProvider.setResponseText("one-terminal filler");
        testLlmProvider.setOnGenerate(() -> {
            boundReached.countDown();
            awaitLatch(gate);
        });
        for (int i = 0; i < dispatchBound; i++) {
            adapter.deliverDm(CONTACT_PREFIX + "one-" + i, "filler question " + i);
        }
        assertTrue(boundReached.await(15, TimeUnit.SECONDS),
                "all pool workers must be latched before the queued turn is driven");

        adapter.deliverDm(CONTACT_PREFIX + "solo", "queued question");
        adapter.deliverDm(CONTACT_PREFIX + "solo", "/stop");

        gate.countDown();
        String stoppedTerminal = bundleLoader.get(BundleKeys.PROGRESS_STOPPED);
        DispatchAwaits.await(() -> adapter.finalizedBodies().contains(stoppedTerminal),
                "the cancelled turn's stopped terminal");
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "pool quiescent — no further outbound can arrive");

        assertEquals(2, sentTo("solo").size(),
                "exactly two sends to the sender across the scenario: the ack "
                        + "placeholder and /stop's own reply — the stopped terminal is "
                        + "an in-place finalize, never a second bubble");
        assertEquals(1, adapter.finalizedBodies().stream()
                        .filter(body -> body.equals(stoppedTerminal)).count(),
                "exactly one stopped terminal for the cancelled turn");
    }

    /**
     * Acceptance 3 (no-op half) — /stop with genuinely nothing in progress
     * still returns the no-op guidance through the queue-capable intake.
     * (The worker-held cancel half is pinned by
     * {@code InboundRouterConcurrentDispatchIT}, which must pass
     * unmodified.)
     */
    @Test
    void stopWithNothingInProgressStillRepliesNoop() throws Exception {
        seedVouchedUser("idle");

        adapter.deliverDm(CONTACT_PREFIX + "idle", "/stop");

        String stopNoop = bundleLoader.get(BundleKeys.REPLY_STOP_NOOP);
        assertTrue(sentTo("idle").stream().anyMatch(sent -> sent.text().equals(stopNoop)),
                "/stop with nothing in flight or queued must reply the no-op guidance");
    }

    // --- helpers ---

    /** Every outbound SEND addressed to the given test contact's DM scope. */
    private List<OutboundMessage> sentTo(String contactSuffix) {
        ScopeRef scope = new ScopeRef.Dm(CONTACT_PREFIX + contactSuffix);
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
        // full suite, part of the saturating burst gets silently dropped
        // and the saturation latch times out.
        registeredContactSet.markRegistered(ADAPTER, CONTACT_PREFIX + suffix);
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
