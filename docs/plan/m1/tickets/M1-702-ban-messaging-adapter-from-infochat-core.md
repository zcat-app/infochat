---
id: M1-702
title: "Ban infochat-messaging-adapter from infochat-core"
status: done
created: 2026-07-26
last_updated: 2026-07-30
blocked_by: []
remediates: M1-698
files_budget: 1
files_scope:
  - infochat-core/pom.xml
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The M1-698 OutboundChokepointArchTest guard and its spec paragraph
    (docs/spec/security.md §"The chokepoint routing is build-guarded").
    This ticket adds only the infochat-core enforcer rule that the
    guard's module-scope residual points at; it does not change the
    guard, its allowlist, or the spec claim.
  - >-
    The existing bannedDependencies rules in infochat-collector,
    infochat-llm-adapter, infochat-ssrf, and infochat-messaging-adapter.
    Those are already correct and enforced; this ticket mirrors their
    shape into infochat-core, it does not edit them.
  - >-
    Widening OutboundChokepointArchTest's package import to scan sibling
    modules. That is a separate question; this ticket makes the
    messaging-adapter dependency edge mechanically impossible from
    infochat-core so the guard's provider-module scope stays the
    universe of possible senders.
acceptance:
  - >-
    infochat-core/pom.xml adds a maven-enforcer-plugin execution (phase
    validate, goal enforce) with a bannedDependencies rule excluding
    app.zcat.infochat:infochat-messaging-adapter, mirroring the stanza
    in infochat-collector/pom.xml (execution id
    ban-messaging-adapter-from-collector). Version comes from the
    parent pluginManagement; no <version> on the plugin.
  - mvn verify from the repo root is green.
  - >-
    Adding a <dependency> on app.zcat.infochat:infochat-messaging-adapter
    to infochat-core/pom.xml fails the build at the validate phase with
    the bannedDependencies message (the rule has teeth, not just prose).
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/09-reference.md §9.1 Module dependency DAG
  - docs/spec/security.md §LLM output sanitizer
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-30
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 161
      removed: 9
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-30
    verdict: CLEAN
    base: 5a63ff1a26eb70229139ee9020a0baa767551e93
    head: "<working tree>"
    verdict_file: docs/plan/m1/redteam/M1-702-2026-07-30.md
    out_of_model_count: 3
    note: |
      Ran at the /m1-tick run redteam gate, ahead of review, against the
      uncommitted working-tree diff (branch had zero commits). No findings:
      the diff is a build-time bannedDependencies rule that only removes a
      capability, so it opens no new attack surface. Three out-of-model
      observations, none blocking: (1) docs/spec/security.md's residual list
      now understates the shipped control, since core's edge is no longer
      convention-only — a spec: commit could retire that clause; (2)
      reflective invocation and (3) direct-transport access remain accepted
      residual risk, unchanged by this diff, as does the messaging-adapter
      module's own necessary use of the SPI.
clarity_check:
  date: 2026-07-30
  verdict: PASS
  warnings: []
  blockers: []
escalation_reason:
---

# M1-702: Ban infochat-messaging-adapter from infochat-core

## Context

`docs/design/09-reference.md` §9.1 states that `infochat-core` "promises
no user-facing surface and no `messaging-adapter` dependency," and the
DAG diagram puts core in the leaf row with `(none)` dependencies.
Unlike `infochat-collector` — whose no-messaging-adapter edge is
enforced by a `maven-enforcer-plugin` `bannedDependencies` rule
(`infochat-collector/pom.xml`, documented at 09-reference.md line 41) —
`infochat-core` has **no enforcer rule**: today only convention (nobody
happened to add the dependency) keeps core off `infochat-messaging-adapter`.

M1-698 shipped `OutboundChokepointArchTest`, which guards the outbound
chokepoint routing invariant by failing the build if any class in
`app.zcat.infochat.provider..` other than `OutboundDelivery` and
`DigestDelivery.RecordingAdapter` holds a call or method-reference edge
to `MessagingAdapter.send/update/finalizeMessage`. The guard's reach is
the provider module, and the spec paragraph it ships
(`docs/spec/security.md` §"The chokepoint routing is build-guarded")
bounds the sibling-module residual by the module DAG: collector,
llm-adapter, ssrf, and messaging-adapter are enforcer-blocked from the
messaging-adapter dependency, **but infochat-core's edge is
convention-only and tracked as a follow-up**. A `/redteam-multi` audit
(kimi + opencode, 2026-07-26, M1-698 round 3; evidence at
`docs/plan/m1/redteam-multi/M1-698-2026-07-26-r3/verdict-opencode.txt`)
flagged that this falsifies any "the DAG makes the provider scope total"
claim: a class compiled under `app.zcat.infochat.core..` could call
`adapter.send(...)` and be invisible to the provider-scoped guard, with
no enforcer trip to keep the dependency edge from opening. This ticket
makes core's no-messaging-adapter promise mechanical, matching
collector's shape, so the DAG property the M1-698 guard relies on is
structural rather than conventional for the core edge too.

## Acceptance

- `infochat-core/pom.xml` adds a `maven-enforcer-plugin` execution
  (phase `validate`, goal `enforce`) whose `bannedDependencies` rule
  excludes `app.zcat.infochat:infochat-messaging-adapter`, mirroring the
  `ban-messaging-adapter-from-collector` stanza in
  `infochat-collector/pom.xml` (same plugin groupId/artifactId, version
  inherited from the parent `<pluginManagement>`, `<fail>true</fail>`).
- `mvn verify` from the repo root is green.
- Temporarily adding
  `<dependency><groupId>app.zcat.infochat</groupId><artifactId>infochat-messaging-adapter</artifactId></dependency>`
  to `infochat-core/pom.xml` fails the build at the `validate` phase with
  the `bannedDependencies` message — i.e. the rule has teeth, not just
  prose. (Revert the temporary dependency before committing.)

## Out-of-scope

The M1-698 guard (`OutboundChokepointArchTest`) and its spec paragraph
are not modified — this ticket adds only the core enforcer rule the
guard's module-scope residual points at. The existing `bannedDependencies`
rules in collector / llm-adapter / ssrf / messaging-adapter are already
correct; this ticket mirrors their shape into core, it does not edit
them. Widening `OutboundChokepointArchTest`'s `importPackages` to scan
sibling modules is a separate question and explicitly not done here:
once the core edge is mechanically impossible, the guard's
provider-module scope stays the universe of possible senders and the
sibling-module residual collapses to zero for core.

## Notes

- **Mirror the collector stanza, including the comment.** The collector
  rule carries an explanatory comment naming the module-DAG rule and why
  the ban lives per-module (a parent-level ban would also break the
  Provider, which depends on `infochat-messaging-adapter` legitimately).
  The core stanza should carry the analogous comment, citing
  `docs/design/09-reference.md` §9.1 and the core row ("promises no
  user-facing surface and no messaging-adapter dependency").
- **Why no separate test file.** The enforcer rule IS the check: it runs
  at every build's `validate` phase and fails the build on a violation.
  `files_budget: 1` (just `infochat-core/pom.xml`); acceptance item 3 is
  verified by temporarily adding the dependency and observing the
  `validate`-phase failure, then reverting.
- **Why `security_relevant: true`.** The ticket closes a gap surfaced by
  a `/redteam` finding on a `security_relevant` ticket (M1-698): the
  module-DAG edge the chokepoint guard's scope rests on. The change
  itself is a build-time dependency ban (no secrets/auth/input handling),
  but it hardens a security-relevant module boundary, so the `/redteam`
  gate runs on it.
- **Census of the missing-rule class, for grounding.** Of the five
  non-provider modules, four already enforce the messaging-adapter ban:
  `infochat-collector/pom.xml`,
  `infochat-llm-adapter/pom.xml`,
  `infochat-ssrf/pom.xml`,
  `infochat-messaging-adapter/pom.xml` (siblings ban). Only
  `infochat-core/pom.xml` is missing — a single-instance ticket, no
  §Census section needed.
