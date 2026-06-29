---
id: M1-525
title: "Free auto_joined_group slots when the bot leaves a group"
status: pending
created: 2026-06-29
last_updated: 2026-06-29
blocked_by: []
files_budget: 8
files_scope: []
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - "The in-band bot-admin recovery command (M1-522 item 3) — sibling M1-526 owns it; M1-526 is blocked_by this ticket because it depends on the removed_at column and the GroupJoinRepository freeing method introduced here."
  - "The D47 total caps themselves (per-user-activation-cap, global-max-groups) and their check-then-act enforcement in GroupInvitationHandler (M1-519) — this ticket only makes the counts DECREASE on leave; it does not alter the cap values or the cap check."
  - "The §3.5 @mention approval machine (GroupApprovalService / GroupApprovalCheck, groups table) — unchanged."
  - "Auto-detection of the residual pure-join-only SimpleX group that is never @mentioned (no groups row, never sent to, so neither the native hook nor the permanent-delivery-failure path can fire) — needs a deferred known-group-reconciliation SPI; M1-526's admin command is the in-band recovery for that residual."
  - "The optional advisory-lock hardening of the global-cap check-then-act race (M1-519 redteam Finding 1) — a separate, deliberately-deferred flood-bound, not reopened here."
acceptance:
  - >-
    A V56 Flyway migration adds auto_joined_group.removed_at (nullable
    timestamp) and applies cleanly on a fresh DB. It GRANTs UPDATE to the
    provider app role; DELETE stays REVOKED (M1-519 V55 append-only guard intact
    — freeing is a removed_at soft-set, never a row DELETE).
    GroupJoinRepository.countJoins() and countJoinsByInviter() exclude rows with
    removed_at IS NOT NULL, so an auto-join the bot later leaves stops counting
    against the D47 per-user-activation and global-max-groups caps. A named test
    asserts a removed_at-set row is excluded from both counts.
  - >-
    For adapters with supportsMembershipEvents=true (Signal, in-memory), a
    bot-removed signal frees the auto_joined_group slot even for a join-only
    auto-joined group. MembershipEventHandler.handleBotRemoved currently returns
    early when no non-removed groups row resolves; a branch frees the
    auto_joined_group row even when groupId == null. A named test asserts the
    slot is freed on BotRemoved for a join-only group.
  - >-
    For SimpleX (supportsMembershipEvents=false, no native BotRemoved event), a
    leave-detection mechanism frees the auto_joined_group slot so the cap is not
    a permanent lifetime ratchet. A named test asserts the slot is freed on that
    signal.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - "docs/spec/messaging.md §Required SPI surface"
  - "docs/spec/messaging.md §Capability flags (minimum set)"
  - "docs/spec/security.md §Rate limiting"
decision_refs:
  - D47
decomposed_from: M1-522
remediates: M1-519
reopens: []
redteam_findings: []
clarity_check: {}
revisions:
  - date: 2026-06-29
    reason: clarity-fail rework
    snapshot:
      status: escalated
      complexity: low
      risk: low
      round_cap: 2
      security_relevant: false
      migration_touch: false
      out_of_scope: []
      acceptance: []
      spec_refs: []
      decision_refs: []
      note: >-
        Skeleton from the M1-522 decompose; §Acceptance and §Out-of-scope were
        TODO placeholders. clarity-fail blockers: empty acceptance + empty
        out_of_scope. Refine inherits M1-522's already-WARN-passed acceptance
        items 1+2 (SimpleX mechanism left agnostic at acceptance level, as in
        the parent) and recalibrates sizing per the parent and the clarity
        warnings.
      clarity_check:
        date: 2026-06-29
        verdict: FAIL
        blockers:
          - "acceptance: frontmatter is empty (body §Acceptance marked TODO, only a rough sketch); populate with concrete testable items before start"
          - "out_of_scope: frontmatter is empty (body §Out-of-scope marked TODO); move the boundary prose into the frontmatter list as named entries"
        warnings:
          - "complexity: low / risk: low are template defaults; Notes flag them as likely needing medium+ once migration + SimpleX detection path are confirmed"
          - "security_relevant: false flagged by Notes as likely wrong once the DB migration + GRANT is confirmed in scope"
escalations:
  - date: 2026-06-29
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      CLARITY VERDICT: FAIL — 2 blockers
      1. acceptance: frontmatter field is empty (acceptance: []). Body
         §Acceptance is marked TODO and holds only a rough sketch. Populate with
         concrete, testable items before /m1-tick start M1-525.
      2. out_of_scope: frontmatter field is empty (out_of_scope: []). Body
         §Out-of-scope is marked TODO. Move the boundary prose into the
         frontmatter list as specific named entries.
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

This ticket carries M1-522 acceptance items 1+2 (the *automatic* slot-freeing
half); the in-band bot-admin recovery command (M1-522 item 3) is sibling M1-526.
The SimpleX mechanism is left agnostic at the acceptance level — exactly as the
parent framed it — so the named test asserts "the slot is freed on that signal"
regardless of which signal the implementation selects (the permanent-delivery-
failure inference is the documented candidate; see §Notes).

1. A V56 Flyway migration adds `auto_joined_group.removed_at` (nullable
   timestamp) and applies cleanly on a fresh DB. It GRANTs UPDATE to the
   provider app role; DELETE stays REVOKED (M1-519's V55 append-only guard is
   intact — freeing is a `removed_at` soft-set, never a row DELETE).
   `GroupJoinRepository.countJoins()` and `countJoinsByInviter()` exclude rows
   with `removed_at IS NOT NULL`, so an auto-join the bot later leaves stops
   counting against the D47 per-user-activation and global-max-groups caps. A
   named test asserts a `removed_at`-set row is excluded from both counts.
2. For adapters with `supportsMembershipEvents=true` (Signal, in-memory), a
   bot-removed signal frees the `auto_joined_group` slot even for a join-only
   auto-joined group. `MembershipEventHandler.handleBotRemoved` currently
   returns early when no non-removed `groups` row resolves; a branch frees the
   `auto_joined_group` row even when `groupId == null`. A named test asserts the
   slot is freed on `BotRemoved` for a join-only group.
3. For SimpleX (`supportsMembershipEvents=false`, no native `BotRemoved` event),
   a leave-detection mechanism frees the `auto_joined_group` slot so the cap is
   not a permanent lifetime ratchet. A named test asserts the slot is freed on
   that signal.
4. `mvn -B verify` is green from the repo root.

## Out-of-scope

- The in-band bot-admin recovery command (M1-522 item 3) — sibling M1-526 owns
  it; M1-526 is `blocked_by` this ticket.
- The D47 total caps themselves (per-user-activation-cap, global-max-groups) and
  their check-then-act enforcement in `GroupInvitationHandler` (M1-519) — this
  ticket only makes the counts DECREASE on leave; it does not alter the cap
  values or the cap check.
- The §3.5 @mention approval machine (`GroupApprovalService`/`GroupApprovalCheck`,
  `groups` table) — unchanged.
- Auto-detection of the residual pure-join-only SimpleX group that is never
  @mentioned (no `groups` row, never sent to, so neither the native hook nor the
  permanent-delivery-failure path can fire) — needs a deferred
  known-group-reconciliation SPI; M1-526's admin command is the in-band recovery
  for that residual.
- The optional advisory-lock hardening of the global-cap check-then-act race
  (M1-519 redteam Finding 1) — a separate, deliberately-deferred flood-bound,
  not reopened here.

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
