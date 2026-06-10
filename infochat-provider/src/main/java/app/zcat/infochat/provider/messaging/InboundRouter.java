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
 *   <li>4 ban check from the step-1 snapshot ({@code isBanned}); a
 *       banned user receives the fixed {@code error.ban.fixed} reply
 *       and stops
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
 *   <li>command body cap: a slash body longer than
 *       {@code infochat.command.body-cap} gets the fixed
 *       {@code error.command.body_too_large} reply and stops at the
 *       same position as the chat-mode cap — before the parser
 *       ({@code handleSlash}) and any DB write;</li>
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
 * 3 (group unregistered/preban drop), 4 (ban check), and 5 (probation
 * gate) all consume the SAME {@link UserSnapshot} resolved at step 1.
 * The step-1 SELECT projects {@code is_banned} so the step 4 ban
 * predicate reads {@code snapshot.isBanned()} rather than issuing a
 * second query; the step-1→step-4 TOCTOU is microseconds and the ban
 * takes effect on the next inbound regardless.</p>
 *
 * <p><b>Dispatch connection (pre-LLM phase).</b> Every router-owned DB
 * step of one inbound — the users-row snapshot, the groups-row
 * resolution, the membership upsert — runs on a single lazily-borrowed
 * pool connection ({@link DispatchDb}) that is closed before step 6
 * hands off to a command handler or the chat agent, so no router
 * connection is ever held across an LLM call. The {@code groups.id} is
 * resolved at most once per dispatch (step 4.1) and carried forward to
 * the group rate caps, the anchor clear, and chat scope resolution.
 * Collaborators invoked during the pre-LLM phase (invite consumer,
 * approval check, auto-promote, probation, anchor repository) manage
 * their own connections, so a dispatch may briefly hold two pool
 * connections; the pool must size for that worst case.</p>
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

    /** Single users-row lookup feeding steps 2, 3, 4, and 5 from one SELECT. */
    private static final String USER_SNAPSHOT_SQL =
            "SELECT id, registration_state, is_banned FROM users "
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
    RegisteredContactSet registeredContactSet;

    @Inject
    InviteCodeConsumer inviteCodeConsumer;

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

    @ConfigProperty(name = "infochat.command.body-cap", defaultValue = "8192")
    int commandBodyCap;

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
        //
        // M1-229 split: a registered sender gets a per-(adapter,
        // contactId) bucket; an unregistered sender shares the per-
        // adapter stranger limiter and mints no per-id state, so a Sybil
        // flood of distinct stranger ids cannot pin the per-id map at
        // maxContactBuckets (the M1-205 capacity-wall DOS). The route is
        // a pure in-memory RegisteredContactSet lookup — NO DB read here
        // (the users-row SELECT stays at lookupUser below, deliberately
        // downstream). The null guard mirrors the groupApprovalCheck /
        // groupAutoPromoteService pattern: plain-JUnit test subclasses
        // that bypass CDI leave the field null and route conservatively
        // (treated as stranger, never falsely registered).
        boolean registered = registeredContactSet != null
                && registeredContactSet.isRegistered(adapterName, contactId);
        if (!rateCapBucket.tryAcquire(adapterName, contactId, registered)) {
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
        // (group unregistered/preban drop), 4 (ban check), and 5
        // (probation gate) — see class-level Javadoc. Every router-owned
        // DB step of the pre-LLM intake phase shares the one lazily-
        // borrowed dispatch connection below; the try closes it before
        // step 6 hands off to a command handler or the chat agent.
        Optional<UserSnapshot> snapshot;
        // DM → the actor's users.id; group → the groups.id resolved at
        // most once at step 4.1 and carried forward (group rate caps,
        // anchor clear, chat scope resolution — per-scope isolation,
        // schema §Invariants).
        UUID dispatchScopeId;
        try (DispatchDb db = new DispatchDb(dataSource)) {
            snapshot = lookupUser(db, adapterName, contactId);

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
            //
            // is_banned is served from the step-1 snapshot (one users-row
            // SELECT per dispatch) instead of a second live query. Spec-
            // legal: §Authorization model requires the ban check at step-4
            // ordering, not a separate query. The step-1→step-4 TOCTOU is
            // microseconds inside this synchronous handler; a ban landing in
            // that window lets at most this one in-flight message through and
            // takes effect on the next inbound. snapshot is guaranteed present
            // here — DM-empty returns at step 2, group-empty/preban at step 3.
            if (snapshot.get().isBanned()) {
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

            // Slash-command body cap (commands.md §Input length caps): a
            // slash body longer than infochat.command.body-cap gets the
            // fixed error.command.body_too_large reply and stops BEFORE the
            // parser (handleSlash) — same ordering rationale as the chat cap
            // above (after the authorization gates, before any DB write).
            if (normalized.startsWith("/") && normalized.length() > commandBodyCap) {
                sendReply(msg.scope(), bundleLoader.get(BundleKeys.ERROR_COMMAND_BODY_TOO_LARGE), adapterName);
                return;
            }

            // Step 4.1 — Group resolution, auto-promote, membership
            // (M1-079c). Placed AFTER the ban check (step 4) so banned
            // users never get a membership row written. The groups-row
            // id is resolved exactly once per dispatch, here, on the
            // dispatch connection, and carried forward as
            // dispatchScopeId. A missing row is the vanished-group race
            // (concurrent removal after the step-3.5 approval read):
            // silent drop with a specific debug log — throwing here was
            // a timing oracle distinguishing removed-group state. For
            // every non-banned group-scope sender, attempt auto-promote
            // first (INSERT with is_group_admin=true ON CONFLICT DO
            // NOTHING), then ensure a non-admin membership row exists.
            // The null check on the service mirrors the replyTarget
            // pattern: plain-JUnit test subclasses do not wire CDI
            // fields.
            if (msg.scope() instanceof ScopeRef.Group group) {
                Optional<UUID> groupId = lookupGroupId(db, adapterName, group.adapterGroupId());
                if (groupId.isEmpty()) {
                    log.debug("InboundRouter: no active groups row for adapter={} group={}; dropping",
                            adapterName, ContactIds.redact(group.adapterGroupId()));
                    return;
                }
                dispatchScopeId = groupId.get();
                if (groupAutoPromoteService != null) {
                    UUID senderId = snapshot.get().id();
                    groupAutoPromoteService.tryAutoPromote(dispatchScopeId, senderId, adapterName, contactId);
                    ensureGroupMembership(db, dispatchScopeId, senderId);
                }
            } else {
                dispatchScopeId = snapshot.get().id();
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
            // /retry itself) and all chat-mode messages. The scope id is
            // the carried dispatchScopeId — no group re-lookup; a
            // vanished group row already silent-dropped at step 4.1.
            if (!"retry".equals(commandName)) {
                UUID anchorActorId = snapshot.get().id();
                summaryAnchorRepository.clear(
                        anchorActorId, chatModeScopeKindOf(msg.scope()), dispatchScopeId);
            }
        }

        // ---- LLM boundary. The dispatch connection is closed above;
        // step 6 runs with no router-held pool connection — command
        // handlers and the chat agent borrow (and release) their own,
        // and nothing may pin a connection under an LLM call.

        // Step 6 — Parse + dispatch (slash-command resolver or
        // chat-mode fallback). The body is @Nullable: a slash handler
        // that owns its own message lifecycle via the ProgressNotifier
        // returns null (handleSlash propagates it) to signal
        // "already delivered" — the router then performs NO send for
        // that invocation, so a placeholder→finalize handler does not
        // double-send. Every other branch assigns a non-null body.
        @Nullable String body;
        try {
            if (normalized.startsWith("/")) {
                // Per-group command rate cap (D47) per spec §Rate
                // limiting: an aggregate sub-bucket keyed on groups.id
                // bounding slash-command dispatch volume from all
                // members. "Approved only" holds by position
                // (pending/rejected stopped at step 3.5); DM slash
                // dispatch never consults the group bucket. Overflow
                // sends the fixed group.command_rate_limit reply per
                // design §4.9 — unlike the reply bucket's silent drop.
                // The bucket keys on the carried dispatchScopeId — the
                // groups.id resolved once at step 4.1.
                if (msg.scope() instanceof ScopeRef.Group
                        && !rateCapBucket.tryAcquireGroupCommand(dispatchScopeId)) {
                    body = bundleLoader.get(BundleKeys.GROUP_COMMAND_RATE_LIMIT);
                } else {
                    body = handleSlash(msg.scope(), normalized);
                }
            } else {
                // Chat-mode dispatch: enforce LLM rate cap, then delegate
                // to the chat agent (the chat-mode body cap already fired
                // before step 4.1 — see the intake-step order above).
                // scope_kind is "dm" or "group"; the scope id is the
                // carried dispatchScopeId — the user's UUID for DM, the
                // group's UUID for group scope (per-scope isolation,
                // schema §Invariants).
                UUID actorId = snapshot.get().id();
                if (!llmRateCap.tryAcquire(actorId)) {
                    body = bundleLoader.get(BundleKeys.ERROR_CHAT_LLM_RATE_CAP);
                } else {
                    String scopeKind = chatModeScopeKindOf(msg.scope());
                    // Per-group LLM backstop (D47, M1-222) per spec
                    // §Rate limiting: the per-user cap above fires
                    // first; this aggregate sub-bucket keyed on
                    // groups.id bounds LLM cost for groups with many
                    // active members. Group scope only — the DM case
                    // never consults the group bucket. "Approved only"
                    // holds by position (pending/rejected stopped at
                    // step 3.5). Overflow sends the fixed
                    // group.llm_rate_limit reply per design §4.9 —
                    // unlike the reply bucket's silent drop. Periodic
                    // digests are system-initiated and never reach
                    // this path.
                    if (msg.scope() instanceof ScopeRef.Group
                            && !rateCapBucket.tryAcquireGroupLlm(dispatchScopeId)) {
                        // Group-cap rejection refunds the per-user token
                        // acquired above — the backstop must not drain the
                        // sender's personal budget on fixed replies.
                        llmRateCap.refund(actorId);
                        body = bundleLoader.get(BundleKeys.GROUP_LLM_RATE_LIMIT);
                    } else {
                        body = dispatchChat(actorId, scopeKind, dispatchScopeId, normalized);
                    }
                }
            }
        } catch (RuntimeException e) {
            SafeLog.error(log,
                    "InboundRouter dispatch failed for scope="
                            + ContactIds.redact(scopeIdOf(msg.scope())),
                    e);
            body = INTERNAL_ERROR_REPLY;
        }

        // A null body means a self-delivering handler already shipped
        // its reply through the ProgressNotifier — skip the router send.
        if (body != null) {
            sendReply(msg.scope(), body, adapterName);
        }
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
     * do not depend on a real users row. Production resolution runs on
     * the shared per-dispatch connection ({@link DispatchDb}, lazily
     * borrowed at this first use); overrides ignore the context and
     * never touch JDBC.</p>
     */
    Optional<UserSnapshot> lookupUser(DispatchDb db, String adapter, String contactId) {
        try (PreparedStatement ps = db.connection().prepareStatement(USER_SNAPSHOT_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new UserSnapshot(
                        rs.getObject("id", UUID.class),
                        rs.getString("registration_state"),
                        rs.getBoolean("is_banned")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "InboundRouter.lookupUser failed for adapter=" + adapter
                            + " contact_id=" + ContactIds.redact(contactId), e);
        }
    }

    /**
     * Per-dispatch snapshot of one users-row's state. Captures the
     * columns the splice needs: {@code id} (for downstream audit
     * hooks if any), {@code registration_state} (step 3 group
     * unregistered/preban drop predicate), and {@code is_banned}
     * (step 4 ban check). Package-private so test subclasses can
     * construct instances when overriding {@link #lookupUser}.
     */
    record UserSnapshot(UUID id, String registrationState, boolean isBanned) {}

    /**
     * Per-dispatch database context: one lazily-borrowed pool
     * connection shared by every router-owned DB step of the pre-LLM
     * intake phase (users-row snapshot, groups-row resolution,
     * membership upsert). Lazy so dispatches that stop before any DB
     * step — and test subclasses that override the lookup seams —
     * never borrow at all. Closed before step 6 hands off to a
     * command handler or the chat agent: no router connection may be
     * held across an LLM call, and dispatch-phase collaborators
     * borrow their own.
     */
    static final class DispatchDb implements AutoCloseable {

        private final DataSource dataSource;
        private @Nullable Connection connection;

        DispatchDb(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        /** The shared dispatch connection, borrowed from the pool on first use. */
        Connection connection() throws SQLException {
            if (connection == null) {
                connection = dataSource.getConnection();
            }
            return connection;
        }

        @Override
        public void close() {
            if (connection == null) {
                return;
            }
            try {
                connection.close();
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "InboundRouter dispatch connection release failed", e);
            }
        }
    }

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

    private @Nullable String handleSlash(ScopeRef scope, String normalized) {
        String firstToken = normalized.split("\\s+", 2)[0];
        String commandName = firstToken.substring(1);
        for (CommandHandler handler : commandHandlers) {
            if (handler.name().equals(commandName)) {
                // A null return signals the handler already delivered its
                // reply via the ProgressNotifier; propagate the null so
                // onMessage skips the router send (no double-send).
                OutboundMessage reply = handler.handle(scope, normalized);
                return reply == null ? null : reply.text();
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

    /**
     * Resolve the {@code groups.id} for an active (not removed) group
     * row, or empty when no such row exists. A missing row is a normal
     * race outcome (group removed between the step-3.5 approval read
     * and the step-4.1 resolution), NOT an error: the caller
     * silent-drops instead of throwing, so an attacker cannot use the
     * exception-vs-reply difference as a timing oracle on group
     * existence. The {@link SQLException} branch (infrastructure
     * failure) still throws. Same test seam as {@link #lookupUser}:
     * package-private + non-final, runs on the shared per-dispatch
     * connection.
     */
    Optional<UUID> lookupGroupId(DispatchDb db, String adapter, String upstreamGroupId) {
        try (PreparedStatement ps = db.connection().prepareStatement(SELECT_GROUP_SQL)) {
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

    private void ensureGroupMembership(DispatchDb db, UUID groupId, UUID userId) {
        try (PreparedStatement ps = db.connection().prepareStatement(ENSURE_MEMBERSHIP_SQL)) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "InboundRouter.ensureGroupMembership failed", e);
        }
    }

    /**
     * Chat-mode dispatch hand-off. Package-private + non-final for the
     * same reason as {@link #lookupUser}: plain-JUnit tests assert the
     * LLM-boundary invariant (the dispatch connection is released
     * before this point) without wiring a real
     * {@link app.zcat.infochat.provider.chat.ChatAgent}.
     */
    String dispatchChat(UUID actorId, String scopeKind, UUID scopeId, String normalized) {
        return chatAgent.handle(actorId, scopeKind, scopeId, normalized);
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
