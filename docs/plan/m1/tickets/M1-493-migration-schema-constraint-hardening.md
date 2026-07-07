---
id: M1-493
title: "Schema hardening: NOT NULL upstream_identifier + approve_quarantine phantom NOTIFY"
status: abandoned
created: 2026-06-27
last_updated: 2026-06-29
blocked_by: []
abandoned_reason: decomposed
decomposed_into:
  - M1-516
  - M1-517
files_budget: 5
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - "18#F1 (price_snapshot UPDATE grant), 18#F2 (duplicate index), 18#F3 (stage2_verdict CHECK) were all FALSIFIED — already fixed by later migrations V39, V42, V36 respectively. Do not re-do them."
acceptance:
  - >-
    post.upstream_identifier is NOT NULL, matching docs/spec/schema.md's non-null
    UID contract (V7__joins_post.sql:139 currently nullable; confirmed no later
    migration adds the constraint). Lands via a forward migration.
  - >-
    approve_quarantine no longer emits a phantom new_post NOTIFY when its post
    UPDATE matches zero rows: the latest function body (V50__banned_admin_actor_checks.sql,
    the UPDATE post ... WHERE id=v_post_id AND fetched_at=v_post_fetched_at at
    ~115-123 followed by an unconditional pg_notify('new_post') at ~128) guards
    the new_post NOTIFY with IF FOUND / GET DIAGNOSTICS. Lands via a forward
    migration redeclaring the function (carrying the current body forward).
  - >-
    Tests cover: a null upstream_identifier insert is rejected; approve_quarantine
    on a quarantine row whose post no longer exists fires no new_post NOTIFY.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-core/src/test/java/app/zcat/infochat/core/schema/UpstreamIdentifierNotNullIT.java"
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/outbox/ApproveQuarantinePhantomNotifyIT.java"
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
escalations:
  - date: 2026-06-29
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (budget-breach). F1 (NOT NULL on post.upstream_identifier) breaks
      44 INSERT INTO post sites across 37 existing test files that omit
      upstream_identifier (verified by grep: production PostPersister supplies
      it, only test fixtures take the shortcut). test_plan.preserves forbids
      breaking them, so landing F1 requires editing ~37 files vs files_budget: 5.
      No surgical shortcut: a column DEFAULT/trigger would mask the contract
      (forbidden shim, defeats the constraint) and the inserts share no
      chokepoint. F2 (approve_quarantine phantom NOTIFY guard) is independent
      and in-budget (~2 files).
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-29
  verdict: WARN
  warnings:
    - "COMPLEXITY-RISK-CALIBRATED: risk:low flagged as possibly under-calibrated for a migration_touch NOT NULL add. Premise (live production NULL rows) does not hold — M1 is greenfield, migrations run against a fresh schema with no pre-existing data; risk:low retained."
  blockers: []
---

# M1-493: Schema hardening — NOT NULL upstream_identifier + approve_quarantine phantom NOTIFY

## Context

From `/deep-code-review full` (2026-06-27), report `19-migrations-01.md`,
findings F1 and F2 — the two of the migration set that **survived** a
falsification pass against the full V1–V51 chain. (The three sibling findings —
18#F1 price_snapshot UPDATE, 18#F2 duplicate index, 18#F3 stage2_verdict CHECK —
were all already fixed by later migrations V39 / V42 / V36 and are NOT in scope;
the original verification missed them by reading only the defining migration.)

- **19#F1** — `post.upstream_identifier` is nullable (`V7__joins_post.sql:139`),
  contradicting `docs/spec/schema.md`'s non-null UID contract; no later migration
  adds NOT NULL (verified across V8–V51). Masked today by the non-null `uid` +
  unique key, but the storage layer doesn't back the contract.
- **19#F2** — `approve_quarantine`'s latest body (V50) fires
  `pg_notify('new_post', ...)` unconditionally even when its post UPDATE matched
  zero rows (reachable when the post was TTL-dropped — quarantine has no FK to
  post). Verified the guard is absent across all redeclarations V21/V25/V32/V41/V48/V50.

## Acceptance

See frontmatter. Add the NOT NULL constraint and the `IF FOUND` guard via forward
migrations; cover both behaviorally.

## Out-of-scope

See frontmatter. The three already-fixed findings are explicitly excluded.

## Notes

- Source: `/deep-code-review full` (2026-06-27), reports 19#F1, 19#F2.
- `migration_touch: true`; forward migrations only (the schema is append-only).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-493-*.md
```
