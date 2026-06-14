---
id: M1-363
title: "provider: remove the audit-bypassing LlmOutputSanitizer constructor; type-check tool-arg list elements at the LLM dispatch boundary"
status: done
created: 2026-06-14
last_updated: 2026-06-14
clarity_check:
  date: 2026-06-14
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 12
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
      files: 13
      added: 231
      removed: 74
escalations:
  - date: 2026-06-14
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — pre-implementation call-site sweep. Acceptance item 1 removes the
      public no-arg LlmOutputSanitizer() constructor; 6 existing test files
      construct via that ctor and must each switch to mocked collaborators /
      static helpers (LlmOutputSanitizerTest, DigestRendererTest,
      ClusterBlockRendererTest, RetryCommandHandlerTest, SummaryCommandHandlerTest,
      ChatAgentTest). With the 2 production files (LlmOutputSanitizer,
      ChatToolDispatcher) and the ChatToolDispatcherTest tags[123] pin
      (acceptance item 4), the irreducible file count is 9 vs files_budget 7.
  - date: 2026-06-14
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — round-1 mvn verify compile failure surfaced a missed 11th file.
      The round-1 sweep matched `new LlmOutputSanitizer()` (6 files) but missed
      TranslationPipelineTest's `static final class CountingSanitizer extends
      LlmOutputSanitizer`: its implicit no-arg super() no longer compiles and its
      sanitize() calls super.sanitize() (so it needs real collaborators, the
      SanitizerTestDoubles no-op pair like the others). True firm file count is
      now 11 (the 10 implemented + TranslationPipelineTest) vs files_budget 10.
revisions:
  - date: 2026-06-14
    reason: budget-breach refine (widen files_budget for the no-arg-ctor test fan-out)
    snapshot:
      status: escalated
      escalation_reason: budget-breach
      files_budget_at_snapshot: 7
      note: |
        Pre-implementation call-site sweep found 9 irreducible files (2 prod +
        6 no-arg-ctor test files + 1 new tags[123] pin) vs budget 7. Acceptance
        and out_of_scope unchanged; only files_budget widened 7 -> 10 (9 firm +
        1 headroom). The two concerns stay one diff at the §7 LLM boundary.
  - date: 2026-06-14
    reason: budget-breach refine (widen for the missed extends-subclass call site)
    snapshot:
      status: escalated
      escalation_reason: budget-breach
      files_budget_at_snapshot: 10
      note: |
        Round-1 mvn verify compile-failed on TranslationPipelineTest's
        `CountingSanitizer extends LlmOutputSanitizer` (an 11th file the round-1
        `new LlmOutputSanitizer()` sweep missed). Comprehensive re-sweep (new +
        extends, all modules) confirms it is the only remaining file. Acceptance
        and out_of_scope unchanged; files_budget widened 10 -> 12 (11 firm + 1
        headroom).
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-14
    verdict: CLEAN
    base: be64c431f152eac468d8b20c3aa6a179edb43739
    head: 3a07ced1ecbccc26398103536d7e52c7f49325b1
    verdict_file: docs/plan/m1/redteam/M1-363-2026-06-14.md
    out_of_model_count: 1
    note: |
      Pre-merge audit of the committed M1-363 branch (run between
      /m1-tick commit and /m1-tick merge). No threat-model gaps in the
      sanitizer single-ctor / audit-always change or the tool-arg
      element-type guard. One advisory out-of-model observation
      recorded in the verdict file; no remediation ticket required.
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
