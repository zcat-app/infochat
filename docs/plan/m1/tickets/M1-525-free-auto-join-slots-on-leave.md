---
id: M1-525
title: "Free auto_joined_group slots when the bot leaves a group"
status: pending
created: 2026-06-29
last_updated: 2026-06-29
blocked_by: []
files_budget: 8
files_scope: []
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope: []
acceptance: []
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
decomposed_from: M1-522
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-525: Free auto_joined_group slots when the bot leaves a group

## Context

Decomposed from M1-522 (the plan-writer flagged that M1-522's four acceptance
items could not fit `files_budget: 8`). This child carries the *automatic*
slot-freeing half (M1-522 acceptance items 1+2): when the bot leaves or is
removed from a group it auto-joined, its `auto_joined_group` slot must stop
counting against the D47 caps, so the cap is not a permanent lifetime ratchet.
The sibling ticket M1-526 carries the in-band bot-admin recovery command (item
3) and is `blocked_by` this ticket because it depends on the `removed_at`
column and the repository freeing method this ticket introduces.

This addresses the M1-519 in-progress redteam audit
(`docs/plan/m1/redteam/M1-519-2026-06-29.md`) Finding 2 (the lifetime ratchet).

## Acceptance

TODO — fill in before `/m1-tick start M1-525`. Sketch (from M1-522 items 1+2):

1. A bot-leave signal frees the corresponding `auto_joined_group` slot
   (`removed_at` soft-delete; counters exclude `removed_at IS NOT NULL`),
   mirroring the `groups`-table convention. A Flyway migration (next free V)
   adds `auto_joined_group.removed_at` and applies cleanly on a fresh DB,
   granting only UPDATE (DELETE stays revoked per M1-519's V55 append-only
   guard).
2. SimpleX (no native membership events) has a leave-detection mechanism so the
   cap is not a permanent lifetime ratchet. A NAMED test asserts the slot is
   freed on that signal.
3. `mvn -B verify` is green from the repo root.

## Out-of-scope

TODO — fill in before `/m1-tick start M1-525`. The bot-admin recovery command
(M1-522 item 3) belongs to sibling M1-526, not here. The D47 total caps and
their enforcement in `GroupInvitationHandler` (M1-519) are not re-implemented.

## Notes

- Native-event adapters: `MembershipEventHandler.handleBotRemoved` is the
  natural freeing hook, but it currently returns early when no non-removed
  `groups` row resolves — a join-only group needs a branch that runs even when
  `groupId == null`. Audit before assuming the native hook suffices.
- SimpleX (`supportsMembershipEvents=false`) fires no native `BotRemoved`
  event; detection likely needs a permanent-delivery-failure inference
  (`OutboundDelivery`/`GroupRepository`) or periodic reconciliation. Scope the
  mechanism before `/m1-tick start`. A pure join-only SimpleX group never
  @mentioned has no `groups` row and is never sent to, so neither path fires —
  true auto-detection of that residual needs a known-group-reconciliation SPI
  (deferred today); M1-526's admin command is the recovery for that residual.
- Likely `security_relevant: true` / `risk: medium` once the migration + GRANT
  are confirmed in scope; `complexity` may be `medium` for the SimpleX path.
  Set these accurately when fleshing out — the skeleton uses template defaults.
- Consider `remediates: M1-519` once acceptance is finalized.
- Parent context: `docs/plan/m1/tickets/M1-522-auto-join-slot-freeing.md`;
  M1-519 redteam audit: `docs/plan/m1/redteam/M1-519-2026-06-29.md`.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-525-*.md
```
