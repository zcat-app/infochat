---
id: M1-763
title: "Timed-out digest render is never cancelled and keeps spending LLM calls"
status: pending
created: 2026-08-04
last_updated: 2026-08-04
blocked_by: []
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    `DigestRenderer.java`. The fix belongs at the SUBMISSION site, not
    inside the render. If it turns out the interrupt cannot stop the
    render — because the LLM client swallows it, or because the spend
    happens between two uninterruptible calls — the cooperative-check
    variant threads a cancellation token through `renderSections` and
    every generator it drives, which is a different and much wider
    change. ESCALATE at that point rather than widening in place;
    `DigestRenderer.java` is also contended by M1-759 and M1-762, so a
    silent widening here collides with them.
  - >-
    THE SLOT-WINDOW POLICY. How long a render gets (`slot.windowEnd()`),
    when the worker degrades, and what the degraded renderer emits are
    all unchanged. This ticket does not make the deadline longer, shorter
    or adaptive — it makes the EXISTING deadline actually stop the work.
  - >-
    `CancellationService` / `InFlightTracker`. That machinery is
    chat-turn cancellation, keyed by (userId, scopeKind, scopeId) and
    driven by a user's `/stop`. A scheduled digest render has no user and
    is never registered in the tracker, so reusing it would mean
    inventing a synthetic turn identity. Out of scope; the digest render
    is cancelled by its own future, not by the chat path.
  - >-
    THE AGGREGATE SYSTEM LLM BUDGET. `docs/spec/security.md` §Rate
    limiting claims one exists as "the backstop for digest cost"; it does
    not exist in code. That is a separate, still-undecided spec-vs-code
    gap (see M1-758 out_of_scope, which records it at the spec site).
    This ticket stops ONE leak; it does not supply the missing ceiling
    and must not be read as closing that gap.
  - >-
    The `NEVER re-render after renderSections()` invariant on the success
    path and the cache-write that follows. Untouched.
acceptance:
  - >-
    THE DEFECT, stated so the fix is verifiable: `renderFuture` is a
    `CompletableFuture` built by `CompletableFuture.supplyAsync(...,
    renderExecutor)`, and `CompletableFuture.cancel(boolean)` documents
    that its `mayInterruptIfRunning` argument "has no effect in this
    implementation because interrupts are not used to control
    processing". So `renderFuture.cancel(true)` at `DigestWorker.java`
    completes the future exceptionally for the WAITER and leaves the
    render running to completion on `renderExecutor` — spending its
    per-cluster prose calls, its per-section roll-up calls and, since
    M1-756, its per-headline translator calls, all AFTER the worker has
    already degraded, emitted the degraded content and moved on.
  - >-
    THE COMMENT ASSERTS THE OPPOSITE OF WHAT THE CODE DOES and must stop
    doing so. It reads "Cancel the orphaned render rather than leaving it
    to run past the slot window ... the get(timeout) above stops waiting
    but does not stop the work behind the future. (M1-494 13#F4)" — the
    second half is correct about `get`, and the first half is false about
    `cancel`. Note the anchor: this code was ADDED to close a red-team
    finding, so the finding it claims to close is still open.
  - >-
    A RENDER THAT OVERRUNS ITS SLOT WINDOW STOPS MAKING GENERATIVE CALLS.
    This is the observable property; the mechanism is the implementer's
    choice, but the in-place candidate is submitting the render through
    `renderExecutor.submit(...)` and holding the returned `Future`, whose
    `cancel(true)` DOES interrupt (`FutureTask` honours the flag, unlike
    `CompletableFuture`). `renderExecutor` is a
    `newVirtualThreadPerTaskExecutor`, so the interrupt lands on a
    virtual thread and surfaces at its next interruptible point.
  - >-
    VERIFY THE INTERRUPT ACTUALLY REACHES THE SPEND before declaring the
    ticket done, and say so in the commit. An interrupt only stops work
    at an interruptible point; if the generative calls run through a
    client that swallows `InterruptedException` or blocks
    uninterruptibly, the future is cancelled and the calls continue —
    the same defect with a different mechanism. If that is what the code
    does, ESCALATE (the cooperative-token variant is out_of_scope above);
    do not declare victory on the future's state alone.
  - >-
    THE TEST ASSERTS SPEND, NOT CANCELLATION STATE. A test that asserts
    `future.isCancelled()` passes TODAY against the broken code and is
    exactly the vacuous shape M1-762 item 2 exists to kill. Assert
    instead that a render which overruns its window makes NO FURTHER
    provider calls after the worker degrades — count calls on a spy,
    with the render blocked on a latch the test controls.
  - >-
    Time driving the deadline stays on the injected `java.time.Clock`
    (`Duration.between(clock.instant(), slot.windowEnd())` is already
    correct per CLAUDE.md §"Injectable time in decision logic"). Do not
    introduce `Instant.now()` while restructuring the submission.
  - >-
    The degraded-path behaviour after a timeout is byte-unchanged:
    `degradedRenderer.render(collection.posts())`, `isDegraded = true`,
    and the same cache write. Only the fate of the ORPHANED render
    changes.
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
  preserves:
    - >-
      `DigestWorkerClockTest` and every existing `DigestWorkerTest`
      assertion, including the success-path "never re-render" invariant
      and the degraded-on-timeout content.
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
  - docs/spec/security.md §Failure handling
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-763: Timed-out digest render is never cancelled

## Context

Surfaced by the M1-756 red-team (2026-08-04, out-of-model item 2) and
verified against `main` at `2698edbf` — it is **pre-existing**, not
introduced by M1-756. M1-756's entire `DigestWorker` delta is the single
`slot.groupId()` argument added to the `renderSections` call; the
submission and cancel sites are untouched by it.

What M1-756 changed is the **cost** of the bug. Before it, an orphaned
render burned per-cluster prose and per-section roll-up calls. Since it,
the same orphaned render also burns up to
`infochat.digest.translation-max-per-render` translator calls per render
— on a path where the worker has already given up, already emitted
degraded content, and already moved to the next group.

## Why the existing comment makes this worse, not better

The site carries a comment claiming the cancel works, anchored to a
red-team finding ID (`M1-494 13#F4`). A future reader auditing digest
cost will find an explicit, sourced assurance that orphaned renders are
stopped, and stop looking. That is the failure mode worth fixing even if
the spend turns out to be small: the code documents a control it does
not have.

## The mechanism, precisely

```java
CompletableFuture<List<RenderedSection>> renderFuture =
        CompletableFuture.supplyAsync(() -> digestRenderer.renderSections(...), renderExecutor);
...
renderFuture.cancel(true);   // <-- mayInterruptIfRunning is IGNORED
```

`CompletableFuture.cancel`'s contract is explicit that the flag has no
effect, because `CompletableFuture` does not use interrupts to control
processing. The call transitions the future to cancelled — which the
already-timed-out waiter no longer cares about — and does nothing to the
task running on `renderExecutor`.

`ExecutorService.submit(...)` returns a `FutureTask`, which DOES honour
`cancel(true)` by interrupting the running thread. That is the smallest
change that could work, which is why `files_scope` is two files.

## The honest caveat

An interrupt stops work only at an interruptible point. Whether the
generative calls in `renderSections` — cluster prose, category roll-up,
and the M1-756 translator leg — actually observe it depends on the LLM
client beneath them. `acceptance:` therefore requires verifying the
interrupt reaches the spend, and escalating if it does not, rather than
asserting on the future's own state. A cancelled future with a still-
spending render is this exact bug wearing a different hat.

## Notes

- `security_relevant: true`: the promise at stake is `docs/spec/security.md`
  §Rate limiting. An unbounded orphaned render is a cost/DOS surface, and
  the deployment has no aggregate LLM ceiling to catch it — see the
  out_of_scope entry, and M1-758 where that gap is recorded at the spec
  site.
- Pre-flight: `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-763-digest-render-cancel-noop.md`
