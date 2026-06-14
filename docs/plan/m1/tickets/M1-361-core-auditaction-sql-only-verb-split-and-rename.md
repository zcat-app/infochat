---
id: M1-361
title: "core: split SQL-only audit verbs out of the writer-facing AuditAction enum; rename the config-baked STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE verb"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 9
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditVerb.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/ProcedureOnlyAction.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditLogWriter.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/DefaultRedactionHook.java
  - infochat-core/src/test/java/app/zcat/infochat/core/audit
  - infochat-collector/src/main/java/app/zcat/infochat/collector
  - infochat-provider/src/main/java/app/zcat/infochat/provider
  - infochat-collector/src/test/java/app/zcat/infochat/collector
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The SECURITY DEFINER procedures' SQL-side INSERT of their own verbs (V5/V10/V27/V50) — unchanged; they keep writing the verb strings directly in SQL.
  - The audit_log.action column TEXT values already written by procedures — no data migration (M1 greenfield; no deployed rows).
  - Any change to which events are audited or the audit-before-effect ordering — unchanged.
acceptance:
  - "A sealed AuditVerb interface permits AuditAction and ProcedureOnlyAction; the procedure-only verbs (UNBAN_PREBAN_DELETE, APPROVE_QUARANTINE, REJECT_QUARANTINE, D47_GROUP_ONLY_PREBAN_CONVERSION) move to ProcedureOnlyAction; AuditAction keeps only application-writable verbs. AuditLogWriter.write keeps taking AuditAction so a Java caller can no longer pass a procedure-only verb (compile-time refusal); read paths that enumerate all verbs use AuditVerb."
  - "STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE is renamed to the value-agnostic STARTUP_RELEASE_ON_STAGE2_FAILURE; the observed config value moves into the audit row's details_json at the emitting call site; the deferral comment is removed."
  - "All call sites and any AuditAction.values()-iterating fixtures are updated to the split (AuditVerb.values() or the appropriate enum); the redaction-hook AuditRow type signature compiles against the narrowed AuditAction."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditVerb.java
    - infochat-core/src/main/java/app/zcat/infochat/core/audit/ProcedureOnlyAction.java
  modifies:
    - infochat-core/src/test/java/app/zcat/infochat/core/audit (verb-split + rename assertions)
    - infochat-collector/src/test/java/app/zcat/infochat/collector (verb iteration / rename follow-on, if any)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-361: AuditAction SQL-only verb split + verb rename

## Context

Two deep-review v6 findings on `infochat-core` audit, grouped (same file,
`AuditAction.java`):

- **opus-47 `02-module-infochat-core.md` F1** (medium,
  MAINTAINABILITY-RULES-DRIFT) — procedure-only verbs share the writer-facing
  enum, so the "only SQL writes these" contract is comment-enforced; a Java
  caller could `write(... APPROVE_QUARANTINE ...)` and produce an audit row that
  bypasses the SECURITY DEFINER procedure (audit-before-effect broken).
- **opus-47 `02-module-infochat-core.md` F4** (low, MAINTAINABILITY-RULES-DRIFT)
  — `STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE` bakes a config value into the verb
  name; the comment diagnoses it and defers without a tracking item. M1 is
  greenfield (no deployed audit rows), so the cleanup is cheapest now.

**Verified at source 2026-06-14:** `AuditAction.java` carries the procedure-only
verbs alongside app verbs (per the class javadoc at 27-35) and the
`STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE` constant + deferral comment at 194-203.
opus-48's core pass returned no findings (it did not examine this enum-shape
contract); the mechanism is verified above.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- The sealed split moves the constraint from comment+vigilance to compiler; a
  grep for `AuditAction.APPROVE_QUARANTINE` then finds only read-path uses.
- The verb names the EVENT; details_json carries the OBSERVED config value, so
  config evolution lands in JSON without a verb rewrite.
