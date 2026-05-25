---
id: M1-078
title: JSpecify @NonNull/@Nullable annotation sweep across all modules
status: pending
created: 2026-05-25
last_updated: 2026-05-25
blocked_by: []
files_budget: 25
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/ingest/Fetcher.java
  - infochat-core/src/main/java/app/zcat/infochat/core/ingest/NormalizedPost.java
  - infochat-core/src/main/java/app/zcat/infochat/core/ingest/StreamSource.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditLogWriter.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/RedactionHook.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/IpBlocklist.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/PinnedDnsResolver.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/UrlRedactor.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/LlmProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/EmbeddingProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleEmbeddingProvider.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/Identity.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/InboundMessage.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/OutboundMessage.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessageHandle.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/ScopeRef.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/ProgressNotifier.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/TranslationProvider.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/CapabilityFlags.java
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
source: deep-code-review full-2026-05-25-1901 (CT1 cross-cutting theme; 02-module-infochat-core.md#F2, 03-module-infochat-ssrf.md#F3, 04-module-infochat-llm-adapter.md#F2, 05-module-infochat-messaging-adapter.md#F1, 05-module-infochat-messaging-adapter.md#F3, 05-module-infochat-messaging-adapter.md#F4)
out_of_scope:
  - any behavioral code change — this ticket adds annotations only (no logic, no new validation)
  - any infochat-collector or infochat-provider annotation work — those modules have many internal methods; this ticket covers the shared SPI surface only (core, ssrf, llm-adapter, messaging-adapter)
  - changing ProgressNotifier.publish(String scope) to ProgressNotifier.publish(ScopeRef scope) — that is a functional API change, not an annotation
  - any test file annotation — §7a applies to public/protected production methods only
acceptance:
  - "scripts/lint-contracts.py reports zero violations across infochat-core, infochat-ssrf, infochat-llm-adapter, and infochat-messaging-adapter src/main/java. Verify: python3 scripts/lint-contracts.py --modules infochat-core,infochat-ssrf,infochat-llm-adapter,infochat-messaging-adapter exits 0"
  - "Every public/protected method with reference-type parameters in files_scope carries @NonNull or @Nullable from org.jspecify.annotations. Verify: lint-contracts.py zero violations"
  - "Identity.displayName is annotated @Nullable (spec says 'optional display name'). Verify: code inspection"
  - "NormalizedPost record components: title @Nullable, url @Nullable, publishedAt @Nullable; all others @NonNull. Verify: code inspection"
  - "No import from org.jetbrains.annotations anywhere in files_scope. Verify: grep returns empty"
  - "mvn clean verify (full suite from root) is green"
test_plan:
  adds: []
  modifies: []
  preserves:
    - all tests currently green on main (annotation-only changes are source-compatible)
spec_refs:
  - docs/process/engineering-rules-verbatim.md §7a
decision_refs: []
---

## Context

Cross-cutting theme CT1 from the deep code review: every module's public SPI methods lack `@NonNull`/`@Nullable` annotations, violating engineering rule §7a. The violation spans all four shared modules (core, ssrf, llm-adapter, messaging-adapter) and affects every SPI boundary type (records, interfaces, constructors, helpers).

This is a mechanical, annotation-only sweep. No behavioral changes. The JSpecify dependency is already in the parent POM at `provided` scope.

## Fix approach

1. Run `scripts/lint-contracts.py` to enumerate all violations.
2. For each public/protected method parameter: add `@NonNull` (default) or `@Nullable` (where the contract permits null — e.g. `NormalizedPost.title`, `Identity.displayName`, `LlmRouter.forTask scopeLanguage`).
3. Re-run lint script to confirm zero violations.
4. Full `mvn clean verify` to confirm source compatibility.

## Design decisions embedded in annotations

- `Identity.displayName` → `@Nullable` (spec says "optional display name"; SimpleX contacts may not have one)
- `NormalizedPost.title` → `@Nullable` (javadoc says "nullable")
- `NormalizedPost.url` → `@Nullable` (javadoc says "nullable")
- `NormalizedPost.publishedAt` → `@Nullable` (javadoc says "nullable")
- `LlmRouter.forTask(task, scopeLanguage)` → task `@NonNull`, scopeLanguage `@Nullable`
- `UrlRedactor.redact(url)` → `@Nullable` (implementation explicitly handles null)
- All other parameters → `@NonNull`
