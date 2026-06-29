package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.chat.LlmRateCap;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import app.zcat.infochat.provider.command.AssetCommandFamilyOracle;
import app.zcat.infochat.provider.command.asset.AssetRegistry;
import app.zcat.infochat.provider.group.GroupApprovalCheck;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
 *       then the size-cap branch emits the too-large bundle reply. No
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
 *       → snapshot ban check (true) → ban-fixed reply. No
 *       {@code handleSlash}.</li>
 *   <li>(g) {@code unregisteredGroupSenderIsSilentlyDropped} — Group,
 *       unknown contact (no users row), body {@code /help}; flow runs
 *       rateCap → normalize → users lookup → D47 step-3 silent drop.
 *       No outbound, no step-4 ban check, no {@code handleSlash}
 *       (D47 gate #1).</li>
 *   <li>(h) {@code registeredGroupSenderDispatchesNormally} — Group,
 *       known {@code vouched} contact; flow runs rateCap → normalize
 *       → users lookup → snapshot ban check (false) → groupApprovalCheck →
 *       handleSlash → dispatch reply (the step-3 drop does not over-
 *       fire for registered senders; step 4 fires BEFORE step 3.5
 *       per the M1-112-redteam ordering swap).</li>
 *   <li>(i) {@code bannedRegisteredGroupSenderShortCircuitsAtBanCheckBeforeStep35}
 *       — Group, registered but banned contact; flow runs rateCap →
 *       normalize → users lookup → snapshot ban check (true) → ban-fixed
 *       reply. {@code groupApprovalCheck.check} MUST NOT appear — step 4
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
        // still fires and emits the too-large bundle reply. Users-lookup,
        // inviteCodeConsumer, and banCheck must NOT be consulted (the
        // bundle lookup is part of emitting the reply, not a collaborator
        // on the intake path).
        assertEquals(1, target.captured.size(),
                "size-cap path must send exactly one too-large reply");
        assertEquals(FakeBundleLoader.stubFor(BundleKeys.ERROR_ROUTER_MESSAGE_TOO_LARGE),
                target.captured.get(0).text());
        assertEquals(
                List.of("setAdapterName", "rateCapBucket.tryAcquire",
                        "bundleLoader.get(" + BundleKeys.ERROR_ROUTER_MESSAGE_TOO_LARGE + ")"),
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
        // too-large reply is emitted (closes the DOS amplification
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

    // ----- (e2) DM unknown contact + breached threshold → SAME reply -------
    //
    // Pins the no-brute-force-oracle property from docs/spec/security.md
    // §Invite-code registration ("it does not change the per-failure
    // user-visible reply"): the BruteForceThresholdBreached outcome must
    // produce a reply byte-identical to the Rejected outcome's, so an
    // attacker cannot observe from the reply text whether the threshold
    // has been crossed.

    @Test
    void breachedThresholdRepliesIdenticallyToRejected() {
        CallLog rejectedLog = new CallLog();
        InboundRouter rejectedRouter = newRouterWithLog(rejectedLog, Optional.empty());
        ((FakeInviteCodeConsumer) rejectedRouter.inviteCodeConsumer).outcome =
                new InviteCodeConsumer.Rejected();
        CapturingAdapter rejectedTarget = new CapturingAdapter();
        rejectedRouter.setReplyTarget(rejectedTarget);
        rejectedRouter.onMessage(dmInbound(DM_CONTACT, UUID.randomUUID().toString()), ADAPTER);

        CallLog breachedLog = new CallLog();
        InboundRouter breachedRouter = newRouterWithLog(breachedLog, Optional.empty());
        ((FakeInviteCodeConsumer) breachedRouter.inviteCodeConsumer).outcome =
                new InviteCodeConsumer.BruteForceThresholdBreached();
        CapturingAdapter breachedTarget = new CapturingAdapter();
        breachedRouter.setReplyTarget(breachedTarget);
        breachedRouter.onMessage(dmInbound(DM_CONTACT, UUID.randomUUID().toString()), ADAPTER);

        assertEquals(1, breachedTarget.captured.size(),
                "breached-threshold path must produce exactly one reply; got: "
                        + breachedTarget.captured);
        assertEquals(FakeBundleLoader.stubFor(BundleKeys.ERROR_INVITE_REQUIRED),
                breachedTarget.captured.get(0).text(),
                "breached-threshold reply body must equal the error.invite.required bundle entry");
        assertEquals(rejectedTarget.captured.get(0).text(),
                breachedTarget.captured.get(0).text(),
                "breached-threshold reply must be byte-identical to the Rejected-path reply — "
                        + "no brute-force oracle in the reply text");
    }

    // ----- (f) DM known is_banned=true → ban-fixed reply -------------------

    @Test
    void knownBannedDmStopsWithFixedReplyAndNoHandleSlash() {
        CallLog log = new CallLog();
        InboundRouter router = newRouterWithLog(log,
                Optional.of(new InboundRouter.UserSnapshot(UUID.randomUUID(), "vouched", true, null)));
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
                Optional.of(new InboundRouter.UserSnapshot(UUID.randomUUID(), "vouched", false, null)));
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
                Optional.of(new InboundRouter.UserSnapshot(UUID.randomUUID(), "vouched", true, null)));
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
                Optional.of(new InboundRouter.UserSnapshot(UUID.randomUUID(), "vouched", false, null)));
        CapturingAdapter inboundAdapter = new CapturingAdapter("inmemory");
        CapturingAdapter otherAdapter = new CapturingAdapter("other");
        router.setReplyTarget(inboundAdapter);
        router.setReplyTarget(otherAdapter);

        router.onMessage(dmInbound(DM_CONTACT, "/help"), "inmemory");

        assertEquals(1, inboundAdapter.captured.size(),
                "reply must route through the adapter that delivered the inbound; got: "
                        + inboundAdapter.captured);
        assertEquals(FakeBundleLoader.stubFor(BundleKeys.ERROR_UNKNOWN_COMMAND),
                inboundAdapter.captured.get(0).text(),
                "the routed reply is the /help dispatch (unknown-command bundle reply)");
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
                Optional.of(new InboundRouter.UserSnapshot(UUID.randomUUID(), "vouched", true, null)));
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

    // ----- (l) vanished/removed group → silent drop, never an exception ----
    //
    // A removed-but-approved (or otherwise vanished) group resolves at
    // the step-3.5 approval read to GroupApprovalCheck.Outcome.SilentDrop
    // (here the empty groupRowId drives the recording check to return
    // SilentDrop). The chat-mode dispatch must silently drop — no reply,
    // no exception — preserving the timing-oracle protection: an
    // attacker cannot distinguish removed-group state by response shape.
    // This is the mechanism that replaced the router's former step-4.1
    // lookupGroupId re-read (removed_at IS NULL filter → empty → drop).

    @Test
    void groupChatMessageWithVanishedGroupRowIsSilentlyDroppedNotThrown() {
        CallLog log = new CallLog();
        InboundRouter router = newRouterWithLog(log,
                Optional.of(new InboundRouter.UserSnapshot(UUID.randomUUID(), "vouched", false, null)),
                Optional.empty());
        // Outside CDI the chat-mode body-cap config field defaults to 0
        // and the LLM rate-cap collaborator is null; lift both so the
        // non-slash body passes the chat-mode body cap and the LLM
        // rate cap and reaches scope resolution.
        router.chatBodyCap = 2048;
        router.llmRateCap = new LlmRateCap(10);
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
     * Variant with an explicit approved group id —
     * {@code Optional.empty()} simulates a groups row that vanished
     * (removed): the step-3.5 approval read now resolves it to a
     * {@link GroupApprovalCheck.Outcome.SilentDrop} (the router's
     * former step-4.1 {@code lookupGroupId} re-read that produced the
     * drop is gone). A present id yields {@code Approved(id)}, which the
     * router carries forward as the group dispatch scope id.
     */
    private InboundRouter newRouterWithLog(CallLog log, Optional<InboundRouter.UserSnapshot> snapshot,
                                           Optional<UUID> groupRowId) {
        InboundRouter router = new InboundRouter() {
            @Override
            Optional<UserSnapshot> lookupUser(DispatchDb db, String adapter, String contactId) {
                log.calls.add("lookupUser");
                return snapshot;
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
        // M1-506: step 2 now calls the SimpleX admin-claim BEFORE the invite
        // consume. These ordering scenarios use ADAPTER="inmemory" (never a
        // claim), so a log-silent stub returning NotClaimed keeps the flow
        // falling through to inviteCodeConsumer exactly as before — the
        // per-step call-order assertions are unchanged (the stub never logs).
        router.simpleXAdminClaim = new SimpleXAdminClaim() {
            @Override
            public Outcome claim(String adapter, String contactId, String body) {
                return new NotClaimed();
            }
        };
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
        // log entry in those cases. The outcome encodes the group id
        // the router carries to step 4.1 — or SilentDrop for the
        // vanished-group case (empty groupRowId), since the former
        // step-4.1 lookupGroupId re-read no longer produces that drop.
        GroupApprovalCheck.Outcome approvalOutcome = groupRowId
                .<GroupApprovalCheck.Outcome>map(GroupApprovalCheck.Outcome.Approved::new)
                .orElseGet(GroupApprovalCheck.Outcome.SilentDrop::new);
        router.groupApprovalCheck = new RecordingGroupApprovalCheck(log, approvalOutcome);
        router.summaryAnchorRepository = new SummaryAnchorRepository() {
            @Override public void clear(UUID userId, String scopeKind, UUID scopeId) {}
        };
        // §7a wiring: the production fields are non-null by contract, so
        // the plain-JUnit setup supplies the log-silent doubles instead
        // of relying on removed null branches. The fake JDBC stack only
        // serves the step-4.1 membership upsert (the lookups are
        // overridden seams above); the no-arg oracle answers false for
        // every asset probe.
        router.registeredContactSet = new NoopRegisteredContactSet();
        router.assetCommandFamilyOracle = new AssetCommandFamilyOracle(new AssetRegistry());
        CountingDispatchDataSource dispatchDataSource =
                new CountingDispatchDataSource(UUID.randomUUID());
        router.dataSource = dispatchDataSource;
        router.groupAutoPromoteService = new NoopGroupAutoPromoteService(dispatchDataSource);
        router.maxInboundBodyBytes = 65536;
        router.commandBodyCap = 65536;
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

    private static InboundMessage groupInbound(String groupId, String contactId, String body) {
        return new InboundMessage(
                new Identity(contactId, "Bob", Instant.now()),
                new ScopeRef.Group(groupId),
                body,
                Instant.now(),
                "msg-1");
    }
}
