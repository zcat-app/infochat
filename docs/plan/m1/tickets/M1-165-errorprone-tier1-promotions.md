---
id: M1-165
title: "Promote Tier-1 Error Prone checks to ERROR (repo-wide)"
status: done
created: 2026-06-03
last_updated: 2026-06-05
blocked_by:
  - M1-164
files_budget: 24
files_scope:
  - pom.xml
  - infochat-core
  - infochat-ssrf
  - infochat-llm-adapter
  - infochat-messaging-adapter
  - infochat-collector
  - infochat-provider
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the NullAway rollout (umbrella M1-164 + subtickets M1-164a..f)
  - enabling any Error Prone check beyond the named Tier-1 set
  - promoting UnusedVariable / UnusedMethod / FieldCanBeFinal (CDI/Quarkus reflection causes false positives — left at default severity deliberately)
  - production code behavior change (the promoted checks are corrected mechanically without altering behavior)
acceptance:
  - "Parent-pom maven-compiler-plugin pluginManagement promotes these Error Prone checks to ERROR (-Xep:<Check>:ERROR): MissingOverride, ReferenceEquality, FallThrough, MissingCasesInEnumSwitch, DefaultCharset, JdkObsolete, FutureReturnValueIgnored"
  - "Every finding from the promoted checks is fixed across all modules (no -Xep suppression and no per-site @SuppressWarnings used to dodge a finding rather than fix it)"
  - "mvn -B clean verify from the repo root exits 0 with the Tier-1 checks at ERROR"
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
      files: 3
      added: 13
      removed: 5
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-05
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-165: Promote Tier-1 Error Prone checks to ERROR (repo-wide)

## Context

Follow-up to the M1-164 NullAway/Error Prone adoption (decision D48). With Error
Prone wired into the build (its default checks already at ERROR per module),
this ticket promotes a curated Tier-1 set of WARNING-level checks to ERROR
repo-wide and fixes the resulting findings. Tier-1 is chosen to be low-noise and
to mirror the project's documented style rules (exhaustive switch dispatch,
determinism, no legacy JDK APIs).

## Acceptance

See frontmatter. Promote the named checks in the parent-pom pluginManagement,
fix every finding across all modules, keep the full `mvn verify` green.

## Out-of-scope

See frontmatter. This ticket only promotes the named Tier-1 checks — not the
broader Tier-2 set, and not `UnusedVariable`/`UnusedMethod`/`FieldCanBeFinal`
(those false-positive on CDI/Quarkus reflection and stay at default severity).
The NullAway rollout is M1-164 and its subtickets.

## Notes

- Tier-1 rationale: `MissingOverride` (clarity), `ReferenceEquality` (`==` on
  value objects), `FallThrough` + `MissingCasesInEnumSwitch` (the project's
  "prefer switch expressions / exhaustiveness" style rule), `DefaultCharset`
  (determinism/portability), `JdkObsolete` (keep off legacy APIs on JDK 25),
  `FutureReturnValueIgnored` (dropped async results; low risk given the
  blocking-on-virtual-threads style).
- If any single check yields a large finding set that would blow files_budget,
  escalate to split that check into its own follow-up rather than weakening the
  budget — keep each diff reviewable.
- Tier-2 candidates for a later ticket: NarrowingCompoundAssignment, BadImport,
  OperatorPrecedence, ArgumentSelectionDefectChecker.
