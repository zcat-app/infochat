# Deep code review — consolidated summary

**Run directory:** /home/ubuntu5/Projects/quarkus-projects/infochat/deep-code-review/v2/opus-47
**Date:** 2026-06-06 19:00
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

1. [high] SECURITY — `/summary` and `/retry` bypass the per-user LLM rate cap that the spec mandates apply to LLM-triggering operations.
   - Sources: 07-module-infochat-provider.md#F1
   - Why first: Direct spec violation in security.md §Rate limiting; lowest-trust (probation) users can drive the LLM hardest because chat mode IS capped but the slash commands they are allowed to use are not.

2. [high] SECURITY — Re-eval BENIGN path does not emit `quarantine_review` NOTIFY for BENIGN_CLOSED transitions, violating the channel's "all quarantine state-machine moves visible to the Provider" contract.
   - Sources: 06-module-infochat-collector.md#F1
   - Why first: Asymmetric drop on the re-eval path while the first-pass Stage 2 path emits correctly; combined with F3 (Provider trusts payload `new_status` without re-reading row state) this is a wire-protocol soundness issue across two modules.

3. [high] SECURITY — `quarantine_review` listener trusts the NOTIFY payload's `new_status` without re-reading the row's current status; combined with F2's non-transactional dispatch, a poisoned or out-of-order payload races the row's later state and advances the cursor past a still-PENDING row.
   - Sources: 01-architecture.md#F2, 01-architecture.md#F3
   - Why first: Trust-boundary violation between Collector and Provider; the spec's same-transaction invariant is broken on the dispatch path and the payload is treated as ground truth. Loss mode is silent (admin never notified about PENDING/NEEDS_REVIEW rows).

4. [high] SECURITY — `ThrottledAdminNotifier.getState` failure log uses the unsanitized `key` parameter, re-opening the ADMIN-NOTIFY line-forgery surface that `notifyOnce` carefully closes.
   - Sources: 02-module-infochat-core.md#F1
   - Why first: One-line fix that closes a log-injection hole on a security-critical audit channel; the class's whole sanitize() discipline exists to prevent exactly this.

5. [high] SECURITY — Collector role over-granted UPDATE on `price_snapshot`, contradicting spec §Operational ("INSERT-only; no updates") and widening the SQL-injection blast radius the DB-role split is designed to bound.
   - Sources: 02-module-infochat-core.md#F2
   - Why first: DB-role hardening regression; spec is unambiguous and the design follow-up (V38 dedup) reinforced the INSERT-only invariant which is now contradicted by the GRANT matrix.

## Cross-cutting themes

### CT1. Hand-written `@NonNull` despite NullAway package-default making it redundant

- **Pattern:** Engineering rule §7a says "non-null is the package default; `@NonNull` is no longer written by hand." Multiple modules still carry hand-written `@NonNull` annotations — most severely the messaging adapter module (181 occurrences across 28 files) and the LLM adapter module (cross-cutting). The SSRF module also has multiple sites.
- **Where it appears:** 04-module-infochat-llm-adapter.md#F1, 05-module-infochat-messaging-adapter.md#F1, 05-module-infochat-messaging-adapter.md#F8, 03-module-infochat-ssrf.md#F2
- **Suggested system-level fix:** One mechanical repo-wide sweep removing every hand-written `@NonNull` import + annotation in `src/main` and `src/test`, then `mvn verify` to confirm NullAway:ERROR still enforces the invariant. Add a build-time check (a one-liner grep in `lint-ticket.py`-style) that fails on any new hand-written `@NonNull` to prevent regression.

### CT2. Defensive null/contract checks between internal classes violate §7

- **Pattern:** Multiple modules carry `if (param == null)` checks, `Objects.requireNonNull` calls, or "SPI-contract assertion" branches at boundaries that are between two internal classes (not at system boundaries). §7 forbids defensive code for impossible scenarios; with NullAway+JSpecify enforcing non-null at compile time, the runtime checks are dead branches.
- **Where it appears:** 03-module-infochat-ssrf.md#F1, 04-module-infochat-llm-adapter.md#F2, 06-module-infochat-collector.md#F9, 07-module-infochat-provider.md#F11
- **Suggested system-level fix:** Audit all `if (x == null)` and `Objects.requireNonNull` sites in `src/main` for the §7 trust-boundary test; delete those between internal classes, keep only those at system boundaries (adapter inbound, HTTP endpoints, config parsing, SQL deserialization, LLM tool-call arguments, file I/O). Companion: drop tests that exercise the deleted branches (e.g. `constructorRejectsNullTimeout`), since those tests now exist only to validate code §7 forbids.

### CT3. `NOTIFY` wire contract is asymmetric and partially un-honored between Collector producer and Provider consumer

- **Pattern:** The Postgres LISTEN/NOTIFY channels (`new_price_snapshot`, `quarantine_review`, `new_post`) are the trust boundary between services per the architecture spec. Multiple gaps surface: (a) `new_price_snapshot` has a producer but no consumer; (b) `quarantine_review` cursor advance and admin-notify run on separate transactions, violating the same-transaction invariant; (c) the consumer trusts payload `new_status` without re-reading the row; (d) re-eval BENIGN path silently skips the NOTIFY emit; (e) Stage 2 BENIGN path re-emits NOTIFY for rows it didn't transition; (f) JSON payload is built by string concatenation in the emitter; (g) QuarantineReviewListener does not catch up on reconnect (NewPostListener does).
- **Where it appears:** 01-architecture.md#F1, 01-architecture.md#F2, 01-architecture.md#F3, 06-module-infochat-collector.md#F1, 06-module-infochat-collector.md#F2, 06-module-infochat-collector.md#F12, 07-module-infochat-provider.md#F7
- **Suggested system-level fix:** Coordinated ticket touching both Collector and Provider sides of every channel: (1) Either land the missing `new_price_snapshot` Provider-side cache+listener or remove the channel with a spec amendment. (2) Make `QuarantineReviewListener.handleEvent` `@Transactional` and have it re-read row state for the actionable decision. (3) Use `RETURNING id` on every UPDATE that drives a NOTIFY so the emit set exactly matches the transition set. (4) Replace JSON string-concat in `QuarantineNotifyEmitter` with a Jackson `ObjectNode` builder. (5) Mirror `NewPostListener.reconcileAfterReconnect()` in `QuarantineReviewListener.ensureListenConnection`. Add a spec-level note that every NOTIFY emit must (a) commit in the same TX as its trigger, (b) be derived from `RETURNING`, (c) be JSON-built via the shared emitter.

### CT4. Provider-side admin-only / DM-only / null-guarded handler paths drift from spec

- **Pattern:** Several Provider command handlers (`/invite`, `/ban`, `/unban`) silently treat group scope as not-authorized because they resolve the caller via `contactIdOf(scope)` (DM-only) instead of the existing `InboundContext.senderContactId()` seam used by `/approve-group`. Combined with internal-class null guards in `InboundRouter`, the dispatch path is non-uniform.
- **Where it appears:** 07-module-infochat-provider.md#F4, 07-module-infochat-provider.md#F11
- **Suggested system-level fix:** Standardize on `inboundContext.senderContactId()` for caller identity across every admin handler; remove the DM-only `contactIdOf` shape unless the spec explicitly requires DM-only (in which case emit an explicit `error.admin.dm_only` reply rather than the misleading `error.admin_only`). Sweep all admin handlers in one pass.

### CT5. Inconsistent SPI / config / capability surfaces across adapters and providers

- **Pattern:** Parallel SPIs ship subtly different shapes that should be uniform: SimpleX vs Signal config beans (one has public getters, the other deliberately doesn't); SimpleX silent LRU eviction vs Signal evict-on-finalize for tracked handles; SimpleX `setTyping` actively issues a typing command despite `supportsTypingIndicator=false` while Signal honors the flag; `assertIdentity` SPI method is dead surface every adapter implements; the LLM router and the startup guard normalize provider names differently (case-sensitive vs case-insensitive); the capability surface is split between an immutable record and a per-instance accessor (`trustLevel()`).
- **Where it appears:** 01-architecture.md#F4, 01-architecture.md#F5, 04-module-infochat-llm-adapter.md#F7, 05-module-infochat-messaging-adapter.md#F2, 05-module-infochat-messaging-adapter.md#F3, 05-module-infochat-messaging-adapter.md#F4, 05-module-infochat-messaging-adapter.md#F7
- **Suggested system-level fix:** Establish a "parallel SPIs must be uniform" convention in the engineering rules; for every shape where two implementations of the same SPI diverge, either fold the divergence into the contract (with a documented invariant) or align both implementations to the same shape. Audit each SPI surface (`MessagingAdapter`, `LlmProvider`, `Config` beans) for shape drift in a single follow-up pass.

### CT6. Duplicated helpers and stale rationale comments

- **Pattern:** Helper methods are duplicated verbatim across modules with stale rationale comments justifying inline copies that no longer apply: `joinPath` and `preview` exist in three LLM provider classes with one comment now stale because `LlmHttpSupport` exists; Kraken/Bitfinex repeat case-normalization three times. Comments defending duplication outlast the conditions that produced them.
- **Where it appears:** 04-module-infochat-llm-adapter.md#F5, 04-module-infochat-llm-adapter.md#F10, 06-module-infochat-collector.md#F8
- **Suggested system-level fix:** Move the LLM helpers into `LlmHttpSupport` (the existing package-private home), drop the duplicates, delete the now-stale comments. For case-normalization in asset sources, factor a `vsLower`/`vsUpper` pair via a single local computation per method. As a process habit: when adding "kept inline because no shared util" rationale, file a follow-up ticket that revisits the comment when a shared util IS added.

## Findings by category

### SECURITY (8)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| high | `/summary` and `/retry` bypass the per-user LLM rate cap | infochat-provider/src/main/java/.../messaging/InboundRouter.java:587-616; command/SummaryCommandHandler.java; command/RetryCommandHandler.java | 07-module-infochat-provider.md#F1 |
| high | `quarantine_review` payload's `new_status` is trusted without re-reading the row's current status | infochat-provider/src/main/java/.../outbox/QuarantineReviewListener.java:228-273 | 01-architecture.md#F3 |
| high | Collector role over-granted UPDATE on `price_snapshot` | infochat-core/src/main/resources/db/migration/V17__price_snapshot.sql:85 | 02-module-infochat-core.md#F2 |
| high | Re-eval BENIGN does not emit `quarantine_review` NOTIFY for BENIGN_CLOSED transitions | infochat-collector/src/main/java/.../eval/reeval/ReEvaluationJob.java:129-150 | 06-module-infochat-collector.md#F1 |
| high | `ThrottledAdminNotifier.getState` failure log uses the unsanitized key | infochat-core/src/main/java/.../notifier/ThrottledAdminNotifier.java:285-313 | 02-module-infochat-core.md#F1 |
| medium | `/save` does not scope-check the target post | infochat-provider/src/main/java/.../command/SaveCommandHandler.java:99-104,257-279 | 07-module-infochat-provider.md#F5 |
| medium | Anthropic response parser silently truncates multi-block content | infochat-llm-adapter/.../AnthropicProvider.java:178-197 | 04-module-infochat-llm-adapter.md#F4 |
| medium | `SimpleXMessageCodec.classifyError` substring matching is brittle | infochat-messaging-adapter/src/main/java/.../impl/simplex/SimpleXMessageCodec.java:603-622 | 05-module-infochat-messaging-adapter.md#F6 |
| low | Inconsistent provider-name normalization between router and startup guard | infochat-llm-adapter/.../LlmRouter.java:148-156; LlmRouterStartupGuard.java:144,210-211 | 04-module-infochat-llm-adapter.md#F7 |

### PERFORMANCE (9)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| high | `ConfirmStateService` has unbounded in-memory growth for abandoned confirms | infochat-provider/src/main/java/.../command/ConfirmStateService.java:49,101-106,161-177 | 07-module-infochat-provider.md#F3 |
| high | `emitQuarantineNotifyForClosedRows` re-emits NOTIFY for prior BENIGN_CLOSED rows | infochat-collector/src/main/java/.../eval/stage2/Stage2VerdictHandler.java:248-261 | 06-module-infochat-collector.md#F2 |
| medium | `chat_memory` LRU trigger races and re-counts on every insert | infochat-core/src/main/resources/db/migration/V18__chat_tables.sql:35-57 | 02-module-infochat-core.md#F4 |
| medium | `ChatMemoryPruner` truncates retention to whole days | infochat-provider/src/main/java/.../scheduler/ChatMemoryPruner.java:29-46 | 07-module-infochat-provider.md#F9 |
| medium | `PerSourceUnknownTracker` scan has no time bound on the joined post rows | infochat-collector/src/main/java/.../eval/reeval/PerSourceUnknownTracker.java:59-91 | 06-module-infochat-collector.md#F6 |
| medium | `QuarantineReviewListener` does not catch up on reconnect | infochat-provider/src/main/java/.../outbox/QuarantineReviewListener.java:294-310 | 07-module-infochat-provider.md#F7 |
| medium | `SignalJsonRpcClient` reader loop reads one char at a time | infochat-messaging-adapter/src/main/java/.../impl/signal/SignalJsonRpcClient.java:395-441 | 05-module-infochat-messaging-adapter.md#F5 |
| low | `ReEvaluationJob.reconstructOriginalBody` is O(N²) in placeholder count | infochat-collector/src/main/java/.../eval/reeval/ReEvaluationJob.java:243-266 | 06-module-infochat-collector.md#F11 |
| low | Per-call MicroProfile lookups for stable config | infochat-llm-adapter/.../AnthropicProvider.java:104-112 | 04-module-infochat-llm-adapter.md#F9 |
| low | Redundant `Map.copyOf` on already-immutable pin map | infochat-ssrf/.../PinnedDnsResolver.java:153-155; SsrfGuardedHttpClient.java:354-355 | 03-module-infochat-ssrf.md#F5 |

### SIMPLIFICATION (8)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| high | `MessagingAdapter.assertIdentity` is dead SPI surface | infochat-messaging-adapter/src/main/java/.../messaging/MessagingAdapter.java:72-81 | 01-architecture.md#F4 |
| medium | `joinPath` and `preview` are duplicated verbatim across three provider classes | infochat-llm-adapter/.../OpenAiCompatibleProvider.java:257-273; OpenAiCompatibleEmbeddingProvider.java:222-238; AnthropicProvider.java:215-230 | 04-module-infochat-llm-adapter.md#F5 |
| medium | `KrakenSnapshotSource` / `BitfinexSnapshotSource` compute `vsUpper` then check via `toLowerCase` | infochat-collector/src/main/java/.../assets/source/KrakenSnapshotSource.java:101-104; BitfinexSnapshotSource.java:97-100 | 06-module-infochat-collector.md#F8 |
| medium | `SimpleXConfig` exposes public getters; `SignalConfig` deliberately doesn't — pick one | infochat-messaging-adapter/src/main/java/.../impl/signal/SignalConfig.java; impl/simplex/SimpleXConfig.java | 05-module-infochat-messaging-adapter.md#F7 |
| low | `AuditLogWriter.write` open-codes nullable-UUID binding | infochat-core/src/main/java/.../audit/AuditLogWriter.java:106-122 | 02-module-infochat-core.md#F6 |
| low | LLM tool LIMIT clamping is inconsistent across tools | infochat-provider/src/main/java/.../chat/tool/GetReferencesTool.java:38-39; SearchPostsTool.java:51-54; RecallMemoryTool.java:46; ListSavesTool.java:29-30 | 07-module-infochat-provider.md#F10 |
| low | `SignalJsonRpcClient.skipToNewline` uses mark/reset to overshoot the terminator by one | infochat-messaging-adapter/src/main/java/.../impl/signal/SignalJsonRpcClient.java:451-472 | 05-module-infochat-messaging-adapter.md#F9 |

### MAINTAINABILITY-RULES-DRIFT (28)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| high | `new_price_snapshot` channel has producer but no consumer; Provider-side cache it gates does not exist | infochat-collector/.../assets/store/PriceSnapshotStore.java:118-134; infochat-provider/.../command/asset/AssetSnapshotReader.java:84-113 | 01-architecture.md#F1 |
| high | `quarantine_review` cursor advance and admin notification are not in the same transaction | infochat-provider/src/main/java/.../outbox/QuarantineReviewListener.java:143-188 | 01-architecture.md#F2 |
| high | Bluesky `actor` query-string interpolation is not URL-encoded | infochat-collector/src/main/java/.../fetcher/bluesky/BlueskyFetcher.java:113-122 | 06-module-infochat-collector.md#F3 |
| high | Hand-written `@NonNull` annotations across every main-source file violate §7a (messaging) | infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/* (28 files, 181 occurrences) | 05-module-infochat-messaging-adapter.md#F1 |
| high | `@NonNull` is hand-written across the module despite NullAway's non-null-by-default (llm-adapter) | infochat-llm-adapter/src/main/java/.../* (cross-cutting) | 04-module-infochat-llm-adapter.md#F1 |
| high | `SimpleXAdapter.setTyping` issues a typing command despite `supportsTypingIndicator=false` | infochat-messaging-adapter/src/main/java/.../impl/simplex/SimpleXAdapter.java:319-338 | 05-module-infochat-messaging-adapter.md#F2 |
| high | Silent LRU eviction of open SimpleX handles violates the SPI finalize invariant | infochat-messaging-adapter/src/main/java/.../impl/simplex/SimpleXAdapter.java:96-107 | 05-module-infochat-messaging-adapter.md#F3 |
| high | `/invite` command is silently DM-only for bot admins | infochat-provider/src/main/java/.../command/InviteCommandHandler.java:172,665-667 | 07-module-infochat-provider.md#F4 |
| high | `searchPosts` / `getPost` return `published_at` labeled as `ready_at` | infochat-provider/src/main/java/.../chat/tool/SearchPostsTool.java:139,168-169; GetPostTool.java:43,61-62 | 07-module-infochat-provider.md#F2 |
| medium | `AuditLogWriter` exposes two constructors over one `@Inject` field | infochat-core/src/main/java/.../audit/AuditLogWriter.java:62-84 | 02-module-infochat-core.md#F3 |
| medium | Capability surface is split between the immutable record and the `trustLevel()` accessor without a documented invariant | infochat-messaging-adapter/src/main/java/.../messaging/CapabilityFlags.java:17-25; MessagingAdapter.java:60-70 | 01-architecture.md#F5 |
| medium | Canonical host for bracketed IPv6 literals never matches the JDK resolver lookup key | infochat-ssrf/src/main/java/.../SsrfGuardedHttpClient.java:288-290 | 03-module-infochat-ssrf.md#F3 |
| medium | Defensive null checks at an internal-only constructor (SSRF) | infochat-ssrf/src/main/java/.../SsrfGuardedHttpClient.java:197-217 | 03-module-infochat-ssrf.md#F1 |
| medium | Defensive null-check inside the trust boundary in `LlmRouter.forTask` | infochat-llm-adapter/.../LlmRouter.java:139-142 | 04-module-infochat-llm-adapter.md#F2 |
| medium | `Entry.supportedLanguages` declares `@Nullable` but the value is never null | infochat-llm-adapter/.../LlmRouter.java:332-340, 162-171 | 04-module-infochat-llm-adapter.md#F3 |
| medium | `ExportDataCollector` false-positive truncation + `LIMIT N` via string concatenation | infochat-provider/src/main/java/.../command/ExportDataCollector.java:188-195,197-199 | 07-module-infochat-provider.md#F8 |
| medium | Hand-written `@NonNull` annotations (SSRF cross-cutting) | infochat-ssrf/.../IpBlocklist.java:101; PinnedDnsResolver.java:64,131,177; SsrfGuardedHttpClient.java:304,329,599,633,675 | 03-module-infochat-ssrf.md#F2 |
| medium | Interval string concatenation in `PerSourceUnknownTracker` | infochat-collector/src/main/java/.../eval/reeval/PerSourceUnknownTracker.java:77 | 06-module-infochat-collector.md#F7 |
| medium | Mutable static `sanitizer` test seam in production code | infochat-collector/src/main/java/.../eval/stage1/Stage1Pipeline.java:229 | 06-module-infochat-collector.md#F10 |
| medium | `PostPersister` defensively validates SPI contract inside the trust boundary | infochat-collector/src/main/java/.../outbox/PostPersister.java:88-102 | 06-module-infochat-collector.md#F9 |
| medium | `RedactionHook.redact` cannot signal fail-closed on regex timeout | infochat-core/src/main/java/.../audit/RedactionHook.java:30-44 | 01-architecture.md#F6 |
| medium | `SafeLog` signature mimics SLF4J but drops the throwable | infochat-core/src/main/java/.../log/SafeLog.java:24-34 | 02-module-infochat-core.md#F5 |
| medium | `SET LOCAL infochat.actor_id` uses string-concatenated SQL | infochat-provider/src/main/java/.../command/* (12 sites across 8 files) | 07-module-infochat-provider.md#F6 |
| medium | `setStage2Verdict` is a redundant second UPDATE on the just-modified row | infochat-collector/src/main/java/.../eval/stage2/Stage2VerdictHandler.java:212-221 | 06-module-infochat-collector.md#F5 |
| medium | Silent `XMLStreamException` swallow in `RssFeedParser.parse` | infochat-collector/src/main/java/.../fetcher/rss/RssFeedParser.java:102-108 | 06-module-infochat-collector.md#F4 |
| medium | `SignalIdentity.resolve` and `SimpleXIdentity.resolve` are unimplemented stubs | infochat-messaging-adapter/.../impl/signal/SignalIdentity.java:28-31; impl/simplex/SimpleXIdentity.java:28-31 | 05-module-infochat-messaging-adapter.md#F4 |
| medium | `validateLocalOnlyConfiguration` is documented as a "pure function" but performs DNS I/O | infochat-llm-adapter/.../LlmRouterStartupGuard.java:159-179, 263-284 | 04-module-infochat-llm-adapter.md#F6 |
| low | `extractErrorMessage` catches an impossible `IOException` | infochat-llm-adapter/.../AnthropicProvider.java:203-213 | 04-module-infochat-llm-adapter.md#F8 |
| low | `HostInterfaceSet.enumerate()` comment misstates failure-handling lifecycle | infochat-ssrf/src/main/java/.../HostInterfaceSet.java:42-52 | 03-module-infochat-ssrf.md#F4 |
| low | Inconsistent annotation style on record components | infochat-messaging-adapter/src/main/java/.../messaging/Identity.java:20; ScopeRef.java:26-29; InboundMessage.java:24-29; OutboundMessage.java:18-22; MessageHandle.java:34 | 05-module-infochat-messaging-adapter.md#F8 |
| low | Internal-class null guards in `InboundRouter` violate §7 | infochat-provider/src/main/java/.../messaging/InboundRouter.java:457-476,500-515 | 07-module-infochat-provider.md#F11 |
| low | Malformed `Location` header escapes as a non-typed runtime exception | infochat-ssrf/src/main/java/.../SsrfGuardedHttpClient.java:380-388 | 03-module-infochat-ssrf.md#F6 |
| low | `QuarantineNotifyEmitter` builds JSON by string concatenation | infochat-collector/src/main/java/.../notify/QuarantineNotifyEmitter.java:79-81 | 06-module-infochat-collector.md#F12 |
| low | Stale rationale comment in `OpenAiCompatibleEmbeddingProvider.joinPath` | infochat-llm-adapter/.../OpenAiCompatibleEmbeddingProvider.java:217-227 | 04-module-infochat-llm-adapter.md#F10 |
| low | V5 verb-catalogue line comments have drifted from the AuditAction enum | infochat-core/src/main/resources/db/migration/V5__identity_audit.sql:276-298 | 02-module-infochat-core.md#F7 |

## Synthesizer notes

- The two "hand-written `@NonNull`" findings (04#F1 and 05#F1) are listed as separate rows in MAINTAINABILITY-RULES-DRIFT because they affect different module surfaces with different blast radii (LLM-adapter cross-cutting vs messaging-adapter's 181 occurrences across 28 files). Cross-cutting theme CT1 consolidates them; the module-level rows preserve the per-module fix granularity.
- 03#F1 (SSRF defensive null checks) and 03#F2 (SSRF hand-written `@NonNull`) overlap with CT1 and CT2 but are independent edits — F1 deletes runtime branches, F2 deletes annotations. The reports flagged both at medium severity.
- 04#F5 (joinPath/preview duplication) and 04#F10 (stale rationale comment in the same file) are intentionally separate per the source reviewer ("listed separately because the comment itself is the bug, not just the duplication"); CT6 consolidates them but the two-row split mirrors the source.
- 01#F3 (architecture) and 06#F1 (collector) describe related-but-distinct root causes: 01#F3 is about the Provider trusting the payload `new_status`; 06#F1 is about the Collector failing to emit a payload on the re-eval BENIGN path. They are two findings, not one, and remain separate in the SECURITY table.
- The architecture reviewer noted that finding 01#F1 (`new_price_snapshot` channel half-implemented) was already flagged in `deep-code-review/v1/deepseek/01-architecture.md#F2` and remains open. Synthesizer cannot verify across runs but records the cross-run observation as the source reviewer reported it.
- The provider reviewer's "Cross-module observations" section names a possible per-group LLM cap gap (D47) and a possible Collector audit-row pattern for `SET LOCAL infochat.actor_id`. These are reviewer notes flagged as out-of-scope, not findings — they are not represented in the tables above.
