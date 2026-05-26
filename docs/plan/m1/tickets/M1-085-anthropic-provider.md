---
id: M1-085
title: "AnthropicProvider — native Messages API with prompt caching"
status: done
created: 2026-05-26
last_updated: 2026-05-26
clarity_check:
  date: 2026-05-26
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 1
    date: 2026-05-26
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 646
      removed: 8
  - round: 2
    date: 2026-05-26
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 651
      removed: 8
blocked_by: []
files_budget: 8
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/AnthropicProviderTest.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmRouterTest.java
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - infochat-core/** — no core module changes
  - infochat-collector/src/main/java/** — no collector code changes
  - infochat-provider/src/main/java/** — no provider code changes
  - infochat-messaging-adapter/** — no adapter changes
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java — not modified
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/LlmProvider.java — SPI is frozen
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/EmbeddingProvider.java — no embedding work; AnthropicEmbeddingProvider is a future ticket if needed
  - any Flyway migration
  - any pre-existing test file not listed in files_scope
acceptance:
  - "AnthropicProvider is an @ApplicationScoped CDI bean implementing LlmProvider with PROVIDER_NAME = \"anthropic\""
  - "generate() POSTs to the Anthropic Messages API with the correct wire format: top-level `system` array containing a text content block (not a messages entry with role system), `messages` array with a single user-role entry, `model` string, and `max_tokens` integer"
  - "The system prompt content block carries `\"cache_control\": {\"type\": \"ephemeral\"}` so Anthropic's server-side prompt cache applies to the stable system prefix"
  - "Auth headers use `x-anthropic-version` (stable API version string) and `anthropic-api-key` (raw key, not Bearer token); the api-key header is omitted when the config value is empty"
  - "Response parsing extracts `content[0].text` from the Anthropic Messages API response body and returns it as LlmResponse.text()"
  - "generate() succeeds for any ModelTask value when the per-task config properties are set: `infochat.llm.<taskKeySegment>.base-url`, `.api-key`, `.model`, `.timeout-ms`, and Anthropic-required `.max-tokens`"
  - "IOException, InterruptedException, and non-2xx HTTP status throw LlmCallFailedException; Anthropic error responses (type=error) include the error message in the exception for diagnostics"
  - "LlmRouter.providerName() recognizes AnthropicProvider via instanceof and returns PROVIDER_NAME (\"anthropic\"), parallel to the existing OpenAiCompatibleProvider check"
  - "LlmRouterStartupGuard.PER_TASK_BASE_URL_KEYS is expanded with entries for all six ModelTask values, each mapping to its `infochat.llm.<taskKeySegment>.base-url` property key"
  - "Language config `infochat.llm.anthropic.languages=en,cs` is present under the `%remote-llm` profile in both collector and provider application.properties"
  - "Per-task `%remote-llm` profile defaults for SUMMARIZER and CHAT_AGENT set `provider=anthropic` with the Anthropic API base-url and profile-appropriate max-tokens values"
  - AnthropicProviderTest.generatePostsCorrectWireFormat passes
  - AnthropicProviderTest.generateIncludesCacheControlOnSystemPrompt passes
  - AnthropicProviderTest.generateSendsAuthHeaders passes
  - AnthropicProviderTest.generateOmitsApiKeyHeaderWhenEmpty passes
  - AnthropicProviderTest.generateParsesContentResponse passes
  - AnthropicProviderTest.generateThrowsOnNon2xx passes
  - AnthropicProviderTest.generateThrowsOnIoError passes
  - LlmRouterTest.perTaskOverrideAnthropicRoutesToAnthropicProvider passes
  - LlmRouterTest.anthropicProviderWithCzechLanguageRoutesForCzechSummarizer passes
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/AnthropicProviderTest.java
  preserves:
    - all tests currently green on main
    - LlmRouterTest existing test methods pass unchanged
spec_refs:
  - docs/spec/llm.md §SPI shape
  - docs/spec/llm.md §Per-task routing rules
  - docs/spec/llm.md §Failure handling
  - docs/spec/llm.md §Hardware profile contract
decision_refs:
  - D32
  - D27
---

# M1-085: AnthropicProvider — native Messages API with prompt caching

## Context

The LlmProvider SPI (M1-007b) and LlmRouter (M1-033) are on main with one
concrete provider: OpenAiCompatibleProvider (covers Ollama, llama.cpp,
OpenAI, OpenRouter, NanoGPT). This ticket adds the second provider —
AnthropicProvider — using the native Anthropic Messages API rather than the
OpenAI-compatible endpoint. The spec motivation is prompt caching
(`docs/spec/llm.md` §Why a thin SPI — "cache-friendly call shapes"): the
Anthropic `cache_control` block lets the caller mark the system prompt as
cacheable, saving ~90% on repeated system prompts for the summarizer and
chat agent.

## Acceptance

1. **AnthropicProvider CDI bean.** `@ApplicationScoped`, implements
   `LlmProvider`, constant `PROVIDER_NAME = "anthropic"`.

2. **Anthropic Messages API wire format.** `generate()` POSTs a request
   body with: a top-level `system` array containing a text content block
   (NOT a messages entry with `role: "system"` — Anthropic's format
   differs from OpenAI here), a `messages` array with a single user-role
   entry, a `model` string, and a `max_tokens` integer.

3. **Prompt caching.** The system prompt content block carries
   `"cache_control": {"type": "ephemeral"}` so Anthropic's server-side
   prompt cache applies to the stable system prefix.

4. **Auth headers.** `x-anthropic-version` (stable API version string)
   and `anthropic-api-key` (raw key value, not Bearer token). The api-key
   header is omitted when the config value is empty (matching
   OpenAiCompatibleProvider's empty-key behavior).

5. **Response parsing.** Extracts `content[0].text` from the Messages API
   response body and returns it as `LlmResponse.text()`.

6. **Per-task config.** `generate()` succeeds for any `ModelTask` when
   the per-task config properties are set:
   `infochat.llm.<taskKeySegment>.base-url`, `.api-key`, `.model`,
   `.timeout-ms`, and Anthropic-required `.max-tokens`.

7. **Error handling.** IOException, InterruptedException, and non-2xx HTTP
   status throw `LlmCallFailedException`. Anthropic error responses
   (`"type": "error"`) include the error message in the exception for
   diagnostics.

8. **LlmRouter integration.** `providerName()` recognizes
   `AnthropicProvider` via `instanceof` and returns `PROVIDER_NAME`
   (`"anthropic"`), parallel to the existing `OpenAiCompatibleProvider`
   check.

9. **LlmRouterStartupGuard expansion.** `PER_TASK_BASE_URL_KEYS` is
   expanded with entries for all six `ModelTask` values, each mapping to
   its `infochat.llm.<taskKeySegment>.base-url` property key.

10. **Language config.** `infochat.llm.anthropic.languages=en,cs` is
    present under the `%remote-llm` profile in both collector and provider
    `application.properties`.

11. **Profile defaults.** Per-task `%remote-llm` profile defaults for
    SUMMARIZER and CHAT_AGENT set `provider=anthropic` with the Anthropic
    API base-url and profile-appropriate `max-tokens` values.

12. **Tests.** Seven `AnthropicProviderTest` methods (wire format, cache
    control, auth headers, empty-key omission, response parsing, non-2xx
    error, IO error) and two `LlmRouterTest` additions (explicit Anthropic
    routing, Czech-language summarizer routing) pass. Full `mvn verify`
    green.

## Out-of-scope

- **OpenAiCompatibleProvider** — not modified. It stays as-is; T3-D adds a
  parallel provider, not a replacement.
- **LlmProvider SPI** — frozen. No method additions, no new types on the
  interface.
- **EmbeddingProvider / AnthropicEmbeddingProvider** — the Anthropic
  embeddings API is a future ticket if operators need it. T3-D is
  LlmProvider only.
- **Collector and provider Java code** — no changes outside
  `infochat-llm-adapter`. The adapter module is the boundary.
- **Flyway migrations** — no schema changes.
- **Pre-existing tests not in files_scope** — no modifications to existing
  test files beyond LlmRouterTest additions.

## Notes

- **Design reference:** `docs/design/05-llm-and-embeddings.md` §5.3
  (AnthropicProvider design, property keys), §5.7 (profile defaults
  table), §5.8 (failure handling per task).
- **Design-vs-code drift: `Capability` enum.** The design doc §5.3 shows
  a `Capability` enum (`JSON_MODE`, `TOOL_CALLS`, `PROMPT_CACHING`,
  `SUPPORTS_LANGUAGE_CS`, etc.) on `LlmProvider`. This does NOT exist in
  code — the SPI is frozen. Language capabilities are config-driven via
  `infochat.llm.<providerName>.languages` (comma-separated ISO 639-1
  codes), read by `LlmRouter.supportedLanguagesFor()` at CDI build time,
  defaulting to `Set.of("en")`. T3-D uses this mechanism.
- **Design-vs-code drift: `MessageHandle` sealed interface.** Irrelevant
  to T3-D (that's the messaging adapter, not the LLM adapter).
- **`max_tokens` is REQUIRED by Anthropic.** Unlike the OpenAI API where
  `max_tokens` is optional, the Anthropic Messages API requires it. The
  per-task property `infochat.llm.<taskKeySegment>.max-tokens` provides
  this. OpenAiCompatibleProvider can ignore the property.
- **Dynamic config vs static injection.** OpenAiCompatibleProvider uses
  `@ConfigProperty` injection (one field per property per task). With only
  SECURITY_JUDGE wired, that's 4 fields. For 6 tasks × 5 properties =
  30 fields, dynamic config lookup via `ConfigProvider.getConfig()` is
  cleaner. Either approach satisfies the acceptance criteria.
- **Anthropic API version header.** The `x-anthropic-version` value should
  be verified against current Anthropic API docs at implementation time.
  Use the latest stable version (e.g. `2023-06-01` or newer).
- **Anthropic error response format.**
  `{"type":"error","error":{"type":"...","message":"..."}}`. Parse the
  inner `error.message` for the exception message rather than dumping raw
  body.
- **AnthropicProviderTest pattern.** Use JDK's `com.sun.net.httpserver`
  (or Quarkus WireMock if already a test dependency) to stand up a local
  mock HTTP server. The test configures AnthropicProvider with the mock's
  base-url, sends a generate() call, and asserts against the captured
  request and canned response. This matches how LlmRouterTest avoids
  Quarkus boot — plain JUnit5 with hand-rolled config.
- **LlmRouterStartupGuard: TAGGER gap.** The guard currently only maps
  SECURITY_JUDGE despite TAGGER already having a base-url property on
  main. Expanding to all six tasks covers this pre-existing gap as a
  side effect. If the reviewer flags TAGGER as scope drift, the
  implementer can narrow to the five tasks whose base-url properties
  T3-D introduces or modifies.

## Round 1 rework

1. Add `@NonNull` to the `Config config` parameter on the public `@Inject` constructor of `AnthropicProvider` (PARAMETER-CONTRACT-CHECK fail).
