-- V56: auto_joined_group.removed_at — slot-freeing on bot-leave (M1-525,
-- remediates M1-519 redteam Finding 2: the V55 count was a one-way lifetime
-- ratchet). When the bot leaves or is removed from a group it auto-joined, the
-- slot must stop counting against the D47 per-user-activation and
-- global-max-groups caps, so a left group no longer permanently consumes a slot.
--
-- Freeing is a removed_at soft-set (an UPDATE), never a row DELETE — the V55
-- append-only guard (DELETE revoked from infochat_provider and PUBLIC) stays in
-- force. The count predicates GroupJoinRepository.countJoins /
-- countJoinsByInviter add `AND removed_at IS NULL`, mirroring the groups
-- COUNT_ACTIVE / COUNT_BY_ACTIVATED_BY idiom (which already excludes removed
-- rows so a freed slot is reusable).
--
-- Two production paths write this column (M1-525):
--   * MembershipEventHandler.handleBotRemoved, for adapters with a native
--     BotRemoved event (supportsMembershipEvents=true: Signal, in-memory) —
--     including a join-only group that never entered the @mention machine.
--   * GroupRepository.markRemovedAudited (the OutboundDelivery
--     permanent-delivery-failure inference), for SimpleX
--     (supportsMembershipEvents=false), which fires no native event.

ALTER TABLE auto_joined_group ADD COLUMN removed_at TIMESTAMPTZ;

-- Column-scoped UPDATE (the V31 source precedent: GRANT UPDATE (cols)): the
-- provider soft-sets removed_at (free) and, on re-join, clears it back to NULL
-- while re-attributing inviter_user_id to the current inviter (tryRecordJoin's
-- ON CONFLICT ... DO UPDATE — M1-525 acceptance item 4, closing the leave->
-- re-join cap-laundering DoS). inviter_user_id is in the column list because the
-- re-join reactivation overwrites it; this grants no new identity-forgery
-- surface — the provider already sets inviter_user_id on INSERT (V55) — and the
-- (adapter, upstream_group_id) natural key stays OUT of the list, so an
-- injection foothold still cannot repoint a row onto another group. DELETE
-- stays revoked (V55): the append-only guard is intact — freeing is a soft-set,
-- not a row delete.
GRANT UPDATE (removed_at, inviter_user_id) ON auto_joined_group TO infochat_provider;
