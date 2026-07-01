---
id: M1-535
title: "Test DBs must be deterministic: disable Testcontainers reuse for test JVMs (fixes partition-horizon drift + container leak)"
status: done
created: 2026-07-01
last_updated: 2026-07-01
blocked_by: []
files_budget: 4
files_scope:
  - pom.xml
  - scripts/verify-serialized.sh
  - infochat-collector/src/test/java/app/zcat/infochat/collector/partition/CurrentMonthPartitionBootIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/startup/HeartbeatSchedulerIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "No production partition-logic changes. PartitionCreator / PartitionPruner / PartitionDdl already provision current+next month and prune by retention correctly in production (they read the injected Clock); this ticket does not touch them."
  - "No new Flyway migration and NO DEFAULT partition. The deliberate no-DEFAULT-partition invariant (V7 comment / Invariant 6) stays; the fix is test determinism, not a schema fallback."
  - "Do NOT un-pin or edit the existing fixed-date test seeds (M1-479's 2026-07-15 core seeds; the collector stream/Nostr tests' 2026-05-22 FETCHED_AT). Deterministic fresh DBs make them safe; churning them is separate scope."
  - "No change to infochat-core's raw-Testcontainers PostgresSchemaTestBase — it already starts a fresh per-JVM container and never reuses, so it is not subject to the cross-run drift this ticket fixes."
  - "No change to any M1-529 file (that ticket is parked in-review; this fix unblocks its suite)."
acceptance:
  - >-
    The parent pom (pom.xml) configures BOTH maven-surefire-plugin and
    maven-failsafe-plugin so the test JVMs run with Testcontainers container reuse
    DISABLED regardless of any host ~/.testcontainers.properties reuse.enable=true
    — e.g. a <systemPropertyVariables>testcontainers.reuse.enable=false</...> (or
    the equivalent TESTCONTAINERS_REUSE_ENABLE=false environmentVariable) applied
    to both plugins. `grep -nE 'reuse' pom.xml` shows the disable in both the
    surefire and failsafe configurations.
  - >-
    HeartbeatSchedulerIT no longer resumes the globally-halted scheduler. The
    true cause of the 2026-07-01 failures (proven by a 2-test repro:
    HeartbeatSchedulerIT + Kind6HandlerIT): its `scheduler.resume()` un-halted the
    shared Quarkus test app's scheduler and never restored it, so
    PartitionPruner.onTick fired with the real clock and dropped the retention-
    boundary month partition (post_202605 once "today" >= 2026-07-01), breaking
    every later IT that seeds a fixed May-2026 fetched_at. The test now drives the
    handler directly via heartbeatScheduler.tick() (package-visible; mirrors
    FetchSchedulerIT's manual-tick pattern), so no @Scheduled bean — least of all
    PartitionPruner — runs against the shared DB. `grep -n 'resume()' ...HeartbeatSchedulerIT.java`
    returns nothing and the test invokes `heartbeatScheduler.tick()`.
  - >-
    A full `mvn clean verify` (fresh Dev Services containers, Ryuk reaper active)
    passes the five integration tests that failed on 2026-07-01 —
    StreamSourceStopDrainIT, Kind6HandlerIT, Kind6LinkingIT,
    Kind6RepostResolutionIT, NostrSinceCursorIT — with no partition-not-found
    errors.
  - >-
    scripts/verify-serialized.sh, while HOLDING the flock (immediately after
    acquiring it — reaping before the lock would race a concurrent verify and kill
    its live DB), removes orphaned Quarkus test Dev Services containers (docker
    label io.quarkus.devservice.launch-mode=TEST) left by hard-killed/OOM'd runs,
    so container accumulation can no longer re-trigger host OOM. The reap targets
    ONLY that label (never the operator's infochat-* compose stack), is a no-op
    when docker is absent or nothing matches, and never fails the verify (its exit
    status stays that of mvn). `grep -n 'launch-mode=TEST' scripts/verify-serialized.sh`
    matches, and the mvn invocation remains the script's last/status-bearing command.
  - >-
    A new tripwire IT (CurrentMonthPartitionBootIT, infochat-collector) asserts
    that after a fresh @QuarkusTest boot a `post` partition covering the current
    instant (clock.instant()) EXISTS — proving PartitionCreator.onStart provisions
    the active month so `now()`-keyed inserts stay valid at any future month
    boundary, independent of V30's static June/July-2026 horizon. It reads the same
    injected Clock the app uses (no split-clock). The test fails if a future change
    stops provisioning the active month.
  - >-
    `mvn -B clean verify` from the repo root exits 0; no previously-green test
    regresses.
test_plan:
  adds:
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/partition/CurrentMonthPartitionBootIT.java — asserts a post partition covering clock.instant() exists after a fresh Quarkus boot (PartitionCreator.onStart active-month guarantee)."
  preserves:
    - "The five previously-failing collector stream/Nostr ITs and all other collector tests currently green on a fresh DB."
    - "All wizard/adapter/provider tests green on main."
  modifies:
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/startup/HeartbeatSchedulerIT.java — stop resuming the shared halted scheduler; drive heartbeatScheduler.tick() directly so PartitionPruner cannot fire and drop partitions sibling ITs depend on. Still asserts last_seen_at advances."
spec_refs:
  - "docs/spec/verification.md §CI shape"
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-01
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 217
      removed: 60
escalations: []
revisions:
  - date: 2026-07-01
    reason: "clarity-fail rework (run bounded self-refine, prose-only) — clarity FAIL: SPEC-REFS-VALID blocker: 'docs/spec/verification.md' lacked a §<section> anchor. Added '§CI shape' (an existing heading in verification.md; the section on how the test suite runs — the relevant context for a test-determinism fix). Also addressed the ACCEPTANCE-RUNNABLE WARN by removing the non-checkable 'ROOT CAUSE (recorded for the reviewer)' item from acceptance — its content is duplicated verbatim in the ## Context section, so no information is lost. No scope/files_budget/intent change."
    prior_values: |
      spec_refs (pre-refine):
        - "docs/spec/verification.md"
      acceptance[0] (pre-refine, removed — duplicated ## Context): the
      "ROOT CAUSE (recorded for the reviewer)" paragraph on reused-DB partition
      drift ("no partition of relation post found for row"; fresh DB passes).
  - date: 2026-07-01
    reason: "in-implementation correctness fix (not scope): acceptance item for the verify-serialized.sh reaper said 'BEFORE it acquires the flock', which would race a concurrent lock-holding verify and kill its live Dev Services DB. Corrected to 'while HOLDING the flock (immediately after acquiring it)' — the only safe placement, since holding the lock guarantees no concurrent verify from this clone. Behavior/scope unchanged; wording aligned to the safe implementation."
    prior_values: |
      acceptance reaper-item placement (pre-fix): "BEFORE it acquires the flock"
  - date: 2026-07-01
    reason: "USER-AUTHORIZED SCOPE FOLD (deep-investigation finding). The reuse-disable + reaper fix the container leak/OOM but do NOT fix the 2026-07-01 test failures — those persist on fresh DBs. Root cause proven by a 2-test repro (HeartbeatSchedulerIT + Kind6HandlerIT): HeartbeatSchedulerIT calls scheduler.resume() on the globally-halted shared test scheduler and never restores it, so PartitionPruner.onTick fires with the real clock and drops the retention-boundary May-2026 partition, breaking sibling ITs that seed fixed May dates. User chose to fold the real fix into M1-535. Added HeartbeatSchedulerIT.java to files_scope (drive tick() directly instead of resuming the shared scheduler), files_budget 3->4, added an acceptance item for the scheduler-leak fix, corrected acceptance item 2 (the 5 tests pass because of the scheduler fix, not reuse-disable), and corrected the ## Context to record the true root cause. The reuse-disable + reaper remain in scope (they fix the separate OOM/leak the user hit)."
    prior_values: |
      files_budget (pre-fold): 3
      files_scope (pre-fold): pom.xml, scripts/verify-serialized.sh, CurrentMonthPartitionBootIT.java
      acceptance (pre-fold) attributed the 5 tests passing to the disabled-reuse config (incorrect); no HeartbeatSchedulerIT item.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-07-01
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-535: Deterministic test DBs — disable Testcontainers reuse for test JVMs

## Context

On 2026-07-01 the full `mvn verify` went red with five collector integration
tests failing on `PSQLException: ERROR: no partition of relation "post" found for
row` (StreamSourceStopDrainIT, Kind6HandlerIT, Kind6LinkingIT,
Kind6RepostResolutionIT, NostrSinceCursorIT). They were green on 2026-06-30.

Investigation (see the M1-529 session): the `post` table is RANGE-partitioned by
`fetched_at` with **no DEFAULT partition** (V7 Invariant 6). Flyway seeds May
(V7) + June/July 2026 (V30). Production rolls partitions via `PartitionCreator`
(`@Observes StartupEvent` + `@Scheduled`, current+next month) and prunes aged
ones via `PartitionPruner` (`@Scheduled`, 30-day retention) — production is fine.
The failure is **test-only and not a missing migration**. Two distinct defects
surfaced, both fixed here:

- **Real cause of the 5 failures — a scheduler-state leak (proven).**
  `%test.quarkus.scheduler.start-mode=halted` keeps `@Scheduled` beans quiet so
  they don't mutate the DB shared across the collector's `@QuarkusTest` classes.
  `HeartbeatSchedulerIT` breaks that: it calls `scheduler.resume()` (to watch the
  heartbeat tick fire) and **never restores the halted state**. The now-running
  `PartitionPruner.onTick` fires with the real clock; once "today" >= 2026-07-01
  the 30-day cutoff (June 1) makes the May-2026 partition aged and it is
  **dropped** (`Dropped aged partition post_202605`). Every later IT seeding a
  fixed May-2026 `fetched_at` (Kind6*, StreamSourceStopDrain, NostrSinceCursor
  stale) then hits "no partition found". Reproduced deterministically with just
  `HeartbeatSchedulerIT + Kind6HandlerIT`; before 2026-07-01 May is still within
  retention so nothing drops. Fix: drive `heartbeatScheduler.tick()` directly (it
  is package-visible; mirrors `FetchSchedulerIT`), never resuming the shared
  scheduler — so no `@Scheduled` bean runs against the shared DB.

- **Separate defect — container reuse leak/OOM.** The host
  `~/.testcontainers.properties` has `reuse.enable=true`, so Quarkus Dev Services
  containers persist across runs and Ryuk is suppressed; ~86 leaked containers
  accumulated and OOM-killed the build. Forcing reuse off (parent pom) makes every
  run use a fresh, migration-defined DB and re-enables Ryuk; the
  verify-serialized reaper sweeps any pre-existing debris. This does NOT by itself
  fix the 5 failures (they still fail on a fresh DB via the scheduler leak above)
  — it fixes the OOM/leak, which is why both changes are in this ticket.

Prior monthly-style fixes (M1-121 June+July migration, M1-479 pinning core seeds
to `2026-07-15`) treated symptoms; M1-479's own pin re-breaks after 2026-08-01.
The permanent fix is **deterministic test DBs**, not more dated migrations or
date pins.

## Acceptance

See the YAML `acceptance:` list. In prose: force Testcontainers reuse OFF for all
test JVMs from the parent pom (overriding the host flag) so every run gets a
clean, migration-defined DB where `PartitionCreator.onStart` provisions the
active month; add a defensive reaper of orphaned Quarkus TEST Dev Services
containers to `scripts/verify-serialized.sh`; add a tripwire IT that locks in the
active-month provisioning guarantee so a future month boundary cannot silently
break inserts.

## Out-of-scope

See the YAML `out_of_scope:` list. Load-bearing exclusions: no production
partition-logic change, no DEFAULT partition, no new migration, no editing of
existing fixed-date test seeds, and no M1-529 files.

## Notes

- **Why disable reuse rather than make tests idempotent to drift.** Reuse is not
  a project design choice (no repo config sets it — it comes from the host
  properties file). Fresh DBs make the suite time-invariant: `now()`-keyed
  inserts self-provision via `onStart`, fixed-date pins hit always-created
  bootstrap partitions, and nothing drifts. Making every test robust to an
  arbitrarily-drifted reused DB is more fragile and larger.
- **Reaper is belt-and-suspenders.** Disabling reuse re-enables Ryuk, which reaps
  containers when the test JVM dies (even on hard-kill, after its socket
  timeout). The verify-serialized.sh reap covers the window before Ryuk fires and
  any debris from before this change.
- **Alternatives considered:** (a) add a DEFAULT partition — rejected, it breaks
  the deliberate no-fallback invariant and hides mis-dated rows; (b) gate reuse
  only in verify-serialized.sh — rejected, leaves local `mvn verify` outside the
  script non-deterministic (user chose the in-repo/all-runs fix).
