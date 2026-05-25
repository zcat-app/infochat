package app.zcat.infochat.messaging;

import org.jspecify.annotations.NonNull;

/**
 * Adapter-sourced group-membership lifecycle signal. Adapters surface
 * these when the underlying protocol reports a membership change; Provider
 * consumes them to update {@code group_membership} rows and trigger
 * cleanup (e.g. setting {@code removed_at} on UserLeft / BotRemoved).
 *
 * <p>Sealed with four permits covering the lifecycle signals defined in
 * {@code docs/spec/messaging.md} §Failure handling: user joins, user
 * leaves, bot removed from group, group deleted entirely.</p>
 */
public sealed interface MembershipEvent
        permits MembershipEvent.UserJoined,
                MembershipEvent.UserLeft,
                MembershipEvent.BotRemoved,
                MembershipEvent.GroupDeleted {

    /** The adapter-defined stable group identifier this event pertains to. */
    @NonNull String adapterGroupId();

    /** A user joined a group the bot is a member of. */
    record UserJoined(@NonNull String adapterGroupId, @NonNull String contactId)
            implements MembershipEvent {}

    /** A user left (or was removed from) a group the bot is a member of. */
    record UserLeft(@NonNull String adapterGroupId, @NonNull String contactId)
            implements MembershipEvent {}

    /** The bot itself was removed from the group. */
    record BotRemoved(@NonNull String adapterGroupId)
            implements MembershipEvent {}

    /** The group was deleted entirely by the platform or group owner. */
    record GroupDeleted(@NonNull String adapterGroupId)
            implements MembershipEvent {}
}
