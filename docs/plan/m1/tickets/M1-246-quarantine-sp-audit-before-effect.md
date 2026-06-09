---
id: M1-246
title: "Quarantine stored procedures: audit-before-effect reorder"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 4
files_scope:
  - infochat-core/src/main/resources/db/migration
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - The NOTIFY payloads and pg_notify ordering — NOTIFYs stay last; only the audit_log INSERT moves ahead of the UPDATEs.
  - delete_preban_user (V5) — already audit-before-effect; it is the reference shape, not a target.
  - The details_json payload contents — v_post_id is read at the FOR UPDATE, so the payload is unaffected by the reorder.
  - Weakening Invariant 7 (opus's Option B) — explicitly rejected; the fix restores the invariant, it does not relax it.
acceptance:
  - "A new CREATE OR REPLACE FUNCTION migration reorders approve_quarantine (originally V41) and reject_quarantine (originally V32) so the INSERT INTO audit_log precedes the UPDATE quarantine / UPDATE post mutations in both bodies, matching delete_preban_user's audit-before-effect shape and Invariant 7 (schema.md §Invariants); the pg_notify calls remain last and v_post_id is captured at the FOR UPDATE so details_json is unchanged."
  - "A test exercises approve and reject through the reordered procedures and asserts the audit_log row exists with the same details_json as before and the quarantine/post state transitions are unchanged (behavior-preserving reorder); the migration applies cleanly on a fresh DB (mvn verify runs Flyway migrate)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Invariants
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-246: Quarantine stored procedures — audit-before-effect reorder

## Context

Source: `deep-code-review/v3/` UNIFIED-REPORT.md T7 (opus arch `#F2`, medium
rules-drift).

`V41 approve_quarantine` runs `FOR UPDATE` → `UPDATE quarantine` → `UPDATE post`
→ `INSERT INTO audit_log` → `pg_notify`: the audit lands **after** the mutations.
`reject_quarantine` (V32) has the same shape. The sibling `V5 delete_preban_user`
does audit-before-effect and comments the invariant. Invariant 7 (`schema.md
§Invariants`) is "audit-before-effect." Blast radius is bounded (single
transaction, atomic rollback) → medium, not high — but the two quarantine
procedures should match the invariant and their sibling.

## Acceptance

See frontmatter. In prose: a `CREATE OR REPLACE FUNCTION` migration reorders both
procedure bodies so the `audit_log` INSERT precedes the UPDATEs, with NOTIFYs
last and `details_json` unaffected; a test confirms the reorder is
behavior-preserving; the migration applies cleanly; `mvn verify` is 0.

## Out-of-scope

See frontmatter. NOTIFY ordering, `delete_preban_user`, the payload contents, and
the invariant itself are untouched. Opus's Option B (weaken the invariant) is
rejected.

## Notes

- `migration_touch: true` — serializes against other migration-touching tickets
  (M1-245). Sweep in-flight worktrees for the highest `V*.sql` before assigning
  the next version.
- `security_relevant: true` because audit integrity (Invariant 7) is an
  audit-evasion-lens property; a `/redteam` pass is appropriate.
- `v_post_id` is read at the `FOR UPDATE` in both bodies — keep that read where it
  is so the moved `details_json` payload is byte-identical.
</content>
</invoke>
