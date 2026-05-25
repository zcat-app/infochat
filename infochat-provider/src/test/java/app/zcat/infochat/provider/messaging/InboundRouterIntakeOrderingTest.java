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
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import app.zcat.infochat.provider.command.ConfirmStateService;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin the intake-step order of {@link InboundRouter#onMessage}
 * against the spec at {@code docs/spec/security.md} §Authorization
 * model. Nine scenarios cover the runnable shape of the splice
 * (originally M1-044b acceptance item 12; updated by M1-044e for the
 * rate-cap-first and DM-gate-pre-dispatch reorderings):
 *
 * <ol>
 *   <li>(a) {@code overSizeCapDropsAfterRateCapPasses} — body exceeds
 *       the size cap and rate-cap passes; rateCapBucket fires FIRST,
 *       then the size-cap branch emits MESSAGE_TOO_LARGE_REPLY. No
 *       other collaborator consulted.</li>
 *   <li>(a2) {@code oversizedBodyDropsAfterOverRateCap} — body exceeds
 *       the size cap AND rate-cap rejects; the silent drop dominates,
 *       no outbound is emitted (closes DOS amplification surface).</li>
 *   <li>(b) {@code overRateCap} — bucket returns {@code false};
 *       dispatch returns silently with no outbound and no further
 *       collaborator consulted.</li>
 *   <li>(c) {@code emptyBodyAfterNormalize} — body normalizes to
 *       empty; dispatch returns with no further collaborator
 *       consulted.</li>
 *   <li>(d) {@code unknownContactValidInvite} — DM, unknown contact,
 *       UUID-shaped body; flow runs rateCap → normalize → inviteCodeConsumer
 *       (Accepted) → welcome reply. Step 4 ban check NOT consulted;
 *       {@code handleSlash} NOT called.</li>
 *   <li>(e) {@code unknownContactInvalidInvite} — DM, unknown contact,
 *       non-UUID body; flow runs rateCap → normalize → inviteCodeConsumer
 *       (Rejected) → invite-required reply. The consumer (not the
 *       router) owns the UUID-parse. Step 4 ban check NOT consulted.</li>
 *   <li>(f) {@code knownBannedDmStops} — DM, known is_banned=true
 *       contact; flow runs rateCap → normalize → users lookup
 *       → banCheck (true) → ban-fixed reply. No {@code handleSlash}.</li>
 *   <li>(g) {@code groupOnlyDmGateShortCircuitsBeforeDispatch} — DM,
 *       known registration_state='group_only' contact, body
 *       {@code /help}; flow runs rateCap → normalize → banCheck
 *       (false) → DM-gate short-circuit → invite-required reply.
 *       {@code handleSlash} MUST NOT run (closes AUTH-BYPASS surface).</li>
 *   <li>(h) {@code groupMentionAutoRegisters} — Group, unknown
 *       contact; flow runs rateCap → normalize → autoRegisterService
 *       → banCheck → handleSlash → dispatch reply.</li>
 * </ol>
 *
 * <p>The test is plain JUnit (no {@code @QuarkusTest}) — every
 * database-touching collaborator is hand-rolled, and the
 * {@link InboundRouter#lookupUser} method is overridden via an
 * anonymous subclass so no {@link javax.sql.DataSource} fake is
 * required. Collaborator invocation order is recorded into a shared
 * {@link CallLog} list and asserted with {@link List#equals(Object)}
 * — equivalent to Mockito's {@code InOrder} verifier without the
 * extra dependency.</p>
 */
class InboundRouterIntakeOrderingTest {

    private static final String ADAPTER = "inmemory";
    private static final String DM_CONTACT = "alice-contact-1234567890abcdef";
    private static final String GROUP_CONTACT = "bob-contact-1234567890abcdef";
    private static final String GROUP_ID = "group-xyz-12345";

    // ----- (a) DM body exceeds size cap → rate-cap first, then size-cap ----

    @Test
    void overSizeCapDropsAfterRateCapPassesNoOtherCollaborator() {
        CallLog log = new CallLog();
        InboundRouter router = newRouterWithLog(log, Optional.empty());
        router.maxInboundBodyBytes = 16;
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        String oversize = "0123456789ABCDEF01234567"; // 24 ASCII bytes, cap=16
        router.onMessage(dmInbound(DM_CONTACT, oversize), ADAPTER);

        // M1-044e: rate-cap runs first; when it passes, the size-cap
        // still fires and emits MESSAGE_TOO_LARGE_REPLY. Users-lookup,
        // inviteCodeConsumer, and banCheck must NOT be consulted.
        assertEquals(1, target.captured.size(),
                "size-cap path must send exactly one too-large reply");
        assertEquals(InboundRouter.MESSAGE_TOO_LARGE_REPLY, target.captured.get(0).text());
        assertEquals(
                List.of("setAdapterName", "rateCapBucket.tryAcquire"),
                log.calls,
                "rate-cap first; no other collaborator consulted on the rate-cap-passes/size-cap-drops path; got: "
                        + log.calls);
    }

    // ----- (a2) rate-cap rejects AND body oversize → silent drop wins ------

    @Test
    void oversizedBodyDropsAfterOverRateCap() {
        CallLog log = new CallLog();
        InboundRouter router = newRouterWithLog(log, Optional.empty());
        router.maxInboundBodyBytes = 16;
        ((CountingRateCapBucket) router.rateCapBucket).next = false;
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        // Oversize body AND rate-cap rejecting: per spec §Authorization
        // model step 1.5, the silent drop dominates — no
        // MESSAGE_TOO_LARGE_REPLY is emitted (closes the DOS amplification
        // surface flagged by /redteam M1-044b).
        String oversize = "0123456789ABCDEF01234567"; // 24 ASCII bytes, cap=16
        router.onMessage(dmInbound(DM_CONTACT, oversize), ADAPTER);

        assertTrue(target.captured.isEmpty(),
                "over-rate-cap path must produce zero outbound even when the body is oversize; got: "
                        + target.captured);
        assertEquals(
                List.of("setAdapterName", "rateCapBucket.tryAcquire"),
                log.calls,
                "only setAdapterName + rateCapBucket consulted; size-cap reply must NOT fire; got: "
                        + log.calls);
    }

    // ----- (b) DM over rate cap → rate-cap consulted, nothing else ---------

    @Test
    void overRateCapDropsSilentlyWithNoOtherCollaboratorConsulted() {
        CallLog log = new CallLog();
        InboundRouter router = newRouterWithLog(log, Optional.empty());
        ((CountingRateCapBucket) router.rateCapBucket).next = false;
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(dmInbound(DM_CONTACT, "/help"), ADAPTER);

        assertTrue(target.captured.isEmpty(),
                "over-rate-cap path must produce zero outbound; got: " + target.captured);
        assertEquals(
                List.of("setAdapterName", "rateCapBucket.tryAcquire"),
                log.calls,
                "only setAdapterName + rateCapBucket consulted on the over-rate-cap path; got: "
                        + log.calls);
    }

    // ----- (c) DM body normalizes to empty → no further work ---------------

    @Test
    void emptyBodyAfterNormalizeDropsBeforeUsersLookupOrInviteConsume() {
        CallLog log = new CallLog();
        InboundRouter router = newRouterWithLog(log, Optional.empty());
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        // The normalize pass returns "" for body "   " (whole-body trim).
        router.onMessage(dmInbound(DM_CONTACT, "   "), ADAPTER);

        assertTrue(target.captured.isEmpty(),
                "normalize-empty path must produce zero outbound; got: " + target.captured);
        assertEquals(
                List.of("setAdapterName", "rateCapBucket.tryAcquire"),
                log.calls,
                "users lookup, inviteCodeConsumer, banCheck must NOT be consulted after "
                        + "normalize returns empty; got: " + log.calls);
    }

    // ----- (d) DM unknown contact + valid invite UUID → welcome ------------

    @Test
    void unknownContactValidInviteAcceptedSendsWelcomeAndStopsBeforeBanCheck() {
        CallLog log = new CallLog();
        InboundRouter router = newRouterWithLog(log, Optional.empty());
        FakeInviteCodeConsumer fakeInvite = (FakeInviteCodeConsumer) router.inviteCodeConsumer;
        fakeInvite.outcome = new InviteCodeConsumer.Accepted(UUID.randomUUID());
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        UUID validCode = UUID.randomUUID();
        router.onMessage(dmInbound(DM_CONTACT, validCode.toString()), ADAPTER);

        assertEquals(1, target.captured.size(),
                "Accepted invite must produce exactly one welcome reply; got: " + target.captured);
        assertEquals(FakeBundleLoader.stubFor(BundleKeys.REPLY_WELCOME_DM_FRESH),
                target.captured.get(0).text(),
                "Accepted welcome body must equal the dm_fresh bundle entry");
        assertEquals(
                List.of(
                        "setAdapterName",
                        "rateCapBucket.tryAcquire",
                        "lookupUser",
                        "inviteCodeConsumer.consume",
                        "bundleLoader.get(reply.welcome.dm_fresh)"),
                log.calls,
                "Accepted-invite path must call exactly these collaborators in order — banCheck and "
                        + "handleSlash NOT consulted; got: " + log.calls);
    }

    // ----- (e) DM unknown contact + invalid invite body → invite-required --

    @Test
    void unknownContactInvalidInviteRejectsWithFixedReplyAndStopsBeforeBanCheck() {
        CallLog log = new CallLog();
        InboundRouter router = newRouterWithLog(log, Optional.empty());
        FakeInviteCodeConsumer fakeInvite = (FakeInviteCodeConsumer) router.inviteCodeConsumer;
        fakeInvite.outcome = new InviteCodeConsumer.Rejected();
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        // M1-044e: the router no longer pre-parses the body as a UUID.
        // A non-UUID String reaches inviteCodeConsumer.consume just like
        // a UUID-shaped one; the FakeInviteCodeConsumer returns the
        // canned Rejected outcome regardless of body shape.
        router.onMessage(dmInbound(DM_CONTACT, "not-a-uuid-body"), ADAPTER);

        assertEquals(1, target.captured.size(),
                "Rejected invite must produce exactly one invite-required reply; got: " + target.captured);
        assertEquals(FakeBundleLoader.stubFor(BundleKeys.ERROR_INVITE_REQUIRED),
                target.captured.get(0).text(),
                "Rejected reply body must equal the error.invite.required bundle entry");
        assertEquals(
                List.of(
                        "setAdapterName",
                        "rateCapBucket.tryAcquire",
                        "lookupUser",
                        "inviteCodeConsumer.consume",
                        "bundleLoader.get(error.invite.required)"),
                log.calls,
                "Rejected-invite path must call exactly these collaborators in order — banCheck NOT "
                        + "consulted; got: " + log.calls);
    }

    // ----- (f) DM known is_banned=true → ban-fixed reply -------------------

    @Test
    void knownBannedDmStopsWithFixedReplyAndNoHandleSlash() {
        CallLog log = new CallLog();
        InboundRouter router = newRouterWithLog(log,
                Optional.of(new InboundRouter.UserSnapshot(UUID.randomUUID(), false, "vouched")));
        // BanCheck returns true regardless of the snapshot.is_banned column —
        // step 4 consults the live SQL per spec.
        ((FakeBanCheck) router.banCheck).banned = true;
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(dmInbound(DM_CONTACT, "/help"), ADAPTER);

        assertEquals(1, target.captured.size(),
                "banned-user path must produce exactly one ban-fixed reply; got: " + target.captured);
        assertEquals(FakeBundleLoader.stubFor(BundleKeys.ERROR_BAN_FIXED),
                target.captured.get(0).text(),
                "ban reply body must equal the error.ban.fixed bundle entry");
        assertEquals(
                List.of(
                        "setAdapterName",
                        "rateCapBucket.tryAcquire",
                        "lookupUser",
                        "banCheck.isBanned",
                        "bundleLoader.get(error.ban.fixed)"),
                log.calls,
                "banned path must call exactly these collaborators in order — inviteCodeConsumer and "
                        + "handleSlash NOT consulted; got: " + log.calls);
    }

    // ----- (g) DM from known group_only contact + /help → DM-gate fires -----

    @Test
    void groupOnlyDmGateShortCircuitsBeforeDispatch() {
        CallLog log = new CallLog();
        InboundRouter router = newRouterWithLog(log,
                Optional.of(new InboundRouter.UserSnapshot(UUID.randomUUID(), false, "group_only")));
        // RecordingCommandHandler is wired but MUST NOT be invoked — the
        // M1-044e DM-gate fires BEFORE dispatch.
        router.commandHandlers = new SingletonInstance<>(new RecordingCommandHandler(log, "help"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(dmInbound(DM_CONTACT, "/help"), ADAPTER);

        assertEquals(1, target.captured.size(),
                "group_only DM path must produce exactly one reply; got: " + target.captured);
        assertEquals(FakeBundleLoader.stubFor(BundleKeys.ERROR_INVITE_REQUIRED),
                target.captured.get(0).text(),
                "the pre-dispatch DM-gate emits the error.invite.required bundle entry");
        assertEquals(
                List.of(
                        "setAdapterName",
                        "rateCapBucket.tryAcquire",
                        "lookupUser",
                        "banCheck.isBanned",
                        "bundleLoader.get(error.invite.required)"),
                log.calls,
                "DM-gate path must short-circuit BEFORE dispatch — handler.handle MUST NOT appear; got: "
                        + log.calls);
    }

    // ----- (h) Group @mention from unknown contact → auto-register + dispatch

    @Test
    void groupMentionAutoRegistersAndDispatchesNormally() {
        CallLog log = new CallLog();
        InboundRouter router = newRouterWithLog(log, Optional.empty());
        router.commandHandlers = new SingletonInstance<>(new RecordingCommandHandler(log, "help"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(groupInbound(GROUP_ID, GROUP_CONTACT, "/help"), ADAPTER);

        assertEquals(1, target.captured.size(),
                "group dispatch must produce exactly one outbound; got: " + target.captured);
        assertEquals("handler-reply:help", target.captured.get(0).text(),
                "the dispatch reply is the RecordingCommandHandler's output, not the DM-gate literal");
        assertEquals(
                List.of(
                        "setAdapterName",
                        "rateCapBucket.tryAcquire",
                        "lookupUser",
                        "autoRegisterService.resolveOrRegisterGroup",
                        "lookupUser",
                        "banCheck.isBanned",
                        "handler.handle(help)"),
                log.calls,
                "group-mention path must call exactly these collaborators in order — the second "
                        + "lookupUser is the M1-045 redteam-fix re-fetch after auto-register inserts; got: "
                        + log.calls);
    }

    // ----- helpers + fakes ------------------------------------------------

    /**
     * Build a router with all M1-044b collaborators replaced by
     * recording fakes. {@code snapshot} controls the
     * {@link InboundRouter#lookupUser} override's INITIAL return —
     * empty means "DM unknown contact" / "group unknown contact",
     * non-empty means "user known with this
     * {@link InboundRouter.UserSnapshot} state."
     *
     * <p><b>M1-045 redteam-fix re-fetch.</b> Fix 1 added a re-fetch
     * of {@code lookupUser} after step 3's
     * {@code autoRegisterService.resolveOrRegisterGroup} insert.
     * The override is therefore stateful: the FIRST call returns
     * the {@code snapshot} the test seeded; subsequent calls
     * synthesize a present snapshot from V5 auto-register defaults
     * ({@code is_banned=false}, {@code registration_state='group_only'})
     * so the now-guard-less {@code snapshot.get().id()} at step 5 does
     * not NPE. Scenario (h)
     * {@code groupMentionAutoRegistersAndDispatchesNormally} relies
     * on the synthesized re-fetch result; every other scenario in
     * this file uses the initial snapshot only (auto-register does
     * not fire on DM scope or when the initial snapshot is present).
     */
    private InboundRouter newRouterWithLog(CallLog log, Optional<InboundRouter.UserSnapshot> snapshot) {
        java.util.concurrent.atomic.AtomicInteger lookupCallCount =
                new java.util.concurrent.atomic.AtomicInteger();
        InboundRouter router = new InboundRouter() {
            @Override
            Optional<UserSnapshot> lookupUser(String adapter, String contactId) {
                log.calls.add("lookupUser");
                if (lookupCallCount.incrementAndGet() == 1) {
                    return snapshot;
                }
                return Optional.of(new UserSnapshot(
                        UUID.randomUUID(), false, "group_only"));
            }
        };
        router.commandHandlers = new SingletonInstance<>();
        router.autoRegisterService = new RecordingAutoRegisterService(log);
        router.inboundContext = new RecordingInboundContext(log);
        router.rateCapBucket = new CountingRateCapBucket(log);
        router.inviteCodeConsumer = new FakeInviteCodeConsumer(log);
        router.banCheck = new FakeBanCheck(log);
        router.bundleLoader = new FakeBundleLoader(log);
        // M1-051: step 4.5 confirm-cancel sweep peek call would NPE on
        // a null @Inject field. The Noop returns Optional.empty() AND
        // — critically — does NOT log into the CallLog. The per-step
        // call-order assertions of scenarios (g) and (h)
        // (groupOnlyDmGateShortCircuitsBeforeDispatch + groupMention
        // AutoRegistersAndDispatchesNormally) pin precise sequences
        // that must remain unchanged: an extra "confirmStateService.peek"
        // log entry would break those assertions, so this Noop is
        // deliberately log-silent.
        router.confirmStateService = new NoopConfirmStateService();
        // M1-045: step 5 probation gate would NPE on null @Inject
        // fields. The two Noop stand-ins live as top-level classes
        // in this same package — NoopProbationCheck + NoopCommand
        // Permissions — and are deliberately log-silent. See their
        // class-level javadoc for the rationale (same as
        // NoopConfirmStateService above).
        router.commandPermissions = new NoopCommandPermissions();
        router.probationCheck = new NoopProbationCheck();
        router.summaryAnchorRepository = new SummaryAnchorRepository() {
            @Override public void clear(UUID userId, UUID scopeId) {}
        };
        router.maxInboundBodyBytes = 65536;
        return router;
    }

    private static InboundMessage dmInbound(String contactId, String body) {
        return new InboundMessage(
                new Identity(contactId, "Alice", Instant.now()),
                new ScopeRef.Dm(contactId),
                body,
                Instant.now(),
                "msg-1");
    }

    private static InboundMessage groupInbound(String groupId, String contactId, String body) {
        return new InboundMessage(
                new Identity(contactId, "Bob", Instant.now()),
                new ScopeRef.Group(groupId),
                body,
                Instant.now(),
                "msg-1");
    }

    /** Ordered append-only log of collaborator method invocations. */
    private static final class CallLog {
        final List<String> calls = new ArrayList<>();
    }

    /**
     * Records {@code setAdapterName} into the {@link CallLog}; the
     * recorded entry is the spec's "identity" gate (step 1 — adapter
     * name set BEFORE any size-cap / rate-cap / normalize work).
     */
    private static final class RecordingInboundContext extends InboundContext {
        private final CallLog log;

        RecordingInboundContext(CallLog log) {
            this.log = log;
        }

        @Override
        public void setAdapterName(String adapterName) {
            log.calls.add("setAdapterName");
            super.setAdapterName(adapterName);
        }
    }

    /**
     * Records {@code rateCapBucket.tryAcquire}; default returns
     * {@code true} (under-cap). The {@link #next} field flips the
     * return value for the over-cap scenario without changing the
     * recorded call name.
     */
    private static final class CountingRateCapBucket extends RateCapBucket {
        private final CallLog log;
        boolean next = true;

        CountingRateCapBucket(CallLog log) {
            this.log = log;
        }

        @Override
        public boolean tryAcquire(String adapter, String contactId) {
            log.calls.add("rateCapBucket.tryAcquire");
            return next;
        }
    }

    /** Records {@code inviteCodeConsumer.consume}; returns the canned outcome. */
    private static final class FakeInviteCodeConsumer extends InviteCodeConsumer {
        private final CallLog log;
        Outcome outcome = new Rejected();

        FakeInviteCodeConsumer(CallLog log) {
            this.log = log;
        }

        @Override
        public Outcome consume(String adapter, String contactId, String body) {
            log.calls.add("inviteCodeConsumer.consume");
            return outcome;
        }
    }

    /** Records {@code banCheck.isBanned}; returns the {@link #banned} flag. */
    private static final class FakeBanCheck extends BanCheck {
        private final CallLog log;
        boolean banned = false;

        FakeBanCheck(CallLog log) {
            this.log = log;
        }

        @Override
        public boolean isBanned(String adapter, String contactId) {
            log.calls.add("banCheck.isBanned");
            return banned;
        }
    }

    /**
     * Records {@code autoRegisterService.resolveOrRegisterGroup};
     * returns a fresh UUID without touching a database. The
     * deprecated {@code resolveOrRegister} is overridden too because
     * the parent's body still calls the SQL upsert.
     */
    private static final class RecordingAutoRegisterService extends AutoRegisterService {
        private final CallLog log;

        RecordingAutoRegisterService(CallLog log) {
            this.log = log;
        }

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

    /**
     * Records each {@code bundleLoader.get(key)} call and returns a
     * stub string keyed on the bundle key (so each test can assert
     * the precise reply body without depending on en.properties).
     */
    private static final class FakeBundleLoader extends BundleLoader {
        private final CallLog log;

        FakeBundleLoader(CallLog log) {
            this.log = log;
        }

        @Override
        public String get(String key) {
            log.calls.add("bundleLoader.get(" + key + ")");
            return stubFor(key);
        }

        static String stubFor(String key) {
            return "bundle:" + key;
        }
    }

    /**
     * Log-silent no-op {@link ConfirmStateService} (M1-051). All
     * accessor methods return {@code Optional.empty()} / no-op
     * WITHOUT logging into {@link CallLog} — the existing per-step
     * call-order assertions in scenarios (g) and (h) pin precise
     * sequences that an extra log entry would break.
     */
    private static final class NoopConfirmStateService extends ConfirmStateService {
        @Override
        public Optional<ConfirmStateService.PendingConfirm> peek(java.util.UUID actor, ScopeRef scope) {
            return Optional.empty();
        }
        @Override
        public Optional<ConfirmStateService.PendingConfirm> takeAny(java.util.UUID actor, ScopeRef scope) {
            return Optional.empty();
        }
        @Override
        public Optional<ConfirmStateService.PendingConfirm> takeMatching(java.util.UUID actor, ScopeRef scope, String commandName) {
            return Optional.empty();
        }
        @Override
        public void remember(java.util.UUID actor, ScopeRef scope, ConfirmStateService.PendingConfirm pending) {
            // no-op
        }
    }

    /**
     * Test {@link CommandHandler} that records its dispatch and
     * returns a deterministic body keyed on its name.
     */
    private static final class RecordingCommandHandler implements CommandHandler {
        private final CallLog log;
        private final String name;

        RecordingCommandHandler(CallLog log, String name) {
            this.log = log;
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public OutboundMessage handle(ScopeRef scope, String rawText) {
            log.calls.add("handler.handle(" + name + ")");
            return new OutboundMessage(
                    scope,
                    "handler-reply:" + name,
                    Instant.now(),
                    UUID.randomUUID().toString());
        }
    }

    /** Captures outbound messages the router sends. */
    private static final class CapturingAdapter implements MessagingAdapter {
        final List<OutboundMessage> captured = new ArrayList<>();

        @Override
        public String name() {
            return "capturing";
        }

        @Override
        public CapabilityFlags capabilities() {
            throw new UnsupportedOperationException();
        }

        @Override
        public app.zcat.infochat.messaging.AdapterTrustLevel trustLevel() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Identity assertIdentity(InboundMessage msg) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MessageHandle send(OutboundMessage msg) {
            captured.add(msg);
            return null;
        }

        @Override
        public void update(MessageHandle handle, String body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void finalize(MessageHandle handle, String body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setTyping(ScopeRef scope, boolean typing) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setInboundHandler(InboundHandler handler) {
            throw new UnsupportedOperationException();
        }
    }

    /** Minimal {@link Instance} backed by a fixed list. */
    private static final class SingletonInstance<T> implements Instance<T> {
        private final List<T> items;

        @SafeVarargs
        SingletonInstance(T... items) {
            this.items = List.of(items);
        }

        @Override
        public Iterator<T> iterator() {
            return items.iterator();
        }

        @Override
        public T get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return items.isEmpty();
        }

        @Override
        public boolean isAmbiguous() {
            return items.size() > 1;
        }

        @Override
        public void destroy(T instance) {
            // no-op
        }

        @Override
        public Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }
    }
}
