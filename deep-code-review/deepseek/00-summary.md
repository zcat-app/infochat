# Deep code review -- consolidated summary

**Run directory:** .reviews/deep-review/full-2026-06-01-2057/
**Date:** 2026-06-01
**Synthesizer:** review-synthesizer (deepseek)

## Coverage

- **Reports consumed:** 7
  - architecture: yes
  - module-infochat-core: yes
  - module-infochat-ssrf: yes
  - module-infochat-llm-adapter: yes
  - module-infochat-messaging-adapter: yes
  - module-infochat-collector: yes
  - module-infochat-provider: yes

No targets failed or are missing. The summary below covers the full scope of the audit.

## Top priority

1. [HIGH] MAINTAINABILITY-RULES-DRIFT -- QuarantineReviewListener: SQL constructed via string concatenation in getUpsertSql
   - Sources: 07-module-infochat-provider.md#F1
   - Why first: A numeric interval value is spliced into SQL via string concatenation inside an otherwise fully parameterized query. This pattern normalizes an injection-unsafe style; a future change that makes the throttle-window value user-configurable would introduce a SQL injection vector. The same method also has unsynchronized field access (no memory barrier) on the cached SQL string.

2. [HIGH] MAINTAINABILITY-RULES-DRIFT -- SignalConfig.validate() provides a misleading boot-time guarantee
   - Sources: 05-module-infochat-messaging-adapter.md#F1
   - Why first: The boot-time `Files.exists`/`isWritable` checks use a single elapsed instant but the Javadoc promises that a misconfigured deployment fails at boot. A post-boot filesystem remount or volume detach defeats the check entirely, making the guard illusory for production Signal deployments.

3. [MEDIUM] MAINTAINABILITY-RULES-DRIFT -- GrantAdminCommandHandler: SQL constructed via string concatenation
   - Sources: 07-module-infochat-provider.md#F5
   - Why first: Second SQL-concatenation finding in the Provider module (a UUID concatenated into SET LOCAL via Statement rather than PreparedStatement). The duplicate pattern across two Provider files normalizes a dangerous anti-pattern in a codebase that otherwise consistently uses bind parameters.

4. [MEDIUM] MAINTAINABILITY-RULES-DRIFT -- DigestScheduler: transaction atomicity gap in recordMissedSlot
   - Sources: 07-module-infochat-provider.md#F4
   - Why first: The audit-log write commits before the sentinel cache insert and admin notification run. A failure after the commit produces orphan audit rows with no matching sentinel, causing duplicate audit entries on the next scheduler tick -- a data-integrity defect in the missed-slot detection path.

5. [MEDIUM] MAINTAINABILITY-RULES-DRIFT -- Multiple files: Defensive null-checks on CDI-injected fields violate engineering rule 7
   - Sources: 07-module-infochat-provider.md#F2
   - Why first: Four production classes guard @Inject-ed CDI fields against null, a pattern explicitly forbidden by engineering rule 7. The checks exist solely to accommodate test code that constructs instances without CDI wiring, degrading production code for test convenience.

## Cross-cutting themes

### CT1. Spec-to-code drift across multiple modules

- **Pattern:** Three reports identify discrepancies between spec/design documents and the actual implementation. The DAG document (architecture report) claims three sibling modules depend on infochat-core when none of their poms or imports bear this out. The NOTIFY channel spec (architecture report) describes a price-snapshot cache layer and LISTEN consumer that do not exist -- the channel emits into a vacuum. The local-only config guard spec (llm-adapter report) says the guard fails Provider startup, but the implementation runs it on Collector startup because the security-judge config keys live there.
- **Where it appears:** 01-architecture.md#F1, 01-architecture.md#F2, 04-module-infochat-llm-adapter.md#F3
- **Suggested system-level fix:** Add a spec-audit step to the M1 workflow (e.g., before every spec-adjacent merge or every 5 tickets) that cross-references the relevant spec sections against the current implementation. For the DAG document in particular, automate the verification via a script that extracts actual module dependencies from pom.xml files and compares against the documented graph.

### CT2. Missing nullability annotations on public API surfaces

- **Pattern:** Two reports flag public API elements that lack required `@NonNull` annotations. The infochat-ssrf module has three public constructors whose reference-type parameters have no nullability annotations. The infochat-llm-adapter module has an SPI interface (`LlmProvider.generate`) whose return type lacks `@NonNull` despite the Javadoc guaranteeing non-null and the sole implementation already carrying the annotation.
- **Where it appears:** 03-module-infochat-ssrf.md#F1, 04-module-infochat-llm-adapter.md#F4
- **Suggested system-level fix:** Run `scripts/lint-contracts.py` as part of the pre-commit or CI gate (currently it requires manual invocation). Extend it to check return types on interface/SPI methods in addition to method parameters. The two modules affected here may be signs of wider non-compliance that automated checking would surface.

### CT3. String concatenation for structured output at system boundaries

- **Pattern:** Three reports identify cases where structured strings (SQL, JSON) are assembled via raw concatenation rather than proper builders, parameter binding, or escaping. The collector builds NOTIFY payloads via string concatenation without JSON escaping in two of three sites. The provider assembles SQL strings via concatenation in two separate files (QuarantineReviewListener, GrantAdminCommandHandler). Each per-module reviewer noted one instance as an isolated issue, but together they show a systemic pattern of building structured output at system boundaries without proper encoding.
- **Where it appears:** 06-module-infochat-collector.md#F5, 07-module-infochat-provider.md#F1, 07-module-infochat-provider.md#F5
- **Suggested system-level fix:** Establish a project-wide convention: SQL MUST use only PreparedStatement bind parameters (no `+` concatenation of any fragment into SQL text); structured payloads MUST use a JSON serializer or a shared `jsonEscape` helper. Add a CODEOWNERS or review-checklist item that flags raw concatenation in SQL or JSON construction contexts.

## Findings by category

### SECURITY (1)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| low | getState() exception log uses unsanitized key | `ThrottledAdminNotifier.java:305` | 02-module-infochat-core.md#F3 |

### PERFORMANCE (0)

No findings in this category.

### SIMPLIFICATION (4)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| medium | AssetSnapshotFetcher duplicates SourceRepository failure-counter logic | `AssetSnapshotFetcher.java:228-297` | 06-module-infochat-collector.md#F2 |
| low | Entity extraction prompt embedded as Java string constant | `EntityExtractorWorker.java:127-142` | 06-module-infochat-collector.md#F7 |
| low | NostrRelayConnection.backoffDelay is static but uses an instance-level Random | `NostrRelayConnection.java:354` | 06-module-infochat-collector.md#F4 |
| low | Triplicated sha256Hex, jsonEscape, normalizeTag utilities | `PostPersister.java:168`, `BootstrapLoader.java:276`/`297`, `BootstrapAssetsLoader.java:363`/`378`, `PriceSnapshotStore.java:133`, `StartupReleaseOnStage2FailureWarn.java:157`, `TagVocabulary.java:127`, `TaggerWorker.java:425` | 06-module-infochat-collector.md#F6 |

### MAINTAINABILITY-RULES-DRIFT (27)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| high | QuarantineReviewListener: SQL constructed via string concatenation in getUpsertSql | `QuarantineReviewListener.java:86-97` | 07-module-infochat-provider.md#F1 |
| high | SignalConfig.validate() provides a misleading boot-time guarantee | `SignalConfig.java:63-79` | 05-module-infochat-messaging-adapter.md#F1 |
| medium | `new_price_snapshot` NOTIFY channel has producer but no consumer, and the spec-promised cache layer is absent | `PriceSnapshotStore.java:20-42` (producer); `AssetSnapshotReader.java` (no cache) | 01-architecture.md#F2 |
| medium | BlueskyFetcher query parameters lack URL encoding | `BlueskyFetcher.java:110-116` | 06-module-infochat-collector.md#F1 |
| medium | ChatToolDispatcher: CDI constructor does not validate registry-tool completeness | `ChatToolDispatcher.java:69-75` | 07-module-infochat-provider.md#F3 |
| medium | Defensive null-checks on CDI-injected fields across multiple files | `HelpCommandHandler.java:60-61`, `InboundRouter.java:417,446`, `LlmOutputSanitizer.java:229`, `AssetCommandFamilyOracle.java:44` | 07-module-infochat-provider.md#F2 |
| medium | DigestScheduler: transaction atomicity gap in recordMissedSlot | `DigestScheduler.java:130-158` | 07-module-infochat-provider.md#F4 |
| medium | GrantAdminCommandHandler: SQL constructed via string concatenation | `GrantAdminCommandHandler.java:195-197` | 07-module-infochat-provider.md#F5 |
| medium | IPv6 IP literals rejected as "invalid host" before reaching IP blocklist | `SsrfGuardedHttpClient.java:269` | 03-module-infochat-ssrf.md#F2 |
| medium | Missing nullability annotations on public constructor reference-type parameters | `SsrfGuardedHttpClient.java:162,183`, `PinnedDnsResolver.java:56` | 03-module-infochat-ssrf.md#F1 |
| medium | Module DAG design document is inaccurate about sibling-to-core dependencies | `docs/design/09-reference.md` lines 18-38 | 01-architecture.md#F1 |
| medium | Oversize-line character-at-a-time drain in SignalJsonRpcClient reader loop | `SignalJsonRpcClient.java:87,326-370` | 05-module-infochat-messaging-adapter.md#F3 |
| medium | PostgresSchemaTestBase.truncateAll() omits key tables from cleanup | `PostgresSchemaTestBase.java:80-84` | 02-module-infochat-core.md#F1 |
| medium | Silent exception swallow in extractErrorMessage | `AnthropicProvider.java:201-203` | 04-module-infochat-llm-adapter.md#F2 |
| medium | SimpleXConfig.validate() is never called for idle adapters | `SimpleXConfig.java:73-88` | 05-module-infochat-messaging-adapter.md#F2 |
| medium | Spec-drift: local-only guard runs on Collector, spec says Provider startup | `LlmRouterStartupGuard.java:40-44`, `docs/spec/llm.md:132-134` | 04-module-infochat-llm-adapter.md#F3 |
| medium | Task key segment mapping duplicated in three locations | `AnthropicProvider.java:209-218`, `LlmRouter.java:249-258`, `AnthropicProviderTest.java:221-229` | 04-module-infochat-llm-adapter.md#F1 |
| medium | ThrottledAdminNotifier.sanitize() -- cross-sectional risk with no enforcement boundary | `ThrottledAdminNotifier.java:115-122,217-219,284,305` | 02-module-infochat-core.md#F2 |
| low | BootstrapAssetsLoader defensive code for unreachable scenario | `BootstrapAssetsLoader.java:301-305` | 06-module-infochat-collector.md#F3 |
| low | InboundRouter: UserSnapshot.isBanned field is dead code | `InboundRouter.java:601` | 07-module-infochat-provider.md#F6 |
| low | LlmOutputSanitizer: non-standard whitespace can bypass multi-word closed-list token matching | `LlmOutputSanitizer.java:191` | 07-module-infochat-provider.md#F7 |
| low | Missing @NonNull annotation on LlmProvider.generate return type | `LlmProvider.java:36` | 04-module-infochat-llm-adapter.md#F4 |
| low | NOTIFY payloads built via string concatenation without JSON escaping | `QuarantineNotifyEmitter.java:41`, `ReadyPromoter.java:176`, `PriceSnapshotStore.java:99` | 06-module-infochat-collector.md#F5 |
| low | Redactor.CATALOGUE generic pattern allows extended backtracking before watchdog fires | `Redactor.java:52-54` | 02-module-infochat-core.md#F4 |
| low | SignalAdapter null field reliance for error messages | `SignalAdapter.java:91-95` | 05-module-infochat-messaging-adapter.md#F4 |
| low | Undocumented "null" string normalization in MicroProfileConfigReader | `LlmRouter.java:396-399` | 04-module-infochat-llm-adapter.md#F5 |
| low | UrlRedactor omits brackets around IPv6 addresses | `UrlRedactor.java:64` | 03-module-infochat-ssrf.md#F3 |

## Synthesizer notes

- The Provider module accounts for 9 of the 27 MAINTAINABILITY-RULES-DRIFT findings and both HIGH-severity findings. This concentration is notable: the Provider is the user-facing service with CDI wiring, command routing, and chat-agent integration, which may explain the higher finding density. The findings range from genuine data-integrity concerns (transaction atomicity gap, SQL concatenation) to rule-compliance issues (defensive null-checks, missing validation). The developer should consider whether the Provider module's review coverage or testing harness needs strengthening relative to other modules.
- The Architecture report finding F2 (NOTIFY channel with no consumer) and the Collector report finding F5 (NOTIFY payloads lack JSON escaping) both touch the NOTIFY subsystem at different angles. The architecture finding concerns the channel lifecycle (producer with no consumer, spec-described cache absent); the collector finding concerns payload formatting (concatenation without escaping). They are distinct root causes and appear as separate rows in the MAINTAINABILITY-RULES-DRIFT table, but a single developer should own both fixes since they require coordinated updates to the same `PriceSnapshotStore` class.
- The `new_price_snapshot` NOTIFY channel described in Architecture F2 is listed in the spec's closed set of v1 channels. The architecture report recommends Option A (remove the channel and update the spec). If the developer chooses instead to implement the cache (Option B), the Collector F5 payload-escaping issue must be fixed first as part of that work, since structured JSON parsing on the consumer side requires well-formed input.
