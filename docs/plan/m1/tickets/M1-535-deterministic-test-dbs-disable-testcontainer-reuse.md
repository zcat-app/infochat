---
id: M1-535
title: "Test DBs must be deterministic: disable Testcontainers reuse for test JVMs (fixes partition-horizon drift + container leak)"
status: pending
created: 2026-07-01
last_updated: 2026-07-01
blocked_by: []
files_budget: 3
files_scope:
  - pom.xml
  - scripts/verify-serialized.sh
  - infochat-collector/src/test/java/app/zcat/infochat/collector/partition/CurrentMonthPartitionBootIT.java
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
    Under the disabled-reuse config a full `mvn clean verify` boots fresh Dev
    Services containers (Ryuk reaper active) and the five integration tests that
    failed on 2026-07-01 due to reused/drifted partition state — StreamSourceStopDrainIT,
    Kind6HandlerIT, Kind6LinkingIT, Kind6RepostResolutionIT, NostrSinceCursorIT —
    all pass.
  - >-
    scripts/verify-serialized.sh, BEFORE it acquires the flock, removes orphaned
    Quarkus test Dev Services containers (docker label
    io.quarkus.devservice.launch-mode=TEST) left by hard-killed/OOM'd prior runs,
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
  modifies: []
spec_refs:
  - "docs/spec/verification.md §CI shape"
decision_refs: []
reviews: []
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
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: ""
  verdict: ""
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
(`@Observes StartupEvent` + `@Scheduled`, current+next month) — it is fine. The
failure is **test-only and not a missing migration**:

- The host `~/.testcontainers.properties` has `reuse.enable=true`.
- All collector `@QuarkusTest` classes share ONE Dev Services Postgres. With
  reuse on, that container is **reused across runs and days** and its partition
  set drifts from the migration baseline (also why ~86 containers leaked and
  OOM-killed earlier — reuse suppresses the Ryuk reaper).
- On a **fresh, migration-only DB** every affected test passes — verified twice
  on 2026-07-01 (`Kind6HandlerIT` alone, and all five together, with
  `-Dtestcontainers.reuse.enable=false`).

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
