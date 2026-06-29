---
id: M1-524
title: "Unify the divergent outbox-IT awaitCursor poll helper into one fixture"
status: pending
created: 2026-06-29
last_updated: 2026-06-29
decomposed_from: M1-499
blocked_by: []
files_budget: 8
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "The other three outbox helpers (clearAllItPosts, resetNewPostCursor, ensureTestSource) — already consolidated into OutboxItFixtures by M1-499."
acceptance:
  - >-
    The five copies of the outbox-IT awaitCursor poll helper
    (NewPostListenerIT, NewPostListenerReconnectIT,
    NewPostListenerReconcileOnReconnectIT, QuarantineReviewListenerIT,
    QuarantineReviewReconcileOnReconnectIT) are consolidated into ONE shared
    helper (alongside the M1-499 OutboxItFixtures) that takes the cursor channel,
    the ProviderStateDao, the predicate, the fail message, and the timeout/poll
    durations as parameters. Each former copy calls the shared helper.
  - >-
    The normalization this requires is explicitly authorized here (unlike
    M1-499): pick one deadline mechanism (the copies split between
    System.nanoTime() and Instant.now() — functionally equivalent); drop the
    vacuous assertNotNull(c) on the Optional in the two NewPostListener copies
    (an Optional is never null, so the assertion always passes — confirm before
    deleting); preserve each call site's channel (NewPostHandler.CHANNEL_NEW_POST
    vs QuarantineReviewListener.CHANNEL), predicate, fail message, and its own
    AWAIT_TIMEOUT / AWAIT_POLL values by passing them as arguments.
  - "mvn -B verify is green from the repo root."
test_plan:
  preserves:
    - all five outbox ITs still green; the cursor-advance conditions they assert are unchanged
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-524: Unify the divergent outbox-IT awaitCursor poll helper into one fixture

## Context

Split out of M1-499 (test-fixture dedup sweep, finding 34#F2). M1-499 consolidated
three genuinely-duplicated outbox-IT helpers (clearAllItPosts, resetNewPostCursor,
ensureTestSource) into `OutboxItFixtures`, but the fourth helper named in 34#F2,
`awaitCursor`, is NOT a clean duplicate: its five copies have diverged along
several axes (deadline mechanism `System.nanoTime()` vs `Instant.now()`; a vacuous
`assertNotNull(c)` present in two copies and absent in three; cursor channel
`NewPostHandler.CHANNEL_NEW_POST` vs `QuarantineReviewListener.CHANNEL`; and
per-file `AWAIT_TIMEOUT` 30s/10s and `AWAIT_POLL` 100ms/50ms).

Consolidating it requires *normalizing* those differences (one deadline mechanism,
dropping the vacuous assertion), which M1-499's `out_of_scope` ("no change to what
the tests assert") forbade. This ticket exists to authorize that normalization
explicitly and do the unification.

## Acceptance

See frontmatter.

## Notes

- Source: M1-499 finding 34#F2, awaitCursor portion (user-chosen "extract 3 clean,
  defer awaitCursor", 2026-06-29).
- The shared helper will sit beside `OutboxItFixtures` (provider testsupport).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-524-*.md
```
