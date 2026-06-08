---
id: M1-232
title: "/retry --digest: atomic regenerate, honest skip status"
status: pending
created: 2026-06-08
last_updated: 2026-06-09
blocked_by: []
files_budget: 7
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRetryService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/SummaryCacheRepository.java
  - infochat-core/src/main/resources/db/migration/V46__grant_update_summary_cache.sql
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRetryConcurrencyIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRetryServiceTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - The scheduled-digest path itself (DigestScheduler / the @Observes DigestSlot trigger) beyond making execute() report whether it ran — the scheduling cadence and slot computation are unchanged.
  - The paused-group /retry --digest no-op (commands.md §Periodic group digests) — that branch already returns a friendly no-op and is unchanged.
  - The DigestRetryService per-group cooldown (lastRetryAt) semantics — unchanged.
  - Any digest content/collection logic.
acceptance:
  - "DigestWorker.execute reports whether it actually ran the slot (returns a boolean or small result enum); the @Observes scheduled entry point keeps a void-returning wrapper that ignores the result."
  - "DigestRetryService.retryDigest does NOT report RetryResult.SUCCESS when the worker skipped the run because the slot was already in flight; it returns a distinct status (e.g. ALREADY_IN_PROGRESS) and leaves the cached digest untouched."
  - "The cached digest is never left empty: the regeneration overwrites the cache row atomically (SummaryCacheRepository UPSERT on (group_id, slot_kind, slot_fired_at)) instead of delete-then-execute, so there is no window where the cache is deleted but not yet rewritten. The UPSERT is a NEW repository method (e.g. upsert(...)); the existing insert(...) is left unchanged because DigestScheduler.recordMissedSlot shares it for the missed-slot sentinel and relies on the plain INSERT raising a unique-index violation to keep its audit+sentinel transaction atomic — changing the shared insert to ON CONFLICT DO UPDATE would silently duplicate audit rows."
  - "A new migration V46__grant_update_summary_cache.sql adds GRANT UPDATE ON summary_cache TO infochat_provider; without it the ON CONFLICT DO UPDATE upsert fails at runtime with 'permission denied for table summary_cache' under the deliberately-weak infochat_provider role (the role exercised by the default datasource in both %test and production). The V23 SELECT/INSERT/DELETE grant is otherwise unchanged."
  - "A named test asserts that when a scheduled run for the same (group, slotKind) holds the worker's in-flight guard, /retry --digest leaves the existing cache row intact and returns the not-run status (NOT SUCCESS)."
  - "A named test asserts the normal /retry --digest path regenerates and overwrites the cache row (UPSERT) and returns SUCCESS."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRetryConcurrencyIT.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRetryServiceTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Command catalogue
  - docs/spec/commands.md §Periodic group digests
decision_refs: []
reviews: {}
escalations:
  - date: 2026-06-08
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — premise-fail surfaced during start-step planning, before any
      implementation. Acceptance item 3 mandates an UPSERT (INSERT ... ON
      CONFLICT DO UPDATE) on summary_cache, but the Provider connects as the
      deliberately-weak role `infochat_provider`, which V23 grants only
      SELECT, INSERT, DELETE (V31 added only sequence USAGE). Postgres
      ON CONFLICT DO UPDATE requires the UPDATE privilege on the SET columns,
      so the UPSERT fails at runtime (permission denied for table
      summary_cache) in both production AND %test (the weak role is exercised
      under test by design). Satisfying item 3 requires a new migration
      `GRANT UPDATE ON summary_cache TO infochat_provider`, which contradicts
      migration_touch: false and is absent from files_scope / files_budget: 6.
revisions:
  - date: 2026-06-09
    reason: premise-fail refine — acceptance item 3 mandated an ON CONFLICT DO
      UPDATE upsert on summary_cache, but the Provider's deliberately-weak role
      (infochat_provider) is granted only SELECT/INSERT/DELETE (V23), so the
      upsert fails with permission-denied at runtime. Widen scope to add the
      GRANT UPDATE migration (V46; files_budget 6→7; migration_touch true),
      pin the grant as a new acceptance item, and clarify that the upsert is a
      NEW repository method so DigestScheduler's shared sentinel insert stays
      unchanged.
    prior_values: |
      status: escalated
      files_budget: 6
      migration_touch: false
      files_scope (6): DigestWorker.java, DigestRetryService.java,
        SummaryCacheRepository.java, DigestRetryConcurrencyIT.java,
        DigestWorkerTest.java, DigestRetryServiceTest.java
      acceptance had 6 items (no grant item; item 3 did not constrain the
        upsert to a new method).
      clarity_check: PASS 2026-06-08 (evaluated the pre-refine 6-item ticket).
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-232: /retry --digest: atomic regenerate, honest skip status

## Context

Deep-review finding `deep-code-review/v2.5/opus-48/07-module-infochat-provider.md#F2`
(medium MAINTAINABILITY-RULES-DRIFT). `commands.md` commits that
`/retry --digest` *replaces* the cached digest. The implementation deletes
the cache row first (`DigestRetryService.retryDigest` → `deleteCacheRow`,
then `digestWorker.execute(slot)`), but `DigestWorker.execute` is `void`
and silently `return`s when its own per-`(group, slotKind)` in-flight guard
(`inFlightSlots.add`) loses to a concurrent scheduled run. In that race the
cache row is deleted, nothing replaces it, and `retryDigest` still returns
`RetryResult.SUCCESS`. The window is narrow (requires a concurrent
scheduled run of the exact same slot) but the failure is destructive (cache
lost) and the success report is actively misleading.

## Acceptance

See frontmatter. In prose: make `execute()` report whether it ran; have
`retryDigest` surface a skip as a non-SUCCESS status and leave the cache
untouched; replace delete-then-execute with an atomic UPSERT regeneration
so the cache is never left empty; named tests pin both the concurrent-skip
and normal-regenerate paths; `mvn verify` is 0.

## Out-of-scope

See frontmatter. The scheduling path, the paused-group no-op, the cooldown,
and digest collection logic are unchanged. This ticket changes only the
retry atomicity + the worker's run-reporting contract.

## Notes

- Recommended fix (return status + UPSERT) and the alternative
  (share the worker's in-flight key) are in the source finding;
  the UPSERT path is preferred because it removes the
  delete-before-skip window entirely.
- `SummaryCacheRepository.insert` is currently a plain `INSERT INTO
  summary_cache` (no `ON CONFLICT`). Add a NEW `upsert(...)` method
  (`INSERT ... ON CONFLICT (group_id, slot_kind, slot_fired_at) DO UPDATE
  SET ...`) and switch `DigestWorker.executeSlot` to call it. Do NOT change
  the existing `insert(...)`: `DigestScheduler.recordMissedSlot` shares it
  for the missed-slot sentinel and depends on the plain INSERT throwing a
  unique-index violation to roll back its audit+sentinel transaction; an
  `ON CONFLICT DO UPDATE` there would commit duplicate audit rows.
- The upsert needs UPDATE privilege the Provider role lacks. `summary_cache`
  was granted `SELECT, INSERT, DELETE` to `infochat_provider` in V23 (V31
  added only sequence USAGE); the default datasource connects as that weak
  role in both `%test` and production, so without `GRANT UPDATE` the upsert
  fails at runtime with `permission denied for table summary_cache`. The
  V46 migration adds exactly that grant. (This premise gap — the original
  6-file ticket assumed the repository change alone sufficed — is why the
  ticket was refined; see `revisions:`.)
- V46 was the lowest free migration version when this ticket was refined
  (swept across all in-flight worktrees, not just `main`). Re-confirm it is
  still free at `start` time — a parallel worktree may grab it first.
- Test edits to existing DigestWorker/DigestRetryService tests are
  authorized by this ticket (execute()'s return type changes); they are
  signature-tracking only and must not weaken assertions.
