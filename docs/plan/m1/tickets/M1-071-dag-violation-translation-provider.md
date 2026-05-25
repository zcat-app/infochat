---
id: M1-071
title: Fix DAG violation — move LlmTranslationProvider out of llm-adapter
status: done
created: 2026-05-25
last_updated: 2026-05-25
clarity_check:
  date: 2026-05-25
  verdict: PASS
  warnings: []
  blockers: []
revisions:
  - date: 2026-05-25
    reason: clarity-fail refine
    snapshot:
      spec_refs:
        - "docs/spec/architecture.md §Module DAG"
        - "docs/design/09-reference.md §Sibling modules MUST NOT depend on each other"
      acceptance_item_3: "LlmRouter.validateRegistry() @PostConstruct method is removed (dead defensive code, §7). Verify: code inspection + LlmRouterTest green"
blocked_by: []
files_budget: 10
files_scope:
  - infochat-llm-adapter/pom.xml
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/translation/LlmTranslationProvider.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/translation/LlmTranslationProvider.java
  - infochat-provider/pom.xml
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/translation/LlmTranslationProviderTest.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmRouterTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/LlmTranslationProviderTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/TranslationPipelineTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/TranslationPipelineIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
source: deep-code-review full-2026-05-25-1901 (04-module-infochat-llm-adapter.md#F1, 01-architecture.md#F3)
out_of_scope:
  - any TranslationProvider interface move — the interface stays in infochat-messaging-adapter; only the implementation moves
  - any TranslationPipeline logic change — only the bean's package location changes
  - any LlmRouter functional change — only the @PostConstruct removal and case-normalization fix (F3, F4 from same report) ride along since they're in the same file
  - any new translation feature or language support
acceptance:
  - "infochat-llm-adapter/pom.xml no longer declares any dependency on infochat-messaging-adapter (neither compile nor provided). Verify: grep for infochat-messaging-adapter in infochat-llm-adapter/pom.xml returns empty"
  - "LlmTranslationProvider.java exists under infochat-provider (e.g. app.zcat.infochat.provider.translation package) and implements TranslationProvider. CDI discovers it correctly. Verify: TranslationPipelineIT.translationRoundTrip still passes"
  - "LlmRouter.validateRegistry() @PostConstruct method is removed (dead defensive code, §7). Verify: grep -r validateRegistry LlmRouter.java returns empty + LlmRouterTest green"
  - "LlmRouter.forTask normalizes scopeLanguage to lowercase before set lookup. Verify: LlmRouterTest.caseInsensitiveLanguageLookup passes"
  - "mvn clean verify (full suite) is green"
test_plan:
  adds:
    - LlmRouterTest.caseInsensitiveLanguageLookup (new)
  modifies:
    - LlmTranslationProviderTest.java (moved to provider module, package update)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Architectural principles
  - docs/design/09-reference.md §9.1 Module dependency DAG
decision_refs: []
reviews:
  - round: 1
    date: 2026-05-25
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 74
      removed: 48
  - round: 2
    date: 2026-05-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 85
      removed: 48
escalations:
  - date: 2026-05-25
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      SPEC-REFS-VALID: FAIL
        - docs/spec/architecture.md §Module DAG: ANCHOR-NOT-FOUND. No heading containing "module dag".
        - docs/design/09-reference.md §Sibling modules MUST NOT depend on each other: ANCHOR-NOT-FOUND. The rule is body text inside §9.1 Module dependency DAG.
---

## Context

`infochat-llm-adapter` has a `provided`-scope dependency on `infochat-messaging-adapter` because `LlmTranslationProvider` implements `TranslationProvider` (which lives in the messaging-adapter). This violates the documented "sibling modules MUST NOT depend on each other" rule.

Additionally, `LlmRouter` has a redundant `@PostConstruct validateRegistry()` that re-checks an invariant the constructor already enforces (§7 violation), and a latent case-sensitivity bug where `scopeLanguage` is not normalized to lowercase before lookup against normalized stored values.

## Fix approach

1. Move `LlmTranslationProvider` from `infochat-llm-adapter` to `infochat-provider` (which already depends on both siblings). Remove the `infochat-messaging-adapter` dependency from `infochat-llm-adapter/pom.xml`.
2. Delete `LlmRouter.validateRegistry()` (dead code — constructor + `List.copyOf` already guarantee non-empty).
3. Normalize `scopeLanguage` to `toLowerCase(Locale.ROOT)` in `LlmRouter.forTask` before the set lookup.

## Round 1 rework

1. Add two missing paths to `files_scope`: (a) `infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmRouterTest.java` and (b) `infochat-provider/src/test/java/app/zcat/infochat/provider/translation/LlmTranslationProviderTest.java`. Both are required by the ticket's own acceptance criteria and test_plan but were omitted from `files_scope`.
