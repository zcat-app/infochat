package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.command.AssetCommandFamilyOracle;
import app.zcat.infochat.provider.command.BanConfirm;
import app.zcat.infochat.provider.command.CommandPermissions;
import app.zcat.infochat.provider.command.ConfirmStateService;
import app.zcat.infochat.provider.command.ForgetConfirm;
import app.zcat.infochat.provider.command.QuarantineRejectConfirm;
import app.zcat.infochat.provider.command.RejectGroupCommandHandler;
import app.zcat.infochat.provider.command.asset.AssetRegistry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit (no Quarkus boot) router-level coverage of the M1-051
 * step 4.5 confirm-cancel sweep. Constructs {@link InboundRouter} by
 * hand with recording fakes — mirrors the pattern from
 * {@link InboundRouterIntakeOrderingTest} but focuses specifically on
 * the sweep's cancel-vs-leave-alone decision tree.
 *
 * <p>Three scenarios pin acceptance item 22 of M1-051:</p>
 * <ul>
 *   <li>(a) non-matching input: peek → non-empty, body NOT confirm-shape,
 *       takeAny + cancellation reply sent BEFORE dispatch;</li>
 *   <li>(b) matching confirm input: peek → non-empty, body IS confirm-shape,
 *       sweep does NOT takeAny, no cancellation reply, dispatch proceeds;</li>
 *   <li>(c) empty pending: peek → empty, no-op path; no takeAny, no
 *       cancellation, dispatch proceeds.</li>
 * </ul>
 *
 * <p>M1-775 adds the argument half of that decision tree: a confirm leg
 * that retypes the command's identifying argument redeems the pending
 * action only when the argument names it, and a leg naming something
 * else is treated as any other input.</p>
 *
 * <p>Two further scenarios pin the M1-772 single-line rule's own drain,
 * which fires ahead of the sweep because the rejection returns before
 * it: (d) a multi-line body drains unconditionally — even one shaped
 * like a confirm — and reaches no handler; (e) with nothing pending the
 * rejection stays a single reply.</p>
 */
class InboundRouterConfirmCancelTest {

    private static final String ADAPTER = "inmemory";
    private static final String DM_CONTACT = "alice-dm-contact-id";
    private static final UUID ACTOR_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    /**
     * Absolute instants, not {@code now()}-relative offsets: the
     * probation gate's decision is pinned to {@link #FIXED_NOW} via an
     * injected {@code Clock.fixed}, so these fixtures cannot rot into a
     * date-boundary flake.
     */
    private static final Instant FIXED_NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final Instant PROBATION_UNTIL = Instant.parse("2026-08-06T13:00:00Z");

    @Test
    void nonMatchingInputCancelsPendingAndSendsAcknowledgement() {
        // Pending /ban exists; user sends /help (any other input) →
        // sweep takeAny + cancellation reply sent BEFORE dispatch.
        FakeConfirmStateService confirmState = new FakeConfirmStateService(
                Optional.of(new BanConfirm("target-1", null, "intent-req")));
        CapturingAdapter capture = new CapturingAdapter();
        InboundRouter router = newRouter(confirmState, capture);

        router.onMessage(dmInbound(DM_CONTACT, "/help"), ADAPTER);

        // Two outbounds in order: cancellation, then the /help dispatch
        // (unknown-command reply since the test wires no /help handler).
        assertEquals(2, capture.captured.size(),
                "expected one cancellation + one dispatch outbound; got: " + capture.captured);
        assertEquals("Pending `ban` cancelled.", capture.captured.get(0).text(),
                "first outbound must be the cancellation acknowledgement BEFORE dispatch");
        assertEquals("bundle:" + BundleKeys.ERROR_UNKNOWN_COMMAND, capture.captured.get(1).text(),
                "second outbound must be the /help dispatch (unknown-command bundle reply)");

        // ConfirmStateService call sequence: peek → takeAny (sweep
        // drained the pending entry before dispatch).
        assertTrue(confirmState.calls.contains("peek"),
                "sweep must call peek to detect the pending entry");
        assertTrue(confirmState.calls.contains("takeAny"),
                "sweep must call takeAny on a non-matching input to drain the pending entry");
    }

    @Test
    void matchingConfirmInputDoesNotCancelAndProceedsToDispatch() {
        // Pending /ban exists; user sends "/ban confirm" (matching
        // confirm-shape) → sweep does NOT takeAny, no cancellation
        // reply, dispatch proceeds.
        FakeConfirmStateService confirmState = new FakeConfirmStateService(
                Optional.of(new BanConfirm("target-2", "spam", "intent-req")));
        CapturingAdapter capture = new CapturingAdapter();
        CountingCommandHandler banHandler = new CountingCommandHandler("ban", "ban-dispatched");
        InboundRouter router = newRouter(confirmState, capture);
        router.commandHandlers = new SingletonInstance<>(banHandler);

        router.onMessage(dmInbound(DM_CONTACT, "/ban confirm"), ADAPTER);

        // Single outbound — the dispatch reply only. NO cancellation
        // reply because the sweep recognized the confirm-shape and
        // left the pending entry alone for the handler.
        assertEquals(1, capture.captured.size(),
                "expected exactly one outbound (dispatch only); got: " + capture.captured);
        assertEquals("ban-dispatched", capture.captured.get(0).text(),
                "outbound must be the /ban handler's reply, not a cancellation acknowledgement");
        assertEquals(1, banHandler.dispatchCount,
                "/ban handler must have dispatched exactly once");

        // ConfirmStateService call sequence: peek only (no takeAny).
        assertTrue(confirmState.calls.contains("peek"),
                "sweep must call peek even on matching confirm input");
        assertFalse(confirmState.calls.contains("takeAny"),
                "sweep must NOT call takeAny on a matching confirm-shape body — "
                        + "the handler's takeMatching is the authoritative pop");
    }

    @Test
    void emptyPendingTakesNoOpPath() {
        // No pending state; user sends /help → sweep peek returns
        // empty → no-op (no takeAny, no cancellation), dispatch
        // proceeds with the existing reply.
        FakeConfirmStateService confirmState = new FakeConfirmStateService(Optional.empty());
        CapturingAdapter capture = new CapturingAdapter();
        InboundRouter router = newRouter(confirmState, capture);

        router.onMessage(dmInbound(DM_CONTACT, "/help"), ADAPTER);

        // Single outbound — the /help dispatch only (unknown-command reply).
        assertEquals(1, capture.captured.size(),
                "expected exactly one outbound (dispatch only); got: " + capture.captured);
        assertEquals("bundle:" + BundleKeys.ERROR_UNKNOWN_COMMAND, capture.captured.get(0).text(),
                "outbound must be the /help dispatch reply, not a cancellation");

        // ConfirmStateService call sequence: peek only.
        assertTrue(confirmState.calls.contains("peek"),
                "sweep must call peek even on empty pending (to determine emptiness)");
        assertFalse(confirmState.calls.contains("takeAny"),
                "sweep must NOT call takeAny when peek returns empty (no-op path)");
    }

    @Test
    void multiLineSlashBodyCancelsPendingEvenWhenItLooksLikeAConfirm() {
        // M1-772 redteam finding 1: the single-line rejection returns
        // ahead of the step-4.5 sweep, so it drains the pending itself.
        // The body is deliberately one isConfirmShape would ACCEPT if it
        // were on a single line — it starts with "/ban " and ends with
        // " confirm" — proving the drain is unconditional rather than a
        // copy of the sweep's predicate. A rejected body dispatches
        // nothing, so it can never redeem the armed payload.
        FakeConfirmStateService confirmState = new FakeConfirmStateService(
                Optional.of(new BanConfirm("target-3", null, "intent-req")));
        CapturingAdapter capture = new CapturingAdapter();
        CountingCommandHandler banHandler = new CountingCommandHandler("ban", "ban-dispatched");
        InboundRouter router = newRouter(confirmState, capture);
        router.commandHandlers = new SingletonInstance<>(banHandler);

        router.onMessage(dmInbound(DM_CONTACT, "/ban target-3\nnote to self confirm"), ADAPTER);

        assertEquals(0, banHandler.dispatchCount,
                "a multi-line body must reach NO handler");
        assertEquals(2, capture.captured.size(),
                "expected one cancellation + one multiline error; got: " + capture.captured);
        assertEquals("Pending `ban` cancelled.", capture.captured.get(0).text(),
                "first outbound must be the cancellation acknowledgement");
        assertEquals("bundle:" + BundleKeys.ERROR_COMMAND_MULTILINE, capture.captured.get(1).text(),
                "second outbound must be the multiline rejection");
        assertTrue(confirmState.calls.contains("takeAny"),
                "the rejection must drain the pending confirm, not leave it armed");
    }

    @Test
    void multiLineSlashBodyWithNoPendingConfirmRepliesOnlyOnce() {
        // The drain is a no-op in the common case: no pending entry
        // means the rejection stays a single reply.
        FakeConfirmStateService confirmState = new FakeConfirmStateService(Optional.empty());
        CapturingAdapter capture = new CapturingAdapter();
        InboundRouter router = newRouter(confirmState, capture);

        router.onMessage(dmInbound(DM_CONTACT, "/help\nnote to self"), ADAPTER);

        assertEquals(1, capture.captured.size(),
                "expected exactly one outbound (the multiline error); got: " + capture.captured);
        assertEquals("bundle:" + BundleKeys.ERROR_COMMAND_MULTILINE, capture.captured.get(0).text(),
                "outbound must be the multiline rejection, not a cancellation");
    }

    @Test
    void overCapChatBodyCancelsPendingConfirm() {
        // M1-774: the chat-mode body cap returns ahead of the step-4.5
        // sweep, so it drains the pending confirm itself — an admin who
        // armed a destructive confirm and then sends an over-cap chat
        // body gets the cancellation BEFORE the too-large reply, and
        // the entry is not left redeemable for the rest of its TTL.
        FakeConfirmStateService confirmState = new FakeConfirmStateService(
                Optional.of(new BanConfirm("target-4", null, "intent-req")));
        CapturingAdapter capture = new CapturingAdapter();
        InboundRouter router = newRouter(confirmState, capture);
        router.chatBodyCap = 8;

        router.onMessage(dmInbound(DM_CONTACT, "a chat body far beyond the cap"), ADAPTER);

        assertEquals(2, capture.captured.size(),
                "expected one cancellation + one body-too-large reply; got: " + capture.captured);
        assertEquals("Pending `ban` cancelled.", capture.captured.get(0).text(),
                "first outbound must be the cancellation acknowledgement BEFORE the rejection");
        assertEquals("bundle:" + BundleKeys.ERROR_CHAT_BODY_TOO_LARGE, capture.captured.get(1).text(),
                "second outbound must be the chat body-cap rejection");
        assertTrue(confirmState.calls.contains("takeAny"),
                "the body-cap rejection must drain the pending confirm, not leave it armed");
    }

    @Test
    void overCapCommandBodyCancelsPendingConfirmBeforeTheRejection() {
        // M1-774: same drain on the slash-command body cap. The
        // over-cap body dispatches nothing, so it can never redeem the
        // armed payload; the cancellation fires first, the rejection
        // second, and no handler sees the body.
        FakeConfirmStateService confirmState = new FakeConfirmStateService(
                Optional.of(new BanConfirm("target-5", null, "intent-req")));
        CapturingAdapter capture = new CapturingAdapter();
        CountingCommandHandler banHandler = new CountingCommandHandler("ban", "ban-dispatched");
        InboundRouter router = newRouter(confirmState, capture);
        router.commandHandlers = new SingletonInstance<>(banHandler);
        router.commandBodyCap = 12;

        router.onMessage(dmInbound(DM_CONTACT, "/ban target-5 padded-payload"), ADAPTER);

        assertEquals(0, banHandler.dispatchCount,
                "an over-cap command body must reach NO handler");
        assertEquals(2, capture.captured.size(),
                "expected one cancellation + one command body-cap reply; got: " + capture.captured);
        assertEquals("Pending `ban` cancelled.", capture.captured.get(0).text(),
                "first outbound must be the cancellation acknowledgement BEFORE the rejection");
        assertEquals("bundle:" + BundleKeys.ERROR_COMMAND_BODY_TOO_LARGE, capture.captured.get(1).text(),
                "second outbound must be the command body-cap rejection");
        assertTrue(confirmState.calls.contains("takeAny"),
                "the command body-cap rejection must drain the pending confirm");
    }

    @Test
    void overCapBodyWithNoPendingConfirmRepliesOnlyOnce() {
        // The drain is unconditional (takeAny on an empty map is a
        // no-op), so in the common case — no pending entry — the
        // body-cap rejection stays a single reply: no cancellation
        // outbound, only the too-large error.
        FakeConfirmStateService confirmState = new FakeConfirmStateService(Optional.empty());
        CapturingAdapter capture = new CapturingAdapter();
        InboundRouter router = newRouter(confirmState, capture);
        router.chatBodyCap = 8;

        router.onMessage(dmInbound(DM_CONTACT, "a chat body far beyond the cap"), ADAPTER);

        assertEquals(1, capture.captured.size(),
                "expected exactly one outbound (the body-cap rejection); got: " + capture.captured);
        assertEquals("bundle:" + BundleKeys.ERROR_CHAT_BODY_TOO_LARGE, capture.captured.get(0).text(),
                "outbound must be the body-cap rejection, not a cancellation");
    }

    @Test
    void probationBlockedCommandCancelsPendingConfirm() {
        // M1-774: the step-5 probation block returns ahead of the
        // step-4.5 sweep, and it is NOT a vacuous path — /forget is
        // both confirm-arming and probation-allowed, so a probation
        // user really can be holding an entry when a blocked input
        // arrives. Without this drain the entry survives the blocked
        // input with no acknowledgement and a later `/forget confirm`
        // (itself probation-allowed) still redeems it.
        FakeConfirmStateService confirmState = new FakeConfirmStateService(
                Optional.of(new ForgetConfirm()));
        CapturingAdapter capture = new CapturingAdapter();
        CountingCommandHandler blockedHandler =
                new CountingCommandHandler("add-source", "add-source-dispatched");
        InboundRouter router = newRouter(confirmState, capture, PROBATION_UNTIL, false);
        router.commandHandlers = new SingletonInstance<>(blockedHandler);

        router.onMessage(dmInbound(DM_CONTACT, "/add-source https://example.org/feed.xml"), ADAPTER);

        assertEquals(0, blockedHandler.dispatchCount,
                "a probation-blocked command must reach NO handler");
        assertEquals(2, capture.captured.size(),
                "expected one cancellation + one probation-blocked reply; got: " + capture.captured);
        assertEquals("Pending `forget` cancelled.", capture.captured.get(0).text(),
                "first outbound must be the cancellation acknowledgement BEFORE the rejection");
        assertEquals("bundle:" + BundleKeys.ERROR_PROBATION_BLOCKED, capture.captured.get(1).text(),
                "second outbound must be the probation-blocked rejection");
        assertTrue(confirmState.calls.contains("takeAny"),
                "the probation block must drain the pending confirm, not leave it armed");
    }

    @Test
    void probationAllowedCommandLeavesTheSweepToCancel() {
        // The complement: a probation user invoking an ALLOWED command
        // falls through step 5 to the step-4.5 sweep, which cancels
        // exactly once. Pins that the new drain did not double-cancel
        // the fall-through path.
        FakeConfirmStateService confirmState = new FakeConfirmStateService(
                Optional.of(new ForgetConfirm()));
        CapturingAdapter capture = new CapturingAdapter();
        InboundRouter router = newRouter(confirmState, capture, PROBATION_UNTIL, true);

        router.onMessage(dmInbound(DM_CONTACT, "/help"), ADAPTER);

        // Same two outbounds the non-probation sweep scenario produces:
        // cancellation, then the /help dispatch (unknown-command reply,
        // no /help handler wired). One cancellation, not two.
        assertEquals(2, capture.captured.size(),
                "expected one cancellation + one dispatch outbound; got: " + capture.captured);
        assertEquals("Pending `forget` cancelled.", capture.captured.get(0).text(),
                "the sweep, not the probation block, owns this cancellation");
        assertEquals("bundle:" + BundleKeys.ERROR_UNKNOWN_COMMAND, capture.captured.get(1).text(),
                "second outbound must be the /help dispatch, not a duplicate cancellation");
    }

    @Test
    void confirmLegNamingAnotherTargetCancelsAndRedeemsNothing() {
        // M1-775's repro: `/ban bob confirm` against a pending
        // BanConfirm("alice") used to satisfy the sweep's prefix+suffix
        // match, so the entry survived and the handler popped the STORED
        // payload — alice was banned by a message that named bob. The
        // mismatched leg is now "any other input": drained, acknowledged,
        // and nothing redeemed. The handler still dispatches (the drain
        // does not swallow the body), but its takeMatching comes back
        // empty, so it executes neither target.
        FakeConfirmStateService confirmState = new FakeConfirmStateService(
                Optional.of(new BanConfirm("alice-contact", null, "intent-req")));
        CapturingAdapter capture = new CapturingAdapter();
        ConfirmPoppingCommandHandler banHandler =
                new ConfirmPoppingCommandHandler("ban", "ban", confirmState);
        InboundRouter router = newRouter(confirmState, capture);
        router.commandHandlers = new SingletonInstance<>(banHandler);

        router.onMessage(dmInbound(DM_CONTACT, "/ban bob-contact confirm"), ADAPTER);

        assertTrue(confirmState.calls.contains("takeAny"),
                "a confirm leg naming another target must be drained by the sweep");
        assertEquals("Pending `ban` cancelled.", capture.captured.get(0).text(),
                "first outbound must be the cancellation acknowledgement");
        assertNull(banHandler.popped,
                "the handler must find NO pending payload — neither the stored target "
                        + "nor the retyped one may execute");
    }

    @Test
    void argumentCarryingQuarantineRejectLegStillRedeems() {
        // The legitimate argument-carrying leg (M1-458): the retyped id
        // is the one the prompt armed, so the sweep must leave the entry
        // alone and the handler must pop it.
        UUID quarantineId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        FakeConfirmStateService confirmState = new FakeConfirmStateService(
                Optional.of(new QuarantineRejectConfirm(quarantineId)));
        CapturingAdapter capture = new CapturingAdapter();
        ConfirmPoppingCommandHandler quarantineHandler =
                new ConfirmPoppingCommandHandler("quarantine", "quarantine-reject", confirmState);
        InboundRouter router = newRouter(confirmState, capture);
        router.commandHandlers = new SingletonInstance<>(quarantineHandler);

        router.onMessage(dmInbound(DM_CONTACT, "/quarantine reject " + quarantineId + " confirm"), ADAPTER);

        assertFalse(confirmState.calls.contains("takeAny"),
                "a confirm leg retyping the pending id must NOT be drained by the sweep");
        assertEquals(1, capture.captured.size(),
                "expected exactly one outbound (dispatch only); got: " + capture.captured);
        assertNotNull(quarantineHandler.popped,
                "the handler's takeMatching must still redeem the pending payload");
    }

    @Test
    void argumentCarryingRejectGroupLegStillRedeems() {
        // The second argument-carrying leg — the prompt the admin is
        // shown instructs `/reject-group <id> confirm` verbatim.
        UUID groupId = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
        FakeConfirmStateService confirmState = new FakeConfirmStateService(
                Optional.of(new RejectGroupCommandHandler.RejectGroupConfirm(groupId)));
        CapturingAdapter capture = new CapturingAdapter();
        ConfirmPoppingCommandHandler rejectGroupHandler =
                new ConfirmPoppingCommandHandler("reject-group", "reject-group", confirmState);
        InboundRouter router = newRouter(confirmState, capture);
        router.commandHandlers = new SingletonInstance<>(rejectGroupHandler);

        router.onMessage(dmInbound(DM_CONTACT, "/reject-group " + groupId + " confirm"), ADAPTER);

        assertFalse(confirmState.calls.contains("takeAny"),
                "a confirm leg retyping the pending group id must NOT be drained by the sweep");
        assertNotNull(rejectGroupHandler.popped,
                "the handler's takeMatching must still redeem the pending payload");
    }

    @Test
    void quarantineRejectLegNamingAnotherIdCancels() {
        // The wrong-target case for a two-word sweepPrefix: the prefix
        // and the trailing token both match, so only the id comparison
        // separates this from the redeeming leg above.
        UUID armed = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        UUID other = UUID.fromString("11111111-2222-3333-4444-555555555555");
        FakeConfirmStateService confirmState = new FakeConfirmStateService(
                Optional.of(new QuarantineRejectConfirm(armed)));
        CapturingAdapter capture = new CapturingAdapter();
        ConfirmPoppingCommandHandler quarantineHandler =
                new ConfirmPoppingCommandHandler("quarantine", "quarantine-reject", confirmState);
        InboundRouter router = newRouter(confirmState, capture);
        router.commandHandlers = new SingletonInstance<>(quarantineHandler);

        router.onMessage(dmInbound(DM_CONTACT, "/quarantine reject " + other + " confirm"), ADAPTER);

        assertTrue(confirmState.calls.contains("takeAny"),
                "a confirm leg naming another quarantine id must be drained by the sweep");
        assertEquals("Pending `quarantine-reject` cancelled.", capture.captured.get(0).text(),
                "first outbound must be the cancellation acknowledgement");
        assertNull(quarantineHandler.popped,
                "the handler must find NO pending payload — the forensic reject must not run");
    }

    // ----- router wiring + fakes -------------------------------------------

    /**
     * Construct an {@link InboundRouter} with all M1-044b + M1-051
     * collaborators replaced by no-op / recording fakes.
     * {@link InboundRouter#lookupUser} is overridden to return a
     * fixed "vouched" snapshot so step 2 (DM unknown) is skipped —
     * the sweep at step 4.5 IS the focus of these scenarios.
     */
    private InboundRouter newRouter(FakeConfirmStateService confirmState, CapturingAdapter target) {
        return newRouter(confirmState, target, null, true);
    }

    /**
     * Overload carrying the step-5 probation levers: {@code
     * probationUntil} seeds the snapshot the gate reads, and {@code
     * allowedDuringProbation} picks the permissions stand-in. The
     * two-arg form above pins the pre-M1-774 shape (no probation, every
     * command allowed) so the existing sweep scenarios are unchanged.
     */
    private InboundRouter newRouter(
            FakeConfirmStateService confirmState,
            CapturingAdapter target,
            @Nullable Instant probationUntil,
            boolean allowedDuringProbation) {
        InboundRouter router = new InboundRouter() {
            @Override
            Optional<UserSnapshot> lookupUser(DispatchDb db, String adapter, String contactId) {
                return Optional.of(new UserSnapshot(ACTOR_ID, "vouched", false, probationUntil));
            }

            @Override
            String lookupScopeLanguage(DispatchDb db, String scopeKind, UUID scopeId) {
                return "en";
            }
        };
        router.commandHandlers = new SingletonInstance<>();
        router.inboundContext = new InboundContext();
        router.rateCapBucket = new NoopRateCapBucket();
        router.inviteCodeConsumer = new NoopInviteCodeConsumer();
        router.bundleLoader = new CancellationBundleLoader();
        router.confirmStateService = confirmState;
        // M1-045: step 5 probation gate would NPE on null @Inject
        // fields for the vouched-user scenarios this test exercises
        // (every test in this file routes through lookupUser →
        // banCheck → probationCheck). The Noop stand-ins live as
        // top-level classes in this package — see NoopProbationCheck
        // + NoopCommandPermissions class-level javadoc for the
        // log-silent rationale.
        router.commandPermissions = allowedDuringProbation
                ? new NoopCommandPermissions()
                : new BlockingCommandPermissions();
        router.probationCheck = new NoopProbationCheck();
        // The step-5 gate compares probationUntil against the injected
        // Clock; pinning it keeps the blocked-path fixture off the wall
        // clock (engineering rules §Injectable time in decision logic).
        router.clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        router.summaryAnchorRepository = new SummaryAnchorRepository() {
            @Override public void clear(UUID userId, String scopeKind, UUID scopeId) {}
        };
        // §7a wiring: the intake consults the registered-contact set on
        // every dispatch, and the handleSlash unknown-command fallback
        // probes the asset oracle (the no-arg oracle answers false).
        router.registeredContactSet = new NoopRegisteredContactSet();
        router.assetCommandFamilyOracle = new AssetCommandFamilyOracle(new AssetRegistry());
        router.maxInboundBodyBytes = 65536;
        router.commandBodyCap = 65536;
        router.setReplyTarget(target);
        router.outboundDelivery = TestOutboundDelivery.passThrough();
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

    /**
     * Recording {@link ConfirmStateService} fake — every accessor
     * call appends to {@code calls}; {@code peek} returns the
     * constructor-supplied value; {@code takeAny} returns the same
     * AND clears the stored value (mirroring the production
     * pop-on-take semantics).
     */
    private static final class FakeConfirmStateService extends ConfirmStateService {
        final List<String> calls = new ArrayList<>();
        private Optional<ConfirmStateService.PendingConfirm> stored;

        FakeConfirmStateService(Optional<ConfirmStateService.PendingConfirm> initial) {
            this.stored = initial;
        }

        @Override
        public Optional<ConfirmStateService.PendingConfirm> peek(UUID actor, ScopeRef scope) {
            calls.add("peek");
            return stored;
        }

        @Override
        public Optional<ConfirmStateService.PendingConfirm> takeAny(UUID actor, ScopeRef scope) {
            calls.add("takeAny");
            Optional<ConfirmStateService.PendingConfirm> result = stored;
            stored = Optional.empty();
            return result;
        }

        @Override
        public Optional<ConfirmStateService.PendingConfirm> takeMatching(UUID actor, ScopeRef scope, String commandName) {
            calls.add("takeMatching:" + commandName);
            // Production pop semantics (M1-775): return AND clear iff the
            // stored payload answers to this commandName. Without them a
            // test cannot tell "the handler redeemed the pending" from
            // "the sweep had already drained it", which is the whole
            // distinction the argument match introduces.
            if (stored.isPresent() && stored.get().commandName().equals(commandName)) {
                Optional<ConfirmStateService.PendingConfirm> result = stored;
                stored = Optional.empty();
                return result;
            }
            return Optional.empty();
        }

        @Override
        public void remember(UUID actor, ScopeRef scope, ConfirmStateService.PendingConfirm pending) {
            calls.add("remember");
        }
    }

    /**
     * Permissions stand-in that blocks every command during probation —
     * the complement of {@link NoopCommandPermissions}, which allows
     * every command. Only the gate predicate is overridden; the blocked
     * reply's command listing still renders from the real
     * {@code CommandPermissions} implementation.
     */
    private static final class BlockingCommandPermissions extends CommandPermissions {
        BlockingCommandPermissions() {
            super(new AssetCommandFamilyOracle(new AssetRegistry()));
        }

        @Override
        public boolean allowedDuringProbation(String slashCommand) {
            return false;
        }
    }

    /**
     * Bundle loader that returns the actual production-string body
     * for {@link BundleKeys#REPLY_CONFIRM_CANCELLED} so the test can
     * assert the exact rendered cancellation literal. Other keys
     * return a deterministic stub.
     */
    private static final class CancellationBundleLoader extends BundleLoader {
        @Override
        public String get(String key) {
            return switch (key) {
                case BundleKeys.REPLY_CONFIRM_CANCELLED -> "Pending `{0}` cancelled.";
                default -> "bundle:" + key;
            };
        }

        @Override
        public String get(String key, String langCode) {
            return get(key);
        }
    }

    /**
     * Counting {@link CommandHandler} — counts dispatches and
     * returns a deterministic stub. The test exercises this for the
     * matching-confirm scenario to prove dispatch proceeds.
     */
    private static final class CountingCommandHandler implements CommandHandler {
        private final String name;
        private final String stubBody;
        int dispatchCount = 0;

        CountingCommandHandler(String name, String stubBody) {
            this.name = name;
            this.stubBody = stubBody;
        }

        @Override public String name() { return name; }

        @Override
        public OutboundMessage handle(ScopeRef scope, String rawText) {
            dispatchCount++;
            return new OutboundMessage(scope, stubBody, Instant.now(), "h-" + dispatchCount);
        }
    }

    /**
     * {@link CommandHandler} stand-in that performs the handler-side
     * confirm fork the real handlers perform — {@code takeMatching} on
     * its own key — and records what came back. That recorded value is
     * what makes "the mismatched leg executes nothing" an assertion
     * rather than an inference: the sweep's drain and the handler's pop
     * are two different removals, and only the handler's decides whether
     * the destructive action runs.
     */
    private static final class ConfirmPoppingCommandHandler implements CommandHandler {
        private final String name;
        private final String confirmKey;
        private final ConfirmStateService confirmState;
        ConfirmStateService.@Nullable PendingConfirm popped;

        ConfirmPoppingCommandHandler(String name, String confirmKey, ConfirmStateService confirmState) {
            this.name = name;
            this.confirmKey = confirmKey;
            this.confirmState = confirmState;
        }

        @Override public String name() { return name; }

        @Override
        public OutboundMessage handle(ScopeRef scope, String rawText) {
            popped = confirmState.takeMatching(ACTOR_ID, scope, confirmKey).orElse(null);
            return new OutboundMessage(scope,
                    popped == null ? "no-pending" : "executed", Instant.now(), "h-1");
        }
    }
}
