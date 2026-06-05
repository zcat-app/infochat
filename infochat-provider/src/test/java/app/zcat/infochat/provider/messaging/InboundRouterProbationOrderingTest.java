package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.command.AssetCommandFamilyOracle;
import app.zcat.infochat.provider.command.CommandPermissions;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
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
 * §Authorization model. Scenarios cover the runnable shape:
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
 *       consults probation; {@code probationCheck.inProbation} NOT
 *       called.</li>
 *   <li>(e) D47 gate #1: an unregistered group sender (no users row)
 *       is silently dropped at step 3 — no reply, dispatch returns
 *       before the ban check.</li>
 *   <li>(f) D47 gate #1: a {@code preban} group sender is silently
 *       dropped the same way.</li>
 *   <li>(g) The drop does NOT over-fire — a registered group sender
 *       in probation still reaches the step 5 probation gate.</li>
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

    // ----- (a) in-probation user + blocked command → gate fires --------------

    @Test
    void inProbationBlockedCommandShortCircuitsAtStep5() {
        CallLog log = new CallLog();
        UserSnapshotSeed snapshot = new UserSnapshotSeed(UUID.randomUUID(), "invited");
        Instant expiry = Instant.now().plus(2, ChronoUnit.HOURS);
        InboundRouter router = newRouter(log, snapshot, false, true, expiry, false);
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
                        "banCheck.isBanned",
                        "probationCheck.inProbation",
                        "commandPermissions.allowedDuringProbation(add-source)",
                        "probationCheck.probationExpiry",
                        "bundleLoader.get(error.probation.blocked)"),
                log.calls,
                "blocked-during-probation path must short-circuit at step 5 BEFORE dispatch — "
                        + "handler.handle and probationCheck.clearIfPromoted MUST NOT appear; got: " + log.calls);
    }

    // ----- (b) in-probation user + allowed command → falls through to dispatch

    @Test
    void inProbationAllowedCommandFallsThroughToDispatch() {
        CallLog log = new CallLog();
        UserSnapshotSeed snapshot = new UserSnapshotSeed(UUID.randomUUID(), "invited");
        Instant expiry = Instant.now().plus(2, ChronoUnit.HOURS);
        InboundRouter router = newRouter(log, snapshot, false, true, expiry, true);
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
                        "banCheck.isBanned",
                        "probationCheck.inProbation",
                        "commandPermissions.allowedDuringProbation(help)",
                        "handler.handle(help)"),
                log.calls,
                "allowed-during-probation path must reach dispatch; probationExpiry and clearIfPromoted MUST NOT appear; got: "
                        + log.calls);
    }

    // ----- (c) past-probation user → clearIfPromoted fires + dispatch --------

    @Test
    void pastProbationClearsAndDispatches() {
        CallLog log = new CallLog();
        UserSnapshotSeed snapshot = new UserSnapshotSeed(UUID.randomUUID(), "invited");
        InboundRouter router = newRouter(log, snapshot, false, false, null, true);
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
                        "banCheck.isBanned",
                        "probationCheck.inProbation",
                        "probationCheck.clearIfPromoted",
                        "handler.handle(add-source)"),
                log.calls,
                "past-probation path must call clearIfPromoted on the way to dispatch; got: " + log.calls);
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
        InboundRouter router = newRouter(log, snapshot, false, false, null, true);
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
        Instant expiry = Instant.now().plus(2, ChronoUnit.HOURS);
        InboundRouter router = newRouter(log, snapshot, false, true, expiry, false);
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
                        "banCheck.isBanned",
                        "groupApprovalCheck.check",
                        "probationCheck.inProbation",
                        "commandPermissions.allowedDuringProbation(add-source)",
                        "probationCheck.probationExpiry",
                        "bundleLoader.get(error.probation.blocked)"),
                log.calls,
                "registered group sender falls through step 3 to the probation gate; got: " + log.calls);
    }

    // ----- (d) banned user in probation → step 4 short-circuits before step 5

    @Test
    void bannedInProbationShortCircuitsAtStep4BeforeProbation() {
        CallLog log = new CallLog();
        UserSnapshotSeed snapshot = new UserSnapshotSeed(UUID.randomUUID(), "invited");
        Instant expiry = Instant.now().plus(2, ChronoUnit.HOURS);
        InboundRouter router = newRouter(log, snapshot, true, true, expiry, true);
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
                        "banCheck.isBanned",
                        "bundleLoader.get(error.ban.fixed)"),
                log.calls,
                "step 4 ban check must fire BEFORE step 5 probation check — "
                        + "probationCheck.inProbation MUST NOT appear; got: " + log.calls);
    }

    // ----- helpers ------------------------------------------------------------

    private record UserSnapshotSeed(UUID id, String registrationState) {}

    private InboundRouter newRouter(
            CallLog log,
            UserSnapshotSeed snapshot,
            boolean banned,
            boolean inProbation,
            Instant probationExpiry,
            boolean allowedDuringProbation) {
        InboundRouter router = new InboundRouter() {
            @Override
            Optional<UserSnapshot> lookupUser(String adapter, String contactId) {
                log.calls.add("lookupUser");
                return Optional.of(new UserSnapshot(
                        snapshot.id(),
                        snapshot.registrationState()));
            }
        };
        router.commandHandlers = new SingletonInstance<>();
        router.inboundContext = new RecordingInboundContext(log);
        router.rateCapBucket = new CountingRateCapBucket(log);
        router.inviteCodeConsumer = new FakeInviteCodeConsumer(log);
        router.banCheck = new FakeBanCheck(log, banned);
        router.bundleLoader = new FakeBundleLoader(log);
        router.confirmStateService = new NoopConfirmStateService();
        router.commandPermissions = new RecordingCommandPermissions(log, allowedDuringProbation);
        router.probationCheck = new RecordingProbationCheck(log, inProbation, probationExpiry);
        // M1-112: step 3.5 D47 approval gate. The recording fake logs
        // "groupApprovalCheck.check" into the CallLog ONLY when the
        // router's step-3.5 branch actually fires (group scope +
        // snapshot present). DM scenarios and unregistered-group
        // scenarios bypass step 3.5, so no spurious log entry appears.
        router.groupApprovalCheck = new RecordingGroupApprovalCheck(log);
        router.summaryAnchorRepository = new SummaryAnchorRepository() {
            @Override public void clear(UUID userId, UUID scopeId) {}
        };
        router.maxInboundBodyBytes = 65536;
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
            Optional<UserSnapshot> lookupUser(String adapter, String contactId) {
                log.calls.add("lookupUser");
                return Optional.empty();
            }
        };
        router.commandHandlers = new SingletonInstance<>();
        router.inboundContext = new RecordingInboundContext(log);
        router.rateCapBucket = new CountingRateCapBucket(log);
        router.inviteCodeConsumer = new FakeInviteCodeConsumer(log);
        router.banCheck = new FakeBanCheck(log, false);
        router.bundleLoader = new FakeBundleLoader(log);
        router.confirmStateService = new NoopConfirmStateService();
        router.commandPermissions = new RecordingCommandPermissions(log, true);
        router.probationCheck = new RecordingProbationCheck(log, false, null);
        // M1-112: step 3.5 D47 approval gate. The recording fake logs
        // "groupApprovalCheck.check" into the CallLog ONLY when the
        // router's step-3.5 branch actually fires (group scope +
        // snapshot present). DM scenarios and unregistered-group
        // scenarios bypass step 3.5, so no spurious log entry appears.
        router.groupApprovalCheck = new RecordingGroupApprovalCheck(log);
        router.summaryAnchorRepository = new SummaryAnchorRepository() {
            @Override public void clear(UUID userId, UUID scopeId) {}
        };
        router.maxInboundBodyBytes = 65536;
        return router;
    }

    /**
     * Records step 5 probation reads/UPDATEs into the {@link CallLog}.
     * The three knobs ({@code inProbation}, {@code probationExpiry},
     * and the no-op {@code clearIfPromoted}) drive scenarios (a)–(d).
     */
    private static final class RecordingProbationCheck extends ProbationCheck {
        private final CallLog log;
        private final boolean inProbationFlag;
        private final Instant expiry;
        RecordingProbationCheck(CallLog log, boolean inProbationFlag, Instant expiry) {
            this.log = log;
            this.inProbationFlag = inProbationFlag;
            this.expiry = expiry;
        }
        @Override
        public boolean inProbation(UUID userId) {
            log.calls.add("probationCheck.inProbation");
            return inProbationFlag;
        }
        @Override
        public void clearIfPromoted(UUID userId) {
            log.calls.add("probationCheck.clearIfPromoted");
        }
        @Override
        public Instant probationExpiry(UUID userId) {
            log.calls.add("probationCheck.probationExpiry");
            return expiry;
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
}
