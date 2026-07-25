---
id: M1-698
title: "Guard the outbound chokepoint routing invariant"
status: pending
created: 2026-07-25
last_updated: 2026-07-25
blocked_by: []
remediates: M1-691
files_budget: 4
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/OutboundChokepointArchTest.java
  - infochat-provider/pom.xml
  - docs/spec/security.md
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The `](` adjacency break itself, the closed-list command redaction, and
    anything inside LlmOutputSanitizer. M1-691 shipped the break at the
    chokepoint; this ticket only guards the ROUTING invariant that makes the
    chokepoint total, not the transform or the redaction control.
  - >-
    OutboundDelivery's five entry points and DigestDelivery.RecordingAdapter.
    The guard asserts the EXISTING call-site set is closed; it must not change
    what those classes do or how DigestDelivery wraps the adapter.
  - >-
    Audit logging on the `](` break. The break is hygiene (like the flatten
    pass), not a per-occurrence security event like closed-list redaction;
    adding an audit row is a separate design question, not this guard.
acceptance:
  - >-
    An architecture test (ArchUnit, added as a test-scope dependency to
    infochat-provider/pom.xml) asserts that the only classes in the provider
    main source that call MessagingAdapter.send / .update / .finalizeMessage
    are OutboundDelivery and DigestDelivery.RecordingAdapter. A new direct
    call from any other provider main class fails the build.
  - >-
    The test scopes to PRODUCTION code only — test doubles that implement
    MessagingAdapter (FailingMessagingAdapter, RecordingMessagingAdapter,
    ScriptedAdapter) and tests that drive an adapter through OutboundDelivery
    are not flagged.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/OutboundChokepointArchTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-698: Guard the outbound chokepoint routing invariant

## Context

M1-691 relocated the `](` no-link guarantee to `OutboundDelivery`, the
single Provider-side outbound chokepoint, and restated the spec claim
(`docs/spec/security.md` §"Sanitizer output never contains `](`") as an
OUTBOUND property: every outbound body has its `](` adjacency broken
before the transport. The truth of that claim depends on every outbound
path routing through `OutboundDelivery`'s five entry points.

A `/redteam-multi` audit (kimi + opencode, 2026-07-25; evidence at
`docs/plan/m1/redteam-multi/M1-691-2026-07-25/`) flagged that this
routing is a CONVENTION enforced by census, not a mechanical guard:
nothing fails the build if a future caller invokes `adapter.send`
directly, and such a bypass would be invisible — the `](` break emits no
WARN and no audit row, unlike the closed-list control. The closed-list
match set is held to be "a structural property of the codebase rather
than a discipline" (`docs/spec/security.md` §Match-set derivation)
precisely because a CI registry check fails the build on a missing
entry. This ticket gives the outbound chokepoint the same shape: a build
that BREAKS when the invariant is violated, rather than a census a
developer must remember to re-run.

No current bypass exists — the census below confirms every call site is
already inside the chokepoint — so this is preventive hardening, not a
defect fix. The spec claim stays as M1-691 wrote it (it is honest today
and scoped to the chokepoint); this ticket makes it honest-by-
construction rather than honest-by-convention.

## Census

The invariant's distinguishing token is a call to one of the three
mutating `MessagingAdapter` methods. Enumerate mechanically over the
provider main source:

    rg -n "\.send\(|\.update\(|\.finalizeMessage\(" \
      infochat-provider/src/main/java --glob '*.java'

Re-run on 2026-07-25 returns:

| Site | Disposition |
|---|---|
| `OutboundDelivery.java:143,154,190` (`adapter.send`) | allow — the chokepoint |
| `OutboundDelivery.java:221` (`adapter.update`) | allow — `updateInPlace` |
| `OutboundDelivery.java:234` (`adapter.finalizeMessage`) | allow — `finalizeInPlace` |
| `DigestDelivery.java:172` (`delegate.send`) | allow — `RecordingAdapter`, invoked BY `OutboundDelivery.deliverSequenceToGroup` |
| `DigestDelivery.java:204` (`delegate.update`) | allow — `RecordingAdapter` delegation |
| `DigestDelivery.java:207` (`delegate.finalizeMessage`) | allow — `RecordingAdapter` delegation |
| `DigestCategoryDeliveryRepository.java:48` | N/A — a javadoc reference, not a call |

The allowlist is therefore exactly two classes: `OutboundDelivery` and
`DigestDelivery.RecordingAdapter`. The arch test encodes that set; any
seventh site a future change introduces fails the build. (Test doubles
that IMPLEMENT `MessagingAdapter` are not call sites of its methods and
are out of the invariant's scope — see acceptance item 2.)

## Acceptance

- An ArchUnit test (`OutboundChokepointArchTest`) fails the build if any
  provider main class other than `OutboundDelivery` and
  `DigestDelivery.RecordingAdapter` calls `MessagingAdapter.send`,
  `.update`, or `.finalizeMessage`. ArchUnit is added as a test-scope
  dependency in `infochat-provider/pom.xml`.
- The test analyzes PRODUCTION classes only, so test doubles
  (`FailingMessagingAdapter`, `RecordingMessagingAdapter`,
  `ScriptedAdapter`) and tests that drive an adapter through
  `OutboundDelivery` are not flagged.
- `mvn verify` from the repo root is green.

## Out-of-scope

The `](` adjacency break, the closed-list redaction, and
`LlmOutputSanitizer` are untouched — M1-691 shipped the transform and
this ticket guards only the ROUTING invariant that makes the chokepoint
total. `OutboundDelivery`'s five entry points and
`DigestDelivery.RecordingAdapter` keep their current behaviour; the
guard asserts the existing call-site set is closed, it does not change
what those classes do. Audit logging on the `](` break is deliberately
not added here: the break is hygiene (like the flatten pass), not a
per-occurrence security event like closed-list redaction, and whether to
audit it is a separate design question.

## Notes

- **ArchUnit scoping.** ArchUnit's `ClassFileImporter` analyzes the test
  classpath, which mixes main and test classes. Restrict the import to
  production locations (e.g. `importLocations` of
  `infochat-provider/target/classes`, or a `LocationFilter` on
  `/classes/`) so test doubles that implement `MessagingAdapter` are not
  mistaken for violating callers. The point of the guard is the main
  source set, not the test set.
- **Allowlist is two classes, not one.** `DigestDelivery.RecordingAdapter`
  calls `delegate.send` — but it is invoked BY
  `OutboundDelivery.deliverSequenceToGroup`, so its call is inside the
  chokepoint, not around it. Allowing it is correct; allowing only
  `OutboundDelivery` would force a false-positive carve-out every time
  the decorator pattern is reused.
- **Why not soften the spec instead.** The M1-691 redteam disposition
  (`docs/plan/m1/redteam-multi/M1-691-2026-07-25/disposition.md`)
  considered softening the spec claim and rejected it: the claim is
  already scoped to the chokepoint ("carried once at OutboundDelivery",
  enumerating the four path types) and does not overclaim today. Making
  the invariant structural is the proportionate response; weakening a
  true claim is not.
- **Alternative considered: a grep-based CI check.** A shell grep in CI
  is cheaper than an ArchUnit dependency but brittle on the call-site
  shape (the receiver is a parameter, not `this`) and cannot reason
  about the `delegate.` decorator pattern. ArchUnit resolves the
  receiver type, so it is the right tool.
