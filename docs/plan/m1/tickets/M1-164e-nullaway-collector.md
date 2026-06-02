---
id: M1-164e
title: "Onboard infochat-collector to NullAway + Error Prone"
status: pending
created: 2026-06-03
last_updated: 2026-06-03
blocked_by:
  - M1-164a
files_budget: 14
files_scope:
  - infochat-collector
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
decomposed_from: M1-164
out_of_scope:
  - build wiring (parent-pom pluginManagement is M1-164a)
  - any module other than infochat-collector
  - retiring scripts/lint-contracts.py or rewriting the §7a docs (umbrella M1-164)
  - Tier-1 Error Prone check promotions (M1-165)
  - production code behavior change (annotations are compile-time)
acceptance:
  - "infochat-collector/pom.xml activates the managed NullAway/Error Prone plugin config"
  - "infochat-collector's genuinely-nullable parameters/returns/fields carry @Nullable (JSpecify); no @NonNull is added; all NullAway:ERROR and Error Prone default-check findings are resolved"
  - "Quarkus-generated sources are confirmed excluded from NullAway analysis (per the M1-164a generated-sources policy); no findings originate from generated code"
  - "mvn -B -pl infochat-collector clean verify exits 0 with NullAway:ERROR active"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Architectural principles
decision_refs:
  - D48
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-164e: Onboard infochat-collector to NullAway + Error Prone

## Context

Per-module onboarding under the M1-164 umbrella (decision D48). Activates the
managed NullAway/Error Prone build config (landed in M1-164a) for
`infochat-collector` — a Quarkus application module, so the generated-sources
exclusion policy first matters here — and brings the module to green under
`NullAway:ERROR`.

## Acceptance

See frontmatter. Activate the plugin, annotate genuine nullable cases with
`@Nullable`, fix all findings, confirm generated sources are excluded, keep
`mvn -pl infochat-collector verify` green.

## Out-of-scope

See frontmatter. Build wiring is M1-164a; the doc flip + lint retirement is the
umbrella. No `@NonNull` (non-null is the default).

## Notes

- First Quarkus-app module in the rollout: verify the M1-164a generated-sources
  exclusion (`-XepExcludedPaths` / `TreatGeneratedAsUnannotated`) actually
  suppresses findings from Quarkus-generated code.
- Larger module — if findings are numerous, fixing per-package across rounds is
  fine; escalate to split per-package only if the diff exceeds files_budget.
- Must not be started in parallel with M1-146 (defensive sweep), which also
  touches `infochat-collector` (`AssetSnapshotFetcher`, `BootstrapAssetsLoader`).
