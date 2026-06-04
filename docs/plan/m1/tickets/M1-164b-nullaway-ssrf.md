---
id: M1-164b
title: "Onboard infochat-ssrf to NullAway + Error Prone"
status: done
created: 2026-06-03
last_updated: 2026-06-04
blocked_by:
  - M1-164a
files_budget: 10
files_scope:
  - infochat-ssrf
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
decomposed_from: M1-164
out_of_scope:
  - build wiring (parent-pom pluginManagement is M1-164a)
  - any module other than infochat-ssrf
  - retiring scripts/lint-contracts.py or rewriting the §7a docs (umbrella M1-164)
  - Tier-1 Error Prone check promotions (M1-165)
  - production code behavior change (annotations are compile-time)
acceptance:
  - "infochat-ssrf/pom.xml activates the managed NullAway/Error Prone plugin config"
  - "infochat-ssrf's genuinely-nullable parameters/returns/fields carry @Nullable (JSpecify); no @NonNull is added; all NullAway:ERROR and Error Prone default-check findings are resolved"
  - "mvn -B -pl infochat-ssrf clean verify exits 0 with NullAway:ERROR active"
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
    date: 2026-06-04
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 53
      removed: 35
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-04
  verdict: WARN
  warnings:
    - "SECURITY-FLAG-CONSISTENT: infochat-ssrf contains SsrfGuardedHttpClient (an outbound-HTTP validation boundary). security_relevant: false is defensible because all changes are compile-time annotations only, but consider setting security_relevant: true to ensure a redteam reviewer inspects the diff for any unintended behavioral delta to the SSRF guard."
  blockers: []
---

# M1-164b: Onboard infochat-ssrf to NullAway + Error Prone

## Context

Per-module onboarding under the M1-164 umbrella (decision D48). Activates the
managed NullAway/Error Prone build config (landed in M1-164a) for
`infochat-ssrf` and brings the module to green under `NullAway:ERROR`.

## Acceptance

See frontmatter. Activate the plugin, annotate genuine nullable cases with
`@Nullable`, fix all findings, keep `mvn -pl infochat-ssrf verify` green.

## Out-of-scope

See frontmatter. Build wiring is M1-164a; the doc flip + lint retirement is the
umbrella. No `@NonNull` (non-null is the default).

## Notes

- Independent of the other module subtickets; depends only on M1-164a's wiring.
- Must not be started in parallel with M1-146 (defensive sweep), which also
  touches `infochat-ssrf` (`SsrfGuardedHttpClient`).
