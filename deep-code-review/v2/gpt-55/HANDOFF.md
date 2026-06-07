# Session Handoff

## Current Branch

- Branch: `main`
- Working tree state at handoff: `?? deep-code-review/v2/`
- Review output directory: `deep-code-review/v2/gpt-55/`
- Files created in this directory before this handoff: none

## Completed Work

- Performed a broad repository inspection of the Quarkus multi-module project:
  - `infochat-core`
  - `infochat-collector`
  - `infochat-provider`
  - `infochat-llm-adapter`
  - `infochat-messaging-adapter`
  - `infochat-ssrf`
- Excluded generated and non-source review artifacts from evidence gathering:
  - `target/**`
  - `.claude/**`
  - `deep-code-review/**`
- Collected high-confidence evidence for security, reliability, performance, architecture, simplicity, code quality, and testing findings.
- Created the requested output directory:
  - `deep-code-review/v2/gpt-55/`
- Confirmed the full requested report and `00-summary.md` have not yet been written.

## Pending Work

- Write the requested review artifacts:
  - `deep-code-review/v2/gpt-55/00-summary.md`
  - `deep-code-review/v2/gpt-55/01-report.md`
- The report must include all requested sections:
  - Executive Summary
  - Security Review
  - Reliability Review
  - Performance Review
  - Architecture Review
  - Simplicity Review
  - Code Quality Review
  - Testing Review
  - Technical Debt
  - Refactoring Roadmap
- Include concrete file/class/function/line references.
- Do not report generic advice without code evidence.
- Do not praise the code.

## Known Environment Issue

- Initial sandboxed shell commands failed with:
  - `bwrap: loopback: Failed RTM_NEWADDR: Operation not permitted`
- Escalated read-only commands worked.
- Continue using `sandbox_permissions: "require_escalated"` for shell inspection commands if the sandbox error recurs.
- The user approved/saved several command prefixes during the session, including:
  - `mkdir -p deep-code-review/v2/gpt-55`
  - selected `nl -ba ...` source inspection commands

## Important Findings Already Identified

### LLM Routing Misconfiguration Can Break Chat And Summaries

- `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java`
  - Default provider resolution falls back to `OpenAiCompatibleProvider.PROVIDER_NAME`.
- `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java`
  - `configFor(ModelTask)` only supports `SECURITY_JUDGE`.
  - `TAGGER`, `ENTITY`, `SUMMARIZER`, `CHAT_AGENT`, and `TRANSLATOR` throw `UnsupportedOperationException`.
- `infochat-provider/src/main/resources/application.properties`
  - Only security LLM settings are configured by default.
- Risk:
  - Non-`remote-llm` profiles can route `SUMMARIZER` and `CHAT_AGENT` to a provider that cannot serve those tasks.
  - Summary prose silently degrades.
  - Chat can fail with a generic unavailable response.

### Production Password Defaults Are Unsafe

- `infochat-provider/src/main/resources/application.properties`
  - Provider datasource password defaults to `infochat-dev`.
- `infochat-collector/src/main/resources/application.properties`
  - Collector and owner datasource passwords default to `infochat-dev`.
- `docker-compose.yml`
  - Postgres password defaults to `infochat-dev`.
- `docs/design/07-deployment.md`
  - Deployment docs mark these secrets as required, but runtime config still provides known fallback values.
- Risk:
  - Production-like profiles can boot with known credentials if environment variables are missing.

### SSRF Guard Serializes Outbound Connection Establishment Globally

- `infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/PinnedDnsResolver.java`
  - Uses a static pin slot guarded by one global `ReentrantLock`.
- `infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java`
  - HTTP requests acquire the global pinned-DNS lock during connection/headers/redirect handling.
  - WebSocket pinned dials also use the same global lock.
- Risk:
  - One slow SSRF-guarded connect path can block unrelated outbound work.
  - This is a likely production scalability bottleneck.

### SSRF HTTP Client Is Recreated Per Request

- `infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java`
  - Builds a new `HttpClient` inside each guarded `get` call.
- Risk:
  - Loses connection reuse and creates avoidable client/selector overhead.

### Partition Provisioning Can Fail After Collector Downtime Across Month Boundary

- `infochat-collector/src/main/java/app/zcat/infochat/collector/partition/PartitionCreator.java`
  - Only provisions `YearMonth.now(UTC).plusMonths(1)`.
  - Comments explicitly state there is no default partition and missing partitions wedge inserts.
- Risk:
  - If the collector is down long enough to miss partition creation, the next run creates the following month, not necessarily the current missing month.
  - Current-month inserts can remain broken.

### Summary Query Loads All Eligible Posts Then Caps In Java

- `infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java`
  - `fetch()` calls `selectPosts()`, computes `total = all.size()`, then truncates with `subList`.
  - SQL orders by recency but has no `LIMIT`.
  - Selected columns include `p.body`.
- Risk:
  - Large active scopes can materialize many rows and bodies only to discard most of them.
  - High DB, memory, and latency risk.

### Chat Search Tool Accepts Unbounded LLM-Controlled Limit

- `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java`
  - Reads `limit` from tool arguments and passes it into SQL `LIMIT ?` without min/max validation.
- Risk:
  - Prompt-influenced tool calls can request excessive result sizes or invalid limits.
  - Causes performance degradation or SQL errors.

### Chat Message Sequence Allocation Is Not Intrinsically Atomic

- `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatSessionRepository.java`
  - Reads `chat_session.next_seq`, inserts `chat_message`, and relies on a trigger to increment the sequence.
- `infochat-core/src/main/resources/db/migration/V18__chat_tables.sql`
  - Primary key includes `(user_id, scope_kind, scope_id, seq)`.
- Risk:
  - Current single-instance and in-memory in-flight controls reduce likelihood.
  - The database method itself can race under future multi-instance or alternate write paths.

### RateCapBucket Can Grow From Arbitrary Contact IDs

- `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/RateCapBucket.java`
  - Uses unbounded `ConcurrentHashMap` buckets keyed by adapter/contact and group.
  - Eviction is periodic, not capacity-bound.
- `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java`
  - Rate-cap buckets are created from adapter contact IDs before durable authorization lookup.
- Risk:
  - A compromised or low-trust adapter path can create many bucket keys and pressure heap.

### Messaging Startup Can Continue With Zero Working Adapters

- `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/MessagingStartup.java`
  - Catches adapter startup failures and continues.
- Risk:
  - Provider can appear healthy while all messaging ingress/egress is unavailable.

### Admin Bootstrap Is Not Implemented

- `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java`
  - Validates admin bootstrap config presence but comments state actual startup bootstrap is deferred.
- `infochat-provider/src/main/resources/application.properties`
  - Mentions admin bootstrap as an operational requirement.
- Risk:
  - Fresh production deployment may pass config validation but have no usable admin unless seeded out of band.

### SimpleX Admin Notification Is A Log-Only Stub

- `infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/ProductionAdapterBeans.java`
  - Admin notifier for SimpleX logs warnings instead of sending unified admin notifications.
- Risk:
  - Adapter crash-cap notifications may not reach administrators through the messaging channel.

### LLM Provider Error Logging May Leak Prompt-Adjacent Data

- `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java`
  - Logs non-2xx response body previews.
- `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java`
  - Similar pattern likely exists; verify exact lines before reporting.
- Risk:
  - Provider error bodies can include request-derived or user-derived content.

### Semantic Linking Query May Be Expensive

- `infochat-provider/src/main/java/app/zcat/infochat/provider/linking/LinkingJob.java`
  - Candidate query joins `post_embedding` to itself over a time window and orders by vector distance.
- `infochat-core/src/main/resources/db/migration/V11__embeddings_and_links.sql`
  - Verify whether an ANN/vector index exists before finalizing this as a performance finding.
- Risk:
  - Without an index-friendly query shape, this can scale poorly with the number of embeddings in the window.

## Important Architectural Decisions To Preserve In The Review

- The system is intentionally split into Maven modules:
  - core schema/shared model
  - collector ingestion
  - provider user-facing behavior
  - LLM adapter
  - messaging adapter
  - SSRF guard
- PostgreSQL partitioning is a central storage decision.
  - No default partition is intentionally used.
  - Monthly partition creation is delegated to collector-side scheduled DDL.
- SSRF protection is centralized in `infochat-ssrf`.
  - It uses DNS pinning through a JVM-wide resolver/pin mechanism.
  - This is security-motivated but has major concurrency/performance consequences.
- Messaging is adapter-based.
  - Runtime adapter enablement is profile/config driven.
  - Some production paths still rely on bootstrap/stub behavior.
- LLM access is provider-routed by `ModelTask`.
  - The routing abstraction exists, but task support and configuration coverage are incomplete.
- Provider single-instance behavior appears intentional.
  - There is an instance/advisory lock.
  - Some data-path assumptions rely on that single-instance constraint and will not survive horizontal scaling.

## Next Recommended Actions

1. Re-run only the missing source inspections needed to confirm exact line numbers for the final report:
   - `SsrfGuardedHttpClient.java`
   - `PinnedDnsResolver.java`
   - `LlmRouter.java`
   - `ChatSessionRepository.java`
   - `RateCapBucket.java`
   - `MessagingStartup.java`
   - `AdapterRegistry.java`
   - `ProductionAdapterBeans.java`
   - `LinkingJob.java`
   - `V11__embeddings_and_links.sql`
   - `AnthropicProvider.java`
2. Write `00-summary.md` first.
   - Include overall assessment, top 10 findings, highest-risk issue, highest-impact improvement, and most unnecessary complexity.
3. Write `01-report.md`.
   - Organize exactly by the user-requested sections.
   - Rank security by severity and performance by expected gain.
   - Include confidence levels where evidence is incomplete.
4. Do not run tests unless needed for verification.
   - This is a review task, not a code-change task.
5. Avoid modifying application source files.
   - Only write review artifacts under `deep-code-review/v2/gpt-55/`.


## Continuation Update - 2026-06-07 GPT-5.5

The review artifacts have now been written and expanded after the user's request for a more comprehensive deep review covering security issues, performance issues, design flaws, and simplification possibilities.

Updated files:

- `deep-code-review/v2/gpt-55/00-summary.md`
- `deep-code-review/v2/gpt-55/01-report.md`

The newer report supersedes the earlier pending-work section above. It also corrects stale handoff claims:

- `SsrfGuardedHttpClient` is not rebuilding `HttpClient` on every redirect hop; it uses one per guarded `get()` call.
- `SearchPostsTool`'s raw `limit` is clamped by the shipped `ChatToolDispatcher`; remaining issue is local invariant safety for future callers.
- Audit redaction is no longer the V5 stub because V31 installs real redactors; remaining issue is stale export-comment drift.
- `post_embedding` has an HNSW index; semantic-linking risk is query shape/plan validation, not missing index.

No production code was changed. This continuation changed only review artifacts under `deep-code-review/v2/gpt-55/`.
