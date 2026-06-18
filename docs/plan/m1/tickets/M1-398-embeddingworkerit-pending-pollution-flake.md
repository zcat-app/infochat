---
id: M1-398
title: "flaky IT: EmbeddingWorkerIT.postAlreadyEmbeddedIsNotPickedUpByEnumeratePending fails once its fixed-date seed (2026-05-16) falls outside enumeratePending's rolling fetched_at >= now() - 32d scan window"
status: done
created: 2026-06-18
last_updated: 2026-06-18
blocked_by: []
clarity_check:
  date: 2026-06-18
  verdict: PASS
  warnings: []
  blockers: []
files_budget: 1
files_scope:
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorkerIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "EmbeddingWorker.enumeratePending production query (WHERE status='RAW' AND tagger_done=TRUE AND embedding_done=FALSE AND fetched_at >= now() - ?::INTERVAL ORDER BY fetched_at, id LIMIT ?) — correct as written; the `fetched_at >= now() - scanWindow` floor is a deliberate partition-pruning optimization (PartitionScan.scanWindow() = retention-days.post + 2d slack = '32 days'). The defect is the TEST seeding `fresh` at a fixed past date (FETCHED_AT=2026-05-16) that the rolling now()-relative window eventually excludes, not the SUT. Do not change production code, the scanWindow value, or the retention property."
  - "The cross-class cleanup of OTHER collector IT classes / any shared Postgres test base — only touch those if the chosen fix demonstrably requires it, and escalate (refine) before widening files_scope rather than editing them silently."
  - "The other six EmbeddingWorkerIT scenarios (@Order 1–4, 6, 7) — unchanged. In particular do NOT retarget the shared FETCHED_AT constant or seedPost helper used by the post_embedding-writing scenarios (@Order 1–4, 6), whose fetched_at MUST stay inside V11's post_embedding_202605 (May 2026) partition; the @Order(5) fix needs a fetched_at near now() but @Order(5) writes no post_embedding row, so its seed must NOT share the partition-pinned constant."
acceptance:
  - "The root cause is recorded (a comment at the @Order(5) test, or the commit message) and the test `postAlreadyEmbeddedIsNotPickedUpByEnumeratePending` is made robust so it passes regardless of the wall-clock date on which the suite runs — i.e. its `fresh` post must satisfy enumeratePending's `fetched_at >= now() - scanWindow` floor at run time. The fix is to seed `fresh` with a fetched_at INSIDE that rolling window (near now()), not the partition-pinned past constant, AND to make the positive assertion robust to in-window pollution from other near-now-seeding collector ITs (e.g. EmbeddingWorkerPickupFloorIT seeds an in-window pickup-ready post and never cleans it up) — i.e. assert `fresh`'s membership against the full in-window pending set rather than a fixed `LIMIT 10` top slice. Both existing assertions MUST be preserved with equal strength — (a) the already-embedded post is NOT returned by the pickup, and (b) a fresh tagger_done=true / embedding_done=false post IS reachable through enumeratePending. Neither assertion may be deleted, weakened, or made vacuous (test-integrity rule); silently dropping assertion (b), or making it pass without `fresh` actually being returned by enumeratePending, is not acceptable."
  - "`mvn -B verify` from the repo root exits 0. Run `mvn -B verify` twice and confirm both exit 0 — confirms the fix is stable across Failsafe's unstable class run-order and not merely a lucky single pass."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-18
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 137
      removed: 52
escalations:
  - date: 2026-06-18
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A (premise-fail). The ticket's stated root cause — cross-class
      pollution leaving >=10 pending posts that crowd the fresh post out of
      enumeratePending's LIMIT 10 — is falsified. enumeratePending also filters
      AND fetched_at >= now() - ?::INTERVAL where the interval is
      retention-days.post(30)+slack(2)="32 days" (PartitionScan.java:48,
      application.properties:167). The @Order(5) test seeds `fresh` at the fixed
      FETCHED_AT=2026-05-16T10:00:00Z; today (2026-06-18) now()-32d ~= 2026-05-17,
      so `fresh` falls OUTSIDE the window and is filtered out — a deterministic
      time-bomb that worsens daily, not a run-order flake. Proof: raising the
      limit to Integer.MAX_VALUE (return ALL pending rows) still failed
      assertion (b) `:246 ... expected: <true> but was: <false>`; if LIMIT
      crowding were the cause, MAX_VALUE would have fixed it. The sibling
      "polluting" ITs share the same 2026-05-16 constant, so they are also
      time-excluded and cannot crowd anything. The production query is correct
      (partition-pruning); the defect is the test seeding a fixed date that the
      rolling scan window eventually excludes.
revisions:
  - date: 2026-06-18
    reason: "premise-fail refine: original root cause (cross-class pollution crowding fresh out of LIMIT 10) falsified by Integer.MAX_VALUE experiment; real cause of the OBSERVED failure is the rolling fetched_at >= now() - 32d scan-window filter excluding the fixed-date (2026-05-16) seed. A files_scope sweep also found a latent secondary risk the original theory got right in spirit: other near-now-seeding collector ITs (e.g. EmbeddingWorkerPickupFloorIT, no @AfterEach) leave in-window pickup-ready posts, so the dual-axis fix seeds fresh within the window AND enumerates the full in-window pending set rather than trusting LIMIT 10. Corrected title, out_of_scope[0]/[2], both acceptance items, and the body Root-cause/Fix sections. Pre-refine title: 'flaky IT: ... fails when cross-class pollution leaves >=10 pending posts in the shared DevServices DB'; pre-refine fix techniques cited 'raising the enumeratePending limit' alone (proven non-functional without the seed-date fix)."
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-398: EmbeddingWorkerIT pending-pollution flake

## Context

Discovered while running `mvn -B verify` for M1-393 (a docs+shell-only change
that cannot affect Java test behavior). The full suite failed on:

```
EmbeddingWorkerIT.postAlreadyEmbeddedIsNotPickedUpByEnumeratePending:236
  fresh tagger_done=true / embedding_done=false post MUST appear in pickup
  ==> expected: <true> but was: <false>
```

It is **pre-existing on main**, not introduced by M1-393 (a docs+shell-only
change). M1-392 landed with a green verify on an earlier date; the failure
turned permanent once wall-clock time crossed the scan-window threshold (see
below), which is why an earlier run was green and 2026-06-18 runs are red.

### Root cause (corrected — premise-fail refine, 2026-06-18)

> The original analysis below the line attributed this to cross-class
> pollution crowding the fresh post out of a `LIMIT 10`. That is **wrong**:
> raising the limit to `Integer.MAX_VALUE` (return ALL pending rows) still
> failed assertion (b). The real cause is a time-relative filter the original
> analysis omitted.

`EmbeddingWorker.enumeratePending` (EmbeddingWorker.java:519) is:

```
SELECT ... FROM post
 WHERE status='RAW' AND tagger_done=TRUE AND embedding_done=FALSE
   AND fetched_at >= now() - ?::INTERVAL      -- the omitted clause
 ORDER BY fetched_at, id
 LIMIT ?
```

The interval is `PartitionScan.scanWindow()` =
`retention-days.post (30) + PARTITION_SCAN_SLACK (2) = "32 days"`
(PartitionScan.java:48, application.properties:167) — a deliberate
partition-pruning floor.

The `@Order(5)` test seeds its `fresh` post at the **fixed** constant
`FETCHED_AT = 2026-05-16T10:00:00Z`. As wall-clock time advances, once
`now() - 32 days` passes 2026-05-16 (i.e. on/after ~2026-06-17) the `fresh`
post falls **outside** the window and `enumeratePending` correctly stops
returning it — so assertion (b) fails deterministically and worsens daily.
This is a **time-bomb, not a run-order flake**: the EmbeddingWorkerIT sibling
scenarios (@Order 1–4, 6) share the same 2026-05-16 constant and are likewise
time-excluded, so the originally-hypothesised crowding never happened for the
observed failure.

There IS, however, a latent secondary risk the LIMIT-10 query is exposed to:
other collector ITs DO seed in-window pickup-ready posts and do not clean them
up — e.g. `EmbeddingWorkerPickupFloorIT` seeds `uid='embed-floor-it/in-window'`
at `Instant.now()` (RAW, tagger_done, embedding_done=false) with no
`@AfterEach`. Those survive into EmbeddingWorkerIT's run and match the pickup
predicate, so a fixed `LIMIT 10` could still order-dependently crowd `fresh`
out. The robust fix covers both axes.

### Fix

1. Seed the `@Order(5)` `fresh` post (and `already`) with a `fetched_at`
   **inside** the rolling scan window — near `now()` (e.g. `Instant.now()` minus
   a small margin), which the module-wide DevServices DB can serve because
   `PartitionCreator.onStart` (PartitionCreator.java:60,76) provisions the active
   + next month `post` partitions at startup. `@Order(5)` writes no
   `post_embedding` row, so its seed is NOT bound by V11's May-2026
   `post_embedding_202605` partition that pins the shared `FETCHED_AT` constant
   for the embedding-writing scenarios — introduce a separate near-now timestamp
   rather than retargeting `FETCHED_AT`.
2. Enumerate the **full** in-window pending set (a `limit` large enough to
   return every matching row) instead of `LIMIT 10`, so `fresh`'s membership
   reflects the `WHERE` filter alone and is immune to in-window pollution from
   other ITs. This strengthens both assertions: (a) becomes "absent from the
   entire pending set", (b) becomes "present in the entire pending set".

### Original analysis (FALSIFIED — retained for the audit trail)

`EmbeddingWorkerIT` is a `@QuarkusTest` against the **shared** module-wide
DevServices Postgres. Its `@BeforeEach` cleanup is scoped to its own rows only
(`DELETE FROM post WHERE uid LIKE 'embed-it/%'`). The original analysis held
that other collector ITs leave ≥10 unrelated `RAW`/`tagger_done`/
`embedding_done=false` posts with earlier `fetched_at` that sort ahead of the
fresh post and push it past `LIMIT 10`, with the count crossing 10 depending on
Failsafe's class order. The `Integer.MAX_VALUE` experiment disproves it.

## Acceptance / Out-of-scope

See frontmatter.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-398-*.md
```
