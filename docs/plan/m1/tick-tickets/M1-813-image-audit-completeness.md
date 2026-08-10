---
id: M1-813
title: "Write IMAGE_GENERATE rows on queue-depth failures"
status: pending
created: 2026-08-10
last_updated: 2026-08-10
flow: tick
reproduction: >-
  to-be-written: ImageCommandHandlerTest.queueDepthUnreachableWritesTheAuditRow —
  sets client.queueDepthThrow = ComfyUIClient.UnreachableException, handles
  `/image a cat`, and asserts BOTH the D76 refund AND exactly one
  IMAGE_GENERATE row with details {"outcome":"failed"}. RED on main: the
  queueDepth() UnreachableException catch refunds and returns without
  calling writeAuditRow (ImageCommandHandler.java:188-190), while
  generate()'s unreachable catch writes the row (:278-284) — the audit
  contract differs by which backend call fails (bench/livetest-10-08-26.md
  E13, live-verified: no IMAGE_GENERATE row for the unreachable queue-read
  path).
analysis_ref: docs/plan/m1/tick-analysis/livetest-image-defects.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ImageCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ImageCommandHandlerTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The refund boundary itself (D76) — every refund stays exactly where it
    is; this ticket ADDS rows beside them, moves none.
  - The runGeneration() arms — they already write rows; untouched.
  - The queue-depth-read InterruptedException arm
    (ImageCommandHandler.java:194-199) — already writes the "stopped" row;
    untouched, pinned by its existing test.
  - A new audit outcome value — the vocabulary stays
    {delivered, failed, stopped}; the new rows use "failed", uniform with
    generate()'s arms for the same failure classes (analysis option 7).
  - The M1-803 round-3 RECOMMENDED-NEW-TICKET (jobStarted=true refund net
    hole on the timeout/stop arms) — recorded open in the analysis, not
    absorbed here.
acceptance:
  - "ImageCommandHandlerTest.queueDepthUnreachableWritesTheAuditRow passes — REPRODUCTION (written and run RED at start): queueDepthThrow = UnreachableException → the localized IMAGE_ERROR_BACKEND_UNREACHABLE reply, the refund, AND exactly one IMAGE_GENERATE row {\"outcome\":\"failed\"} (commands.md §Content: the attempt is audited as a content-free IMAGE_GENERATE row — uniformly, whichever backend call failed; D75)."
  - "ImageCommandHandlerTest.queueDepthBreakerOpenWritesTheAuditRow passes: queueDepthThrow = BreakerOpenException → refund + IMAGE_ERROR_BREAKER_OPEN + one content-free failed row."
  - "ImageCommandHandlerTest.queueDepthIoFailureWritesTheAuditRow passes: queueDepthThrow = a plain IOException → refund + IMAGE_ERROR_GENERATION_FAILED + one content-free failed row."
  - "ImageCommandHandlerTest.queueOverBudgetWritesTheAuditRow passes — FAILURE-MODE (analysis D-5: the bench named the unreachable arm, but the queue-over-budget arm omits the row too): a queue depth at/above the budget → refund + the queue-busy reply + one content-free failed row."
  - "D76 refund boundary preserved (analysis P5): ImageCommandHandlerTest.backendUnreachableRefunds passes UNEDITED (the refund assertion predates this ticket and stays load-bearing), and each new test asserts refund AND row together — deleting either the refund or the row from any arm reds its test."
  - "Rows stay content-free and single-writer: ImageCommandHandlerTest.auditRowIsContentFree passes UNEDITED; the new arms call the existing writeAuditRow (ImageCommandHandler.java:408-438, M1-763 interrupt park included) — Verify: `grep -c 'writeAuditRow' ImageCommandHandler.java` grows by exactly four call sites, no second row writer appears in the diff."
  - "The stopped arm is untouched: ImageCommandHandlerTest.stopDuringQueueDepthReadWritesStoppedAuditRow passes UNEDITED."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - ImageCommandHandlerTest.queueDepthUnreachableWritesTheAuditRow
    - ImageCommandHandlerTest.queueDepthBreakerOpenWritesTheAuditRow
    - ImageCommandHandlerTest.queueDepthIoFailureWritesTheAuditRow
    - ImageCommandHandlerTest.queueOverBudgetWritesTheAuditRow
  preserves:
    - all tests currently green on main (backendUnreachableRefunds, auditRowIsContentFree, stopDuringQueueDepthReadWritesStoppedAuditRow, the failure-contract suite)
spec_refs:
  - docs/spec/commands.md §Content
decision_refs:
  - D75
  - D76
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-813: Write IMAGE_GENERATE rows on queue-depth failures

## Context

Live test 2026-08-10 (bench/livetest-10-08-26.md E13): with ComfyUI
stopped, a fresh `/image` returned the correct localized backend-unreachable
reply and refunded the attempt — but the audit table gained NO
IMAGE_GENERATE row. The same failure class reached through `generate()`
DOES write a row, so the audit contract differs by which backend call
failed; an operator reconstructing image attempts from the audit trail sees
a hole exactly on the queue-depth failure paths. Shared analysis:
`analysis_ref:` (pitfall P5 below matches it).

## Root cause

Verified at ImageCommandHandler.java:182-204: `handle()`'s queue-depth arms
— BreakerOpenException (:185-187), UnreachableException (:188-190),
IOException (:191-193) — each refund and reply without calling
`writeAuditRow`; the queue-over-budget arm (:201-204) likewise. The
InterruptedException arm (:194-199) already writes the `stopped` row, and
every terminal arm inside `runGeneration()` writes its row
(:245-359) — the M1-803 per-arm audit write simply stopped at `handle()`'s
own arms. No test asserted a row on these arms
(ImageCommandHandlerTest.backendUnreachableRefunds :179-192 asserts the
refund only), so the asymmetry shipped.

## Pitfalls

Numbered consistently with the analysis document.

- P5: the row stays content-free and the refund boundary stays exact —
  D75 (actor, scope, outcome; never prompt, never hash) and D76 (refund iff
  the GPU never ran). Adding rows must not move a refund, a reply, or the
  row shape; the new arms reuse the single `writeAuditRow` (with its
  M1-763 interrupt park), never a second writer.

## Approach

Derived from `spec_refs:` — commands.md §Content commits the attempt to a
content-free IMAGE_GENERATE row; a row that exists for one failure site but
not another with the same terminal is a contract hole, and the uniform fix
is to write it everywhere the attempt is refunded or failed at the
queue-depth stage.

- **Files to touch:** `files_scope`.
- **Steps, in order:**
  1. Write the reproduction test RED.
  2. Add `writeAuditRow(actorId, scopeId, "failed")` to the four arms
     (BreakerOpen, Unreachable, IOException, queue-over-budget), AFTER the
     refund and BEFORE the reply — mirroring runGeneration()'s
     refund→row→terminal order.
  3. The three sibling arm tests + the failure-mode test.
  4. Full verify; confirm the untouched-tests list green.
- **Controls to preserve (§10):** every refund exactly where it is (D76);
  the localized reply bundle keys (the eight-mode failure contract's voice
  is unchanged); the `stopped` row on the interrupted arm; the content-free
  row shape and single writer; the fail-loud `writeAuditRow` SQLException
  propagation (an audit outage still aborts the turn — the D75 durability
  posture, same as every existing arm).
- **Pitfall→mitigation:** P5→step 2's refund→row order + acceptance items
  5-7's unedited pins.

## Definition of done

Every acceptance item green by its named test: the reproduction and the
three sibling arms each assert refund + exactly one content-free failed
row; the queue-over-budget failure-mode does the same; the refund boundary,
the content-free pin, and the stopped arm all pass unedited; full verify
green.

## Verification

- reproduction → queueDepthUnreachableWritesTheAuditRow (RED on main;
  deleting the new row write from the Unreachable arm reds it).
- P5 (refund) → backendUnreachableRefunds unedited + every new test's
  paired refund assertion (deleting a refund reds its test).
- P5 (content-free) → auditRowIsContentFree unedited; the new rows carry
  {"outcome":"failed"} only — a row interpolating anything else reds the
  new tests' details assertion.
- failure-mode → queueOverBudgetWritesTheAuditRow (a depth at budget
  refunds, refuses, AND rows; omitting the row reds it).
- stopped-arm preservation → stopDuringQueueDepthReadWritesStoppedAuditRow
  unedited.
- Non-vacuity: removing any one of the four added writeAuditRow calls reds
  exactly its arm's test; adding prompt text to the row reds the
  content-free assertion.

## Out-of-scope

Named in `out_of_scope`: the refund boundary, the runGeneration() arms, the
interrupted arm, any new outcome vocabulary, and the M1-803 round-3
recommended ticket (recorded open in the analysis). No pre-existing test is
modified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-813-image-audit-completeness.md
```
