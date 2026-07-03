---
id: M1-549
title: live-reset preserves provider_state (F-live-4)
status: pending
created: 2026-07-03
last_updated: 2026-07-03
blocked_by: []
files_budget: 3
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - re-seeding the V9/V21 sentinel rows from the reset SQL (the rejected
    alternative — it duplicates the sentinel shape in a third place, and
    the script's "every control-plane table empty" assertion would need a
    special case for it anyway)
  - V9/V21 migration changes, or softening NewPostReconciler /
    QuarantineReviewReconciler's missing-row IllegalStateException (the
    fail-fast is the spec'd behavior and did its job — it exposed this bug)
  - prod/live-seed.sh and prod/live-inject-adversarial.sh (neither touches
    provider_state)
  - F-live-5 (LLM timeout profile defaults) and F-live-3 (collector
    startup race) — separate tickets
  - docs/plan/live-e2e/ handoff updates (post-merge process doc)
acceptance:
  - prod/sql/reset-control-plane.sql no longer truncates provider_state;
    a comment records why the table is preserved (it is the Provider's
    cursor over the PRESERVED data-plane, and its sentinel rows are
    inserted only by Flyway first-boot migrations V9/V21, so truncation
    boot-loops the next Provider start — F-live-4).
  - prod/live-reset.sh's CONTROL_PLANE_TABLES no longer lists
    provider_state (the post-reset emptiness assertion must not flag the
    preserved rows), and its header/count comments match the SQL's
    18-table truncate/delete set.
  - docs/testing/USER_TEST_PLAN.md §Live-iteration reset no longer names
    provider_state among the cleared tables.
  - "Host validation (post-verify, M1-546 precedent): on the live
    deployment, run prod/live-reset.sh, restart the Provider WITHOUT any
    manual sentinel re-seed, and observe readiness UP (no
    provider_state-missing boot-loop); provider_state still holds its
    new_post and quarantine_review rows after the reset."
  - mvn verify is green.
test_plan:
  adds: []
  # prod/ tooling has no automated test harness (M1-536 precedent: the
  # script's own pre/post assertions ARE the verification). The behavior
  # change is proven by the host validation acceptance item.
  preserves:
    - all tests currently green on main (no src/main or src/test change)
spec_refs:
  - docs/spec/schema.md §Operational
  - docs/testing/USER_TEST_PLAN.md §The automation boundary
decision_refs:
  - D34
---

## Context

**F-live-4 (MEDIUM, tooling; bit BOTH resets of the 2026-07-03 live 4b-3
run):** `prod/live-reset.sh` TRUNCATEs `provider_state` (it is in the
19-table control-plane list), but the `new_post` / `quarantine_review`
sentinel rows are inserted only by Flyway first-boot migrations (V9 /
V21), which never re-run — so the next Provider boot throws
`IllegalStateException: provider_state row for channel='new_post' is
missing` (`NewPostReconciler.runCatchUp`) and the container boot-loops.
The reset script's own epilogue ("restart the Provider to re-seed")
covers only the admin bootstrap. The host workaround (manual owner-role
re-INSERT of both sentinel rows after every reset) is in the live-e2e
running log.

## Design: exclude provider_state from the reset (not re-seed)

The finding offered two fixes; exclusion wins on every axis checked:

- **provider_state is a cursor over the data-plane, which the reset
  preserves.** `cursor_high`/`cursor_low_id` reference post `ready_at` /
  post ids — preserved rows. Keeping the cursor is consistent with the
  reset's own invariant ("data-plane byte-for-byte intact") and avoids
  the epoch-cursor page-through of ~3.7k preserved posts on the next
  boot.
- **No scenario-visible behavior depends on an epoch cursor.**
  `NewPostHandler.handle`'s only side effect is advancing the cursor
  itself (no subscriber push on `new_post` in v1); the digest watermark
  is `summary_anchor`, and `/summary`/chat retrieval is deterministic
  SQL. Verified against the 7 live scenarios — none observes the cursor.
- **Re-seeding instead would duplicate the V9/V21 sentinel shape in a
  third place** (a sync hazard) and would ALSO require special-casing
  the script's "every control-plane table empty" assertion — strictly
  more moving parts.
- **Defense-in-depth alignment:** V9 REVOKEs DELETE on provider_state
  from both service roles ("the row is upserted, never deleted");
  the owner-role TRUNCATE contradicted that design intent.

## Implementation anchors (surveyed 2026-07-03)

- `prod/sql/reset-control-plane.sql`: `provider_state` in the TRUNCATE
  list (l.82); header comment "control-plane rows (users / ... /
  provider state)" (l.3–4).
- `prod/live-reset.sh`: `CONTROL_PLANE_TABLES` (l.45–50, includes
  `provider_state`); "The 19 control-plane tables" comment (l.42);
  header "(users / groups / invites / chat / audit / provider state)"
  (l.4–5).
- `docs/testing/USER_TEST_PLAN.md` §Live-iteration reset: cleared-table
  example list names `provider_state` (l.167).
- Boot-loop site: `NewPostReconciler.runCatchUp` (and the
  `QuarantineReviewReconciler` sibling) — unchanged by this ticket.

## Not security_relevant — justification

Test-loop tooling only; no trust boundary or threat-model surface. The
change PRESERVES rows the reset used to delete (rows that carry no user
content — channel names, timestamps, post/quarantine ids), and restores
alignment with V9's no-DELETE defense-in-depth posture.
