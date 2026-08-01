---
id: M1-740
title: "Provider test fixtures insert into monthly-partitioned tables with wall-clock timestamps: the suite breaks on every unprovisioned month boundary"
status: done
created: 2026-08-01
last_updated: 2026-08-01
blocked_by: []
files_budget: 20
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/GetReferencesToolTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/RetrievalWorldPredicateIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetSnapshotReaderClockTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetCommandsRoundtripIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetHandlerIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetSnapshotReaderCacheIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterStopRetryIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerGroupScopeIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryAdapterScopeIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryRenderFormIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryGroupScopeIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryClockIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/TranslationPipelineIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/UntaggedPostRetrievalIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/journey/GoldenPathJourneyIT.java
  - infochat-provider/src/test/resources/fixtures/seed-ready-posts.sql
  - scripts/lint-partitioned-test-inserts.py
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Any production-code change. `PartitionCreator` / `PartitionPruner` /
    `PartitionDdl` stay exactly as they are; production provisioning works
    (collector startup + daily tick, idempotent).
  - >-
    Any new Flyway migration, including a V69 August-partitions
    kick-forward. Fixtures pin into the bootstrap months the migrations
    already provision, so no new partition is needed anywhere — a new
    migration would re-create the same monthly fire drill next month.
  - >-
    Collector-module test sources. The collector app boots
    `PartitionCreator` (`StartupEvent` + daily tick), so its test
    container is provisioned for the active and next month at every run —
    structurally immune to this failure class.
  - >-
    A DEFAULT partition on any table. Invariant 6 forbids one (a
    fallback bucket silently accumulates rows the TTL partition-drop
    would never age out).
  - >-
    A provider-side startup partition-provisioning bean (the
    test-boot-provisions alternative). Considered and rejected: it keeps
    the §9 ambient-time violations alive and adds a permanent test-only
    DDL path, to defend against a problem the fixed-date fixtures remove
    outright — see §Why fixed dates, not test-boot provisioning.
  - >-
    Rewriting test verdict logic or assertions. Only timestamp sourcing
    changes, plus the per-fixture time-family consistency edits the
    ordering assertions require.
acceptance:
  - >-
    Every in-scope provider test file binds every partition-key column it
    inserts — `post.fetched_at`, `post_reference.created_at`,
    `price_snapshot.captured_at`, `post_entity.fetched_at`,
    `post_embedding.fetched_at` — to a FIXED instant constant, never to
    `Instant.now()` / `DEFAULT now()` / `CURRENT_TIMESTAMP`. Within each
    fixture the time family (`published_at` / `ready_at` / `fetched_at`)
    stays mutually consistent, so existing ordering assertions — including
    the M1-689 undated-post sort expectations (`COALESCE(published_at,
    fetched_at) DESC, id DESC`) — hold unchanged.
  - >-
    The six tests that failed at the 2026-08-01 month boundary
    (`GetReferencesToolTest` ×4, `AssetSnapshotReaderClockTest` ×2) pass,
    with their verdict semantics byte-identical:
    `GetReferencesToolTest.seedReference` binds `created_at` to the same
    fixed May-2026 instant the fixtures already pin `fetched_at` to;
    `AssetSnapshotReaderClockTest` pins `capturedAt` to a fixed instant
    inside a migration-provisioned month and keeps the injected `Clock`
    (fixed relative to `capturedAt`) as the sole source of the staleness
    verdict.
  - >-
    The provider FAILSAFE tier runs green in the current month — the tier
    never executed in the 2026-08-01 red window (surefire failed first),
    so its eleven `fetched_at`-omitting ITs are unproven until this
    ticket's verify demonstrates them.
  - >-
    `scripts/lint-partitioned-test-inserts.py` exits 1 when any
    provider/core test source INSERTs into one of the five partitioned
    tables while omitting the partition-key column from the INSERT column
    list OR binding it from `Instant.now()` / `OffsetDateTime.now()` /
    `LocalDateTime.now()` / `CURRENT_TIMESTAMP` / `now()`, and exits 0 on
    the post-ticket tree. It reconstructs Java string-concatenated SQL
    (`"INSERT INTO post (uid, " + "...`) before checking, ships a
    `--self-test` mode with one violating and one clean embedded fixture,
    and its docstring documents the same manual-invocation posture as
    `scripts/lint-ticket.py`.
  - >-
    The §Census is re-run at implementation time against provider and
    core test sources, and every file it returns is disposed in the
    commit: fixed in this ticket, value-level verified pinned (the
    surefire tier that ran green on 2026-08-01 is empirically
    August-safe), or core-module pinned. No provider/core test INSERT
    into a partitioned table may rely on the wall clock after this
    ticket.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      scripts/lint-partitioned-test-inserts.py with `--self-test` — the
      violating fixture (an INSERT omitting the partition key, and one
      binding it from `Instant.now()`) is rejected; the clean fixture
      (fixed-instant binding) passes; the script exits 0 on the
      post-ticket tree and 1 on a deliberately reintroduced violation.
  preserves:
    - >-
      Every currently-green test's assertions. Only timestamp sourcing
      changes; verdict logic, ordering expectations, and counts are
      untouched.
    - >-
      `GetReferencesToolTest`'s link-graph fixtures and
      `AssetSnapshotReaderClockTest`'s stale/fresh boundary semantics
      (`window + 1s` stale, `window - 1s` fresh), pinned by the same
      named tests with fixed instants.
    - all tests currently green on main
spec_refs:
  - docs/design/02-schema.md §2.4.4
decision_refs: []
reviews:
  - round: 1
    date: 2026-08-01
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 22
      added: 960
      removed: 163
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-08-01
  verdict: WARN
  warnings:
    - "lint SECURITY-FLAG-INFERENCE on InboundRouterStopRetryIT — test-only fixture timestamp sourcing changes; no production/security surface touched, security_relevant: false stands"
    - "self-check: census grep re-run live at start; 48 sites returned, matches ticket's disposition table (14 FIX + verified-pinned remainder + core-module pinned)"
escalation_reason:
---

# M1-740: the suite breaks on the 1st of every unprovisioned month

## Context

At 2026-08-01 00:00 UTC the full suite went red project-wide:
`GetReferencesToolTest` (4 errors) and `AssetSnapshotReaderClockTest`
(2 errors) failed with `PSQL ERROR: no partition of relation
"post_reference" / "price_snapshot" found for row`. Verified on `main`
with zero code involvement — the failure is environmental, and it has
recurred every month since May (V30's June/July provisioning was the
previous kick-forward).

Root cause, verified layer by layer:

- The five monthly-partitioned tables (`post`, `post_embedding`,
  `post_entity`, `post_reference`, `price_snapshot`) are provisioned by
  migrations only through July 2026 (V7/V11/V17/V28/V29 bootstrap +
  V30). There is no DEFAULT partition (Invariant 6).
- Production never notices: the collector's `PartitionCreator`
  provisions the active and next month at `StartupEvent` and on a daily
  tick, idempotently (`CREATE TABLE IF NOT EXISTS`).
- **Provider tests boot only the provider app.** Their database carries
  migrations and nothing else, so any fixture that inserts a
  wall-clock-stamped row into a partitioned table breaks on the 1st of
  the first month the migrations don't cover. The collector module is
  structurally immune (its own boot provisions the container); the core
  module's schema tests pin fixed instants.
- Two surefire classes are the proven August casualties
  (`seedReference` omits `created_at` → `DEFAULT now()`; the asset
  clock test binds `captured_at` from `Instant.now()` — ambient time in
  a test whose verdict is already pinned to an injected `Clock`, an
  engineering-rules §9 violation). Eleven failsafe-tier ITs omit
  `fetched_at` the same way and would fail the moment the tier runs —
  it never ran in the red window because surefire failed first.

This blocked M1-724's commit gate (and blocks every ticket's `mvn
verify`) within nine minutes of the month boundary.

## Census

Enumeration command (run at implementation time; both directions of the
disposition must be re-verified, values not just column names):

```
grep -rln 'INSERT INTO post_reference\|INSERT INTO price_snapshot\|INSERT INTO post_entity\|INSERT INTO post_embedding\|INSERT INTO post ' \
  --include='*.java' infochat-provider/src/test infochat-core/src/test
```

Disposition of the 2026-08-01 enumeration:

| Site | Finding | Disposition |
|---|---|---|
| `GetReferencesToolTest` | `post_reference` INSERT omits `created_at` (4 surefire errors) | FIX: bind `created_at` to the fixture's fixed May-2026 instant |
| `RetrievalWorldPredicateIT` | `post_reference` INSERT omits `created_at` | FIX: same |
| `AssetSnapshotReaderClockTest` | `price_snapshot.captured_at` bound from `Instant.now()` (2 surefire errors) | FIX: pin to a fixed instant; injected `Clock` already decides the verdict |
| `AssetHandlerIT`, `AssetSnapshotReaderCacheIT`, `AssetCommandsRoundtripIT`, `GoldenPathJourneyIT` | `price_snapshot.captured_at` bound from `Instant.now()` (all); `GoldenPathJourneyIT`'s `post.fetched_at` bound from `Instant.now()` (failsafe tier — never ran in the red window, fails on first August run; surfaced by the implementation-time census re-run and brought into scope by the budget-breach refine) | FIX: pin the partition-key binding to a fixed instant inside a provisioned month, keeping each test's verdict source unchanged (staleness stays with the injected `Clock`; the journey post's window membership stays with `ready_at`) |
| `seed-ready-posts.sql` (test-resources fixture consumed by `SeedFixtureIT` + `DevTerminalHarnessRoundtripIT`) | both `INSERT INTO post` omit `fetched_at` → `DEFAULT now()` (round-1 verify red on exactly these two ITs; the ticket's census grep was `--include='*.java'`-only and structurally blind to .sql fixtures — second budget-breach refine) | FIX: add `fetched_at` to both INSERT column lists bound to a fixed literal inside a provisioned month; `published_at`/`ready_at` keep their deliberate `now()`-relative staggering (not partition keys; the 24h-window semantics the header documents are unchanged). The `post_embedding` INSERT already reads `fetched_at` back from the post row, so it follows into the same partition. The lint is extended to `.sql` test resources so this class can no longer slip past the census |
| `InboundRouterStopRetryIT`, `RetryCommandHandlerGroupScopeIT`, `SummaryAdapterScopeIT`, `SummaryRenderFormIT`, `SummaryGroupScopeIT`, `SummaryIT`, `EligiblePostQueryClockIT`, `EligiblePostQueryIT`, `TranslationPipelineIT`, `UntaggedPostRetrievalIT`, `DigestPostCollectorIT` | `post` INSERT omits `fetched_at` (failsafe tier — never ran in the red window, fails on first August run) | FIX: bind `fetched_at` to a fixed instant consistent with each fixture's `published_at`/`ready_at` family |
| All other provider surefire tests inserting into partitioned tables | ran GREEN on 2026-08-01 post-boundary → empirically August-safe | VERIFY value-level (the partition-key binding resolves to a fixed constant); lint guards |
| Core-module schema tests (`PartitionHorizonInsertIT` et al.) | `fetched_at` bound to fixed instants; green in the red window | VERIFY value-level; lint guards |
| Collector-module tests | container provisioned at app boot by `PartitionCreator` | OUT OF SCOPE (structurally immune) |

## The design: fixed dates, plus a lint that keeps them fixed

Two moves, no production code, no migration:

1. **Pin every partition-key insert in provider/core tests to a fixed
   instant.** The bootstrap months the migrations provision (May–July
   2026) exist in every test database forever, so a fixture whose rows
   land in them is immune to the calendar. This is the engineering-rules
   §9 posture the two failing classes already violated — the fix is
   compliance, not a new mechanism. The only subtlety is per-fixture
   time-family consistency: `fetched_at` participates in
   `COALESCE(published_at, fetched_at)` ordering, so where a fixture's
   assertions depend on relative order, its `published_at` / `ready_at`
   / `fetched_at` values are pinned as one consistent family (and an
   injected `Clock`, where present, is pinned into the same family).
2. **`scripts/lint-partitioned-test-inserts.py`** rejects new ambient-time
   inserts at author time (column omitted, or bound from a `now()`
   source), so the next `Instant.now()` in a seed helper fails fast
   instead of next month.

## Why fixed dates, not test-boot provisioning

The alternative — a provider-side bean that runs the partition DDL at
test boot (the collector `PartitionCreator` shape, duplicated) — makes
the suite green with one class and no fixture edits. Rejected: it
protects and perpetuates the §9 ambient-time violations (fixtures that
float with the wall clock drift their window/recency semantics every
run, which is how the two failing classes were written in the first
place), and it adds a permanent test-only DDL path to defend against a
problem the fixed fixtures remove outright. Fixed dates are also the
stricter test: a fixture that names its instants documents its own time
semantics.

## Notes

- A `spec:` follow-up (non-ticket docs edit) should record the rule as
  a decision row in `docs/spec/decisions.md`: test fixtures bind
  partition-key columns to fixed instants inside migration-provisioned
  months; ambient-now inserts are a lint error; production provisioning
  stays with the collector's `PartitionCreator`. Deliberately NOT in
  this ticket's scope — `docs/spec/decisions.md` is in M1-724's
  in-review scope, and touching it here would bar the parallel start.
- V30-style monthly provisioning migrations are the anti-pattern this
  ticket retires: each one kicked the same failure one or two months
  forward.
