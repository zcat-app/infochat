---
id: M1-143
title: "MembershipEventHandler audit-before-effect (Invariant 7)"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider
  - infochat-provider/src/test/java/app/zcat/infochat/provider
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - other admin command handlers (they already follow audit-before-effect)
  - the AuditLogWriter consolidation (deferred under M1-041)
acceptance:
  - "MembershipEventHandler wraps the audit row and the state mutation in one transaction (mirroring BanCommandHandler) so MEMBER_LEFT/BOT_REMOVED audit-before-effect holds (Invariant 7), instead of mutating then auditing then swallowing failure"
  - "GroupMembershipRepository.isGroupAdmin / markMemberRemoved gain Connection-accepting overloads for the spanning transaction"
  - "A test asserts an audit-write failure rolls back the mutation (the was_group_admin flag is not silently lost)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Invariants
  - docs/spec/security.md §Authorization model
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-143: MembershipEventHandler audit-before-effect (Invariant 7)

## Context

`MembershipEventHandler.java:105-127` mutates state, then opens a fresh
connection to audit, then logs-and-continues on failure — inverting Invariant 7
(audit-before-effect) for `MEMBER_LEFT`/`BOT_REMOVED`, unlike every other admin
handler. The `was_group_admin` flag loss has a real downstream effect on
`/unban` group-admin restoration.

## Acceptance

See frontmatter. Wrap audit + mutation in one transaction (BanCommandHandler
pattern); add Connection-accepting overloads.

## Out-of-scope

See frontmatter. **security_relevant** → run `/redteam` after.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §B-MEMBERSHIP-AUDIT;
  `opus-47-full-handout.md` §F-MAINT-67; `opus-47-only-handout.md` §M20.
