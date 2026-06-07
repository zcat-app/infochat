package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.LlmRateCap;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import app.zcat.infochat.provider.command.AssetCommandFamilyOracle;
import app.zcat.infochat.provider.command.CommandPermissions;
import app.zcat.infochat.provider.command.ConfirmStateService;
import app.zcat.infochat.provider.command.asset.AssetHandler;
import app.zcat.infochat.provider.group.GroupApprovalCheck;
import app.zcat.infochat.provider.group.GroupAutoPromoteService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provider-side inbound dispatch. {@link AdapterRegistry} wraps the
 * router with a per-adapter {@link MessagingAdapter.InboundHandler}
 * lambda that captures the source adapter's {@code name()} and calls
 * {@link #onMessage(InboundMessage, String)}; the registry also tells
 * the router which adapter to send replies through via
 * {@link #setReplyTarget}.
 *
 * <p><b>Intake-step order (M1-044b splice).</b> {@link #onMessage}
 * runs the intake steps in the spec's exact order per
 * {@code docs/spec/security.md} §Authorization model:
 * <ol>
 *   <li>identity (resolved by {@link AdapterRegistry} into
 *       {@link InboundMessage#sender()});</li>
 *   <li>1.5 transport-level rate cap via {@link RateCapBucket};
 *       over-cap inbound is dropped silently with no outbound;</li>
 *   <li>1.7 Unicode normalize (NFKC + bidi-strip + zero-width-strip,
 *       per-line outside CommonMark fenced code);</li>
 *   <li>2 DM unknown contact → {@link InviteCodeConsumer#consume} with
 *       the normalized body. The consumer owns the UUID-parse: a
 *       non-UUID body and an invalid UUID share the same Rejected
 *       branch (both increment the brute-force counter — M1-044e
 *       AUDIT-EVASION fix). {@link InviteCodeConsumer.Accepted} sends
 *       the {@code reply.welcome.dm_fresh} bundle entry and stops;
 *       {@link InviteCodeConsumer.Rejected} and
 *       {@link InviteCodeConsumer.BruteForceThresholdBreached} both
 *       send the fixed {@code error.invite.required} reply per spec
 *       (the rate-limit state does not change the per-failure reply)
 *       and stop;</li>
 *   <li>3 Group message from an unregistered or pre-banned contact
 *       (no {@code users} row, or {@code registration_state} is
 *       {@code preban}) → silent drop: no reply, no DB write, no
 *       registration (D47 gate #1, spec §Authorization model). A
 *       registered ({@code invited}/{@code vouched}) group sender
 *       falls through carrying the snapshot resolved at step 1;</li>
 *   <li>4 ban check via {@link BanCheck#isBanned}; a banned user
 *       receives the fixed {@code error.ban.fixed} reply and stops
 *       (spec §User ban: "one fixed reply per inbound message").
 *       <b>Execution position:</b> step 4 fires after step 3 and
 *       BEFORE step 3.5 per spec §Authorization model — a banned
 *       user in group scope short-circuits here without reaching
 *       the group approval check or any group-related DB write.
 *       Step numbers in this list are stable cross-reference labels;
 *       execution order is the list order;</li>
 *   <li>3.5 D47 approval gate (M1-112): only reached by non-banned,
 *       registered users in group scope (step 4 already filtered
 *       banned). {@link GroupApprovalCheck#check} resolves the
 *       {@code (adapter, upstream_group_id)} {@code groups} row,
 *       consults the per-group reply rate bucket, and dispatches on
 *       {@code approval_status}. {@code approved} falls through to
 *       step 4.1 (auto-promote); {@code pending}/{@code rejected}
 *       emit the corresponding fixed bundle reply and stop; bucket
 *       exhaustion silently drops the @-mention (no reply); a
 *       missing row triggers cap-checks + race-safe INSERT +
 *       throttled admin notification inside
 *       {@link app.zcat.infochat.provider.group.GroupApprovalService}.
 *       Pending/rejected groups never reach step 4.1 auto-promote — the
 *       gate is the boundary between "is this a group the bot processes"
 *       and "what does the bot do with this message";</li>
 *   <li>chat-mode body cap: a non-slash body longer than
 *       {@code infochat.chat.body-cap} gets the fixed
 *       {@code error.chat.body_too_large} reply and stops here —
 *       AFTER the authorization gates (so the invite flow, D47
 *       invisibility, the ban reply, and the per-group reply rate
 *       bucket keep their spec'd precedence over the cap reply) and
 *       BEFORE the membership write (4.1), the probation lazy-clear
 *       (5), the confirm drain (4.5), and the anchor clear (4.6) —
 *       {@code commands.md} §Input length caps forbids any DB write
 *       for an oversized chat-mode message;</li>
 *   <li>5 slow-start probation gate (M1-045): if {@link ProbationCheck}
 *       reports {@code inProbation == true} AND the command is NOT in
 *       {@link CommandPermissions}'s allowed-during-probation set, the
 *       router emits {@code error.probation.blocked} with the time-
 *       until-unlock interpolated; otherwise
 *       {@link ProbationCheck#clearIfPromoted} runs as the lazy
 *       graduation clear;</li>
 *   <li>4.5 confirm-cancel sweep (M1-051): if the resolved snapshot
 *       has pending confirm state for this {@code (actor.id, scope)},
 *       AND the inbound body is NOT the confirm-shape for the pending
 *       command, drain the pending entry via
 *       {@link ConfirmStateService#takeAny} and send a cancellation
 *       acknowledgement BEFORE proceeding to dispatch. The
 *       confirm-shape body is forwarded unchanged so the handler's
 *       takeMatching pops the pending args. Empty pending → no-op;</li>
 *   <li>6 parse + dispatch (slash-command resolver or chat-mode
 *       fallback).</li>
 * </ol>
 *
 * <p><b>Users-row SELECT count per dispatch.</b> The dispatch path is
 * exactly one users-row SELECT per inbound. Steps 2 (DM emptiness),
 * 3 (group unregistered/preban drop), and 5 (probation gate) all
 * consume the SAME {@link UserSnapshot} resolved at step 1. The step 4
 * ban predicate consults {@link BanCheck#isBanned} directly per spec
 * (a separate query that sees the freshest {@code is_banned} state for
 * a banned-mid-dispatch race).</p>
 *
 * <p><b>Body-size cap (defense in depth, preserved from M1-038).</b>
 * After the rate-cap check, {@link #onMessage} drops any inbound
 * whose UTF-8 byte length exceeds
 * {@code infochat.router.max-inbound-body-bytes} with the fixed
 * {@link #MESSAGE_TOO_LARGE_REPLY} literal. Ordering rationale
 * (M1-044e): rate-cap fires FIRST so a hostile flood cannot drive
 * outbound cost via the size-cap reply path (closes the DOS
 * amplification surface — over-cap inbound never produces a friendly
 * error reply per spec §Authorization model step 1.5). The size-cap
 * still fires BEFORE normalize so NFKC amplification on adversarial
 * inputs (Hangul jamo expansion, deep combining-mark sequences)
 * cannot amplify cost. Bucket arithmetic is O(1) and cares nothing
 * about payload size, so reversing the order does not expose any
 * unbounded-input attack on the bucket.</p>
 *
 * <p><b>Normalization-first invariant (preserved from M1-035b).</b>
 * After rate cap, {@link #onMessage} runs the Unicode normalization
 * pass spec'd in {@code docs/spec/security.md} §Authorization model
 * step 1.7: NFKC, bidi-control strip, zero-width strip, leading/
 * trailing whitespace trim, applied per-line OUTSIDE CommonMark
 * fenced code blocks. The normalized body REPLACES the raw body for
 * every downstream consumer.</p>
 *
 * <p><b>Contact-id redaction in operator logs (preserved from
 * M1-038).</b> The three error-log sites in {@link #onMessage}
 * (dispatch failure, no-replyTarget, reply-send-failed) route the
 * scope's id through {@link ContactIds#redact} so a contact id never
 * appears unredacted in non-audit logs.</p>
 *
 * <p><b>Multi-adapter reply.</b> {@link #replyTargets} holds one
 * bound adapter per {@link MessagingAdapter#name()}.
 * {@link AdapterRegistry} binds one entry per activated adapter, and
 * {@link #sendReply} resolves the target by the inbound
 * {@code adapterName} so a reply always ships through the exact
 * adapter that delivered the message — never across adapter identity
 * spaces (D46, {@code security.md} §Per-adapter admin threat profile).
 * An {@code adapterName} with no bound target takes the no-target
 * drop+redact branch.</p>
 */
@ApplicationScoped
public class InboundRouter {

    private static final Logger log = LoggerFactory.getLogger(InboundRouter.class);

    /** Deterministic English literal for chat-mode (non-slash) input until T2-D wires the real dispatcher. */
    static final String CHAT_MODE_REPLY =
            "Chat-mode replies are not in the MVP; try /help for the available commands.";

    /** Deterministic English literal for an unknown slash command until M1-035c's bundle infrastructure lands. */
    static final String UNKNOWN_COMMAND_REPLY =
            "Unknown command. Try /help for the available commands.";

    /**
     * Deterministic English literal for any uncaught dispatch exception.
     * The exception's own message is NEVER interpolated here — that
     * is M1-020's sanitization concern. A fixed literal sidesteps it
     * for MVP.
     */
    static final String INTERNAL_ERROR_REPLY =
            "Something went wrong handling that message. Please try again.";

    /**
     * Deterministic English literal for an oversize inbound. Sent
     * before the normalization pass runs (see body-size cap docs at
     * the class level).
     */
    static final String MESSAGE_TOO_LARGE_REPLY =
            "That message is too large for the bot to process. Please shorten it and resend.";

    /**
     * Test-only seam: incremented on entry to {@link #normalize}. The
     * size-cap test asserts this counter does not advance across an
     * oversize inbound, proving the cap-checked branch returns before
     * the normalization pass runs.
     */
    static final AtomicLong NORMALIZE_INVOCATIONS = new AtomicLong();

    /** The pre-banned registration state: a group message from such a contact is silently dropped at step 3. */
    private static final String REGISTRATION_STATE_PREBAN = "preban";

    /** Single users-row lookup feeding steps 2, 3, and 5 from one SELECT. */
    private static final String USER_SNAPSHOT_SQL =
            "SELECT id, registration_state FROM users "
                    + "WHERE adapter = ? AND contact_id = ?";

    private static final String SELECT_GROUP_SQL =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ? "
                    + "AND removed_at IS NULL";

    private static final String ENSURE_MEMBERSHIP_SQL =
            "INSERT INTO group_membership (group_id, user_id) VALUES (?, ?) "
                    + "ON CONFLICT DO NOTHING";

    @Inject
    Instance<CommandHandler> commandHandlers;

    @Inject
    InboundContext inboundContext;

    @Inject
    RateCapBucket rateCapBucket;

    @Inject
    InviteCodeConsumer inviteCodeConsumer;

    @Inject
    BanCheck banCheck;

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    ConfirmStateService confirmStateService;

    @Inject
    GroupAutoPromoteService groupAutoPromoteService;

    @Inject
    GroupApprovalCheck groupApprovalCheck;

    @Inject
    CommandPermissions commandPermissions;

    @Inject
    AssetCommandFamilyOracle assetCommandFamilyOracle;

    @Inject
    AssetHandler assetHandler;

    @Inject
    ProbationCheck probationCheck;

    @Inject
    app.zcat.infochat.provider.chat.ChatAgent chatAgent;

    @Inject
    SummaryAnchorRepository summaryAnchorRepository;

    @Inject
    LlmRateCap llmRateCap;

    @ConfigProperty(name = "infochat.chat.body-cap", defaultValue = "2048")
    int chatBodyCap;

    /**
     * Defense-in-depth body cap. The default is below the in-memory
     * adapter's declared {@code maxInboundMessageBytes} so a
     * misbehaving adapter that lets a larger payload through still
     * gets dropped here. The base default and the per-profile
     * overrides live in {@code application.properties}.
     */
    @ConfigProperty(name = "infochat.router.max-inbound-body-bytes", defaultValue = "65536")
    int maxInboundBodyBytes;

    /**
     * Reply targets keyed by {@link MessagingAdapter#name()}.
     * {@link AdapterRegistry} binds one entry per activated adapter so
     * {@link #sendReply} can route a reply back through the exact
     * adapter that delivered the inbound (resolved by the inbound
     * {@code adapterName}) — never across adapter identity spaces.
     * Replaces the former single last-registered-wins reference.
     */
    private final ConcurrentHashMap<String, MessagingAdapter> replyTargets =
            new ConcurrentHashMap<>();

    /**
     * Bind the adapter used to send replies for inbound delivered under
     * its {@link MessagingAdapter#name()}. Called by
     * {@link AdapterRegistry} once per activated adapter at startup.
     */
    void setReplyTarget(MessagingAdapter adapter) {
        this.replyTargets.put(adapter.name(), adapter);
    }

    /**
     * Clear all bound reply targets. {@link AdapterRegistry#start}
     * calls this before re-registering so an idempotent restart in the
     * same JVM does not retain a stale name&rarr;adapter entry from a
     * prior activation.
     */
    void resetReplyTargets() {
        this.replyTargets.clear();
    }

    /**
     * Entry point for one inbound message routed from the named
     * source adapter. See the class-level Javadoc for the full
     * intake-step order.
     */
    @ActivateRequestContext
    public void onMessage(InboundMessage msg, String adapterName) {
        // @ActivateRequestContext gives this dispatch its own CDI
        // request-scope context so @RequestScoped collaborators
        // (InboundContext) resolve correctly when invoked from threads
        // that have no ambient request scope.
        //
        // Set the per-request adapter name BEFORE any size-cap / rate-cap
        // / normalize / dispatch step runs. Handlers and downstream
        // collaborators that need to qualify a per-actor users lookup by
        // (adapter, contact_id) — required for the V5 UNIQUE constraint
        // and cross-adapter isolation — read it back via @Inject
        // InboundContext.
        inboundContext.setAdapterName(adapterName);

        String raw = msg.text();
        String contactId = msg.sender().contactId();
        inboundContext.setSenderContactId(contactId);

        // Step 1.5 — transport-level rate cap. Fires FIRST per spec
        // §Authorization model step 1.5: over-cap inbound is dropped
        // silently with NO outbound (no ban reply, no invite-required
        // reply, no friendly error — including no MESSAGE_TOO_LARGE_REPLY).
        // A hostile flood that would otherwise drive the size-cap reply
        // path is short-circuited here, closing the DOS amplification
        // surface (M1-044e fix). Bucket arithmetic is O(1) and cares
        // nothing about payload size; the size-cap below still bounds
        // NFKC cost on the (rate-cap-passing) bodies that reach it.
        if (!rateCapBucket.tryAcquire(adapterName, contactId)) {
            return;
        }

        // Body-size cap (M1-038, preserved). Fires AFTER rate cap so a
        // flood cannot leak MESSAGE_TOO_LARGE_REPLY outbound, and BEFORE
        // normalize so NFKC amplification cannot drive cost on
        // adversarial inputs.
        if (raw != null && exceedsUtf8ByteLength(raw, maxInboundBodyBytes)) {
            sendReply(msg.scope(), MESSAGE_TOO_LARGE_REPLY, adapterName);
            return;
        }

        // Step 1.7 — Unicode normalize (M1-035b, preserved).
        String normalized = normalize(raw);
        if (normalized.isEmpty()) {
            return;
        }

        // Single users-row SELECT feeds steps 2 (DM emptiness), 3
        // (group unregistered/preban drop), and 5 (probation gate).
        // Step 4 consults BanCheck.isBanned directly per spec — see
        // class-level Javadoc.
        Optional<UserSnapshot> snapshot = lookupUser(adapterName, contactId);

        // Step 2 — DM unknown contact + invite-code consume.
        // Per spec §Authorization model step 2: pass the normalized
        // body to InviteCodeConsumer.consume; the consumer owns the
        // UUID-parse + brute-force counter (M1-044e fix — closes
        // AUDIT-EVASION by ensuring non-UUID probes also increment
        // the counter). Accepted → welcome reply; Rejected and
        // BruteForceThresholdBreached → fixed error.invite.required
        // reply (the spec gives the same user-visible reply for
        // both — the rate-limit state does not change it).
        if (msg.scope() instanceof ScopeRef.Dm && snapshot.isEmpty()) {
            InviteCodeConsumer.Outcome outcome =
                    inviteCodeConsumer.consume(adapterName, contactId, normalized);
            switch (outcome) {
                case InviteCodeConsumer.Accepted a ->
                        sendReply(msg.scope(), bundleLoader.get(BundleKeys.REPLY_WELCOME_DM_FRESH), adapterName);
                case InviteCodeConsumer.Rejected r ->
                        sendReply(msg.scope(), bundleLoader.get(BundleKeys.ERROR_INVITE_REQUIRED), adapterName);
                case InviteCodeConsumer.BruteForceThresholdBreached b ->
                        sendReply(msg.scope(), bundleLoader.get(BundleKeys.ERROR_INVITE_REQUIRED), adapterName);
            }
            return;
        }

        // Step 3 — Group message from an unregistered or pre-banned
        // contact → silent drop (D47 gate #1). Per spec §Authorization
        // model: a group @mention from a contact with no users row, or
        // whose registration_state is 'preban', produces NO reply, NO
        // DB write, NO registration. D47 removed the group
        // auto-registration path entirely; group interaction now
        // requires prior DM registration. The drop returns BEFORE any
        // snapshot.get() downstream, so a registered (invited/vouched)
        // group sender — guaranteed present here — falls through safely.
        if (msg.scope() instanceof ScopeRef.Group
                && (snapshot.isEmpty()
                        || REGISTRATION_STATE_PREBAN.equals(snapshot.get().registrationState()))) {
            return;
        }

        // Step 4 — ban check per spec §User ban + §Authorization model
        // step 4. The fixed error.ban.fixed reply is sent and dispatch
        // stops. Fires AFTER step 3 (registered/preban filter) and
        // BEFORE step 3.5 (group approval) so a banned user in group
        // scope short-circuits here without triggering any group-
        // related DB write (groups row INSERT, admin notification, or
        // per-group rate-cap consumption). Step numbers are stable
        // cross-reference labels per spec §Authorization model
        // "Step labels are stable cross-reference identifiers, not
        // execution-order indices"; execution order is the source-
        // code order in this method.
        if (banCheck.isBanned(adapterName, contactId)) {
            sendReply(msg.scope(), bundleLoader.get(BundleKeys.ERROR_BAN_FIXED), adapterName);
            return;
        }

        // Step 3.5 — D47 approval gate (M1-112). Group-scope inbound
        // from a registered (not preban), non-banned user routes
        // through GroupApprovalCheck.check, which consults the per-
        // group reply rate bucket then dispatches on approval_status.
        // Pending / rejected short-circuit with a fixed reply BEFORE
        // step 4.1 (auto-promote); approved falls through. Banned
        // users are already filtered at step 4 above and never reach
        // this block. The null check mirrors the step-4.1 pattern:
        // plain-JUnit test subclasses that bypass CDI may leave the
        // field null.
        if (msg.scope() instanceof ScopeRef.Group group && snapshot.isPresent()
                && groupApprovalCheck != null) {
            GroupApprovalCheck.Outcome outcome = groupApprovalCheck.check(
                    adapterName,
                    group.adapterGroupId(),
                    snapshot.get().id(),
                    ContactIds.redact(contactId));
            switch (outcome) {
                case GroupApprovalCheck.Outcome.Approved a -> {
                    // Fall through to step 4.1 (auto-promote).
                }
                case GroupApprovalCheck.Outcome.FixedReply f -> {
                    sendReply(msg.scope(), bundleLoader.get(f.bundleKey()), adapterName);
                    return;
                }
                case GroupApprovalCheck.Outcome.SilentDrop s -> {
                    return;
                }
            }
        }

        // Chat-mode body cap (commands.md §Input length caps): beyond
        // the cap → friendly error, no chat-agent invocation, no LLM
        // call, and no DB write. The check fires BEFORE the membership
        // write (4.1), the probation lazy-clear (5), the confirm drain
        // (4.5), and the anchor clear (4.6) — all forbidden for an
        // oversized message — and AFTER the authorization gates
        // (2/3/4/3.5) so the invite flow, D47 invisibility, the ban
        // reply, and the per-group reply rate bucket keep their spec'd
        // precedence over the cap reply.
        if (!normalized.startsWith("/") && normalized.length() > chatBodyCap) {
            sendReply(msg.scope(), bundleLoader.get(BundleKeys.ERROR_CHAT_BODY_TOO_LARGE), adapterName);
            return;
        }

        // Step 4.1 — Group membership + auto-promote (M1-079c).
        // Placed AFTER the ban check (step 4) so banned users never
        // get a membership row written. For every non-banned group-
        // scope sender, attempt auto-promote first (INSERT with
        // is_group_admin=true ON CONFLICT DO NOTHING), then ensure a
        // non-admin membership row exists. The null check mirrors the
        // replyTarget pattern: plain-JUnit test subclasses do not wire
        // CDI fields.
        if (msg.scope() instanceof ScopeRef.Group group && snapshot.isPresent()
                && groupAutoPromoteService != null) {
            Optional<UUID> groupId = lookupGroupId(adapterName, group.adapterGroupId());
            if (groupId.isEmpty()) {
                // Group row vanished between the step-3.5 approval read
                // and here (concurrent removal). Silent drop with a
                // specific debug log — throwing here was a timing
                // oracle distinguishing removed-group state.
                log.debug("InboundRouter: no active groups row for adapter={} group={}; dropping",
                        adapterName, ContactIds.redact(group.adapterGroupId()));
                return;
            }
            UUID senderId = snapshot.get().id();
            groupAutoPromoteService.tryAutoPromote(groupId.get(), senderId, adapterName, contactId);
            ensureGroupMembership(groupId.get(), senderId);
        }

        // Step 5 — slow-start probation gate (M1-045) per spec
        // §Slow-start tier + §Authorization model step 5. A probation
        // user invoking a non-allowed command receives the
        // error.probation.blocked reply (with the {0} time-until-
        // unlock interpolated from probation_until); a probation
        // user invoking an allowed command falls through to the
        // rest of the pipeline; a non-probation user gets the
        // opportunistic clearIfPromoted (the lazy clear) on the way.
        //
        // Invariant: snapshot is always present here — DM-empty
        // short-circuited at step 2's invite-consume; group senders
        // with no row (or 'preban') were silently dropped at step 3.
        // The defensive isPresent guard removed by M1-045 redteam-fix.
        UUID probationActorId = snapshot.get().id();
        String commandName = commandNameOf(normalized);
        if (probationCheck.inProbation(probationActorId)) {
            if (!commandPermissions.allowedDuringProbation(commandName)) {
                Instant expiry = probationCheck.probationExpiry(probationActorId);
                String body = MessageFormat.format(
                        bundleLoader.get(BundleKeys.ERROR_PROBATION_BLOCKED),
                        formatTimeUntilUnlock(expiry));
                sendReply(msg.scope(), body, adapterName);
                return;
            }
        } else {
            // Lazy clear: nulls probation_until on the next
            // request after graduation. Idempotent after first
            // success (WHERE clause matches zero rows).
            probationCheck.clearIfPromoted(probationActorId);
        }

        // Step 4.5 — confirm-cancel sweep (M1-051). Per spec
        // §Surface conventions ("any other input cancels"): a pending
        // confirm under (actor.id, scope) that does NOT match the
        // current inbound's confirm-shape is drained AND the user
        // receives a cancellation acknowledgement BEFORE the
        // intended-next-command dispatches. Snapshot is guaranteed
        // present here by the step-3 invariant.
        UUID confirmActorId = snapshot.get().id();
        Optional<ConfirmStateService.PendingConfirm> pending =
                confirmStateService.peek(confirmActorId, msg.scope());
        if (pending.isPresent() && !isConfirmShape(normalized, pending.get())) {
            // Drain pending and send cancellation BEFORE dispatch.
            // The takeAny call removes the entry; the subsequent
            // dispatch proceeds with no pending state, so the
            // user's intended-next-command is processed normally.
            ConfirmStateService.PendingConfirm cancelled = pending.get();
            confirmStateService.takeAny(confirmActorId, msg.scope());
            String cancellation = MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_CONFIRM_CANCELLED),
                    cancelled.commandName());
            sendReply(msg.scope(), cancellation, adapterName);
        }
        // Matching confirm-shape: leave pending in place; the
        // handler's takeMatching pops it on the dispatch path.

        // Step 4.6 — anchor-clear on non-/retry input (M1-065).
        // Spec §/retry: "any non-/retry input from the same (user,
        // scope) clears the anchor." Fires for all commands (except
        // /retry itself) and all chat-mode messages.
        if (!"retry".equals(commandName)) {
            UUID anchorActorId = snapshot.get().id();
            // Empty scope (group row vanished mid-dispatch) → nothing
            // to clear; the chat dispatch below silent-drops the same
            // case.
            resolveChatScopeId(msg.scope(), anchorActorId, adapterName)
                    .ifPresent(anchorScopeId -> summaryAnchorRepository.clear(
                            anchorActorId, chatModeScopeKindOf(msg.scope()), anchorScopeId));
        }

        // Step 6 — Parse + dispatch (slash-command resolver or
        // chat-mode fallback).
        String body;
        try {
            if (normalized.startsWith("/")) {
                body = handleSlash(msg.scope(), normalized);
            } else {
                // Chat-mode dispatch: enforce LLM rate cap, then delegate
                // to ChatAgent (the chat-mode body cap already fired
                // before step 4.1 — see the intake-step order above).
                // scope_kind is "dm" or "group"; scope_id is the user's
                // UUID for DM, the group's UUID for group scope
                // (per-scope isolation, schema §Invariants).
                UUID actorId = snapshot.get().id();
                if (!llmRateCap.tryAcquire(actorId)) {
                    body = bundleLoader.get(BundleKeys.ERROR_CHAT_LLM_RATE_CAP);
                } else {
                    String scopeKind = chatModeScopeKindOf(msg.scope());
                    Optional<UUID> scopeId = resolveChatScopeId(
                            msg.scope(), actorId, adapterName);
                    if (scopeId.isEmpty()) {
                        // Group row vanished mid-dispatch — silent drop,
                        // mirroring the step-4.1 empty branch.
                        log.debug("InboundRouter: no active groups row for chat scope adapter={} scope={}; dropping",
                                adapterName, ContactIds.redact(scopeIdOf(msg.scope())));
                        return;
                    }
                    body = chatAgent.handle(actorId, scopeKind, scopeId.get(), normalized);
                }
            }
        } catch (RuntimeException e) {
            SafeLog.error(log,
                    "InboundRouter dispatch failed for scope="
                            + ContactIds.redact(scopeIdOf(msg.scope())),
                    e);
            body = INTERNAL_ERROR_REPLY;
        }

        sendReply(msg.scope(), body, adapterName);
    }

    /**
     * Resolve a {@link UserSnapshot} for {@code (adapter, contactId)} —
     * the V5 UNIQUE (adapter, contact_id) constraint guarantees
     * zero-or-one result. Used by steps 2 (DM emptiness), 3 (group
     * unregistered/preban drop, reading {@code registration_state}),
     * and 5 (probation gate's actor.id read).
     *
     * <p>Package-private + non-final so plain-JUnit test helpers
     * (InboundRouterNormalizeTest, InboundRouterContactIdRedactionTest,
     * InboundRouterIntakeOrderingTest) can subclass {@link InboundRouter}
     * and override this method with a fixed-value response — the
     * helpers do not wire a {@link DataSource} fake, and the
     * size-cap / normalize / redaction code paths the tests exercise
     * do not depend on a real users row. Production callers consume
     * via the {@code @Inject} {@link DataSource} seam.</p>
     */
    Optional<UserSnapshot> lookupUser(String adapter, String contactId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(USER_SNAPSHOT_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new UserSnapshot(
                        rs.getObject("id", UUID.class),
                        rs.getString("registration_state")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "InboundRouter.lookupUser failed for adapter=" + adapter
                            + " contact_id=" + ContactIds.redact(contactId), e);
        }
    }

    /**
     * Per-dispatch snapshot of one users-row's state. Captures only
     * the columns the splice needs: {@code id} (for downstream audit
     * hooks if any) and {@code registration_state} (step 3 group
     * unregistered/preban drop predicate). The ban decision is NOT
     * snapshotted — step 4 consults {@link BanCheck#isBanned} live.
     * Package-private so test subclasses can construct instances
     * when overriding {@link #lookupUser}.
     */
    record UserSnapshot(UUID id, String registrationState) {}

    private void sendReply(ScopeRef scope, String body, String adapterName) {
        MessagingAdapter target = replyTargets.get(adapterName);
        if (target == null) {
            // No adapter is bound under this inbound's adapterName. The
            // AdapterRegistry binds every activated adapter's reply
            // target before wiring its inbound handler, so a miss means
            // either a test that did not bind this name or a stale
            // teardown — log (redacting the scope id) and drop; no
            // defensive throw, and deliberately no last-wins fallback
            // (per-adapter isolation, D46).
            log.error("InboundRouter has no replyTarget for adapter={}; dropping reply for scope={}",
                    adapterName, ContactIds.redact(scopeIdOf(scope)));
            return;
        }
        try {
            target.send(new OutboundMessage(
                    scope,
                    body,
                    Instant.now(),
                    UUID.randomUUID().toString()));
        } catch (MessagingException e) {
            // Outbound failure on the reply itself is not recoverable
            // on this code path — surface it in the log and move on.
            SafeLog.error(log,
                    "InboundRouter reply send failed for adapter=" + target.name()
                            + " scope=" + ContactIds.redact(scopeIdOf(scope)),
                    e);
        }
    }

    /**
     * Does {@code normalized} look like the confirm-shape body for
     * {@code pending}? Per spec §Surface conventions: the canonical
     * confirm form is {@code "/" + sweepPrefix + " confirm"} and the
     * "args retyped" relaxation accepts any body that starts with
     * {@code "/" + sweepPrefix + " "} AND ends with {@code " confirm"}.
     * Used by step 4.5 to decide cancel-vs-leave-alone — the handler
     * still performs the authoritative {@code takeMatching} call on
     * its own commandName key when dispatch reaches it.
     */
    private static boolean isConfirmShape(String normalized,
                                          ConfirmStateService.PendingConfirm pending) {
        String prefix = "/" + pending.sweepPrefix();
        if (normalized.equals(prefix + " confirm")) {
            return true;
        }
        return normalized.startsWith(prefix + " ") && normalized.endsWith(" confirm");
    }

    private String handleSlash(ScopeRef scope, String normalized) {
        String firstToken = normalized.split("\\s+", 2)[0];
        String commandName = firstToken.substring(1);
        for (CommandHandler handler : commandHandlers) {
            if (handler.name().equals(commandName)) {
                return handler.handle(scope, normalized).text();
            }
        }
        // Asset-command fallback: operator-configured assets have no per-asset
        // CommandHandler bean. Dispatch consults the SAME AssetCommandFamilyOracle
        // the probation gate (CommandPermissions) uses, so the gate and the
        // dispatcher agree by construction — an asset the gate admits during
        // probation is the same asset this branch routes, closing the
        // "pass the gate then Unknown command" path. A bootstrap-added asset
        // becomes dispatchable with no new code. The null guard is the
        // plain-JUnit subclass path, which wires no CDI fields and must still
        // fall through to the unknown reply (mirrors the replyTarget /
        // groupAutoPromoteService convention elsewhere in this class).
        if (assetCommandFamilyOracle != null && assetCommandFamilyOracle.isAssetCommand(commandName)) {
            return assetHandler.handle(commandName, scope, normalized).text();
        }
        return UNKNOWN_COMMAND_REPLY;
    }

    /**
     * Extract the command name for the step 5 probation gate. For a
     * slash body, returns the first whitespace-delimited token with
     * the leading {@code /} stripped (e.g. {@code "help"} from
     * {@code "/help foo"}). For a non-slash body (chat mode),
     * returns the fixed sentinel {@code "chat-mode"} so the gate's
     * allowed-set check fails closed — spec §Slow-start tier lists
     * chat mode in the Blocked column. The sentinel name is NOT in
     * {@link CommandPermissions}'s allowed set and NOT in the asset
     * family, so a probation user typing prose receives the
     * probation reply.
     */
    private static String commandNameOf(String normalized) {
        if (!normalized.startsWith("/")) {
            return "chat-mode";
        }
        String firstToken = normalized.split("\\s+", 2)[0];
        return firstToken.substring(1);
    }

    /**
     * Format the time-until-unlock for the
     * {@code error.probation.blocked} {@code {0}} token. The output
     * is approximate ({@code "~Nh"} or {@code "~Nm"}) because the
     * probation window is hours-scale and users do not need
     * second-precision. A null or past expiry renders as
     * {@code "<1m"} — defends the user-visible reply against the
     * tight race where {@link ProbationCheck#inProbation} returned
     * true but a concurrent {@code clearIfPromoted} on the same
     * row nulled the column between the two reads.
     */
    static String formatTimeUntilUnlock(@Nullable Instant expiry) {
        if (expiry == null) {
            return "<1m";
        }
        Duration remaining = Duration.between(Instant.now(), expiry);
        if (remaining.isNegative() || remaining.toMinutes() < 1) {
            return "<1m";
        }
        long hours = remaining.toHours();
        if (hours >= 1) {
            return "~" + hours + "h";
        }
        return "~" + remaining.toMinutes() + "m";
    }

    /**
     * Extract the id component of a {@link ScopeRef} for redacted
     * logging. DM scope carries the cryptographic {@code contactId};
     * group scope carries the adapter-defined stable group id. Both
     * are treated as contact-id-shaped for redaction purposes.
     */
    private static String scopeIdOf(ScopeRef scope) {
        return switch (scope) {
            case ScopeRef.Dm dm -> dm.contactId();
            case ScopeRef.Group group -> group.adapterGroupId();
        };
    }

    private static String chatModeScopeKindOf(ScopeRef scope) {
        return switch (scope) {
            case ScopeRef.Dm ignored -> "dm";
            case ScopeRef.Group ignored -> "group";
        };
    }

    // DM scope → actorId; group scope → group UUID from the groups
    // table, empty when the group row is absent (removed mid-dispatch).
    // Callers skip the anchor clear / silent-drop the chat dispatch on
    // empty rather than throwing.
    private Optional<UUID> resolveChatScopeId(ScopeRef scope,
                                              UUID actorId,
                                              String adapterName) {
        return switch (scope) {
            case ScopeRef.Dm ignored -> Optional.of(actorId);
            case ScopeRef.Group group -> lookupGroupId(adapterName, group.adapterGroupId());
        };
    }

    /**
     * Resolve the {@code groups.id} for an active (not removed) group
     * row, or empty when no such row exists. A missing row is a normal
     * race outcome (group removed between the step-3.5 approval read
     * and a later lookup), NOT an error: callers silent-drop or skip
     * instead of throwing, so an attacker cannot use the
     * exception-vs-reply difference as a timing oracle on group
     * existence. The {@link SQLException} branch (infrastructure
     * failure) still throws.
     */
    Optional<UUID> lookupGroupId(String adapter, String upstreamGroupId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_GROUP_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of((UUID) rs.getObject("id"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "InboundRouter.lookupGroupId failed for adapter=" + adapter, e);
        }
    }

    private void ensureGroupMembership(UUID groupId, UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(ENSURE_MEMBERSHIP_SQL)) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "InboundRouter.ensureGroupMembership failed", e);
        }
    }

    /**
     * Count UTF-8 byte length without allocating a byte[]. Returns true
     * as soon as the running count exceeds {@code limit} (early exit).
     */
    static boolean exceedsUtf8ByteLength(String s, int limit) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x7F) {
                count += 1;
            } else if (c <= 0x7FF) {
                count += 2;
            } else if (Character.isHighSurrogate(c)) {
                count += 4;
                i++;
            } else {
                count += 3;
            }
            if (count > limit) {
                return true;
            }
        }
        return false;
    }

    /**
     * Unicode normalization pass per
     * {@code docs/spec/security.md} §Authorization model step 1.7:
     * NFKC + bidi-control strip + zero-width strip + whole-body trim,
     * applied per-line OUTSIDE CommonMark fenced code blocks.
     *
     * <p>Package-private + static so {@code InboundRouterNormalizeTest}
     * can exercise the function directly without booting Quarkus.</p>
     */
    static String normalize(String raw) {
        NORMALIZE_INVOCATIONS.incrementAndGet();
        if (raw == null) {
            return "";
        }
        String[] lines = raw.split("\n", -1);
        StringBuilder out = new StringBuilder(raw.length());
        boolean inFence = false;
        char fenceChar = '`';
        int fenceCount = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!inFence) {
                FenceOpener opener = matchFenceOpener(line);
                if (opener != null) {
                    inFence = true;
                    fenceChar = opener.fenceChar;
                    fenceCount = opener.count;
                    // Fence-delimiter lines are preserved verbatim too
                    // — they carry literal fence characters whose
                    // exact byte sequence is the closing match key.
                    out.append(line);
                } else {
                    appendNormalized(out, line);
                }
            } else {
                if (matchFenceCloser(line, fenceChar, fenceCount)) {
                    inFence = false;
                }
                out.append(line);
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString().trim();
    }

    /**
     * Match a CommonMark fenced code-block opener per §4.5: 0–3
     * leading spaces, then a run of 3 or more {@code `} or {@code ~}
     * characters (all the same), optionally followed by an info
     * string.
     *
     * @return the opener descriptor on a match, {@code null} otherwise.
     */
    private static @Nullable FenceOpener matchFenceOpener(String line) {
        int i = 0;
        int n = line.length();
        while (i < n && i < 3 && line.charAt(i) == ' ') {
            i++;
        }
        if (i >= n) {
            return null;
        }
        char c = line.charAt(i);
        if (c != '`' && c != '~') {
            return null;
        }
        int count = 0;
        while (i + count < n && line.charAt(i + count) == c) {
            count++;
        }
        if (count < 3) {
            return null;
        }
        return new FenceOpener(c, count);
    }

    /**
     * Match a CommonMark fenced code-block closer per §4.5: 0–3
     * leading spaces, then a run of the same {@code fenceChar} at
     * count ≥ {@code fenceCount}, then only whitespace until end of
     * line.
     */
    private static boolean matchFenceCloser(String line, char fenceChar, int fenceCount) {
        int i = 0;
        int n = line.length();
        while (i < n && i < 3 && line.charAt(i) == ' ') {
            i++;
        }
        int count = 0;
        while (i + count < n && line.charAt(i + count) == fenceChar) {
            count++;
        }
        if (count < fenceCount) {
            return false;
        }
        int rest = i + count;
        while (rest < n) {
            char ch = line.charAt(rest);
            if (ch != ' ' && ch != '\t') {
                return false;
            }
            rest++;
        }
        return true;
    }

    /**
     * Apply NFKC + bidi-control strip + zero-width strip to one line
     * (no trim — whole-body trim runs at the end of {@link #normalize}).
     */
    private static void appendNormalized(StringBuilder out, String line) {
        String nfkc = Normalizer.normalize(line, Normalizer.Form.NFKC);
        for (int i = 0; i < nfkc.length(); ) {
            int cp = nfkc.codePointAt(i);
            i += Character.charCount(cp);
            if (isBidiControl(cp) || isZeroWidth(cp)) {
                continue;
            }
            out.appendCodePoint(cp);
        }
    }

    private static boolean isBidiControl(int cp) {
        // The implicit directional marks (ALM, LRM, RLM) are bidi
        // controls NFKC does NOT remove, so the explicit strip must
        // cover them alongside the embedding/override/isolate sets.
        return cp == 0x061C                        // ALM
                || cp == 0x200E || cp == 0x200F    // LRM, RLM
                || (cp >= 0x202A && cp <= 0x202E)  // LRE, RLE, PDF, LRO, RLO
                || (cp >= 0x2066 && cp <= 0x2069); // LRI, RLI, FSI, PDI
    }

    private static boolean isZeroWidth(int cp) {
        return cp == 0x200B    // ZERO WIDTH SPACE
                || cp == 0x200C // ZERO WIDTH NON-JOINER
                || cp == 0x200D // ZERO WIDTH JOINER
                || cp == 0xFEFF; // ZERO WIDTH NO-BREAK SPACE / BOM
    }

    /** Descriptor for a matched fence opener: which character (`` ` `` or {@code ~}) and how many. */
    private record FenceOpener(char fenceChar, int count) {}
}
