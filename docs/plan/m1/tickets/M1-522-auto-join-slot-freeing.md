---
id: M1-522
title: "Free auto_joined_group slots when the bot leaves a group"
status: abandoned
created: 2026-06-29
last_updated: 2026-06-29
abandoned_reason: decomposed
decomposed_into:
  - M1-525
  - M1-526
blocked_by:
  - M1-519
files_budget: 8
files_scope: []
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - "The D47 total caps themselves (per-user-activation-cap / global-max-groups) and their enforcement in GroupInvitationHandler — M1-519 added those; this ticket only makes the count decrease when the bot leaves a group."
  - "The §3.5 @mention approval machine (GroupApprovalService/GroupApprovalCheck, groups table) — unchanged."
acceptance:
  - >-
    A bot-leave signal for a group the bot auto-joined frees its
    auto_joined_group slot (so countJoins / countJoinsByInviter stop counting
    it), via a removed_at soft-delete mirroring the groups-table convention. A
    Flyway migration adding auto_joined_group.removed_at applies cleanly on a
    fresh DB.
  - >-
    For SimpleX (supportsMembershipEvents=false), a mechanism exists to detect
    the bot having left / been removed from an auto-joined group, so the cap is
    not a permanent lifetime ratchet. A named test asserts the slot is freed on
    that signal.
  - >-
    A bot-admin-facing recovery command frees auto_joined_group slots from chat
    (e.g. clears slots for groups the bot is no longer in), so a flooded global
    pool is recoverable in-band and not only via operator psql under the DB owner
    role (M1-519 redteam Finding 2). A named test asserts an admin can recover a
    saturated pool. The command runs in deterministic Java with a bot-admin
    authorization check (never an LLM tool); the freeing UPDATE/DELETE privilege
    is granted to the appropriate role only.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - "docs/spec/security.md §Authorization model"
  - "docs/spec/messaging.md §Required SPI surface"
decision_refs:
  - D47
remediates: M1-519
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-29
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE: items 1-3 pledge a named test without naming it; sharpen with concrete test names during implementation."
    - "COMPLEXITY-RISK-CALIBRATED: risk medium->high (persistence migration + new admin command + DB GRANT). Applied: risk now high."
    - "SECURITY-FLAG-CONSISTENT: security_relevant false->true (new privileged bot-admin command + DB role GRANT). Applied: security_relevant now true."
  blockers: []
escalations:
  - date: 2026-06-29
    reason: outline-fail
    reviewer_verdict_excerpt: |
      ## OUTLINE FAILED — escalation recommended
      REASON: No implementable outline satisfying all four acceptance items fits
      within files_budget: 8. Acceptance item 3 alone (a new bot-admin recovery
      command) has a hard floor of ~8 production files: handler, localized reply
      strings in BOTH bundles/en.properties and bundles/cs.properties, a
      BundleKeys constant, a new AuditAction verb, the GroupJoinRepository
      freeing/list method, AND — because every bot-admin command must appear in
      the CI-enforced closed privileged-tier list — additions to BOTH
      docs/spec/commands.md §"Closed list of privileged-tier commands" and
      LlmOutputSanitizer.CLOSED_LIST (byte-equality enforced by
      LlmOutputSanitizerTest.matchSetEqualsSpecClosedList). Acceptance items 1+2
      add a non-overlapping set: the V56 Flyway migration (removed_at + GRANT
      UPDATE, DELETE stays revoked per V55), the removed_at IS NULL
      count-exclusion plus the handleBotRemoved groupId==null freeing branch, AND
      the SimpleX permanent-delivery-failure freeing path
      (OutboundDelivery/GroupRepository) — load-bearing because SimpleX fires no
      native BotRemoved event (supportsMembershipEvents=false). Minimal
      production-file set ~11, before tests — well over the ceiling of 8.
      SUGGESTED ESCALATION: decompose (split items 1+2 ~6 files from item 3 ~7-8
      files), OR refine to raise files_budget to ~12. Secondary scoping note: a
      pure join-only SimpleX group never @mentioned has no groups row and is
      never sent to, so neither the native hook nor the permanent-failure path
      ever fires — genuine auto-detection needs a known-group-reconciliation SPI
      (deferred today); the in-band admin command is the only recovery for that
      residual.
---

# M1-522: Free auto_joined_group slots when the bot leaves a group

## Context

M1-519 added the durable `auto_joined_group` table and enforced the D47 total
group-count caps on the auto-join surface, closing the unbounded-passive-
membership DoS from the M1-515 round-2 redteam re-audit. To keep that ticket
surgical, slot-freeing was deferred here: `auto_joined_group` has no
`removed_at`, so the count only ever grows. For SimpleX — the only v1
auto-accept adapter — `supportsMembershipEvents=false`, so the bot receives no
native `BotRemoved` event and the `OutboundDelivery` permanent-failure cleanup
path only fires for groups the bot SENDS to (never a join-only group). The
practical consequence: once the bot has auto-joined `global-max-groups` distinct
groups over its lifetime (default 5 on `pi`, 10 on `laptop`), it permanently
stops accepting new invitations even after leaving every one of them. This
ticket restores the slot-freeing that mirrors the §3.5 `groups.removed_at`
convention, including a SimpleX-viable leave-detection mechanism, AND adds an
admin-facing in-band recovery command. Both gaps were surfaced as MEDIUM DoS
findings by the M1-519 in-progress redteam audit
(`docs/plan/m1/redteam/M1-519-2026-06-29.md`): Finding 2 (the lifetime ratchet
is reachable by a modest invite-gated actor set and recoverable today only via
operator psql) drives the recovery command; the same audit's Finding 1 (the
global cap is a non-atomic check-then-act that can overshoot by a bounded amount
under concurrency) is the optional advisory-lock hardening noted below.

## Acceptance

1. A bot-leave signal frees the corresponding `auto_joined_group` slot
   (`removed_at` soft-delete; the counters exclude `removed_at IS NOT NULL`),
   mirroring the `groups`-table convention so an auto-join the bot later leaves
   no longer counts against the caps. A Flyway migration adds
   `auto_joined_group.removed_at` and applies cleanly on a fresh DB.
2. SimpleX (no native membership events) has a leave-detection mechanism so the
   cap is not a permanent lifetime ratchet. A named test asserts the slot is
   freed on that signal.
3. A bot-admin-facing recovery command frees `auto_joined_group` slots from chat
   so a flooded global pool is recoverable in-band (not only via operator psql).
   Authorization is a deterministic bot-admin check in Java (never an LLM tool);
   the freeing privilege is granted to the appropriate role only. A named test
   asserts an admin can recover a saturated pool (M1-519 redteam Finding 2).
4. `mvn -B verify` is green from the repo root.

## Out-of-scope

The total caps and their enforcement in `GroupInvitationHandler` (M1-519) are
not re-implemented — this ticket only adds the freeing half plus the admin
recovery command. The §3.5 @mention approval machine is untouched.

## Notes

- For adapters that DO report membership events, the native `BotRemoved` path
  (`MembershipEventHandler.handleBotRemoved`) is the natural freeing hook — but
  it currently returns early when no non-removed `groups` row resolves, so a
  join-only group needs a branch that runs even when `groupId == null`. Audit
  this before assuming the native hook suffices.
- The SimpleX leave-detection mechanism is the hard part and the reason this is
  `complexity: high`: SimpleX surfaces no membership-departure event, so
  detection likely needs a different signal (e.g. inferring departure from a
  send/delivery failure, or a periodic reconciliation against the adapter's
  known-group list). Scope the mechanism before `/m1-tick start`.
- Admin recovery command (acceptance item 3): the natural home is a bot-admin
  command alongside the existing group commands; it needs a `GroupJoinRepository`
  freeing method (clear slots, e.g. for groups the bot is no longer in) and a
  GRANT of the freeing privilege (UPDATE for the removed_at soft-delete) to the
  role the command runs as. Keep DELETE revoked — freeing is a removed_at UPDATE,
  not a row DELETE (M1-519's V55 append-only guard stays intact).
- OPTIONAL hardening (M1-519 redteam Finding 1): make the global-cap check atomic
  via a pg advisory lock (or `SELECT … FOR UPDATE` serialization) spanning the
  count and the insert in `GroupInvitationHandler`, closing the bounded
  cross-inviter overshoot. M1-519 deliberately left this as a flood-bound,
  matching the §3.5 `GroupApprovalService` "Race window R2" stance; include it
  here only if a strict aggregate ceiling is wanted.
- M1-519 table + handler: `docs/plan/m1/tickets/M1-519-auto-join-group-count-cap.md`.
  M1-519 redteam audit: `docs/plan/m1/redteam/M1-519-2026-06-29.md`.
  Round-2 M1-515 redteam re-audit: `docs/plan/m1/redteam/M1-515-2026-06-29-recheck.md`.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-522-*.md
```
