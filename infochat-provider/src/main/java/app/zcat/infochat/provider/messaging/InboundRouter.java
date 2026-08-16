package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerRegistry;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ProgressStage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.Utf8;
import app.zcat.infochat.messaging.metrics.AdapterMetrics;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.ChatAgent;
import app.zcat.infochat.provider.chat.ChatReplyMode;
import app.zcat.infochat.provider.chat.ChatReplyModeResolver;
import app.zcat.infochat.provider.chat.InFlightTracker;
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
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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
 *   <li>2 DM unknown contact → first the SimpleX bootstrap-admin
 *       claim-token (D50): {@link SimpleXAdminClaim#claim} on the
 *       normalized body; a {@link SimpleXAdminClaim.Claimed} outcome
 *       sends the {@code reply.welcome.admin_claim} entry and stops, and
 *       {@link SimpleXAdminClaim.NotClaimed} (wrong/used token, no token
 *       configured, or non-SimpleX adapter) falls through to the invite
 *       path so a bad token gets the SAME fixed reply an invalid invite
 *       would. Then {@link InviteCodeConsumer#consume} with
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
 *       for an oversized chat-mode message. Like the single-line rule
 *       it drains any pending confirm for {@code (actor.id, scope)}
 *       before the rejection reply (M1-774) — an in-memory removal,
 *       not a DB write;</li>
 *   <li>command body cap: a slash body longer than
 *       {@code infochat.command.body-cap} gets the fixed
 *       {@code error.command.body_too_large} reply and stops at the
 *       same position as the chat-mode cap — before the parser
 *       ({@code handleSlash}) and any DB write. Drains any pending
 *       confirm first, like the chat-mode cap (M1-774);</li>
 *   <li>single-line rule (M1-772): a slash body carrying any line
 *       boundary gets the fixed {@code error.command.multiline} reply
 *       and stops at the same position as the two caps — see
 *       {@link #isMultiLineCommand}. Unlike the caps it first drains
 *       any pending confirm for this {@code (actor.id, scope)} and
 *       acknowledges the cancellation: the rejected body dispatches
 *       nothing, so it is unconditionally the "any other input" that
 *       spec §Surface conventions says closes the armed window, and it
 *       is reachable by a body a user can type by accident. The drain
 *       is an in-memory removal; the step-4.6 anchor clear stays behind
 *       the return because it is a DB write;</li>
 *   <li>5 slow-start probation gate (M1-045): if the step-1
 *       {@link UserSnapshot#inProbation(Instant)} (from the snapshot's
 *       {@code probation_until}) is true AND the command is NOT in
 *       {@link CommandPermissions}'s allowed-during-probation set, the
 *       router emits {@code error.probation.blocked} with the time-
 *       until-unlock interpolated from the snapshot value — draining
 *       any pending confirm for {@code (actor.id, scope)} first, since
 *       a probation user can hold one ({@code /forget} is both
 *       confirm-arming and probation-allowed) and this block returns
 *       ahead of the step-4.5 sweep (M1-774); otherwise
 *       {@link ProbationCheck#clearIfPromoted} runs as the lazy
 *       graduation clear, but only when {@code probation_until} is
 *       non-null and already past (the NULL steady state issues no
 *       UPDATE);</li>
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
 * The step-1 SELECT projects {@code is_banned} AND
 * {@code probation_until} so the step 4 ban predicate reads
 * {@code snapshot.isBanned()} and the step 5 gate reads
 * {@code snapshot.inProbation(now)} rather than issuing further queries;
 * the step-1→step-4/5 TOCTOU is microseconds and both the ban and a
 * probation graduation take effect on the next inbound regardless. The
 * only remaining step-5 write is the lazy graduation
 * {@link ProbationCheck#clearIfPromoted} UPDATE, now issued solely on the
 * first post-graduation inbound (non-null past {@code probation_until}),
 * never on the NULL steady state.
 *
 * <p>One dispatch path exceeds that count: an unrecognized slash command
 * consults {@code HelpCommandHandler.slashMissSuggestion} for intent-aware
 * guidance (M1-647), and the handler resolves the caller's tier through its
 * own reads — a second users-row SELECT plus a probation check, plus the
 * group-approval and group-admin lookups in group scope. The path is
 * reached only by a name no handler and no asset family serves, and it is
 * bounded by the step-1.5 per-(adapter, contact_id) transport cap, so the
 * extra reads cannot be driven faster than that cap allows.</p>
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
 * {@code error.router.message_too_large} bundle reply. Ordering rationale
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

    /**
     * Test-only seam: incremented on entry to {@link #normalize}. The
     * size-cap test asserts this counter does not advance across an
     * oversize inbound, proving the cap-checked branch returns before
     * the normalization pass runs.
     */
    static final AtomicLong NORMALIZE_INVOCATIONS = new AtomicLong();

    /** The pre-banned registration state: a group message from such a contact is silently dropped at step 3. */
    private static final String REGISTRATION_STATE_PREBAN = "preban";

    /** Trailing token of every confirm leg, with its separating space. */
    private static final String CONFIRM_TOKEN = " confirm";

    /**
     * M1-705 cheap-command classification — exactly the eight
     * parser-only / DB-read {@link CommandHandler}-bean command names
     * docs/spec/security.md §Rate limiting names in the cheap group
     * ({@code /quarantine list} and the asset commands draw the same
     * bucket IN-HANDLER: the quarantine subcommand split is invisible
     * at this name level, and operator-configured asset names can never
     * appear in a static set). {@code add-source} and {@code
     * quarantine} are deliberately absent — they draw their own
     * dedicated per-user buckets in-handler — so no command ever draws
     * TWO per-user buckets. A cheap command in group scope still draws
     * the per-GROUP command bucket at dispatch, so the group backstop's
     * rejection refunds the charged cheap token (the group-LLM refund
     * discipline, applied to the cheap path).
     */
    private static final Set<String> CHEAP_COMMANDS = Set.of(
            "help", "status", "list-sources", "get-sources",
            "get-tags", "saved", "audit", "export");

    /** Single users-row lookup feeding steps 2, 3, 4, and 5 from one SELECT. */
    private static final String USER_SNAPSHOT_SQL =
            "SELECT id, registration_state, is_banned, probation_until FROM users "
                    + "WHERE adapter = ? AND contact_id = ?";

    private static final String ENSURE_MEMBERSHIP_SQL =
            "INSERT INTO group_membership (group_id, user_id) VALUES (?, ?) "
                    + "ON CONFLICT DO NOTHING";

    /**
     * Effective scope language per D43 — same resolution semantics as
     * {@code ChatAgent.readScopeLanguage} and the digest path's
     * {@code GROUP_META_SQL} COALESCE: a missing row means {@code en}.
     * Reads the {@code reply_mode} override on the SAME row so a dispatch
     * still runs exactly one scope_preferences SELECT (D79 reply-mode
     * resolution rides this lookup — see {@link #lookupScopeLanguage}).
     */
    private static final String SELECT_SCOPE_LANGUAGE_SQL =
            "SELECT language, reply_mode FROM scope_preferences WHERE scope_kind = ? AND scope_id = ?";

    @Inject
    Instance<CommandHandler> commandHandlers;

    @Inject
    InboundContext inboundContext;

    @Inject
    ChatReplyModeResolver replyModeResolver;

    @Inject
    RateCapBucket rateCapBucket;

    @Inject
    RegisteredContactSet registeredContactSet;

    @Inject
    InviteCodeConsumer inviteCodeConsumer;

    @Inject
    SimpleXAdminClaim simpleXAdminClaim;

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

    // The step-5 probation gate samples 'now' from the injected Clock, never
    // inline Instant.now(), so the block/allow decision is pinnable in tests
    // (InboundRouterProbationClockTest) and shares one clock with the app-side
    // probation_until writers — closing the app/DB skew engineering-rules §9
    // forbids on this authorization path. Field-initialised = Clock.systemUTC()
    // so the many hand-constructed router tests stay non-null; CDI injection
    // overrides it in the managed bean (producer ThrottledAdminNotifier
    // .systemUtcClock()). Production behaviour is byte-for-byte preserved under
    // Clock.systemUTC(). (M1-451, pattern from M1-450 ProbationCheck)
    @Inject
    Clock clock = Clock.systemUTC();

    @Inject
    app.zcat.infochat.provider.chat.ChatAgent chatAgent;

    @Inject
    SummaryAnchorRepository summaryAnchorRepository;

    @Inject
    LlmRateCap llmRateCap;

    @Inject
    OutboundDelivery outboundDelivery;

    /**
     * D31 progress publisher for the chat-mode dispatch (M1-607): the
     * chat turn self-delivers placeholder → coalesced stage edits →
     * finalized reply, mirroring {@code SummaryCommandHandler}. Injected
     * as the concrete class (not the {@link app.zcat.infochat.messaging.ProgressNotifier}
     * SPI) because the chat path alone needs the terminal delivery
     * outcome ({@link StageProgressNotifier#completeDelivered}) to gate
     * the deferred post-delivery persist.
     */
    @Inject
    StageProgressNotifier progressNotifier;

    /**
     * Read-only peek mirroring ChatAgent's M1-606 breaker gate: when the
     * chat endpoint's breaker would short-circuit, ChatAgent skips the
     * M1-589 semantic pre-fetch, so the RETRIEVING stage must not be
     * advertised for that turn. {@code wouldShortCircuit} never
     * transitions breaker state, so this second peek consumes nothing.
     */
    @Inject
    LlmCircuitBreakerRegistry breakerRegistry;

    /**
     * §6.12 adapter-metrics emission point for the inbound chokepoint
     * ({@code adapter.inbound.total} + inbound {@code adapter.message.bytes}).
     * The throwaway-registry initializer keeps the many plain-constructed
     * router tests working unmodified; CDI replaces it with the produced
     * deployment-wide bean.
     */
    @Inject
    AdapterMetrics adapterMetrics = AdapterMetrics.noop();

    /**
     * Bounded worker seam for the D35 interruptible dispatch class
     * (M1-634): step 6 of an interruptible inbound runs on a per-request
     * worker so the one-in-flight guard observes real contention and
     * {@code /stop} — dispatched inline like every other slash command —
     * stays reachable while a worker holds the LLM call. The
     * {@link InterruptibleDispatcher#direct()} initializer keeps the
     * hand-constructed plain-JUnit router tests synchronous and
     * unmodified; CDI replaces it with the managed bounded pool.
     */
    @Inject
    InterruptibleDispatcher interruptibleDispatcher = InterruptibleDispatcher.direct();

    /**
     * The interruptible-turn lifecycle registry (M1-638): the router
     * registers every interruptible dispatch here at submit time, before
     * the hop, so {@code /stop} can reach a turn no worker has picked up
     * yet. The throwaway-instance initializer keeps the hand-constructed
     * router tests working unmodified; CDI replaces it with the singleton
     * the handlers and CancellationService share.
     */
    @Inject
    InFlightTracker inFlightTracker = new InFlightTracker();

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

        // §6.12 observability, ahead of every intake gate: the inbound
        // counter is a transport-level traffic count, so rate-capped,
        // oversize, and banned messages are all counted. Walk the body's
        // UTF-8 byte length once here (Utf8.byteLength, alloc-free — a
        // hostile flood must not buy a getBytes() copy per message) and
        // reuse the same int for both the size summary and the M1-038
        // body-size cap below, so the recorded value and the cap decision
        // cannot drift and the body is not walked twice in the Provider.
        adapterMetrics.inbound(adapterName, msg.scope());
        int inboundByteLength = raw == null ? 0 : Utf8.byteLength(raw);
        if (raw != null) {
            adapterMetrics.messageBytes(adapterName, AdapterMetrics.Direction.INBOUND, inboundByteLength);
        }

        // Step 1.5 — transport-level rate cap. Fires FIRST per spec
        // §Authorization model step 1.5: over-cap inbound is dropped
        // silently with NO outbound (no ban reply, no invite-required
        // reply, no friendly error — including no size-cap reply).
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
        // downstream).
        boolean registered = registeredContactSet.isRegistered(adapterName, contactId);
        if (!rateCapBucket.tryAcquire(adapterName, contactId, registered)) {
            return;
        }

        // Body-size cap (M1-038, preserved). Fires AFTER rate cap so a
        // flood cannot leak MESSAGE_TOO_LARGE_REPLY outbound, and BEFORE
        // normalize so NFKC amplification cannot drive cost on
        // adversarial inputs.
        if (raw != null && inboundByteLength > maxInboundBodyBytes) {
            // Fires before any DB step by design (the hostile-flood path
            // must stay query-free), so the context language is still the
            // pre-resolution "en" default — see InboundContext#effectiveLanguage.
            sendReply(msg.scope(),
                    bundleLoader.get(BundleKeys.ERROR_ROUTER_MESSAGE_TOO_LARGE,
                            inboundContext.effectiveLanguage()),
                    adapterName);
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
        // most once (by the step-3.5 approval read) and carried forward
        // (group rate caps, anchor clear, chat scope resolution —
        // per-scope isolation, schema §Invariants).
        UUID dispatchScopeId;
        // The groups.id carried from the step-3.5 Approved outcome to the
        // step-4.1 membership write. Null until the approval gate yields
        // Approved; guaranteed non-null for any group reaching step 4.1
        // (pending/rejected/silent-drop all return at step 3.5).
        @Nullable UUID approvedGroupId = null;
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
                // No users row → no scope_preferences row, so the context
                // language is necessarily the "en" default here.
                String lang = inboundContext.effectiveLanguage();
                // SimpleX bootstrap-admin claim-token (D50): a first DM
                // whose body equals the configured admin-token establishes
                // the bootstrap admin (registers the sending connection's
                // contact + flips is_admin). Runs BEFORE the invite consume
                // so a token presentation is not counted as an invite
                // brute-force attempt; a NotClaimed outcome (wrong/used
                // token, no token configured, or any non-SimpleX adapter)
                // falls through to the invite path and gets the SAME fixed
                // reply an invalid invite would — no oracle on token
                // validity. The claim consumer owns all SimpleX specificity,
                // keeping this router adapter-agnostic.
                if (simpleXAdminClaim.claim(adapterName, contactId, normalized)
                        instanceof SimpleXAdminClaim.Claimed) {
                    // A claimed bootstrap admin is is_admin=true + vouched with
                    // no probation (D50), so it gets the distinct admin welcome
                    // — the shared probation welcomeReply would tell someone who
                    // just became the administrator they are on probation (M1-624).
                    sendReply(msg.scope(),
                            bundleLoader.get(BundleKeys.REPLY_WELCOME_ADMIN_CLAIM, lang),
                            adapterName);
                    return;
                }
                InviteCodeConsumer.Outcome outcome =
                        inviteCodeConsumer.consume(adapterName, contactId, normalized);
                switch (outcome) {
                    case InviteCodeConsumer.Accepted a ->
                            sendReply(msg.scope(), welcomeReply(lang), adapterName);
                    case InviteCodeConsumer.Rejected r ->
                            sendReply(msg.scope(), bundleLoader.get(BundleKeys.ERROR_INVITE_REQUIRED, lang), adapterName);
                    case InviteCodeConsumer.BruteForceThresholdBreached b ->
                            sendReply(msg.scope(), bundleLoader.get(BundleKeys.ERROR_INVITE_REQUIRED, lang), adapterName);
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

            // Effective scope language (D43), resolved as soon as the
            // scope's id is known so every later reply on this dispatch
            // renders in the language the scope chose. DM scope ids are
            // the user's UUID — available right here from the snapshot
            // (guaranteed present: DM-empty returned at step 2). Group
            // scope ids resolve at step 4.1; group replies before that
            // point (ban, approval gate, body caps, and the single-line
            // rejection with its confirm-cancellation) render the "en"
            // context default — correct for pending/rejected groups,
            // which can never have dispatched /lang, and a documented
            // limitation for the banned-user-in-group reply. ONE
            // scope_preferences SELECT per dispatch, on the dispatch
            // connection — handlers read the context instead of
            // querying per call site.
            if (msg.scope() instanceof ScopeRef.Dm) {
                inboundContext.setEffectiveLanguage(
                        lookupScopeLanguage(db, "dm", snapshot.get().id()));
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
                sendReply(msg.scope(),
                        bundleLoader.get(BundleKeys.ERROR_BAN_FIXED, inboundContext.effectiveLanguage()),
                        adapterName);
                return;
            }

            // Step 3.5 — D47 approval gate (M1-112). Group-scope inbound
            // from a registered (not preban), non-banned user routes
            // through GroupApprovalCheck.check, which consults the per-
            // group reply rate bucket then dispatches on approval_status.
            // Pending / rejected short-circuit with a fixed reply BEFORE
            // step 4.1 (auto-promote); approved falls through. Banned
            // users are already filtered at step 4 above and never reach
            // this block.
            if (msg.scope() instanceof ScopeRef.Group group && snapshot.isPresent()) {
                GroupApprovalCheck.Outcome outcome = groupApprovalCheck.check(
                        adapterName,
                        group.adapterGroupId(),
                        snapshot.get().id(),
                        ContactIds.redact(contactId));
                switch (outcome) {
                    case GroupApprovalCheck.Outcome.Approved a -> {
                        // Carry the groups.id the approval read resolved
                        // to step 4.1 — no second SELECT. Fall through to
                        // step 4.1 (auto-promote).
                        approvedGroupId = a.groupId();
                    }
                    case GroupApprovalCheck.Outcome.FixedReply f -> {
                        sendReply(msg.scope(),
                                bundleLoader.get(f.bundleKey(), inboundContext.effectiveLanguage()),
                                adapterName);
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
                // M1-774: this cap returns ahead of the step-4.5 sweep,
                // so it drains any pending confirm itself — an admin who
                // armed a destructive confirm and then sends an over-cap
                // body gets the cancellation BEFORE this rejection, not
                // a silent TTL-long survival. Same unconditional shape as
                // the M1-772 single-line drain (inline at the multiline
                // branch); this site calls the shared helper below. The
                // drain is an in-memory removal, so it does not violate
                // §Input length caps' no-DB-write constraint.
                drainPendingConfirm(snapshot.get().id(), msg.scope(), adapterName);
                sendReply(msg.scope(),
                        bundleLoader.get(BundleKeys.ERROR_CHAT_BODY_TOO_LARGE, inboundContext.effectiveLanguage()),
                        adapterName);
                return;
            }

            // Slash-command body cap (commands.md §Input length caps): a
            // slash body longer than infochat.command.body-cap gets the
            // fixed error.command.body_too_large reply and stops BEFORE the
            // parser (handleSlash) — same ordering rationale as the chat cap
            // above (after the authorization gates, before any DB write).
            if (normalized.startsWith("/") && normalized.length() > commandBodyCap) {
                // M1-774: same drain as the chat-mode cap above — the
                // pending confirm is cancelled and acknowledged before
                // this rejection reply.
                drainPendingConfirm(snapshot.get().id(), msg.scope(), adapterName);
                sendReply(msg.scope(),
                        bundleLoader.get(BundleKeys.ERROR_COMMAND_BODY_TOO_LARGE, inboundContext.effectiveLanguage()),
                        adapterName);
                return;
            }

            // Single-line rule (commands.md §Surface conventions), see
            // isMultiLineCommand. Placed beside the body cap above and
            // sharing its ordering rationale — after the authorization
            // gates, before handleSlash and before every DB write. That
            // position is what keeps this a router-only change: no handler
            // ever receives a multi-line body, so every handler keeps its
            // existing rawText contract (SummaryCommandHandler still
            // hashes the whole body it was sent; the endsWith(" confirm")
            // suffix tests and GetSourcesCommandHandler's re-serialization
            // are untouched).
            //
            // The step-4.5 confirm drain is carried across the return
            // rather than skipped (M1-772 redteam finding 1). A rejected
            // body dispatches nothing, so it can never redeem a pending
            // confirm: it is unconditionally "any other input" under spec
            // §Surface conventions, INCLUDING the shapes isConfirmShape
            // would accept on one line ("/ban x\nnote confirm" both starts
            // with the prefix and ends with " confirm"). Draining
            // unconditionally is therefore both simpler than mirroring the
            // step-4.5 predicate and strictly safer — it keeps the armed
            // window on the broad-blast-radius admin primitives
            // self-closing for a body a user can type by accident.
            //
            // takeAny is an in-memory map removal, which is why it may
            // precede the parser at all; the step-4.6 anchor clear is a DB
            // write and is deliberately NOT hoisted with it, matching the
            // body-cap branch above. A surviving /retry anchor only
            // re-rolls the same user's own last summary in the same scope.
            if (isMultiLineCommand(normalized)) {
                confirmStateService.takeAny(snapshot.get().id(), msg.scope()).ifPresent(cancelled ->
                        sendReply(msg.scope(),
                                MessageFormat.format(
                                        bundleLoader.get(BundleKeys.REPLY_CONFIRM_CANCELLED,
                                                inboundContext.effectiveLanguage()),
                                        cancelled.commandName()),
                                adapterName));
                sendReply(msg.scope(),
                        bundleLoader.get(BundleKeys.ERROR_COMMAND_MULTILINE, inboundContext.effectiveLanguage()),
                        adapterName);
                return;
            }

            // Step 4.1 — auto-promote + membership (M1-079c). Placed
            // AFTER the ban check (step 4) so banned users never get a
            // membership row written. The groups-row id is no longer
            // re-read here: it is the id the step-3.5 approval read
            // already resolved, carried forward as approvedGroupId
            // (guaranteed non-null for any group reaching this point —
            // pending/rejected and the removed-or-bucket silent drop all
            // returned at step 3.5).
            //
            // Race trade (acceptance item 5): dropping the former
            // step-4.1 re-read — which filtered removed_at IS NULL —
            // narrows a window. A group removed BETWEEN the step-3.5
            // approval read and this membership write now passes (the
            // approval read is authoritative), so a benign membership
            // INSERT for a just-removed group is tolerated rather than
            // silently dropped. A group that was ALREADY removed at the
            // approval read still drops silently: GroupApprovalCheck
            // .dispatchByStatus maps approved + removed_at to SilentDrop,
            // preserving the timing-oracle protection the old
            // lookupGroupId removed_at filter provided.
            //
            // For every non-banned group-scope sender, attempt
            // auto-promote first (INSERT with is_group_admin=true ON
            // CONFLICT DO NOTHING), then ensure a non-admin membership
            // row exists.
            if (msg.scope() instanceof ScopeRef.Group) {
                dispatchScopeId = Objects.requireNonNull(approvedGroupId);
                // Group counterpart of the DM resolution above: the
                // group's scope id exists from this point on, so every
                // later reply on this dispatch renders in the group's
                // chosen language.
                inboundContext.setEffectiveLanguage(
                        lookupScopeLanguage(db, "group", dispatchScopeId));
                UUID senderId = snapshot.get().id();
                groupAutoPromoteService.tryAutoPromote(dispatchScopeId, senderId, adapterName, contactId);
                ensureGroupMembership(db, dispatchScopeId, senderId);
            } else {
                dispatchScopeId = snapshot.get().id();
            }

            // Step 5 — slow-start probation gate (M1-045) per spec
            // §Slow-start tier + §Authorization model step 5. A probation
            // user invoking a non-allowed command receives the
            // error.probation.blocked reply (with the {0} time-until-
            // unlock interpolated from the snapshot's probation_until); a
            // probation user invoking an allowed command falls through to
            // the rest of the pipeline; a just-graduated user (probation_until
            // set but already past) gets the opportunistic clearIfPromoted
            // on the way — the NULL steady state issues no UPDATE at all.
            //
            // probation_until is served from the step-1 snapshot (M1-364):
            // no inProbation() SELECT on the hot path, no probationExpiry()
            // SELECT on the blocked path. The step-1→step-5 TOCTOU is the
            // same microsecond window the is_banned check documents above;
            // the probation timer is hours-scale, so a graduation landing
            // inside that window costs at most this one in-flight message.
            //
            // Invariant: snapshot is always present here — DM-empty
            // short-circuited at step 2's invite-consume; group senders
            // with no row (or 'preban') were silently dropped at step 3.
            // The defensive isPresent guard removed by M1-045 redteam-fix.
            UserSnapshot probationActor = snapshot.get();
            Instant probationNow = clock.instant();
            String commandName = commandNameOf(normalized);
            if (probationActor.inProbation(probationNow)) {
                if (!commandPermissions.allowedDuringProbation(commandName)) {
                    // M1-774: this block returns ahead of the step-4.5 sweep
                    // and a probation user CAN be holding a pending confirm —
                    // /forget is both confirm-arming and probation-allowed,
                    // so the arm succeeds and any later blocked input (a
                    // chat-mode body is blocked, commandNameOf yields
                    // "chat-mode") short-circuits here. Drain it, same
                    // unconditional shape as the two body caps: the blocked
                    // body dispatches nothing, so it can never redeem the
                    // armed payload, and this path already replies, so the
                    // acknowledgement costs no new outbound permission.
                    drainPendingConfirm(probationActor.id(), msg.scope(), adapterName);
                    // {0} = time until unlock; {1} = the canonical probation
                    // command list (same source /help renders), so the
                    // rejection can no longer omit an allowed command (M1-590).
                    String body = MessageFormat.format(
                            bundleLoader.get(BundleKeys.ERROR_PROBATION_BLOCKED, inboundContext.effectiveLanguage()),
                            formatTimeUntilUnlock(probationNow, probationActor.probationUntil()),
                            commandPermissions.renderProbationCommandList());
                    sendReply(msg.scope(), body, adapterName);
                    return;
                }
            } else if (probationActor.probationUntil() != null) {
                // Lazy clear: probation_until is set but already elapsed
                // (inProbation(now) was false with a non-null column — the
                // just-graduated case). Nulls the column on this first
                // post-graduation inbound; idempotent thereafter (WHERE
                // matches zero rows). Skipped entirely for the NULL steady
                // state, removing the per-inbound UPDATE that matched zero
                // rows in the common case (the M1-364 finding).
                probationCheck.clearIfPromoted(probationActor.id());
            }

            // Early returns ahead of this sweep and the confirm drain
            // (M1-774 enumeration — the one place the per-path decision
            // is written down). The rule the per-path answers follow: a
            // rejection drains iff it has resolved an actor id AND is
            // free to emit the acknowledgement. Every exception below is
            // one of those two capabilities missing — deliberately NOT
            // the argument that a surviving entry can never be redeemed,
            // which this code cannot assert: ban, group approval and
            // probation are all states that can change inside the 60s
            // TTL, so "no later input can redeem it" is false in general.
            //
            // 1. Step 1.5 rate-cap drop — no drain: fires before the
            //    users-row read and must stay query-free AND outbound-
            //    free (M1-044e: a hostile flood must not drive replies;
            //    a cancellation would be amplification). A pending
            //    entry survives here only until its TTL.
            // 2. Transport byte cap (M1-038) — no drain: same pre-lookup
            //    hostile-flood path, one fixed reply only.
            // 3. Normalize-empty — no drain: pre-lookup silent return;
            //    no actor id exists without a read.
            // 4. Step 2 DM invite gate — vacuous: no users row means no
            //    actor id, and confirms are keyed by users.id, so no
            //    entry can exist.
            // 5. Step 3 group unregistered/preban drop — vacuous:
            //    unregistered has no users row, and a 'preban' row is
            //    minted by banning a contact that never registered, so
            //    it never held a users.id under which to arm one.
            // 6. Step 4 ban reply — no drain: a ban landing mid-TTL does
            //    leave an entry, but spec §User ban gives a banned user
            //    ONE fixed reply per inbound message, so a cancellation
            //    ack here is an outbound this path may not spend. The
            //    entry expires by TTL (or is redeemable again if the
            //    unban lands inside it — the drain is not what bounds
            //    that; the TTL is).
            // 7. Step 3.5 approval short-circuit — no drain: an entry
            //    armed while the group was approved can survive a mid-
            //    TTL status regression, bucket exhaustion, or removal.
            //    SilentDrop must emit no outbound by contract, and the
            //    pending/rejected FixedReply is the single reply D47
            //    invisibility allows — a second one would disclose that
            //    this member holds a pending confirm to a group the bot
            //    has not accepted.
            // 8. Chat body cap — DRAINS (this ticket); see its branch.
            // 9. Command body cap — DRAINS (this ticket); see its branch.
            // 10. Step 5 probation block — DRAINS (this ticket); see its
            //    branch. Not vacuous and not inert: /forget is BOTH
            //    confirm-arming and probation-allowed, so a probation
            //    user can hold an entry, any blocked input (including
            //    every chat-mode body) returns here ahead of this sweep,
            //    and a later `/forget confirm` is itself allowed — so
            //    the entry really is redeemable across the gap.
            // The M1-772 single-line rejection is the reference drain
            // (inline at the multiline branch, byte-for-byte preserved).
            //
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
                        bundleLoader.get(BundleKeys.REPLY_CONFIRM_CANCELLED, inboundContext.effectiveLanguage()),
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

        // Step 6 — parse + dispatch (slash-command resolver or chat-mode
        // fallback), then deliver — extracted into runDispatchStage so the
        // interruptible class can run it on a worker (below) while every
        // other body runs it inline right here.
        //
        // M1-634 fork: the D35 interruptible class (chat-mode, user-issued
        // /summary, user-issued /retry re-roll) is handed to the bounded
        // InterruptibleDispatcher so the transport's single dispatch thread
        // is freed the moment intake ends. That concurrency is what makes
        // the spec'd behaviours reachable over live transports: a second
        // same-(user, scope) request is admitted WHILE the first holds its
        // InFlightTracker slot (reject-with-guidance, commands.md §Surface
        // conventions), and /stop — non-interruptible, dispatched inline —
        // can cancel a worker-held LLM call "immediately" (D35). Everything
        // non-interruptible keeps the transport thread's arrival order.
        //
        // The closure captures plain VALUES only (senderContactId /
        // effectiveLanguage are read from the intake context HERE, before
        // submit): this request context is destroyed when onMessage
        // returns, and a worker-side read of it would be a cross-user
        // isolation leak. The worker runs the stage under its own fresh,
        // seeded context (see InterruptibleDispatcher.runStage).
        UUID actorId = snapshot.get().id();
        ScopeRef scope = msg.scope();
        UUID stageScopeId = dispatchScopeId;
        if (isInterruptible(normalized)) {
            // M1-636: per-user cross-scope concurrency cap, checked FIRST —
            // before the turn is registered, before the M1-635 placeholder,
            // before any pool slot is spent — so a rejection consumes
            // nothing: no registry entry, no LlmRateCap token (drawn only in
            // the handlers, downstream), no in-flight slot, no outbound
            // placeholder that would then have to be walked back. Same
            // check-order doctrine as the slot-before-bucket order at
            // SummaryCommandHandler.tryAcquire. The count-then-register pair
            // is race-free per sender: a user's identity is adapter-scoped,
            // so all their submits arrive on that adapter's single dispatch
            // thread; a concurrent worker-side release only makes the count
            // conservatively high for this one check. The per-SCOPE guard's
            // reject keeps its worker-side timing and text untouched — this
            // coarser bound rejects only a sender already at their
            // cross-scope ceiling.
            if (inFlightTracker.countNonTerminalTurns(actorId)
                    >= interruptibleDispatcher.perUserCap()) {
                sendReply(scope,
                        bundleLoader.get(BundleKeys.ERROR_CHAT_PER_USER_CAP,
                                inboundContext.effectiveLanguage()),
                        adapterName);
                return;
            }
            // M1-638: one lifecycle object per interruptible dispatch, born
            // at submit. The PURPOSE-MINTED id below is the turn's identity
            // in InFlightTracker for its whole life — registered before the
            // hop so /stop can cancel a turn no worker has picked up yet,
            // adopted by the handler's tryAcquire on the worker (the
            // QUEUED→RUNNING transition), and reused as the operationId that
            // keys the turn's progress lifecycle. It is never this context's
            // own id, so the M1-634 hop contract stands: the worker reads
            // nothing of the submitting context — the id crosses the hop as
            // one more plain captured String.
            String turnId = UUID.randomUUID().toString();
            String turnScopeKind = chatModeScopeKindOf(scope);
            // D79: the reply mode resolved at intake crosses the hop as one
            // more plain captured value (the M1-634 pattern); the worker's
            // fresh context is re-seeded with it in runSeededDispatchStage.
            ChatReplyMode resolvedReplyMode = inboundContext.replyMode();
            inFlightTracker.registerQueued(actorId, turnScopeKind, stageScopeId, turnId);
            // M1-635: when every worker is occupied the submit below will
            // queue, and nothing would reach the sender until a worker frees
            // — a silence that can span several 90s LLM turns and reads as a
            // broken bot. Publish the STARTED placeholder HERE, on the
            // transport thread, under the turn's id; the worker seeds it
            // into its fresh context before any dispatch work, so its own
            // stage publishes and terminal land on this placeholder: one
            // lifecycle, never a second bubble. A non-queued turn gets no
            // acknowledgement (work starts immediately) — its Reply bodies
            // stay plain router sends, exactly as before the offload.
            if (interruptibleDispatcher.wouldQueue()) {
                progressNotifier.publishQueuedPlaceholder(turnId, scope);
                interruptibleDispatcher.dispatch(
                        adapterName,
                        inboundContext.senderContactId(),
                        inboundContext.effectiveLanguage(),
                        () -> runSeededDispatchStage(
                                turnId, scope, normalized, actorId, stageScopeId,
                                resolvedReplyMode,
                                body -> progressNotifier.complete(scope, body)));
                return;
            }
            interruptibleDispatcher.dispatch(
                    adapterName,
                    inboundContext.senderContactId(),
                    inboundContext.effectiveLanguage(),
                    () -> runSeededDispatchStage(
                            turnId, scope, normalized, actorId, stageScopeId,
                            resolvedReplyMode,
                            body -> sendReply(scope, body, adapterName)));
            return;
        }
        runDispatchStage(scope, normalized, actorId, stageScopeId, adapterName);
    }

    /**
     * The step-6 stage: dispatch a normalized inbound and deliver any
     * {@link DispatchResult.Reply} body. A
     * {@link DispatchResult.AlreadyDelivered} means a self-delivering
     * handler already shipped its reply through the ProgressNotifier —
     * the router performs NO send for that invocation (no double-send),
     * and the chat path has already run its delivery-gated post-delivery
     * commit inside {@link #dispatchChatSelfDelivering} (M1-607). No
     * Reply path produces a pending chat commit. Runs inline on the
     * transport thread for non-interruptible bodies and on an
     * {@link InterruptibleDispatcher} worker for the D35 interruptible
     * class (M1-634).
     */
    private void runDispatchStage(ScopeRef scope, String normalized, UUID actorId,
                                  UUID dispatchScopeId, String adapterName) {
        DispatchResult dispatchResult =
                dispatchSlashOrChat(scope, normalized, actorId, dispatchScopeId);
        if (dispatchResult instanceof DispatchResult.Reply reply) {
            sendReply(scope, reply.body(), adapterName);
        }
    }

    /**
     * Interruptible step-6 stage (M1-635/M1-638): every interruptible
     * dispatch runs through here, seeded with the turn id minted at submit.
     * The worker (a) seeds that id into its fresh context BEFORE any
     * dispatch work — {@link StageProgressNotifier} keys per-operation
     * state by it (M1-611), which is what makes the self-delivering
     * handlers' own publishes and terminals land on the M1-635
     * acknowledgement instead of minting a second placeholder, and
     * {@link InFlightTracker#tryAcquire} adopts the submit-registered turn
     * by it — and (b) delivers a plain {@link DispatchResult.Reply} body
     * (the in-flight reject, the rate-cap replies, {@code /retry} guidance,
     * the internal-error reply) through {@code replySink}: the queued
     * variant reconciles it as a notifier terminal REPLACING the
     * acknowledgement placeholder (a separate send would strand "working on
     * it" forever alongside a second bubble, M1-607), while the non-queued
     * variant — no placeholder exists — keeps the plain router send.
     *
     * <p>The preamble skips a turn {@code /stop} cancelled while it sat in
     * the pool queue: no LLM work, no rate-cap draw, and the D35 stopped
     * terminal lands on the same shape as {@code dispatchChatSelfDelivering}'s
     * null-reply arm. {@code consumeIfCancelled} is exactly-once, so no
     * other path can also terminate this turn (the single-publisher
     * invariant acceptance-pinned by M1-638).</p>
     */
    private void runSeededDispatchStage(String operationId, ScopeRef scope, String normalized,
                                        UUID actorId, UUID dispatchScopeId,
                                        ChatReplyMode resolvedReplyMode,
                                        Consumer<String> replySink) {
        inboundContext.setOperationId(operationId);
        inboundContext.setReplyMode(resolvedReplyMode);
        String scopeKind = chatModeScopeKindOf(scope);
        try {
            if (inFlightTracker.consumeIfCancelled(actorId, scopeKind, dispatchScopeId,
                    operationId)) {
                progressNotifier.complete(scope,
                        bundleLoader.get(BundleKeys.PROGRESS_STOPPED,
                                inboundContext.effectiveLanguage()));
                return;
            }
            DispatchResult dispatchResult =
                    dispatchSlashOrChat(scope, normalized, actorId, dispatchScopeId);
            if (dispatchResult instanceof DispatchResult.Reply reply) {
                replySink.accept(reply.body());
            }
        } finally {
            // Leak guard: a turn that terminated without being adopted — a
            // pre-handler Reply arm, an in-flight reject, an escaped
            // exception — must not leave a QUEUED registry entry behind
            // (M1-636's permanent-lockout hazard). No-op for adopted turns.
            inFlightTracker.discard(actorId, scopeKind, dispatchScopeId, operationId);
        }
    }

    /**
     * Is {@code normalized} in the D35 interruptible dispatch class —
     * chat-mode (non-slash), user-issued {@code /summary},
     * {@code /image}, or user-issued {@code /retry} WITHOUT
     * {@code --digest}? Only these
     * are offloaded to the worker seam; a misclassification here is an
     * ordering bug (an offloaded mutating command would lose the
     * transport thread's arrival order), so the predicate must stay
     * exact. The {@code --digest} test deliberately mirrors
     * {@code RetryCommandHandler.hasFlag}'s whitespace-token equality —
     * {@code /retry --digest} is D35 non-interruptible and must keep
     * its inline ordering, so the two classifications may not drift.
     *
     * <p>Package-private (not {@code private}) so
     * {@code InboundRouterInterruptibleClassificationTest} pins this
     * D35/D61 boundary directly as a table (M1-640): the inline
     * self-serialization the D61 per-user-cap exclusion rests on is a
     * property of this predicate's answer, so a refactor that moved
     * {@code /retry --digest} onto the offloaded path fails that test.
     */
    static boolean isInterruptible(String normalized) {
        if (!normalized.startsWith("/")) {
            return true;
        }
        String commandName = commandNameOf(normalized);
        if ("summary".equals(commandName) || "image".equals(commandName)) {
            return true;
        }
        if (!"retry".equals(commandName)) {
            return false;
        }
        for (String part : normalized.split("\\s+")) {
            if (part.equals("--digest")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Step-6 dispatch: route a normalized inbound to the slash-command
     * resolver or the chat-mode agent, applying the per-group command and
     * LLM rate caps, wrapped in the dispatch-failure catch. Returns a
     * {@link DispatchResult} — {@link DispatchResult.Reply} carrying a body
     * for the router to send, or {@link DispatchResult.AlreadyDelivered}
     * when a self-delivering slash/chat handler already shipped its reply
     * via the ProgressNotifier (the router then performs no send).
     *
     * <p>Runs AFTER the dispatch connection is closed — no router-held pool
     * connection survives into the LLM call; command handlers and the chat
     * agent borrow (and release) their own.</p>
     */
    private DispatchResult dispatchSlashOrChat(ScopeRef scope, String normalized,
                                               UUID actorId, UUID dispatchScopeId) {
        try {
            if (normalized.startsWith("/")) {
                // M1-705 cheap-command bucket (design §4.9, spec §Rate
                // limiting "Parser-only + DB-read paginated commands"):
                // the eight spec-named bean commands draw a per-(adapter,
                // contactId) bucket distinct from the step-1.5 transport
                // bucket. Placed BEFORE the per-group command bucket,
                // mirroring the per-user-before-per-group order of the
                // LLM path below. By position it sits BEHIND step 1.5, so
                // its overflow can afford the friendly reject naming the
                // retry delay ({0} = seconds, computed from bucket state
                // — never a hardcoded constant) instead of the transport
                // bucket's silent drop, without reintroducing the
                // amplification that silence was built to prevent.
                boolean cheapCharged = CHEAP_COMMANDS.contains(commandNameOf(normalized));
                if (cheapCharged
                        && !rateCapBucket.tryAcquireCheapCommand(
                                inboundContext.adapterName(), inboundContext.senderContactId())) {
                    return new DispatchResult.Reply(MessageFormat.format(
                            bundleLoader.get(BundleKeys.ERROR_COMMAND_RATE_LIMIT,
                                    inboundContext.effectiveLanguage()),
                            Long.toString(rateCapBucket.cheapCommandRetryAfterSeconds(
                                    inboundContext.adapterName(), inboundContext.senderContactId()))));
                }
                // Per-group command rate cap (D47) per spec §Rate limiting:
                // an aggregate sub-bucket keyed on groups.id bounding slash-
                // command dispatch volume from all members. "Approved only"
                // holds by position (pending/rejected stopped at step 3.5);
                // DM slash dispatch never consults the group bucket. Overflow
                // sends the fixed group.command_rate_limit reply per design
                // §4.9 — unlike the reply bucket's silent drop. The bucket
                // keys on the carried dispatchScopeId — the groups.id resolved
                // once at step 4.1.
                if (scope instanceof ScopeRef.Group
                        && !rateCapBucket.tryAcquireGroupCommand(dispatchScopeId)) {
                    // Refund the cheap token charged above — the group
                    // backstop must not drain the sender's personal budget
                    // on a fixed reply (the same discipline as the
                    // group-LLM refund on the chat path below).
                    if (cheapCharged) {
                        rateCapBucket.refundCheapCommand(
                                inboundContext.adapterName(), inboundContext.senderContactId());
                    }
                    return new DispatchResult.Reply(
                            bundleLoader.get(BundleKeys.GROUP_COMMAND_RATE_LIMIT, inboundContext.effectiveLanguage()));
                }
                // A null handleSlash return signals the handler already
                // delivered its reply via the ProgressNotifier — map it to
                // AlreadyDelivered so the router skips the send (no
                // double-send for a placeholder→finalize handler).
                @Nullable String slashBody = handleSlash(scope, normalized);
                return slashBody == null
                        ? new DispatchResult.AlreadyDelivered()
                        : new DispatchResult.Reply(slashBody);
            }

            // Chat-mode dispatch: enforce LLM rate cap, then delegate to the
            // chat agent (the chat-mode body cap already fired before step
            // 4.1 — see the intake-step order above). scope_kind is "dm" or
            // "group"; the scope id is the carried dispatchScopeId — the
            // user's UUID for DM, the group's UUID for group scope (per-scope
            // isolation, schema §Invariants).
            if (!llmRateCap.tryAcquire(actorId)) {
                return new DispatchResult.Reply(
                        bundleLoader.get(BundleKeys.ERROR_CHAT_LLM_RATE_CAP, inboundContext.effectiveLanguage()));
            }
            String scopeKind = chatModeScopeKindOf(scope);
            // Per-group LLM backstop (D47, M1-222) per spec §Rate limiting:
            // the per-user cap above fires first; this aggregate sub-bucket
            // keyed on groups.id bounds LLM cost for groups with many active
            // members. Group scope only — the DM case never consults the
            // group bucket. "Approved only" holds by position (pending/
            // rejected stopped at step 3.5). Overflow sends the fixed
            // group.llm_rate_limit reply per design §4.9 — unlike the reply
            // bucket's silent drop. Periodic digests are system-initiated and
            // never reach this path.
            if (scope instanceof ScopeRef.Group
                    && !rateCapBucket.tryAcquireGroupLlm(dispatchScopeId)) {
                // Group-cap rejection refunds the per-user token acquired
                // above — the backstop must not drain the sender's personal
                // budget on fixed replies.
                llmRateCap.refund(actorId);
                return new DispatchResult.Reply(
                        bundleLoader.get(BundleKeys.GROUP_LLM_RATE_LIMIT, inboundContext.effectiveLanguage()));
            }
            // The chat turn self-delivers through the ProgressNotifier
            // (placeholder → stage edits → finalized reply, M1-607), so
            // every dispatched-turn outcome — reply, /stop cancellation,
            // failure — is AlreadyDelivered; only the pre-dispatch fixed
            // errors above remain plain router sends.
            return dispatchChatSelfDelivering(scope, actorId, scopeKind, dispatchScopeId, normalized);
        } catch (RuntimeException e) {
            SafeLog.error(log,
                    "InboundRouter dispatch failed for scope="
                            + ContactIds.redact(scopeIdOf(scope)),
                    e);
            // The exception's own message is NEVER interpolated here —
            // a fixed bundle reply sidesteps M1-020's sanitization concern.
            return new DispatchResult.Reply(
                    bundleLoader.get(BundleKeys.ERROR_INTERNAL, inboundContext.effectiveLanguage()));
        }
    }

    /**
     * Chat-mode dispatch with D31 progress publication (M1-607): the turn
     * self-delivers through the {@link StageProgressNotifier} — placeholder
     * ({@code STARTED}), coalesced stage edits, then a terminal that
     * REPLACES the placeholder — mirroring {@code SummaryCommandHandler}.
     * The stage sequence brackets the compute inside {@link #dispatchChat}:
     * {@code RETRIEVING} is advertised only when the M1-589 pre-fetch will
     * actually run (skipped while the chat breaker is OPEN, M1-606 — the
     * same read-only {@code wouldShortCircuit} peek ChatAgent gates on; the
     * microsecond TOCTOU between the two peeks costs at most one cosmetic
     * stage label), and {@code GENERATING} covers the LLM tool loop. Stage
     * strings are enum-keyed bundle lookups — user input is NEVER
     * interpolated (messaging.md §Progress notifications).
     *
     * <p>Terminals: a computed reply (including ChatAgent's friendly error
     * strings) finalizes via {@link StageProgressNotifier#completeDelivered},
     * whose delivery outcome gates the deferred persist — placeholder and
     * finalize wrap compute+deliver, never the persist, so a permanently-
     * failed delivery leaves the window "as if the message was never
     * generated" (messaging.md §Failure handling). A {@code null} reply is
     * a /stop-cancelled turn: the placeholder is finalized with the D35
     * stopped terminal — never the stale answer — while the /stop
     * dispatch's own acknowledgement remains a separate message. A
     * RuntimeException degrades to {@link StageProgressNotifier#fail}
     * (localized failure terminal on the placeholder), mirroring the
     * SummaryCommandHandler failure arm.</p>
     */
    private DispatchResult dispatchChatSelfDelivering(ScopeRef scope, UUID actorId,
                                                      String scopeKind, UUID scopeId,
                                                      String normalized) {
        try {
            progressNotifier.publish(scope, ProgressStage.STARTED);
            if (!breakerRegistry.wouldShortCircuit(ModelTask.CHAT_AGENT)) {
                progressNotifier.publish(scope, ProgressStage.RETRIEVING);
            }
            progressNotifier.publish(scope, ProgressStage.GENERATING);
            @Nullable String reply = dispatchChat(actorId, scopeKind, scopeId, normalized);
            if (reply == null) {
                // /stop cancelled the turn (the only null-reply source):
                // terminal stopped text per D35 — complete(), never fail(),
                // and never the stale answer.
                progressNotifier.complete(scope,
                        bundleLoader.get(BundleKeys.PROGRESS_STOPPED,
                                inboundContext.effectiveLanguage()));
                return new DispatchResult.AlreadyDelivered();
            }
            progressNotifier.publish(scope, ProgressStage.FINALIZING);
            boolean delivered = progressNotifier.completeDelivered(scope, reply);
            runPostDeliveryCommit(scope, delivered);
            return new DispatchResult.AlreadyDelivered();
        } catch (RuntimeException e) {
            // Mirror SummaryCommandHandler's failure arm: the notifier's
            // localized failure terminal replaces the placeholder — never a
            // second plain reply on top of a dangling one. The exception's
            // message is not interpolated into any outbound (M1-020).
            SafeLog.error(log,
                    "InboundRouter chat dispatch failed for scope="
                            + ContactIds.redact(scopeIdOf(scope)),
                    e);
            progressNotifier.fail(scope);
            return new DispatchResult.AlreadyDelivered();
        }
    }

    /**
     * Post-delivery chat-turn persistence (spec messaging.md §Failure
     * handling). {@link InboundContext#takePendingChatCommit()} is non-null
     * only when this dispatch computed a chat reply; it is read (and cleared)
     * unconditionally so a permanent failure does not strand it. Persist +
     * auto-compress run ONLY when the reply was delivered ({@code delivered}
     * — the terminal finalize's outcome): on permanent failure the commit is
     * dropped, so neither turn is written.
     */
    private void runPostDeliveryCommit(ScopeRef scope, boolean delivered) {
        ChatAgent.PendingCommit pendingChatCommit = inboundContext.takePendingChatCommit();
        if (!delivered || pendingChatCommit == null) {
            return;
        }
        String adapterName = inboundContext.adapterName();
        try {
            Optional<String> autoCompressNotice = pendingChatCommit.commit();
            // Auto-compress fires between turns; its notice now rides a second
            // outbound because persistence runs AFTER the reply is delivered
            // (it can no longer be appended to the already-sent reply).
            autoCompressNotice.ifPresent(
                    notice -> sendReply(scope, notice, adapterName));
        } catch (RuntimeException e) {
            // Send succeeded but post-delivery persistence failed. The user
            // already received the reply, so a resend would duplicate it —
            // log and move on; do NOT re-enter the send path.
            SafeLog.error(log,
                    "Chat-turn persistence failed after delivery for scope="
                            + ContactIds.redact(scopeIdOf(scope)),
                    e);
        }
    }

    /**
     * Outcome of the step-6 {@link #dispatchSlashOrChat} call: either a
     * {@link Reply} body the router must send, or {@link AlreadyDelivered}
     * when a self-delivering handler already shipped its reply via the
     * ProgressNotifier and the router must NOT send. Makes the former
     * {@code @Nullable String body} null-sentinel an explicit type so the
     * skip-send branch is a pattern match, not a null check.
     */
    private sealed interface DispatchResult {
        record Reply(String body) implements DispatchResult {}

        record AlreadyDelivered() implements DispatchResult {}
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
                Timestamp probationUntil = rs.getTimestamp("probation_until");
                return Optional.of(new UserSnapshot(
                        rs.getObject("id", UUID.class),
                        rs.getString("registration_state"),
                        rs.getBoolean("is_banned"),
                        probationUntil == null ? null : probationUntil.toInstant()));
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
     * unregistered/preban drop predicate), {@code is_banned}
     * (step 4 ban check), and {@code probation_until} (step 5
     * slow-start gate — nullable; NULL means the user graduated or
     * was never in probation). Package-private so test subclasses can
     * construct instances when overriding {@link #lookupUser}.
     */
    record UserSnapshot(UUID id, String registrationState, boolean isBanned,
                        @Nullable Instant probationUntil) {

        /**
         * @return {@code true} iff {@code probation_until} is set and
         *         still in the future relative to {@code now} — the
         *         snapshot-local equivalent of the former
         *         {@code ProbationCheck.inProbation} SELECT
         *         ({@code probation_until IS NOT NULL AND probation_until
         *         > NOW()}), letting step 5 decide probation from the
         *         single per-dispatch users-row read.
         */
        boolean inProbation(Instant now) {
            return probationUntil != null && probationUntil.isAfter(now);
        }
    }

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

    /**
     * Deliver a reply through the outbound chokepoint. Returns the
     * {@link MessageHandle} on success, or {@code null} when no adapter is
     * bound for {@code adapterName} or the chokepoint aborted the send
     * (permanent failure / exhausted retry). The chat path uses the return
     * value to gate post-delivery chat-turn persistence (spec
     * {@code messaging.md} §Failure handling); other reply paths ignore it.
     */
    private @Nullable MessageHandle sendReply(ScopeRef scope, String body, String adapterName) {
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
            return null;
        }
        // The chokepoint owns retry (TRANSIENT) and abort (PERMANENT /
        // exhausted). A null handle means the reply was aborted; log it
        // here with the scope id REDACTED (never the full contact id) and
        // drop it — there is no recovery on the reply path.
        MessageHandle handle = outboundDelivery.deliver(target, new OutboundMessage(
                scope,
                body,
                Instant.now(),
                UUID.randomUUID().toString()));
        if (handle == null) {
            log.error("InboundRouter reply aborted for adapter={} scope={}",
                    target.name(), ContactIds.redact(scopeIdOf(scope)));
        }
        return handle;
    }

    /**
     * Is {@code normalized} a slash body carrying content past its first
     * line? Per {@code docs/spec/commands.md} §Surface conventions a
     * command and all its arguments occupy ONE line; such a body is
     * rejected unparsed.
     *
     * <p><b>Why the rule exists.</b> The command word is pinned to
     * position 0 by the {@code startsWith("/")} classification, but every
     * argument parser tokenizes with {@code split("\\s+")}, and Java's
     * {@code \s} matches {@code \n}. Arguments were therefore gathered
     * across every line of the body while the command word was not — so
     * {@code /list-sources} with {@code --all} on a later line reached
     * the admin-only deployment-wide listing branch (§Source URL
     * visibility), and {@code /retry} with {@code --digest} reached the
     * admin digest-retry branch ({@code RetryCommandHandler.hasFlag}
     * scans every token of the body). Neither was an authorization
     * bypass — both branches re-check the actor and refuse a caller who
     * lacks the tier — but an ADMIN who pasted a note under a bare
     * {@code /list-sources} got the deployment-wide enumeration, and its
     * privileged-read audit row, from a command they did not type.
     * Rejecting at this funnel closes the class for every command at
     * once instead of per parser. (M1-772.)
     *
     * <p><b>Testing for {@code \n} alone would not close it.</b>
     * {@link #normalize} splits on {@code \n} only, and
     * {@code appendNormalized} preserves every other line terminator —
     * none of {@code \r}, {@code U+000B}, {@code \f}, {@code U+0085},
     * {@code U+2028}, {@code U+2029} is a bidi control or a zero-width
     * codepoint, and NFKC folds none of them. A bare {@code \r} is
     * therefore invisible to a newline test while still being a token
     * separator for the handlers' {@code split("\\s+")}, so
     * {@code /list-sources\r--all} would have dispatched the admin
     * listing with the body never appearing to hold a second line. The
     * predicate matches the whole {@code \R} set for that reason: the
     * rule is "no line boundary at all", not "no content past the first
     * newline". The three that {@code \s} misses ({@code U+0085},
     * {@code U+2028}, {@code U+2029}) cannot split a token today and are
     * rejected anyway — they are line boundaries to the READER, and a
     * rule about what occupies one line has to read them the way the
     * reader does.
     *
     * <p>A trailing terminator is rejected rather than tolerated. The
     * common ones are already gone — {@link #normalize} ends with
     * {@code trim()}, which strips everything at or below {@code U+0020}
     * — so this only bites on a trailing {@code U+0085} / {@code U+2028}
     * / {@code U+2029}, where rejecting is the safe direction and keeps
     * the rule stateable in one sentence.
     *
     * <p>Ordinary in-line whitespace stays legal: {@code \t} and the
     * space are not line boundaries, so {@code /list-sources\t--all}
     * dispatches exactly as before. It is one line, and a closed-list
     * entry spanning it is matched by the sanitizer's within-line scan.
     *
     * <p>Chat bodies are deliberately unaffected: {@link #normalize}
     * preserves fenced code blocks verbatim precisely so chat mode can
     * carry them, and this predicate is false for anything not beginning
     * with a slash.
     *
     * <p>Package-private + static so
     * {@code InboundRouterCommandCapTest} can exercise the predicate
     * directly without booting Quarkus, the same as {@link #normalize}
     * and {@link #isInterruptible}.
     */
    static boolean isMultiLineCommand(String normalized) {
        if (!normalized.startsWith("/")) {
            return false;
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (isLineBoundary(normalized.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The {@code \R} set as a char test — every codepoint Java's
     * {@code \R} treats as a line boundary. All are BMP, so a
     * {@code charAt} scan cannot split one across a surrogate pair.
     */
    private static boolean isLineBoundary(char c) {
        // Written as numeric comparisons, not char literals: VT, NEL and
        // the two Unicode separators have no escape mnemonic, and a raw
        // control character embedded in the source is unreadable and easy
        // to corrupt in transit.
        return c == '\n'        // LF
                || c == '\r'    // CR
                || c == '\f'    // FF
                || c == 0x000B  // VT
                || c == 0x0085  // NEL
                || c == 0x2028  // LINE SEPARATOR
                || c == 0x2029; // PARAGRAPH SEPARATOR
    }

    /**
     * Does {@code normalized} look like the confirm-shape body for
     * {@code pending}? Per spec §Surface conventions: the canonical
     * confirm form is {@code "/" + sweepPrefix + " confirm"}, and a body
     * that retypes the command's identifying argument
     * ({@code "/" + sweepPrefix + " <args> confirm"}) is confirm-shape
     * only when {@code <args>} identifies the very action the pending
     * payload holds. Used by step 4.5 to decide cancel-vs-leave-alone —
     * the handler still performs the authoritative {@code takeMatching}
     * call on its own commandName key when dispatch reaches it.
     *
     * <p><b>Why the arguments are compared, not ignored (M1-775).</b>
     * Matching on prefix + suffix alone let {@code /ban bob confirm}
     * satisfy a pending {@code BanConfirm("alice")}: the sweep left the
     * entry armed and the handler popped the STORED payload, so a
     * destructive admin primitive ran against a target that appeared
     * nowhere in the message that triggered it. A mismatched segment is
     * now "any other input" — the sweep drains it and the user is told,
     * exactly as §Surface conventions says every non-confirming body is
     * treated.</p>
     *
     * <p>The comparison is on the segment <i>as a whole</i>, never
     * containment: {@code /ban bob alice confirm} must not redeem
     * {@code BanConfirm("alice")}. The segment is trimmed because
     * {@link #normalize} preserves interior whitespace runs, so
     * {@code /ban  alice  confirm} is the same intent as the single-
     * spaced form.</p>
     */
    private static boolean isConfirmShape(String normalized,
                                          ConfirmStateService.PendingConfirm pending) {
        String prefix = "/" + pending.sweepPrefix();
        // The canonical bare leg, tested FIRST: for this body the segment
        // below would be empty, and the slice's start index would run
        // past its end for any prefix whose own tail overlaps the
        // trailing token.
        if (normalized.equals(prefix + " confirm")) {
            return true;
        }
        if (!normalized.startsWith(prefix + " ")) {
            return false;
        }
        // Slice AFTER the prefix rather than off the whole body: the
        // trailing " confirm" must follow the retyped segment, not
        // straddle the prefix boundary.
        String afterPrefix = normalized.substring(prefix.length() + 1);
        if (!afterPrefix.endsWith(CONFIRM_TOKEN)) {
            return false;
        }
        String retypedArguments =
                afterPrefix.substring(0, afterPrefix.length() - CONFIRM_TOKEN.length()).trim();
        // An empty segment is the canonical form again (a doubled space
        // before the token) — the wire-form rule lives here, so payloads
        // only ever answer for a body that named something.
        return retypedArguments.isEmpty() || pending.matchesRetypedArguments(retypedArguments);
    }

    /**
     * Drain any pending confirmation for {@code (actorId, scope)} and
     * emit the cancellation acknowledgement, if one exists. The
     * unconditional shape — no {@link #isConfirmShape} consultation —
     * mirrors the M1-772 single-line drain: a body that is about to be
     * rejected dispatches nothing, so it can never redeem the armed
     * payload; it is the "any other input" that spec §Surface
     * conventions says closes the armed window, even when the body
     * superficially matches a confirm shape on one line. {@code takeAny}
     * is an in-memory map removal, which is why the drain may precede
     * the parser on paths that must not touch the DB (M1-774: the two
     * body caps). The step-4.6 anchor clear is a DB write and is
     * deliberately NOT hoisted with it.
     */
    private void drainPendingConfirm(UUID actorId, ScopeRef scope, String adapterName) {
        confirmStateService.takeAny(actorId, scope).ifPresent(cancelled ->
                sendReply(scope,
                        MessageFormat.format(
                                bundleLoader.get(BundleKeys.REPLY_CONFIRM_CANCELLED,
                                        inboundContext.effectiveLanguage()),
                                cancelled.commandName()),
                        adapterName));
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
        // becomes dispatchable with no new code.
        if (assetCommandFamilyOracle.isAssetCommand(commandName)) {
            return assetHandler.handle(commandName, scope, normalized).text();
        }
        // Intent-aware miss guidance (M1-647): route through the SAME resolver
        // /help <unknown> uses, so /mute and /help mute answer identically. The
        // help handler owns both the catalogue and the caller-tier filter, so
        // the suggestion is tier-safe by construction — this path cannot name a
        // command the caller may not see. Located among the handlers the router
        // already dispatches to rather than injected on its own: that keeps the
        // dependency where one already exists, and a router assembled without a
        // help handler then has no suggestion surface at all and simply keeps
        // the flat reply below. The method is public because this call crosses
        // the CDI client proxy, which delegates public methods only.
        for (CommandHandler handler : commandHandlers) {
            if (handler instanceof HelpCommandHandler helpCommandHandler) {
                @Nullable String suggestion = helpCommandHandler.slashMissSuggestion(
                        scope, commandName, inboundContext.effectiveLanguage());
                if (suggestion != null) {
                    return suggestion;
                }
                break;
            }
        }
        // Genuine no-match: the flat reply already names no commands and points
        // at /help, which is exactly the honest answer here.
        return bundleLoader.get(BundleKeys.ERROR_UNKNOWN_COMMAND, inboundContext.effectiveLanguage());
    }

    /**
     * The registration welcome reply, with its {@code {0}} placeholder
     * filled from the canonical probation command list — the same
     * {@link CommandPermissions} source {@code /help} and the probation
     * rejection render, so the welcome can no longer omit an allowed
     * command (M1-590). Both DM-registration branches (SimpleX admin
     * claim, invite accept) go through here.
     */
    private String welcomeReply(String lang) {
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_WELCOME_DM_FRESH, lang),
                commandPermissions.renderProbationCommandList());
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
     * {@code "<1m"}. The expiry now comes from the same per-dispatch
     * {@link UserSnapshot#probationUntil()} the step-5 gate read, so it
     * is non-null whenever the gate fires and the null branch is a
     * formatter-totality guard; the sub-minute branch still covers an
     * expiry that elapses between the snapshot read and this call.
     *
     * <p>{@code now} is the gate's own {@code probationNow =
     * clock.instant()} sample, threaded in so the rendered token is
     * computed against the same instant as the block decision (and is
     * pinnable in tests) rather than re-reading the wall clock. (M1-471)
     */
    static String formatTimeUntilUnlock(Instant now, @Nullable Instant expiry) {
        if (expiry == null) {
            return "<1m";
        }
        Duration remaining = Duration.between(now, expiry);
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
     * Resolve the effective scope language for {@code (scopeKind,
     * scopeId)} per D43: a missing {@code scope_preferences} row means
     * {@code en}. Called at most once per dispatch — DM right after the
     * users-row snapshot gates, group at the step-4.1 resolution; the
     * result is published to {@link InboundContext#effectiveLanguage()}
     * for every downstream bundle lookup. Same test seam as
     * {@link #lookupUser}: package-private + non-final, runs on the
     * shared per-dispatch connection.
     */
    String lookupScopeLanguage(DispatchDb db, String scopeKind, UUID scopeId) {
        try (PreparedStatement ps = db.connection().prepareStatement(SELECT_SCOPE_LANGUAGE_SQL)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                String language;
                String replyModeOverride;
                if (!rs.next()) {
                    language = "en";
                    replyModeOverride = null;
                } else {
                    language = rs.getString("language");
                    replyModeOverride = rs.getString("reply_mode");
                }
                resolveReplyMode(replyModeOverride, language, scopeKind, scopeId);
                return language;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "InboundRouter.lookupScopeLanguage failed for scope_kind=" + scopeKind, e);
        }
    }

    // D79: resolve the reply mode once per dispatch and cache it on the
    // context so it never flips mid-turn. Guarded for unit tests that wire
    // the router by hand and leave the resolver/context unset.
    private void resolveReplyMode(@Nullable String replyModeOverride, String language,
                                  String scopeKind, UUID scopeId) {
        if (replyModeResolver == null || inboundContext == null) {
            return;
        }
        inboundContext.setReplyMode(
                replyModeResolver.resolve(replyModeOverride, language, scopeKind, scopeId));
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
    @Nullable String dispatchChat(UUID actorId, String scopeKind, UUID scopeId, String normalized) {
        // Compute the reply WITHOUT persisting. The chat-turn persistence
        // (and auto-compress) is deferred to a post-delivery commit so a
        // permanent delivery failure leaves the context window "as if the
        // message was never generated" (spec messaging.md §Failure
        // handling). The pending commit is stashed on the request-scoped
        // InboundContext for onMessage to run after the reply is delivered;
        // it is left unset for a /stop-cancelled / rejected / failed turn.
        ChatAgent.ChatTurnResult result =
                chatAgent.handleTurn(actorId, scopeKind, scopeId, normalized);
        ChatAgent.PendingCommit pending = result.pendingCommit();
        if (pending != null) {
            inboundContext.setPendingChatCommit(pending);
        }
        // Null reply propagates a /stop-cancelled chat turn: ChatAgent
        // already let the /stop handler reply, so the router's null-body
        // branch skips the send (no double-reply).
        String reply = result.reply();
        String provenanceNotice = result.provenanceNotice();
        if (reply == null || provenanceNotice == null) {
            return reply;
        }
        // Retrieval-provenance notice (M1-617, D58) rides the SAME outbound
        // as the reply, blank-line separated — a second adapter send could
        // reorder against the terminal finalize and doubles delivery cost.
        // Degrade/rejection turns carry a null notice and ship unchanged.
        return reply + "\n\n" + provenanceNotice;
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
