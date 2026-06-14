---
id: M1-363
title: "provider: remove the audit-bypassing LlmOutputSanitizer constructor; type-check tool-arg list elements at the LLM dispatch boundary"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 7
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolDispatcher.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/llm
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool
  - infochat-provider/src/test/java/app/zcat/infochat/provider
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The markdown/closed-list rewrite rules themselves and the audit-per-occurrence semantics — unchanged; this removes the WAY to bypass them, not the rules.
  - The set of validated tool args / the listMaxSize and inputMaxLength bounds — unchanged; this adds element-type validation alongside the existing size checks.
  - The dispatcher's existing IllegalArgumentException/ClassCastException/DateTimeParseException catch arms — kept.
acceptance:
  - "LlmOutputSanitizer has a single @Inject constructor with two final non-null AuditLogWriter and DataSource fields; the public no-arg test-seam constructor and the auditWiringPresent flag (and the @Nullable on those fields) are removed, so no production code path can construct an audit-bypassing sanitizer. emitAuditRows always emits."
  - "The provider tests that used new LlmOutputSanitizer() switch to mocked collaborators or the existing static helpers (applyMarkdownLinkStrip / applyClosedListStrip); LlmOutputSanitizerTest stays green."
  - "ChatToolDispatcher.validateValue rejects a list whose elements are not the accepted shape (string / nested list / map) with a ToolResult.ValidationError, so a model-supplied {\"tags\":[123]} yields a self-correctable validation error at the dispatch boundary rather than an uncaught ArrayStoreException out of SearchPostsTool's createArrayOf."
  - "A test pins that {\"tags\":[123]} (a non-string list element) returns a ValidationError, not an internal error, and that {\"tags\":[\"a\",\"b\"]} still dispatches."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/llm (sanitizer single-ctor test updates)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat (tool-arg list-element validation test)
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

# M1-363: LLM-boundary hardening (sanitizer + tool-arg types)

## Context

Two deep-review v6 findings at the provider's LLM trust boundary, grouped:

- **opus-47 `07-module-infochat-provider.md` F2** (medium,
  MAINTAINABILITY-RULES-DRIFT / durability) — `LlmOutputSanitizer` keeps a public
  no-arg constructor that turns auditing off, gated by an `auditWiringPresent`
  flag; the spec's "every match is audit-logged" durability is discipline-
  enforced, and the bypass is reachable from any production caller. **Verified
  2026-06-14:** public no-arg ctor at `LlmOutputSanitizer.java:103`
  (`auditWiringPresent=false`), `@Inject` ctor at 90, two `@Nullable` fields at
  73-74, emit gated at 291.
- **opus-48 `07-module-infochat-provider.md` F2** (low, SECURITY) — tool-arg list
  elements are not type-checked; `{"tags":[123]}` escapes as an
  `ArrayStoreException` (not caught by the dispatcher's three arms) instead of a
  `ValidationError`. **Verified 2026-06-14:** `ChatToolDispatcher.validateValue`
  (202-224) recurses on list elements but never checks element type;
  `SearchPostsTool` casts `(List<String>)` and calls
  `createArrayOf("TEXT", tags.toArray(new String[0]))`.

Both sit at engineering-rules §7's named system boundary (LLM tool-call args /
spec durability commitment); fixing them together keeps the boundary-validation
story in one diff.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- Removing the bypass makes the durability contract structural and lets §7a's
  "non-null is the package default" apply uniformly (the two collaborators are
  genuinely non-null on the CDI path).
