---
id: M1-152
title: "Schema-hardening migration (stage2_verdict CHECK + V27 audit verb + Nostr index)"
status: done
revisions:
  - date: 2026-06-06
    reason: budget-breach rework — acceptance item 2's enum addition requires touching AuditAction.java in infochat-core, which the original files_scope did not cover; widened files_scope by one path
    prior_values: |
      files_scope:
        - infochat-core/src/main/resources/db/migration
        - infochat-collector/src/main/java/app/zcat/infochat/collector
        - infochat-collector/src/test/java/app/zcat/infochat/collector
      The AuditAction closed set named by acceptance item 2 lives at
      infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java;
      the or-branch ("correct the migration to an existing verb") has no
      semantically valid target verb, so the enum path is the only correct
      implementation and must be in scope.
created: 2026-06-02
last_updated: 2026-06-06
escalations:
  - date: 2026-06-06
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (pre-implementation files_scope conflict): acceptance item 2 requires
      adding the V27 verb D47_GROUP_ONLY_PREBAN_CONVERSION to the AuditAction
      closed set, but the enum lives at
      infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java,
      outside files_scope (which covers only infochat-core/.../db/migration and
      the collector main/test trees). The or-branch ("correct the migration to
      an existing verb") has no semantically valid target verb.
blocked_by: []
files_budget: 5
files_scope:
  - infochat-core/src/main/resources/db/migration
  - infochat-core/src/main/java/app/zcat/infochat/core/audit
  - infochat-collector/src/main/java/app/zcat/infochat/collector
  - infochat-collector/src/test/java/app/zcat/infochat/collector
complexity: low
risk: medium
round_cap: 2
security_relevant: true
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
reviews:
  - round: 1
    date: 2026-06-06
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 234
      removed: 10
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-06
    verdict: CLEAN
    base: 0dcf13b (fork point of m1/M1-152-schema-hardening-migration)
    head: working tree of m1/M1-152-schema-hardening-migration (pre-commit, post-APPROVE)
    verdict_file: docs/plan/m1/redteam/M1-152-2026-06-06.md
    out_of_model_count: 1
    note: |
      CLEAN. stage2_verdict CHECK, AuditAction closure member, and the
      composite index all tighten or are read-neutral; none opens a hole.
      One OUT-OF-MODEL advisory (Nostr since-cursor poisoning via
      attacker-controlled published_at) is pre-existing behavior outside
      this diff and unguaranteed by the threat model — recommend a separate
      ticket to clamp Nostr created_at in the NostrEvent/PostPersister path,
      not this migration. No action required for M1-152.
clarity_check:
  date: 2026-06-06
  verdict: WARN
  warnings:
    - "SECURITY-FLAG-CONSISTENT: security_relevant: false may be under-claimed. The ticket enforces the stage2_verdict security-classification closed set at the DB layer and fixes an audit_log action-verb gap (V27). Both surfaces are audit-integrity adjacent. Consider flipping to security_relevant: true before start."
  blockers: []
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
