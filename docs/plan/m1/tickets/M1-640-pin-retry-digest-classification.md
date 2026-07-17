---
id: M1-640
title: "Pin the /retry --digest non-interruptible classification with a test"
status: done
created: 2026-07-17
last_updated: 2026-07-17
clarity_check:
  date: 2026-07-17
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 1
    date: 2026-07-17
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 108
      removed: 8
redteam_findings: []
redteam_audits:
  - date: 2026-07-17
    verdict: CLEAN
    base: 7b9c92d35172c6b87dd9a6d61ee56bb2a8cd1b22
    head: working-tree (uncommitted, branch m1/M1-640-pin-retry-digest-classification)
    verdict_file: docs/plan/m1/redteam/M1-640-2026-07-17.md
    out_of_model_count: 0
    note: |
      Pre-commit --in-progress audit of the visibility-widen + new
      classification test. CLEAN: the diff adds no attack surface and
      hardens the D35/D61 concurrency boundary it audits. Nothing feeds a
      follow-up ticket.
blocked_by: []
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterInterruptibleClassificationTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Changing the isInterruptible classification itself, or the D35
    interruptible/non-interruptible membership of any command. This ticket
    pins the CURRENT behavior; it does not reclassify anything.
  - >-
    Pinning D61 structural precondition (b) — "every inline LLM surface draws
    from the per-minute LLM bucket". That precondition stays process-enforced
    (D61's revisit clause + RetryCommandHandler's rate-cap gate); it is not a
    pure classification function and has no equivalent one-line pin. Only
    precondition (a) — the inline-on-transport classification — is mechanically
    pinnable and in scope here.
  - >-
    Refactoring, deduplicating, or otherwise coupling isInterruptible and
    RetryCommandHandler.hasFlag into a shared helper. They are deliberately
    two small mirror predicates (InboundRouter javadoc). Extracting a shared
    unit is a larger design change and is not this ticket's job; the test
    pins the router side's answer, which is the D61-load-bearing one.
  - >-
    Any change to InterruptibleDispatcher, the M1-636 per-user cap, or the
    dispatch-knob boot coupling (all settled by M1-636 / M1-639).
acceptance:
  - >-
    A new test class InboundRouterInterruptibleClassificationTest pins the D35
    dispatch classification as a table, load-bearing that /retry --digest is
    NON-interruptible: isInterruptible("/retry --digest") is false (the D61
    self-serialization precondition — a future refactor moving it onto the
    pooled/async path fails this test instead of silently voiding the bound),
    while isInterruptible("/retry"), isInterruptible("/summary"), and a
    non-slash chat body are all true, and an unknown slash command is false.
  - >-
    The same test pins the whitespace-token equality edge the classification
    shares with RetryCommandHandler.hasFlag (InboundRouter javadoc: "the two
    classifications may not drift"): a token that merely CONTAINS the flag
    substring — e.g. "/retry --digestx" or "/retry x--digest" — stays
    interruptible (true), and "/retry foo --digest" (flag as a later token)
    is non-interruptible (false). This is the assertion a naive
    contains("--digest") rewrite would fail.
  - >-
    InboundRouter.isInterruptible is widened from private to package-private
    (static, same package) so the same-package test calls it directly; no
    other visibility or behavior change. (An equivalent behavioral IT that
    observes inline-vs-offloaded dispatch is acceptable but heavier — the
    surgical path is the direct classification unit test.)
  - mvn -pl infochat-provider -am verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterInterruptibleClassificationTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
decision_refs:
  - D35
  - D61
---

# M1-640: Pin the /retry --digest non-interruptible classification with a test

## Context

The M1-639 pre-commit red-team (`docs/plan/m1/redteam/M1-639-2026-07-17.md`,
CLEAN) raised one out-of-model note. D61 records that `/retry --digest` stays
outside the M1-636 per-user concurrency cap, and its soundness rests on two
structural preconditions:

- **(a)** `/retry --digest` stays classified D35 non-interruptible, so it
  dispatches inline on the single-threaded transport path and self-serializes
  to at most one concurrent call per adapter identity.
- **(b)** every inline LLM surface draws from the per-minute LLM bucket.

Precondition (a) is enforced today only by process — the D61 "a future ticket
must revisit this row deliberately" clause plus paired javadoc on
`InboundRouter.isInterruptible` (which explicitly warns "the two
classifications may not drift"). **No test pins
`isInterruptible("/retry --digest") == false`.** A future refactor that moved
`/retry --digest` onto the pooled/async dispatch path — or rewrote the flag
match as a naive `contains("--digest")` — would silently void the
self-serialization bound the D61 exclusion depends on, and CI would stay
green.

This ticket closes that gap for precondition (a) — the one precondition that
is a pure, mechanically-pinnable classification function. Precondition (b) has
no equivalent one-line pin (it is a cross-cutting property of every inline
surface, not a single predicate) and stays process-enforced; that split is
recorded in `out_of_scope`.

## Notes

**The surgical change.** `InboundRouter.isInterruptible(String normalized)` is
a `private static` pure function of the step-1.7-normalized body
(`InboundRouter.java:1010`). Widen it to package-private (drop `private`, keep
`static`) so a same-package plain-JUnit test in
`app.zcat.infochat.provider.messaging` can call it directly with a table of
inputs. No behavior change; the widening traces directly to the acceptance
(the test must reach the predicate). This is a faster, more targeted pin than
a full Quarkus IT that would have to observe inline-vs-offloaded dispatch
through a seam.

**Why `security_relevant: true`.** The classification IS the D35/D61 boundary;
voiding it re-opens the concurrency residual the M1-636 cap and D61 exclusion
jointly bound. The diff adds no attack surface (a visibility widening + a unit
test), but it guards a property recorded in `security.md`, so it inherits the
adversarial lens that surfaced the gap. Same posture as M1-639.

**Sizing.** Two real files (production visibility widening + one new test),
well inside `files_budget: 3`; the ticket file and regenerated STATUS.md are
lifecycle-exempt and not counted.
