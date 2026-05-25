---
id: M1-083
title: Quarantine/audit redteam remediation — rate bucket, audit coverage, pagination
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by:
  - M1-081b
remediates: M1-081b
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/QuarantineCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AuditCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/QuarantineCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AuditCommandHandlerTest.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
  - infochat-provider/src/main/resources/bundles/en.properties
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-collector/** — Collector-side code is not touched
  - infochat-core/src/main/resources/db/migration/** — no migration changes
  - QuarantineReviewListener.java or QuarantineReviewReconciler.java — the listener/reconciler are not affected by these fixes
  - any change to InboundRouter.java rate-limiting infrastructure — this ticket wires existing RateLimitBucket into the command handler, it does not change the rate-limiting framework itself
  - any change to the approve_quarantine or reject_quarantine stored procedures — the stored procedures are correct; this ticket adds the rate bucket at the handler layer
acceptance:
  - "QuarantineCommandHandler.handleApprove applies a per-admin rate bucket (keyed by actor user id) before calling approve_quarantine; rate-exceeded requests return a friendly rate-limit reply without calling the stored procedure"
  - "QuarantineCommandHandler.handleReject applies the same per-admin rate bucket as approve"
  - "QuarantineCommandHandlerTest verifies that rapid sequential /quarantine approve calls hit the rate limit after the bucket drains"
  - "AuditCommandHandler.handle writes an audit_log row (action AUDIT_READ) after the admin check passes and before returning results — the audit row records actor_user_id, actor_contact_id, adapter, and the filter parameters (--actor, --action) in details_json"
  - "QuarantineCommandHandler.handleList writes an audit_log row (action QUARANTINE_LIST) after the admin check passes and before returning results — the audit row records actor_user_id, actor_contact_id, adapter, and whether --all was passed in details_json"
  - "AuditAction enum gains AUDIT_READ and QUARANTINE_LIST values"
  - "AuditCommandHandlerTest verifies that a successful /audit invocation writes an AUDIT_READ audit row"
  - "QuarantineCommandHandlerTest verifies that a successful /quarantine list invocation writes a QUARANTINE_LIST audit row"
  - "QuarantineCommandHandler.handleList supports --page N (1-indexed); default is page 1; invalid or out-of-range page values are clamped"
  - "QuarantineCommandHandlerTest verifies --page 2 returns the second page of results when enough rows exist"
  - "mvn -B clean verify from the repo root exits 0"
  - "Every prior test continues to pass"
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/QuarantineCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AuditCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
  - docs/spec/security.md §Authorization model step 8
  - docs/spec/commands.md §Admin (bot admin)
decision_refs:
  - D9
  - D34

reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-083: Quarantine/audit redteam remediation

## Context

Red-team audit of M1-081b (2026-05-26) surfaced three findings:

1. **DOS/medium** — `/quarantine approve` has no per-admin rate bucket.
   The spec (security.md §Rate limiting) explicitly lists
   "`/quarantine approve` — per-admin bucket" as a required bucket.
   The handler calls the stored procedure with no command-level rate
   limiting; only the generic transport-level cap applies.

2. **AUDIT-EVASION/medium** — `/quarantine list` and `/audit` perform
   privileged admin-only reads without writing any audit row. The spec
   requires "audit-log the intent" (step 8) for every command. The
   codebase pattern audits admin-only reads (e.g. `LIST_SOURCES_ALL`
   for `/list-sources --all`).

3. **AUDIT-EVASION/low** — `/quarantine list` missing `--page N`
   support. The handler hardcodes page=1 and pageSize=20; entries
   beyond index 20 are invisible to the admin through the chat
   interface.

Full findings: `docs/plan/m1/redteam/M1-081b-2026-05-26.md`

## Approach

1. Add `AUDIT_READ` and `QUARANTINE_LIST` values to `AuditAction` enum.
2. Wire audit-log writes into `AuditCommandHandler.handle` and
   `QuarantineCommandHandler.handleList` after the admin check.
3. Wire a per-admin rate bucket into `handleApprove` and `handleReject`
   using the existing `RateLimitBucket` infrastructure.
4. Add `--page N` parsing to `handleList` (same pattern as
   `AuditCommandHandler`'s existing `--page` support).
5. Add test coverage for all three fixes.
