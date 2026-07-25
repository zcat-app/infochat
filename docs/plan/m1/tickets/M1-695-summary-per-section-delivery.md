---
id: M1-695
title: "Deliver the default /summary as one message per category section"
status: pending
created: 2026-07-25
last_updated: 2026-07-25
blocked_by:
  - M1-694
decomposed_from: M1-687
files_budget: 8
files_scope: []
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope: []
acceptance: []
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-695: Deliver the default /summary as one message per category section

## Context

Split out of **M1-687** (failed the `complexity: high` plan gate twice,
2026-07-25). M1-694 makes the default `/summary` render the categorized form
as a single joined body; this ticket splits that body into one outbound
message per category section, so a single category can be forwarded on its
own to someone not using the app — the shape the user explicitly asked for,
and D63's shape applied to the `/summary` scope.

This is separated from M1-694 because it is a **delivery-lifecycle** change
with its own blast radius, not a render change. Paying for it once, here,
keeps M1-694's test churn purely about output bytes.

## Census

**Required — class-scoped.** The class is "every test that asserts the
message COUNT of a default `/summary`". These assert shapes like
`assertEquals(1, adapter.finalizedBodies().size())` and
`assertEquals(1, adapter.sentMessages().size())`, which per-section delivery
changes whenever the fixture produces more than one category section.

Enumerate by invocation, then filter to count assertions — do NOT reuse
M1-687's label-based predicate, which missed bare-content fields entirely:

```
grep -rln '"/summary\|"/retry\| /summary\| /retry' \
  --include=*.java infochat-provider/src/test/java
```

Known count-asserting sites from M1-687's plan passes (re-verify):
`TranslationPipelineIT:134-135,186-187` (`exactly one placeholder send`,
`exactly one finalized summary message`), `SummaryAdapterScopeIT:113-116`,
`SummaryGroupScopeIT:138`, `GoldenPathJourneyIT:245-246`.

Note that most existing fixtures seed 2–3 posts sharing few tags, and
`infochat.digest.category-min-clusters` defaults to 3, so they collapse to a
single Other section and their count assertions may survive unchanged.
Verify per fixture; do not assume in either direction.

`InMemoryConversationBackend` is a scenario helper that watermarks
`sentMessages()`/`finalizedBodies()` and returns every reply since the mark,
matching if any one matches — verified unaffected by per-section delivery,
but give it a row.

## Acceptance

TO BE WRITTEN. Carried forward from M1-687:

- in the default form, `/summary` is delivered as one outbound message per
  category section, not one joined body
- the closing affordance lands on the last section exactly once
- the placeholder/abandonment safety net still holds — `terminateAbandoned`
  (M1-334 / M1-611) must not regress, and no placeholder is left dangling
- the over-cap degraded form is delivered per-section too

## Out-of-scope

TO BE WRITTEN. At minimum: the render itself (M1-694), `/retry`'s render form
(M1-696) and `/retry`'s delivery shape (it returns one `OutboundMessage` and
stays that way), and the scheduled digest's delivery path (`DigestDelivery`,
`DigestWorker`, `DigestScheduler`, `DigestRetryService`).

## Notes

The seam is the blocker M1-687's second plan pass surfaced, and it is the
whole reason this is its own ticket:

- `SummaryCommandHandler:168-169` injects the **SPI type**
  `ProgressNotifier`, which declares exactly `publish` / `complete` / `fail`
  (`ProgressNotifier.java:68,79,89`). There is no multi-message terminal on
  it.
- Three candidate seams, each with a different cost — pick one and record
  why:
  1. widen the `ProgressNotifier` SPI (touches the messaging module and every
     implementor),
  2. inject the concrete `StageProgressNotifier` in the handler
     (`completeDelivered`, `StageProgressNotifier.java:265`, is the in-repo
     precedent for a public non-SPI terminal),
  3. inject `OutboundDelivery` + `AdapterRegistry` directly and resolve the
     adapter in the handler (duplicates `StageProgressNotifier.resolveAdapter`).
- Any of the three drags in `RecordingProgressNotifier.java`
  (`provider/command/`, `implements ProgressNotifier`), which
  `SummaryCommandHandlerTest:98,126` wires — budget for it.
- `DigestDelivery.deliver` cannot be reused: it constructs `ScopeRef.Group`
  targets and writes per-group delivery records (`DigestDelivery.java:110-135`).
  `OutboundDelivery.deliver(adapter, msg)` is scope-generic and is the likely
  seam.
- The obvious shape is: finalize the placeholder with the first section, send
  the rest as fresh messages.
