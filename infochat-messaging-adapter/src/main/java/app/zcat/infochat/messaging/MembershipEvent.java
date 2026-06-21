package app.zcat.infochat.messaging;


/**
 * Adapter-sourced group-membership lifecycle signal. Adapters surface
 * these when the underlying protocol reports a membership change; Provider
 * consumes them to update {@code group_membership} rows and trigger
 * cleanup (e.g. setting {@code removed_at} on UserLeft / BotRemoved).
 *
 * <p>Sealed with three lifecycle permits: user joins, user leaves, bot
 * removed from group. Group-deleted-upstream is not a distinct permit: in
 * v1 it is handled via the permanent-send-failure threshold path, with no
 * adapter&rarr;Provider carrier for that failure sub-class (deferred to v2).
 * See {@code docs/spec/messaging.md} §Failure handling and
 * {@code docs/design/06-messaging.md} §Permanent-delivery-failure cleanup.</p>
 */
public sealed interface MembershipEvent
        permits MembershipEvent.UserJoined,
                MembershipEvent.UserLeft,
                MembershipEvent.BotRemoved {

    /** The adapter-defined stable group identifier this event pertains to. */
    String adapterGroupId();

    /** A user joined a group the bot is a member of. */
    record UserJoined(String adapterGroupId, String contactId)
            implements MembershipEvent {}

    /** A user left (or was removed from) a group the bot is a member of. */
    record UserLeft(String adapterGroupId, String contactId)
            implements MembershipEvent {}

    /** The bot itself was removed from the group. */
    record BotRemoved(String adapterGroupId)
            implements MembershipEvent {}
}
