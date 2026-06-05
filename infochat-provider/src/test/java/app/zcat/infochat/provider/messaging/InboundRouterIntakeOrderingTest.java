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
import app.zcat.infochat.provider.group.GroupApprovalCheck;
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
 * model. Ten scenarios cover the runnable shape of the splice
 * (originally M1-044b acceptance item 12; updated by M1-044e for the
 * rate-cap-first and DM-gate-pre-dispatch reorderings; M1-112-redteam
 * Finding 1 added scenario (i) for the step-4 → step-3.5 ordering
 * swap that closes the banned-user-in-group PERM-ESCAL gap):
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
 *   <li>(g) {@code unregisteredGroupSenderIsSilentlyDropped} — Group,
 *       unknown contact (no users row), body {@code /help}; flow runs
 *       rateCap → normalize → users lookup → D47 step-3 silent drop.
 *       No outbound, no {@code banCheck}, no {@code handleSlash}
 *       (D47 gate #1).</li>
 *   <li>(h) {@code registeredGroupSenderDispatchesNormally} — Group,
 *       known {@code vouched} contact; flow runs rateCap → normalize
 *       → users lookup → banCheck (false) → groupApprovalCheck →
 *       handleSlash → dispatch reply (the step-3 drop does not over-
 *       fire for registered senders; step 4 fires BEFORE step 3.5
 *       per the M1-112-redteam ordering swap).</li>
 *   <li>(i) {@code bannedRegisteredGroupSenderShortCircuitsAtBanCheckBeforeStep35}
 *       — Group, registered but banned contact; flow runs rateCap →
 *       normalize → users lookup → banCheck (true) → ban-fixed reply.
 *       {@code groupApprovalCheck.check} MUST NOT appear — step 4
 *       short-circuits before step 3.5, closing the M1-112-redteam
 *       PERM-ESCAL surface where banned users could trigger group
 *       row INSERTs and admin notifications before the ban check.</li>
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

    // ----- (g) Group from unregistered contact + /help → D47 silent drop -----

    @Test
    void unregisteredGroupSenderIsSilentlyDropped() {
        CallLog log = new CallLog();
        InboundRouter router = newRouterWithLog(log, Optional.empty());
        // RecordingCommandHandler is wired but MUST NOT be invoked — the
        // D47 step-3 silent drop fires BEFORE dispatch.
        router.commandHandlers = new SingletonInstance<>(new RecordingCommandHandler(log, "help"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(groupInbound(GROUP_ID, GROUP_CONTACT, "/help"), ADAPTER);

        assertEquals(0, target.captured.size(),
                "unregistered group sender must receive NO reply (D47 silent drop); got: " + target.captured);
        assertEquals(
                List.of(
                        "setAdapterName",
                        "rateCapBucket.tryAcquire",
                        "lookupUser"),
                log.calls,
                "D47 silent drop must return at step 3 — banCheck and handler.handle MUST NOT appear; got: "
                        + log.calls);
    }

    // ----- (h) Group @mention from registered contact → dispatch normally ----

    @Test
    void registeredGroupSenderDispatchesNormally() {
        CallLog log = new CallLog();
        InboundRouter router = newRouterWithLog(log,
                Optional.of(new InboundRouter.UserSnapshot(UUID.randomUUID(), false, "vouched")));
        router.commandHandlers = new SingletonInstance<>(new RecordingCommandHandler(log, "help"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(groupInbound(GROUP_ID, GROUP_CONTACT, "/help"), ADAPTER);

        assertEquals(1, target.captured.size(),
                "registered group dispatch must produce exactly one outbound; got: " + target.captured);
        assertEquals("handler-reply:help", target.captured.get(0).text(),
                "the dispatch reply is the RecordingCommandHandler's output");
        assertEquals(
                List.of(
                        "setAdapterName",
                        "rateCapBucket.tryAcquire",
                        "lookupUser",
                        "banCheck.isBanned",
                        "groupApprovalCheck.check",
                        "handler.handle(help)"),
                log.calls,
                "registered group sender falls through step 3 to dispatch; step 4 (ban) "
                        + "fires BEFORE step 3.5 (group approval) per spec §Authorization model "
                        + "execution-order note; got: " + log.calls);
    }

    // ----- (i) Group @mention from banned, registered contact → ban reply --
    //
    // Per spec §Authorization model execution-order note: step 4 (ban
    // check) fires AFTER step 3 (registered/preban filter) and BEFORE
    // step 3.5 (group approval). A banned, registered user @-mentioning
    // in a group short-circuits at step 4 with the fixed ban reply;
    // GroupApprovalCheck.check MUST NOT be invoked, and no group-related
    // DB write (groups row INSERT, admin notification, per-group rate-
    // cap consumption) may fire. This closes the redteam Finding 1
    // (PERM-ESCAL/medium) gap on M1-112.

    @Test
    void bannedRegisteredGroupSenderShortCircuitsAtBanCheckBeforeStep35() {
        CallLog log = new CallLog();
        InboundRouter router = newRouterWithLog(log,
                Optional.of(new InboundRouter.UserSnapshot(UUID.randomUUID(), true, "vouched")));
        // BanCheck consults the live row per spec; align the fake with
        // the snapshot column so the production code's step 4 returns
        // is_banned=true.
        ((FakeBanCheck) router.banCheck).banned = true;
        router.commandHandlers = new SingletonInstance<>(new RecordingCommandHandler(log, "help"));
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(groupInbound(GROUP_ID, GROUP_CONTACT, "/help"), ADAPTER);

        assertEquals(1, target.captured.size(),
                "banned-in-group path must produce exactly one ban-fixed reply; got: " + target.captured);
        assertEquals(FakeBundleLoader.stubFor(BundleKeys.ERROR_BAN_FIXED),
                target.captured.get(0).text(),
                "banned-in-group reply must equal the error.ban.fixed bundle entry — step 4 wins");
        assertEquals(
                List.of(
                        "setAdapterName",
                        "rateCapBucket.tryAcquire",
                        "lookupUser",
                        "banCheck.isBanned",
                        "bundleLoader.get(error.ban.fixed)"),
                log.calls,
                "banned group sender must short-circuit at step 4 BEFORE step 3.5 — "
                        + "groupApprovalCheck.check and handler.handle MUST NOT appear; got: " + log.calls);
    }

    // ----- (j) two activated adapters → reply routes to the inbound one ----
    //
    // M1-125 acceptance item 2: with two adapters bound as reply targets,
    // a message inbound on adapter "inmemory" must be replied through
    // "inmemory" and NEVER through the other activated adapter "other".
    // This is the multi-adapter shape v1 ships (SimpleX + Signal, D46);
    // the former single last-registered-wins reply field cross-routed
    // every reply through whichever adapter registered last.

    @Test
    void replyRoutesThroughInboundAdapterNeverAnotherActivatedAdapter() {
        CallLog log = new CallLog();
        InboundRouter router = newRouterWithLog(log,
                Optional.of(new InboundRouter.UserSnapshot(UUID.randomUUID(), false, "vouched")));
        CapturingAdapter inboundAdapter = new CapturingAdapter("inmemory");
        CapturingAdapter otherAdapter = new CapturingAdapter("other");
        router.setReplyTarget(inboundAdapter);
        router.setReplyTarget(otherAdapter);

        router.onMessage(dmInbound(DM_CONTACT, "/help"), "inmemory");

        assertEquals(1, inboundAdapter.captured.size(),
                "reply must route through the adapter that delivered the inbound; got: "
                        + inboundAdapter.captured);
        assertEquals(InboundRouter.UNKNOWN_COMMAND_REPLY, inboundAdapter.captured.get(0).text(),
                "the routed reply is the /help dispatch (UNKNOWN_COMMAND_REPLY)");
        assertTrue(otherAdapter.captured.isEmpty(),
                "reply must NEVER cross-route to a different activated adapter; got: "
                        + otherAdapter.captured);
    }

    // ----- (k) banned-user fixed reply routes through the inbound adapter --
    //
    // M1-125 acceptance item 3: the banned-user fixed reply must be
    // DELIVERED (not silently dropped) through the inbound adapter, and
    // never cross-routed to another activated adapter. Under the former
    // single-field design a name-mismatched last adapter could silently
    // turn the ban reply into a drop.

    @Test
    void bannedUserFixedReplyDeliveredThroughInboundAdapterNotDroppedOrCrossRouted() {
        CallLog log = new CallLog();
        InboundRouter router = newRouterWithLog(log,
                Optional.of(new InboundRouter.UserSnapshot(UUID.randomUUID(), false, "vouched")));
        ((FakeBanCheck) router.banCheck).banned = true;
        CapturingAdapter inboundAdapter = new CapturingAdapter("inmemory");
        CapturingAdapter otherAdapter = new CapturingAdapter("other");
        router.setReplyTarget(inboundAdapter);
        router.setReplyTarget(otherAdapter);

        router.onMessage(dmInbound(DM_CONTACT, "/help"), "inmemory");

        assertEquals(1, inboundAdapter.captured.size(),
                "banned-user fixed reply must be delivered through the inbound adapter, not dropped; got: "
                        + inboundAdapter.captured);
        assertEquals(FakeBundleLoader.stubFor(BundleKeys.ERROR_BAN_FIXED),
                inboundAdapter.captured.get(0).text(),
                "banned reply body must equal the error.ban.fixed bundle entry");
        assertTrue(otherAdapter.captured.isEmpty(),
                "banned reply must NEVER cross-route to a different activated adapter; got: "
                        + otherAdapter.captured);
    }

    // ----- (l) vanished group row → silent drop, never an exception --------
    //
    // lookupGroupId returns Optional.empty() when the groups row is
    // absent (removed between the step-3.5 approval read and a later
    // lookup). The chat-mode dispatch must silently drop — no reply,
    // no exception — closing the timing oracle the former
    // IllegalStateException throw opened on group existence.

    @Test
    void groupChatMessageWithVanishedGroupRowIsSilentlyDroppedNotThrown() {
        CallLog log = new CallLog();
        InboundRouter router = newRouterWithLog(log,
                Optional.of(new InboundRouter.UserSnapshot(UUID.randomUUID(), false, "vouched")),
                Optional.empty());
        // The two chat-mode config fields default to 0 outside CDI;
        // lift them so the non-slash body passes the chat-mode body
        // cap and the LLM rate cap and reaches scope resolution.
        router.chatBodyCap = 2048;
        router.llmRateCapPerMinute = 10;
        CapturingAdapter target = new CapturingAdapter();
        router.setReplyTarget(target);

        router.onMessage(groupInbound(GROUP_ID, GROUP_CONTACT, "hello there"), ADAPTER);

        assertTrue(target.captured.isEmpty(),
                "vanished group row must silent-drop the chat dispatch (no reply, no throw); got: "
                        + target.captured);
    }

    // ----- helpers + fakes ------------------------------------------------

    /**
     * Build a router with all M1-044b collaborators replaced by
     * recording fakes. {@code snapshot} controls the
     * {@link InboundRouter#lookupUser} override's return — empty means
     * "DM unknown contact" / "group unknown contact", non-empty means
     * "user known with this {@link InboundRouter.UserSnapshot} state."
     * Under D47 the dispatch path issues exactly one users-row lookup
     * (the group auto-register re-fetch was removed), so the override
     * is stateless.
     */
    private InboundRouter newRouterWithLog(CallLog log, Optional<InboundRouter.UserSnapshot> snapshot) {
        return newRouterWithLog(log, snapshot, Optional.of(UUID.randomUUID()));
    }

    /**
     * Variant with an explicit {@code lookupGroupId} result —
     * {@code Optional.empty()} simulates a groups row that vanished
     * (removed) between the step-3.5 approval read and a later lookup.
     */
    private InboundRouter newRouterWithLog(CallLog log, Optional<InboundRouter.UserSnapshot> snapshot,
                                           Optional<UUID> groupRowId) {
        InboundRouter router = new InboundRouter() {
            @Override
            Optional<UserSnapshot> lookupUser(String adapter, String contactId) {
                log.calls.add("lookupUser");
                return snapshot;
            }

            @Override
            Optional<UUID> lookupGroupId(String adapter, String upstreamGroupId) {
                return groupRowId;
            }
        };
        router.commandHandlers = new SingletonInstance<>();
        router.inboundContext = new RecordingInboundContext(log);
        router.rateCapBucket = new CountingRateCapBucket(log);
        router.inviteCodeConsumer = new FakeInviteCodeConsumer(log);
        router.banCheck = new FakeBanCheck(log);
        router.bundleLoader = new FakeBundleLoader(log);
        // M1-051: step 4.5 confirm-cancel sweep peek call would NPE on
        // a null @Inject field. The Noop returns Optional.empty() AND
        // — critically — does NOT log into the CallLog. The per-step
        // call-order assertions of scenarios (g) and (h)
        // (unregisteredGroupSenderIsSilentlyDropped + registeredGroup
        // SenderDispatchesNormally) pin precise sequences that must
        // remain unchanged: an extra "confirmStateService.peek" log
        // entry would break those assertions, so this Noop is
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
        // M1-112: step 3.5 D47 approval gate. The recording fake logs
        // "groupApprovalCheck.check" into the CallLog ONLY when the
        // router's step-3.5 branch actually fires (group scope +
        // snapshot present). DM scenarios and unregistered-group
        // scenarios bypass step 3.5 entirely, so this fake emits no
        // log entry in those cases.
        router.groupApprovalCheck = new RecordingGroupApprovalCheck(log);
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
     * Records {@code groupApprovalCheck.check} (M1-112). Returns
     * {@link GroupApprovalCheck.Outcome.Approved} so the dispatch falls
     * through to step 4. The recording variant lives here because the
     * package-level {@link NoopGroupApprovalCheck} is deliberately
     * log-silent — the per-step call-order assertions in scenario (h)
     * (registeredGroupSenderDispatchesNormally) pin the precise
     * sequence including the new step-3.5 entry.
     */
    private static final class RecordingGroupApprovalCheck extends GroupApprovalCheck {
        private final CallLog log;

        RecordingGroupApprovalCheck(CallLog log) {
            this.log = log;
        }

        @Override
        public Outcome check(String adapter, String upstreamGroupId,
                             UUID activatorUserId, String activatorRedactedContactId) {
            log.calls.add("groupApprovalCheck.check");
            return new Outcome.Approved();
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
        private final String name;

        // Default name "inmemory" matches the inbound adapterName the
        // existing scenarios deliver, so the router's name-keyed reply
        // resolution (M1-125) finds this fake. The two-adapter routing
        // tests construct a second instance under a different name.
        CapturingAdapter() {
            this("inmemory");
        }

        CapturingAdapter(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
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
