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
out_of_scope: []
acceptance: []
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
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

Skeleton — needs acceptance criteria, sizing, and out_of_scope filled
in before `/m1-tick start M1-084` will pass clarity.

## Out-of-scope

Skeleton — needs explicit boundaries.

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
