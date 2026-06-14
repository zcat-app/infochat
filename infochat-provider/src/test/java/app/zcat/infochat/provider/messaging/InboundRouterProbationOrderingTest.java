package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.command.AssetCommandFamilyOracle;
import app.zcat.infochat.provider.command.CommandPermissions;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import app.zcat.infochat.provider.group.GroupApprovalCheck;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin the step 5 slow-start probation gate's position relative to
 * steps 3 (D47 group drop), 4 (ban) and 6 (parse + dispatch) in
 * {@link InboundRouter#onMessage} per
 * {@code docs/spec/security.md} §Slow-start tier +
 * §Authorization model. Since M1-364 the gate decides probation from
 * the per-dispatch {@link InboundRouter.UserSnapshot}'s
 * {@code probation_until} (no {@code probationCheck.inProbation} or
 * {@code probationExpiry} SELECT); the only step-5 collaborator call
 * left is the lazy {@code clearIfPromoted} UPDATE on the
 * just-graduated path. Scenarios cover the runnable shape:
 *
 * <ol>
 *   <li>(a) Registered, non-banned, in-probation user sending
 *       {@code /add-source} (a blocked-during-probation command) →
 *       gate emits {@code error.probation.blocked}; {@code handleSlash}
 *       NOT called; {@code clearIfPromoted} NOT called.</li>
 *   <li>(b) Same user sending {@code /help} (an allowed-during-
 *       probation command) → gate allows; dispatch fires.</li>
 *   <li>(c) Registered, non-banned, past-probation user sending
 *       {@code /add-source} → {@code clearIfPromoted} fires (the
 *       lazy clear on the way to dispatch); dispatch fires.</li>
 *   <li>(d) Banned user in probation sending {@code /help} → step 4
 *       short-circuits with {@code error.ban.fixed} BEFORE step 5
 *       consults probation.</li>
 *   <li>(e) D47 gate #1: an unregistered group sender (no users row)
 *       is silently dropped at step 3 — no reply, dispatch returns
 *       before the ban check.</li>
 *   <li>(f) D47 gate #1: a {@code preban} group sender is silently
 *       dropped the same way.</li>
 *   <li>(g) The drop does NOT over-fire — a registered group sender
 *       in probation still reaches the step 5 probation gate.</li>
 *   <li>(h) M1-364: a steady-state non-probation inbound
 *       ({@code probation_until = NULL}) issues NO probation
 *       SELECT/UPDATE beyond the single user-snapshot SELECT — not
 *       even {@code commandPermissions.allowedDuringProbation}.</li>
 *   <li>(i) M1-364: the blocked-during-probation reply's
 *       time-until-unlock token is sourced from the snapshot's
 *       {@code probation_until}, not a live {@code probationExpiry}
 *       read.</li>
 * </ol>
 *
 * <p>Plain JUnit (no DevServices Postgres) — every collaborator is
 * a hand-rolled fake that records its invocation into a
 * {@link CallLog}. The mirror of {@code InboundRouterIntakeOrderingTest}
 * patterns lives here because the new step 5 collaborators
 * ({@link ProbationCheck}, {@link CommandPermissions}) require
 * call-order recording that is not part of the pre-existing test
 * file's helpers — the duplication is intentional and bounded.
 */
class InboundRouterProbationOrderingTest {

    private static final String ADAPTER = "inmemory";
    private static final String DM_CONTACT = "probation-ordering-test-contact";
    private static final UUID GROUP_ROW_ID = UUID.randomUUID();

    // ----- (a) in-probation user + blocked command → gate fires --------------

    @Test
    void inProbationBlockedCommandShortCircuitsAtStep5() {
        CallLog log = new CallLog();
        UserSnapshotSeed snapshot = new UserSnapshotSeed(UUID.randomUUID(), "invited");
        Instant probationUntil = Instant.now().plus(2, ChronoUnit.HOURS);
        InboundRouter router = newRouter(log, snapshot, false, probationUntil, false);
        // RecordingCommandHandler wired but MUST NOT be invoked.
        router.commandHandlers = new SingletonInstance<>(new RecordingCommandHandler(log, "add-source"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(dmInbound("/add-source https://example.org/feed --tags x"), ADAPTER);

        assertEquals(1, target.captured.size(),
                "blocked-during-probation path must produce exactly one reply; got: " + target.captured);
        assertTrue(target.captured.get(0).text()
                        .startsWith(FakeBundleLoader.stubFor(BundleKeys.ERROR_PROBATION_BLOCKED)),
                "blocked-during-probation reply must come from the error.probation.blocked bundle entry; got: "
                        + target.captured.get(0).text());
        assertEquals(
                List.of(
                        "setAdapterName",
                        "rateCapBucket.tryAcquire",
                        "lookupUser",
                        "commandPermissions.allowedDuringProbation(add-source)",
                        "bundleLoader.get(error.probation.blocked)"),
                log.calls,
                "blocked-during-probation path must short-circuit at step 5 BEFORE dispatch — "
                        + "handler.handle and probationCheck.clearIfPromoted MUST NOT appear, and the gate "
                        + "reads the snapshot (no probationCheck SELECT); got: " + log.calls);
    }

    // ----- (b) in-probation user + allowed command → falls through to dispatch

    @Test
    void inProbationAllowedCommandFallsThroughToDispatch() {
        CallLog log = new CallLog();
        UserSnapshotSeed snapshot = new UserSnapshotSeed(UUID.randomUUID(), "invited");
        Instant probationUntil = Instant.now().plus(2, ChronoUnit.HOURS);
        InboundRouter router = newRouter(log, snapshot, false, probationUntil, true);
        router.commandHandlers = new SingletonInstance<>(new RecordingCommandHandler(log, "help"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(dmInbound("/help"), ADAPTER);

        assertEquals(1, target.captured.size(),
                "allowed-during-probation path must produce exactly one reply; got: " + target.captured);
        assertEquals("handler-reply:help", target.captured.get(0).text(),
                "the dispatch reply is the RecordingCommandHandler's output, not the probation literal");
        assertEquals(
                List.of(
                        "setAdapterName",
                        "rateCapBucket.tryAcquire",
                        "lookupUser",
                        "commandPermissions.allowedDuringProbation(help)",
                        "handler.handle(help)"),
                log.calls,
                "allowed-during-probation path must reach dispatch; clearIfPromoted MUST NOT appear; got: "
                        + log.calls);
    }

    // ----- (c) past-probation user → clearIfPromoted fires + dispatch --------

    @Test
    void pastProbationClearsAndDispatches() {
        CallLog log = new CallLog();
        UserSnapshotSeed snapshot = new UserSnapshotSeed(UUID.randomUUID(), "invited");
        // probation_until in the past: the snapshot reports not-in-probation,
        // so the lazy clearIfPromoted UPDATE fires on the way to dispatch.
        Instant probationUntil = Instant.now().minus(2, ChronoUnit.HOURS);
        InboundRouter router = newRouter(log, snapshot, false, probationUntil, true);
        router.commandHandlers = new SingletonInstance<>(new RecordingCommandHandler(log, "add-source"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(dmInbound("/add-source https://example.org/feed --tags x"), ADAPTER);

        assertEquals(1, target.captured.size(),
                "past-probation path must produce exactly one reply; got: " + target.captured);
        assertEquals("handler-reply:add-source", target.captured.get(0).text(),
                "the dispatch reply is the RecordingCommandHandler's output");
        assertEquals(
                List.of(
                        "setAdapterName",
                        "rateCapBucket.tryAcquire",
                        "lookupUser",
                        "probationCheck.clearIfPromoted",
                        "handler.handle(add-source)"),
                log.calls,
                "past-probation path must call clearIfPromoted on the way to dispatch; got: " + log.calls);
    }

    // ----- (h) M1-364: steady-state non-probation issues no probation query --

    @Test
    void steadyStateNonProbationIssuesNoProbationQueryBeyondSnapshot() {
        // probation_until = NULL: the snapshot reports not-in-probation AND
        // the column is null, so step 5 issues no clearIfPromoted UPDATE and
        // never consults commandPermissions — the gate is invisible. Proves
        // the common-case hot path costs exactly the one user-snapshot SELECT.
        CallLog log = new CallLog();
        UserSnapshotSeed snapshot = new UserSnapshotSeed(UUID.randomUUID(), "invited");
        InboundRouter router = newRouter(log, snapshot, false, null, true);
        router.commandHandlers = new SingletonInstance<>(new RecordingCommandHandler(log, "help"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(dmInbound("/help"), ADAPTER);

        assertEquals(1, target.captured.size(),
                "steady-state path must produce exactly one reply; got: " + target.captured);
        assertEquals(
                List.of(
                        "setAdapterName",
                        "rateCapBucket.tryAcquire",
                        "lookupUser",
                        "handler.handle(help)"),
                log.calls,
                "steady-state non-probation inbound must issue NO probation SELECT/UPDATE and NO "
                        + "commandPermissions call beyond the single user-snapshot SELECT; got: " + log.calls);
        assertTrue(log.calls.stream().noneMatch(c -> c.startsWith("probationCheck.")),
                "no probationCheck collaborator call may appear for a NULL-probation inbound; got: " + log.calls);
    }

    // ----- (i) M1-364: blocked reply's unlock time is sourced from snapshot --

    @Test
    void probationBlockedReplyUnlockTimeSourcedFromSnapshot() {
        CallLog log = new CallLog();
        UserSnapshotSeed snapshot = new UserSnapshotSeed(UUID.randomUUID(), "invited");
        // A wide margin so the truncated-hours formatting is stable across
        // the microseconds between the router's format call and this one.
        Instant probationUntil = Instant.now().plus(5, ChronoUnit.HOURS).plus(30, ChronoUnit.MINUTES);
        InboundRouter router = newRouter(log, snapshot, false, probationUntil, false);
        // A {0}-bearing template for the probation key so the interpolated
        // unlock token actually lands in the reply text (the default stub
        // has no placeholder).
        router.bundleLoader = new ProbationTemplateBundleLoader(log);
        router.commandHandlers = new SingletonInstance<>(new RecordingCommandHandler(log, "add-source"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(dmInbound("/add-source https://example.org/feed --tags x"), ADAPTER);

        assertEquals(1, target.captured.size(),
                "blocked-during-probation path must produce exactly one reply; got: " + target.captured);
        assertEquals("unlock-in " + InboundRouter.formatTimeUntilUnlock(probationUntil),
                target.captured.get(0).text(),
                "the unlock token must be formatted from the snapshot's probation_until; got: "
                        + target.captured.get(0).text());
        assertTrue(log.calls.stream().noneMatch(c -> c.startsWith("probationCheck.")),
                "the blocked path must source the expiry from the snapshot, not a probationCheck SELECT; got: "
                        + log.calls);
    }

    // ----- (e) D47: unregistered group sender → silent drop at step 3 --------

    @Test
    void unregisteredGroupSenderIsSilentlyDropped() {
        // D47 gate #1: a group @mention from a contact with no users
        // row produces NO reply, NO DB write, NO registration. Dispatch
        // returns at step 3 BEFORE the ban check, probation gate, and
        // any handler.
        CallLog log = new CallLog();
        InboundRouter router = newRouterEmptySnapshot(log);
        router.commandHandlers = new SingletonInstance<>(new RecordingCommandHandler(log, "help"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(groupInbound("/help"), ADAPTER);

        assertEquals(0, target.captured.size(),
                "unregistered group sender must receive NO reply (silent drop); got: " + target.captured);
        assertEquals(
                List.of(
                        "setAdapterName",
                        "rateCapBucket.tryAcquire",
                        "lookupUser"),
                log.calls,
                "D47 silent drop must return at step 3 BEFORE banCheck / probation / dispatch; got: "
                        + log.calls);
    }

    // ----- (f) D47: preban group sender → silent drop at step 3 --------------

    @Test
    void prebanGroupSenderIsSilentlyDropped() {
        // D47 gate #1: a group @mention from a 'preban' contact is
        // dropped the same way as an unregistered one.
        CallLog log = new CallLog();
        UserSnapshotSeed snapshot = new UserSnapshotSeed(UUID.randomUUID(), "preban");
        InboundRouter router = newRouter(log, snapshot, false, null, true);
        router.commandHandlers = new SingletonInstance<>(new RecordingCommandHandler(log, "help"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(groupInbound("/help"), ADAPTER);

        assertEquals(0, target.captured.size(),
                "preban group sender must receive NO reply (silent drop); got: " + target.captured);
        assertEquals(
                List.of(
                        "setAdapterName",
                        "rateCapBucket.tryAcquire",
                        "lookupUser"),
                log.calls,
                "D47 silent drop for preban must return at step 3; got: " + log.calls);
    }

    // ----- (g) D47: registered group sender in probation is still gated ------

    @Test
    void registeredGroupSenderInProbationStillHitsProbationGate() {
        // The silent drop must NOT over-fire: a registered (invited/
        // vouched) group sender in probation sending a blocked command
        // still receives error.probation.blocked — proving the step-3
        // drop is scoped to unregistered / preban contacts only.
        CallLog log = new CallLog();
        UserSnapshotSeed snapshot = new UserSnapshotSeed(UUID.randomUUID(), "invited");
        Instant probationUntil = Instant.now().plus(2, ChronoUnit.HOURS);
        InboundRouter router = newRouter(log, snapshot, false, probationUntil, false);
        router.commandHandlers = new SingletonInstance<>(new RecordingCommandHandler(log, "add-source"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(groupInbound("/add-source https://example.org/feed --tags x"), ADAPTER);

        assertEquals(1, target.captured.size(),
                "registered group sender + blocked command must produce exactly one reply; got: "
                        + target.captured);
        assertTrue(target.captured.get(0).text()
                        .startsWith(FakeBundleLoader.stubFor(BundleKeys.ERROR_PROBATION_BLOCKED)),
                "registered group sender must be gated by probation, not silently dropped; got: "
                        + target.captured.get(0).text());
        assertEquals(
                List.of(
                        "setAdapterName",
                        "rateCapBucket.tryAcquire",
                        "lookupUser",
                        "groupApprovalCheck.check",
                        "commandPermissions.allowedDuringProbation(add-source)",
                        "bundleLoader.get(error.probation.blocked)"),
                log.calls,
                "registered group sender falls through step 3 to the probation gate; got: " + log.calls);
    }

    // ----- (d) banned user in probation → step 4 short-circuits before step 5

    @Test
    void bannedInProbationShortCircuitsAtStep4BeforeProbation() {
        CallLog log = new CallLog();
        UserSnapshotSeed snapshot = new UserSnapshotSeed(UUID.randomUUID(), "invited");
        Instant probationUntil = Instant.now().plus(2, ChronoUnit.HOURS);
        InboundRouter router = newRouter(log, snapshot, true, probationUntil, true);
        router.commandHandlers = new SingletonInstance<>(new RecordingCommandHandler(log, "help"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(dmInbound("/help"), ADAPTER);

        assertEquals(1, target.captured.size(),
                "banned path must produce exactly one ban-fixed reply; got: " + target.captured);
        assertEquals(FakeBundleLoader.stubFor(BundleKeys.ERROR_BAN_FIXED),
                target.captured.get(0).text(),
                "banned-in-probation reply must equal the error.ban.fixed bundle entry — step 4 wins");
        assertEquals(
                List.of(
                        "setAdapterName",
                        "rateCapBucket.tryAcquire",
                        "lookupUser",
                        "bundleLoader.get(error.ban.fixed)"),
                log.calls,
                "step 4 ban check must fire BEFORE step 5 probation gate — "
                        + "no probation collaborator call may appear; got: " + log.calls);
    }

    // ----- helpers ------------------------------------------------------------

    private record UserSnapshotSeed(UUID id, String registrationState) {}

    private InboundRouter newRouter(
            CallLog log,
            UserSnapshotSeed snapshot,
            boolean banned,
            @Nullable Instant probationUntil,
            boolean allowedDuringProbation) {
        InboundRouter router = new InboundRouter() {
            @Override
            Optional<UserSnapshot> lookupUser(DispatchDb db, String adapter, String contactId) {
                log.calls.add("lookupUser");
                return Optional.of(new UserSnapshot(
                        snapshot.id(),
                        snapshot.registrationState(),
                        banned,
                        probationUntil));
            }

            @Override
            String lookupScopeLanguage(DispatchDb db, String scopeKind, UUID scopeId) {
                return "en";
            }
        };
        router.commandHandlers = new SingletonInstance<>();
        router.inboundContext = new RecordingInboundContext(log);
        router.rateCapBucket = new CountingRateCapBucket(log);
        router.inviteCodeConsumer = new FakeInviteCodeConsumer(log);
        router.bundleLoader = new FakeBundleLoader(log);
        router.confirmStateService = new NoopConfirmStateService();
        router.commandPermissions = new RecordingCommandPermissions(log, allowedDuringProbation);
        router.probationCheck = new RecordingProbationCheck(log);
        // M1-112: step 3.5 D47 approval gate. The recording fake logs
        // "groupApprovalCheck.check" into the CallLog ONLY when the
        // router's step-3.5 branch actually fires (group scope +
        // snapshot present). DM scenarios and unregistered-group
        // scenarios bypass step 3.5, so no spurious log entry appears.
        // The Approved outcome carries GROUP_ROW_ID — the id the router
        // now forwards as the group dispatch scope id, replacing the
        // former step-4.1 lookupGroupId re-read.
        router.groupApprovalCheck = new RecordingGroupApprovalCheck(
                log, new GroupApprovalCheck.Outcome.Approved(GROUP_ROW_ID));
        router.summaryAnchorRepository = new SummaryAnchorRepository() {
            @Override public void clear(UUID userId, String scopeKind, UUID scopeId) {}
        };
        // §7a wiring: the production fields are non-null by contract, so
        // the plain-JUnit setup supplies log-silent doubles instead of
        // relying on removed null branches. The fake JDBC stack only
        // serves the step-4.1 membership upsert (the lookups are
        // overridden seams above); the no-arg oracle answers false for
        // every asset probe.
        router.registeredContactSet = new NoopRegisteredContactSet();
        router.assetCommandFamilyOracle = new AssetCommandFamilyOracle();
        CountingDispatchDataSource dispatchDataSource =
                new CountingDispatchDataSource(snapshot.id());
        router.dataSource = dispatchDataSource;
        router.groupAutoPromoteService = new NoopGroupAutoPromoteService(dispatchDataSource);
        router.maxInboundBodyBytes = 65536;
        router.commandBodyCap = 65536;
        router.outboundDelivery = TestOutboundDelivery.passThrough();
        return router;
    }

    private static InboundMessage dmInbound(String body) {
        return new InboundMessage(
                new Identity(DM_CONTACT, "Alice", Instant.now()),
                new ScopeRef.Dm(DM_CONTACT),
                body,
                Instant.now(),
                "msg-1");
    }

    private static InboundMessage groupInbound(String body) {
        return new InboundMessage(
                new Identity(DM_CONTACT, "Bob", Instant.now()),
                new ScopeRef.Group("probation-ordering-test-group"),
                body,
                Instant.now(),
                "msg-1");
    }

    /**
     * Build a router whose {@code lookupUser} always returns empty —
     * the unregistered-contact case. Used by the D47 silent-drop
     * scenario (e): a group {@code @mention} from an unknown contact
     * returns at step 3 with no outbound and no DB write.
     */
    private InboundRouter newRouterEmptySnapshot(CallLog log) {
        InboundRouter router = new InboundRouter() {
            @Override
            Optional<UserSnapshot> lookupUser(DispatchDb db, String adapter, String contactId) {
                log.calls.add("lookupUser");
                return Optional.empty();
            }

            @Override
            String lookupScopeLanguage(DispatchDb db, String scopeKind, UUID scopeId) {
                return "en";
            }
        };
        router.commandHandlers = new SingletonInstance<>();
        router.inboundContext = new RecordingInboundContext(log);
        router.rateCapBucket = new CountingRateCapBucket(log);
        router.inviteCodeConsumer = new FakeInviteCodeConsumer(log);
        router.bundleLoader = new FakeBundleLoader(log);
        router.confirmStateService = new NoopConfirmStateService();
        router.commandPermissions = new RecordingCommandPermissions(log, true);
        router.probationCheck = new RecordingProbationCheck(log);
        // M1-112: step 3.5 D47 approval gate. The recording fake logs
        // "groupApprovalCheck.check" into the CallLog ONLY when the
        // router's step-3.5 branch actually fires (group scope +
        // snapshot present). DM scenarios and unregistered-group
        // scenarios bypass step 3.5, so no spurious log entry appears.
        router.groupApprovalCheck = new RecordingGroupApprovalCheck(log);
        router.summaryAnchorRepository = new SummaryAnchorRepository() {
            @Override public void clear(UUID userId, String scopeKind, UUID scopeId) {}
        };
        // §7a wiring: the intake always consults the registered-contact
        // set (step 1.5) before this scenario's step-3 drop.
        router.registeredContactSet = new NoopRegisteredContactSet();
        router.maxInboundBodyBytes = 65536;
        router.commandBodyCap = 65536;
        router.outboundDelivery = TestOutboundDelivery.passThrough();
        return router;
    }

    /**
     * Records the lazy graduation {@code clearIfPromoted} UPDATE into
     * the {@link CallLog}. Since M1-364 the router no longer calls
     * {@code inProbation} or {@code probationExpiry} — the snapshot
     * answers both — so this fake records only the one call that
     * remains on the step-5 path.
     */
    private static final class RecordingProbationCheck extends ProbationCheck {
        private final CallLog log;
        RecordingProbationCheck(CallLog log) {
            this.log = log;
        }
        @Override
        public void clearIfPromoted(UUID userId) {
            log.calls.add("probationCheck.clearIfPromoted");
        }
    }

    /**
     * Records step 5 permission lookups into the {@link CallLog}.
     * The command name is captured so the assertion can pin which
     * slash was checked (e.g. {@code allowedDuringProbation(help)}).
     */
    private static final class RecordingCommandPermissions extends CommandPermissions {
        private final CallLog log;
        private final boolean allowed;
        RecordingCommandPermissions(CallLog log, boolean allowed) {
            super(new AssetCommandFamilyOracle());
            this.log = log;
            this.allowed = allowed;
        }
        @Override
        public boolean allowedDuringProbation(String slashCommand) {
            log.calls.add("commandPermissions.allowedDuringProbation(" + slashCommand + ")");
            return allowed;
        }
    }

    /**
     * A {@link BundleLoader} double that returns a {@code {0}}-bearing
     * template for {@code error.probation.blocked} so scenario (i) can
     * assert the snapshot-sourced unlock token lands in the reply. All
     * other keys fall through to the shared {@link FakeBundleLoader}
     * stub, and every lookup is recorded into the {@link CallLog} with
     * the same {@code bundleLoader.get(key)} shape the recording fakes
     * use. (It extends {@code BundleLoader} rather than
     * {@code FakeBundleLoader}, which is {@code final}.)
     */
    private static final class ProbationTemplateBundleLoader extends BundleLoader {
        private final CallLog log;
        ProbationTemplateBundleLoader(CallLog log) {
            this.log = log;
        }
        @Override
        public String get(String key) {
            return get(key, "en");
        }
        @Override
        public String get(String key, String langCode) {
            log.calls.add("bundleLoader.get(" + key + ")");
            if (BundleKeys.ERROR_PROBATION_BLOCKED.equals(key)) {
                return "unlock-in {0}";
            }
            return FakeBundleLoader.stubFor(key);
        }
    }
}
