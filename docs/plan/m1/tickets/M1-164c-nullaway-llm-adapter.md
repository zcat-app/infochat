---
id: M1-164c
title: "Onboard infochat-llm-adapter to NullAway + Error Prone"
status: pending
created: 2026-06-03
last_updated: 2026-06-03
blocked_by:
  - M1-164a
files_budget: 10
files_scope:
  - infochat-llm-adapter
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
decomposed_from: M1-164
out_of_scope:
  - build wiring (parent-pom pluginManagement is M1-164a)
  - any module other than infochat-llm-adapter
  - retiring scripts/lint-contracts.py or rewriting the §7a docs (umbrella M1-164)
  - Tier-1 Error Prone check promotions (M1-165)
  - production code behavior change (annotations are compile-time)
acceptance:
  - "infochat-llm-adapter/pom.xml activates the managed NullAway/Error Prone plugin config"
  - "infochat-llm-adapter's genuinely-nullable parameters/returns/fields carry @Nullable (JSpecify); no @NonNull is added; all NullAway:ERROR and Error Prone default-check findings are resolved (incl. the LlmHttpSupport Flow.Subscriber override parameters)"
  - "mvn -B -pl infochat-llm-adapter clean verify exits 0 with NullAway:ERROR active"
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

# M1-164c: Onboard infochat-llm-adapter to NullAway + Error Prone

## Context

Per-module onboarding under the M1-164 umbrella (decision D48). Activates the
managed NullAway/Error Prone build config (landed in M1-164a) for
`infochat-llm-adapter` and brings the module to green under `NullAway:ERROR`.

## Acceptance

See frontmatter. Activate the plugin, annotate genuine nullable cases with
`@Nullable`, fix all findings, keep `mvn -pl infochat-llm-adapter verify` green.

## Out-of-scope

See frontmatter. Build wiring is M1-164a; the doc flip + lint retirement is the
umbrella. No `@NonNull` (non-null is the default).

## Notes

- `LlmHttpSupport` overrides a non-annotated JDK interface (`Flow.Subscriber`),
  so its override parameters need explicit annotation rather than inheriting an
  annotated parent — expect findings there.
- Must not be started in parallel with M1-146 (defensive sweep), which also
  touches `infochat-llm-adapter` (`LlmRouter`, `OpenAiCompatibleProvider`).
