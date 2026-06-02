---
id: M1-162
title: "[INVESTIGATE] confirm-or-drop adapter SPI surfaces vs D47"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 8
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
  - docs/design/06-messaging.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - deleting any SPI surface before its intent is reconciled with the D47 design
acceptance:
  - "Reconcile with the D47 group-authorization design whether each surface is live or dead: MessagingAdapter.onMembershipEvent (two incompatible dispatch shapes — SignalAdapter bypasses it, InMemoryAdapter routes through it), the Signal group handler DM-decode duplication with no wired producer, and the ProgressNotifier SPI with zero implementations"
  - "Record per-surface decision (keep + unify, or remove) with rationale"
  - "Implement the chosen outcome: unify onMembershipEvent on one dispatch shape, and either wire or remove the Signal group path / ProgressNotifier per the decision"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
decision_refs:
  - D47
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-162: [INVESTIGATE] confirm-or-drop adapter SPI surfaces vs D47

## Context

Three adapter SPI surfaces whose intent must be reconciled with the D47 design
before action: `MessagingAdapter.onMembershipEvent` creates two incompatible
dispatch shapes (SignalAdapter calls `handler.onEvent` directly; InMemoryAdapter
routes through `onMembershipEvent`); the Signal group handler duplicates DM
decode with no wired producer; `ProgressNotifier` has zero implementations and
may be an intentional v2 seam. The handouts mark all three "RESOLVE or DROP —
reconcile design first," which is exactly the investigate-and-decide shape the
user asked to keep separate from a committed direction.

## Acceptance

See frontmatter. Decide per surface against D47, then implement.

## Out-of-scope

See frontmatter.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-MEMBERSHIP-SPI, §C-SIGNAL-GROUP-DUP,
  §D-PROGRESS-NOTIFIER; `opus-47-full-handout.md` §F-MAINT-24/42/49/84; `opus-47-only-handout.md` §M9.
