# Deep code review — consolidated summary

**Run directory:** /home/ubuntu5/Projects/quarkus-projects/infochat/.reviews/deep-review/full-2026-06-01-2355
**Date:** 2026-06-02 00:10
**Synthesizer:** review-synthesizer (opus)

## Coverage

- **Reports consumed:** 7
  - architecture: yes
  - module-infochat-core: yes
  - module-infochat-ssrf: yes
  - module-infochat-llm-adapter: yes
  - module-infochat-messaging-adapter: yes
  - module-infochat-collector: yes
  - module-infochat-provider: yes

## Top priority

1. [high] MAINTAINABILITY-RULES-DRIFT — DigestScheduler queries all non-removed groups, not just approved ones
   - Sources: 07-module-infochat-provider.md#F1
   - Why first: Direct spec violation — pending/rejected groups can receive periodic digests because the query omits the `approval_status = 'approved'` filter. Behavioral bug, not just a documentation gap.

2. [high] MAINTAINABILITY-RULES-DRIFT — NormalizedPost.sourceId javadoc contradicts the runtime value
   - Sources: 02-module-infochat-core.md#F1
   - Why second: Actively misleading contract — any Fetcher or StreamSource implementor reading the javadoc will assume `sourceId` is a database key when it is a per-startup dispatch token. Wastes debugging time and can produce incorrect implementations.

3. [high] MAINTAINABILITY-RULES-DRIFT — InMemoryAdapter supportsCodeFormatting contradicts design rationale
   - Sources: 05-module-infochat-messaging-adapter.md#F1
   - Why third: Eliminates test coverage of the code-formatting render path. The design explicitly intended InMemoryAdapter to exercise this path; setting it to `false` means no adapter in the test suite covers monospace rendering.

4. [medium] MAINTAINABILITY-RULES-DRIFT — price_snapshot schema diverges from spec on PK shape and column names
   - Sources: 02-module-infochat-core.md#F2
   - Why fourth: The spec's PK `(asset, sub_verb, captured_at)` enforced a dedup invariant; the surrogate PK `(id, captured_at)` removed it without a replacement UNIQUE constraint. Concurrent fetchers can insert duplicate snapshots.

5. [medium] SECURITY — QuarantineNotifyEmitter NOTIFY payload fields interpolated without JSON escaping
   - Sources: 06-module-infochat-collector.md#F1
   - Why fifth: The only NOTIFY channel whose payload is not defensively escaped. Other emitters (PriceSnapshotStore, ReadyPromoter) apply JSON escaping; this one does not. A future caller passing unexpected characters produces malformed JSON.

## Cross-cutting themes

### CT1. JSON escaping is inconsistent and duplicated across modules

- **Pattern:** At least five independent JSON-escaping implementations exist across the codebase with varying thoroughness (backslash+quote only; backslash+quote+CR+LF; full control-char escaping). One NOTIFY emitter (QuarantineNotifyEmitter) applies no escaping at all. Three command handlers in the provider copy-paste an identical `quoteJsonString` method.
- **Where it appears:** 06-module-infochat-collector.md#F1, 06-module-infochat-collector.md#F4, 07-module-infochat-provider.md#F2
- **Suggested system-level fix:** Extract a single shared `JsonEscapes` utility (in `infochat-core` or a shared package) with a thorough implementation covering all JSON-significant characters. Replace all hand-rolled escape methods across both modules. The NOTIFY emitters should use the same utility for payload construction.

### CT2. Spec and design documents are out of sync with implementation across multiple modules

- **Pattern:** Seven findings across four reports describe situations where the spec or design document commits to a specific behavior, schema shape, or capability value that the implementation does not follow. The drift spans NOTIFY channel consumers (architecture), SPI contract javadoc (core), schema PK shape (core), adapter capability flags (messaging-adapter), and query predicates (provider). Some drift is acknowledged in code comments or design notes, but the spec has not been amended to match.
- **Where it appears:** 01-architecture.md#F1, 02-module-infochat-core.md#F1, 02-module-infochat-core.md#F2, 05-module-infochat-messaging-adapter.md#F1, 05-module-infochat-messaging-adapter.md#F2, 05-module-infochat-messaging-adapter.md#F3, 07-module-infochat-provider.md#F1
- **Suggested system-level fix:** A single pass reconciling each spec/design commitment against the implementation — either updating the code to match the spec or amending the spec to match the implementation. The messaging-adapter capability values in particular need a decision: use design values or update the design document with empirically-tuned values. The DigestScheduler missing filter is the only case where the implementation is unambiguously wrong and the spec is correct.

### CT3. Utility functions duplicated instead of shared

- **Pattern:** Four distinct utility functions (tag normalization, SHA-256 hex encoding, JSON escaping, JSON string quoting) are each copy-pasted in two to three files. All carry similar logic with minor implementation divergences (e.g., SHA-256 uses manual StringBuilder loop in one place and JDK 25 HexFormat in another; JSON escaping varies from minimal to thorough across copies).
- **Where it appears:** 06-module-infochat-collector.md#F2, 06-module-infochat-collector.md#F3, 06-module-infochat-collector.md#F4, 07-module-infochat-provider.md#F2
- **Suggested system-level fix:** Extract shared utilities into `infochat-core` or a dedicated shared package: `TagNormalizer` (with `normalize`/`normalizeOrThrow`), `Sha256` (using `HexFormat`), `JsonEscapes` (thorough implementation). The collector module has three TODO comments (`T1-D: consolidate`) already acknowledging the tag normalization duplication.

## Findings by category

### SECURITY (2)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| medium | QuarantineNotifyEmitter NOTIFY payload fields not JSON-escaped | QuarantineNotifyEmitter.java:41-42 | 06-module-infochat-collector.md#F1 |
| low | `canonicalizeHost` uses `IDN.ALLOW_UNASSIGNED` in security-critical SSRF path | SsrfGuardedHttpClient.java:273 | 03-module-infochat-ssrf.md#F3 |

### PERFORMANCE (1)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| medium | `readBounded` creates a new platform-thread executor per HTTP request | SsrfGuardedHttpClient.java:420-424 | 03-module-infochat-ssrf.md#F1 |

### SIMPLIFICATION (5)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| medium | Tag normalization logic duplicated in three places | BootstrapLoader.java:266-274, TaggerWorker.java:425-432, TagVocabulary.java:127-134 | 06-module-infochat-collector.md#F2 |
| medium | SHA-256 hex encoding duplicated in two places | BootstrapLoader.java:276-290, PostPersister.java:168-177 | 06-module-infochat-collector.md#F3 |
| medium | `quoteJsonString` duplicated across three command handlers | BanCommandHandler.java:462-486, GrantAdminCommandHandler.java:368-392, RevokeAdminCommandHandler.java:364-388 | 07-module-infochat-provider.md#F2 |
| low | `approve_quarantine` audit INSERT omits denormalized actor columns | V21__quarantine_admin.sql:68-69, V25__quarantine_procedure_remediation.sql:58-59 | 02-module-infochat-core.md#F3 |
| low | JSON escape helper duplicated across the module | BootstrapLoader.java:297-310, StartupReleaseOnStage2FailureWarn.java:152-175, PriceSnapshotStore.java:133-135 | 06-module-infochat-collector.md#F4 |

### MAINTAINABILITY-RULES-DRIFT (11)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| high | DigestScheduler queries all non-removed groups, not just approved ones | DigestScheduler.java:174-176 | 07-module-infochat-provider.md#F1 |
| high | NormalizedPost.sourceId javadoc contradicts the runtime value | NormalizedPost.java:17-41, Fetcher.java:33, StreamSource.java:37 | 02-module-infochat-core.md#F1 |
| high | InMemoryAdapter supportsCodeFormatting contradicts design rationale | InMemoryAdapter.java:61 | 05-module-infochat-messaging-adapter.md#F1 |
| medium | `new_price_snapshot` NOTIFY channel missing Provider-side LISTEN consumer | cross-cutting (see report) | 01-architecture.md#F1 |
| medium | price_snapshot schema diverges from spec on PK shape and column names | V17__price_snapshot.sql:35-52 | 02-module-infochat-core.md#F2 |
| medium | EmbeddingResult exposes mutable float array without defensive copy | EmbeddingResult.java:14 | 04-module-infochat-llm-adapter.md#F1 |
| medium | SimpleXAdapter capability values drift from design | SimpleXAdapter.java:64-78 | 05-module-infochat-messaging-adapter.md#F2 |
| medium | SignalAdapter capability values drift from design | SignalAdapter.java:70-84 | 05-module-infochat-messaging-adapter.md#F3 |
| low | Stale class-level Javadoc rejects ws/wss but code supports them | SsrfGuardedHttpClient.java:40-46 | 03-module-infochat-ssrf.md#F2 |
| low | AnthropicProvider.extractErrorMessage silently swallows Exception | AnthropicProvider.java:201 | 04-module-infochat-llm-adapter.md#F2 |
| low | SignalAdapter.start() throws IllegalStateException rather than MessagingException | SignalAdapter.java:174-233 | 05-module-infochat-messaging-adapter.md#F4 |

## Synthesizer notes

- The provider report uses UPPERCASE severity labels (HIGH, MEDIUM) while all other reports use lowercase. Normalized to lowercase in the consolidated tables.
- The architecture report's F1 (missing `new_price_snapshot` LISTEN consumer) is a spec-implementation gap, not a behavioral bug. The reviewer explicitly noted "This is not a correctness issue." It is included in the MAINTAINABILITY-RULES-DRIFT category at the architecture reviewer's stated severity (medium).
- The messaging-adapter report's F2 (SimpleXAdapter) and F3 (SignalAdapter) both note that the implementation values may have been empirically tuned. The correct resolution path is to verify against live adapters and update whichever document (implementation or design) is wrong — not blindly revert to design values.
- The core report's F3 (approve_quarantine audit INSERT) notes that V24's `delete_preban_user` procedure uses the same pattern but includes a justification comment. The finding is about the missing comment, not about the column omission itself.
- Total findings across all reports: 19. No reports produced zero findings. No failed targets.
