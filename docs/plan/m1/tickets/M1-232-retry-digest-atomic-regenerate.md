---
id: M1-232
title: "/retry --digest: atomic regenerate, honest skip status"
status: pending
created: 2026-06-08
last_updated: 2026-06-08
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRetryService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/SummaryCacheRepository.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRetryConcurrencyIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRetryServiceTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The scheduled-digest path itself (DigestScheduler / the @Observes DigestSlot trigger) beyond making execute() report whether it ran — the scheduling cadence and slot computation are unchanged.
  - The paused-group /retry --digest no-op (commands.md §Periodic group digests) — that branch already returns a friendly no-op and is unchanged.
  - The DigestRetryService per-group cooldown (lastRetryAt) semantics — unchanged.
  - Any digest content/collection logic.
acceptance:
  - "DigestWorker.execute reports whether it actually ran the slot (returns a boolean or small result enum); the @Observes scheduled entry point keeps a void-returning wrapper that ignores the result."
  - "DigestRetryService.retryDigest does NOT report RetryResult.SUCCESS when the worker skipped the run because the slot was already in flight; it returns a distinct status (e.g. ALREADY_IN_PROGRESS) and leaves the cached digest untouched."
  - "The cached digest is never left empty: the regeneration overwrites the cache row atomically (SummaryCacheRepository UPSERT on (group_id, slot_kind, slot_fired_at)) instead of delete-then-execute, so there is no window where the cache is deleted but not yet rewritten."
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
  summary_cache` (no `ON CONFLICT`); the UPSERT is a small repository
  change.
- Test edits to existing DigestWorker/DigestRetryService tests are
  authorized by this ticket (execute()'s return type changes); they are
  signature-tracking only and must not weaken assertions.
