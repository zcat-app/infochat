---
id: M1-207
title: "infochat_admin role: resolve the paper-principal contradiction (decision)"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: [M1-189]
files_budget: 5
files_scope:
  - infochat-core/src/main/resources/db/migration
  - infochat-collector/src/test/java/app/zcat/infochat/collector/db
  - docs/spec/security.md
  - docs/design/02-schema.md
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - the V39 grants migration content — M1-189's (this ticket is blocked on it precisely to keep grant-layer migrations ordered and non-overlapping)
  - the V35 LOCK scope — M1-190's (V40)
  - editing comments inside already-applied migrations (V2/V4/V11/V17 etc.) — Flyway checksums make applied files immutable; corrections land in the new migration's comments and in the docs
  - service-role (collector/provider) grants — untouched
acceptance:
  - "A decision is recorded and applied for the infochat_admin role: EITHER (a) a new migration grants the operator surface the migration corpus describes for it (redacted audit_log_view SELECT, heartbeat-row DELETE, invite_code_attempt TRUNCATE, EXECUTE on the operator-only partition procedures and quarantine-review escape hatches) plus the LOGIN posture decided alongside (V2/V31 comments defer exactly this re-evaluation), OR (b) the role is documented as intentionally dormant in v1, with docs/spec/security.md §DB roles and docs/design/02-schema.md §Roles stating what the operator actually uses instead (superuser psql), so no doc claims a capability the role lacks"
  - "If direction (a): a named IT (DbRoleMatrixIT pattern) asserts infochat_admin can perform each granted action and that infochat_collector/infochat_provider CANNOT perform the admin-only ones"
  - "If direction (b): a named grep-style check or IT asserts the role still holds only USAGE (today the only grant anywhere is V2:65 GRANT USAGE ON SCHEMA public), pinning the dormant posture so future migrations widening it must touch the pin"
  - "The choice and its argument are recorded in the commit message"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/db
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §DB roles
decision_refs:
  - D34
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-207: infochat_admin role: resolve the paper-principal contradiction (decision)

## Context

Stray unified finding D5 (`deep-code-review/v2/UNIFIED.md` §2,
med-decision), promised by M1-189's out_of_scope as "a separate
decision-tier ticket, not yet filed" — this is that ticket.

The `infochat_admin` role is created NOLOGIN (V2:55, re-affirmed V4:12)
and holds exactly one grant anywhere in the corpus: `GRANT USAGE ON
SCHEMA public` (V2:65). Yet six migrations document operator escape
hatches as belonging to it — redacted audit_log_view reads, heartbeat
deletes, invite_code_attempt TRUNCATE, operator-only partition
procedures (V17:11/:81, V11:136, V2:15, others) — none of which a
USAGE-only NOLOGIN role can perform. V2:37 and V31:9 both defer "the
wiring ticket re-evaluates LOGIN on infochat_admin"; nothing ever did.

Decision-tier (user call at start): grant the documented surface, or
document the role as dormant and stop the comment drift. Either
direction is small; the value is ending the contradiction.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §2 D5 under `deep-code-review/v2/` (kimi-folder
  core C-F2); filed per M1-189's out_of_scope promise (disposition
  recorded in the batch summary).
- blocked_by M1-189 keeps the grant-layer migrations strictly ordered
  (V39 → this ticket's version, if direction (a) adds one). Migration
  version: next free after the MIG-lane sweep at start (V41 is reserved
  by M1-200; V39/V40 by M1-189/M1-190).
- migration_touch is set true for the MIG lane even though direction
  (b) ships no migration — better a stale reservation than a version
  collision (workflow memory: sweep worktrees before assigning).
