---
id: M1-682
title: "Fail the build when a transport adapter inherits connected()"
status: done
created: 2026-07-23
last_updated: 2026-07-23
blocked_by: [M1-681]
files_budget: 2
files_scope:
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/AdapterCapabilityContractTest.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Removing the `connected()` interface default. The default exists for
    a documented reason — transportless adapters (the in-memory test
    double) have no wire to lose and correctly read connected — so
    deleting it would force a meaningless `return true` onto every such
    adapter. The gap is that TRANSPORT adapters silently inherit it, not
    that the default itself is wrong.
  - >-
    `AdapterReadinessCheck` and the readiness payload. M1-681 already
    folds `connected()` there and both v1 transport adapters report
    honestly, so the payload is correct today; this ticket adds the
    guard that keeps it correct for a future adapter.
  - >-
    SimpleXAdapter's and SignalAdapter's `connected()` implementations.
    Both already override (`SimpleXAdapter.java:749`,
    `SignalAdapter.java:408`) and their semantics are not in question.
  - >-
    Any new adapter. This ticket adds a guard, not a third transport.
acceptance:
  - >-
    A new case in AdapterCapabilityContractTest fails when a transport
    adapter does not declare its own `connected()` — i.e. the method's
    declaring class resolves to the `MessagingAdapter` interface rather
    than to the adapter class. The case enumerates the same
    transport-adapter set the file's existing cross-adapter cases use,
    so a future transport adapter is covered the moment it joins that
    set without any further edit.
  - >-
    The new case passes on the current tree with no production change:
    SimpleXAdapter and SignalAdapter both already override
    `connected()`, so the guard is green at introduction and only a
    future omission turns it red.
  - >-
    The `connected()` javadoc on MessagingAdapter states that the
    default-true applies to transportless adapters only and that a
    transport adapter MUST override it, so the contract a reader sees at
    the declaration matches the contract the test enforces.
  - mvn verify is green from the repo root
test_plan:
  adds: []
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/AdapterCapabilityContractTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Failure handling
  - docs/spec/deployment.md §Health and observability
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-23
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 35
      removed: 9
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-07-23
  verdict: PASS
  warnings: []
  blockers: []
escalation_reason:
---

# M1-682: Fail the build when a transport adapter inherits connected()

## Context

M1-681's 3-auditor `redteam-multi` audit
(`docs/plan/m1/redteam-multi/M1-681-2026-07-23/`) returned CLEAN; kimi
reported this as an out-of-model SPI-hardening candidate. It was
re-verified against the working tree on 2026-07-23 and survived a
falsification pass.

`MessagingAdapter.connected()` carries an interface default of `true`
(`MessagingAdapter.java:362-364`). The default is **correct and
deliberate** — its javadoc states the reason: "Default true so
transportless adapters (the in-memory test double) — which have no wire
to lose — always read connected." The falsification pass confirms
dropping it is the wrong fix: the in-memory adapter would then carry a
meaningless `return true`.

The hazard is narrower. Before M1-681, `connected()` fed only the
`adapter.connection.status` Micrometer gauge, so an adapter that forgot
to override it produced a wrong metric. M1-681 folds `connected()` into
`AdapterReadinessCheck.evaluate`, so the same omission now produces a
**false-green readiness payload** — the exact operator-facing failure
M1-681 exists to remove. M1-681 therefore widened the blast radius of
inheriting the default without adding a guard against it.

Both v1 transport adapters override today (verified:
`SimpleXAdapter.java:749`, `SignalAdapter.java:408`), so the payload is
honest on the current tree. Nothing detects a future omission: there is
no compile-time signal (the default makes the method optional) and no
test signal — `AdapterCapabilityContractTest` already enumerates
transport adapters to pin cross-adapter contracts
(`notConnectedSendIsPermanentForEveryTransportAdapter`,
`notConnectedUpdateAndFinalizeArePermanent`) but asserts nothing about
`connected()`. That file is the natural home: it is exactly the
"every transport adapter must X" pattern this needs, and it runs
without transport or subprocess setup.

Adapters are pluggable by D46 and the spec anticipates more of them, so
"a future adapter" is a designed extension point rather than
speculation.

## Acceptance

See the frontmatter. In short: a reflection-based case in
`AdapterCapabilityContractTest` asserts every transport adapter declares
its own `connected()`, the case is green at introduction (both current
adapters override), and the `connected()` javadoc states the
transport-adapter MUST so the declaration matches the enforced contract.

## Out-of-scope

Removing the interface default, the readiness check itself, the two
existing `connected()` implementations, and adding any new adapter. See
the frontmatter for the full list and the reasoning.

## Notes

- The mechanical check is
  `adapter.getClass().getMethod("connected").getDeclaringClass()` — a
  transport adapter must not resolve it to `MessagingAdapter.class`.
  Reflection is appropriate here precisely because the property is
  "did the author write this method", which no ordinary call can
  observe.
- The alternative kimi named — have readiness fail closed for adapters
  that do not override — was considered and is NOT the chosen route:
  it moves a build-time authoring mistake into a runtime behavior
  change, and would make a legitimately-transportless adapter read
  not-ready. Recorded here so the alternative is not rediscovered.
- Full evidence: `docs/plan/m1/redteam-multi/M1-681-2026-07-23/verdict-kimi.txt`,
  out-of-model item 2.
</content>
