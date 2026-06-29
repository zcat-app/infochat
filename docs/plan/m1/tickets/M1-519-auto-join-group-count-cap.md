---
id: M1-519
title: "Enforce D47 group-count caps on the auto-join surface"
status: done
created: 2026-06-29
last_updated: 2026-06-29
blocked_by:
  - M1-515
files_budget: 8
files_scope: []
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - "The transport rate cap on the invitation path — M1-515 already added the per-(adapter, inviterContactId) RateCapBucket token to GroupInvitationHandler.handle. This ticket adds a TOTAL cap, not a rate cap; do not change the existing rate-cap logic."
  - "The @mention pending-row creation path (GroupApprovalService/GroupApprovalCheck §3.5) — those caps already fire there. This ticket extends cap enforcement to the auto-join surface only; do not re-implement the existing approval-time checks."
  - "The D47 approval state machine itself (approval_status pending/approved, /approve-group, /reject-group) — unchanged."
acceptance:
  - >-
    GroupInvitationHandler enforces the §3.5 D47 per-user group-activation cap and
    the global max-groups cap BEFORE issuing /_join, so a registered inviter cannot
    grow the bot's total passive memberships past the operator's configured
    ceiling. An invitation that would exceed either cap is not joined.
  - >-
    A named GroupInvitationHandlerTest asserts that once a single inviter's
    activation cap (or the global max-groups cap) is reached, further invitations
    from that inviter (or any inviter, for the global cap) stop triggering /_join,
    even when the transport rate cap still has tokens.
  - >-
    Joined groups are tracked durably enough to evaluate the total caps (a count or
    membership record), surviving restart. If a schema change is required, a Flyway
    migration applies cleanly on a fresh DB.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - GroupInvitationHandlerTest
  preserves:
    - all tests currently green on main
spec_refs:
  - "docs/spec/security.md §Authorization model"
  - "docs/spec/messaging.md §Required SPI surface"
decision_refs:
  - D47
remediates: M1-515
reviews:
  - round: 1
    date: 2026-06-29
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 567
      removed: 21
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-29
    category: DOS
    severity: medium
    promise: |
      The D47 caps "bound the bot's TOTAL passive memberships" and the global cap
      "stops the aggregate even when every inviter is individually under its own
      cap" (§3.5 / GroupInvitationHandler comments).
    gap: |
      Global-cap enforcement is a non-atomic check-then-act (countJoins() read,
      then tryRecordJoin INSERT) with no lock spanning read and insert. Distinct
      registered inviters dispatch concurrently (per-inviter FIFO only), so N
      inviters racing the window each see count < globalMaxGroups and all record,
      overshooting the global ceiling by up to N-1. Per-inviter cap is safe;
      global cap races. Overshoot bounded by concurrency width (unbounded-growth
      DoS still closed).
    repro: |
      N colluding registered inviters each invite the bot to a distinct new group
      at the same instant with the global pool near full; all evaluate countJoins()
      before any commits, all join, bot ends in globalMaxGroups+(N-1) groups.
    suggested_fix_class: other
  - date: 2026-06-29
    category: DOS
    severity: medium
    promise: |
      Caps "close the unbounded-growth DoS" while keeping the bot able to serve
      legitimate auto-join traffic.
    gap: |
      auto_joined_group has no removed_at and DELETE is revoked from all app roles;
      counts never decrease (slot-freeing deferred to M1-522). ceil(global/per-user)
      registered inviters (2 on pi, 4 on laptop) can fill the global pool with
      throwaway groups, then leave them — the rows persist, so the bot silently
      refuses ALL further auto-joins deployment-wide, recoverable only via operator
      psql under the owner role.
    repro: |
      A small invite-gated actor set fills countJoins() to globalMaxGroups with
      disposable groups, then leaves/deletes them; rows remain, every subsequent
      legitimate invitation is dropped with no chat/admin recovery. Anticipated/
      deferred trade-off (M1-522), surfaced because the recovery ticket is undelivered.
    suggested_fix_class: other
redteam_audits:
  - date: 2026-06-29
    verdict: FINDINGS
    base: bfcd24de
    head: working-tree (m1/M1-519-auto-join-group-count-cap, uncommitted)
    verdict_file: docs/plan/m1/redteam/M1-519-2026-06-29.md
    findings_count: 2
    out_of_model_count: 1
    note: |
      2 MEDIUM DOS findings, both anticipated. F1 (global-cap check-then-act race,
      bounded overshoot) mirrors the §3.5 GroupApprovalService flood-bound race it
      already documents as acceptable; the unbounded-growth DoS remains closed. F2
      (lifetime-ratchet lockout) is the user-accepted slot-freeing deferral tracked
      by M1-522. Out-of-model: the dual-table shared-config-key comment overstates
      a single shared ceiling. Awaiting user escalation decision.
outline_file: target/m1-tick-outline-M1-519.md
clarity_check:
  date: 2026-06-29
  verdict: WARN
  warnings:
    - "Acceptance criterion 3 does not name a test that verifies surviving restart; if no schema change is made there is no named artifact proving durability. Address by naming a durability test or making persistence non-optional."
    - "frontmatter test_plan.adds was empty but criterion 2 requires adding GroupInvitationHandlerTest; populated."
  blockers: []
---

# M1-519: Enforce D47 group-count caps on the auto-join surface

## Context

M1-515 made the bot auto-accept SimpleX group invitations from registered,
non-banned inviters, gated behind the per-(adapter, inviterContactId) transport
rate cap. The round-2 redteam re-audit
(`docs/plan/m1/redteam/M1-515-2026-06-29-recheck.md`) confirmed the rate cap
closes the original no-cap finding but flagged a residual MEDIUM DoS: the rate
cap bounds the join RATE, not the TOTAL. The §3.5 D47 per-user
group-activation cap and the global max-groups cap fire only at the @mention
pending-row-creation path, never on the auto-join surface — so a registered user
inviting at ~1/sec (under the transport cap) can grow the bot's PASSIVE
memberships unbounded over time. M1-515's active-processing property is intact
(the §3.5 caps still fire at @mention); this ticket closes the passive-membership
ceiling. The user chose to defer this to its architecturally-correct home (the
D47 machine + join tracking) rather than expand M1-515's scope, which explicitly
lists the D47 approval machine in `out_of_scope`.

## Acceptance

1. `GroupInvitationHandler` enforces the §3.5 per-user group-activation cap and
   the global max-groups cap before issuing `/_join`. An invitation that would
   exceed either cap is not joined.
2. A named `GroupInvitationHandlerTest` asserts that once an inviter's activation
   cap (or the global max-groups cap) is reached, further invitations stop
   triggering `/_join` even when the transport rate cap still has tokens.
3. Joined groups are tracked durably enough to evaluate the total caps and the
   tracking survives restart. If a schema change is required, a Flyway migration
   applies cleanly on a fresh DB.
4. `mvn -B verify` is green from the repo root.

## Out-of-scope

The transport rate cap (already added in M1-515) is untouched — this ticket adds
a TOTAL cap, a distinct concern. The @mention-time approval-cap checks in
`GroupApprovalService`/`GroupApprovalCheck` already fire and are not
re-implemented. The D47 approval state machine is unchanged.

## Notes

- This is D47-machine work and almost certainly needs join-tracking persistence
  (a count or membership record keyed by inviter and globally). Sizing fields
  (`files_budget`, `files_scope`, `complexity`, `risk`, `migration_touch`) are set
  to plausible defaults for that shape; revise before `/m1-tick start` once the
  exact storage and cap-config wiring are scoped.
- Reuse the existing profile-driven cap config that §3.5 already reads for the
  @mention path rather than introducing a parallel config surface.
- Round-2 redteam re-audit: `docs/plan/m1/redteam/M1-515-2026-06-29-recheck.md`.
  Original M1-515 finding: `docs/plan/m1/redteam/M1-515-2026-06-29.md`.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-519-*.md
```
