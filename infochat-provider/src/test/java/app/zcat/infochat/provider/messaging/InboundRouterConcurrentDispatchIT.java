package app.zcat.infochat.provider.messaging;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-634 concurrency proofs for the interruptible dispatch offload:
 * the one-in-flight guard and /stop are REACHABLE because a second
 * inbound is admitted while a worker still holds the first turn's
 * LLM call. Every case drives the REAL intake path
 * ({@code InMemoryAdapter.deliverDm} → registry handler →
 * {@code InboundRouter.onMessage}) — never a direct handler call,
 * which could not observe contention (the pre-fix bug was precisely
 * that the single transport thread serialized the whole turn).
 *
 * <p>Determinism: {@code TestLlmProvider.setOnGenerate} latches a turn
 * INSIDE {@code generate()}, i.e. strictly between the
 * {@code InFlightTracker} slot acquisition and its release — contention
 * windows are held open by construction, not by sleeps.</p>
 */
@QuarkusTest
class InboundRouterConcurrentDispatchIT {

    private static final String ADAPTER = "inmemory";
    private static final String CONTACT_PREFIX = "concurrent-dispatch-it-";
    private static final String GUARDIAN = "concurrent-dispatch-it-guardian-permanent";

    @Inject InMemoryAdapter adapter;
    @Inject TestLlmProvider testLlmProvider;
    @Inject BundleLoader bundleLoader;
    @Inject InFlightTracker inFlightTracker;
    @Inject InterruptibleDispatcher interruptibleDispatcher;
    @Inject @SeedDataSource DataSource dataSource;

    // defaultValue mirrors InterruptibleDispatcher's own — the property is
    // unset in %test so both resolve the same baked default. A drift (bean
    // default changed, this one stale) fails LOUDLY: the boundReached latch
    // or the watermark assert trips, never a silent pass.
    @ConfigProperty(name = "infochat.chat.dispatch.max-concurrency", defaultValue = "4")
    int dispatchBound;

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
     * Acceptance 1 — guard reachability (reproduce-then-fix). The second
     * same-(user, scope) request is admitted WHILE the first holds its
     * InFlightTracker slot (held open by latching the first turn inside
     * generate()), receives the localized in-flight reject, and causes
     * NO second LLM invocation.
     */
    @Test
    void secondRequestSameUserScopeRejectedWhileFirstInFlight() throws Exception {
        seedVouchedUser("guard");
        CountDownLatch firstInsideGenerate = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        testLlmProvider.setResponseText("first turn reply");
        testLlmProvider.setOnGenerate(() -> {
            firstInsideGenerate.countDown();
            awaitLatch(releaseFirst);
        });

        adapter.deliverDm(CONTACT_PREFIX + "guard", "first message");
        assertTrue(firstInsideGenerate.await(15, TimeUnit.SECONDS),
                "first turn must reach generate() (slot held) before the second is driven");

        // Second request for the SAME (user, scope) while the slot is held.
        // Pre-fix this could never contend: it queued on the single dispatch
        // thread and only ran after the first turn finished.
        adapter.deliverDm(CONTACT_PREFIX + "guard", "second message");

        String inFlightReject = bundleLoader.get(BundleKeys.ERROR_CHAT_IN_FLIGHT);
        DispatchAwaits.await(() -> adapter.finalizedBodies().contains(inFlightReject),
                "second request's in-flight reject terminal");
        assertEquals(1, testLlmProvider.callCount(),
                "the rejected second request must cause NO second LLM invocation");

        // Release the first turn and prove it completes normally despite
        // the rejected intruder.
        releaseFirst.countDown();
        DispatchAwaits.await(
                () -> adapter.finalizedBodies().stream()
                        .anyMatch(body -> body.startsWith("first turn reply")),
                "first turn's own terminal after release");
    }

    /**
     * Acceptance 2 — /stop reachability (D35). /stop is processed WITHOUT
     * waiting for the in-flight request: its acknowledgement is sent
     * synchronously (inline, non-interruptible path) while the first turn
     * is still latched inside generate(), the slot is freed, the cancelled
     * turn finalizes with the stopped terminal (never a stale answer), and
     * a follow-up request is accepted. Pre-fix /stop queued BEHIND the
     * latched call — this test would deadlock on the old threading.
     */
    @Test
    void stopCancelsInFlightRequestWithoutWaitingForIt() throws Exception {
        UUID userId = seedVouchedUserReturningId("stop");
        CountDownLatch firstInsideGenerate = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        testLlmProvider.setOnGenerate(() -> {
            firstInsideGenerate.countDown();
            awaitLatch(neverReleased);
        });

        adapter.deliverDm(CONTACT_PREFIX + "stop", "long-running question");
        assertTrue(firstInsideGenerate.await(15, TimeUnit.SECONDS),
                "turn must be latched inside generate() before /stop is driven");

        adapter.deliverDm(CONTACT_PREFIX + "stop", "/stop");

        // Inline dispatch means the acknowledgement exists the moment
        // deliverDm returns — no await. This is the D35 "immediately".
        String stopAck = bundleLoader.get(BundleKeys.REPLY_STOP_CANCELLED);
        assertTrue(adapter.sentMessages().stream()
                        .anyMatch(outbound -> outbound.text().equals(stopAck)),
                "/stop acknowledgement must be sent synchronously while the "
                        + "cancelled turn is still inside its LLM call");

        // The cancelled turn's worker unwinds (interrupt propagates out of
        // the latched generate()) and finalizes its placeholder with the
        // stopped terminal — the canned reply is discarded.
        String stoppedTerminal = bundleLoader.get(BundleKeys.PROGRESS_STOPPED);
        DispatchAwaits.await(() -> adapter.finalizedBodies().contains(stoppedTerminal),
                "cancelled turn's stopped terminal");

        assertFalse(inFlightTracker.isInFlight(userId, "dm", userId),
                "cancel must free the slot without waiting for the worker");

        // Worker freed + slot free → a follow-up request is accepted.
        // reset() clears the never-released generate hook internally.
        testLlmProvider.reset();
        testLlmProvider.setResponseText("follow-up reply");
        adapter.deliverDm(CONTACT_PREFIX + "stop", "are you back?");
        DispatchAwaits.await(
                () -> adapter.finalizedBodies().stream()
                        .anyMatch(body -> body.startsWith("follow-up reply")),
                "follow-up turn accepted after /stop");
    }

    /**
     * Acceptance 3 (concurrency half) — two dispatches for DIFFERENT
     * (user, scope) run concurrently: both turns must be inside
     * generate() at the same time to pass the in-generate barrier;
     * serial dispatch would park the first turn at the barrier until
     * timeout while the second never starts.
     */
    @Test
    void differentUserScopesDispatchConcurrently() throws Exception {
        seedVouchedUser("cc-a");
        seedVouchedUser("cc-b");
        CountDownLatch bothInsideGenerate = new CountDownLatch(2);
        testLlmProvider.setResponseText("concurrent reply");
        testLlmProvider.setOnGenerate(() -> {
            bothInsideGenerate.countDown();
            awaitLatch(bothInsideGenerate);
        });

        adapter.deliverDm(CONTACT_PREFIX + "cc-a", "question from a");
        adapter.deliverDm(CONTACT_PREFIX + "cc-b", "question from b");

        assertTrue(bothInsideGenerate.await(15, TimeUnit.SECONDS),
                "both turns must be inside generate() concurrently — neither "
                        + "may block the other's dispatch");
        DispatchAwaits.await(
                () -> adapter.finalizedBodies().stream()
                        .filter(body -> body.startsWith("concurrent reply")).count() == 2,
                "both turns' terminals");
    }

    /**
     * Acceptance 3 (isolation half) — per-request CDI state is isolated
     * across concurrent dispatches. The cs user's turn must render its
     * terminal (reply + provenance notice) in Czech and the en user's in
     * English WHILE both run concurrently: effectiveLanguage is seeded
     * into each worker's own fresh InboundContext, so a bleed would
     * render both in one language. Two distinct finalized messages also
     * prove operationId separation (each turn finalizes its OWN
     * placeholder, M1-611).
     */
    @Test
    void inboundContextIsolatedAcrossConcurrentDispatches() throws Exception {
        UUID csUserId = seedVouchedUserReturningId("iso-cs");
        seedVouchedUser("iso-en");
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO scope_preferences (scope_kind, scope_id, language) "
                  + "VALUES ('dm', ?, 'cs')",
                    csUserId);
        }
        CountDownLatch bothInsideGenerate = new CountDownLatch(2);
        testLlmProvider.setResponseText("isolation reply");
        testLlmProvider.setOnGenerate(() -> {
            bothInsideGenerate.countDown();
            awaitLatch(bothInsideGenerate);
        });

        adapter.deliverDm(CONTACT_PREFIX + "iso-cs", "otázka v češtině");
        adapter.deliverDm(CONTACT_PREFIX + "iso-en", "question in english");
        assertTrue(bothInsideGenerate.await(15, TimeUnit.SECONDS),
                "both turns must run concurrently for the isolation check to bite");

        // Assert per-language provenance containment, not full-body
        // equality: the cs terminal additionally carries the D43
        // translation-pipeline annotation (TestLlmProvider has no
        // translator override, so the byte-identical output is treated
        // as a fallback — M1-437 condition b) whose exact assembly is
        // the translation pipeline's concern, not this test's. The
        // localized provenance sentence alone proves each concurrent
        // worker rendered from ITS OWN seeded context.
        String csProvenance = bundleLoader.get("reply.chat.provenance.general_knowledge", "cs");
        String enProvenance = bundleLoader.get("reply.chat.provenance.general_knowledge", "en");
        DispatchAwaits.await(() -> adapter.finalizedBodies().size() == 2,
                "two independent finalized terminals");
        List<String> finalized = adapter.finalizedBodies();
        assertTrue(finalized.stream().anyMatch(body -> body.contains(csProvenance)),
                "cs scope's terminal must localize per ITS OWN context; got: " + finalized);
        assertTrue(finalized.stream().anyMatch(
                        body -> body.contains(enProvenance) && !body.contains(csProvenance)),
                "en scope's terminal must localize per ITS OWN context; got: " + finalized);
    }

    /**
     * Acceptance 4 — bounded load. A burst larger than the pool bound
     * never exceeds the configured interruptible-LLM concurrency: with
     * the gate held, exactly {@code dispatchBound} turns sit inside
     * generate() and the rest queue; unbounded dispatch would let all
     * eight in. The watermark is checked again after full drain.
     */
    @Test
    void burstDoesNotExceedDispatchConcurrencyBound() throws Exception {
        int burst = dispatchBound * 2;
        for (int i = 0; i < burst; i++) {
            seedVouchedUser("burst-" + i);
        }
        CountDownLatch boundReached = new CountDownLatch(dispatchBound);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger insideGenerate = new AtomicInteger();
        AtomicInteger watermark = new AtomicInteger();
        testLlmProvider.setResponseText("burst reply");
        testLlmProvider.setOnGenerate(() -> {
            int concurrent = insideGenerate.incrementAndGet();
            watermark.accumulateAndGet(concurrent, Math::max);
            boundReached.countDown();
            awaitLatch(gate);
            insideGenerate.decrementAndGet();
        });

        for (int i = 0; i < burst; i++) {
            adapter.deliverDm(CONTACT_PREFIX + "burst-" + i, "burst question " + i);
        }

        assertTrue(boundReached.await(15, TimeUnit.SECONDS),
                "the pool must run dispatchBound turns concurrently");
        gate.countDown();
        DispatchAwaits.await(
                () -> adapter.finalizedBodies().stream()
                        .filter(body -> body.startsWith("burst reply")).count() == burst,
                "all " + burst + " burst turns' terminals");
        assertTrue(watermark.get() <= dispatchBound,
                "LLM-call concurrency watermark " + watermark.get()
                        + " must not exceed the configured bound " + dispatchBound);
    }

    // --- helpers ---

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
    }

    private UUID seedVouchedUserReturningId(String suffix) throws Exception {
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
