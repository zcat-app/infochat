---
id: M1-338
title: "AbstractInstanceLockGuard: scope the ownership probe to the gate's lock id"
status: done
created: 2026-06-14
last_updated: 2026-06-14
clarity_check:
  date: 2026-06-14
  verdict: WARN
  warnings:
    - "Acceptance item 2 ('the invariant becomes a coincidence rather than load-bearing') is a design-intent statement, not a runnable check; belongs as an implementation comment. Items 1, 3, 4 fully bound the behavioral contract, so it does not block."
  blockers: []
blocked_by: []
files_budget: 3
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/startup/AbstractInstanceLockGuard.java
  - infochat-core/src/test/java/app/zcat/infochat/core/startup
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The reentrancy rationale (pg_try_advisory_lock is NOT re-called in the probe) — unchanged; that reasoning is correct and preserved.
  - The single-instance acquisition path and heartbeat/refresher — unchanged; only the ownership re-check predicate is narrowed.
acceptance:
  - "The held-session ownership probe verifies this session holds the SPECIFIC advisory lock the guard acquired, not 'any advisory lock'. The probe predicate ANDs the pid filter with the lock-id key: the guard computes the lock id once at startup on the held connection (e.g. SELECT hashtext(<lock-key-input>)::int8, run in the same backend the probe consults) and binds it so the pg_locks predicate matches ((classid::bigint << 32) | objid::bigint) = ? AND pid = pg_backend_pid() AND locktype = 'advisory'."
  - "The 'held session takes exactly one advisory lock' invariant becomes a coincidence rather than load-bearing: a future patch that adds a second advisory lock on the held connection cannot mask a server-side release of the single-instance gate (today's bare EXISTS over any advisory row would return true and hide the release)."
  - "A test pins the scoping: the probe returns owned=true only when the gate lock id is present for the backend, and would return false if the gate lock were released even with another advisory lock held by the same session (exercised via the test seam). Existing single-instance-guard acquisition/zombie tests stay green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/startup (scoped-ownership-probe case)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-14
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 174
      removed: 22
escalations:
  - date: 2026-06-14
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — about to touch 3 files (AbstractInstanceLockGuard.java + two test
      files under the in-scope startup test dir) against files_budget: 2. The
      probe's new prepareStatement calls force a modify of
      InstanceLockProbeLockScopeTest.java (its Connection proxy returned null
      for prepareStatement → probe-thread NPE); the new scoping test (acceptance
      item 3) needs real Postgres pg_locks, so it lands in the @QuarkusTest
      InstanceLockLivenessTest.java — a distinct 3rd file. All three paths are
      within files_scope; only the numeric files_budget is exceeded.
revisions:
  - date: 2026-06-14
    reason: budget-breach refine — widen files_budget 2 → 3
    snapshot:
      files_budget: 2
      note: |
        files_scope unchanged (both touched test files already live under the
        in-scope startup test dir). Only the numeric files_budget rose: the
        probe's new prepareStatement calls force a modify of
        InstanceLockProbeLockScopeTest.java in addition to the new scoping test
        in InstanceLockLivenessTest.java, so 3 files are touched, not 2.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-338: AbstractInstanceLockGuard — scope the ownership probe to the gate lock id

## Context

Deep-review v5.5 (opus-47, `02-module-infochat-core.md` F2) found that the
held-session ownership re-check answers "does this session hold ANY advisory
lock?", not "does this session hold the single-instance gate?". **Verified at
source 2026-06-14:** the probe is `SELECT EXISTS (SELECT 1 FROM pg_locks WHERE
locktype = 'advisory' AND pid = pg_backend_pid())`
(AbstractInstanceLockGuard.java:190-192), relying on the comment-documented
invariant that the held connection takes exactly one advisory lock.

The invariant is real but fragile: a future commit that adds a second
`pg_advisory_lock` on the held connection — for any reason — converts a real
release of the single-instance gate into a false-positive "still owned" probe
result. The probe's whole job is to catch zombie-after-server-side-release, so its
safety property rests on a coding rule the build cannot enforce. Binding the
predicate to the actual lock-id key (encoded in `pg_locks.classid`/`objid`) makes
the check resilient.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Report Option A (lock-id-scoped SQL predicate) is preferred over Option B (a
  build-time grep gate banning `pg_advisory_lock` outside this class) — the
  structural SQL fix catches inline DDL / stored-proc advisory locks a grep would
  miss. Compute `hashtext(...)::int8` on the held connection so the same backend
  hashes the key (no HA-replica hashing divergence).
