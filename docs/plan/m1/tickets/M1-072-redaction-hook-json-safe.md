---
id: M1-072
title: DefaultRedactionHook generic pattern — JSON-safe replacement
status: ready
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
  - docs/spec/security.md §Audit log redaction
decision_refs: []
---

## Context

The 7th (generic) redaction pattern in `DefaultRedactionHook` uses `Matcher.replaceAll` which replaces the **entire match** — including the keyword (`token`, `api_key`, etc.) and JSON separator characters (`":"`) — not just the secret value. When triggered on JSON like `{"token":"<long-value>"}`, it produces invalid JSON that fails PostgreSQL's `?::jsonb` cast in `AuditLogWriter`, rolling back the entire admin action transaction.

## Fix approach

Use a group-reference replacement (`$1[REDACTED]`) that preserves the keyword and separator, replacing only the captured secret-value group. Alternative: use a lookbehind so the keyword/separator anchors the match without being consumed.
