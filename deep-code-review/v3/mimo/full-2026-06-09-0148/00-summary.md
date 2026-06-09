# Deep code review — consolidated summary

**Run directory:** /home/ubuntu5/Projects/quarkus-projects/infochat/deep-code-review/v3/mimo/full-2026-06-09-0148
**Date:** 2026-06-09 02:00
**Synthesizer:** review-synthesizer (mimo)

## Coverage

- **Reports consumed:** 7
  - architecture: yes
  - module-infochat-core: yes
  - module-infochat-ssrf: yes
  - module-infochat-llm-adapter: yes
  - module-infochat-messaging-adapter: yes
  - module-infochat-collector: yes
  - module-infochat-provider: yes

All targets succeeded. No failed or missing reports.

## Top priority

1. [high] SECURITY — Signal `canonicalizeAci` accepts non-UUID strings as valid contact ids from the wire, allowing arbitrary data to enter the identity system
   - Sources: 05-module-infochat-messaging-adapter.md#F1
   - Why first: This is the only security-severity finding across all reports. A non-UUID sourceUuid becomes a permanent contact id in the `(adapter, contact_id)` join key, potentially corrupting identity, logs, and audit storage. The SimpleX adapter validates its contact ids at the boundary; the Signal adapter does not.

2. [high] PERFORMANCE — BanCheck runs a separate DB query on every inbound message after the snapshot SELECT already fetched the user row
   - Sources: 07-module-infochat-provider.md#F1, 07-module-infochat-provider.md#F4
   - Why second: Every non-banned inbound message (the vast majority) pays an unnecessary database round-trip. The fix is a one-column addition to an existing query. The class's own Javadoc claims "exactly one users-row SELECT per dispatch" but the code issues two.

3. [high] MAINTAINABILITY-RULES-DRIFT — ChatAgent sanitizer-audit failure causes user-visible inconsistency: an audit-logging infrastructure failure (SQLException from emitAuditRows) surfaces as a misleading "chat unavailable" error to the user
   - Sources: 07-module-infochat-provider.md#F2
   - Why third: The sanitizer's primary job is stripping admin commands from LLM output. An audit INSERT failure should degrade operator observability, not break the user's chat. The recommended fix (log-and-continue instead of throw) is small and eliminates a class of user-visible failures rooted in non-critical infrastructure.

4. [medium] PERFORMANCE — SignalAdapter reconnect and both dispatch executors (Signal, SimpleX) use platform threads instead of virtual threads, inconsistent with the project's JDK 25 virtual-thread-first policy
   - Sources: 05-module-infochat-messaging-adapter.md#F2, 05-module-infochat-messaging-adapter.md#F3
   - Why fourth: Three locations across both adapters use `Thread.ofPlatform()` or `new Thread()` for blocking I/O patterns (reconnect with up to 15s endpoint probe; dispatch threads calling handler callbacks that block on DB and LLM). Virtual threads are the correct carrier for these patterns on JDK 25. The fixes are one-line changes each.

5. [medium] SIMPLIFICATION — `LlmRouterStartupGuard.isLoopback` performs a blocking DNS resolution via `InetAddress.getByName` at startup; a static set of loopback literals would suffice and avoid blocking on DNS during boot
   - Sources: 04-module-infochat-llm-adapter.md#F1
   - Why fifth: If DNS is slow or the configured remote host is unreachable, startup blocks for the DNS timeout (~30s) before falling back. The guard's purpose is catching the common operator mistake (cloud URL vs localhost), which is a pure string comparison. The per-call SSRF guard handles the edge cases DNS resolution would catch.

## Cross-cutting themes

### CT1. Platform threads where virtual threads belong

- **Pattern:** The project targets JDK 25 with a virtual-thread-first, blocking-style policy (CLAUDE.md Stack section). Three locations in the messaging-adapter module use platform threads for blocking I/O patterns: the Signal reconnect thread, the Signal dispatch executor, and the SimpleX dispatch executor. The SimpleX reconnect path already uses virtual threads correctly, making the inconsistency visible only when reading both adapters together.
- **Where it appears:** 05-module-infochat-messaging-adapter.md#F2, 05-module-infochat-messaging-adapter.md#F3
- **Suggested system-level fix:** A project-wide audit of `Thread.ofPlatform()`, `new Thread()`, and `Executors.newSingleThreadExecutor(Thread.ofPlatform()...factory())` across all modules to confirm these three are the only instances. Consider adding a static-analysis rule or grep-based check to the engineering rules or CI to flag new platform-thread usage outside of documented exceptions (e.g., the Nostr relay HttpClient executor which uses platform threads intentionally for I/O callbacks).

### CT2. Duplicate test doubles across modules

- **Pattern:** Multiple modules carry copy-pasted test infrastructure rather than sharing it. The SSRF module has a duplicate `LoopbackPermitting` inner class when the shared `LoopbackPermittingBlocklist` exists. The LLM adapter module has `StubConfig implements Config` copy-pasted five times across test files (~200 lines of duplication). These are independent modules with independent test trees, so the duplication is structural rather than accidental — but both reviewers independently flagged the same anti-pattern.
- **Where it appears:** 03-module-infochat-ssrf.md#F2, 04-module-infochat-llm-adapter.md#F2
- **Suggested system-level fix:** Extract per-module shared test utilities into a top-level test-scope class where one exists. For the SSRF module, use the already-existing `LoopbackPermittingBlocklist`. For the LLM adapter, extract a single `StubConfig` in the test tree. No cross-module test-utility jar is warranted at this scale, but each module should de-duplicate its own test doubles.

### CT3. UserSnapshot missing is_banned — single root cause surfacing as two findings

- **Pattern:** The `USER_SNAPSHOT_SQL` query selects only `id` and `registration_state`, omitting `is_banned`. This causes the BanCheck to run a separate query on every inbound message (performance finding) and creates a documentation-vs-implementation gap where the class Javadoc claims "one SELECT per dispatch" but the code issues two (maintainability finding). The two findings have the same root cause and the same fix: add `is_banned` to the snapshot query.
- **Where it appears:** 07-module-infochat-provider.md#F1, 07-module-infochat-provider.md#F4
- **Suggested system-level fix:** Add `is_banned` to `USER_SNAPSHOT_SQL` and add an `isBanned` field to the `UserSnapshot` record. Use the snapshot value for the intake-path ban check. Retain the `BanCheck` service for admin command confirm-leg paths that need a fresh read inside a transaction. Update the class Javadoc to reflect the single-SELECT invariant.

## Findings by category

### SECURITY (1)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| high | Signal `canonicalizeAci` accepts non-UUID strings as valid contact ids | SignalMessageCodec.java:240, SignalGroupHandler.java:165, SignalAdapter.java:160 | 05-module-infochat-messaging-adapter.md#F1 |

### PERFORMANCE (5)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| high | BanCheck runs a separate DB query on every inbound message after the snapshot SELECT already fetched the user row | InboundRouter.java:450, BanCheck.java:45-61 | 07-module-infochat-provider.md#F1 |
| medium | SignalAdapter reconnect uses `new Thread` instead of virtual thread | SignalAdapter.java:402 | 05-module-infochat-messaging-adapter.md#F2 |
| medium | Dispatch executors use platform-thread factories | SignalJsonRpcClient.java:276, SimpleXWebSocketClient.java:169 | 05-module-infochat-messaging-adapter.md#F3 |
| low | `InterruptibleCharSequence.charAt` calls `System.nanoTime()` on every character access | Redactor.java:158-160 | 02-module-infochat-core.md#F1 |
| low | `MessageDigest.getInstance("SHA-256")` allocated per `verify()` call instead of cached | NostrEventVerifier.java:285 | 06-module-infochat-collector.md#F1 |

### SIMPLIFICATION (5)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| medium | Blocking DNS resolution in startup guard's `isLoopback` | LlmRouterStartupGuard.java:272-293 | 04-module-infochat-llm-adapter.md#F1 |
| medium | Four copy-pasted token-bucket acquire methods | RateCapBucket.java:262-428 | 07-module-infochat-provider.md#F3 |
| low | Duplicate `LoopbackPermitting` test double when shared `LoopbackPermittingBlocklist` exists | SsrfGuardedHttpClientTest.java:686 | 03-module-infochat-ssrf.md#F2 |
| low | Copy-pasted `StubConfig` across 5 test files | AnthropicProviderTest.java:331, OpenAiCompatibleProviderTest.java:153, AnthropicProviderMultiBlockContentTest.java:120, HttpProviderSharedPipelineTest.java:138, LlmRouterTest.java:333 | 04-module-infochat-llm-adapter.md#F2 |
| low | Null-guard on parameters that cannot legally be null | BanCommandHandler.java:393-401 | 07-module-infochat-provider.md#F5 |

### MAINTAINABILITY-RULES-DRIFT (7)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| high | ChatAgent sanitizer-audit failure causes user-visible inconsistency | ChatAgent.java:140-192 | 07-module-infochat-provider.md#F2 |
| medium | UserSnapshot does not include `is_banned` despite class Javadoc claiming "one SELECT per dispatch" | InboundRouter.java:220-226, 450 | 07-module-infochat-provider.md#F4 |
| medium | Body-cap default (10 MiB) contradicts design note (5 MB) | SsrfGuardedHttpClient.java:107 | 03-module-infochat-ssrf.md#F1 |
| medium | Dual NOTIFY payload construction strategies for `quarantine_review` | cross-cutting (QuarantineNotifyEmitter.java, V32 stored procedures) | 01-architecture.md#F1 |
| low | SimpleXSubprocess uses `java.util.Random` for backoff jitter | SimpleXSubprocess.java:76 | 05-module-infochat-messaging-adapter.md#F4 |
| low | Stage2VerdictHandler issues a separate UPDATE on the same row already touched by the parent transaction | Stage2VerdictHandler.java:211-219 | 06-module-infochat-collector.md#F2 |
| low | EmbeddingWorker swallows InterruptedException silently | EmbeddingWorker.java:224-233 | 06-module-infochat-collector.md#F3 |

## Synthesizer notes

- Provider findings F1 and F4 describe the same root cause (UserSnapshot missing `is_banned`). They are consolidated into a single cross-cutting theme (CT3) and both appear in the PERFORMANCE and MAINTAINABILITY-RULES-DRIFT tables with cross-references. The performance finding (F1) is in the Top priority list; the maintainability finding (F4) is in the MAINTAINABILITY-RULES-DRIFT table. A developer fixing one fixes both.

- The architecture report (01-architecture.md) contained no findings above low severity and primarily validated that the module DAG, NOTIFY channels, capability flags, schema invariants, and trust boundaries are spec-consistent. Its two findings (F1 medium, F2 low) are included in the category tables above.

- The infochat-collector module report (06-module-infochat-collector.md) found only low-severity issues. The report explicitly notes "No critical, high, or medium issues were found" after reviewing 68 production classes.

- The TranslationProvider placement observation (01-architecture.md#F2, low severity) was not grouped into a cross-cutting theme because no other report flagged a similar SPI-placement pattern. It appears only in the SIMPLIFICATION table.
