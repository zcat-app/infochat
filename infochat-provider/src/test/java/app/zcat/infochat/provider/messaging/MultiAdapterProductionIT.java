package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.impl.signal.FakeSignalCli;
import app.zcat.infochat.messaging.impl.signal.SignalAccountStoreFixture;
import app.zcat.infochat.messaging.impl.signal.SignalAdapter;
import app.zcat.infochat.messaging.impl.simplex.FakeSimpleXProcess;
import app.zcat.infochat.messaging.impl.simplex.SimpleXAdapter;
import app.zcat.infochat.messaging.impl.simplex.SimpleXConfig;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.command.GrantAdminCommandHandler;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-109 multi-adapter production-shape IT. Proves the D46 commitment
 * (Provider runs any non-empty subset of {SimpleX, Signal, InMemory}
 * simultaneously) with both production adapters wired against in-process
 * fakes that stand in for the {@code simplex-chat} and {@code signal-cli}
 * subprocesses. Extends the coverage M1-105's {@code
 * MultiAdapterIsolationIT} provided under two virtual adapter names —
 * the DB-level isolation invariants and the V5 last-admin trigger are
 * re-exercised here under the production adapter names ({@code "simplex"}
 * / {@code "signal"}) and the cross-adapter blast-radius scenarios
 * (acceptance items 2, 3, 7, 8) land for the first time.
 *
 * <p><b>Profile wiring.</b> {@link FakeSimpleXProcess} and {@link
 * FakeSignalCli} are instantiated as static fields at class load — their
 * constructors bind ephemeral loopback ports. {@link Profile#getConfigOverrides()}
 * returns those bound ports as
 * {@code infochat.adapters.simplex.ws-port} and
 * {@code infochat.adapters.signal.endpoint} so that the CDI-wired {@link
 * SimpleXAdapter} and {@link SignalAdapter} beans dial the fakes when
 * {@link MessagingStartup} reflectively calls each adapter's
 * {@code start()} at {@code @PostConstruct}. The subprocess binary is
 * {@code /bin/sleep} — it doesn't accept the adapter-defined arg list
 * and exits immediately, but {@code adapter.start()} returns success
 * because the WS / TCP probe to the fake's bound port succeeds; the
 * subprocess supervisors' WARN spam is suppressed via
 * {@code src/test/resources/application.properties}.
 *
 * <p><b>Crash-test independence.</b> The {@code simpleXCrashDoesNotAffectSignal}
 * and {@code signalCrashDoesNotAffectSimpleX} tests construct FRESH
 * fakes and FRESH adapter instances via the public production
 * constructors rather than reusing the class-wide shared statics. Two
 * reasons: (1) closing a shared fake mid-test would leave the
 * corresponding CDI-wired adapter in a degraded state that subsequent
 * tests cannot recover from (the adapter binds to its endpoint at
 * {@code start()}, with no rebind path); (2) each crash test must run
 * in a clean both-adapters-alive precondition independent of the
 * other crash test's ordering. The shared statics still bear load —
 * they're the substrate that exercises the production-CDI happy path
 * (acceptance item 1 and the DB-level isolation tests rely on
 * {@code registry.activatedAdapters()} reflecting the two-bean
 * activation).
 *
 * <p><b>DB-level cleanup discipline.</b> Each test seeds rows under
 * {@link #PREFIX} and {@link #cleanup()} deletes by prefix; {@code
 * audit_log} append-only triggers are temporarily disabled during the
 * delete because {@code audit_log} is mutate-forbidden at runtime per
 * V5. {@link #lastAdminGlobalAcrossAdapters()} captures the snapshot of
 * every pre-existing {@code is_admin = TRUE} contact (outside the
 * prefix, so it never picks up our own seeds) and restores it in a
 * {@code finally}, so other ITs sharing the JVM-wide Postgres container
 * see the same admin landscape they did before. Mirrors the M1-105
 * pattern verbatim.
 */
@QuarkusTest
@TestProfile(MultiAdapterProductionIT.Profile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiAdapterProductionIT {

    private static final String PREFIX = "m1-109-prod-";

    // The production adapter names — gate 2 in AdapterRegistry resolves
    // these to the SimpleXAdapter and SignalAdapter beans contributed by
    // ProductionAdapterBeans (M1-120). The DB-level isolation tests
    // exercise the (adapter, contact_id) boundary under these names; the
    // upstream V5 UNIQUE constraint keys off the adapter column string,
    // so the boundary is verified for the actual production strings, not
    // just the virtual names M1-105's IT used.
    private static final String SIMPLEX = "simplex";
    private static final String SIGNAL = "signal";

    // Sentinel admin row that anchors the global is_admin=TRUE count
    // throughout this IT's lifetime. Without it, this class's own
    // @BeforeEach prefix-cleanup trips the V5 DELETE trigger
    // (last_admin_protection: cannot delete the last bot admin) when a
    // prior test left behind the only admin row in the JVM-wide
    // Postgres container — which is the situation in a fresh CI start
    // where no other test class has seeded an admin yet. The sentinel
    // lives OUTSIDE the PREFIX so the prefix-based DELETE in cleanup
    // never targets it; it persists for the duration of the test JVM
    // (the DevServices container is dropped at mvn-exit, taking the
    // row with it, so no cross-invocation leak). Other ITs that capture
    // pre-existing admins via snapshot queries (e.g. M1-105's
    // lastAdminProtectionGlobal) pick up the sentinel naturally and
    // restore it in their own finally blocks, so the sentinel is
    // transparent to them.
    private static final String SENTINEL_CONTACT = "m1-109-sentinel-permanent-admin";
    private static final String SENTINEL_ADAPTER = "sentinel";
    private static final AtomicBoolean sentinelSeeded = new AtomicBoolean(false);

    // Shared fakes bound at static-init so the Profile.getConfigOverrides()
    // can return the bound ports. Quarkus reflectively instantiates the
    // Profile class AFTER class load, so the static block has already
    // bound the ports by the time the profile config is queried. The
    // SimpleX fake needs its accept loop running before any client
    // connects; SignalCli starts its accept thread in its own constructor.
    static final FakeSimpleXProcess sharedFakeSimplex;
    static final FakeSignalCli sharedFakeSignal;
    // signal-cli account-store fixture: SignalAdapter.start() derives the
    // bot's ACI (the D10 anchor) from the store under .data-dir, so every
    // adapter that really starts in this IT — the CDI bean and the
    // per-test factory adapters — needs a readable store. The fixture ACI
    // is the value the removed .bot-aci property used to supply, so the
    // anchor the tests observe is unchanged. Created in the same
    // static-init block as the fakes because Profile.getConfigOverrides()
    // runs after class load.
    static final Path sharedSignalStoreDir;
    // SimpleXAdapter.start() no longer derives a bot queue address (the
    // /show_address derivation was removed in M1-518; group @-mention
    // recognition reads the per-group memberId per-frame, D51), so the shared
    // fake needs no standing /show_address responder — the CDI bean's boot-time
    // start() connects its WebSocket and returns without issuing a query.
    static {
        try {
            sharedFakeSimplex = new FakeSimpleXProcess();
            sharedFakeSimplex.start();
            sharedFakeSignal = new FakeSignalCli();
            sharedSignalStoreDir = Files.createTempDirectory("m1-109-signal-store-");
            SignalAccountStoreFixture.writeStore(sharedSignalStoreDir,
                    "m1-109-test-account", "00000000-0000-0000-0000-000000000002");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Inject @SeedDataSource DataSource dataSource;
    @Inject AdapterRegistry registry;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject GrantAdminCommandHandler grantHandler;

    @BeforeEach
    void cleanup() throws Exception {
        // Seed the sentinel admin once per class run, BEFORE the
        // prefix-cleanup itself. ON CONFLICT (adapter, contact_id) DO
        // NOTHING absorbs any race / re-entrant call.
        if (sentinelSeeded.compareAndSet(false, true)) {
            ensureSentinelAdmin();
        }
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE target_contact_id LIKE ? "
                                + "OR actor_user_id IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%", PREFIX + "%");
                exec(conn,
                        "UPDATE users SET banned_by = NULL "
                                + "WHERE banned_by IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%");
                exec(conn,
                        "DELETE FROM users WHERE contact_id LIKE ?",
                        PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
        }
    }

    @AfterAll
    static void closeFakes() throws IOException {
        sharedFakeSimplex.close();
        sharedFakeSignal.close();
    }

    // ----- (1, 4, 9) crossAdapterIsolation ----------------------------------
    @Test
    @Order(1)
    void crossAdapterIsolation() throws Exception {
        // Acceptance item 1 (umbrella): with infochat.adapters=simplex,signal
        // and the per-adapter properties wired to the fakes, both
        // production CDI beans were activated by AdapterRegistry's seven
        // gates and reached MessagingStartup's reflective start() loop.
        // If any gate or any adapter.start() had thrown, that adapter
        // would be absent from activatedAdapters() (MessagingStartup's
        // §6.7 catch absorbs failures rather than re-raising); a complete
        // two-name list is the strongest cheap assertion that the
        // production-deployment shape is intact end-to-end.
        List<String> activated = registry.activatedAdapters().stream()
                .map(MessagingAdapter::name).sorted().toList();
        assertEquals(List.of(SIGNAL, SIMPLEX), activated,
                "both production adapters must be activated alongside one another");

        // Acceptance items 4 + 9: (adapter, contact_id) UNIQUE-constraint
        // isolation. The same contact_id string seeded under both
        // production adapter names produces distinct user rows; a
        // per-adapter mutation is bounded to one row.
        String sharedId = PREFIX + "isolation-sharedContactId";
        UUID idOnSimplex = seedUser(SIMPLEX, sharedId, false, false);
        UUID idOnSignal = seedUser(SIGNAL, sharedId, false, false);
        assertNotEquals(idOnSimplex, idOnSignal,
                "same contact_id on different production adapters MUST yield two distinct user ids");

        setIsAdmin(SIMPLEX, sharedId, true);
        assertTrue(isAdmin(SIMPLEX, sharedId),
                "(simplex, sharedId).is_admin must reflect the per-adapter UPDATE");
        assertFalse(isAdmin(SIGNAL, sharedId),
                "(signal, sharedId).is_admin must remain false — UNIQUE (adapter, contact_id) "
                        + "bounds the UPDATE to one row");
        // No post-test demotion: mirrors M1-105's crossAdapterIsolation
        // pattern. (simplex, sharedId) remains admin; the subsequent
        // @BeforeEach prefix-cleanup deletes it. The V5 DELETE trigger
        // relies on at least one admin remaining globally (pre-existing
        // admins or M1-105's leftover bobOnB row).
    }

    // ----- (5, 10) lastAdminGlobalAcrossAdapters ----------------------------
    @Test
    @Order(2)
    void lastAdminGlobalAcrossAdapters() throws Exception {
        // V5 trg_last_admin_protection_update counts is_admin=TRUE rows
        // globally across adapters (no adapter filter); revoking the
        // sole simplex admin while signal also has zero admins is
        // blocked because the count would drop to zero. Mirrors M1-105's
        // pattern under the production adapter names — snapshot
        // pre-existing admins so we can demote them during the test and
        // restore them in finally.
        List<UserKey> preExisting = snapshotPreExistingAdmins();
        String aliceOnSimplex = PREFIX + "lastAdmin-alice-on-simplex";
        String bobOnSignal = PREFIX + "lastAdmin-bob-on-signal";

        // Seed our two admins FIRST so the global count never dips below
        // 1 during the subsequent demotion of pre-existing admins. The
        // trigger only refuses the demotion that WOULD drop the count to
        // zero; any prior demotion that leaves ≥1 admin remaining is fine.
        seedUser(SIMPLEX, aliceOnSimplex, true, false);
        seedUser(SIGNAL, bobOnSignal, true, false);

        try {
            for (UserKey k : preExisting) {
                setIsAdmin(k.adapter, k.contactId, false);
            }

            // First revoke: count 2 → 1, allowed (bobOnSignal remains).
            setIsAdmin(SIMPLEX, aliceOnSimplex, false);
            assertFalse(isAdmin(SIMPLEX, aliceOnSimplex),
                    "first revoke must succeed when one other admin remains globally");

            // Second revoke: count 1 → 0, MUST trip the trigger. The
            // assertion proves the protection is GLOBAL (Signal having
            // zero admins does not let SimpleX's revoke through).
            SQLException trigger = assertThrows(SQLException.class,
                    () -> setIsAdmin(SIGNAL, bobOnSignal, false),
                    "revoking the global last admin must raise from the V5 trigger");
            assertNotNull(trigger.getMessage(),
                    "V5 trigger SQLException must carry a non-null message");
            assertTrue(trigger.getMessage().contains("last_admin_protection"),
                    "V5 trigger error must contain 'last_admin_protection'; got: "
                            + trigger.getMessage());
            assertTrue(isAdmin(SIGNAL, bobOnSignal),
                    "(signal, bob).is_admin must remain true — the trigger rolls back "
                            + "the UPDATE before it commits");
        } finally {
            // Restore: re-promote every pre-existing admin so the
            // JVM-wide DB landscape is identical to entry. bobOnSignal
            // is left admin (the trigger blocked his demotion) — this
            // is the same shape M1-105's lastAdminProtectionGlobal
            // leaves bobOnB on adapter-b. Subsequent cleanup deletes
            // succeed because the pre-existing snapshot (or M1-105's
            // own leftover admin row, if M1-105 ran earlier in the
            // same JVM) anchors the count.
            for (UserKey k : preExisting) {
                setIsAdmin(k.adapter, k.contactId, true);
            }
        }
    }

    // ----- (6, 11) grantAdminIsAdapterScoped ---------------------------------
    @Test
    @Order(3)
    void grantAdminIsAdapterScoped() throws Exception {
        // /grant-admin invoked from a simplex inbound context flips ONLY
        // (simplex, target).is_admin; the (signal, target) row with a
        // coincidentally identical contact_id is left untouched. The
        // handler reads the inbound adapter name from InboundContext
        // (commands.md §Admin), so directly setting that context faithfully
        // simulates dispatch from a chosen adapter without standing up a
        // full inbound-router pipeline.
        String admin = PREFIX + "grantScope-admin-simplex";
        String target = PREFIX + "grantScope-target";

        seedUser(SIMPLEX, admin, true, false);
        seedUser(SIMPLEX, target, false, false);
        // Mirrored contact_id on signal; the inbound-from-simplex
        // /grant-admin must NOT touch this row.
        seedUser(SIGNAL, target, false, false);

        inboundContext.setAdapterName(SIMPLEX);
        inboundContext.setSenderContactId(admin);
        OutboundMessage reply = grantHandler.handle(
                new ScopeRef.Dm(admin), "/grant-admin " + target);

        assertEquals(redactedGrantSuccess(target), reply.text(),
                "outbound body must be reply.grant_admin.success with redacted target");
        assertTrue(isAdmin(SIMPLEX, target),
                "(simplex, target).is_admin must flip to true");
        assertFalse(isAdmin(SIGNAL, target),
                "(signal, target).is_admin must remain false — the per-adapter scoping "
                        + "rule from commands.md §Admin bounds the UPDATE to the inbound "
                        + "adapter");
        // No post-test demotion: mirrors M1-105's
        // grantAdminScopedToInboundAdapter pattern. Both (simplex, admin)
        // and (simplex, target) remain admin after the test; the
        // subsequent @BeforeEach prefix-cleanup deletes them. The V5
        // DELETE trigger relies on at least one admin remaining globally
        // (pre-existing admins or M1-105's leftover bobOnB row), which
        // is the same fragility M1-105 carries.
    }

    // ----- (2, 7) simpleXCrashDoesNotAffectSignal -----------------------------
    @Test
    @Order(4)
    void simpleXCrashDoesNotAffectSignal() throws Exception {
        // §6.7 per-adapter resilience: a SimpleX subprocess crash —
        // simulated by closing the fake's server socket, which severs
        // the adapter's WebSocket connection at the TCP layer — must
        // not affect the Signal adapter's JSON-RPC connection. Fresh
        // adapters + fresh fakes (constructed via the public production
        // constructors, bypassing the CDI graph) so the test is fully
        // independent of both the class-wide shared statics and the
        // symmetric signalCrash test.
        try (FakeSimpleXProcess sxFake = new FakeSimpleXProcess();
             FakeSignalCli sgFake = new FakeSignalCli()) {
            sxFake.start();
            SimpleXAdapter sx = newSimpleXAdapter(sxFake);
            SignalAdapter sg = newSignalAdapter(sgFake);
            try {
                // sx.start() no longer issues a /show_address identity query
                // (M1-518), so no answerer is needed before it.
                sx.start();
                sg.start();

                // Barrier on Signal's JSON-RPC connection being established (TCP
                // probe + real connect = generation 2) before the liveness probe
                // below, so its 2000 ms budget measures only the post-crash write,
                // not the connect race. Under a loaded host the connect otherwise
                // races the probe and nextOutbound times out (M1-540). The sibling
                // signalCrashDoesNotAffectSimpleX barriers the same way via
                // sxFake.awaitClient.
                sgFake.awaitConnectionGeneration(2, 10_000);

                // Crash SimpleX: close the fake's listener + active client
                // socket. SimpleXAdapter's WebSocketClient observes the
                // close and enters its degraded state.
                sxFake.close();
                // Give the WS event loop a brief window to process the
                // disconnect. 250 ms is a generous upper bound for
                // in-process WebSocket close propagation; if the bound
                // is too tight on a loaded CI host, the subsequent
                // assertion would still detect a real failure (signal's
                // outbound never arriving at the fake), so the window
                // is conservatively low rather than padded out.
                Thread.sleep(250);

                // Probe Signal liveness: dispatch setTyping from a
                // background virtual thread (SignalJsonRpcClient.setTyping
                // calls call() which BLOCKS until the daemon's response),
                // then poll the fake for the outbound JSON-RPC line. If
                // signal's connection was severed by the simplex crash,
                // the line never reaches the fake and nextOutbound
                // throws. Unblock the typing thread by responding so the
                // test does not hang waiting for the virtual thread to
                // finish.
                Thread sender = Thread.ofVirtual().name("m1-109-signal-typing-after-sx-crash")
                        .start(() -> sg.setTyping(
                                new ScopeRef.Dm("00000000-0000-0000-0000-000000000999"),
                                true));
                JsonObject outbound = sgFake.nextOutbound(2000);
                assertNotNull(outbound,
                        "Signal must still process outbound JSON-RPC after SimpleX crash");
                sgFake.respondSuccess(outbound.getString("id"),
                        Json.createObjectBuilder().build());
                sender.join(3000);
            } finally {
                sx.close();
                sg.close();
            }
        }
    }

    // ----- (3, 8) signalCrashDoesNotAffectSimpleX -----------------------------
    @Test
    @Order(5)
    void signalCrashDoesNotAffectSimpleX() throws Exception {
        // Symmetric to simpleXCrashDoesNotAffectSignal: a Signal
        // subprocess crash must not affect SimpleX's WebSocket
        // connection. Fresh adapters + fresh fakes for the same
        // independence reason documented on the simplex-crash test.
        try (FakeSimpleXProcess sxFake = new FakeSimpleXProcess();
             FakeSignalCli sgFake = new FakeSignalCli()) {
            sxFake.start();
            SimpleXAdapter sx = newSimpleXAdapter(sxFake);
            SignalAdapter sg = newSignalAdapter(sgFake);
            try {
                // sx.start() no longer issues a /show_address identity query
                // (M1-518); the test's liveness probe below is the only reader
                // of sxFake's frame queue, so no answerer thread can steal it.
                sx.start();
                sg.start();
                // Wait for SimpleX's WebSocket handshake to complete on
                // the fake's accept thread before the crash. Without this
                // barrier the subsequent setTyping could race with the
                // not-yet-connected client (the SimpleXWebSocketClient
                // connect() is awaited inside ws.start(), so by the time
                // sx.start() returned the connect was issued — but the
                // fake's accept handler runs on its own virtual thread
                // and may not have completed the handshake bookkeeping).
                sxFake.awaitClient(Duration.ofSeconds(10));

                // Crash Signal.
                sgFake.close();
                Thread.sleep(250);

                // Probe SimpleX liveness via a real send dispatched from
                // a background virtual thread (setTyping is no longer a
                // probe option: the SPI declares it a no-op for
                // supportsTypingIndicator=false adapters, so it issues no
                // frame). send() blocks awaiting the command ack, which
                // the fake never produces — the probe only needs the
                // frame BYTES to reach the fake's queue, and sx.close()
                // in the finally completes the in-flight send
                // exceptionally so the probe thread never outlives the
                // test.
                Thread.ofVirtual().name("m1-204-simplex-send-after-sg-crash")
                        .start(() -> {
                            try {
                                sx.send(new OutboundMessage(
                                        new ScopeRef.Dm("simplex-recipient-queue"),
                                        "liveness probe",
                                        Instant.now(),
                                        "liveness-probe-after-signal-crash"));
                            } catch (MessagingException ignored) {
                                // ack timeout / close-triggered abort —
                                // irrelevant to the probe.
                            }
                        });
                String frame = sxFake.awaitFrame(Duration.ofSeconds(2));
                assertNotNull(frame,
                        "SimpleX must still flush WebSocket frames after Signal crash");
            } finally {
                sx.close();
                sg.close();
            }
        }
    }

    // ----- per-test adapter factories ----------------------------------------

    private static SimpleXAdapter newSimpleXAdapter(FakeSimpleXProcess fake) {
        // /bin/sleep stands in for the simplex-chat binary: it ignores
        // the adapter's argument list and exits non-zero on launch.
        // SimpleXSubprocess restarts it asynchronously up to its crash
        // cap; the supervisor noise is suppressed via
        // src/test/resources/application.properties. The adapter's
        // start() waits on the WS port reaching ready and connects to
        // the fake (which IS bound and listening), so start() returns
        // success and the fake observes the WS handshake.
        SimpleXConfig cfg = new SimpleXConfig("/bin/sleep", "/tmp", fake.port());
        return new SimpleXAdapter(
                cfg,
                HttpClient.newHttpClient(),
                // Admin notifications from the supervisor go to /dev/null
                // — this IT is testing cross-adapter blast-radius, not
                // the notification channel. Wiring a logger here would
                // surface the suppressed supervisor failures, which is
                // the noise the per-test application.properties is set
                // up to dampen. The bot needs no construction-time identity:
                // start() no longer derives one (M1-518).
                notice -> { });
    }

    private static SignalAdapter newSignalAdapter(FakeSignalCli fake) {
        // Same /bin/sleep stand-in pattern as SimpleX. SignalAdapter.start()
        // derives the bot ACI from the shared account-store fixture, then
        // awaits the daemon endpoint via TCP connect probe; the fake
        // accepts on its bound port so the probe succeeds and the
        // SignalJsonRpcClient connects.
        return new SignalAdapter(
                "/bin/sleep", sharedSignalStoreDir.toString(), "m1-109-test-account",
                fake.endpoint());
    }

    // ----- helpers (mirrored from M1-105's MultiAdapterIsolationIT) ---------

    /** (adapter, contact_id) pair captured by {@link #snapshotPreExistingAdmins()}. */
    private record UserKey(String adapter, String contactId) {}

    private List<UserKey> snapshotPreExistingAdmins() throws Exception {
        List<UserKey> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             // Exclude rows under our own prefix so a re-entrant test
             // does not capture its own seeded admins as "pre-existing".
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT adapter, contact_id FROM users "
                             + "WHERE is_admin = TRUE AND is_banned = FALSE "
                             + "AND contact_id NOT LIKE ?")) {
            ps.setString(1, PREFIX + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new UserKey(rs.getString("adapter"), rs.getString("contact_id")));
                }
            }
        }
        return out;
    }

    /**
     * Idempotently seed the JVM-wide sentinel admin row. ON CONFLICT
     * (adapter, contact_id) DO NOTHING handles the case where a prior
     * test class run inside the same Postgres container already
     * inserted it.
     */
    private void ensureSentinelAdmin() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state, banned_at) "
                             + "VALUES (?, ?, TRUE, FALSE, 'vouched', NULL) "
                             + "ON CONFLICT (adapter, contact_id) DO NOTHING")) {
            ps.setString(1, SENTINEL_ADAPTER);
            ps.setString(2, SENTINEL_CONTACT);
            ps.executeUpdate();
        }
    }

    private UUID seedUser(String adapter, String contactId, boolean isAdmin,
                          boolean isBanned) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state, banned_at) "
                             + "VALUES (?, ?, ?, ?, 'vouched', "
                             + "CASE WHEN ? THEN NOW() ELSE NULL END) "
                             + "RETURNING id")) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            ps.setBoolean(4, isBanned);
            ps.setBoolean(5, isBanned);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void setIsAdmin(String adapter, String contactId, boolean isAdmin) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET is_admin = ? WHERE adapter = ? AND contact_id = ?")) {
            ps.setBoolean(1, isAdmin);
            ps.setString(2, adapter);
            ps.setString(3, contactId);
            ps.executeUpdate();
        }
    }

    private boolean isAdmin(String adapter, String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_admin FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return rs.getBoolean("is_admin");
            }
        }
    }

    private String redactedGrantSuccess(String contactId) {
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_GRANT_ADMIN_SUCCESS),
                ContactIds.redact(contactId));
    }

    private static void exec(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    // ----- profile -----------------------------------------------------------

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // simplex,signal activates both production adapters via gate 2
            // (every CSV name resolves to a registered bean — the
            // production beans live in ProductionAdapterBeans (M1-120)).
            // Gate 7 (bootstrap admin union non-empty) is satisfied by the
            // two .admin entries. Signal's bot-identity anchor is derived at
            // start() from the account-store fixture under .data-dir; SimpleX
            // needs none (the /show_address derivation was removed in M1-518).
            // The .binary / .data-dir / .ws-port / .account /
            // .endpoint entries point at the shared in-process fakes so
            // adapter.start() (called reflectively by MessagingStartup at
            // @PostConstruct) connects to the fakes' bound ephemeral
            // ports; /bin/sleep is the binary placeholder and the
            // supervisor's launch-failure WARNs are suppressed via
            // src/test/resources/application.properties.
            return Map.ofEntries(
                    Map.entry("infochat.adapters", "simplex,signal"),
                    Map.entry("infochat.adapters.simplex.binary", "/bin/sleep"),
                    Map.entry("infochat.adapters.simplex.data-dir", "/tmp"),
                    Map.entry("infochat.adapters.simplex.ws-port",
                            String.valueOf(sharedFakeSimplex.port())),
                    // Well-formed SimpleX queue address (URL-safe base64,
                    // >=43 chars) so AdapterRegistry's bootstrap-admin parse
                    // gate passes; the prior kebab-slug value is rejected by
                    // SimpleXIdentity.isWellFormed (M1-208).
                    Map.entry("infochat.adapters.simplex.admin",
                            "M1109SimplexBootstrapAdminQueueAddr000000000A"),
                    Map.entry("infochat.adapters.signal.binary", "/bin/sleep"),
                    Map.entry("infochat.adapters.signal.data-dir",
                            sharedSignalStoreDir.toString()),
                    Map.entry("infochat.adapters.signal.account", "m1-109-test-account"),
                    Map.entry("infochat.adapters.signal.admin",
                            "00000000-0000-0000-0000-000000000001"),
                    Map.entry("infochat.adapters.signal.endpoint",
                            "127.0.0.1:" + sharedFakeSignal.endpoint().getPort())
            );
        }
    }
}
