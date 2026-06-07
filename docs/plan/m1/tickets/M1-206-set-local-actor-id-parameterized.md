---
id: M1-206
title: "Parameterize SET LOCAL infochat.actor_id (drop UUID string concat)"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 9
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ApproveGroupCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/GrantAdminCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/QuarantineCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RejectGroupCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnbanCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/VouchCommandHandler.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any behavior change in these handlers — the actor id must reach the SQL session exactly as before; audit triggers keep firing identically
  - the audit-correctness legs in the same files — M1-195's
  - group-scope caller resolution in the same files — M1-198's
  - the analogous SET LOCAL observation in the collector (audit obs, no concat sites confirmed there) — out until someone verifies a site exists
acceptance:
  - "No production statement concatenates the actor UUID into SQL text: a repo grep for SET LOCAL string concatenation in src/main returns zero sites (today 12 code sites across 8 handler files build \"SET LOCAL infochat.actor_id = '\" + actor.id + \"'\" — values are internal UUIDs, so this is hygiene, not injection)"
  - "The actor id still reaches the audit trigger layer per-transaction: existing audit ITs that depend on infochat.actor_id stay green"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Authorization model
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-206: Parameterize SET LOCAL infochat.actor_id (drop UUID string concat)

## Context

Stray unified finding P19 (`deep-code-review/v2/UNIFIED.md` §2,
med-hygiene): 12 sites across the 8 files in scope build the
per-transaction actor-id marker by concatenating `actor.id` (an internal
UUID, never attacker-controlled at these call sites) into a `SET LOCAL`
statement. PostgreSQL cannot bind parameters in SET LOCAL itself, which
is presumably why the concat shape was chosen; the parameterizable
equivalent exists (see suggestion below).

Filed as its own mechanical ticket rather than riding T19/M1-195: P19's
file set is 8 handlers, most of which M1-195 does not touch, and mixing
a no-behavior-change sweep into a security-relevant audit-fix ticket
would blur both reviews.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §2 P19 under `deep-code-review/v2/` (opus-47
  prov F6, mimo prov F1) — the batch-1 prompt attached it to T19;
  UNIFIED §3's T19 text doesn't list it; batch-2 drafting filed it
  separately (disposition recorded in the batch summary).
- Shares every file with M1-198 and two files with M1-195 — serialize:
  run this AFTER both land (purely mechanical, rebases trivially).

## Suggested direction (unverified hypothesis)

`SELECT set_config('infochat.actor_id', ?, true)` is the parameterized
equivalent of SET LOCAL (the `true` makes it transaction-scoped).

Per CLAUDE.md §Verify before recommending, treat this as a hypothesis:
falsify it against the code before adopting (what would make it wrong?
is there a simpler alternative meeting the same acceptance?). Adopting,
adapting, or replacing it is the implementer's call as long as every
acceptance item holds; a replacement that changes files_scope goes
through the escalate path.
