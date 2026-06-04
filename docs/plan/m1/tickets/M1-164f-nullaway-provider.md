---
id: M1-164f
title: "Onboard infochat-provider to NullAway + Error Prone"
status: done
created: 2026-06-03
last_updated: 2026-06-05
blocked_by:
  - M1-164a
files_budget: 65
files_scope:
  - infochat-provider
complexity: medium
risk: low
round_cap: 3
security_relevant: false
migration_touch: false
decomposed_from: M1-164
out_of_scope:
  - build wiring (parent-pom pluginManagement is M1-164a)
  - any module other than infochat-provider
  - retiring scripts/lint-contracts.py or rewriting the §7a docs (umbrella M1-164)
  - Tier-1 Error Prone check promotions (M1-165)
  - production code behavior change (annotations are compile-time)
acceptance:
  - "infochat-provider/pom.xml activates the managed NullAway/Error Prone plugin config"
  - "infochat-provider's genuinely-nullable parameters/returns/fields carry @Nullable (JSpecify); no @NonNull is added; all NullAway:ERROR and Error Prone default-check findings are resolved"
  - "Quarkus-generated sources are confirmed excluded from NullAway analysis (per the M1-164a generated-sources policy); no findings originate from generated code"
  - "mvn -B -pl infochat-provider clean verify exits 0 with NullAway:ERROR active"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Architectural principles
decision_refs:
  - D48
reviews:
  - round: 1
    date: 2026-06-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 51
      added: 261
      removed: 147
escalations:
  - date: 2026-06-04
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — budget-breach. Activating NullAway:ERROR on infochat-provider
      surfaces 200 findings across 57 source files (command=31, messaging=7,
      command/asset=4, summary=3, chat=3, digest=3, group=2, outbox=2,
      chat/tool=1, translation=1). files_budget is 14; the fix touches ~57
      files, a ~4x breach. Ticket Notes pre-authorize escalation: "escalate
      to split per-package only if the diff exceeds files_budget."
revisions:
  - date: 2026-06-04
    reason: budget-breach rework
    snapshot:
      status: escalated
      files_budget: 14
      round_cap: 2
      escalation_reason: budget-breach
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-04
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-164f: Onboard infochat-provider to NullAway + Error Prone

## Context

Per-module onboarding under the M1-164 umbrella (decision D48). Activates the
managed NullAway/Error Prone build config (landed in M1-164a) for
`infochat-provider` — the largest, most dependency-downstream module — and
brings it to green under `NullAway:ERROR`. Onboarding it last (signal-quality
preference) means it is checked against already-annotated `core`/adapter
contracts.

## Acceptance

See frontmatter. Activate the plugin, annotate genuine nullable cases with
`@Nullable`, fix all findings, confirm generated sources are excluded, keep
`mvn -pl infochat-provider verify` green.

## Out-of-scope

See frontmatter. Build wiring is M1-164a; the doc flip + lint retirement is the
umbrella. No `@NonNull` (non-null is the default).

## Notes

- Largest module — if findings are numerous, fixing per-package across rounds is
  fine; escalate to split per-package only if the diff exceeds files_budget.
- Must not be started in parallel with M1-146 (defensive sweep), which also
  touches `infochat-provider` (`InboundRouter` `UserSnapshot.isBanned`).
