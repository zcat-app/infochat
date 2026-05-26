---
id: M1-084
title: "MembershipEvent wiring + tryAutoPromote re-promote path"
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by: []
files_budget: 8
files_scope: []
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - "GroupDeleted / UserJoined event handling — only UserLeft and BotRemoved in scope"
  - "Reopening or modifying M1-079 umbrella IT — this ticket ships production code only"
  - "Schema migrations or DDL — the V5 trigger and one_admin_per_group index already exist"
  - "Real adapter (SimpleX/Signal) membership-event emission — only InMemoryAdapter fires events in v1"
  - "Changes to isEligible() logic in tryAutoPromote"
  - "Changes to /promote, /demote, or any slash command implementation"
  - "Changes to InboundRouter message-handling flow"
acceptance:
  - "MessagingAdapter exposes a setMembershipEventHandler hook (or equivalent wiring point) that the provider sets to receive MembershipEvent instances — parallel to the existing setInboundHandler pattern."
  - "AdapterRegistry wires the membership-event handler on each activated adapter during startup, alongside the existing setInboundHandler call."
  - "When MembershipEvent.UserLeft fires, the handler resolves adapter-level group ID and contact ID to internal UUIDs and calls GroupMembershipRepository.markMemberRemoved(groupId, userId). After the call, the row has removed_at IS NOT NULL and is_group_admin = false (V5 trigger)."
  - "When MembershipEvent.BotRemoved fires, the handler resolves the adapter-level group ID and calls GroupRepository.markRemoved(groupId). After the call, the groups row has removed_at IS NOT NULL."
  - "GroupAutoPromoteService.tryAutoPromote succeeds (returns true) for an existing group_membership row where is_group_admin = false and removed_at IS NULL. SQL changes from ON CONFLICT DO NOTHING to ON CONFLICT (group_id, user_id) DO UPDATE SET is_group_admin = true with a WHERE guard excluding removed rows."
  - "Concurrent tryAutoPromote calls for the same group: at most one succeeds (enforced by one_admin_per_group partial unique index); the loser returns false without throwing."
  - "Successful re-promote writes an audit-log entry with action PROMOTE_GROUP_ADMIN and detail {\"auto_promote\":true}, identical to the first-promote path."
test_plan:
  adds:
    - "MembershipEventHandler test: UserLeft -> verifies markMemberRemoved called with correct IDs"
    - "MembershipEventHandler test: BotRemoved -> verifies markRemoved called with correct group ID"
    - "GroupAutoPromoteService re-promote test: existing non-admin member returns true + audit written"
    - "GroupAutoPromoteService re-promote test: removed member returns false"
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
clarity_check:
  date: 2026-05-26
  verdict: FAIL
  warnings:
    - "files_budget: 8 cannot be validated without acceptance criteria"
  blockers:
    - "acceptance: [] is empty — no runnable/testable acceptance items"
    - "out_of_scope: [] is empty — no explicit boundaries defined"
escalations:
  - date: 2026-05-26
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      BLOCKERS:
      1. acceptance: [] is empty — no runnable/testable acceptance items
      2. out_of_scope: [] is empty — no explicit boundaries defined
revisions:
  - date: 2026-05-26
    reason: clarity-fail
    snapshot: |
      acceptance: []
      out_of_scope: []
      test_plan.adds: []
---

# M1-084: MembershipEvent wiring + tryAutoPromote re-promote path

## Context

M1-079 umbrella IT (group lifecycle roundtrip) surfaced two production
code gaps that prevent acceptance step (h) — "user-left clears admin
slot + next auto-promote":

1. **No MembershipEvent handler wired in the provider.**
   `MessagingAdapter.onMembershipEvent` is a default no-op.
   `InMemoryAdapter.removeMember()` fires `MembershipEvent.UserLeft`
   but no provider code processes it — `group_membership.removed_at`
   is never set, and the partial-unique-index slot is never freed.
   M1-079b deferred this to M1-079c; M1-079c deferred it to the
   umbrella ("not an acceptance item; the umbrella pins it if needed").

2. **`GroupAutoPromoteService.tryAutoPromote` only handles new members.**
   The SQL is `INSERT INTO group_membership ... ON CONFLICT DO NOTHING`.
   For existing members whose `is_group_admin` was cleared (e.g. after
   `/promote` swapped admin to another user, or after a user-left event
   cleared the flag), the INSERT hits the PK `(group_id, user_id)`
   conflict and silently returns false. The spec says "the next
   non-banned, non-probation sender is promoted" — no restriction to
   new members. Re-promote of existing members requires an
   `ON CONFLICT ... DO UPDATE` path guarded by the partial unique index.

Both gaps are in frozen sub-ticket files (M1-079b/c). This ticket
ships the missing production code so the umbrella IT can reopen.

## Acceptance

See frontmatter `acceptance:` list — seven criteria covering:
- (a–b) Adapter-to-provider membership-event wiring via setMembershipEventHandler
- (c–d) UserLeft → markMemberRemoved, BotRemoved → markRemoved
- (e–g) tryAutoPromote ON CONFLICT DO UPDATE path, race safety, audit log

## Out-of-scope

See frontmatter `out_of_scope:` list — seven explicit boundaries
covering GroupDeleted/UserJoined events, M1-079 umbrella IT changes,
DDL/migrations, real adapter emission, isEligible logic, slash
commands, and InboundRouter flow.

## Notes

- The MembershipEvent handler likely lives as a new bean or as an
  addition to AdapterRegistry/MessagingStartup. The handler must
  process at minimum `UserLeft` (→ `markMemberRemoved`) and
  `BotRemoved` (→ `GroupRepository.markRemoved`).
- The tryAutoPromote fix is a SQL change: the INSERT needs an
  `ON CONFLICT (group_id, user_id) DO UPDATE SET is_group_admin = true`
  path, still race-safe against the partial unique index
  `one_admin_per_group`. The existing audit-log write on success
  must still fire for the UPDATE path.
- `security_relevant: true` — modifies group-admin promotion logic
  and membership lifecycle. Warrants `/redteam` after review.
- Parent escalation: M1-079 umbrella IT, premise-fail.
