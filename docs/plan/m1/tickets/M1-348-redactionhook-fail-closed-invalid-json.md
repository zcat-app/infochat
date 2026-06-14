---
id: M1-348
title: "DefaultRedactionHook: fail-closed to the sentinel on non-JSON redacted output"
status: done
created: 2026-06-14
last_updated: 2026-06-14
clarity_check:
  date: 2026-06-14
  verdict: WARN
  warnings:
    - "COMPLEXITY-RISK-CALIBRATED: risk: low is mildly optimistic for a change on the audit-log write path. Consider risk: medium. Not a blocker because security_relevant: true is set and the change is strictly fail-closed."
  blockers: []
blocked_by: []
files_budget: 2
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/DefaultRedactionHook.java
  - infochat-core/src/test/java/app/zcat/infochat/core/audit
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The Redactor catalogue / regex shapes — unchanged.
  - The AuditLogWriter ?::jsonb cast and the caller transaction-management contract — unchanged; this ticket makes the hook honor its own 'returns valid JSONB' post-condition so the cast cannot fail on a redactor-produced string, it does not move the boundary into the writer.
acceptance:
  - "DefaultRedactionHook.redact guarantees its returned detailsJson is either null/empty or structurally JSON-shaped: in addition to translating the Redactor.TIMEOUT_SENTINEL to REDACTED_FIELD_JSONB, it falls back to the same REDACTED_FIELD_JSONB sentinel when the redacted output is not JSON-shaped (a minimal structural check: {/[ start, balanced quotes/braces — the cheapest form, since the full parse already runs server-side at the JSONB cast). The class doc's 'translates the timeout sentinel to valid JSONB' responsibility is widened to 'the returned row's detailsJson is null/empty or a valid JSON document'."
  - "An off-contract caller input (a non-JSON detailsJson, or a hypothetical redactor output that broke JSON structure) no longer silently rolls back the surrounding audit transaction at the writer's ?::jsonb cast (an opaque 'invalid input syntax for type json' SQLException that takes the admin action with it under audit-before-effect). Instead the audit row lands with the operator-triageable sentinel marker — strictly stronger fail-closed than the existing watchdog path."
  - "A test pins fail-closed: a detailsJson that, post-redaction, is not valid JSON produces a row whose detailsJson is REDACTED_FIELD_JSONB (not an exception, not a lost row); the existing genericPatternProducesValidJson and timeout-sentinel cases stay green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core/audit (invalid-JSON fail-closed case)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Secrets handling
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-14
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 95
      removed: 9
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-14
    verdict: CLEAN
    base: 579fb46c^
    head: 579fb46c
    verdict_file: docs/plan/m1/redteam/M1-348-2026-06-14.md
    out_of_model_count: 0
    note: |
      Adversarial review of the audit-path fail-closed widening
      (DefaultRedactionHook structural JSONB guard). CLEAN — no findings,
      no out-of-model observations. Ran pre-merge against branch commit
      579fb46c; nothing feeds future tickets.
---

# M1-348: DefaultRedactionHook — fail-closed on non-JSON redacted output

## Context

Deep-review v5.5 (opus-47, `02-module-infochat-core.md` F3) found that
`DefaultRedactionHook` does not guarantee its post-redaction `detailsJson` is
still valid JSONB. **Verified at source 2026-06-14:** `redact`
(DefaultRedactionHook.java:32-56) handles only the `TIMEOUT_SENTINEL` case before
returning `redacted`; there is no structural validity check. For contract-
conforming input the substitution stays JSON-valid (the `genericPatternProducesValidJson`
IT confirms), so this is about off-contract input.

A caller passing a non-JSON `detailsJson` (a regression, an upstream JSON-builder
bug) reaches the writer's `?::jsonb` cast, which fails inside the caller's
transaction with an opaque JDBC SQLException; under audit-before-effect the
surrounding admin action rolls back, and the broken input never reaches the audit
log either. The failure is silent at the redaction layer (the hook returns
happily). The hook already owns the timeout-sentinel→valid-JSONB translation, so
widening it to also fail-closed on invalid output keeps the responsibility in one
place.

## Note on the §No-defensive-code tension (for clarity review)

`detailsJson` is built by internal audit callers, so a non-JSON value is an
internal-code bug rather than a system-boundary input — which is why this is
**low** and borderline against CLAUDE.md §"No defensive code for impossible
scenarios". The case for fixing it anyway: the hook *already* commits in its class
doc to producing valid JSONB (it translates the timeout sentinel), the audit-write
path is security-sensitive (a rolled-back action + lost audit row), and the
failure mode is silent/opaque. The fail-closed half is already built for the
watchdog path; this is a strict widening, not new defensive machinery. If clarity
review judges it over-defensive, the alternative resolution is to **reject** the
finding and instead pin the caller contract — record that decision here.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.
