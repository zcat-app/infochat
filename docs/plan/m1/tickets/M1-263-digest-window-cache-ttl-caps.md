---
id: M1-263
title: "Digest correctness: collection window, cache TTL, caps"
status: done
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 12
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The on-demand /summary path — it already honors the cluster cap; only the periodic digest path changes.
  - DigestScheduler slot timing/centering — slots fire when they fire; only the content-collection lower bound and the cache TTL change.
  - /retry mechanics beyond the cache row being findable (DigestRetryService logic itself unchanged).
  - The digest prose/rendering format.
acceptance:
  - "DigestWorker collects posts for the full inter-digest period: the collection lower bound is the previous digest boundary for the group (e.g. the prior slot's window or last successful digest), not slot.windowStart. A named test asserts a post published between two digest slots (outside the slot window) appears in the next digest."
  - "The digest cache row's expires_at outlives the slot window by the retry horizon: a named test asserts a /retry --digest issued after windowEnd but within the retry window finds a non-expired cache row instead of degrading."
  - "The periodic digest bounds its per-cluster LLM fan-out by infochat.summary.cluster-cap, same as the on-demand /summary path; a named test asserts the cap is enforced."
  - "DigestWorker's render executor is container-managed (no static unmanaged Executors field); it is shut down with the application lifecycle."
  - "DigestPostCollector queries run under the standard statement timeout used elsewhere in the provider."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-09
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 12
      added: 474
      removed: 21
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-09
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-263: Digest correctness: collection window, cache TTL, caps

## Context

Deep-review v4 verified HIGH **H2** plus mediums **M-P1**, **M-P8**, and the
digest slice of **M-P13** (`deep-code-review/v4/UNIFIED-REPORT.md` §1/§2;
sources `deep-code-review/v4/fable5/07-module-infochat-provider.md#F2/#F3`,
`deep-code-review/v4/opus-48/07-module-infochat-provider.md#F1`,
`deep-code-review/v4/opus-47/07-module-infochat-provider.md#F5`,
`deep-code-review/v4/mimo/report.md` MED-011):

- **H2 leg 1:** `DigestWorker.executeSlot` → `collectForGroup(groupId,
  slot.windowStart())` where `windowStart = center − windowWidth/2` (default
  width 30 min). A twice-daily digest therefore collects only posts published
  in the last 15–30 minutes; almost every digest is "no posts yet". The window
  is the *slot* window, not the *inter-digest* period.
- **H2 leg 2:** `cacheRepository.upsert(..., slot.windowEnd())` sets
  `expires_at` = `windowEnd` (≤30 min out), so `/retry --digest` (per
  `DigestRetryService`) finds an expired row and degrades.
- **M-P1:** neither digest SQL has a `LIMIT` and the renderer issues
  per-cluster LLM calls; the on-demand `/summary` path honors
  `infochat.summary.cluster-cap` while this path doesn't. (The render future
  is cancelled at `windowEnd`, so the fan-out is wall-clock-bounded — but it
  still saturates the single local LLM slot for the whole window.)
- **M-P8:** `DigestWorker.RENDER_EXECUTOR` is a static
  `Executors.newVirtualThreadPerTaskExecutor()` with no shutdown and no CDI
  lifecycle.
- **M-P13 (digest slice):** `DigestPostCollector` lacks the statement-timeout
  hygiene used elsewhere. (`ExportDataCollector`'s slice lives in M1-275.)

## Acceptance

See frontmatter. The five legs share the digest record/flow, which is why the
report bundles them into one ticket (suggested cut T2).

## Out-of-scope

See frontmatter. Existing digest tests that pin the slot-window collection
behavior or `windowEnd` TTL are authorized for modification (listed under
`test_plan.modifies`) — this ticket deliberately reverses those two behaviors.

## Notes

- "Previous digest boundary" needs one decision at implementation time:
  derive from the schedule (previous slot's center/window) or persist a
  last-successful-digest marker per group. Prefer whichever the existing
  schema supports without a migration; the report's framing is simply that the
  lower bound must cover the inter-digest period.
- The retry horizon for `expires_at` should come from the existing
  DigestRetryService configuration, not a new constant.
- Per the behavior-reversal rule, grep digest tests for assertions pinning
  `windowStart`-bounded collection or `windowEnd` expiry before finalizing the
  diff; modifying those tests is authorized here.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-263-*.md
```
