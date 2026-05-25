---
id: M1-072
title: DefaultRedactionHook generic pattern — JSON-safe replacement
status: done
created: 2026-05-25
last_updated: 2026-05-25
blocked_by: []
files_budget: 3
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/DefaultRedactionHook.java
  - infochat-core/src/test/java/app/zcat/infochat/core/audit/RedactionHookTest.java
  - infochat-core/src/test/java/app/zcat/infochat/core/audit/AuditLogWriterIT.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
source: deep-code-review full-2026-05-25-1901 (02-module-infochat-core.md#F1)
out_of_scope:
  - any change to the first 6 specific API-key patterns (OpenAI, Anthropic, GitHub, AWS, Google, Slack) — they already match only the key value
  - any AuditLogWriter logic change — the writer is correct; the redactor is producing invalid input
  - any new redaction pattern
acceptance:
  - "The 7th (generic) redaction pattern replaces ONLY the secret value (the 32+ char alphanumeric string), preserving the keyword and JSON separator characters. Verify: RedactionHookTest.genericPatternPreservesJsonStructure passes"
  - "A details_json field containing {\"token\":\"<64-char-value>\"} is redacted to {\"token\":\"[REDACTED]\"} (valid JSON). Verify: RedactionHookTest.genericPatternProducesValidJson passes"
  - "AuditLogWriter can successfully INSERT a row whose details_json was processed by the generic pattern. Verify: AuditLogWriterIT.redactedJsonCastsSuccessfully passes"
  - "mvn -pl infochat-core verify is green"
test_plan:
  adds:
    - RedactionHookTest.genericPatternPreservesJsonStructure (new)
    - RedactionHookTest.genericPatternProducesValidJson (new)
    - AuditLogWriterIT.redactedJsonCastsSuccessfully (new)
  modifies:
    - RedactionHookTest existing generic-pattern assertion (updated to expect only value redacted)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Secrets handling
decision_refs: []
clarity_check:
  date: 2026-05-25
  verdict: PASS
  warnings: []
  blockers: []
revisions:
  - date: 2026-05-25
    reason: clarity-fail
    changes: "Fixed spec_refs anchor from §Audit log redaction to §Secrets handling"
escalations:
  - date: 2026-05-25
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      SPEC-REFS-VALID: FAIL
        docs/spec/security.md §Audit log redaction → ANCHOR-NOT-FOUND.
        No heading contains the substring "audit log redaction" (case-insensitive).
        The section covering audit-log redaction is "## Secrets handling" (line 999).
        The correct anchor would be §Secrets handling.
reviews:
  - round: 1
    date: 2026-05-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 119
      removed: 22
redteam_findings: []
redteam_audits:
  - date: 2026-05-25
    verdict: CLEAN
    base: main
    head: m1/M1-072-redaction-hook-json-safe
    verdict_file: docs/plan/m1/redteam/M1-072-2026-05-25.md
    out_of_model_count: 0
    note: |
      No findings. Regex restructuring preserves all security properties.
---

## Context

The 7th (generic) redaction pattern in `DefaultRedactionHook` uses `Matcher.replaceAll` which replaces the **entire match** — including the keyword (`token`, `api_key`, etc.) and JSON separator characters (`":"`) — not just the secret value. When triggered on JSON like `{"token":"<long-value>"}`, it produces invalid JSON that fails PostgreSQL's `?::jsonb` cast in `AuditLogWriter`, rolling back the entire admin action transaction.

## Fix approach

Use a group-reference replacement (`$1[REDACTED]`) that preserves the keyword and separator, replacing only the captured secret-value group. Alternative: use a lookbehind so the keyword/separator anchors the match without being consumed.
