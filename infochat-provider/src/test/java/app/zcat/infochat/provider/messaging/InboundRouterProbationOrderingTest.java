package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.command.AssetCommandFamilyOracle;
import app.zcat.infochat.provider.command.CommandPermissions;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import app.zcat.infochat.provider.command.ConfirmStateService;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin the step 5 slow-start probation gate's position relative to
 * steps 4 (ban) and 6 (parse + dispatch) in
 * {@link InboundRouter#onMessage} per
 * {@code docs/spec/security.md} §Slow-start tier +
 * §Authorization model. Four scenarios cover the runnable shape:
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
        UserSnapshotSeed snapshot = new UserSnapshotSeed(UUID.randomUUID(), false, "invited");
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
        UserSnapshotSeed snapshot = new UserSnapshotSeed(UUID.randomUUID(), false, "invited");
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
        UserSnapshotSeed snapshot = new UserSnapshotSeed(UUID.randomUUID(), false, "invited");
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

    // ----- (e) group auto-register first message → step 5 still fires --------

    @Test
    void groupAutoRegisterFirstMessageBlocksProbationCommand() {
        // M1-045 redteam-fix AUTH-BYPASS: a group @mention from an
        // unregistered contact must hit step 5's probation gate on
        // the SAME message. Before the fix, snapshot was empty at
        // step 5 (captured BEFORE step 3's auto-register insert) and
        // the gate was skipped. With Fix 1's re-fetch, snapshot
        // carries the just-inserted row's (id, registration_state,
        // probation_until) and the gate enforces /add-source as
        // blocked-during-probation.
        CallLog log = new CallLog();
        Instant expiry = Instant.now().plus(2, ChronoUnit.HOURS);
        InboundRouter router = newRouterForGroupAutoRegister(
                log,
                /* banned */ false,
                /* inProbation */ true,
                /* probationExpiry */ expiry,
                /* allowedDuringProbation */ false);
        router.commandHandlers = new SingletonInstance<>(new RecordingCommandHandler(log, "add-source"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(groupInbound("/add-source https://example.org/feed --tags x"), ADAPTER);

        assertEquals(1, target.captured.size(),
                "group auto-register + blocked-during-probation must produce exactly one reply; got: "
                        + target.captured);
        assertTrue(target.captured.get(0).text()
                        .startsWith(FakeBundleLoader.stubFor(BundleKeys.ERROR_PROBATION_BLOCKED)),
                "group auto-register first message + blocked command must surface "
                        + "error.probation.blocked, NOT short-circuit past step 5; got: "
                        + target.captured.get(0).text());
        assertEquals(
                List.of(
                        "setAdapterName",
                        "rateCapBucket.tryAcquire",
                        "lookupUser",
                        "autoRegisterService.resolveOrRegisterGroup",
                        "lookupUser",
                        "banCheck.isBanned",
                        "probationCheck.inProbation",
                        "commandPermissions.allowedDuringProbation(add-source)",
                        "probationCheck.probationExpiry",
                        "bundleLoader.get(error.probation.blocked)"),
                log.calls,
                "AUTH-BYPASS fix: snapshot re-fetched after step-3 auto-register; step 5 "
                        + "probation gate fires on the FIRST message; got: " + log.calls);
    }

    // ----- (f) group_only DM in probation → DM-gate (step 4.7) wins ----------

    @Test
    void groupOnlyDmInProbationReceivesInviteRequiredNotProbationBlocked() {
        // M1-045 redteam-fix INFO-LEAK: a group_only user (registered
        // via group @mention) DMing the bot while still in probation
        // must receive the fixed error.invite.required reply, not the
        // probation-blocked reply with its allowed-set enumeration.
        // Step 4.7 (DM-gate) now fires BEFORE step 5 (probation), so
        // probationCheck.inProbation MUST NOT appear in the call list.
        CallLog log = new CallLog();
        UserSnapshotSeed snapshot = new UserSnapshotSeed(UUID.randomUUID(), false, "group_only");
        Instant expiry = Instant.now().plus(2, ChronoUnit.HOURS);
        InboundRouter router = newRouter(log, snapshot, false, true, expiry, false);
        router.commandHandlers = new SingletonInstance<>(new RecordingCommandHandler(log, "help"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(dmInbound("/help"), ADAPTER);

        assertEquals(1, target.captured.size(),
                "group_only DM in probation must produce exactly one reply; got: " + target.captured);
        assertEquals(FakeBundleLoader.stubFor(BundleKeys.ERROR_INVITE_REQUIRED),
                target.captured.get(0).text(),
                "INFO-LEAK fix: step 4.7 DM-gate emits error.invite.required (the fixed spec "
                        + "reply), NOT error.probation.blocked (which would enumerate the "
                        + "allowed-set, a probe surface)");
        assertEquals(
                List.of(
                        "setAdapterName",
                        "rateCapBucket.tryAcquire",
                        "lookupUser",
                        "banCheck.isBanned",
                        "bundleLoader.get(error.invite.required)"),
                log.calls,
                "INFO-LEAK fix: step 4.7 DM-gate fires BEFORE step 5 probation — "
                        + "probationCheck.inProbation MUST NOT appear; got: " + log.calls);
    }

    // ----- (d) banned user in probation → step 4 short-circuits before step 5

    @Test
    void bannedInProbationShortCircuitsAtStep4BeforeProbation() {
        CallLog log = new CallLog();
        UserSnapshotSeed snapshot = new UserSnapshotSeed(UUID.randomUUID(), true, "invited");
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

    private record UserSnapshotSeed(UUID id, boolean banned, String registrationState) {}

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
                        snapshot.banned(),
                        snapshot.registrationState()));
            }
        };
        router.commandHandlers = new SingletonInstance<>();
        router.autoRegisterService = new RecordingAutoRegisterService(log);
        router.inboundContext = new RecordingInboundContext(log);
        router.rateCapBucket = new CountingRateCapBucket(log);
        router.inviteCodeConsumer = new FakeInviteCodeConsumer(log);
        router.banCheck = new FakeBanCheck(log, banned);
        router.bundleLoader = new FakeBundleLoader(log);
        router.confirmStateService = new NoopConfirmStateService();
        router.commandPermissions = new RecordingCommandPermissions(log, allowedDuringProbation);
        router.probationCheck = new RecordingProbationCheck(log, inProbation, probationExpiry);
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
     * Build a router whose {@code lookupUser} returns empty on the
     * FIRST call and a present in-probation snapshot on every
     * subsequent call. Exercises the M1-045 redteam-fix Fix 1 path:
     * step 1's snapshot is empty (unknown contact); step 3 fires the
     * RecordingAutoRegisterService and the post-step-3 re-fetch
     * returns the synthesized in-probation snapshot. Used by
     * scenario (e) only.
     */
    private InboundRouter newRouterForGroupAutoRegister(
            CallLog log,
            boolean banned,
            boolean inProbation,
            Instant probationExpiry,
            boolean allowedDuringProbation) {
        java.util.concurrent.atomic.AtomicInteger lookupCallCount =
                new java.util.concurrent.atomic.AtomicInteger();
        UUID autoRegId = UUID.randomUUID();
        InboundRouter router = new InboundRouter() {
            @Override
            Optional<UserSnapshot> lookupUser(String adapter, String contactId) {
                log.calls.add("lookupUser");
                if (lookupCallCount.incrementAndGet() == 1) {
                    return Optional.empty();
                }
                return Optional.of(new UserSnapshot(autoRegId, false, "group_only"));
            }
        };
        router.commandHandlers = new SingletonInstance<>();
        router.autoRegisterService = new RecordingAutoRegisterService(log);
        router.inboundContext = new RecordingInboundContext(log);
        router.rateCapBucket = new CountingRateCapBucket(log);
        router.inviteCodeConsumer = new FakeInviteCodeConsumer(log);
        router.banCheck = new FakeBanCheck(log, banned);
        router.bundleLoader = new FakeBundleLoader(log);
        router.confirmStateService = new NoopConfirmStateService();
        router.commandPermissions = new RecordingCommandPermissions(log, allowedDuringProbation);
        router.probationCheck = new RecordingProbationCheck(log, inProbation, probationExpiry);
        router.summaryAnchorRepository = new SummaryAnchorRepository() {
            @Override public void clear(UUID userId, UUID scopeId) {}
        };
        router.maxInboundBodyBytes = 65536;
        return router;
    }

    private static final class CallLog {
        final List<String> calls = new ArrayList<>();
    }

    private static final class RecordingInboundContext extends InboundContext {
        private final CallLog log;
        RecordingInboundContext(CallLog log) { this.log = log; }
        @Override
        public void setAdapterName(String adapterName) {
            log.calls.add("setAdapterName");
            super.setAdapterName(adapterName);
        }
    }

    private static final class CountingRateCapBucket extends RateCapBucket {
        private final CallLog log;
        CountingRateCapBucket(CallLog log) { this.log = log; }
        @Override
        public boolean tryAcquire(String adapter, String contactId) {
            log.calls.add("rateCapBucket.tryAcquire");
            return true;
        }
    }

    private static final class FakeInviteCodeConsumer extends InviteCodeConsumer {
        private final CallLog log;
        FakeInviteCodeConsumer(CallLog log) { this.log = log; }
        @Override
        public Outcome consume(String adapter, String contactId, String body) {
            log.calls.add("inviteCodeConsumer.consume");
            return new Rejected();
        }
    }

    private static final class FakeBanCheck extends BanCheck {
        private final CallLog log;
        private final boolean banned;
        FakeBanCheck(CallLog log, boolean banned) { this.log = log; this.banned = banned; }
        @Override
        public boolean isBanned(String adapter, String contactId) {
            log.calls.add("banCheck.isBanned");
            return banned;
        }
    }

    private static final class RecordingAutoRegisterService extends AutoRegisterService {
        private final CallLog log;
        RecordingAutoRegisterService(CallLog log) { this.log = log; }
        @Override
        public UUID resolveOrRegisterGroup(Identity sender, String adapterName) {
            log.calls.add("autoRegisterService.resolveOrRegisterGroup");
            return UUID.randomUUID();
        }
        @Override
        public UUID resolveOrRegister(Identity sender, String adapterName) {
            log.calls.add("autoRegisterService.resolveOrRegister");
            return UUID.randomUUID();
        }
    }

    private static final class FakeBundleLoader extends BundleLoader {
        private final CallLog log;
        FakeBundleLoader(CallLog log) { this.log = log; }
        @Override
        public String get(String key) {
            log.calls.add("bundleLoader.get(" + key + ")");
            return stubFor(key);
        }
        static String stubFor(String key) { return "bundle:" + key; }
    }

    private static final class NoopConfirmStateService extends ConfirmStateService {
        @Override
        public Optional<ConfirmStateService.PendingConfirm> peek(UUID actor, ScopeRef scope) {
            return Optional.empty();
        }
        @Override
        public Optional<ConfirmStateService.PendingConfirm> takeAny(UUID actor, ScopeRef scope) {
            return Optional.empty();
        }
        @Override
        public Optional<ConfirmStateService.PendingConfirm> takeMatching(UUID actor, ScopeRef scope, String commandName) {
            return Optional.empty();
        }
        @Override
        public void remember(UUID actor, ScopeRef scope, ConfirmStateService.PendingConfirm pending) {
            // no-op
        }
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

    private static final class RecordingCommandHandler implements CommandHandler {
        private final CallLog log;
        private final String name;
        RecordingCommandHandler(CallLog log, String name) {
            this.log = log;
            this.name = name;
        }
        @Override
        public String name() { return name; }
        @Override
        public OutboundMessage handle(ScopeRef scope, String rawText) {
            log.calls.add("handler.handle(" + name + ")");
            return new OutboundMessage(
                    scope, "handler-reply:" + name, Instant.now(), UUID.randomUUID().toString());
        }
    }

    private static final class CapturingAdapter implements MessagingAdapter {
        final List<OutboundMessage> captured = new ArrayList<>();
        @Override public String name() { return "capturing"; }
        @Override public CapabilityFlags capabilities() { throw new UnsupportedOperationException(); }
        @Override public app.zcat.infochat.messaging.AdapterTrustLevel trustLevel() { throw new UnsupportedOperationException(); }
        @Override public Identity assertIdentity(InboundMessage msg) { throw new UnsupportedOperationException(); }
        @Override public MessageHandle send(OutboundMessage msg) { captured.add(msg); return null; }
        @Override public void update(MessageHandle handle, String body) { throw new UnsupportedOperationException(); }
        @Override public void finalize(MessageHandle handle, String body) { throw new UnsupportedOperationException(); }
        @Override public void setTyping(ScopeRef scope, boolean typing) { throw new UnsupportedOperationException(); }
        @Override public void setInboundHandler(InboundHandler handler) { throw new UnsupportedOperationException(); }
    }

    private static final class SingletonInstance<T> implements Instance<T> {
        private final List<T> items;
        @SafeVarargs
        SingletonInstance(T... items) { this.items = List.of(items); }
        @Override public Iterator<T> iterator() { return items.iterator(); }
        @Override public T get() { throw new UnsupportedOperationException(); }
        @Override public Instance<T> select(Annotation... qualifiers) { throw new UnsupportedOperationException(); }
        @Override public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
        @Override public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
        @Override public boolean isUnsatisfied() { return items.isEmpty(); }
        @Override public boolean isAmbiguous() { return items.size() > 1; }
        @Override public void destroy(T instance) { /* no-op */ }
        @Override public Handle<T> getHandle() { throw new UnsupportedOperationException(); }
        @Override public Iterable<? extends Handle<T>> handles() { throw new UnsupportedOperationException(); }
    }
}
