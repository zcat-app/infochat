---
id: M1-164d
title: "Onboard infochat-messaging-adapter to NullAway + Error Prone"
status: pending
created: 2026-06-03
last_updated: 2026-06-03
blocked_by:
  - M1-164a
files_budget: 10
files_scope:
  - infochat-messaging-adapter
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
decomposed_from: M1-164
out_of_scope:
  - build wiring (parent-pom pluginManagement is M1-164a)
  - any module other than infochat-messaging-adapter
  - retiring scripts/lint-contracts.py or rewriting the §7a docs (umbrella M1-164)
  - Tier-1 Error Prone check promotions (M1-165)
  - production code behavior change (annotations are compile-time)
acceptance:
  - "infochat-messaging-adapter/pom.xml activates the managed NullAway/Error Prone plugin config"
  - "infochat-messaging-adapter's genuinely-nullable parameters/returns/fields carry @Nullable (JSpecify); no @NonNull is added; all NullAway:ERROR and Error Prone default-check findings are resolved (incl. the InboundContext nullable accessors and MessagingException constructors the old §7a pass was to cover)"
  - "mvn -B -pl infochat-messaging-adapter clean verify exits 0 with NullAway:ERROR active"
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

# M1-164d: Onboard infochat-messaging-adapter to NullAway + Error Prone

## Context

Per-module onboarding under the M1-164 umbrella (decision D48). Activates the
managed NullAway/Error Prone build config (landed in M1-164a) for
`infochat-messaging-adapter` and brings the module to green under
`NullAway:ERROR`.

## Acceptance

See frontmatter. Activate the plugin, annotate genuine nullable cases with
`@Nullable`, fix all findings, keep `mvn -pl infochat-messaging-adapter verify`
green.

## Out-of-scope

See frontmatter. Build wiring is M1-164a; the doc flip + lint retirement is the
umbrella. No `@NonNull` (non-null is the default).

## Notes

- The `InboundContext.adapterName()/senderContactId()` nullable accessors and
  the `MessagingException` constructors (originally slated for M1-146's §7a
  pass) are covered here under the non-null-default model — annotate the
  genuinely-nullable ones `@Nullable`.
- `SimpleXWebSocketClient` is where the old regex lint false-positived on
  multi-line `new MessagingException(...)`; NullAway parses real ASTs, so that
  noise is gone.
