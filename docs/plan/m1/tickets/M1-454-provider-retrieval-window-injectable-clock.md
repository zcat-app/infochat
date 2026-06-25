---
id: M1-454
title: "Make provider retrieval/freshness-window decision time injectable: convert six audit-missed inline Instant.now() gates (SearchPostsTool, ListSavesTool, EligiblePostQuery, SavedCommandHandler, DigestWorker, AssetSnapshotReader)"
status: pending
created: 2026-06-25
last_updated: 2026-06-25
blocked_by: []
files_budget: 14
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/ListSavesTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SavedCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetSnapshotReader.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SearchPostsToolClockTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/ListSavesToolClockTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryClockIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedCommandHandlerClockTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerClockTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetSnapshotReaderClockTest.java
complexity: medium
risk: low
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - "The DISPLAY/record time reads in these classes that gate NO decision — LEFT unchanged (engineering-rules §9 exempts display/audit reads): SavedCommandHandler.java:258 (relativeAge 'Xd/Xh ago' string), SavedCommandHandler.java:278 (OutboundMessage send-timestamp), DigestWorker.java:203 (OutboundMessage send-timestamp). Converting these would be scope drift — they render/record, they do not compare against a window."
  - "Any change to a retrieval/freshness window SIZE or to which posts/saves a command returns. This is a determinism refactor: under the production Clock.systemUTC() each window comparison is byte-for-byte identical to today. Changing a window is a separate ticket."
  - "The already-converted/already-correct sites (M1-447 trio, M1-448 partition workers, M1-449 PartitionPruner/DigestRetryService/FetchScheduler, M1-450 ProbationCheck, M1-444 ReEvaluationJob) and the M1-451 probation gates and M1-452 collector sites. This ticket is ONLY the six provider retrieval/freshness-window reads named in the title."
  - "AssetSnapshotReader.isStale(capturedAt, now, window) — already takes `now` as a parameter and is already unit-tested with an explicit now (AssetSnapshotReaderTest). Do NOT change its signature; convert only its caller loadLatest (line 163) which currently passes inline Instant.now()."
  - "The Collector-side asset ingest write (snapshot_created_at stamped at ingest) — a different site, classified (C)/(B) in the audit and out of scope here."
acceptance:
  - "Each of the six classes obtains the current instant used in its retrieval/freshness-window comparison from an injected java.time.Clock (the app-wide @Produces @ApplicationScoped Clock in ThrottledAdminNotifier.systemUtcClock(); field initialised `= Clock.systemUTC()` so hand-built test instances stay non-null and CDI overrides at runtime, per the M1-444 reference). The specific decision-gate reads converted from inline Instant.now() to clock.instant() are: SearchPostsTool.java:82 (published_at >= cutoff window), ListSavesTool.java:94 (saved_at >= cutoff window), EligiblePostQuery.java:125 (published_at >= cutoff, sampled once and threaded to both selectPosts and topActiveFollowedTags so they share one instant), SavedCommandHandler.java:204 (saved_at > cutoff window in bindFilters), DigestWorker.java:157 (now-vs-slot.windowEnd() deadline gate driving the degrade/timeout-budget decision at :158-167), AssetSnapshotReader.java:163 (the Instant.now() fed into the isStale freshness/TTL comparison)."
  - "The display/record reads that gate nothing are LEFT unchanged: SavedCommandHandler.java:258 and :278, DigestWorker.java:203."
  - "SearchPostsToolClockTest pins the Clock via QuarkusMock.installMockForType(Clock.fixed(...), Clock.class) and asserts the published_at window boundary (a post on the cutoff) is included/excluded per the injected instant."
  - "ListSavesToolClockTest pins Clock.fixed(...) and asserts the saved_at window boundary is decided per the injected instant."
  - "EligiblePostQueryClockIT pins Clock.fixed(...) and asserts the deterministic /summary published_at window boundary is decided per the injected instant (covering the single sampled cutoff used by both queries)."
  - "SavedCommandHandlerClockTest pins Clock.fixed(...) and asserts the /saved -w window boundary is decided per the injected instant."
  - "DigestWorkerClockTest pins Clock.fixed(...) and asserts the now-vs-windowEnd deadline decision (degrade vs render) flips at the injected instant."
  - "AssetSnapshotReaderClockTest pins Clock.fixed(...) and asserts loadLatest's stale verdict flips at the freshness-window boundary per the injected instant (the isStale static stays as-is; only the caller's now source is pinned)."
  - "All seven new tests are ADDITIVE; this ticket modifies the assertions of NO pre-existing test (no test_plan.modifies). Under the production Clock.systemUTC() every existing provider test stays green and behaviour is byte-for-byte preserved."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - "SearchPostsToolClockTest.java — pins Clock.fixed(...) and asserts the published_at retrieval-window boundary is decided against the injected instant."
    - "ListSavesToolClockTest.java — pins Clock.fixed(...) and asserts the saved_at window boundary is decided against the injected instant."
    - "EligiblePostQueryClockIT.java — pins Clock.fixed(...) and asserts the /summary published_at window boundary is decided against the injected instant (one sampled cutoff, both queries)."
    - "SavedCommandHandlerClockTest.java — pins Clock.fixed(...) and asserts the /saved -w window boundary is decided against the injected instant."
    - "DigestWorkerClockTest.java — pins Clock.fixed(...) and asserts the now-vs-windowEnd degrade/render deadline decision flips at the injected instant."
    - "AssetSnapshotReaderClockTest.java — pins Clock.fixed(...) and asserts loadLatest's freshness-window stale verdict flips at the injected instant."
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check:
  date: 2026-06-25
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-454: Provider retrieval/freshness-window decision time onto the injected Clock (audit-missed)

## Context

The M1-447 → M1-450 sweep moved the project's decision-logic time reads onto the
injected `java.time.Clock` (engineering-rules §9: time that drives a comparison/gate
on "now" must be pinnable, never inline `Instant.now()` / SQL `now()`). A deep code
review on 2026-06-25, followed by per-site re-verification, found **six provider
classes the M1-447 classification audit (`docs/plan/m1/now-clock-audit.md`) missed
entirely** — none appears in the audit's (A) table, its 8-component DEFERRED list
(which M1-448/449 since converted), or its already-correct list. Each holds exactly
one category-(A) decision-gate read still on inline `Instant.now()`:

| Class | (A) read | Gates |
|---|---|---|
| `SearchPostsTool` | `:82` | `published_at >= ?` retrieval window (which posts `/search` returns) |
| `ListSavesTool` | `:94` | `saved_at >= ?` window (which saves return) |
| `EligiblePostQuery` | `:125` | deterministic `/summary` `published_at >= ?` window (one cutoff → two queries) |
| `SavedCommandHandler` | `:204` | `/saved -w <window>` `saved_at > ?` window |
| `DigestWorker` | `:157` | now-vs-`slot.windowEnd()` deadline → degrade-vs-render + timeout budget |
| `AssetSnapshotReader` | `:163` | `isStale` freshness/TTL verdict shown to the user |

Each leaves a user-visible retrieval/freshness boundary un-pinnable in tests — the
date-boundary time-bomb class M1-398 / M1-400 / M1-444 each fixed one instance of.
The re-verification also confirmed the **non**-gating reads in these classes
(relative-age strings, outbound send-timestamps) are pure display/record and must
stay — separating them is the point of the §9 (A)-vs-(C) classification.

These six are bundled into one provider-scoped ticket following the M1-448 (5
workers) / M1-449 (3 services) precedent for same-pattern conversions, rather than
fragmenting into six one-line tickets.

## Acceptance

See the YAML `acceptance:` list. In short: each class reads its window/freshness
instant from the injected `Clock` (whole-component, no two-clock split — and where a
single cutoff fans into multiple queries it is sampled once); the three display/record
reads stay; each converted class gains a fixed-`Clock` boundary test (7 new tests,
additive); full suite green; behaviour byte-for-byte preserved under `Clock.systemUTC()`.

## Out-of-scope

See the YAML `out_of_scope:` list. The display/record reads stay. No window-size
change. The already-converted sites and the M1-451/M1-452 sibling clock tickets are
not re-touched. `AssetSnapshotReader.isStale`'s signature is unchanged — only its
caller's `now` source moves.

## Notes

- Reference implementation: M1-444 (`ReEvaluationJob`) and M1-448 (the SQL/`Instant`
  window-cutoff conversions). Pattern: `@Inject Clock clock = Clock.systemUTC();`,
  sample `clock.instant()` once where the window is computed, bind it; pin a fixed
  `Clock` in the test via `QuarkusMock.installMockForType(Clock.fixed(...), Clock.class)`.
- The Clock producer is `ThrottledAdminNotifier.systemUtcClock()`.
- No shared time helper exists across the six — each samples its own instant, so this
  is six independent one-line conversions plus one pinned test each (verified: no
  fan-in to convert once).
- `EligiblePostQuery.java:125` already samples its cutoff once and threads it to both
  `selectPosts` and `topActiveFollowedTags`; converting that single read to
  `clock.instant()` keeps both queries on one instant (no intra-call split).
- `DigestWorker.java:157`'s gate compares now against `slot.windowEnd()` (carried on
  the inbound `DigestSlot` event, not a `now()` write), and the cache `expires_at`
  at `:189` derives from the same `windowEnd` — so converting `:157` introduces no
  app-write/app-read clock split.
- Independently decomposable: if the reviewer/plan-writer judges the six-class diff
  too large for one review, each class is self-contained and can split into a child
  ticket per the M1-447 "expect decomposition" guidance. Default here is one ticket
  (matches M1-448/449 and the no-tiny-tickets intent).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-454-provider-retrieval-window-injectable-clock.md
```
