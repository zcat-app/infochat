---
id: M1-235
title: "Migration: denormalize actor cols in delete_preban_user audit row"
status: done
created: 2026-06-08
last_updated: 2026-06-09
blocked_by: []
files_budget: 3
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - The quarantine audit procedures (approve_quarantine / reject_quarantine) — V32 already restored their denormalized actor columns; unchanged.
  - In-place editing of V24 — forbidden; an already-migrated DB keeps the old body, so the fix MUST ship as a new migration (the reason V32/V41 cite).
  - The actor-admin EXISTS gate, the preban-state check, and the DELETE itself in delete_preban_user — unchanged.
  - Any other audit-writing site.
acceptance:
  - "A new Flyway migration (next free version — verify the highest V*.sql across main AND all in-flight worktrees at start; V45 as of 2026-06-08) carries the complete current delete_preban_user procedure body with ONLY the audit INSERT changed to populate actor_contact_id and actor_adapter via JOIN users a ON a.id = p_actor_id (the same pattern V5 used and V32 restored)."
  - "The migration applies cleanly on a fresh database (Flyway) and the procedure still: requires the actor be a bot admin, requires the target be in preban state, writes exactly one audit row, and deletes the preban user."
  - "A named test asserts the UNBAN_PREBAN_DELETE audit row written by delete_preban_user now carries non-null actor_contact_id and actor_adapter matching the acting admin (Testcontainers pgvector)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/DeletePrebanUserAuditDenormIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Entities
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
      files: 4
      added: 152
      removed: 8
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-09
    verdict: CLEAN
    base: c378bcf
    head: working tree (uncommitted, branch m1/M1-235-delete-preban-user-audit-denorm)
    verdict_file: docs/plan/m1/redteam/M1-235-2026-06-09.md
    out_of_model_count: 2
    note: |
      CLEAN — pure audit-completeness fix; V45 restores actor_contact_id /
      actor_adapter to the delete_preban_user audit INSERT, tightening coverage
      while carrying V24's guards forward verbatim. No gap between threat-model
      promise and delivery. Two OUT-OF-MODEL advisories (both predate this diff,
      neither a finding against it): (1) confirm the Provider /unban call path
      sets the infochat.actor_id GUC the audit-integrity trigger relies on;
      (2) spec text (security.md:581 UNBAN_DELETED_PREBAN_ROW) diverges from the
      enum/migrations (UNBAN_PREBAN_DELETE) — candidate for a separate spec:
      reconciliation, not a security gap.
clarity_check:
  date: 2026-06-08
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-235: Migration: denormalize actor cols in delete_preban_user audit row

## Context

Deep-review finding `deep-code-review/v2.5/opus-48/02-module-infochat-core.md#F1`
(medium MAINTAINABILITY-RULES-DRIFT / spec-drift). `docs/spec/schema.md`
§Entities (Audit log) defines `actor_contact_id` and `actor_adapter` as
columns "denormalized at write time for redaction-free historical lookup;
the FK target may rotate." `delete_preban_user` — whose latest definition
is `V24__identity_audit_remediation.sql:40-53` (verified: only V5 and V24
`CREATE OR REPLACE` the procedure; V32/V39/V40 only mention it in comments)
— omits both columns from its `UNBAN_PREBAN_DELETE` audit INSERT, with a
comment claiming they are "derivable from actor_user_id." That contradicts
the spec rationale: the procedure DELETEs a `users` row, exactly the case
where read-time derivation cannot reach the actor's contact id/adapter as
it stood at write time. It is also internally inconsistent — V32 restored
these columns for the quarantine procedures (V32:79,136) and its header
calls the V25 omission a defect; the identical omission here was never
remediated, so two SECURITY DEFINER audit-writing procedures now disagree.

## Acceptance

See frontmatter. In prose: ship a NEW migration (next free version; sweep
worktrees first) carrying the full current `delete_preban_user` body with
only the audit INSERT changed to denormalize `actor_contact_id` /
`actor_adapter` via `JOIN users a ON a.id = p_actor_id`; verify the
procedure's other behavior is unchanged; a Testcontainers test pins the
populated columns; `mvn verify` is 0.

## Out-of-scope

See frontmatter. The quarantine procedures, the procedure's gates/DELETE,
and in-place editing of V24 are off-limits. This is a single-procedure
audit-row correction shipped as a new migration.

## Notes

- Exact corrected INSERT...SELECT (with the `JOIN users a`) is in the
  source finding. The actor-admin EXISTS check guarantees the JOIN yields
  exactly one row, so control flow is identical to the quarantine
  procedures.
- `security_relevant: true` because this is the audit trail's integrity;
  a `/redteam` pass on the migration is appropriate.
- `migration_touch: true` serializes parallel start; confirm the version
  number against all worktrees at `start` (V45 was free on 2026-06-08).
