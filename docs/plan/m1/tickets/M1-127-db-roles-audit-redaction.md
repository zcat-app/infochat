---
id: M1-127
title: "DB per-service role wiring + audit_log_view redaction"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 10
files_scope:
  - infochat-core/src/main/resources/db/migration
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/main/java/app/zcat/infochat/provider/audit
  - docs/design/07-deployment.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - sweeping every privilege-mismatched DML call site (those surface as IT failures and are fixed as they appear — but do not pre-emptively rewrite unrelated handlers)
  - the GRANT matrix definitions themselves (V2/V5/V21 already define them); this ticket makes them load-bearing, it does not re-author them
acceptance:
  - "A migration grants LOGIN to infochat_collector and infochat_provider (currently NOLOGIN)"
  - "Each service connects via a Quarkus named-datasource: Flyway runs as the infochat owner, the runtime connects as the per-service role"
  - "redact_contact_id and redact_secrets_jsonb (currently no-op RETURN input stubs) implement the redaction policy from docs/spec/security.md so audit_log_view returns redacted contact ids and details_json"
  - "The Provider-side redaction hook (DefaultRedactionHook) is enabled rather than a pass-through stub"
  - "Two new operator password inputs are documented in docs/design/07-deployment.md"
  - "mvn -B clean verify from the repo root exits 0 (expect to fix real privilege-mismatched DML surfaced as IT failures)"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §DB roles
  - docs/spec/security.md §Authorization model
  - docs/spec/schema.md §Invariants
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-127: DB per-service role wiring + audit_log_view redaction

## Context

Both services connect as `quarkus.datasource.username=infochat` (the bootstrap
owner); `infochat_collector`/`infochat_provider`/`infochat_admin` are NOLOGIN and
no `SET ROLE` is issued. Every defense-in-depth layer the spec attaches to the
role split — `audit_log_view` redaction, quarantine SECURITY DEFINER carve-outs,
Invariants 4/10 — is decorative; a SQL-injection foothold in the Provider would
have owner privilege on every table today. V2 carries the explicit "until the
named-datasource wiring ticket lands" note; that ticket never landed.
Coupled (same trust boundary): `audit_log_view`'s `redact_contact_id` /
`redact_secrets_jsonb` are literal `RETURN input` no-op stubs, so `/audit`
surfaces raw contact ids and unredacted `details_json`.

## Acceptance

See frontmatter. Migration adds `LOGIN`; Quarkus named-datasource wiring runs
Flyway on the owner and the runtime on the role; the two redactors are
implemented; the Provider hook flips on.

## Out-of-scope

See frontmatter. **Expect IT failures** — they surface the real
privilege-mismatched DML the current owner-connection setup hides; fix those as
they appear, but do not pre-emptively rewrite unrelated handlers. **security_relevant**
→ run `/redteam` after. Migration version assigned at start (do not hardcode).

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A4 (DB-OWNER-ROLE, Critical, GROUNDED) +
  §A11 (AUDIT-VIEW-REDACTION, High, GROUNDED); `opus-47-full-handout.md` §F-SEC-03, F-SEC-05;
  `opus-47-only-handout.md` §TP3.
- Loci: `application.properties` collector `:13` / provider `:18`; roles
  `V2__roles.sql:32-65`; view + stubs `V5__identity_audit.sql:324-352`; Provider read
  `AuditCommandHandler.java:179-204`; hook `DefaultRedactionHook.java:14-21`.
- Plan-writer pass required — datasource wiring + redaction + the IT-failure sweep.
