---
id: M1-563
title: Leave-cleanup posture for membership-event-less adapters
status: pending
created: 2026-07-04
last_updated: 2026-07-04
blocked_by: []
files_budget: 3
files_scope:
  - docs/spec/security.md
  - docs/spec/messaging.md
  - docs/design/06-messaging.md
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
the per-user non-commitment; (3) design §6.3.6 mirrors it; (4) doc-only
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
