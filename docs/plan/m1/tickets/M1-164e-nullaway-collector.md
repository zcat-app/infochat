---
id: M1-164e
title: "Onboard infochat-collector to NullAway + Error Prone"
status: done
created: 2026-06-03
last_updated: 2026-06-05
blocked_by:
  - M1-164a
files_budget: 28
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
reviews:
  - round: 1
    date: 2026-06-04
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PARTIAL
    diff_stats:
      files: 29
      added: 178
      removed: 89
  - round: 2
    date: 2026-06-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 28
      added: 201
      removed: 84
escalations:
  - date: 2026-06-04
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — budget-breach. Activating the inherited NullAway:ERROR config on
      infochat-collector main sources surfaces 160 findings (142 NullAway, 16
      UnicodeDirectionalityCharacters, 2 InlineFormatString) across 26 distinct
      main-source files. Resolving them touches those 26 files + pom.xml = 27
      files, vs files_budget: 14. Each finding sits in its own file, so the
      diff cannot be shrunk below 27 within one ticket.
revisions:
  - date: 2026-06-04
    reason: budget-breach rework
    snapshot:
      status: escalated
      files_budget: 14
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

## Round 1 rework

Reviewer verdict round 1: REWORK (acceptance PARTIAL). Address only the item
below, then re-run `mvn -B clean verify` and `/m1-tick review M1-164e`.

1. Resolve the remaining Error Prone `[EscapedEntity]` finding at
   `infochat-collector/src/main/java/app/zcat/infochat/collector/linking/LinkingJob.java`
   line 31: change `last_linked_at &lt; fetched_at` inside the `{@code ...}`
   javadoc block to `last_linked_at < fetched_at` (the same fix already applied
   to the sibling occurrence at ~line 87 and to `Stage2VerdictHandler`), so
   acceptance item 2's "all Error Prone default-check findings are resolved"
   holds. The file is already in the diff; this is a one-line edit.
