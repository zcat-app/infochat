---
id: M1-516
title: "approve_quarantine: guard new_post NOTIFY when post UPDATE matches zero rows"
status: pending
created: 2026-06-29
last_updated: 2026-06-29
blocked_by: []
files_budget: 3
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: true
decomposed_from: M1-493
out_of_scope:
  - "F1 (NOT NULL on post.upstream_identifier) — split to M1-517. Do NOT add the NOT NULL constraint or touch the 37 post-insert test fixtures here; that churn belongs to M1-517."
  - "The other two NOTIFYs in approve_quarantine: the quarantine_review NOTIFY (always fires — the quarantine UPDATE always matches the FOR UPDATE-locked row) is unchanged. Only the new_post NOTIFY is guarded."
acceptance:
  - >-
    approve_quarantine no longer emits a phantom new_post NOTIFY when its post
    UPDATE matches zero rows. The latest function body
    (V50__banned_admin_actor_checks.sql: the UPDATE post ... WHERE id=v_post_id
    AND fetched_at=v_post_fetched_at at ~115-123 followed by an unconditional
    pg_notify('new_post') at ~128-129) is redeclared via a forward migration
    that carries the V50 body forward verbatim and guards the new_post NOTIFY
    with a GET DIAGNOSTICS ROW_COUNT / IF FOUND check on that UPDATE. The
    quarantine_review NOTIFY still fires unconditionally.
  - >-
    Test: approve_quarantine on a PENDING quarantine row whose post no longer
    exists (TTL-dropped — quarantine has no FK to post) completes successfully,
    fires NO new_post NOTIFY, and still fires the quarantine_review NOTIFY. A
    control case (post still present) fires both.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/notify/ApproveQuarantinePhantomNotifyIT.java"
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-516: approve_quarantine — guard new_post NOTIFY when post UPDATE matches zero rows

## Context

Split from M1-493 (decomposed: budget-breach) — this is finding 19#F2 from
`/deep-code-review full` (2026-06-27). F2 is independent of and far smaller
than F1 (the NOT NULL constraint, now M1-517), so it lands on its own.

`approve_quarantine`'s latest body (`V50__banned_admin_actor_checks.sql`)
fires `pg_notify('new_post', ...)` unconditionally even when its
`UPDATE post ... WHERE id = v_post_id AND fetched_at = v_post_fetched_at`
matched zero rows. That UPDATE can match zero rows when the post was
TTL-dropped — `quarantine` has no FK to `post`, so a quarantine row can
outlive its post. The phantom NOTIFY then tells the Provider's
NewPostListener to chase a post that does not exist. Verified the guard is
absent across every redeclaration V21/V25/V32/V41/V48/V50.

## Acceptance

See frontmatter. Redeclare `approve_quarantine` via a forward migration that
carries the V50 body forward verbatim, adding a `GET DIAGNOSTICS
v_rows = ROW_COUNT` (or `IF FOUND`) check after the `UPDATE post` so the
`new_post` `pg_notify` only fires when a post row was actually updated. The
`quarantine_review` `pg_notify` stays unconditional.

## Out-of-scope

See frontmatter. The NOT NULL constraint (F1) and its 37-file test-fixture
churn are M1-517. Do not touch the `post` table DDL or any post-insert
fixture here.

## Notes

- Source: `/deep-code-review full` (2026-06-27), report 19#F2.
- `migration_touch: true`; forward migration only (schema is append-only).
  The next free version is V53+ (current head is V52).
- Reference for the redeclare-carrying-body-forward pattern: V50 itself
  (CREATE OR REPLACE FUNCTION carrying each prior body forward verbatim,
  with `SET search_path = pg_catalog, public` and the `(UUID, UUID)`
  signature preserved so it replaces rather than overloads and ACLs survive).
- Test infra: the sibling
  `infochat-collector/src/test/java/app/zcat/infochat/collector/notify/QuarantineProcedureNotifyIT.java`
  shows the `@QuarkusTest` + `@Inject @SeedDataSource DataSource` + LISTEN/
  `PGConnection.getNotifications` polling idiom this test should follow.
  (M1-493 originally named the `collector/outbox/` package; `collector/notify/`
  is where the analogous NOTIFY-assertion test and its helpers already live.)

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-516-*.md
```
