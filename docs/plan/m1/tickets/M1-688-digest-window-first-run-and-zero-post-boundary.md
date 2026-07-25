---
id: M1-688
title: "Fix digest first-run collection window"
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
  - >-
    The zero-post boundary advance (a slot that collected nothing still
    writing summary_cache, so its windowStart becomes the next slot's
    lower bound). Investigated at start and deliberately NOT compensated
    for here — see §Notes. It is a symptom of the published_at predicate,
    which M1-689 fixes at the root; the coverage that pins it lives there.
acceptance:
  - >-
    A group's FIRST-EVER digest collects from a full inter-slot period
    rather than from the ~30-minute slot window: when
    SummaryCacheRepository.findPreviousBoundary returns empty,
    DigestWorker.executeSlot derives collectFrom by subtracting one slot
    interval from windowStart instead of using windowStart itself.
  - >-
    The lookback is derived from the configured slot hours
    (infochat.digest.morning-slot-hour / evening-slot-hour), not a
    hardcoded 12h, so a deployment that re-points those hours gets a
    matching first-run lookback. DigestWorkerTest covers a non-default
    hour pair.
  - >-
    Zero-post behavior is unchanged in every respect: the fixed no-posts
    reply is still sent, the cache row is still written (it is the
    scheduler's only re-fire guard), and no digest_section rows are
    persisted. DigestWorkerTest.execute_zeroPosts_sendsFixedReply and
    execute_zeroPostsPersistsNoSections stay green UNAMENDED.
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

# M1-688: Fix digest first-run collection window

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

This ticket was filed with a second defect — the unconditional
`summary_cache` upsert at `DigestWorker.java:228`, which lets an empty slot's
`windowStart` become the next slot's lower bound via
`findPreviousBoundary` (`SummaryCacheRepository.java:177`). That was
investigated at `start` on 2026-07-25 and removed from scope; §Notes records
why. It is not a defect that DigestWorker can or should fix.

The manual `/retry --digest` path shares this collection code exactly — one
call site, same `since`, same SQL. It appears to "work" only because the
collector has no upper bound, so a later invocation sees whatever became
READY in between.

## Acceptance

See the frontmatter. First-run collection falls back one slot interval
instead of to `windowStart`, with the interval derived from the configured
slot hours; every other digest behavior — including the zero-post reply and
its cache write — is unchanged.

## Out-of-scope

The `published_at` → `ready_at` column change (M1-689), the zero-post
boundary advance, the scheduler's slot arithmetic, `DigestPostCollector`'s
predicates, `/summary`'s own empty window, and repairing already-written
rows on live deployments. See the frontmatter.

## Notes

- **Why the zero-post boundary advance is not fixed here (start-gate
  finding, 2026-07-25).** The originally-filed shape was "skip the cache
  upsert when the slot collected nothing". Three findings killed it:
  1. That row is the scheduler's *only* re-fire guard —
     `DigestScheduler.java:150` calls
     `existsByGroupAndSlot(groupId, slotKind, windowStart)` with no expiry
     filter. With no row the slot re-fires on every 60s tick for the rest
     of the 30-minute window, re-sending "no posts to summarize yet".
  2. At `windowEnd`, `recordMissedSlot` (`DigestScheduler.java:276`) inserts
     a sentinel into `summary_cache` at the **same** `slot_fired_at`, plus a
     `DIGEST_SLOT_MISSED` audit row and an admin notification. The boundary
     is written anyway, so the acceptance item was not even achieved.
  3. The remaining alternative — a non-boundary marker column — needs a
     migration plus `SummaryCacheRepository`, both outside `files_scope`,
     and would add a fourth semantic to a table already carrying three
     (content cache, idempotency guard, boundary source).
  It is also unnecessary. The advance is only *harmful* because the window
  compares `published_at`: a post published 06:00 that goes READY at 09:00
  is invisible to the 07:45 slot and then excluded by the 19:45 slot's
  `published_at >= 07:45`. Under M1-689's `ready_at` predicate the same post
  satisfies `ready_at >= 07:45` and is collected — the advance becomes
  lossless. A marker column would therefore be a schema-level compensator
  for a defect M1-689 removes at the root, vestigial the moment it lands.
  M1-689 carries the acceptance item and the IT case that pin the property.
- The "one slot interval" is the gap between the morning and evening centre
  hours (`infochat.digest.morning-slot-hour=8`,
  `infochat.digest.evening-slot-hour=20`, `application.properties:572`), not
  a hardcoded 12h — a deployment that re-points those hours should get a
  matching lookback. Both properties already exist and already carry these
  defaults, so deriving the interval needs no new property;
  `application.properties` stays in `files_scope` only in case the
  derivation turns out to need one.
- `DigestWorker` already injects `java.time.Clock` (`DigestWorker.java:96`)
  and uses it for the degrade deadline; the collection window is derived
  from slot coordinates, not from `now()`, so this ticket introduces no new
  ambient time into decision logic.
- Adjacent code: `DigestWorker.executeSlot`,
  `SummaryCacheRepository.findPreviousBoundary`,
  `DigestScheduler.tickAt` (the missed-slot sentinel at
  `DigestScheduler.java:260` is a deliberate boundary write —
  skip-not-catch-up, not a defect).
- Operator note for whoever runs the live deployment after this lands: the
  07:45Z row is already in the database and `findPreviousBoundary` will
  still return it, so the next digest collects from 07:45Z until that row is
  deleted. Deleting it is an operator action, deliberately out of scope
  here.
