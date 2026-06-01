# Deep code review — consolidated summary

**Run directory:** .reviews/deep-code-review/opus-47/
**Date:** 2026-06-01 12:00
**Synthesizer:** review-synthesizer (opus)

## Coverage

- **Reports consumed:** 7
  - architecture: yes (01-architecture.md)
  - module-infochat-core: yes (02-module-infochat-core.md)
  - module-infochat-ssrf: yes (03-module-infochat-ssrf.md)
  - module-infochat-llm-adapter: yes (04-module-infochat-llm-adapter.md)
  - module-infochat-messaging-adapter: yes (05-module-infochat-messaging-adapter.md)
  - module-infochat-collector: yes (06-module-infochat-collector.md)
  - module-infochat-provider: yes (07-module-infochat-provider.md)

## Top priority

1. [critical] SECURITY — Anthropic auth/version headers use the wrong names (`x-anthropic-version` and `anthropic-api-key` instead of `anthropic-version` and `x-api-key`); every production Anthropic call will 401, and the test was written to lock in the bug.
   - Sources: 04-module-infochat-llm-adapter.md#F1, 04-module-infochat-llm-adapter.md#F2
   - Why first: Hard-breaks an entire production LLM provider plus a §8 test-integrity violation (test pinned to the wrong contract); two `critical`/`high` findings against the same external contract.

2. [critical] SECURITY — Single `volatile MessagingAdapter replyTarget` makes the last-registered adapter the reply path for every inbound, so in a SimpleX+Signal deployment a SimpleX user's reply ships through Signal (cross-adapter user spoofing + outbound to unrelated identity).
   - Sources: 07-module-infochat-provider.md#F1
   - Why first: Hard-violates the spec-committed cross-adapter isolation invariant (D46, security.md §Per-adapter admin threat profile) in the exact multi-adapter shape M1 commits to ship.

3. [critical] SECURITY — Application DB connections use the `infochat` superuser, not the per-service roles; the entire spec-committed three-role least-privilege model is decorative.
   - Sources: 01-architecture.md#F1
   - Why first: Disables every defense-in-depth layer the spec attaches to the role split (audit_log_view, quarantine procedure carve-outs, Invariant 4/10 enforcement); every additional code path that lands accumulates more cleanup the eventual fix has to sweep.

4. [critical] MAINTAINABILITY-RULES-DRIFT — `infochat.reeval.*` config keys are declared only in test resources; Collector startup will fail in every operator profile (laptop, vps, pi, remote-llm).
   - Sources: 06-module-infochat-collector.md#F1
   - Why first: Hard production startup failure; the entire re-evaluation policy in security.md §Re-evaluation job is dead until the keys land in main config.

5. [critical] MAINTAINABILITY-RULES-DRIFT — Only `/zcash` and `/monero` are wired as `CommandHandler`s; any third asset added to `bootstrap-assets.json` is invisible to the slash dispatcher, contradicting the spec's per-asset extensibility commitment.
   - Sources: 07-module-infochat-provider.md#F2
   - Why first: Permanently constrains the operator-config-driven asset extensibility spec commitment; probation gate also leaks (probation user passes the gate then receives "Unknown command").

## Cross-cutting themes

### CT1. NOTIFY payload construction is inconsistent across producers and the `quarantine_review` channel contract is partially broken

- **Pattern:** Multiple independent producers emit NOTIFY payloads with hand-written string concatenation rather than `jsonb_build_object()`; the `quarantine_review` channel's emission points do not match the spec ("PENDING fires on insert" and "every state-machine transition fires"). The producers drift in escaping discipline (some escape, some do not) and in spec compliance (admin-driven APPROVED/REJECTED never fire NOTIFY; PENDING fires at the wrong stage).
- **Where it appears:** 01-architecture.md#F2 (approve/reject procedures do not fire `quarantine_review`), 01-architecture.md#F3 (`QuarantineNotifyEmitter` Java string-concat without escape), 02-module-infochat-core.md#F4 (V21/V25 `pg_notify` raw `||` concat), 06-module-infochat-collector.md#F4 (`emitQuarantineNotifyForPendingRows` fires PENDING at the wrong stage and re-fires per Stage 2 verdict).
- **Suggested system-level fix:** Treat `QuarantineNotifyEmitter` as the lone source-of-truth for the channel's wire format (closed enums for `target_kind`/`new_status`; `jsonb_build_object`-equivalent escaping). Move PENDING NOTIFY into `QuarantineDao.insert` so it fires on row creation. Add NOTIFY emission to `approve_quarantine` and `reject_quarantine` procedures. Convert the V21/V25 `pg_notify('new_post', ...)` calls to `jsonb_build_object(...)::text`. Align the Provider-side parser to the canonical payload shape (Jackson rather than regex).

### CT2. `@NonNull`/`@Nullable` parameter contracts missing on SPI/public surfaces; defensive null checks behind already-non-null inputs

- **Pattern:** Multiple modules ship public/protected constructors and SPI methods whose reference-type parameters lack JSpecify annotations, while simultaneously carrying defensive null checks behind contracts that already guarantee non-null. This is both halves of the §7 + §7a engineering-rule pair violated symmetrically.
- **Where it appears:** 04-module-infochat-llm-adapter.md#F6 (LlmRouter / OpenAiCompatibleProvider defensive nulls), 04-module-infochat-llm-adapter.md#F7 (LlmRouter public constructors missing annotations), 05-module-infochat-messaging-adapter.md#F6 (MessagingException public constructors missing annotations).
- **Suggested system-level fix:** Run `scripts/lint-contracts.py` across every public/protected method in `infochat-llm-adapter` and `infochat-messaging-adapter` to surface every missing annotation; add the annotations; in the same pass remove the defensive null checks that the now-explicit `@NonNull` contracts make impossible. This is a mechanical fix once the lint surface is bounded.

### CT3. Stale ticket-time comments and pre-relocation wiring assumptions persist in the codebase

- **Pattern:** Multiple files carry comments or javadoc that describe the codebase as it existed at an earlier ticket but no longer matches the current shape. The drift is documented in two directions: comments that name code paths that have since moved (V16 / V7 / SsrfGuardedHttpClient) and comments that name shims that were intentionally retained (IpBlocklist M1-025 compat). Both are the same maintainability hazard from opposite angles.
- **Where it appears:** 02-module-infochat-core.md#F2 (V7 references non-existent `infochat_listen` role), 02-module-infochat-core.md#F3 (V16 comment claims notifier still lives in collector), 03-module-infochat-ssrf.md#F1 (IpBlocklist M1-025 compat shim), 03-module-infochat-ssrf.md#F5 (class javadoc claims ws/wss rejected while supported).
- **Suggested system-level fix:** Sweep the codebase for ticket-id references in long-lived comments and javadoc (memory `feedback_no_plan_refs_in_docs.md` already codifies the spec/design rule; extend it to source comments). Each one is either a fossil to delete or a current-state comment to rewrite — the as-written form rots and now actively misleads.

### CT4. Duplicate hand-written JSON / hex / SHA helpers across modules

- **Pattern:** Stringly-typed helpers (JSON quoting, hex encoding, SHA-256) are reimplemented per call site rather than extracted to a shared utility, with several variants drifting in their escape sets / encoding choices.
- **Where it appears:** 06-module-infochat-collector.md#F10 (two distinct SHA-256 hex helpers — `String.format("%02x")` vs `HexFormat.of()`), 07-module-infochat-provider.md#F12 (five drifting JSON-quoting helpers across handlers).
- **Suggested system-level fix:** Extract a `JsonText.quote(String)` helper and a `Hex.sha256(byte[])` helper to `infochat-core` (or a shared utility). Replace every call site. The five JSON-escape variants collapse to one canonical set; the two SHA helpers collapse to the `HexFormat` form.

### CT5. Speculative SPI shape / contract drift between similar adapters

- **Pattern:** Adapter implementations and SPI surfaces inconsistently honour their own contracts: the same semantic state classifies differently across adapters, capability flags drift from design notes, and SPI methods declare exception contracts they do not honour.
- **Where it appears:** 05-module-infochat-messaging-adapter.md#F2 (SignalAdapter and InMemoryAdapter take incompatible dispatch shapes for the same membership SPI), 05-module-infochat-messaging-adapter.md#F3 (SimpleX `supportsTypingIndicator=true` contradicts design §6.4.2 false), 05-module-infochat-messaging-adapter.md#F4 (codec validators raise `IllegalStateException`/`IllegalArgumentException` past `throws MessagingException`), 05-module-infochat-messaging-adapter.md#F5 ("not connected" is TRANSIENT in Signal, PERMANENT in SimpleX), 05-module-infochat-messaging-adapter.md#F7 (InMemoryAdapter `supportsCodeFormatting=false` drifts from design §6.6 true).
- **Suggested system-level fix:** Introduce a cross-adapter contract test suite (architecture-lens already flags this in 05's synthesizer-relevant observations). Pick a single classification per semantic state (e.g. "not connected" → PERMANENT), align capability flags to design notes (or vice versa with a design amendment in the same commit), and force codec/encoder validators to throw the SPI's checked exception. The contract test is the forcing function so future adapter additions cannot regress.

## Findings by category

### SECURITY (8)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| critical | Anthropic auth/version headers use the wrong names | infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java:139,142 | 04-module-infochat-llm-adapter.md#F1 |
| critical | Application DB connections use the superuser, not the per-service roles | infochat-collector/src/main/resources/application.properties:7-13, infochat-provider/src/main/resources/application.properties:12-14, infochat-core/src/main/resources/db/migration/V2__roles.sql:32-39 | 01-architecture.md#F1 |
| critical | Single reply-target makes multi-adapter outbound route to the wrong adapter | infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java:284,604-628, AdapterRegistry.java:254-266 | 07-module-infochat-provider.md#F1 |
| high | `/stop` is a no-op in group scope and uses the wrong scope key in DM | infochat-provider/src/main/java/app/zcat/infochat/provider/command/StopCommandHandler.java:62-69,97-100 | 07-module-infochat-provider.md#F3 |
| medium | extraHeaders leak across cross-origin redirects | infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:334 | 03-module-infochat-ssrf.md#F3 |
| medium | Hand-rolled JSON arg parser in ChatAgent silently mangles non-trivial tool payloads | infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java:251-305 | 07-module-infochat-provider.md#F8 |
| medium | `/promote` reads the actor row without `FOR UPDATE`, leaving a TOCTOU window with `/revoke-admin` | infochat-provider/src/main/java/app/zcat/infochat/provider/command/PromoteCommandHandler.java:90-93,158-169 | 07-module-infochat-provider.md#F6 |
| medium | `/save` accepts unbounded personal-tag strings and counts | infochat-provider/src/main/java/app/zcat/infochat/provider/command/SaveCommandHandler.java:305-321,265-284 | 07-module-infochat-provider.md#F10 |
| medium | `SearchPostsTool.Duration.parse` throws past the tool dispatcher's exception filter | infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java:46-48, ChatToolDispatcher.java:137-145 | 07-module-infochat-provider.md#F11 |
| medium | Upstream-supplied pagination cursors are concatenated into URLs without encoding | infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyFetcher.java:110-117, infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/reddit/RedditFetcher.java:108-114 | 06-module-infochat-collector.md#F6 |

### PERFORMANCE (5)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| high | Connection-per-step churn in the inbound path | infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java:300-553 | 07-module-infochat-provider.md#F5 |
| high | SimpleXAdapter handle table grows unbounded — DOS / memory leak | infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java:88-91,255-256,282-284 | 05-module-infochat-messaging-adapter.md#F1 |
| medium | `LlmOutputSanitizer` compiles 26 patterns per call | infochat-provider/src/main/java/app/zcat/infochat/provider/llm/LlmOutputSanitizer.java:187-209 | 07-module-infochat-provider.md#F9 |
| medium | `latestPublishedAtEpochSeconds` on every Nostr reconnect lacks supporting index | infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java:440 | 06-module-infochat-collector.md#F5 |
| medium | Per-call ExecutorService spawn in readBounded | infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:420-424,471 | 03-module-infochat-ssrf.md#F4 |

### SIMPLIFICATION (4)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| medium | Dead semaphores in TaggerWorker and EntityExtractorWorker | infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TaggerWorker.java:160-205, infochat-collector/src/main/java/app/zcat/infochat/collector/eval/entity/EntityExtractorWorker.java:153-193 | 06-module-infochat-collector.md#F7 |
| low | Duplicate per-handler JSON quoting helpers | infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java:462-486, UnbanCommandHandler.java, GrantAdminCommandHandler.java:368-392, ApproveGroupCommandHandler.java:331-351, SourceUpsertService.java | 07-module-infochat-provider.md#F12 |
| low | `IngestSpisLoadTest` checks only what the compiler already guarantees | infochat-core/src/test/java/app/zcat/infochat/core/ingest/IngestSpisLoadTest.java:20-39 | 02-module-infochat-core.md#F5 |
| low | Two distinct SHA-256-to-hex helpers | infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapLoader.java:285-289, infochat-collector/src/main/java/app/zcat/infochat/collector/outbox/PostPersister.java:168-177 | 06-module-infochat-collector.md#F10 |
| low | Unused `Optional` import | infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java:24 | 04-module-infochat-llm-adapter.md#F9 |

### MAINTAINABILITY-RULES-DRIFT (28)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| critical | Hardcoded `/zcash`, `/monero` command handlers break `bootstrap-assets.json` extensibility | infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetCommandRouter.java:24-55 | 07-module-infochat-provider.md#F2 |
| critical | `infochat.reeval.*` config keys are declared only in test resources | infochat-collector/src/main/resources/application.properties (missing), referenced from infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java:74-86, PerSourceUnknownTracker.java:41-50, AdminReviewTtlJob.java:51-57 | 06-module-infochat-collector.md#F1 |
| critical | NEEDS_REVIEW cap-exhaustion transition is unreachable in production | infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java:107-112,282-293 | 06-module-infochat-collector.md#F2 |
| high | `/help` ignores the spec-promised per-tier filtering | infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java:46-74 | 07-module-infochat-provider.md#F4 |
| high | `approve_quarantine` and `reject_quarantine` do not fire `quarantine_review` NOTIFY | infochat-core/src/main/resources/db/migration/V25__quarantine_procedure_remediation.sql:46-65, 67-104 | 01-architecture.md#F2 |
| high | Anthropic auth-header test asserts the wrong header names | infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/AnthropicProviderTest.java:133-134,154-157 | 04-module-infochat-llm-adapter.md#F2 |
| high | Embedding provider silently breaks the SPI's size-equals-input contract | infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleEmbeddingProvider.java:162-201 | 04-module-infochat-llm-adapter.md#F3 |
| high | `EmbeddingResult` exposes a mutable array via a record value type | infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/EmbeddingResult.java:14 | 04-module-infochat-llm-adapter.md#F4 |
| high | IpBlocklist M1-025 compatibility-shim constructor | infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/IpBlocklist.java:85-87 | 03-module-infochat-ssrf.md#F1 |
| high | Kind-6 repost edge to_post is never resolvable to a real post | infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/Kind6Handler.java:142-153,164-167; infochat-collector/src/main/java/app/zcat/infochat/collector/outbox/PostPersister.java:108-119; infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/GetReferencesTool.java:67-80 | 06-module-infochat-collector.md#F3 |
| high | `MessagingAdapter.onMembershipEvent` is a confused SPI method that creates two incompatible dispatch shapes | infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java:163-174 | 05-module-infochat-messaging-adapter.md#F2 |
| high | Stage 2 `emitQuarantineNotifyForPendingRows` re-fires PENDING on every verdict | infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/Stage2VerdictHandler.java:269-281; infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/QuarantineDao.java:66-90 | 06-module-infochat-collector.md#F4 |
| medium | "Adapter not connected" classifies inconsistently between Signal (TRANSIENT) and SimpleX (PERMANENT) | infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java:330-338, infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java:348-355 | 05-module-infochat-messaging-adapter.md#F5 |
| medium | Adapter SPI methods leak `IllegalStateException` / `IllegalArgumentException` past the `throws MessagingException` contract | infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java:178-183, SimpleXMessageCodec.java:226-232 | 05-module-infochat-messaging-adapter.md#F4 |
| medium | Defensive `catch (RuntimeException)` between internal classes in AssetSnapshotFetcher | infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java:188-198 | 06-module-infochat-collector.md#F8 |
| medium | Defensive null checks inside internal trust boundary | infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java:144-146,182 | 04-module-infochat-llm-adapter.md#F6 |
| medium | Hidden "null" string sentinel in `MicroProfileConfigReader.get` | infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java:399 | 04-module-infochat-llm-adapter.md#F5 |
| medium | IPv6 URL-literal hosts cannot pass canonicalizeHost | infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:269-279 | 03-module-infochat-ssrf.md#F2 |
| medium | `MembershipEventHandler` writes audit rows AFTER state mutation and swallows failures | infochat-provider/src/main/java/app/zcat/infochat/provider/group/MembershipEventHandler.java:105-127 | 07-module-infochat-provider.md#F7 |
| medium | `MessagingException` public constructors lack `@NonNull` / `@Nullable` annotations | infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingException.java:22-30 | 05-module-infochat-messaging-adapter.md#F6 |
| medium | NOTIFY payload string-concatenation in `QuarantineNotifyEmitter` does not escape inputs | infochat-collector/src/main/java/app/zcat/infochat/collector/notify/QuarantineNotifyEmitter.java:39-51 | 01-architecture.md#F3 |
| medium | Public constructors missing JSpecify nullability annotations | infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java:109,134 | 04-module-infochat-llm-adapter.md#F7 |
| medium | Router tightly coupled to concrete provider impls via `instanceof` chain | infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java:298-315 | 04-module-infochat-llm-adapter.md#F8 |
| medium | SECURITY DEFINER procedures drop the spec-mandated `actor_contact_id` / `actor_adapter` denormalization | infochat-core/src/main/resources/db/migration/V24__identity_audit_remediation.sql:44-53; infochat-core/src/main/resources/db/migration/V25__quarantine_procedure_remediation.sql:58-60,100-101 | 02-module-infochat-core.md#F1 |
| medium | SimpleX `supportsTypingIndicator=true` contradicts design §6.4.2 | infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java:288-298 | 05-module-infochat-messaging-adapter.md#F3 |
| low | `AdapterRegistry` parses `infochat.adapters` CSV without duplicate-name detection | infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java:150-159 | 01-architecture.md#F5 |
| low | Asset-command tokens lowercased with the JVM-default locale | infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetHandler.java:156,160 | 07-module-infochat-provider.md#F13 |
| low | Class-level javadoc claims ws/wss are rejected while the class supports them | infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:41-47 | 03-module-infochat-ssrf.md#F5 |
| low | DAG documentation in `docs/design/09-reference.md` §9.1 disagrees with the actual sibling-module poms | docs/design/09-reference.md:33-38 | 01-architecture.md#F4 |
| low | `dispatchKey` is per-tick, not per-startup | infochat-collector/src/main/java/app/zcat/infochat/collector/fetch/FetchScheduler.java:408-419 | 06-module-infochat-collector.md#F9 |
| low | Indistinct constructor-validation error messages | infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java:194-205 | 03-module-infochat-ssrf.md#F6 |
| low | `InMemoryAdapter` capability `supportsCodeFormatting=false` drifts from design §6.6 | infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapter.java:61 | 05-module-infochat-messaging-adapter.md#F7 |
| low | `pg_notify` payload built by raw string concatenation in V21 / V25 | infochat-core/src/main/resources/db/migration/V21__quarantine_admin.sql:74-75; infochat-core/src/main/resources/db/migration/V25__quarantine_procedure_remediation.sql:62-63 | 02-module-infochat-core.md#F4 |
| low | V16 grant-block comment hard-codes a pre-relocation wiring assumption | infochat-core/src/main/resources/db/migration/V16__admin_notification_state.sql:67-73 | 02-module-infochat-core.md#F3 |
| low | V7 comment references an `infochat_listen` role that the role-creation migration never created | infochat-core/src/main/resources/db/migration/V7__joins_post.sql:212-214 | 02-module-infochat-core.md#F2 |

## Synthesizer notes

- Two findings in different reports describe related but distinct root causes around quarantine NOTIFY (01-architecture.md#F2 — admin procedures do not fire `quarantine_review` at all; 06-module-infochat-collector.md#F4 — Stage 2 fires PENDING at the wrong stage). Kept as two findings because the fixes are independent (the admin procedures need new NOTIFY statements; the Stage 2 path needs the PENDING emit moved earlier). Both surface jointly in CT1.
- The architecture report (01) and the messaging-adapter report (05) both touch the `AdapterRegistry` start path: 01#F5 (duplicate-name detection) and 07#F1 (single replyTarget). Different root causes; kept as separate findings. CT5 captures the broader adapter-SPI-consistency theme.
- Several reports' "Synthesizer-relevant observations" sections raise additional cross-module concerns the per-target reviewers explicitly flagged as architecture-lens-scope (e.g. `LlmRouter` resolving providers that cannot serve the requested task, missing remote-embedding-switch startup log, `MAX_OUTBOUND_TEXT_BYTES` vs `maxMessageBytes` lockstep). These are noted but not entered as new findings because no per-target reviewer surfaced them as numbered F-findings; they appear in the per-report observation sections rather than the headline lists.
- The category MAINTAINABILITY-RULES-DRIFT carries the bulk of findings (28 of 47 total). The reviewers' choices to classify cross-module contract drift, SPI shape inconsistency, and stale comments under this category — rather than splitting into a separate "SPEC-DRIFT" or "DESIGN-DRIFT" — is observed without re-categorization.
