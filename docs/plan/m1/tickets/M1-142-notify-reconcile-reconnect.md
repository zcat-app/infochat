---
id: M1-142
title: "NewPostListener reconcile after reconnect"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 4
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider
  - infochat-provider/src/test/java/app/zcat/infochat/provider
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the quarantine_review listener (separate channel; only new_post here)
  - the NOTIFY payload shape (covered by M1-134 where it overlaps)
acceptance:
  - "NewPostListener runs the reconciler after every successful reconnect (not only at startup), so NOTIFYs lost during a transient PG blip are recovered without a process restart"
  - "Reconciler idempotency is confirmed before wiring the post-reconnect call"
  - "A test asserts a reconnect triggers reconcile and the live cursor catches up"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Inter-service communication
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-142: NewPostListener reconcile after reconnect

## Context

On a `getNotifications` throw, `NewPostListener.java:164-211` backs off before
re-`LISTEN`; NOTIFYs in that window are lost. `NewPostReconciler` runs only at
startup, so a transient PG blip that doesn't restart the Provider leaves the
live cursor permanently behind — a "looks healthy, isn't" failure.

## Acceptance

See frontmatter. Invoke `reconcile()` after each successful reconnect; confirm
idempotency first.

## Out-of-scope

See frontmatter.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §B-NOTIFY-RECONCILE;
  `opus-47-full-handout.md` §F-MAINT-55; `opus-48-audit-handout.md` §B8.
