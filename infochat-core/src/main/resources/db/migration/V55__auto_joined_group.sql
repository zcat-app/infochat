-- V55: auto_joined_group — durable join-tracking for the auto-accept surface,
-- so the D47 total group-count caps can bound the bot's passive memberships
-- (M1-519; closes the residual MEDIUM DoS from the M1-515 round-2 redteam
-- re-audit, docs/plan/m1/redteam/M1-515-2026-06-29-recheck.md).
--
-- GroupInvitationHandler auto-accepts a group invitation from a registered,
-- non-banned inviter by issuing /_join, but mints NO `groups` row — that row
-- is created later, at first @mention, by GroupApprovalService. The §3.5 caps
-- (countGroupsActivatedBy / countActiveGroups) therefore see 0 at the join
-- surface and cannot bound passive memberships: a registered inviter under the
-- transport rate cap could grow the bot's joined-group count without limit.
-- This table is the missing durable record the join surface counts BEFORE it
-- joins, keyed by the inviter (per-user activation cap) and globally (global
-- max-groups cap) under the SAME config keys the @mention path already reads.
--
-- One row per joined group, natural key (adapter, upstream_group_id) UNIQUE so
-- a duplicate invitation to an already-joined group consumes exactly one slot
-- (INSERT … ON CONFLICT DO NOTHING in GroupJoinRepository). inviter_user_id is
-- NOT NULL — the join surface always resolves a registered users row before
-- recording — and references users(id) with the default (RESTRICT) behaviour,
-- matching the group_membership / saved_post FK precedent (V5, V15). The only
-- user-deletion path, delete_preban_user, removes PREBAN users, which can never
-- have an auto-join row (auto-join requires registration_state invited|vouched),
-- so the FK never blocks a legitimate deletion.
--
-- No removed_at / soft-delete column: slot-freeing on bot-leave is deferred to
-- a follow-up (M1-522). SimpleX, the only v1 auto-accept adapter, reports no
-- membership events (supportsMembershipEvents=false), so the bot cannot detect
-- being removed from a group and cannot free a slot; the count is intentionally
-- a one-way ratchet at the configured ceiling until that follow-up lands. The
-- caps still close the unbounded-growth DoS this ticket targets.

CREATE TABLE auto_joined_group (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    adapter           TEXT NOT NULL,
    upstream_group_id TEXT NOT NULL,
    inviter_user_id   UUID NOT NULL REFERENCES users(id),
    joined_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (adapter, upstream_group_id)
);

-- Supports the per-inviter activation-cap count (WHERE inviter_user_id = ?).
CREATE INDEX idx_auto_joined_group_inviter ON auto_joined_group(inviter_user_id);

-- Per-role GRANTs (docs/spec/security.md §DB roles; the V5/V7 per-table
-- GRANT-split convention). Provider-only: GroupInvitationHandler counts
-- (SELECT) and records (INSERT) auto-joins on the least-privileged
-- infochat_provider role; the collector never touches this table.
GRANT SELECT, INSERT ON auto_joined_group TO infochat_provider;
-- Append-only guard mirroring post_entity (V28) / post_reference (V29): no app
-- role ever DELETEs a join row. M1-522's slot-freeing will be a removed_at
-- soft-delete (an UPDATE), so DELETE stays revoked permanently.
REVOKE DELETE ON auto_joined_group FROM infochat_provider;
REVOKE DELETE ON auto_joined_group FROM PUBLIC;
