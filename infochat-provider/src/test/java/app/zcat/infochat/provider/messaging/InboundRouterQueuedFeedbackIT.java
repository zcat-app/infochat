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
 * M1-635 queued-feedback proofs: an interruptible request submitted while
 * the {@link InterruptibleDispatcher} pool is saturated is acknowledged at
 * SUBMIT time on the transport thread — the sender is never left in silence
 * while the task sits in the pool queue — and that acknowledgement is the
 * same placeholder lifecycle the worker later publishes into and finalizes
 * (one placeholder per turn, M1-607/M1-611). Every case drives the REAL
 * intake path ({@code InMemoryAdapter.deliverDm} → registry handler →
 * {@code InboundRouter.onMessage}); {@code deliverDm} is synchronous
 * through {@code onMessage}, so submit-time effects are asserted the
 * moment it returns, with no await.
 *
 * <p>Determinism (the M1-634 IT's rig): {@code TestLlmProvider.setOnGenerate}
 * latches turns INSIDE {@code generate()}, so saturation windows are held
 * open by construction, not by sleeps; negative asserts run only after
 * {@code inFlightTaskCount() == 0} makes "no further outbound can arrive"
 * a happens-before fact.</p>
 */
@QuarkusTest
class InboundRouterQueuedFeedbackIT {

    private static final String ADAPTER = "inmemory";
    private static final String CONTACT_PREFIX = "queued-feedback-it-";
    private static final String GUARDIAN = "queued-feedback-it-guardian-permanent";

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
        // Every case here saturates the pool with EXACTLY dispatchBound turns
        // of its own, so it needs all dispatchBound workers free on entry —
        // a foreign task still holding one starves the saturation latch and
        // the case times out on a precondition, not on its subject. The pool
        // is an @ApplicationScoped singleton shared by every IT in this JVM,
        // and a class that awaits only its terminal can return while its
        // worker is still inside runPostDeliveryCommit, so entry quiescence
        // cannot be assumed from the previous class's teardown. Await it here
        // (and drain again in @AfterEach) so a leak surfaces as this loud,
        // located failure instead of a confusing latch timeout downstream.
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
     * Acceptance 1 — a request queued behind a saturated pool is
     * acknowledged BEFORE any worker begins its stage. Happens-before
     * argument: the pool has exactly {@code dispatchBound} threads and
     * every one is verifiably parked inside {@code generate()}
     * (boundReached counted down, gate still closed), so no thread exists
     * that could have begun the queued stage — and {@code deliverDm} is
     * synchronous through {@code onMessage}, so the acknowledgement must
     * already be recorded the moment it returns.
     */
    @Test
    void queuedRequestReceivesAcknowledgementBeforeWorkerBeginsStage() throws Exception {
        for (int i = 0; i < dispatchBound; i++) {
            seedVouchedUser("sat-" + i);
        }
        seedVouchedUser("queued");
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
                        + "request is driven; latched=" + (dispatchBound - boundReached.getCount())
                        + " of " + dispatchBound + ", llmCalls=" + testLlmProvider.callCount()
                        + ", inFlight=" + interruptibleDispatcher.inFlightTaskCount());

        adapter.deliverDm(CONTACT_PREFIX + "queued", "queued question");

        String startedAck = bundleLoader.get(BundleKeys.PROGRESS_STARTED);
        assertTrue(sentTo("queued").stream().anyMatch(sent -> sent.text().equals(startedAck)),
                "queued sender must receive the acknowledgement placeholder at submit time");
        assertEquals(dispatchBound, testLlmProvider.callCount(),
                "no worker may have begun the queued stage when the acknowledgement "
                        + "is asserted");

        gate.countDown();
        DispatchAwaits.await(
                () -> adapter.finalizedBodies().stream()
                        .filter(body -> body.startsWith("saturation reply"))
                        .count() == dispatchBound + 1,
                "all " + (dispatchBound + 1) + " turns' terminals after release");
    }

    /**
     * Acceptance 2 — the submit-time acknowledgement and the worker's own
     * progress publishes are ONE placeholder lifecycle: exactly one
     * outbound send for the queued turn (the ack — the worker's STARTED
     * takes the update path against the seeded operationId's handle, never
     * a second send) and exactly one terminal finalize carrying the reply.
     */
    @Test
    void queuedAcknowledgementAndWorkerPlaceholderAreOneLifecycle() throws Exception {
        for (int i = 0; i < dispatchBound; i++) {
            seedVouchedUser("fill-" + i);
        }
        seedVouchedUser("one");
        CountDownLatch fillersInside = new CountDownLatch(dispatchBound);
        CountDownLatch fillerGate = new CountDownLatch(1);
        testLlmProvider.setResponseText("filler reply");
        testLlmProvider.setOnGenerate(() -> {
            fillersInside.countDown();
            awaitLatch(fillerGate);
        });
        for (int i = 0; i < dispatchBound; i++) {
            adapter.deliverDm(CONTACT_PREFIX + "fill-" + i, "filler question " + i);
        }
        assertTrue(fillersInside.await(15, TimeUnit.SECONDS),
                "all pool workers must be latched before the queued request is driven");

        // Swap the generate hook BEFORE the queued request is driven: the
        // fillers already read the old hook at their generate() entry, and
        // the queued turn — the only later entrant — parks on its own gate,
        // so its reply text can be set uniquely after every filler has
        // finalized (each turn reads responseText at its own release).
        CountDownLatch queuedInside = new CountDownLatch(1);
        CountDownLatch queuedGate = new CountDownLatch(1);
        testLlmProvider.setOnGenerate(() -> {
            queuedInside.countDown();
            awaitLatch(queuedGate);
        });
        adapter.deliverDm(CONTACT_PREFIX + "one", "queued question");

        fillerGate.countDown();
        DispatchAwaits.await(
                () -> adapter.finalizedBodies().stream()
                        .filter(body -> body.startsWith("filler reply"))
                        .count() == dispatchBound,
                "filler terminals");
        assertTrue(queuedInside.await(15, TimeUnit.SECONDS),
                "queued turn must be picked up by a freed worker");
        testLlmProvider.setResponseText("queued turn reply");
        queuedGate.countDown();

        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "pool quiescent — no further outbound can arrive");
        assertEquals(1, sentTo("one").size(),
                "exactly one placeholder send for the queued turn: the submit-time ack; "
                        + "the worker's own STARTED publish must take the update path, "
                        + "never a second send");
        assertEquals(1, adapter.finalizedBodies().stream()
                        .filter(body -> body.startsWith("queued turn reply")).count(),
                "exactly one terminal finalize carrying the queued turn's reply");
    }

    /**
     * Acceptance 3 — an in-flight-guard REJECT of a queued second
     * same-(user, scope) request still terminates in exactly one outbound
     * message: the guidance finalizes the acknowledgement placeholder in
     * place (the worker publishes under the seeded operationId), never a
     * separate reject message alongside an orphaned "working on it".
     */
    @Test
    void inFlightRejectOfQueuedRequestTerminatesInExactlyOneMessage() throws Exception {
        seedVouchedUser("dup");
        for (int i = 0; i < dispatchBound - 1; i++) {
            seedVouchedUser("blk-" + i);
        }
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch firstGate = new CountDownLatch(1);
        testLlmProvider.setOnGenerate(() -> {
            firstInside.countDown();
            awaitLatch(firstGate);
        });
        adapter.deliverDm(CONTACT_PREFIX + "dup", "first question");
        assertTrue(firstInside.await(15, TimeUnit.SECONDS),
                "first turn must hold its in-flight slot inside generate()");

        // Occupy the remaining workers on OTHER users so the pool is
        // saturated while the duplicate sender's slot stays held.
        CountDownLatch blockersInside = new CountDownLatch(dispatchBound - 1);
        CountDownLatch blockerGate = new CountDownLatch(1);
        testLlmProvider.setOnGenerate(() -> {
            blockersInside.countDown();
            awaitLatch(blockerGate);
        });
        testLlmProvider.setResponseText("blocker reply");
        for (int i = 0; i < dispatchBound - 1; i++) {
            adapter.deliverDm(CONTACT_PREFIX + "blk-" + i, "blocker question " + i);
        }
        assertTrue(blockersInside.await(15, TimeUnit.SECONDS),
                "remaining workers must be latched before the duplicate is driven");

        adapter.deliverDm(CONTACT_PREFIX + "dup", "second question");
        assertEquals(2, sentTo("dup").size(),
                "first turn's own placeholder + the queued second request's ack");

        // Free the blockers ONLY — the first turn keeps its slot latched,
        // so the guard MUST reject the dequeued second request.
        blockerGate.countDown();
        String inFlightReject = bundleLoader.get(BundleKeys.ERROR_CHAT_IN_FLIGHT);
        DispatchAwaits.await(() -> adapter.finalizedBodies().contains(inFlightReject),
                "in-flight reject must finalize the queued request's ack placeholder");
        assertEquals(dispatchBound, testLlmProvider.callCount(),
                "the rejected second request must never reach the LLM");

        testLlmProvider.setResponseText("dup first reply");
        firstGate.countDown();
        DispatchAwaits.await(
                () -> adapter.finalizedBodies().stream()
                        .anyMatch(body -> body.startsWith("dup first reply")),
                "first turn's own terminal after release");
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "pool quiescent — no further outbound can arrive");
        assertEquals(2, sentTo("dup").size(),
                "exactly two sends to the duplicate sender across the whole scenario: "
                        + "one placeholder per request, each finalized in place — no "
                        + "separate reject message, no orphaned placeholder");
    }

    /**
     * Hardens the queued-Reply reconciliation on a deterministic no-LLM
     * path: {@code /retry} (interruptible) with no summary anchor returns
     * a plain Reply body from the worker, which must REPLACE the ack
     * placeholder as a notifier terminal — without the reconciliation,
     * every worker-side plain-Reply path would strand a "working on it"
     * bubble forever and send the guidance as a second message.
     */
    @Test
    void queuedPlainReplyPathTerminatesTheAcknowledgementPlaceholder() throws Exception {
        for (int i = 0; i < dispatchBound; i++) {
            seedVouchedUser("plain-" + i);
        }
        seedVouchedUser("noanchor");
        CountDownLatch boundReached = new CountDownLatch(dispatchBound);
        CountDownLatch gate = new CountDownLatch(1);
        testLlmProvider.setResponseText("plain filler reply");
        testLlmProvider.setOnGenerate(() -> {
            boundReached.countDown();
            awaitLatch(gate);
        });
        for (int i = 0; i < dispatchBound; i++) {
            adapter.deliverDm(CONTACT_PREFIX + "plain-" + i, "plain filler " + i);
        }
        assertTrue(boundReached.await(15, TimeUnit.SECONDS),
                "all pool workers must be latched before the /retry is driven");

        adapter.deliverDm(CONTACT_PREFIX + "noanchor", "/retry");
        String startedAck = bundleLoader.get(BundleKeys.PROGRESS_STARTED);
        assertTrue(sentTo("noanchor").stream().anyMatch(sent -> sent.text().equals(startedAck)),
                "queued /retry must be acknowledged at submit time");

        gate.countDown();
        String noAnchorGuidance = bundleLoader.get(BundleKeys.ERROR_RETRY_NO_ANCHOR);
        DispatchAwaits.await(() -> adapter.finalizedBodies().contains(noAnchorGuidance),
                "no-anchor guidance must finalize the ack placeholder");
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "pool quiescent — no further outbound can arrive");
        assertEquals(1, sentTo("noanchor").size(),
                "exactly one outbound send to the /retry sender: the ack, finalized in place");
        assertEquals(dispatchBound, testLlmProvider.callCount(),
                "the no-anchor /retry never reaches the LLM");
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
