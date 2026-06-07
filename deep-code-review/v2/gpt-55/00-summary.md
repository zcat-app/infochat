# Deep Code Review Summary

**Run directory:** `deep-code-review/v2/gpt-55`  
**Date:** 2026-06-07  
**Reviewer:** GPT-5.5  
**Scope:** `infochat-core`, `infochat-collector`, `infochat-provider`, `infochat-llm-adapter`, `infochat-messaging-adapter`, `infochat-ssrf`.

## Coverage

This pass covered the six main modules with repository-wide static scans plus targeted line-by-line review of the matched hot paths. Inventory found 568 Java files, 317 Java test files, and 37 Flyway migrations. I reviewed security boundaries, LLM routing, datasource/secrets config, messaging startup, partition lifecycle, SSRF guarded networking, summary/chat retrieval paths, rate limiting, audit/export behavior, and relevant tests/specs.

This was not a claim that every file was manually read end-to-end. It was a broad deep-review pass with exact evidence for each reported issue and explicit weakening/removal of stale handoff claims.

## Overall Assessment

The highest-risk issues are operational/security correctness gaps rather than obvious SQL injection or missing escaping. LLM task routing can pass startup checks while resolving to providers that cannot serve the requested task; production-like configuration still has known database-password fallbacks; Provider can continue after all messaging transports fail without an implemented readiness check; and bootstrap-admin config is validated but the bootstrap row is not created.

The main performance risks are "bounded late" rather than "bounded at source": summary retrieval caps after materializing full bodies, chat tool results are appended back into prompts without an output byte/token cap, rate-cap maps evict by idle time but have no hard capacity, and SSRF connection establishment serializes through a JVM-global resolver lock.

Several handoff findings were rechecked and corrected:

- `SsrfGuardedHttpClient` does not rebuild the JDK `HttpClient` on every redirect hop; it creates one client per guarded `get()` call and reuses it through the redirect loop.
- `SearchPostsTool` still trusts its local `limit` argument, but the shipped chat dispatcher clamps limits before execution. This is a local-invariant/simplification issue, not a currently exposed unbounded chat SQL path.
- The embedding schema has an HNSW index on `post_embedding.embedding`; the remaining semantic-linking risk is query shape/index usage, not total absence of a vector index.
- Audit redaction is no longer just the V5 stub: V31 replaces the redactor functions. The export collector comment is stale, but the current migration redacts contact ids and secret-like JSON.

## Top Findings

1. **High: LLM task routing can break summary, chat, translation, tagger, and entity tasks.**  
   `LlmRouter` defaults unresolved tasks to `openai-compatible` (`LlmRouter.java:174-179`), while `OpenAiCompatibleProvider.configFor()` only supports `SECURITY_JUDGE` and throws for the other five tasks (`OpenAiCompatibleProvider.java:157-165`). Provider and Collector call those tasks today.

2. **High: Production-like database credentials have known fallbacks.**  
   Provider and Collector datasource passwords default to `infochat-dev` in service properties, and Docker Compose defaults Postgres to the same value. This conflicts with deployment docs that treat DB passwords as operator secrets.

3. **High: Provider can start with zero working messaging transports unless readiness fixes it.**  
   `MessagingStartup.startAllAdapters()` catches every adapter startup exception and continues (`MessagingStartup.java:57-67`). I found no Provider health/readiness implementation or `quarkus-smallrye-health` wiring, while the deployment spec requires not-ready when zero adapters are connected.

4. **High: Monthly partition provisioning does not catch up current/missed months.**  
   `PartitionCreator` creates only `YearMonth.now(UTC).plusMonths(1)` (`PartitionCreator.java:54-61`). After long downtime, current-month inserts can still fail even though the scheduler is running.

5. **High: Summary retrieval materializes every eligible body before applying the cap.**  
   `EligiblePostQuery.fetch()` loads all eligible rows and only then caps in Java; the SQL selects `p.body` with no `LIMIT`.

6. **High: SSRF-guarded connection establishment is globally serialized.**  
   `PinnedDnsResolver` has one static lock/pin slot, and HTTP/WebSocket guarded dials share that lock.

7. **Medium-high: Chat sequence assignment is not atomic at the repository boundary.**  
   `ChatSessionRepository` reads `next_seq`, inserts a message, and relies on an after-insert trigger to increment. Concurrent writers to the same session can read the same sequence.

8. **Medium-high: LLM provider error logging can leak prompt-adjacent content.**  
   OpenAI-compatible, Anthropic, and embedding providers log or propagate 200-character response previews/error messages from upstream LLM APIs.

9. **Medium-high: Chat tool loop has no result-size budget before reinjecting tool output into prompts.**  
   Tool calls are capped and arguments are validated, but successful tool output is cached and appended wholesale into the conversation. `getPost` returns full post bodies and `recallMemory` can return 50 summaries.

10. **Medium: Rate-cap state is idle-evicted but not capacity-bound.**  
    Contact/group maps use `ConcurrentHashMap.computeIfAbsent()` and scheduled idle eviction, with no hard maximum.

11. **Medium: Bootstrap admin is a validated property, not an implemented bootstrap.**  
    `AdapterRegistry` requires at least one `infochat.adapters.<name>.admin`, but comments and properties state the startup bean that creates the admin row is deferred.

12. **Medium: SimpleX adapter admin notifications are log-only.**  
    The production SimpleX admin notifier is a `WARN` log stub, not a messaging/admin notification channel.

## Highest-Risk Issue

LLM task validation is the most urgent fix because the current startup guard can pass while first real use of `/summary`, chat, translation, tagger, or entity extraction fails. The failure crosses Provider and Collector user-facing/ingest flows.

## Highest-Impact Fix

Add service-local LLM startup validation that enumerates the tasks each service actually uses and verifies provider support plus required config without issuing a network call. Then complete default/per-profile task config for Provider and Collector.

## Testing Gaps To Prioritize

- Profile-level LLM route validation for every service-used `ModelTask`.
- Adapter startup/readiness test where every activated transport fails.
- Partition catch-up test for current/missed month creation after downtime.
- Concurrent `ChatSessionRepository.persistTurn()` test for the same session.
- Summary query test/benchmark proving SQL-level caps.
- Chat tool result-size cap tests.
- Rate-cap high-cardinality capacity tests.
