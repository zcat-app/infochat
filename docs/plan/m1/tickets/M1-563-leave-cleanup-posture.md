---
id: M1-563
title: Leave-cleanup posture for membership-event-less adapters
status: done
created: 2026-07-04
last_updated: 2026-07-04
escalations:
  - date: 2026-07-04
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      Redteam MEDIUM (PERM-ESCAL), verified against the live text: "This
      diff narrows that commitment in two spec files but leaves schema.md
      carrying the pre-narrowing promise, so the spec set is now
      internally contradictory ... (1) docs/spec/schema.md:143 still
      lists 'a permanent send failure to that specific user surfaced by
      the adapter' as a leave-cleanup trigger — the exact branch the diff
      DELETED ... (2) docs/spec/schema.md:160-162 promises a rejoining
      former admin does NOT reclaim is_group_admin; the diff's own new
      text ... states the opposite." Falsification pass additionally
      found the same stale trigger clause in the design twin
      docs/design/02-schema.md:198-200 (removed_at DDL comment).
reviews:
  - round: 1
    date: 2026-07-04
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 72
      removed: 23
  - round: 2
    date: 2026-07-04
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 301
      removed: 30
redteam_findings:
  - date: 2026-07-04
    category: PERM-ESCAL
    severity: medium
    promise: |
      schema.md §Identity and access (Group membership), the enforcement
      mirror security.md names ("Invariants (also enforced in
      `schema.md`)"), still promises the departed-group-admin defense
      verbatim (docs/spec/schema.md:141-162): a user_left_group event OR
      a permanent send failure to that specific user soft-clears the row
      and also clears is_group_admin, and a rejoining former admin does
      NOT automatically reclaim is_group_admin.
    gap: |
      This diff narrows that commitment in security.md / messaging.md /
      design-06 but leaves schema.md carrying the pre-narrowing promise,
      so the spec set is now internally contradictory: (1) schema.md:143
      still lists "a permanent send failure to that specific user" as a
      leave-cleanup trigger — the branch this diff deleted as unfireable;
      (2) schema.md:160-162 promises no silent admin reclaim on rejoin,
      while the new security.md:445-449 states the opposite for both v1
      production adapters (silent resume). Delivered behavior matches the
      amended security.md; schema.md is the stale over-promise.
    repro: |
      An operator/implementer reading schema.md §Group membership believes
      a group admin who leaves is soft-cleared (slot freed) and cannot
      silently reclaim admin on rejoin. On both v1 production adapters
      (supportsMembershipEvents=false) none of that happens: admin A
      leaves, no left-group signal / per-user PERMANENT failure fires, A's
      is_group_admin row persists, auto-promote never refills the slot,
      and A silently resumes admin on rejoin. schema.md said this could
      not happen; security.md now says it does.
    suggested_fix_class: other
redteam_audits:
  - date: 2026-07-04
    verdict: FINDINGS
    base: main
    head: m1/M1-563-leave-cleanup-posture
    verdict_file: docs/plan/m1/redteam/M1-563-2026-07-04.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      1 medium (PERM-ESCAL): the amendment left docs/spec/schema.md
      §Identity and access (Group membership) — security.md's named
      enforcement mirror, and in this ticket's spec_refs but outside
      files_scope/files_budget=3 — carrying the pre-narrowing
      leave-cleanup promise, contradicting the amended files. Fix needs
      escalate -> refine to widen scope (add docs/spec/schema.md,
      budget 3 -> 4). Out-of-model item is advisory (the silent-resume
      behavior is now a documented, accepted non-commitment); no action.
  - date: 2026-07-04
    verdict: CLEAN
    base: main
    head: m1/M1-563-leave-cleanup-posture
    verdict_file: docs/plan/m1/redteam/M1-563-2026-07-04-reaudit.md
    out_of_model_count: 1
    note: |
      Re-audit after the in-branch remediation (refine widened scope to
      schema.md + 02-schema.md; round-2 diff reconciled both). CLEAN —
      the internal contradiction is resolved; the re-audit also verified
      commands.md:957 stays consistent and that /demote's precondition
      (removed_at IS NULL on the stale admin row) is satisfied, so the
      documented remediation is executable. Out-of-model: the
      silent-resume privilege-retention surface itself — documented,
      accepted v1 non-commitment with /demote remediation; no follow-up
      ticket (the closing mechanism, member-list polling, was already
      rejected once per the ticket's Out-of-scope and stays a v2
      feature decision).
clarity_check:
  date: 2026-07-04
  verdict: WARN
  warnings:
    - "COMPLEXITY-RISK-CALIBRATED: risk: low claimed for a ticket whose
      subject is the Authorization model's admin auto-promote /
      rejoin-resumes-admin behavior; doc-only scope (out_of_scope bars
      any Java/test/migration change) makes it defensible but the ticket
      does not state that inline."
  blockers: []
revisions:
  - date: 2026-07-04
    reason: redteam-finding refine — the amendment left schema.md (and
      its design twin 02-schema.md) carrying the pre-narrowing
      leave-cleanup promise; widened to reconcile them
    prior:
      files_budget: 3
      files_scope:
        - docs/spec/security.md
        - docs/spec/messaging.md
        - docs/design/06-messaging.md
      acceptance_items: 4
blocked_by: []
files_budget: 5
files_scope:
  - docs/spec/security.md
  - docs/spec/messaging.md
  - docs/spec/schema.md
  - docs/design/06-messaging.md
  - docs/design/02-schema.md
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - any Java code, tests, or migrations — this is a spec/design amendment
    ticket; if the user instead chooses a reconciliation MECHANISM
    (member-list polling), that supersedes this ticket via refine or a
    replacement ticket
  - bot-removed / group-deleted cleanup (works today via
    GroupRepository.markRemovedAudited; posture unchanged)
  - the /promote, /demote command semantics (commands.md — the
    remediation path exists and is not modified, only referenced)
  - InMemoryAdapter's supportsMembershipEvents=true declaration and the
    Provider-side MembershipEventHandler machinery (stays live for the
    test double and any future capability-true adapter)
acceptance:
  - "docs/spec/security.md §Authorization model states explicitly: on
    adapters without native membership events (BOTH v1 production
    adapters, per F-live-10 and the SimpleX §6.4.2 posture), a member
    LEAVING a group does not soft-clear their group_membership row or
    is_group_admin flag; a departed group admin therefore still counts
    as the active admin (first-mention auto-promote does not fire) and
    silently resumes admin on rejoin; the documented remediation is
    bot-admin /demote (which frees the slot for auto-promote or
    /promote)."
  - "docs/spec/messaging.md §Required SPI surface — Membership events
    and §Failure handling (User left group) are reconciled with reality:
    the permanent-delivery-failure fallback is qualified to state that
    group-scope sends produce no per-user delivery-failure signal, so
    per-user leave cleanup is an explicit NON-COMMITMENT on
    membership-event-less adapters in v1 — not a promised-but-unfired
    path. Bot-removed and group-deleted cleanup commitments are
    unchanged."
  - "docs/design/06-messaging.md §6.3.6 (User-left-group) mirrors the
    same posture, replacing the 'or surfaces a PERMANENT send failure to
    a specific user in the group' clause with the non-commitment
    statement and the /demote remediation pointer."
  - "docs/spec/schema.md §Identity and access (Group membership) —
    User-departure lifecycle is reconciled with the amended posture
    (redteam-finding refine): the 'or a permanent send failure to that
    specific user surfaced by the adapter' trigger is removed (a
    group-scope send carries no per-user failure, so the trigger can
    never fire); the soft-clear lifecycle is qualified as available only
    on adapters with a native left-group signal
    (supportsMembershipEvents=true — neither v1 production adapter);
    and the 'rejoins does not automatically reclaim is_group_admin'
    promise is qualified with the membership-event-less non-commitment
    (row and is_group_admin persist, departed admin still counts as
    active, silent resume on rejoin, /demote remediation pointer)."
  - "docs/design/02-schema.md §2.1.4 group_membership.removed_at DDL
    comment drops the 'or permanent send failure' trigger clause and
    reflects that the soft-clear fires only on
    supportsMembershipEvents=true adapters (per-user leave cleanup is a
    v1 non-commitment on both production adapters)."
  - "The diff is doc-only (no *.java / pom.xml / src resources), so mvn
    verify is inert per the M1-379 gate; the round log records the
    inert-N/A note."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main (doc-only diff; the testable
      surface is byte-identical to the fork point)
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/schema.md §Identity and access
decision_refs:
  - D47
---

# M1-563: Leave-cleanup posture for membership-event-less adapters

## Context

Follow-up to the M1-562 redteam audit's first out-of-model item
(docs/plan/m1/redteam/M1-562-2026-07-04.md, CLEAN verdict, advisory).
With M1-562 flipping Signal to `supportsMembershipEvents=false`
(spec-mandated — signal-cli 0.14.5 exposes no native per-user
membership signal, F-live-10), BOTH v1 production adapters are now
membership-event-less. The spec's fallback for that class —
"permanent-delivery-failure-driven cleanup" for per-user leaves — is
unfulfillable as written: bot output to a group is addressed to the
GROUP scope, so a departed member never generates a per-user PERMANENT
send failure, and the implemented fallback
(`GroupRepository.markRemovedAudited`) covers bot-removal only.

Consequences today (verified against the spec text): a member who
leaves keeps their `group_membership` row and any `is_group_admin`
flag; security.md §Authorization model's auto-promote trigger list
("groups left without an admin due to demotion or ban", line ~416)
never fires for a leave; leave-then-rejoin silently resumes group
admin. This is not a threat-model violation — the spec never committed
to leave-driven cleanup — but the promise-shaped fallback language
makes the gap look covered when it is not.

This ticket makes the v1 posture explicit and honest in the spec and
design notes: leave-driven cleanup is a stated NON-COMMITMENT on
membership-event-less adapters, with `/demote` as the documented
remediation. No code changes.

## Acceptance

Mirrors the YAML list: (1) security.md §Authorization model states the
leave-gap, its auto-promote consequence, the rejoin-resumes-admin
consequence, and the /demote remediation; (2) messaging.md qualifies
the delivery-failure fallback to bot-removed/group-deleted and states
the per-user non-commitment; (3) design §6.3.6 mirrors it; (4)
schema.md §Group membership User-departure lifecycle drops the
unfireable per-user permanent-send-failure trigger and qualifies the
no-reclaim-on-rejoin promise with the non-commitment (redteam-finding
refine — the round-1 diff left schema.md contradicting the amended
files); (5) design §2.1.4 removed_at comment mirrors it; (6) doc-only
diff, mvn verify inert per M1-379.

## Out-of-scope

No mechanism is built here. The alternative — deriving leave events by
periodically polling adapter member lists and diffing — is a real
option but a feature decision (new I/O, polling cadence, and a
partial-failure story), and per the M1-562 alternatives record it was
already rejected once for the adapter layer. If the user prefers the
mechanism over the non-commitment, refine or replace this ticket rather
than widening it. Commands, code, tests, and the Provider membership
machinery are untouched.

## Notes

- Origin: M1-562 redteam out-of-model item 1 (advisory; the audit was
  CLEAN). The item's own analysis: the pre-M1-562 Signal membership
  events were built against a wire shape that never existed live, so no
  working cleanup behavior was removed — the spec text simply predates
  the knowledge.
- `/demote` works as remediation because the departed member's
  membership row is precisely what went stale — it is still "active"
  (`removed_at IS NULL`), which is what commands.md requires of a
  demote target.
- Keep the amendment scoped to the leave case: bot-removed and
  group-deleted signals flow through separate adapter surfaces
  (messaging.md §Required SPI surface) and their cleanup is implemented
  and unchanged.
