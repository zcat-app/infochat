# Deep code review — consolidated summary

**Run directory:** /home/ubuntu5/Projects/quarkus-projects/infochat/.reviews/deep-review/full-2026-06-07-0057
**Date:** 2026-06-07 01:45
**Synthesizer:** review-synthesizer (opus)

## Coverage

- **Reports consumed:** 7
  - architecture: yes (01-architecture.md, 10 findings)
  - module-infochat-core: yes (02-module-infochat-core.md, 6 findings)
  - module-infochat-ssrf: yes (03-module-infochat-ssrf.md, 6 findings)
  - module-infochat-llm-adapter: yes (04-module-infochat-llm-adapter.md, 6 findings)
  - module-infochat-messaging-adapter: yes (05-module-infochat-messaging-adapter.md, 11 findings)
  - module-infochat-collector: yes (06-module-infochat-collector.md, 12 findings)
  - module-infochat-provider: yes (07-module-infochat-provider.md, 13 findings)

No failed targets. 64 raw findings consolidate to 61 entries after deduplication (the hand-written `@NonNull` finding appears in three reports; the §7 defensive-null-check finding appears in two).

## Top priority

1. [critical] PERFORMANCE — Inbound messages are dispatched synchronously on the transport's only read thread, so any Provider reply sent from inside `onMessage` self-deadlocks until the ack timeout (30 s SimpleX / 15 s Signal); on Signal, three such timeouts SIGKILL the healthy daemon.
   - Sources: 05-module-infochat-messaging-adapter.md#F1
   - Why first: the core request→reply cycle is structurally broken on both production transports, and the in-memory adapter masks the defect from the entire test suite.

2. [critical] MAINTAINABILITY-RULES-DRIFT — The spec-committed bootstrap-admin @Startup bean does not exist; a fresh deployment has zero admin rows and cannot mint its first admin or invite in-band.
   - Sources: 01-architecture.md#F1
   - Why first: the invite → registration → group chain (D44/D47) is unreachable on any production deployment without an out-of-band raw-SQL escape hatch; CI cannot observe the gap because every test seeds admins by direct INSERT.

3. [critical] MAINTAINABILITY-RULES-DRIFT — No cross-tick UID dedup exists anywhere on the fetch path, so every HTTP-fetch tick re-ingests the entire feed as fresh RAW posts, violating schema.md §UID derivation.
   - Sources: 06-module-infochat-collector.md#F1
   - Why first: ~14,400 duplicate posts per source per day, each paying Stage 1 + tagger + entity + embedding LLM calls; user-visible duplicate content and unbounded partition/index growth.

4. [high] MAINTAINABILITY-RULES-DRIFT — The partition scheduler only ever provisions next month, never the current month, so a fresh deployment after July 2026 (or a restart after a month-spanning outage) wedges every insert into all five partitioned tables for the remainder of the month.
   - Sources: 06-module-infochat-collector.md#F2
   - Why first: total-ingest-outage failure mode whose trigger window opens in under two months; the 25-day liveness WARN cannot detect it.

5. [high] SECURITY — `/summary` and `/retry` bypass the per-user LLM-triggering rate bucket, and `/summary` also bypasses the in-flight tracker, so a registered user can multiply LLM cost and run concurrent prose generations that `/stop` cannot cancel.
   - Sources: 07-module-infochat-provider.md#F1
   - Why first: the rate bucket is the deployment's cheapest defense against LLM-cost exhaustion, and two of the three surfaces the spec routes through it skip it entirely.

## Cross-cutting themes

### CT1. Hand-written `@NonNull` contradicts the §7a null-marked-package convention in every module

- **Pattern:** §7a/D48 make non-null the package default with NullAway enforcement, yet 752 hand-written `@NonNull` annotations across 171 main-source files persist (flagged as a finding by three reports and as an observation by two more: ~275 occurrences counted from the messaging report, 156 in the collector, 20 in messaging-adapter itself). Mixed annotated/bare signatures destroy the reading rule "bare type = never null" exactly on the SPI surfaces other modules consume.
- **Where it appears:** 01-architecture.md#F10, 02-module-infochat-core.md#F6, 04-module-infochat-llm-adapter.md#F1 (observations in 05-module-infochat-messaging-adapter.md and 06-module-infochat-collector.md)
- **Suggested system-level fix:** one dedicated repo-wide mechanical sweep commit deleting every `@NonNull` (and unused imports); a green `mvn verify` proves the sweep changed no contract since NullAway treats both forms identically inside annotated packages.

### CT2. Spec commitments and deferral comments orphaned by milestone completion

- **Pattern:** with M1 fully done (no pending tickets), multiple spec-committed mechanisms have zero implementation, and in-code deferral comments point at tickets that closed without delivering them: the bootstrap-admin bean ("deferred per M1-046's notes"), the partition pruner, the entire ProgressNotifier pipeline, transport reconnect-after-restart (two javadocs describe a mechanism that does not exist), the `/stop` pg_cancel_backend + statement_timeout layers, the Nostr `/add-source` probe, the pagination-cap saturation counter, and dead `resolve(Path)` stubs citing completed tickets that chose a different mechanism.
- **Where it appears:** 01-architecture.md#F1, 01-architecture.md#F3, 01-architecture.md#F8, 05-module-infochat-messaging-adapter.md#F5, 05-module-infochat-messaging-adapter.md#F10, 06-module-infochat-collector.md#F12, 07-module-infochat-provider.md#F5, 07-module-infochat-provider.md#F6
- **Suggested system-level fix:** a milestone-close audit pass that greps every "deferred per"/"lands in M1-" comment and every spec-committed mechanism, and resolves each gap into either a new ticket or an explicit spec amendment — so neither code comments nor spec text promise behavior the finished milestone does not deliver.

### CT3. Copy-paste duplication producing silent contract drift between siblings

- **Pattern:** the same contract is implemented N times by hand and the copies have already diverged: seven admin handlers duplicate ~150 lines each (the provider report traces its F3 and F4 directly to this drift), four RSS-shaped fetchers are verbatim copies, utility helpers are re-implemented, the two production adapters classify the same interrupted-await state differently and report startup failures with different exception types, and parser input-tolerance policies diverge between fetcher kinds.
- **Where it appears:** 07-module-infochat-provider.md#F10 (root of 07#F3/07#F4), 06-module-infochat-collector.md#F7, 06-module-infochat-collector.md#F8, 06-module-infochat-collector.md#F11, 05-module-infochat-messaging-adapter.md#F9, 05-module-infochat-messaging-adapter.md#F11
- **Suggested system-level fix:** extract the shared implementations (an `AdminCommandSupport` bean, an `RssShapedFetch` helper, shared failure-classification) and extend the `AdapterCapabilityContractTest` pattern — one parameterized contract test per cross-implementation invariant — so future drift fails the build instead of a review.

### CT4. Admin-notification delivery is gated on dedup mechanisms that suppress genuinely actionable events

- **Pattern:** two independent designs lose the one notification the operator needed: the `quarantine_review` path fires the admin notifier only when the cursor CAS advances (and never during catch-up), so events missed during downtime or committed out of timestamp order are consumed silently; the per-source UNKNOWN auto-disable uses one global throttle key, so the second source disabled within a throttle window is never reported.
- **Where it appears:** 01-architecture.md#F2, 06-module-infochat-collector.md#F5
- **Suggested system-level fix:** establish (in the ThrottledAdminNotifier design note) that notifier keys are always per-target and that notification firing is state-based — decoupled from cursor/catch-up arithmetic, with the throttle window as the only dedup — and audit every `notifyOnce` call site against it.

### CT5. LLM call sites lack the cost bounds the spec relies on

- **Pattern:** three independent gaps each multiply LLM spend: the missing fetch-path dedup re-evaluates the same content every tick; overlapping `@Scheduled` pollers (default `ConcurrentExecution.PROCEED`) double-pick the same posts for duplicate LLM/embedding calls; and `/summary`/`/retry` bypass the per-user LLM rate bucket on the Provider side.
- **Where it appears:** 06-module-infochat-collector.md#F1, 06-module-infochat-collector.md#F4, 07-module-infochat-provider.md#F1
- **Suggested system-level fix:** inventory every LLM/embedding invocation path against an explicit bound (UID dedup before enqueue, `ConcurrentExecution.SKIP` on every poller, the shared rate bucket on every user-triggered surface) and record the mapping in the llm design note so new call sites must name their bound.

### CT6. Partition lifecycle is half-built across creation, pruning, and consumers

- **Pattern:** creation covers only next month (never the current month), the drop half of Invariant 6 does not exist anywhere despite design notes documenting a nightly pruner with daily partitions and 4/7/30-day horizons, and at least one consumer (the admin-review TTL sweep) joins back to `post`, defeating the denormalization built specifically to survive partition drops — so landing the pruner without fixing the consumer activates a latent defect.
- **Where it appears:** 01-architecture.md#F3, 06-module-infochat-collector.md#F2, 06-module-infochat-collector.md#F10
- **Suggested system-level fix:** one partition-lifecycle component owning startup + per-tick provisioning of current and next month plus profile-driven pruning, landed together with the TTL-sweep join removal and a design-note amendment (02-schema §2.4.4) stating the honest monthly cadence and effective horizons.

## Findings by category

### SECURITY (9)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| high | `/summary` and `/retry` bypass the LLM rate bucket; `/summary` bypasses the in-flight gate | SummaryCommandHandler.java:126-205, RetryCommandHandler.java:133-217, InboundRouter.java:594-615 | 07-module-infochat-provider.md#F1 |
| high | Auto-promote writes a spurious `PROMOTE_GROUP_ADMIN` audit row on every message from the current group admin | GroupAutoPromoteService.java:44-49, 77-98, InboundRouter.java:500-515 | 07-module-infochat-provider.md#F2 |
| high | Signal reader thread dies permanently on structurally-malformed frames | SignalMessageCodec.java:99-121, 153-155, SignalJsonRpcClient.java:474-492, 386-393 | 05-module-infochat-messaging-adapter.md#F2 |
| medium | `/unban` of a non-banned user writes a false `UNBAN` audit row and a false restoration disclosure | UnbanCommandHandler.java:149-204, 254-261 | 07-module-infochat-provider.md#F3 |
| medium | Audit-on-intent applied inconsistently across admin handlers | VouchCommandHandler.java:167-183, PromoteCommandHandler.java:96-119, DemoteCommandHandler.java:88-102, UnbanCommandHandler.java | 07-module-infochat-provider.md#F4 |
| medium | Raw inbound frame bytes interpolated into exception messages in the Signal codec | SignalMessageCodec.java:97, 111 | 05-module-infochat-messaging-adapter.md#F7 |
| low | `/stop` releases the in-flight slot for a still-running worker, allowing the invariant to be bypassed | CancellationService.java:58-68, InFlightTracker.java:58-60 | 07-module-infochat-provider.md#F11 |
| low | Deprecated IPv6 site-local range fec0::/10 is not blocked | IpBlocklist.java:201-223 | 03-module-infochat-ssrf.md#F4 |
| low | getState logs the raw caller key; sanitize misses non-CR/LF control characters | ThrottledAdminNotifier.java:310, 116 | 02-module-infochat-core.md#F3 |

### PERFORMANCE (7)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| critical | Synchronous inbound dispatch on the transport read thread self-deadlocks the request→reply cycle | SimpleXWebSocketClient.java:260-327, SimpleXAdapter.java:347-363, SignalJsonRpcClient.java:514-553 | 05-module-infochat-messaging-adapter.md#F1 |
| high | Concurrent `sendText` collisions are silently swallowed; the JDK single-outstanding-send constraint is unhandled | SimpleXWebSocketClient.java:180-221, 228-242 | 05-module-infochat-messaging-adapter.md#F3 |
| high | JVM-wide single pin slot + global lock serializes all outbound connection establishment | PinnedDnsResolver.java:111-118, SsrfGuardedHttpClient.java:347-398, 599-614 | 03-module-infochat-ssrf.md#F1 |
| medium | Digest slots execute synchronously on the scheduler tick thread | DigestScheduler.java:130-134, DigestWorker.java:77 | 07-module-infochat-provider.md#F7 |
| medium | Scheduled pollers allow overlapping executions (no `ConcurrentExecution.SKIP`) | EmbeddingWorker.java:164, EntityExtractorWorker.java:178, TaggerWorker.java:187, FetchScheduler.java:173, ReEvaluationJob.java:86, LinkingJob.java:123, AdminReviewTtlJob.java:57 | 06-module-infochat-collector.md#F4 |
| medium | Semantic-linking query bypasses the HNSW index | LinkingJob.java:260-276 | 06-module-infochat-collector.md#F6 |
| low | idx_chat_message_session_seq duplicates the primary-key index | V18__chat_tables.sql:69-75 | 02-module-infochat-core.md#F4 |

### SIMPLIFICATION (4)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| medium | Dormant CDI eager-bean machinery on SimpleXConfig / SignalConfig | SimpleXConfig.java:32-34, 52-58, 86-87, SignalConfig.java:27-29, 44-47, 70 | 05-module-infochat-messaging-adapter.md#F8 |
| medium | Four copy-pasted RSS-shaped fetchers | fetcher/rss/RssFetcher.java:66-92, fetcher/nitter/NitterFetcher.java, fetcher/odysee/OdyseeFetcher.java, fetcher/youtube/YouTubeFetcher.java | 06-module-infochat-collector.md#F7 |
| medium | Seven admin handlers duplicate the same ~150-line support boilerplate | BanCommandHandler, GrantAdminCommandHandler, RevokeAdminCommandHandler, UnbanCommandHandler, VouchCommandHandler, InviteCommandHandler, ForgetCommandHandler | 07-module-infochat-provider.md#F10 |
| low | Duplicated utility helpers (`sha256Hex`, `readBigDecimal`) | BootstrapAssetsLoader.java:358-371, assets/source/{Coingecko,Kraken,Bitfinex}SnapshotSource.java | 06-module-infochat-collector.md#F8 |

### MAINTAINABILITY-RULES-DRIFT (41)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| critical | No cross-tick dedup on the fetch path — every tick re-ingests the full feed | PostPersister.java:106-117, FetchScheduler.java:230-237 | 06-module-infochat-collector.md#F1 |
| critical | The spec-committed bootstrap-admin seeding bean does not exist; a fresh deployment cannot create its first admin in-band | AdapterRegistry.java:227-262, application.properties:91-94 | 01-architecture.md#F1 |
| high | 752 hand-written `@NonNull` annotations contradict the §7a null-marking convention | cross-cutting: 171 main-source files across all six modules; representative LlmProvider.java:36, Fetcher.java:33, MessagingAdapter.java:95 | 01-architecture.md#F10, 02-module-infochat-core.md#F6, 04-module-infochat-llm-adapter.md#F1 |
| high | Bot mention is not stripped from delivered group-message text (spec-drift, breaks group commands) | SimpleXGroupHandler.java:71-85, SignalGroupHandler.java:138-167, InboundMessage.java:19-22 | 05-module-infochat-messaging-adapter.md#F4 |
| high | Defensive null checks inside the trust boundary (§7) | LlmRouter.java:139-142, Stage1Worker.java:87-90, EntityExtractorWorker.java:241, TaggerWorker.java:298, Stage2Worker.java:199, 204 | 04-module-infochat-llm-adapter.md#F2, 06-module-infochat-collector.md#F9 |
| high | Invariant 6's partition-drop half is unimplemented and the design notes describe a pruner, cadence, and horizons that do not match the code | PartitionCreator.java:54-80, docs/design/02-schema.md:806-820, docs/design/07-deployment.md:222 | 01-architecture.md#F3 |
| high | PartitionCreator never provisions the current month | PartitionCreator.java:54-70 | 06-module-infochat-collector.md#F2 |
| high | Process supervisors restart the child but nothing reconnects the transport client; javadocs describe a mechanism that does not exist | SimpleXSubprocess.java:27-31, SimpleXAdapter.java:171-235, SignalSubprocess.java:211-235, SignalJsonRpcClient.java:189-215 | 05-module-infochat-messaging-adapter.md#F5 |
| high | `quarantine_review` catch-up advances the cursor without delivering the channel's only side effect; the live path can permanently skip actionable events | QuarantineReviewReconciler.java:28-31, QuarantineReviewListener.java:143-156, 260-273 | 01-architecture.md#F2 |
| high | Signal group inbound is delivered while group outbound is rejected PERMANENT | SignalJsonRpcClient.java:269-276, 245-250, SignalGroupHandler.java:161-167 | 05-module-infochat-messaging-adapter.md#F6 |
| high | Spec contradicts itself on `price_snapshot` write privileges; V17 implements the wider grant | docs/spec/security.md:1032-1034, docs/spec/schema.md:587, V17__price_snapshot.sql:85 | 01-architecture.md#F4 |
| medium | `/export` ships all pages in a single message, defeating the per-message size cap | ExportCommandHandler.java:98-109, 147-159 | 07-module-infochat-provider.md#F8 |
| medium | `/invite list` shows an 8-char prefix but `/invite revoke` requires the full UUID | InviteCommandHandler.java:357, 474-488, 513-519 | 07-module-infochat-provider.md#F9 |
| medium | `/stop`'s pg_cancel_backend path and the statement_timeout safety net are unwired | InFlightTracker.java:36, CancellationService.java:77-81, chat/tool/*.java | 07-module-infochat-provider.md#F5 |
| medium | Design notes document an `infochat.profile` config key that deliberately does not exist | docs/design/07-deployment.md:65, 103, 119, 135, 310, docs/design/01-architecture.md:629, docs/design/05-llm-and-embeddings.md:15, docs/design/02-schema.md:1378 | 01-architecture.md#F7 |
| medium | infochat_admin is a paper principal — the documented operator escape hatches do not work | V2__roles.sql:65 (plus comments in V3, V5, V6, V12) | 02-module-infochat-core.md#F2 |
| medium | Misleading comment about `Optional<String>` empty-string mapping for API keys | OpenAiCompatibleProvider.java:120-126, AnthropicProvider.java:106-107, OpenAiCompatibleEmbeddingProvider.java:89-95 | 04-module-infochat-llm-adapter.md#F4 |
| medium | Nostr `/add-source` probe unimplemented; wss URLs get a misleading SSRF-blocked error | AddSourceCommandHandler.java:163-167, UrlProbe.java:74-108 | 07-module-infochat-provider.md#F6 |
| medium | Per-source UNKNOWN auto-disable notifications coalesce across sources | PerSourceUnknownTracker.java:99-105 | 06-module-infochat-collector.md#F5 |
| medium | `PER_TASK_BASE_URL_KEYS` as `Map<ModelTask, String>` is semantically misleading | LlmRouterStartupGuard.java:116-123 | 04-module-infochat-llm-adapter.md#F5 |
| medium | price_snapshot lacks the spec-committed FK to asset_config | V17__price_snapshot.sql:35-52 | 02-module-infochat-core.md#F1 |
| medium | Re-eval BENIGN release: missing `new_post` / `quarantine_review` NOTIFYs and skipped pipeline stages | ReEvaluationJob.java:129-150, 198-218 | 06-module-infochat-collector.md#F3 |
| medium | SignalAdapter.start() throws IllegalStateException for transport failures the SPI reports as MessagingException | SignalAdapter.java:208-234 | 05-module-infochat-messaging-adapter.md#F9 |
| medium | Tests freeze exception message text that the production contract declares rewordable | SsrfGuardedHttpClientTest.java:66, 80, 90, 104, 136, 174, 196, 212, 228, 358, 429 | 03-module-infochat-ssrf.md#F2 |
| medium | The "collector must not depend on messaging-adapter" guard is documented as build-enforced and CI-verified; neither mechanism exists | docs/design/09-reference.md:41, pom.xml | 01-architecture.md#F5 |
| medium | The spec-committed progress-notification pipeline has no implementation and its SPI surface has zero production consumers | ProgressNotifier.java:37, SummaryCommandHandler.java:126 | 01-architecture.md#F8 |
| medium | `TranslationProvider` lives in the wrong module against spec and both design notes | infochat-messaging-adapter/.../TranslationProvider.java:34, infochat-provider/.../LlmTranslationProvider.java:32 | 01-architecture.md#F6 |
| medium | `validateLocalOnlyConfiguration` is `public static` for test access, leaking a test seam | LlmRouterStartupGuard.java:179 | 04-module-infochat-llm-adapter.md#F3 |
| medium | WebSocket public surface has no module-local tests; the unlock-on-throw guarantee is untested anywhere | SsrfGuardedHttpClient.java:599-614, infochat-ssrf/src/test | 03-module-infochat-ssrf.md#F3 |
| low | AdminReviewTtlJob's inner join defeats the quarantine denormalization | AdminReviewTtlJob.java:78-85 | 06-module-infochat-collector.md#F10 |
| low | Confirm-cancel sweep is skipped by earlier short-circuits | InboundRouter.java:487-490, 530-546, 548-569 | 07-module-infochat-provider.md#F13 |
| low | Dead `resolve(Path)` stubs with javadoc citing completed tickets that chose a different mechanism | SimpleXIdentity.java:28-31, SignalIdentity.java:28-31, SignalAdapter.java:129-133 | 05-module-infochat-messaging-adapter.md#F10 |
| low | Inconsistent upstream-input tolerance in the Bluesky/Reddit parsers | BlueskyResponseParser.java:82-83, RedditResponseParser.java:58-66 | 06-module-infochat-collector.md#F11 |
| low | Interrupted-await classification drifts between adapters (TRANSIENT vs PERMANENT) | SimpleXWebSocketClient.java:188-191, SignalJsonRpcClient.java:329-333 | 05-module-infochat-messaging-adapter.md#F11 |
| low | Javadoc references tickets, migrations-as-changes, and a provider-module class | AuditAction.java:21-25, AuditLogWriter.java:14-20 | 02-module-infochat-core.md#F5 |
| low | Misleading fallback replies on argument and in-flight errors | BanCommandHandler.java:186-193, RetryCommandHandler.java:175-177 (plus six sibling handlers) | 07-module-infochat-provider.md#F12 |
| low | Pagination-cap saturation counter not implemented | BlueskyFetcher.java:78-108, RedditFetcher.java:70-107 | 06-module-infochat-collector.md#F12 |
| low | Scheme allowlist comparison is case-sensitive, inconsistent with the module's own origin comparison | SsrfGuardedHttpClient.java:442-445 | 03-module-infochat-ssrf.md#F5 |
| low | Stale test name and comment claim WebSocket support lives in a future separate wrapper | SsrfGuardedHttpClientTest.java:72-83 | 03-module-infochat-ssrf.md#F6 |
| low | The Fetcher SPI does not carry the output-type discriminator the spec commits to | Fetcher.java:13-18 | 01-architecture.md#F9 |
| low | `warnedUnknownDefault` comment is imprecise about its mechanism | LlmRouter.java:94-100 | 04-module-infochat-llm-adapter.md#F6 |

## Synthesizer notes

- Severity discrepancy on the consolidated `@NonNull` row: 04-module-infochat-llm-adapter.md#F1 rated it high; 01-architecture.md#F10 and 02-module-infochat-core.md#F6 rated it low. The higher severity is used per the consolidation rule; the developer may want to revisit whether a mechanical-cleanup item warrants high.
- Severity discrepancy on the consolidated defensive-null-check row: 04-module-infochat-llm-adapter.md#F2 rated its instance high; 06-module-infochat-collector.md#F9 rated its instances low. Higher severity used. The two were merged on shared root cause (§7-violating guards on values NullAway already proves non-null) and shared fix shape (delete the dead guards).
- The `price_snapshot` UPDATE-grant spec contradiction (01-architecture.md#F4) was also independently noted in 02-module-infochat-core.md's synthesizer observations (not as a finding); the single consolidated row is sourced to the architecture report.
- The `TranslationProvider` module-placement drift (01-architecture.md#F6) was also independently noted in 04-module-infochat-llm-adapter.md's observations; single row sourced to the architecture report.
- 03-module-infochat-ssrf.md's observations note that the message-text assertion drift (03#F2) extends into infochat-collector test files (`NostrSsrfTest`, `NostrSsrfIT`); the collector report did not flag those sites independently, so a fix ticket for 03#F2 should sweep both modules.
- 03-module-infochat-ssrf.md's observations note that 03#F1's lock-hold amplification is worsened by the collector's `NostrRelayConnection` holding the `PinnedDial` across the full WebSocket handshake — relevant when prioritizing that fix.
- 06-module-infochat-collector.md notes that F1 (no dedup) and F4 (overlapping pollers) interact: the FetchScheduler overlap consequence in F4 is absorbed once F1's dedup lands. The two remain separate findings (different root causes and fixes).
- 01-architecture.md#F3 (missing pruner) and 06-module-infochat-collector.md#F10 (TTL join defeats the drop-survival denormalization) are coupled: landing the pruner without the TTL-join fix activates the latent defect F10 describes. Captured in CT6.
- 05-module-infochat-messaging-adapter.md's observations flag that the inbound-handler threading contract is unstated in the SPI javadoc; whichever side fixes 05#F1, the SPI contract should document the assumption.
- 07-module-infochat-provider.md's observations flag two cross-module items not entered as findings by any report: the `CommandHandler.handle(ScopeRef, String)` SPI carrying no actor identity (forcing the `InboundContext` side channel), and V5's `redact_contact_id()` being a pass-through stub leaving `audit_log_view` unredacted at the DB tier (mitigated handler-side). Both may warrant deeper investigation; neither traces to a per-target finding, so neither appears in the tables above.
