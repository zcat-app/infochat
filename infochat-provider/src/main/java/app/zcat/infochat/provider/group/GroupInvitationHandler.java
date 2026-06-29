package app.zcat.infochat.provider.group;

import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.provider.messaging.RateCapBucket;
import app.zcat.infochat.provider.messaging.RegisteredContactSet;
import app.zcat.infochat.provider.user.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

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

    @Inject
    public GroupInvitationHandler(UserRepository userRepository,
                                  RateCapBucket rateCapBucket,
                                  RegisteredContactSet registeredContactSet) {
        this.userRepository = userRepository;
        this.rateCapBucket = rateCapBucket;
        this.registeredContactSet = registeredContactSet;
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
        boolean registeredInviter = userRepository
                .findByAdapterAndContactId(adapter, inviterContactId)
                .filter(user -> !user.isBanned())
                .map(user -> REGISTERED_STATES.contains(user.registrationState()))
                .orElse(false);
        if (!registeredInviter) {
            // Ignore, do not decline (see class javadoc): no join, no reply.
            log.info("group invitation ignored: inviter not a registered user; "
                            + "adapter={} inviter={} group={}",
                    adapter, ContactIds.redact(invitation.inviterContactId()),
                    ContactIds.redact(invitation.adapterGroupId()));
            return;
        }
        source.joinGroup(invitation.adapterGroupId());
        log.info("group invitation auto-accepted: registered inviter; "
                        + "adapter={} inviter={} group={}",
                adapter, ContactIds.redact(invitation.inviterContactId()),
                ContactIds.redact(invitation.adapterGroupId()));
    }
}
