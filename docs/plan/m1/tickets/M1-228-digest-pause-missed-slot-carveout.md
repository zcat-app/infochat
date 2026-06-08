---
id: M1-228
title: "Don't record a missed digest slot for a window the group was paused through"
status: done
created: 2026-06-08
last_updated: 2026-06-08
clarity_check:
  date: 2026-06-08
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: [M1-227]
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestScheduler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestSchedulerMissedSlotTest.java
  - docs/spec/commands.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "The /digest on|off command, the digest_enabled column, the scheduler's queryActiveGroups gate, and the DIGEST_ENABLE/DIGEST_DISABLE audit actions — all delivered by M1-227 (this ticket's blocker). This ticket only adds the missed-slot carve-out that consumes them."
  - "Resume-within-window firing semantics — pinned by M1-227 (re-enabling inside an active window fires that slot). This ticket does not change firing; it only suppresses the spurious missed-slot RECORD for windows that fully elapsed while the group was paused."
  - "A denormalized digest_enabled_changed_at column — deliberately NOT added. The needed 'when was it re-enabled' timestamp is read from the DIGEST_ENABLE audit rows M1-227 already writes, exactly mirroring DigestScheduler.latestApprovalTime's read of APPROVE_GROUP rows. No migration."
acceptance:
  - "A new package-private helper `DigestScheduler.latestDigestEnableTime(UUID groupId)` mirrors the existing `latestApprovalTime`: `SELECT max(created_at) FROM audit_log_view WHERE action = 'DIGEST_ENABLE' AND target_kind = 'group' AND target_id = ?`, returning the `Instant` or null when the group has no `DIGEST_ENABLE` row (never toggled / on by default). Reads `audit_log_view` (provider role has INSERT-only on `audit_log`), same as `latestApprovalTime`."
  - "In `DigestScheduler.processSlot`, the past-window branch (`now` not before `windowEnd`, no cache row) gains a pause carve-out placed AFTER the existing approval carve-out and BEFORE `recordMissedSlot`: `Instant enabledAt = latestDigestEnableTime(group.id); if (enabledAt != null && enabledAt.isAfter(windowEnd)) return null;`. Rationale (commented): a group currently enabled whose most-recent re-enable happened after this window ended was paused through the window, so the slot is neither caught up nor recorded as missed — symmetric to the approval carve-out. (A currently-disabled group never reaches this branch: it is excluded from `queryActiveGroups` by M1-227's gate.)"
  - "A named `DigestSchedulerMissedSlotTest` case (DB-backed) seeds an approved, non-removed, currently-enabled group with a `DIGEST_ENABLE` audit row whose `created_at` is AFTER a fully-elapsed slot window's end and no cache row for that window, advances `now` past `windowEnd`, and asserts NO missed slot is recorded for that window (e.g. `recordMissedSlot` not invoked / no missed-slot notification fired)."
  - "A named `DigestSchedulerMissedSlotTest` case asserts the genuine-miss path still records: an enabled group with NO `DIGEST_ENABLE` row (or whose latest `DIGEST_ENABLE` predates `windowEnd`), past window-end with no cache row, IS still recorded as a missed slot — the carve-out must not suppress real misses."
  - "`docs/spec/commands.md` §Periodic group digests extends the existing skip-not-catch-up rule to name the pause case: a slot window a group was disabled through (re-enabled after the window ended) is neither caught up nor recorded as missed, symmetric to the approval carve-out."
  - "mvn -B clean verify from the repo root exits 0; all tests currently green on main stay green."
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestSchedulerMissedSlotTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-08
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 105
      removed: 7
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-228: Don't record a missed digest slot for a window the group was paused through

## Context

M1-227 adds `/digest on|off`, gating the periodic digest by ANDing
`digest_enabled` into `DigestScheduler.queryActiveGroups()`. A paused
group is excluded from the candidate list, so `processSlot` never runs
for it while paused — correct for firing.

But on the first scheduler tick AFTER `/digest on`, a group re-enabled
*after* its slot window already elapsed re-enters the candidate list and
`processSlot` takes the past-window branch: `now >= windowEnd`, no cache
row for that window, and the approval carve-out does not apply (the group
was approved long ago). It therefore calls `recordMissedSlot(...)` and
emits one missed-slot notification (E4008-class) — a **false positive**:
nothing was missed, the operator intentionally paused the group.

`processSlot` already has the right shape for the fix. The approval
carve-out immediately above the missed-slot record skips windows that
ended before the group became eligible:

```
Instant approvedAt = latestApprovalTime(group.id);
if (approvedAt != null && !windowEnd.isAfter(approvedAt)) return null;
```

This ticket adds the symmetric pause carve-out using the `DIGEST_ENABLE`
audit rows M1-227 writes — no new column, mirroring
`latestApprovalTime`'s read of `APPROVE_GROUP`.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- **Why audit-derived, not a column.** `DigestScheduler` already reads
  `audit_log_view` for `latestApprovalTime`; the pause boundary is the
  same shape (`max(created_at)` for a per-group action). A
  `digest_enabled_changed_at` column would be a second source of truth
  for a fact the append-only audit log already holds, plus a migration.
- **Single-toggle assumption.** "Currently enabled AND latest
  `DIGEST_ENABLE` is after `windowEnd`" correctly classifies the common
  case (one off→on cycle around a window). Pathological churn (multiple
  toggles inside one window) at worst skips a missed-slot record for a
  window the admin was actively fiddling with — the safe direction (no
  false alert), never a suppressed real miss for a stable group.
- **No firing change.** The emit branch (within-window) is untouched;
  this only short-circuits the past-window `recordMissedSlot` path.
