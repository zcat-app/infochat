# Deep code review — consolidated summary

**Run directory:** deep-code-review/v2/mimo-v25pro/
**Date:** 2026-06-07
**Synthesizer:** review-synthesizer (mimo-v2.5-pro)

## Coverage

- **Reports consumed:** 7
  - architecture: yes
  - module-infochat-core: yes
  - module-infochat-ssrf: yes
  - module-infochat-llm-adapter: yes
  - module-infochat-messaging-adapter: yes
  - module-infochat-collector: yes
  - module-infochat-provider: yes

All targets succeeded. No failed or missing targets.

## Top priority

1. [critical] SECURITY — Stage1Pipeline unicode stripping uses visually confusable literal zero-width character constants instead of `\u` escapes
   - Sources: 06-module-infochat-collector.md#F1
   - Why first: A future maintainer editing this security-critical line cannot verify correctness by reading the source; the characters are invisible and visually indistinguishable from whitespace. Trivial fix (replace literals with `\u` escapes).

2. [high] SECURITY — SQL injection surface via `SET LOCAL infochat.actor_id` string concatenation in six admin command handlers
   - Sources: 07-module-infochat-provider.md#F1
   - Why second: Six handlers concatenate a UUID directly into SQL. While `actor.id` is a `UUID` (not user input), the pattern violates prepared-statement discipline and creates a latent injection surface if refactored to accept a `String` id. Note: PostgreSQL does not support parameterized `SET LOCAL` for session variables, so the fix requires a different approach (e.g., a helper that validates UUID format before concatenation).

3. [high] MAINTAINABILITY-RULES-DRIFT — SignalAdapter.start() throws IllegalStateException instead of MessagingException for most failure paths
   - Sources: 05-module-infochat-messaging-adapter.md#F6, 05-module-infochat-messaging-adapter.md#D1
   - Why third: The SPI declares `throws MessagingException`; the adapter throws unchecked `IllegalStateException` instead, bypassing the per-adapter catch in AdapterRegistry. Latent today (Signal not yet wired into startup path), will surface the moment it is.

4. [medium] SECURITY — SignalMessageCodec embeds raw attacker-influenceable line content in exception message (D37 violation)
   - Sources: 05-module-infochat-messaging-adapter.md#F12, 05-module-infochat-messaging-adapter.md#D4, 05-module-infochat-messaging-adapter.md#D11
   - Why fourth: Two locations in the Signal/SimpleX codecs embed user-controlled content (JSON-RPC line, WebSocket close reason) into exception messages. The current single call sites do not log the message, but any second call site that logs `e.getMessage()` would violate D37. The SimpleX codec uses a fixed message; the Signal codec does not.

5. [medium] MAINTAINABILITY-RULES-DRIFT — infochat-core violates its own "pure Java, no Quarkus" module contract
   - Sources: 01-architecture.md#F1
   - Why fifth: The module is documented as reusable outside Quarkus but contains CDI beans, `@ConfigProperty`, `@Startup`, and `@LoggingFilter`. This undermines the architectural guarantee and will mislead a future developer relying on the documented shape.

## Cross-cutting themes

### CT1. Defensive null-checks against NullAway-guaranteed non-null types

- **Pattern:** Multiple modules contain null-checks on parameters and return values that NullAway already guarantees non-null (the package default is non-null per `AnnotatedPackages`). This violates engineering rule 7 ("no defensive code for impossible scenarios").
- **Where it appears:** 03-module-infochat-ssrf.md#F1 (`SsrfGuardedHttpClient` constructor, 8 parameters), 03-module-infochat-ssrf.md#F2 (`resolveAndValidate` return check), 04-module-infochat-llm-adapter.md#F13 (`LlmRouter.forTask` on `task` parameter), 04-module-infochat-llm-adapter.md#F12 (`LlmRouter.Entry` `supportedLanguages` null-check dead after constructor normalization), 06-module-infochat-collector.md#F2 (`Stage2Worker` null-guards `originalBody`), 06-module-infochat-collector.md#F5 (`TagVocabulary.contains` null-checks non-null param), 07-module-infochat-provider.md#F2 (`InboundRouter` null guards on CDI-injected fields)
- **Suggested system-level fix:** Establish a project-wide convention: NullAway's `AnnotatedPackages` is the enforcement. Remove null-checks on non-null-annotated parameters/returns in internal code. For CDI-injected fields, use `@Vetoed` test constructors or package-private test-accessible setters rather than null-guard comments. A grep for `== null` in non-system-boundary code could be added to a lint script.

### CT2. Spec/design-vs-implementation drift on declared constants and contracts

- **Pattern:** Several modules declare values in code that differ from the spec or design document without comments explaining the override. This creates a two-truth problem where the spec and the code disagree and neither documents the discrepancy.
- **Where it appears:** 05-module-infochat-messaging-adapter.md#F2 (`minEditInterval` is `Duration.ZERO` vs design's 600ms), 05-module-infochat-messaging-adapter.md#F3 (`maxMessageBytes`/`maxSendsPerSecond` differ from design values), 05-module-infochat-messaging-adapter.md#F4 (SimpleX codec 4000 vs adapter 2000 byte cap), 05-module-infochat-messaging-adapter.md#D2 (SimpleX equal-jitter vs spec's full-jitter), 02-module-infochat-core.md#F1 (`NormalizedPost.sourceId` doc contradicts Fetcher/StreamSource SPIs and spec), 01-architecture.md#F4 (NOTIFY payload stringly-typed with no shared contract test)
- **Suggested system-level fix:** Add contract tests that assert the runtime values match the design document's declared constants (e.g., `assertThat(adapter.capabilities().minEditInterval()).isEqualTo(Duration.ofMillis(600))`). For the SPI doc contradictions, a single pass through the affected javadocs to align with the spec would eliminate the ambiguity. For the NOTIFY payload, add round-trip integration tests that exercise the full produce-parse cycle.

### CT3. Missing build-time enforcement of documented invariants

- **Pattern:** The project documents several invariants as "enforced by the build" or "verified in CI" but no build plugin or test actually enforces them.
- **Where it appears:** 01-architecture.md#F2 (Module DAG enforcement absent from build — no `maven-enforcer-plugin`), 05-module-infochat-messaging-adapter.md#F2 (`AdapterCapabilityContractTest` does not assert `minEditInterval` values), 01-architecture.md#F4 (NOTIFY payload shape has no round-trip test)
- **Suggested system-level fix:** Add `maven-enforcer-plugin` with `bannedDependencies` encoding the DAG rules from `09-reference.md`. Extend `AdapterCapabilityContractTest` to assert all design-documented constant values. Add NOTIFY round-trip integration tests.

### CT4. Inconsistent exception contracts across sibling adapters

- **Pattern:** The SimpleX adapter consistently wraps failures in `MessagingException` with `FailureCategory`; the Signal adapter uses bare `IllegalStateException` for the same failure classes. Similarly, the SimpleX codec uses fixed error messages; the Signal codec embeds raw content. The SimpleX adapter uses SLF4J; the Signal adapter uses JBoss Logger.
- **Where it appears:** 05-module-infochat-messaging-adapter.md#F6/D1 (`SignalAdapter.start()` exception type), 05-module-infochat-messaging-adapter.md#D4 (Signal codec embeds raw line), 05-module-infochat-messaging-adapter.md#D8 (logger facade divergence), 05-module-infochat-messaging-adapter.md#D9 (platform thread vs virtual thread)
- **Suggested system-level fix:** Align the Signal adapter's error handling, logging, and threading model to match the SimpleX adapter's established patterns. The SimpleX adapter's patterns (MessagingException wrapping, fixed error messages, SLF4J, virtual threads) are the reference implementation; the Signal adapter should follow the same conventions.

## Findings by category

### SECURITY (6)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| critical | Stage1Pipeline unicode stripping uses visually confusable literal zero-width chars | `Stage1Pipeline.java:304` | 06-module-infochat-collector.md#F1 |
| high | SQL injection surface via `SET LOCAL infochat.actor_id` string concatenation | `BanCommandHandler.java`, `GrantAdminCommandHandler.java`, `RevokeAdminCommandHandler.java`, `VouchCommandHandler.java`, `PromoteCommandHandler.java`, `QuarantineCommandHandler.java` | 07-module-infochat-provider.md#F1 |
| medium | SignalMessageCodec/SimpleXWebSocketClient embed attacker-influenceable content in exception messages (D37) | `SignalMessageCodec.java:98`, `SimpleXWebSocketClient.java:295` | 05-module-infochat-messaging-adapter.md#D4, 05-module-infochat-messaging-adapter.md#D11 |
| medium | Stage2Worker null-guards originalBody after substitution (defensive, impossible scenario) | `Stage2Worker.java:199` | 06-module-infochat-collector.md#F2 |
| low | ForwardingResolver dereferences BUILTIN volatile without local null guard | `PinnedDnsResolver.java:174-191` | 03-module-infochat-ssrf.md#F3 |
| low | NostrStreamSource.Registrar creates SsrfGuardedHttpClient directly, bypasses CDI config | `NostrStreamSource.Registrar.java:303-306` | 06-module-infochat-collector.md#F6 |
| low | assertAllTasksResolve always passes "en", never exercises language-aware branch | `LlmRouter.java` | 04-module-infochat-llm-adapter.md#F4 |
| low | LlmRouter.MicroProfileConfigReader normalizes "null" string to empty (silent provider-name drop) | `LlmRouter.java:378-380` | 04-module-infochat-llm-adapter.md#F14 |

### PERFORMANCE (7)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| medium | Stage1Pipeline.findAllMatchesUnderWatchdog per-character System.nanoTime() in watchdog | `Stage1Pipeline.java` (InterruptibleCharSequence.charAt) | 06-module-infochat-collector.md#F3 |
| low | HostInterfaceSet.enumerate() per-call JNI NetworkInterface.getNetworkInterfaces() | `IpBlocklist.java:107`, `HostInterfaceSet.java` | 03-module-infochat-ssrf.md#F4 |
| low | BoundedStringSubscriber does not release accumulated ByteBuffer list on cancel | `LlmHttpSupport.java` (BoundedStringSubscriber.onNext) | 04-module-infochat-llm-adapter.md#F2 |
| low | EmbeddingResult defensive copy on every vector() call (triple allocation per construction) | `EmbeddingResult.java:33-35` | 04-module-infochat-llm-adapter.md#F9, 04-module-infochat-llm-adapter.md#F18 |
| low | LlmRouter.buildFromCdi iterates Instance without closing (CDI @Dependent leak) | `LlmRouter.java:277-289` | 04-module-infochat-llm-adapter.md#F17 |
| low | SimpleXSubprocess Thread.sleep pins carrier thread (vs Signal's ScheduledExecutorService) | `SimpleXSubprocess.java` (sleepForBackoff) | 05-module-infochat-messaging-adapter.md#D6 |
| low | LinkingJob.findSemanticCandidates full table self-join without pre-filter | `LinkingJob.java:261-276` | 06-module-infochat-collector.md#F7 |
| low | LLM rate cap ConcurrentHashMap grows unbounded for distinct userIds | `InboundRouter.java` (llmCallTimestamps) | 07-module-infochat-provider.md#F4 |

### SIMPLIFICATION (9)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| low | MessageHandle is plain record, not sealed interface per design | `MessageHandle.java` | 05-module-infochat-messaging-adapter.md#F1 |
| low | InMemoryMessageHandle is dead public surface | `InMemoryMessageHandle.java` | 05-module-infochat-messaging-adapter.md#D5 |
| low | SimpleXMessageCodec 4000-byte vs SimpleXAdapter 2000-byte cap undocumented disagreement | `SimpleXMessageCodec.java`, `SimpleXAdapter.java` | 05-module-infochat-messaging-adapter.md#F4 |
| low | SignalIdentity/SimpleXIdentity.resolve() throw UOE (dead stubs) | `SignalIdentity.java`, `SimpleXIdentity.java` | 05-module-infochat-messaging-adapter.md#F5 |
| low | SimpleXSubprocess drain discards all bytes, no metrics | `SimpleXSubprocess.java` (drainStream) | 05-module-infochat-messaging-adapter.md#D7 |
| low | Duplicated test StubConfig/CapturingHandler/StubProvider across 3 LLM test files | `LlmRouterTest.java`, `LlmRouterUnknownDefaultTest.java`, `AnthropicProviderTest.java` | 04-module-infochat-llm-adapter.md#F3 |
| low | LlmRouter.Entry supportedLanguages null-check dead after constructor normalization | `LlmRouter.java:167`, `LlmRouter.java:337-339` | 04-module-infochat-llm-adapter.md#F12 |
| low | PER_TASK_BASE_URL_KEYS uses Map.of() (no guaranteed iteration order) | `LlmRouterStartupGuard.java:116-123` | 04-module-infochat-llm-adapter.md#F16 |
| low | Entry supportedLanguages "empty means any" semantics unreachable in production | `LlmRouter.Entry` javadoc | 04-module-infochat-llm-adapter.md#F21 |

### MAINTAINABILITY-RULES-DRIFT (20)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| high | SignalAdapter.start() throws IllegalStateException not MessagingException | `SignalAdapter.java:212,218,233` | 05-module-infochat-messaging-adapter.md#F6, 05-module-infochat-messaging-adapter.md#D1 |
| medium | infochat-core violates "pure Java, no Quarkus" module contract | `ThrottledAdminNotifier.java`, `AuditLogWriter.java`, `DefaultRedactionHook.java`, `AbstractInstanceLockGuard.java`, `Redactor.java` | 01-architecture.md#F1 |
| medium | Module DAG enforcement absent from build (no maven-enforcer-plugin) | `pom.xml` (parent) | 01-architecture.md#F2 |
| medium | NormalizedPost.sourceId doc contradicts Fetcher/StreamSource SPIs and spec UID derivation | `NormalizedPost.java:17-21` | 02-module-infochat-core.md#F1 |
| medium | SsrfGuardedHttpClient constructor null-checks NullAway-guaranteed non-null params | `SsrfGuardedHttpClient.java:197-226` | 03-module-infochat-ssrf.md#F1 |
| medium | OpenAiCompatibleProvider.configFor throws UOE for 5/6 ModelTask values (maintenance trap) | `OpenAiCompatibleProvider.java:157-165` | 04-module-infochat-llm-adapter.md#F1, 04-module-infochat-llm-adapter.md#F8 |
| medium | No module-level test for OpenAiCompatibleProvider (chat completions) | (missing test file) | 04-module-infochat-llm-adapter.md#F15 |
| medium | CapabilityFlags minEditInterval is Duration.ZERO contradicting design's 600ms | `SimpleXAdapter.java`, `SignalAdapter.java` | 05-module-infochat-messaging-adapter.md#F2 |
| medium | InboundRouter null guards on CDI-injected fields bypass NullAway's purpose | `InboundRouter.java` | 07-module-infochat-provider.md#F2 |
| low | TranslationProvider misplaced in infochat-messaging-adapter | `TranslationProvider.java` | 01-architecture.md#F3 |
| low | NOTIFY payload shape stringly-typed with no shared contract | `ReadyPromoter.java`, `PriceSnapshotStore.java`, `QuarantineNotifyEmitter.java`, `NewPostListener.java`, `QuarantineReviewListener.java` | 01-architecture.md#F4 |
| low | resolveAndValidate null-checks non-null return from resolverSeam.apply() | `SsrfGuardedHttpClient.java:471` | 03-module-infochat-ssrf.md#F2 |
| low | Test sources exempt from NullAway/Error Prone | `infochat-ssrf/pom.xml:50-60` | 03-module-infochat-ssrf.md#F5 |
| low | Config-reading divergence between OpenAI and Anthropic providers | `OpenAiCompatibleProvider.java`, `AnthropicProvider.java` | 04-module-infochat-llm-adapter.md#F7 |
| low | LlmRouter.forTask null-check on task parameter (borderline boundary check) | `LlmRouter.java:141` | 04-module-infochat-llm-adapter.md#F13 |
| low | Different max-tokens handling between providers (OpenAI ignores, Anthropic requires) | `OpenAiCompatibleProvider.java`, `AnthropicProvider.java` | 04-module-infochat-llm-adapter.md#F20 |
| low | maxMessageBytes/maxSendsPerSecond differ from design values | `SimpleXAdapter.java`, `SignalAdapter.java` | 05-module-infochat-messaging-adapter.md#F3 |
| low | SimpleXSubprocess uses equal-jitter; spec mandates full-jitter | `SimpleXSubprocess.java` (backoffDelay) | 05-module-infochat-messaging-adapter.md#D2 |
| low | SignalAdapter JBoss Logger vs SimpleXAdapter SLF4J inconsistency | `SignalAdapter.java`, `SimpleXAdapter.java` | 05-module-infochat-messaging-adapter.md#D8 |
| low | SignalJsonRpcClient uses platform thread, not virtual thread | `SignalJsonRpcClient.java:183` | 05-module-infochat-messaging-adapter.md#D9 |
| low | Stage1Pipeline mutable static sanitizer field (test seam anti-pattern) | `Stage1Pipeline.java:229` | 06-module-infochat-collector.md#F4 |
| low | TagVocabulary.contains null-checks non-null param | `TagVocabulary.java:105` | 06-module-infochat-collector.md#F5 |
| low | ReEvaluationJob missing transaction boundary on multi-statement read | `ReEvaluationJob.java:243-265` | 06-module-infochat-collector.md#F8 |
| low | @SuppressWarnings("NullAway.Init") on reactive-streams subscription field | `LlmHttpSupport.java:154` | 04-module-infochat-llm-adapter.md#F10 |
| low | AnthropicProvider.parseContentText only reads content[0] | `AnthropicProvider.java:178-197` | 04-module-infochat-llm-adapter.md#F11 |

## Synthesizer notes

- The provider report's Finding 3 (`PromoteCommandHandler` admin gate TOCTOU) was withdrawn by the reviewer on re-examination within the same report. It is excluded from all tables above.
- The provider report's Finding 4 (LLM rate cap) notes the pattern is acceptable at v1 scale. Consolidated under PERFORMANCE as low.
- The llm-adapter report lists 17 numbered items, but several are documented trade-offs rather than findings (items 5, 6, 10 are explicitly "no finding" or "documented trade-off"). Only the actionable items are included in the tables.
- The llm-adapter report's Finding 1 and Finding 8 describe the same issue (OpenAiCompatibleProvider.configFor UOE for unwired tasks). Consolidated into one row.
- The messaging-adapter report's Finding 6/D1 (SignalAdapter.start exception contract) was escalated from the headline summary's implied severity to the expanded detail's explicit "high" in the D1 section. The summary uses the higher severity.
- Two findings across different modules touch the same conceptual surface (defense-in-depth inconsistency on SimpleX codec validation: messaging-adapter F4 and F8/D3), but they have different root causes (byte-cap disagreement vs chatItemId decode-time validation gap). Listed as separate findings.
- The architecture report observes that the module DAG is correct today (no actual forbidden dependencies exist); the finding is about the absence of build-time enforcement, not a current violation. This is accurately reflected in the MAINTAINABILITY-RULES-DRIFT table.
