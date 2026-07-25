---
id: M1-688
title: "Fix digest first-run window and zero-post boundary advance"
status: pending
created: 2026-07-25
last_updated: 2026-07-25
blocked_by: []
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
  - docs/design/03-commands.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Changing the window's time COLUMN from published_at to ready_at. That
    is M1-689 and it changes retrieval semantics for /summary too. This
    ticket only fixes the window's lower BOUND and the boundary write.
  - >-
    DigestScheduler's slot arithmetic (centre hours, window width, stagger,
    missed-slot sentinel). The scheduler decides WHEN a slot fires and is
    correct; the defect is in what DigestWorker collects once fired.
  - >-
    DigestPostCollector's SQL predicates (D59 world predicate, EXPLICIT
    tag mode, source exclusions). The predicates are correct; only the
    `since` argument handed to them is wrong.
  - >-
    /summary and EligiblePostQuery. The empty-window fallback there is a
    separate surface with its own -w flag under the user's control.
  - >-
    Backfilling or repairing summary_cache rows already written by the
    buggy path on live deployments. That is an operator action, not a
    migration.
acceptance:
  - >-
    A group's FIRST-EVER digest collects from a full inter-slot period
    rather than from the ~30-minute slot window: when
    SummaryCacheRepository.findPreviousBoundary returns empty,
    DigestWorker.executeSlot derives collectFrom by subtracting one slot
    interval from windowStart instead of using windowStart itself.
  - >-
    A zero-post slot no longer establishes the collection boundary for the
    next slot. DigestWorkerTest gains a test asserting that after a slot
    that collected zero posts, the NEXT slot's collectFrom is still the
    older boundary (or the first-run fallback), not the empty slot's
    windowStart. No such test exists today.
  - >-
    The existing zero-post behavior a user sees is otherwise unchanged: the
    fixed no-posts reply is still sent and no digest_section rows are
    persisted. DigestWorkerTest.execute_zeroPosts_sendsFixedReply and
    execute_zeroPostsPersistsNoSections stay green, amended only where
    they assert the cache-write count.
  - >-
    DigestWorkerTest.execute_includesPostPublishedBetweenSlots stays green
    — a normal slot following a NON-empty slot still collects from that
    previous boundary.
  - mvn verify from the repo root is green
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
decision_refs:
  - D17
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-688: Fix digest first-run window and zero-post boundary advance

## Context

Filed from live SimpleX testing on 2026-07-25. The scheduled morning digest
reported "no posts to summarize yet" for a group whose corpus was full.
Confirmed against the live database:

    summary_cache: 1 row — morning, slot_fired_at 2026-07-25 07:45Z,
                   content "No posts to summarize yet…"

`DigestWorker.executeSlot` computes
`collectFrom = cacheRepository.findPreviousBoundary(groupId, windowStart).orElse(slot.windowStart())`
(`DigestWorker.java:170`). This was the group's first-ever digest, so there
was no previous boundary and it collected from **07:45Z** — a 30-minute
window (`infochat.digest.window-width-minutes=30`). At fire time the live DB
held **0** READY posts with `published_at >= 07:45`, against **51** READY
posts published since the previous evening. The corpus was full; the window
was half an hour wide. `DigestWorkerTest.execute_collectsFromWindowStartWhenNoPreviousDigest`
currently pins this as intended behavior.

The second defect compounds it. The `summary_cache` upsert at
`DigestWorker.java:228` sits **outside** the zero-post branch and runs
unconditionally — pinned by `DigestWorkerTest.execute_zeroPosts_sendsFixedReply`,
which asserts `assertEquals(1, cacheRepository.upsertCount())`. That row is
what `findPreviousBoundary` returns for the next slot
(`SummaryCacheRepository.java:177`), so an empty 08:00 digest silently makes
07:45Z the lower bound for the 20:00 digest, and every post published before
07:45 is excluded from that slot and every slot after it. Nothing in the
suite covers what `collectFrom` becomes on the slot *after* an empty one.

The manual `/retry --digest` path shares this collection code exactly — one
call site, same `since`, same SQL. It appears to "work" only because the
collector has no upper bound, so a later invocation sees whatever became
READY in between.

## Acceptance

See the frontmatter. First-run collection falls back one slot interval
instead of to `windowStart`; a zero-post slot does not become the next
slot's boundary; the user-visible zero-post reply and the
no-sections-persisted behavior are unchanged; a new test covers the
slot-after-an-empty-slot case.

## Out-of-scope

The `published_at` → `ready_at` column change (M1-689), the scheduler's slot
arithmetic, `DigestPostCollector`'s predicates, `/summary`'s own empty
window, and repairing already-written rows on live deployments. See the
frontmatter.

## Notes

- The two defects are separable but belong together: fixing only the
  first-run fallback still leaves the zero-post boundary poisoning later
  slots, and fixing only the boundary write leaves every new group's first
  digest empty. Either alone would have produced the reported symptom.
- The "one slot interval" is the gap between the morning and evening centre
  hours (`infochat.digest.morning-slot-hour=8`,
  `infochat.digest.evening-slot-hour=20`, `application.properties:572`), not
  a hardcoded 12h — a deployment that re-points those hours should get a
  matching lookback. Whether this becomes a derived value or its own
  property is an implementation call; `application.properties` is in
  `files_scope` for that reason.
- `DigestWorker` already injects `java.time.Clock` (`DigestWorker.java:96`)
  and uses it for the degrade deadline; the collection window is derived
  from slot coordinates, not from `now()`, so this ticket introduces no new
  ambient time into decision logic.
- Skipping the cache upsert entirely on zero posts is the simplest shape,
  but check what else reads `summary_cache` for that slot first — the
  scheduler's already-fired check (`DigestSchedulerTest.tick_skipsGroupAlreadyFiredInWindow`)
  is the one to confirm, since a slot that writes nothing may re-fire within
  its window. A non-boundary marker column is the alternative if it does.
- Adjacent code: `DigestWorker.executeSlot`,
  `SummaryCacheRepository.findPreviousBoundary`,
  `DigestScheduler.tickAt` (the missed-slot sentinel at
  `DigestScheduler.java:260` is a deliberate boundary write — do not
  confuse it with the zero-post write this ticket removes).
- Operator note for whoever runs the live deployment after this lands: the
  poisoned row is already in the database, so the next digest will still
  collect from 07:45Z until that row is deleted. Deleting it is an operator
  action, deliberately out of scope here.
