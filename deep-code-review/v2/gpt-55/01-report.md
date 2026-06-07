# Deep Code Review Report

**Date:** 2026-06-07  
**Reviewer:** GPT-5.5  
**Scope:** `infochat-core`, `infochat-collector`, `infochat-provider`, `infochat-llm-adapter`, `infochat-messaging-adapter`, `infochat-ssrf`.

## Executive Summary

This pass focused on security issues, performance issues, design flaws, simplification opportunities, and test/spec drift. The review used repository-wide scans plus targeted line-numbered inspection of high-risk code paths: LLM routing/providers, datasource secrets, messaging startup, SSRF networking, partition lifecycle, summary/chat data flows, rate limiting, audit/export redaction, migrations, and tests.

Inventory covered 568 Java files, 317 Java test files, and 37 Flyway migrations across the six main modules. Findings below are evidence-backed; weak or stale handoff claims are called out instead of repeated.

The dominant risk pattern is incomplete runtime validation. Several startup checks prove a name or property exists, but not that the runtime behavior is usable: LLM routes resolve to providers that later throw for the task, bootstrap-admin properties are required but rows are not created, adapter startup logs failures but does not prove any transport is working, and partition scheduling creates only one future month instead of reconciling missing/current months.

## Security Review

### S1. Known Database Password Fallbacks In Production-Like Config

**Severity:** High  
**Evidence:** `infochat-provider/src/main/resources/application.properties:21-23`, `infochat-collector/src/main/resources/application.properties:14-22`, `docker-compose.yml:8-17`, `docs/spec/deployment.md:116-118`, `docs/design/07-deployment.md:299-313`

Provider defaults `quarkus.datasource.password` to `${INFOCHAT_PROVIDER_PASSWORD:infochat-dev}`. Collector defaults both service and owner datasource passwords to `infochat-dev` when env vars are absent. Docker Compose also defaults Postgres to `infochat-dev`.

The deployment docs treat DB credentials as operator-provided secrets. The runtime config still allows services to boot with a known credential if the environment is missing.

**Risk:** A production-like deployment copied from local config can start with known passwords. The collector owner datasource fallback is especially sensitive because it is used for Flyway and partition DDL.

**Fix:** Remove production fallback values. Keep dev/test defaults scoped to `%dev`/`%test` or a local `.env.example`, and fail startup when required production secret env vars are absent.

### S2. LLM Error Logging Can Leak Prompt-Adjacent Data

**Severity:** Medium-high  
**Evidence:** `OpenAiCompatibleProvider.java:215-219`, `OpenAiCompatibleProvider.java:264-272`, `AnthropicProvider.java:165-172`, `AnthropicProvider.java:203-212`, `OpenAiCompatibleEmbeddingProvider.java:159-164`, `OpenAiCompatibleEmbeddingProvider.java:180-192`, `OpenAiCompatibleEmbeddingProvider.java:229-238`

OpenAI-compatible chat and embedding providers log response body previews on non-2xx or malformed responses. Anthropic extracts and logs `error.message`, falling back to a body preview. The preview is capped at 200 characters, but sensitivity is not reduced by a short cap.

LLM provider errors can echo request-derived details, prompt text, post excerpts, tool outputs, or provider-side diagnostics.

**Risk:** User content and prompt-adjacent data can reach operator logs outside the audit/redaction model.

**Fix:** Log status, provider, task, retry-after, and a sanitized error category. Keep body previews behind an explicit debug flag that is off by default and redacted.

### S3. Admin Bootstrap Is A Config Gate, Not A Bootstrap

**Severity:** Medium  
**Evidence:** `AdapterRegistry.java:227-262`, `AdapterRegistry.java:235-238`, `infochat-provider/src/main/resources/application.properties:87-97`, `docs/spec/deployment.md:159-162`

`AdapterRegistry` requires the union of configured `infochat.adapters.<name>.admin` values to be non-empty. But the code comment says the actual `@Startup` admin-bootstrap bean is deferred, and Provider properties say the property is currently a startup invariant only.

The deployment spec says Provider ensures bootstrap-admin users exist and have `is_admin=true`.

**Risk:** A fresh deployment can pass the config gate but still have no admin row unless seeded out of band.

**Fix:** Implement a Provider startup bootstrap that creates/updates the `(adapter, contact_id)` user row and audit row, or change the spec/gate language until that exists.

### S4. SimpleX Admin Notification Is Log-Only

**Severity:** Medium  
**Evidence:** `ProductionAdapterBeans.java:69-77`, `ProductionAdapterBeans.java:145-148`

The SimpleX adapter constructor receives an admin-notification `Consumer<String>`. In production wiring, the consumer logs a `WARN` instead of sending through a unified admin notification surface.

**Risk:** Adapter failure notifications may never reach administrators through the messaging channel they operate from. This is not a direct data-exposure issue, but it weakens incident detection.

**Fix:** Wire a real admin notification channel or explicitly expose this as an operational limitation with metrics/alerts.

### S5. Rate-Cap Buckets Are An Availability Boundary Without Hard Capacity

**Severity:** Medium  
**Evidence:** `RateCapBucket.java:77-82`, `RateCapBucket.java:125-128`, `RateCapBucket.java:162-164`, `RateCapBucket.java:204-218`, `InboundRouter.java:355-369`

Contact and group rate-cap maps are `ConcurrentHashMap`s populated with `computeIfAbsent()` and cleaned by scheduled idle eviction. `InboundRouter` creates the contact bucket from adapter/contact identity before durable authorization lookup.

**Risk:** A compromised or low-trust adapter path can create many contact/group keys and pressure heap until idle eviction catches up.

**Fix:** Add hard map capacity, admission control, or LRU/approximate eviction. Emit metrics for bucket count, evictions, and rejected admissions.

## Reliability Review

### R1. LLM Routing Can Resolve Providers That Cannot Serve The Task

**Severity:** High  
**Evidence:** `LlmRouter.java:145-156`, `LlmRouter.java:174-179`, `LlmRouter.java:219-232`, `OpenAiCompatibleProvider.java:157-165`, `AnthropicProvider.java:104-111`, `infochat-provider/src/main/resources/application.properties:221-233`, `infochat-collector/src/main/resources/application.properties:339-357`

`LlmRouter` resolves per-task provider names, then falls back to the configured default or `openai-compatible`. Its startup assertion loops over `ModelTask.values()` and checks provider-name resolution only.

`OpenAiCompatibleProvider.configFor()` only supports `SECURITY_JUDGE`; `TAGGER`, `ENTITY`, `SUMMARIZER`, `CHAT_AGENT`, and `TRANSLATOR` throw `UnsupportedOperationException`. Provider calls `SUMMARIZER`, `CHAT_AGENT`, and `TRANSLATOR`; Collector config declares tagger/entity base URL/model but no provider override, so they can still resolve to `openai-compatible` and throw.

Remote Provider config selects Anthropic for chat/summarizer but only declares `base-url`, `provider`, and `max-tokens`; `AnthropicProvider.configFor()` requires `base-url`, `model`, and `max-tokens`.

**Risk:** Normal profiles can fail or degrade summary, chat, translation, tagger, and entity extraction at first real use even though startup route resolution passes.

**Fix:** Add provider capability/config validation that does not make a network call. Validate the tasks used by each service at startup. Complete base and profile-specific properties for all used tasks.

### R2. Adapter Startup Can Succeed With Zero Working Transports

**Severity:** High  
**Evidence:** `MessagingStartup.java:57-67`, `AdapterRegistry.java:294-296`, `docs/spec/deployment.md:165-178`, `ProductionAdapterActivationTest.java:110-128`

`MessagingStartup.startAllAdapters()` catches each adapter startup exception and continues. Tests explicitly assert that a startup failure does not abort the loop. That is correct for one failed adapter in a multi-adapter deployment, but there is no success count or failure path when all activated adapters fail.

The deployment spec says Provider readiness is ready when at least one enabled adapter is connected and not-ready when zero are connected. Repository search found no Provider SmallRye health/readiness implementation or health extension wiring.

**Risk:** Provider can complete startup with no usable messaging ingress/egress and appear operational unless external checks catch it.

**Fix:** Track connected/started adapter state. Expose readiness that is not-ready at zero connected adapters. In single-adapter deployments, startup or readiness must fail on that adapter failure.

### R3. Partition Provisioning Does Not Catch Up Current/Missed Months

**Severity:** High  
**Evidence:** `PartitionCreator.java:18-23`, `PartitionCreator.java:54-61`, `V30__partitions_202606_202607.sql:1-11`, `infochat-collector/src/main/resources/application.properties:120-127`, `PartitionCreatorTest.java:19-63`

The system intentionally has no default partition, so missing monthly partitions wedge inserts. V30 adds June and July 2026 as an immediate unblock and points to `PartitionCreator` as the durable mechanism.

`PartitionCreator.onTick()` provisions only `YearMonth.now(UTC).plusMonths(1)`. It does not provision the current month, previous missed months, or a catalog-derived missing window. Unit tests validate DDL strings for a provided month, not catch-up behavior after downtime.

**Risk:** If Collector is down long enough to miss the prior month-end provisioning run, restart can create the following month while current-month inserts still fail.

**Fix:** On each tick and startup, reconcile a window such as previous/current/next/next+1 months per table, using `CREATE TABLE IF NOT EXISTS` and catalog checks. Add tests for missed-current-month recovery.

### R4. Chat Sequence Allocation Can Race

**Severity:** Medium-high  
**Evidence:** `ChatSessionRepository.java:26-32`, `ChatSessionRepository.java:62-84`, `V18__chat_tables.sql:59-70`, `V18__chat_tables.sql:77-104`, `ChatSessionRepositoryTest.java:53-126`

`persistTurn()` reads `chat_session.next_seq`, inserts `chat_message`, then relies on an after-insert trigger to increment `next_seq`. The message primary key includes `seq`.

Existing tests validate sequential persistence and trigger counters, but not concurrent writes to the same session.

**Risk:** Two writers for the same `(user_id, scope_kind, scope_id)` can read the same `next_seq` and collide on the primary key. Current single-instance/in-flight controls reduce probability but do not make the repository method safe.

**Fix:** Allocate sequence atomically with `UPDATE chat_session SET next_seq = next_seq + 1 ... RETURNING next_seq - 1`, then insert using the returned value in the same transaction.

## Performance Review

### P1. Summary Query Caps After Loading All Eligible Bodies

**Severity:** High  
**Evidence:** `EligiblePostQuery.java:119-151`, `EligiblePostQuery.java:177-209`, `EligiblePostQuery.java:215-229`

`EligiblePostQuery.fetch()` calls `selectPosts()`, computes `total = all.size()`, and only then applies `subList(0, clusterCap)`. The SQL selects `p.body`, orders by recency, and has no `LIMIT`.

**Risk:** Large active scopes can materialize many rows and full bodies only to discard most of them, increasing DB read time, heap pressure, and summary latency.

**Fix:** Push the cap into SQL. If exact `totalBeforeCap` matters, use a separate `COUNT(*)`; otherwise fetch `clusterCap + 1` to know truncation occurred.

### P2. Chat Tool Results Have No Output Byte/Token Budget

**Severity:** Medium-high  
**Evidence:** `ChatToolDispatcher.java:38-46`, `ChatToolDispatcher.java:62-67`, `ChatToolDispatcher.java:157-160`, `ChatAgent.java:198-253`, `GetPostTool.java:43-66`, `RecallMemoryTool.java:42-69`

The dispatcher caps call count, input string length, list size, and `limit`, then caches successful tool output. It does not cap the size of `result`. `ChatAgent.runToolLoop()` appends each tool result into the next prompt until `MAX_TOOL_ITERATIONS` is reached.

`getPost` returns full `p.body`; `recallMemory` can return 50 summaries; search/list tools can return arrays. These are wrapped as untrusted content, which helps prompt-injection handling, but not prompt size, latency, cost, or provider context limits.

**Risk:** A normal or adversarial tool sequence can create very large prompts, causing slow requests, LLM errors, high cost, or memory pressure.

**Fix:** Add per-tool and aggregate per-turn result byte/token budgets. Truncate/summarize tool payloads before appending to conversation and before caching.

### P3. SSRF Pinning Serializes Outbound Connect Paths Globally

**Severity:** High  
**Evidence:** `PinnedDnsResolver.java:111-118`, `PinnedDnsResolver.java:146-154`, `SsrfGuardedHttpClient.java:347-397`, `SsrfGuardedHttpClient.java:599-607`

The DNS pinning provider has one static `ReentrantLock` and one static active pin map. HTTP guarded requests and WebSocket pin checks use the same lock around connection establishment.

**Risk:** One slow DNS path, TCP/TLS handshake, redirect chain, or WebSocket dial can block unrelated SSRF-guarded fetches in the same JVM.

**Fix:** Prefer a per-client/per-request resolver design if the HTTP stack allows it. If the global resolver must remain, add lock wait/hold metrics, stricter connection deadlines, and possibly separate worker isolation for long-lived dial classes.

### P4. Semantic Linking Query Needs Plan Validation

**Severity:** Medium  
**Evidence:** `LinkingJob.java:257-276`, `V11__post_embedding.sql:80-85`

The schema has an HNSW index on `post_embedding.embedding`, so this is not a missing-index issue. The remaining risk is query shape: the semantic candidate query self-joins embeddings over a time window, computes distance in the `SELECT`, filters on the same expression, orders by distance, and limits.

**Risk:** PostgreSQL/pgvector may not use the ANN index effectively for this shape, making linking expensive as partitions grow.

**Fix:** Capture `EXPLAIN (ANALYZE, BUFFERS)` on realistic data. If the index is not used, rewrite around an index-friendly nearest-neighbor subquery per driving post.

### P5. Export Collects Up To 10k Rows Per Table In Memory

**Severity:** Low-medium  
**Evidence:** `ExportDataCollector.java:137-142`, `ExportDataCollector.java:158-181`, `ExportDataCollector.java:188-198`

The export collector has a per-table row cap, but it collects every selected row for all positive-list tables into memory before pagination. The default is 10,000 rows per table.

**Risk:** Power users with large chat/audit/save histories can create large in-memory export payloads. This is bounded, but the bound is high and multiplicative across tables.

**Fix:** Stream rows into `ExportPaginator` or lower the default cap and make memory-budget behavior explicit.

## Architecture Review

### A1. LLM Configuration Ownership Is Split Across Modules And Services

The router lives in `infochat-llm-adapter`, but the actual task call sites live in Provider and Collector. Task properties are split across both service configs. No component currently owns the invariant "tasks this service uses are fully configured and executable."

**Recommendation:** Add service-local startup validators that enumerate service-used tasks. The shared adapter can expose provider capability/config validation, but Provider and Collector should own which tasks are required in each service.

### A2. Per-Adapter Resilience Needs A Zero-Success Boundary

The multi-adapter design correctly avoids aborting Provider when one adapter fails. The missing boundary is the all-failed case. Readiness belongs at the architecture level because it determines how orchestration treats a degraded deployment.

**Recommendation:** Model adapter state explicitly: configured, activated, started/connected, failed. Readiness should use that state, and metrics should expose partial degradation.

### A3. Single-Instance Assumptions Leak Into Repository-Level Correctness

The chat sequence race is a concrete example. v1 may be single-instance, but repository methods should remain race-safe under direct concurrent calls. This lowers future migration risk and makes tests less dependent on external in-flight guards.

**Recommendation:** Keep the topology decision, but make shared DB write methods atomic at the database boundary.

### A4. Partition Lifecycle Is Split Between One-Shot Migrations And A Narrow Scheduler

V30 fixed the immediate June/July wedge. `PartitionCreator` is intended to be the durable mechanism, but it only provisions one future month and has no startup/catalog catch-up.

**Recommendation:** Treat partition reconciliation as a catalog-driven lifecycle service, not just "create next month on a daily tick."

## Simplicity Review

### SM1. Tool Invariants Should Be Local To Each Tool

**Evidence:** `SearchPostsTool.java:50-66`, `SearchPostsTool.java:153-155`, `ChatToolDispatcher.java:214-219`

`SearchPostsTool` trusts its local `limit` argument and relies on `ChatToolDispatcher` to clamp it. The shipped path does clamp, so this is not currently an exposed unbounded SQL path. It is still a brittle invariant because future callers can bypass the dispatcher.

**Simplification:** Keep dispatcher-wide validation, but make every tool enforce its own min/max/count/string invariants before SQL.

### SM2. SSRF Pinning Has High Coordination Complexity

The SSRF module centralizes an important defense, but the JVM-global resolver requires a static lock and active pin lifecycle that leaks as a performance constraint into unrelated modules.

**Simplification:** If a future Java/HTTP client surface permits per-client DNS resolution, move pinning out of the static global resolver. Until then, expose the global-lock cost with metrics and keep the implementation isolated.

### SM3. Admin Bootstrap Should Be One Startup Concern

Bootstrap-admin validation is in `AdapterRegistry`, but row creation is deferred elsewhere. That split makes it easy to believe the deployment is bootstrapped when only config presence was checked.

**Simplification:** Put validation and row creation in one startup component, or make `AdapterRegistry` only handle adapter activation and move bootstrap concerns out entirely.

### SM4. Stale Comments Add False Security Context

**Evidence:** `ExportDataCollector.java:118-126`, `V31__service_role_login_and_audit_redaction.sql:56-108`

`ExportDataCollector` still says the V5 redactor stub returns input unchanged, but V31 replaces the function with real contact-id and secret redaction. The code still excludes `target_contact_id`, which may be a defense-in-depth choice, but the comment is outdated.

**Simplification:** Update the comment to describe the current reason for excluding `target_contact_id`, or include the redacted target if product requirements need it.

## Code Quality Review

### CQ1. Startup Guard Name Overstates What It Proves

`LlmRouter.assertAllTasksResolve()` sounds like a full task validation but only proves that provider names resolve. It does not prove provider support or required task config.

**Fix:** Rename it to `assertAllTaskProviderNamesResolve()` or extend it to validate provider task support/config.

### CQ2. Remote-LLM Defaults Are Partial

Provider `%remote-llm` config declares Anthropic `provider`, `base-url`, and `max-tokens` for chat/summarizer, but no task `model`. `AnthropicProvider.configFor()` requires `model`.

**Fix:** Add explicit `%remote-llm.infochat.llm.chat.model`, `%remote-llm.infochat.llm.summarizer.model`, API-key wiring, and validation.

### CQ3. AdapterRegistry Documentation Says Six Gates While Code Has Seven

**Evidence:** `AdapterRegistry.java:60-64`, `AdapterRegistry.java:227-262`

The class documentation still says six startup gates, while code implements Gate 7 for bootstrap-admin config.

**Fix:** Update the class documentation and related tests/spec wording.

### CQ4. Deployment Docs Drift From Runtime Flyway Ownership

**Evidence:** `docs/spec/deployment.md:38-40`, `infochat-provider/src/main/resources/application.properties:3-8`, `infochat-provider/src/main/resources/application.properties:69-74`

Deployment spec text says both services run Flyway on startup. Provider properties state production Provider does not run Flyway and Flyway is test-scoped.

**Fix:** Update deployment docs to match current ownership: Collector/owner datasource runs production migrations; Provider uses test Flyway only.

### CQ5. Partition Design Docs Drift From Monthly Implementation

**Evidence:** `docs/design/02-schema.md:806-812`, `V30__partitions_202606_202607.sql:17-18`, `PartitionDdl` behavior as tested by `PartitionCreatorTest.java:30-52`

Design docs describe nightly creation of `_yyyymmdd` partitions. Current migrations and tests use monthly `_YYYYMM` partitions.

**Fix:** Update design docs or add an explicit decision noting the monthly cadence.

## Testing Review

### T1. Missing Profile-Level LLM Startup Tests

Add tests that enumerate tasks actually used by Provider and Collector and assert resolved providers have required config and task capability without network calls.

### T2. Missing All-Adapters-Failed Readiness/Startup Test

Existing tests assert that one failing adapter does not abort the loop. Add a test where every activated adapter throws and assert readiness/startup behavior.

### T3. Missing Partition Catch-Up Test

`PartitionCreatorTest` validates generated DDL for one supplied month. Add a test for current-month missing after downtime and expected multi-month reconciliation.

### T4. Missing Concurrent Chat Persistence Test

`ChatSessionRepositoryTest` covers sequential persistence and trigger counters. Add a concurrent same-session test to expose or prevent duplicate sequence allocation.

### T5. Missing Chat Tool Result Budget Tests

Existing chat/tool tests cover argument validation, output sanitizer behavior, call iteration caps, and some tool dispatch. Add tests for per-tool result truncation and aggregate prompt budget once implemented.

### T6. Missing Rate-Cap Hard-Cap Test

Add high-cardinality contact/group tests that assert hard capacity and admission/eviction behavior. Idle eviction alone is not enough for adversarial cardinality.

### T7. Missing Summary SQL Cap Test

Add a test or benchmark that seeds more than `clusterCap` eligible posts with large bodies and asserts the SQL path does not materialize all bodies.

## Technical Debt

- LLM task routing checks names but not provider capability/config.
- Production-like datasource config has known password fallbacks.
- Provider adapter startup lacks an implemented zero-connected readiness boundary.
- Bootstrap-admin row creation is deferred while config validation exists.
- Partition lifecycle is not catalog-driven and does not reconcile current/missed months.
- Summary/chat tool paths lack source-level output budgets.
- SSRF pinning depends on a JVM-global resolver lock.
- Rate-cap maps are idle-evicted but not hard-bounded.
- Docs drift on Flyway ownership and partition cadence.

## Refactoring Roadmap

1. **Close startup correctness gaps.** Add LLM task validation, adapter readiness, and bootstrap-admin row creation.

2. **Remove production secret fallbacks.** Make production DB secrets fail-loud when missing; keep local defaults strictly in dev/test.

3. **Move caps to data sources.** Push summary caps into SQL, add chat tool result budgets, and hard-bound rate-cap maps.

4. **Make DB writes race-safe.** Atomically allocate chat message sequences at the database boundary.

5. **Make partition lifecycle reconciliatory.** Provision current and future windows from catalog state on startup/tick.

6. **Measure unavoidable complexity.** If SSRF global DNS pinning remains, add metrics for lock wait/hold time and dial deadlines.

7. **Clean drift.** Update stale comments and deployment/design docs so future reviewers do not re-open already-fixed issues or miss real gaps.
