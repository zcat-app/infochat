---
id: M1-794
title: "Guard against empty sanitized bodies at delivery"
status: pending
created: 2026-08-08
last_updated: 2026-08-08
flow: tick
reproduction: >-
  to-be-written: OutboundDeliveryTest#emptyBodyIsRefusedNotShipped
  — sanitize() can return "" for a non-empty reply (P8, pinned by
  LlmOutputSanitizerPostconditionTest.deletionShapesMatchTheirDocumentedPostconditions:
  a markers-only reply reduces to nothing) and NO guard exists between
  sanitize() and OutboundDelivery, so an empty body reaches the
  transport. The display-hit cache (TranslationPipeline.java:560) would
  also store the empty value for 24h. RED on main today: the test feeds
  an empty body into OutboundDelivery's entry path and asserts it is
  refused, not shipped; main ships it.
analysis_ref: docs/plan/m1/tick-analysis/llm-output-leaks-scaffolding-markdown.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/OutboundDelivery.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/OutboundDeliveryTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    MOVING THE PROTOCOL-TOKEN DETECTORS — M1-791 owns that; this guard is
    about the empty-body shape only.
  - >-
    THE TRANSLATION SANITY CHECK OPERAND — filed separately as M1-793;
    this ticket guards the delivery path, that ticket decides the
    pipeline fallback.
  - >-
    CHANGING any sanitize() pass or the closed list (P11) — the guard
    sits at delivery, downstream of the transform.
acceptance:
  - OutboundDeliveryTest.emptyBodyIsRefusedNotShipped passes — REPRODUCTION.
  - OutboundDeliveryTest.emptyBodyRefusalLogsRatherThanSending passes — FAILURE-MODE: the refusal is observable (a WARN), no transport call is made, and the deterministic command surface (docs/spec/llm.md §Failure handling) is never touched.
  - LlmOutputSanitizerPostconditionTest.deletionShapesMatchTheirDocumentedPostconditions still passes UNCHANGED — the pin that documents "" is a possible sanitize() return today; the follow-up's diff must update the pin deliberately, never silently, and the review gate checks this diff touches the pin only through this ticket's authorization.
  - mvn -B -pl infochat-provider -am verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/OutboundDeliveryTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Failure handling (recap)
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-794: Guard against empty sanitized bodies at delivery

## Context

Filed by M1-792's census (P8) and the analysis's pitfall P8, pinned as
documented by
`LlmOutputSanitizerPostconditionTest.deletionShapesMatchTheirDocumentedPostconditions`:
a markers-only reply sanitizes to "" today (the M1-789 scaffolding strip
drops every line), and no empty-body guard exists between sanitize() and
OutboundDelivery. TranslationPipeline.java:517's "never empty for a
non-empty input" promise is the broken promise the census records; the
display-hit cache at TranslationPipeline.java:560 would also cache the
empty value for 24h.

## Root cause

The M1-789 deleting pass introduced a whole-reply-emptying shape; the
delivery path (OutboundDelivery) validates routing and adjacency but has
no body-emptiness refusal, so "" ships as a message.

## Pitfalls

- P1: the guard must not break the deterministic command surface — a
  deliberately empty deterministic reply (e.g. an empty list response) is
  bot-authored and must stay deliverable; only LLM-authored bodies that
  EMPTIED through the transform are refused, so the guard sits at the
  sanitizer-output call sites or keys off the audit trail, never on
  OutboundDelivery's generic body.
- P2: the pin that documents "" as a possible sanitize() return today
  (deletionShapesMatchTheirDocumentedPostconditions) must be updated
  deliberately IN THIS TICKET's diff — a silent test-side change without
  this ticket's authorization is an engineering-rules §8 violation.

## Approach

- **Files to touch:** the two in `files_scope`.
- **Steps, in order:**
  1. Write the reproduction, run RED.
  2. Add the empty-body refusal on the LLM-authored delivery path (WARN +
     no transport call); leave deterministic command output untouched.
  3. Update the deletionShapes pin's ""-return comment/assertion to cite
     this ticket as the flipper.
- **Controls to preserve (§10):** every non-empty delivery path, the
  deterministic-command exemption, the `](`-free adjacency guarantee.
- **Pitfall→mitigation:** P1→the guard keys off the LLM-authored surface;
  P2→the pin flip lands in this diff with the ticket cited.

## Definition of done

The reproduction and the failure-mode test pass; deterministic empty
output is unaffected; the deletionShapes pin documents this ticket as the
flipper; provider verify is green.

## Verification

- P1 → `OutboundDeliveryTest.emptyBodyRefusalLogsRatherThanSending` plus
  the existing deterministic-empty tests running unchanged — FAILURE-MODE:
  an empty LLM-authored body is refused; a deliberately empty
  deterministic reply still ships.
- P2 → the deletionShapes pin's ""-return assertion/comment changes only
  with this ticket's citation in the diff.
- acceptance item 1 → `emptyBodyIsRefusedNotShipped` (the reproduction).
- acceptance item 3 → `mvn -B -pl infochat-provider -am verify` with
  `LlmOutputSanitizerPostconditionTest.deletionShapesMatchTheirDocumentedPostconditions`
  passing.

## Out-of-scope

Named in `out_of_scope`: no detector moves (M1-791), no translation
check-operand change (M1-793), no sanitize()/closed-list change (P11).

## Census

This ticket is filed BY M1-792's census; its own census is the single P8
row plus the M1-793 sibling.
