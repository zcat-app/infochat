package app.zcat.infochat.provider.group;

import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.provider.messaging.RateCapBucket;
import app.zcat.infochat.provider.messaging.RegisteredContactSet;
import app.zcat.infochat.provider.user.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Provider-side decision point for received group invitations (M1-515). Wired
 * to each activated adapter by {@code AdapterRegistry} via
 * {@code setGroupInvitationHandler}.
 *
 * <p>The registration decision lives here, never in the adapter (the adapter
 * queries no DB, D10): the bot auto-accepts an invitation — by instructing the
 * inviting adapter to {@link MessagingAdapter#joinGroup} — ONLY when the
 * inviter is a registered, non-banned user (the D47 registered-only gate). This
 * is the closure for redteam vector 3 from M1-511: the gate cannot be bypassed
 * to make the bot join arbitrary groups, because an unregistered or banned
 * inviter never triggers {@code /_join}.</p>
 *
 * <p>An invitation from an unregistered or banned inviter is <em>ignored</em>,
 * not declined: the bot neither joins nor sends any reply, so it does not join
 * an arbitrary group and does not reveal that it processed the invitation (less
 * traffic, no presence signal — M1-515 decision). The group still enters the
 * D47 {@code approval_status='pending'} machine on the first @mention after a
 * join; no approval logic is added here.</p>
 *
 * <p>A registered inviter's auto-joins are additionally bounded by the D47
 * total group-count caps (M1-519): before issuing {@code /_join} the handler
 * enforces a per-inviter activation cap and a global max-groups cap, counted
 * from the durable {@code auto_joined_group} table ({@link GroupJoinRepository})
 * under the same config keys the §3.5 @mention path reads. This bounds the
 * bot's TOTAL passive memberships, where the transport rate cap only bounds
 * their RATE; an over-cap invitation is dropped silently (no join, no reply).
 * The §3.5 {@code groups} approval machine is untouched — the join surface
 * writes no {@code groups} row.</p>
 */
@ApplicationScoped
public class GroupInvitationHandler {

    private static final Logger log = LoggerFactory.getLogger(GroupInvitationHandler.class);

    // The D47 registered-only gate: an invite from a user in one of these
    // registration_state values (and not banned) is the sole trigger for an
    // auto-join. 'preban' (the other valid state) and any banned user do not
    // qualify. Kept in sync with the users.registration_state CHECK (V27).
    private static final Set<String> REGISTERED_STATES = Set.of("invited", "vouched");

    private final UserRepository userRepository;
    private final RateCapBucket rateCapBucket;
    private final RegisteredContactSet registeredContactSet;
    private final GroupJoinRepository groupJoinRepository;

    // The D47 total group-count caps, reusing the SAME config keys the §3.5
    // @mention path reads (GroupApprovalService) — one config surface, not a
    // forked one (M1-519). Note the two surfaces COUNT DIFFERENT TABLES: this
    // one counts auto_joined_group (passive joins), §3.5 counts `groups`
    // (@mention-activated groups). The ceilings are therefore enforced
    // independently — the bot's total group footprint can reach up to the sum
    // of the two counters (≈2× the configured value), not a single shared
    // bound. Profile-driven values live in application.properties.
    @ConfigProperty(name = "infochat.groups.per-user-activation-cap", defaultValue = "3")
    int perUserActivationCap;

    @ConfigProperty(name = "infochat.groups.global-max-groups", defaultValue = "10")
    int globalMaxGroups;

    @Inject
    public GroupInvitationHandler(UserRepository userRepository,
                                  RateCapBucket rateCapBucket,
                                  RegisteredContactSet registeredContactSet,
                                  GroupJoinRepository groupJoinRepository) {
        this.userRepository = userRepository;
        this.rateCapBucket = rateCapBucket;
        this.registeredContactSet = registeredContactSet;
        this.groupJoinRepository = groupJoinRepository;
    }

    /**
     * Decide and act on one received group invitation from the given adapter.
     * Called by the lambda wired in {@code AdapterRegistry.start()};
     * {@code source} is the inviting adapter, used to issue the join back so
     * the decision (here) and the transport (there) stay separated per D10.
     *
     * @throws MessagingException if the inviter is registered but the adapter
     *                            fails to issue the join (a transport fault the
     *                            dispatch wrapper isolates and logs).
     */
    public void handle(MessagingAdapter.GroupInvitation invitation,
                       String adapter,
                       MessagingAdapter source) throws MessagingException {
        String inviterContactId = invitation.inviterContactId();
        // §step-1.5 transport rate cap (security.md §Authorization model /
        // §Rate limiting), applied BEFORE the DB lookup so the hostile-flood
        // path stays query-free — the same shape and ordering as
        // InboundRouter.onMessage. The registered split (M1-229) is taken from
        // the in-memory RegisteredContactSet (no query): a registered inviter
        // gets its own per-(adapter, contactId) bucket; an unregistered one
        // shares the per-adapter stranger limiter and mints no per-id state, so
        // a Sybil flood of distinct inviter ids cannot pin the bucket map. An
        // over-cap invitation is dropped silently (no DB lookup, no /_join, no
        // reply), so a registered inviter cannot flood the bot into unbounded
        // /_join outbound or DB-lookup cost (M1-515 redteam DoS finding).
        boolean registeredContact = registeredContactSet.isRegistered(adapter, inviterContactId);
        if (!rateCapBucket.tryAcquire(adapter, inviterContactId, registeredContact)) {
            log.info("group invitation rate-capped (step-1.5 transport cap); "
                            + "adapter={} inviter={} group={}",
                    adapter, ContactIds.redact(inviterContactId),
                    ContactIds.redact(invitation.adapterGroupId()));
            return;
        }
        // Authoritative gate: the in-memory set above is a query-free fast path
        // for the cap split; the users row carries the ban flag and exact
        // registration_state the D47 decision must not race against a stale set.
        // Retain the row (not just a boolean) — the inviter's users.id keys the
        // per-inviter total-cap count below (M1-519).
        Optional<UserRepository.UserRow> inviterRow = userRepository
                .findByAdapterAndContactId(adapter, inviterContactId)
                .filter(user -> !user.isBanned())
                .filter(user -> REGISTERED_STATES.contains(user.registrationState()));
        if (inviterRow.isEmpty()) {
            // Ignore, do not decline (see class javadoc): no join, no reply.
            log.info("group invitation ignored: inviter not a registered user; "
                            + "adapter={} inviter={} group={}",
                    adapter, ContactIds.redact(invitation.inviterContactId()),
                    ContactIds.redact(invitation.adapterGroupId()));
            return;
        }
        UUID inviterUserId = inviterRow.get().id();

        // D47 total group-count caps (M1-519), enforced BEFORE /_join so an
        // over-cap invitation produces no join and no reply — a silent drop, the
        // same shape as the rate-cap and registration-gate drops above. These
        // bound the bot's TOTAL passive memberships; the transport rate cap above
        // only bounds their RATE. Counts come from auto_joined_group, NOT the
        // §3.5 `groups` table, so the @mention approval machine stays untouched.
        // The per-inviter cap is race-free (one inviter's invitations serialize
        // per contactId in the adapter dispatch). The global cap is a non-atomic
        // check-then-act (count here, record below) with no lock spanning the
        // two, so — exactly as the §3.5 GroupApprovalService "Race window R2"
        // documents — concurrent invitations from DISTINCT inviters can overshoot
        // the global ceiling by a bounded amount (≤ dispatch concurrency width).
        // Per spec §Rate limiting these caps are flood-bounds (resource-exhaustion
        // defenses), not strict invariants: the unbounded-growth DoS is closed; a
        // hard atomic ceiling would need advisory-lock serialization, deferred to
        // M1-522 (M1-519 redteam Finding 1).
        if (groupJoinRepository.countJoinsByInviter(inviterUserId) >= perUserActivationCap) {
            log.info("group invitation dropped: per-inviter group-activation cap reached; "
                            + "adapter={} inviter={} group={}",
                    adapter, ContactIds.redact(invitation.inviterContactId()),
                    ContactIds.redact(invitation.adapterGroupId()));
            return;
        }
        if (groupJoinRepository.countJoins() >= globalMaxGroups) {
            log.info("group invitation dropped: global max-groups cap reached; "
                            + "adapter={} inviter={} group={}",
                    adapter, ContactIds.redact(invitation.inviterContactId()),
                    ContactIds.redact(invitation.adapterGroupId()));
            return;
        }
        // Record-then-join: the conservative ordering for a security cap. If
        // joinGroup throws a transport fault after the record, the slot is
        // consumed (a one-row over-count) — the safe direction for a DoS bound,
        // never an under-count that could let the cap be exceeded. The record is
        // idempotent on the natural key, so a re-invite to an already-joined
        // group does not inflate the count.
        groupJoinRepository.tryRecordJoin(adapter, invitation.adapterGroupId(), inviterUserId);
        source.joinGroup(invitation.adapterGroupId());
        log.info("group invitation auto-accepted: registered inviter; "
                        + "adapter={} inviter={} group={}",
                adapter, ContactIds.redact(invitation.inviterContactId()),
                ContactIds.redact(invitation.adapterGroupId()));
    }
}
