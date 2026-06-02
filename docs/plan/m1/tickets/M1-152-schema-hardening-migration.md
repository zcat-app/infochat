---
id: M1-152
title: "Schema-hardening migration (stage2_verdict CHECK + V27 audit verb + Nostr index)"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 5
files_scope:
  - infochat-core/src/main/resources/db/migration
  - infochat-collector/src/main/java/app/zcat/infochat/collector
  - infochat-collector/src/test/java/app/zcat/infochat/collector
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - rewriting the already-applied V22/V27 migrations (use a new successor migration)
  - the summary_anchor / price_snapshot schema decisions (M1-160 / M1-161 investigate-skeletons)
acceptance:
  - "A new migration adds CHECK (stage2_verdict IS NULL OR stage2_verdict IN ('BENIGN','INJECTION','MALWARE','UNKNOWN')) to post (V22 left the closed set unenforced)"
  - "The audit_log.action verb V27 writes is added to the AuditAction closed set (or the migration is corrected to an existing verb)"
  - "A composite index idx_post_source_published ON post(source_id, published_at DESC) supports NostrStreamSource.latestPublishedAtEpochSeconds"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/schema.md §Operational
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-152: Schema-hardening migration

## Context

Three small migration-only additions: (C-STAGE2-CHECK) `V22__post_stage2_verdict.sql:9`
documents the closed set `BENIGN/INJECTION/MALWARE/UNKNOWN` but no CHECK enforces
it (a DB-boundary constraint is the allowed §No-defensive-code carve-out);
(C-V27-AUDIT-VERB) `V27__d47_remove_group_only.sql:51-52` writes an
`audit_log.action` absent from the `AuditAction` closed set; (C-NOSTR-INDEX)
`SELECT MAX(published_at) … WHERE source_id=?` runs per Nostr reconnect with no
supporting composite index.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. Cannot edit applied migrations — use a successor. Migration
version assigned at start (do not hardcode).

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-STAGE2-CHECK, §C-V27-AUDIT-VERB,
  §C-NOSTR-INDEX; `opus-47-full-handout.md` §F-MAINT-65/40, F-PERF-04.
