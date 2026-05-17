---
id: M1-034b
title: Embedding pipeline + ReadyPromoter + first new_post NOTIFY
status: done
created: 2026-05-17
last_updated: 2026-05-17
decomposed_from: M1-034
clarity_check:
  date: 2026-05-17
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 1
    date: 2026-05-17
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 2025
      removed: 7
blocked_by:
  - M1-034a
files_budget: 8
files_scope:
  - infochat-llm-adapter/src/main/java/io/infochat/llm/impl/OpenAiCompatibleEmbeddingProvider.java
  - infochat-collector/src/main/java/io/infochat/collector/eval/embedding/EmbeddingMetadataDao.java
  - infochat-collector/src/main/java/io/infochat/collector/eval/embedding/EmbeddingMetadataStartupGuard.java
  - infochat-collector/src/main/java/io/infochat/collector/eval/embedding/EmbeddingWorker.java
  - infochat-collector/src/main/java/io/infochat/collector/eval/ready/ReadyPromoter.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/io/infochat/collector/eval/embedding/EmbeddingWorkerIT.java
  - infochat-collector/src/test/java/io/infochat/collector/eval/ready/ReadyPromoterIT.java
complexity: medium
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - any Stage 1 / Stage 2 worker code, Stage1Worker, Stage2Worker, OpenAiCompatibleProvider, LlmRouter, release-on-stage2-failure config-flag wiring (M1-032 and M1-033 territory — consumed unchanged)
  - any Tagger code, TaggerWorker, TagVocabulary, tagger.md, tagger-fallback.md, or infochat.llm.tagger.* property keys — M1-034a territory. M1-034b reads post.tagger_done=true as the EmbeddingWorker pickup criterion but does NOT touch the Tagger
  - any change to V11__post_embedding.sql or any other Flyway migration — V11 lands in M1-034a; this ticket reads/inserts/updates the V11 tables but adds no SQL migration of its own
  - any EntityExtractor, post_entity table, post_reference table, LinkingJob — T2 territory
  - any Re-evaluation job, attempt counter, QUARANTINED → NEEDS_REVIEW transition — T2-G territory
  - any throttled admin notifier wiring — T2-G; the embedding-failure and startup-guard-mismatch paths log at WARN/ERROR with canonical error_class strings for the future notifier
  - any LLM output sanitizer — T1-F (embedding output is a numeric vector, not user-visible text; the sanitizer is not relevant here)
  - any /quarantine admin command or approve_quarantine/reject_quarantine stored procedures — T2-G
  - any Provider-side quarantine_review LISTEN listener — M2
  - any AnthropicProvider native-protocol implementation or AnthropicEmbeddingProvider — T3-D. v1's concrete EmbeddingProvider is OpenAiCompatibleEmbeddingProvider only
  - any TranslationProvider impl — T1-F
  - any chat-agent recall tool, five-tool allowlist — T2-D
  - any change to the M1-007b LlmProvider / EmbeddingProvider / ModelTask SPI surfaces (frozen)
  - any infochat-provider module change. The Provider-side NewPostListener from M1-027 will observe this ticket's pg_notify but the listener itself is NOT modified; the contract is the JSON payload byte shape
  - any partition_pruner job — T2 territory
  - any embedding-model migration script (scripts/reembed.sh) — operator tooling
  - any Prometheus/Micrometer metric emit for embedding_batch_failure_total — T2 observability
  - any provider-side cache-invalidation, group-digest recompute, or NewPostHandler real-consumer logic — T1-F. This ticket emits pg_notify('new_post', ...) but M1-027's stub NewPostHandler stays
acceptance:
  - "OpenAiCompatibleEmbeddingProvider.java under infochat-llm-adapter/src/main/java/io/infochat/llm/impl/ implements EmbeddingProvider per docs/design/05-llm-and-embeddings.md §5.1 / §5.5 (the OpenAI-compatible /embeddings endpoint covers Ollama and OpenAI per the multi-provider design). Issues POST <base-url>/embeddings with body {\"model\": \"...\", \"input\": [\"text1\", ...]} and returns List<EmbeddingResult> in input order per the M1-007b SPI shape. Reads (base-url, api-key, model) via @ConfigProperty — grep -E 'class\\s+OpenAiCompatibleEmbeddingProvider\\s+implements\\s+EmbeddingProvider' OpenAiCompatibleEmbeddingProvider.java returns at least one match AND grep -E '/embeddings|/v1/embeddings' OpenAiCompatibleEmbeddingProvider.java returns at least one match AND grep -E 'infochat\\.embeddings\\.base-url|infochat\\.embeddings\\.model' OpenAiCompatibleEmbeddingProvider.java returns at least one match"
  - "OpenAiCompatibleEmbeddingProvider.java does NOT route through the (ModelTask, scope_language) router from M1-033 per docs/spec/llm.md §SPI shape 'Scope of the enum. The embedder is not a ModelTask — EmbeddingProvider is a distinct SPI with its own provider selection.' EmbeddingProvider resolution uses ONE provider per deployment (one base-url, one api-key, one model); there is no per-language or per-task routing for embeddings — grep -E 'ModelTask|LlmRouter' OpenAiCompatibleEmbeddingProvider.java returns zero matches"
  - "EmbeddingMetadataDao.java is the SOLE write path to embedding_metadata in M1. Two SQL shapes: (1) READ the singleton row (SELECT model_identifier, dimension FROM embedding_metadata LIMIT 1); (2) UPDATE the singleton row (UPDATE embedding_metadata SET model_identifier=?, dimension=?, updated_at=now()) — used ONLY by the operator-override path in EmbeddingMetadataStartupGuard — grep -E 'class\\s+EmbeddingMetadataDao' EmbeddingMetadataDao.java returns at least one match AND grep -rE 'INSERT\\s+INTO\\s+embedding_metadata|UPDATE\\s+embedding_metadata' infochat-collector/src/main/java/ returns matches ONLY inside EmbeddingMetadataDao.java (V11's seed INSERT in M1-034a is in a .sql migration file, NOT under src/main/java/, so the grep boundary is clean)"
  - "EmbeddingMetadataStartupGuard.java is a Collector-side @Startup bean that enforces the model identity guard per docs/spec/llm.md §Embedding pipeline 'On every startup the EmbeddingProvider reports its current identifier and dimensionality; if either differs from the stored row, startup is refused with a descriptive error referencing the re-embed procedure.' At startup: read the configured infochat.embeddings.model and infochat.embeddings.dimension (the operator-set values for the active profile); compare to embedding_metadata. On mismatch, refuse startup with a fatal log line naming the stored value, the configured value, and the re-embed procedure path (docs/design/02-schema.md §2.8). @Priority is between Flyway (100) and M1-033's LlmRouterStartupGuard (150) — use @Priority(125). grep -E '@Startup' EmbeddingMetadataStartupGuard.java returns at least one match AND grep -E '@Priority\\s*\\(\\s*125\\s*\\)' EmbeddingMetadataStartupGuard.java returns at least one match AND grep -E 'infochat\\.embeddings\\.model' EmbeddingMetadataStartupGuard.java returns at least one match AND grep -E 'embedding_metadata' EmbeddingMetadataStartupGuard.java returns at least one match"
  - "EmbeddingMetadataStartupGuard.java honors the operator override per docs/spec/llm.md §Embedding pipeline 'An explicit operator override flag bypasses the check for intentional migration runs; its property key and semantics are in design notes.' Property key: infochat.embeddings.allow-model-change=true. When set, the guard: (a) does NOT refuse startup on mismatch; (b) UPDATEs embedding_metadata with the new model_identifier and dimension via EmbeddingMetadataDao; (c) logs WARN with the rotation (old → new) — grep -E 'infochat\\.embeddings\\.allow-model-change' EmbeddingMetadataStartupGuard.java returns at least one match"
  - "EmbeddingWorker.java under infochat-collector/src/main/java/io/infochat/collector/eval/embedding/ picks up posts ready for embedding: status='RAW' AND tagger_done=true AND embedding_done=false. The worker batches posts by infochat.embeddings.batch-size (laptop default 16; document in application.properties) and flushes when the batch is full OR when a profile-driven flush timer fires. Bounded by infochat.embeddings.max-concurrency (laptop default 4 per §5.7) — grep -E 'class\\s+EmbeddingWorker' EmbeddingWorker.java returns at least one match AND grep -E 'tagger_done\\s*=\\s*TRUE|tagger_done\\s*=\\s*true' EmbeddingWorker.java returns at least one match AND grep -E 'embedding_done\\s*=\\s*FALSE|embedding_done\\s*=\\s*false' EmbeddingWorker.java returns at least one match AND grep -E 'infochat\\.embeddings\\.batch-size|batchSize' EmbeddingWorker.java returns at least one match AND grep -E '@Scheduled' EmbeddingWorker.java returns at least one match"
  - "EmbeddingWorker.java invokes EmbeddingProvider.embed(List<String>) with per-post input text per docs/design/05-llm-and-embeddings.md §5.5: 'For each post that reaches EmbeddingWorker: 1. Build input text: title + \"\\n\\n\" + (body_summary or first 800 chars of body). 2. Call EmbeddingProvider.embed(text).' For a batch of N posts the worker builds N input texts and calls embed once. The provider returns N EmbeddingResults in input order per the M1-007b SPI shape — grep -E '\\.embed\\s*\\(' EmbeddingWorker.java returns at least one match"
  - "EmbeddingWorker.java's one-failure-fails-batch retry policy per docs/spec/llm.md §Embedding pipeline 'One-failure-fails-batch retry. If the provider returns a batch result of the wrong shape, an exception, or any per-element error the Collector cannot map back to a specific post, the entire batch retries once. If retry also fails, every post in the batch follows the embedding-failure release path (release without a vector).' A try/catch around the embed call retries exactly once on any failure; per docs/spec/llm.md §Failure handling (recap) 'Retry policy: on a batch failure the same batch is resubmitted as-is; the batch is not split on retry' — the retry uses the SAME batch (no per-post split). On second failure, EVERY post in the batch advances: embedding_done=true, NO post_embedding row inserted, log WARN with canonical error_class='embedding.batch_failure' — grep -E 'embedding\\.batch_failure' EmbeddingWorker.java returns at least one match"
  - "EmbeddingWorker.java's success path inserts one post_embedding row per post via batch INSERT per docs/design/05-llm-and-embeddings.md §5.5 step 3 'Insert one row into post_embedding(post_id, embedding, embedding_model, fetched_at)'. embedding_model is the active model identifier read from embedding_metadata via EmbeddingMetadataDao (NOT the provider's reported value — the metadata is the canonical record per the model identity guard) — grep -E 'INSERT\\s+INTO\\s+post_embedding' EmbeddingWorker.java returns at least one match AND grep -E 'embedding_model' EmbeddingWorker.java returns at least one match"
  - "EmbeddingWorker.java's embedding_done=true UPDATE is the persistence cursor for the Embedding boundary per Invariant 5. On success: post.embedding_done=true (the post_embedding row was inserted). On batch failure: post.embedding_done=true (no row inserted, embedding-less release path per docs/spec/security.md §Failure handling 'Embedding failure → release without a vector; the post is otherwise normal and fully visible') — grep -E 'embedding_done\\s*=\\s*TRUE|embedding_done\\s*=\\s*true' EmbeddingWorker.java returns at least one match"
  - "EmbeddingWorker.java fails fatally on per-element dimensionality mismatch per docs/spec/llm.md §Embedding pipeline 'Dimensionality mismatch at runtime is fatal. Storing vectors of mixed dimensions in the same pgvector column silently corrupts cosine similarity scores. The only safe recovery is a full re-embed.' If the provider returns a vector whose length differs from embedding_metadata.dimension, the worker throws immediately (no retry — this is NOT a batch-failure-retry case but a metadata-invariant violation). The thrown exception unwinds the batch transaction (no post_embedding rows inserted, embedding_done NOT advanced — the post stays in-flight; operator runs the re-embed procedure) — grep -E 'dimension|getDimension|\\.length' EmbeddingWorker.java returns at least one match AND grep -E 'throw\\s+new|IllegalStateException|RuntimeException' EmbeddingWorker.java returns at least one match"
  - "ReadyPromoter.java under infochat-collector/src/main/java/io/infochat/collector/eval/ready/ handles the Stage-5 RAW → READY transition per docs/design/01-architecture.md §1.3.4 step 5 'UPDATE post.status=READY, post.ready_at=now(), NOTIFY new_post with payload (ready_at, post_id)'. Pickup criteria: status='RAW' AND stage1_done=true AND (stage1_flagged=false OR stage2_done=true) AND tagger_done=true AND embedding_done=true. The transition writes status='READY', ready_at=now(), status_changed_at=now() AND emits pg_notify('new_post', json) — ALL IN THE SAME DB TRANSACTION per docs/spec/architecture.md §Inter-service communication 'the high-water mark advances both fields in the same DB transaction as the side effect it triggers, making processing idempotent'. The NOTIFY payload is the cursor key only: {ready_at, post_id} per docs/design/02-schema.md §2.9.1 — grep -E 'class\\s+ReadyPromoter' ReadyPromoter.java returns at least one match AND grep -E \"status\\s*=\\s*'READY'\" ReadyPromoter.java returns at least one match AND grep -E 'pg_notify|NOTIFY\\s+new_post' ReadyPromoter.java returns at least one match AND grep -E '@Transactional|TransactionManager' ReadyPromoter.java returns at least one match"
  - "ReadyPromoter.java's NOTIFY payload uses the JSON form documented in M1-027's NewPostListener (verified at authoring: infochat-provider/src/main/java/io/infochat/provider/outbox/NewPostListener.java carries the parser as a package-private static parsePayload(String) plus regexes \\\"ready_at\\\"\\s*:\\s*\\\"([^\\\"]+)\\\" and \\\"post_id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"). The emit format is the JSON object {\"ready_at\":\"<iso8601-instant>\",\"post_id\":\"<uuid>\"} — ready_at MUST be an Instant.toString() ISO-8601 form (e.g. 2026-05-16T12:34:56.789Z) since the parser calls Instant.parse, and post_id MUST be a canonical UUID.toString() form since the parser calls UUID.fromString. The payload is built inline in ReadyPromoter (no shared helper extracted — M1-027's parser stays in infochat-provider per this ticket's out-of-scope 'no infochat-provider module change' rule; the format compatibility is contract-level, not class-level) — grep -E '\"ready_at\"' ReadyPromoter.java returns at least one match AND grep -E '\"post_id\"' ReadyPromoter.java returns at least one match"
  - "ReadyPromoter.java NEVER promotes posts with status='QUARANTINED' to READY (Stage 2 INJ/MAL/UNK and Stage 1 watchdog fail-closed paths both write status='QUARANTINED' and must NEVER advance). The pickup query's status='RAW' clause is the only filter; there is no path that mutates status='QUARANTINED' to 'READY' in this class — grep -E \"status\\s*=\\s*'QUARANTINED'\" ReadyPromoter.java returns zero matches (the only status the promoter writes is 'READY')"
  - "Quarantined posts are excluded from EmbeddingWorker pickup: a post with status='QUARANTINED' does NOT appear in EmbeddingWorker's SELECT. Verify by reading: the SELECT statement filters on status='RAW'; the status='QUARANTINED' filter is not present in the body because the inverse filter status='RAW' already excludes it"
  - "Posts with stage2_failed=true AND status='RAW' (the release-on-stage2-failure=true infra-failure path from M1-033) ARE picked up by EmbeddingWorker AND ReadyPromoter. The infra-failure path still requires embedding and the ready promotion for the user-facing post to be useful — stage2_failed=true is a metadata flag for the future re-eval job, NOT a downstream-pipeline blocker. Verify by reading: neither worker's pickup query filters on stage2_failed"
  - "application.properties under infochat-collector/src/main/resources is amended to add the embedding property surface (the tagger surface lands in M1-034a). Required keys: infochat.embeddings.base-url (default http://localhost:11434/v1 for laptop Ollama), infochat.embeddings.api-key (default 'ignored' for local Ollama), infochat.embeddings.model (default nomic-embed-text for laptop/vps matching V11's seed row from M1-034a — the property name matches docs/design/05-llm-and-embeddings.md §5.7), infochat.embeddings.dimension (default 768 for laptop/vps matching V11's seed), infochat.embeddings.batch-size (default 16; document choice in property comment), infochat.embeddings.max-concurrency (default 4 for laptop per §5.7), infochat.embeddings.allow-model-change (default false), infochat.embeddings.poll-interval (default 5s matching the Tagger cadence in M1-034a) — grep -E 'infochat\\.embeddings\\.base-url' application.properties returns at least one match AND grep -E 'infochat\\.embeddings\\.model' application.properties returns at least one match AND grep -E 'infochat\\.embeddings\\.dimension' application.properties returns at least one match AND grep -E 'infochat\\.embeddings\\.allow-model-change' application.properties returns at least one match AND grep -E 'infochat\\.embeddings\\.batch-size' application.properties returns at least one match"
  - "EmbeddingWorkerIT.java is a @QuarkusTest IT against real Postgres + a STUB EmbeddingProvider replacing OpenAiCompatibleEmbeddingProvider (@Alternative @Priority(MAX_VALUE) for the test profile). Scenarios (each seeds posts with status='RAW' AND tagger_done=true AND embedding_done=false): (1) happy path — stub returns N=2 vectors for N=2 input texts → 2 post_embedding rows inserted, embedding_done=true for each, post_embedding.embedding_model matches the active model identifier read from embedding_metadata; (2) batch failure — stub throws on the FIRST call → retries; the retry also throws → all N posts in the batch follow the no-vector release path (embedding_done=true, ZERO post_embedding rows inserted, WARN log with error_class='embedding.batch_failure'); (3) partial failure (provider returns N=1 result for N=2 inputs — wrong shape) — same as batch failure (the entire batch retries once, then no-vector release); (4) dimensionality mismatch at runtime — stub returns a vector of dimension D' ≠ D (e.g. 384 when the metadata says 768) → throws RuntimeException immediately, the post stays in-flight (no post_embedding row, embedding_done STAYS false); (5) pre-promotion boundary — a post with tagger_done=true, embedding_done=true, status='RAW' is NOT yet promoted by EmbeddingWorker (the ReadyPromoter is a separate concern tested in ReadyPromoterIT) — grep -E '@Test' EmbeddingWorkerIT.java returns at least five matches"
  - "ReadyPromoterIT.java is a @QuarkusTest IT that asserts the Stage-5 transition + NOTIFY emit. Scenarios: (1) happy path — a post with stage1_done=true, stage1_flagged=false, tagger_done=true, embedding_done=true, status='RAW' is updated to status='READY' with ready_at and status_changed_at set; one NOTIFY new_post payload {ready_at, post_id} is observed by a JDBC LISTEN test fixture (real Postgres NOTIFY, not an in-process mock — same pattern as M1-027's NewPostListenerIT); (2) same-transaction rule — a deliberate failure between the UPDATE and the pg_notify (force the transaction to roll back via @Transactional + throwing inside the boundary) leaves status='RAW' AND no NOTIFY observable; (3) quarantined exclusion — a post with status='QUARANTINED' AND tagger_done=true AND embedding_done=true is NOT promoted (status stays 'QUARANTINED', no NOTIFY); (4) stage2_failed release path — a post with stage2_failed=true AND status='RAW' AND tagger_done=true AND embedding_done=true IS promoted to status='READY'; (5) startup model identity guard — pre-seed embedding_metadata with model='alpha' dimension=768; configure infochat.embeddings.model='beta'; Collector startup FAILS with the descriptive error mentioning 'alpha' and 'beta'; configure infochat.embeddings.allow-model-change=true; startup succeeds and embedding_metadata is overwritten — grep -E '@Test' ReadyPromoterIT.java returns at least five matches AND grep -E 'pg_notify|NOTIFY\\s+new_post|LISTEN' ReadyPromoterIT.java returns at least one match"
  - "mvn -B -pl infochat-collector -am verify exits 0; failsafe reports show EmbeddingWorkerIT and ReadyPromoterIT executed — grep -rE 'Tests run: [1-9]' infochat-collector/target/failsafe-reports returns at least two new matches across the two new IT classes"
  - "mvn -B clean verify from the repo root exits 0; all prior tests (through M1-034a) continue to pass alongside the new EmbeddingProvider impl, the EmbeddingWorker, the EmbeddingMetadataStartupGuard, the ReadyPromoter, and the first new_post NOTIFY emitter. M1-027's NewPostListenerIT continues to pass — its test-harness NOTIFY path is independent of this ticket's production-emit path; both fire the same payload byte shape so the M1-027 parser handles either without code change"
test_plan:
  adds:
    - infochat-collector/src/test/java/io/infochat/collector/eval/embedding/EmbeddingWorkerIT.java (@QuarkusTest IT against real Postgres + stub EmbeddingProvider exercising happy / batch-failure / wrong-shape / dimensionality-mismatch / pre-promotion paths)
    - infochat-collector/src/test/java/io/infochat/collector/eval/ready/ReadyPromoterIT.java (@QuarkusTest IT for the Stage-5 RAW → READY transition + pg_notify(new_post) emit + same-transaction-as-side-effect rule + quarantined exclusion + stage2_failed release path + model-identity startup guard)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a)
    - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java (M1-007b)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java (M1-007c)
    - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (M1-007)
    - infochat-collector/src/test/java/io/infochat/collector/flyway/FlywayMigrationIT.java (M1-017 — V11 from M1-034a continues to apply cleanly)
    - infochat-collector/src/test/java/io/infochat/collector/db/DbRoleMatrixIT.java (M1-006)
    - all M1-008a/b/c schema tests
    - all M1-022/023/024/025/026/029 ingest + SSRF tests
    - M1-027's three provider outbox ITs (the NewPostListenerIT will now observe NOTIFY fired by THIS ticket's ReadyPromoter, end-to-end)
    - M1-028's PostPersisterIT + OutboxRehydratorIT + FetchSchedulerIT
    - M1-032's Stage1PipelineIT + Stage1WatchdogIT + Stage1RegexSetTest
    - M1-033's LlmRouterTest + Stage2WorkerIT + LocalOnlyConflictStartupIT
    - M1-034a's TaggerWorkerIT (the EmbeddingWorker picks up where TaggerWorker leaves off; both ITs continue to pass independently)
spec_refs:
  - docs/spec/security.md §Failure handling
  - docs/spec/security.md §DB roles
  - docs/spec/llm.md §SPI shape
  - docs/spec/llm.md §Embedding pipeline
  - docs/spec/llm.md §Failure handling (recap)
  - docs/spec/llm.md §Hardware profile contract
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/schema.md §Invariants
  - docs/spec/architecture.md §Inter-service communication
  - docs/spec/architecture.md §Pipelines
  - docs/design/01-architecture.md §1.3.4 Eval pipeline workers
  - docs/design/01-architecture.md §1.4.3 Startup-bean ordering
  - docs/design/02-schema.md §2.3.1 post
  - docs/design/02-schema.md §2.4.2 post_embedding
  - docs/design/02-schema.md §2.8 Embedding model migration
  - docs/design/02-schema.md §2.9.1 LISTEN / NOTIFY channels
  - docs/design/05-llm-and-embeddings.md §5.1 SPI overview
  - docs/design/05-llm-and-embeddings.md §5.5 Embeddings
  - docs/design/05-llm-and-embeddings.md §5.7 Profile defaults
  - docs/design/05-llm-and-embeddings.md §5.8 Failure handling per task
decision_refs:
  - D5
  - D22
  - D27
  - D32
---

# M1-034b: Embedding pipeline + ReadyPromoter + first new_post NOTIFY

## Context

Second of two replacement tickets for the deferred M1-034 umbrella
(see `aborted_attempts:` on M1-034 for the split rationale; see
M1-034a for the Tagger pipeline + V11 migration that landed first).
This ticket completes T1-D (the eval pipeline) by adding:

1. **The first concrete `EmbeddingProvider` impl** —
   `OpenAiCompatibleEmbeddingProvider` per
   `docs/design/05-llm-and-embeddings.md` §5.1 (covers Ollama and
   OpenAI; the wire shape is the OpenAI-compatible `/embeddings`
   endpoint).
2. **The model identity guard** — `EmbeddingMetadataDao` (sole
   writer of `embedding_metadata`) + `EmbeddingMetadataStartupGuard`
   (`@Startup @Priority(125)`, between Flyway 100 and M1-033's
   `LlmRouterStartupGuard` 150). Per `docs/spec/llm.md`
   §Embedding pipeline: dimensionality mismatch silently corrupts
   cosine similarity, so the guard refuses startup on mismatch
   unless `infochat.embeddings.allow-model-change=true` (the
   operator override that bypasses the check and rotates the
   metadata).
3. **The Embedding pipeline worker** — `EmbeddingWorker`, with
   batched embedding, one-failure-fails-batch retry policy
   (no per-post split on retry), per-vector dimensionality fatal
   guard, no-vector release path on persistent batch failure.
4. **The Stage 5 RAW → READY promoter** — `ReadyPromoter`, with
   the `pg_notify('new_post', ...)` emit inside the SAME DB
   transaction as the `status='READY'` UPDATE (idempotency
   invariant from `docs/spec/architecture.md` §Inter-service
   communication).
5. **The first `new_post` NOTIFY in the codebase.** M1-027's
   `NewPostListener` is the Provider-side consumer; this ticket
   is the Collector-side emitter. After this ticket merges, the
   end-to-end ingest pipeline (RSS → Fetcher → SSRF gate → outbox
   → Stage 1 → Stage 2 if flagged → Tagger → Embedding → READY →
   Provider listener) fires real production NOTIFYs.
6. **Two new ITs** — `EmbeddingWorkerIT` (happy / batch-fail /
   wrong-shape / dim-mismatch / pre-promotion) and
   `ReadyPromoterIT` (happy + same-transaction rule + quarantined
   exclusion + stage2_failed release + startup-guard scenarios).

Pipeline boundary picked up from M1-034a (where TaggerWorker
writes `tagger_done=true`):

```
status='RAW' AND tagger_done=true AND embedding_done=false
    → EmbeddingWorker (this ticket)
       ↓
status='RAW' AND tagger_done=true AND embedding_done=true
    → ReadyPromoter (this ticket)
       ↓
status='READY', ready_at=now(), NOTIFY new_post {ready_at, post_id}
       ↓
M1-027's NewPostListener wakes, invokes NewPostHandler.handle(...)
```

## Definition of Done

- **`OpenAiCompatibleEmbeddingProvider.java`** implements
  `EmbeddingProvider` (the M1-007b SPI). Issues `POST
  <base-url>/embeddings` with body `{"model": "...", "input":
  ["text1", "text2", ...]}` and returns
  `List<EmbeddingResult>` in input order. Reads
  `(base-url, api-key, model)` via `@ConfigProperty`
  (`infochat.embeddings.base-url`, `infochat.embeddings.api-key`,
  `infochat.embeddings.model`). Does NOT route through the
  `(ModelTask, scope_language)` router — embedding has its own
  resolution path per `docs/spec/llm.md` §SPI shape "Scope of
  the enum."
- **`EmbeddingMetadataDao.java`** is the SOLE writer to
  `embedding_metadata` in M1. Two SQL shapes: read the singleton
  row (`SELECT model_identifier, dimension FROM embedding_metadata
  LIMIT 1`); UPDATE the singleton row (used only by the operator-
  override path in the startup guard). V11's seed INSERT from
  M1-034a is in a `.sql` migration file (not under `src/main/
  java/`) so the "sole writer in Java" boundary is mechanical.
- **`EmbeddingMetadataStartupGuard.java`** is a Collector-side
  `@Startup` bean at `@Priority(125)` (between Flyway 100 and
  M1-033's `LlmRouterStartupGuard` 150). At startup: reads
  `infochat.embeddings.model` and `infochat.embeddings.dimension`,
  compares to `embedding_metadata` (via Dao). On mismatch:
  - If `infochat.embeddings.allow-model-change=true`: UPDATE
    metadata to the new values, log WARN with the rotation
    (old → new), allow startup.
  - Otherwise: refuse startup with a fatal log line naming the
    stored value, the configured value, and the re-embed
    procedure path (`docs/design/02-schema.md` §2.8).
- **`EmbeddingWorker.java`** is a `@Scheduled` polling worker.
  Pickup: `status='RAW' AND tagger_done=true AND
  embedding_done=false`. Batches by
  `infochat.embeddings.batch-size` (laptop default 16). For
  each batch:
  - Builds per-post input text: `title + "\n\n" + (body_summary
    OR first 800 chars of body)` per
    `docs/design/05-llm-and-embeddings.md` §5.5 step 1.
  - Invokes `provider.embed(List<String>)` with the N inputs.
  - **One-failure-fails-batch retry** (per `docs/spec/llm.md`
    §Embedding pipeline + §Failure handling (recap)): any
    batch failure (exception, wrong-shape result, per-element
    error) retries the SAME batch ONCE (no per-post split).
    On second failure: every post in the batch follows the
    no-vector release path (`embedding_done=true`, NO
    `post_embedding` row, log WARN
    `error_class='embedding.batch_failure'`).
  - **Per-vector dimensionality fatal** (per `docs/spec/llm.md`
    §Embedding pipeline "Dimensionality mismatch at runtime is
    fatal"): if a returned vector's length differs from
    `embedding_metadata.dimension`, throw immediately (no retry
    — this is NOT a batch-failure-retry case but a metadata-
    invariant violation). The thrown exception unwinds the
    batch transaction (no rows inserted, `embedding_done` stays
    false — operator runs the re-embed procedure).
  - On batch success: INSERTs N `post_embedding` rows;
    `embedding_model` is read from `embedding_metadata` via
    Dao (the canonical record per the model identity guard).
    UPDATEs each post's `embedding_done=true`.
  - Concurrency bounded by `infochat.embeddings.max-concurrency`
    (laptop 4 / vps 2 / pi 1 / remote-llm 8 per §5.7).
- **`ReadyPromoter.java`** is a `@Scheduled` polling worker.
  Pickup: `status='RAW' AND stage1_done=true AND
  (stage1_flagged=false OR stage2_done=true) AND tagger_done=true
  AND embedding_done=true`. For each post (or in batched ticks
  — implementation choice, document it):
  - **In a single `@Transactional` boundary:**
    - UPDATE `post SET status='READY', ready_at=now(),
      status_changed_at=now() WHERE id=:post_id AND
      fetched_at=:fetched_at`.
    - Emit `pg_notify('new_post', '<JSON payload>')` where
      payload is `{"ready_at": "<iso8601>", "post_id":
      "<uuid>"}`.
  - **Same-transaction rule** is the correctness invariant:
    a NOTIFY outside the transaction would survive a rollback
    as a phantom event, advancing the Provider cursor past
    a non-existent post.
  - Quarantined posts NEVER promoted (the `status='RAW'`
    filter is the only path; this class never writes
    `status='QUARANTINED'`).
- **NOTIFY payload contract.** The format is
  `{"ready_at":"<iso8601-instant>","post_id":"<uuid>"}` —
  values produced by `Instant.toString()` (ISO-8601, since
  M1-027's parser calls `Instant.parse`) and `UUID.toString()`
  (canonical form, since the parser calls `UUID.fromString`).
  Payload built inline in `ReadyPromoter` — no shared helper
  extracted (per the no-infochat-provider-edit out-of-scope).
  The contract is the JSON byte shape, not a shared class.
- **`application.properties`** amended with:
  `infochat.embeddings.{base-url, api-key, model, dimension,
  batch-size, max-concurrency, allow-model-change,
  poll-interval}` with laptop-profile defaults
  (model=`nomic-embed-text`, dimension=768 matching V11's
  seed, batch-size=16, max-concurrency=4,
  allow-model-change=false, poll-interval=5s).
- **`EmbeddingWorkerIT.java`** — `@QuarkusTest` against real
  Postgres + stub `EmbeddingProvider` (`@Alternative
  @Priority(MAX_VALUE)`). Five `@Test` methods covering happy /
  batch-fail / wrong-shape / dim-mismatch / pre-promotion.
- **`ReadyPromoterIT.java`** — `@QuarkusTest`. Five `@Test`
  methods covering happy + JDBC `LISTEN` fixture / same-
  transaction rule / quarantined exclusion / stage2_failed
  release / startup model identity guard (both fail-fast and
  allow-model-change paths).
- `mvn -B clean verify` from the repo root exits 0; M1-027's
  `NewPostListenerIT` continues to pass against the new
  production-emit path (the byte shape matches the test-harness
  shape M1-027 used).

## Implementation notes

- **Startup-bean priority ordering.** Flyway 100 →
  `EmbeddingMetadataStartupGuard` 125 → M1-033
  `LlmRouterStartupGuard` 150. The model identity check must
  run AFTER Flyway (the `embedding_metadata` row must exist —
  V11's seed from M1-034a) and BEFORE the LLM router guard
  (operator-debugging-friendly: model identity is the most
  fundamental data invariant; router config is operator
  preference).
- **EmbeddingWorker pickup invocation shape.** `@Scheduled`
  polling, matching M1-034a `TaggerWorker` and M1-028
  `FetchScheduler`. A separate batch-flush timer fires when
  the in-memory batch buffer is full OR a profile-driven
  timeout elapses.
- **ReadyPromoter invocation shape.** Same — `@Scheduled`
  polling for posts ready to promote.
- **Test-profile scheduler is halted (set by M1-034a).**
  `infochat-collector/src/main/resources/application.properties`
  carries `%test.quarkus.scheduler.start-mode=halted` so
  background `@Scheduled` ticks do not pollute `@QuarkusTest`
  assertions on shared `@ApplicationScoped` beans across ITs.
  `EmbeddingWorkerIT` and `ReadyPromoterIT` MUST drive their
  workers explicitly — invoke the `@Scheduled` method on
  `EmbeddingWorker` and `ReadyPromoter` directly per the M1-034a
  `TaggerWorkerIT` and M1-033 `Stage2WorkerIT` pattern (direct
  method call against the injected bean; no `Thread.sleep` on a
  background tick). No edit to `application.properties` is needed
  in this ticket — the property is already on `main` from M1-034a;
  the `infochat.embeddings.poll-interval` key this ticket adds
  governs production cadence only.
- **One-failure-fails-batch and the not-split-on-retry rule.**
  Per `docs/spec/llm.md` §Failure handling (recap): "Retry
  policy: on a batch failure the same batch is resubmitted
  as-is; the batch is not split on retry. If batch size
  correlates with failures, operators reduce the
  profile-driven batch size — the spec does not introduce a
  per-retry split path." The retry passes the IDENTICAL
  `List<String>` input to `provider.embed`; no per-failure
  halving, no per-post fallback.
- **Dimensionality mismatch is fatal at runtime.** Throwing
  immediately on per-vector dim mismatch keeps the worker
  from advancing `embedding_done=true` and lets the operator
  surface the issue via logs. The startup guard catches the
  configuration case; the runtime check catches the mid-
  process provider-version-switch case (e.g. Ollama pulls a
  different model version between calls).
- **`ready_at` is set ONLY in `ReadyPromoter`.** Per
  Invariant 5, `status='RAW'` is the in-flight representation
  and the per-stage flags are the durable cursor. The
  transition to `status='READY'` happens here at Stage 5;
  no earlier ticket sets `ready_at`.
- **NOTIFY payload format must match M1-027's parser.**
  M1-027's `NewPostListener.parsePayload` (package-private
  static, in
  `infochat-provider/src/main/java/io/infochat/provider/outbox/NewPostListener.java`)
  extracts `ready_at` via regex
  `"ready_at"\s*:\s*"([^"]+)"` then calls `Instant.parse`,
  and `post_id` via `"post_id"\s*:\s*"([^"]+)"` then calls
  `UUID.fromString`. The two regexes are independent so
  field order does not matter; whitespace between key and
  colon is tolerated. ReadyPromoter builds the payload
  inline (no shared helper — M1-027's parser lives in
  `infochat-provider` and extracting it would violate this
  ticket's out-of-scope rule against Provider-module edits).
  The emit code uses `Instant.now().toString()` and
  `UUID.toString()` so both `Instant.parse` and
  `UUID.fromString` round-trip cleanly. The contract is
  the JSON byte shape, not a shared class. Run
  `Instant.parse(emitted)` and `UUID.fromString(emitted)` in
  your head before declaring the format right.
- **`status_changed_at` is set on every status transition**
  per `docs/design/02-schema.md` §2.3.1. The RAW → READY
  transition fires the `status_changed_at = now()` write
  alongside the `status = 'READY'` write — foundation for
  the future T2-G `quarantine_review` cursor that uses
  `status_changed_at`.
- **`embedding_model` column in `post_embedding`** records
  the active embedding model identifier (e.g.
  `'nomic-embed-text'`) so a future re-embed can compare
  per-row models without consulting the metadata table. The
  value is read from `embedding_metadata.model_identifier`
  (the canonical record) at INSERT time, via
  `EmbeddingMetadataDao`.
- **Stub providers in the ITs.** Hand-written
  `TestStubEmbeddingProvider` (for `EmbeddingWorkerIT`)
  selected via `@Alternative @Priority(Integer.MAX_VALUE)`
  for the test profile. The `ReadyPromoterIT` startup-guard
  scenarios use a Quarkus `@TestProfile` per scenario to
  config-override `infochat.embeddings.model` and
  `infochat.embeddings.allow-model-change`.
- **JDBC LISTEN test fixture.** Real Postgres NOTIFY, not
  an in-process mock — same pattern as M1-027's
  `NewPostListenerIT`. Open a separate JDBC connection,
  issue `LISTEN new_post`, poll
  `connection.unwrap(PGConnection.class).getNotifications()`
  with a bounded wait.

## Big-picture notes

- **The pipeline is now end-to-end.** With M1-034a + M1-034b
  landed alongside M1-032 + M1-033, the full chain runs:
  RSS feed → Fetcher (M1-023) → SSRF gate (M1-024/025/026)
  → outbox (M1-028) → Stage 1 (M1-032) → Stage 2 if flagged
  (M1-033) → Tagger (M1-034a) → Embedding (M1-034b) →
  status='READY' + NOTIFY (M1-034b) → Provider catch-up
  listener (M1-027). T1-D closes the eval pipeline; T1-E
  adds the messaging adapter and T1-F adds the first
  user-facing commands.
- **The post is fully visible to the Provider once
  status='READY' fires.** M1-027's `NewPostListener` is the
  Provider-side consumer. Once this ticket fires
  `pg_notify('new_post', {ready_at, post_id})`, the Provider's
  listener wakes, invokes `NewPostHandler.handle(...)`,
  advances the `provider_state` cursor for the `new_post`
  channel. The stub handler from M1-027 logs the event;
  T1-F adds the real cache-invalidation +
  group-digest-recompute logic inside the same
  `@Transactional` boundary.
- **The Embedding model identity guard is load-bearing.**
  Per `docs/spec/llm.md` §Embedding pipeline, dimensionality
  mismatch corrupts cosine similarity scores. The startup
  guard makes the failure mode reachable only via explicit
  operator override; the runtime fatal-throw catches the case
  where a provider silently switches model versions mid-
  process. Without the guard, an operator who changes
  `infochat.embeddings.model` without re-embedding gets a
  silently-corrupted vector store.
- **The `one-failure-fails-batch` retry is intentional.**
  Per `docs/spec/llm.md` §Embedding pipeline: "Silently
  dropping some posts from a batch result without a clean
  per-post error mapping is a worse failure mode than a
  uniform retry." A partial-success path would silently lose
  the dropped post; a uniform retry-then-no-vector-for-all
  path is audit-friendly (the operator sees the batch-failure
  WARN log and knows N posts have no vector).
- **The `pg_notify` is inside the SAME transaction as the
  `status='READY'` UPDATE.** Per
  `docs/spec/architecture.md` §Inter-service communication:
  "the high-water mark advances both fields in the same DB
  transaction as the side effect it triggers, making
  processing idempotent. ... a duplicate NOTIFY or a repeated
  catch-up pass for the same row produces no additional side
  effect." If the NOTIFY emit were outside the transaction,
  a rollback AFTER the NOTIFY would leave a phantom event
  on the wire (no row to back it); the Provider listener
  would process it and CAS-advance the cursor past a
  non-existent post. Same transaction is the correctness
  invariant.
- **First production NOTIFY in the codebase.** Before this
  ticket lands, the `new_post` channel exists but no
  production code writes to it (M1-027's IT used a test-
  harness JDBC `NOTIFY` to exercise the listener). After
  this ticket, the real ingest pipeline fires the NOTIFY
  and M1-027's listener handles it end-to-end.

## Out-of-scope expansion

- **V11 migration changes, V12, or any other Flyway file.**
  V11 lands in M1-034a; this ticket reads/inserts/updates
  the V11 tables but adds no SQL migration.
- **Tagger code, TaggerWorker, TagVocabulary, tagger.md,
  tagger-fallback.md, infochat.llm.tagger.* properties.**
  All M1-034a. This ticket reads `tagger_done=true` as the
  EmbeddingWorker pickup criterion but does not touch the
  Tagger.
- **AnthropicEmbeddingProvider.** T3-D. v1's first concrete
  EmbeddingProvider is OpenAI-compatible only.
- **EntityExtractor / Re-eval / quarantine commands /
  TranslationProvider / chat-agent.** Per the
  `out_of_scope:` list.
- **infochat-provider module changes.** The Provider-side
  `NewPostListener` from M1-027 will observe this ticket's
  pg_notify but is NOT modified; the contract is the JSON
  payload byte shape.
- **partition_pruner job, scripts/reembed.sh.** Operator
  tooling / T2.
- **Prometheus metric emit for `embedding_batch_failure_total`,
  per-task latency histograms.** T2 observability.
- **Real `NewPostHandler` consumer logic** (cache
  invalidation, group-digest recompute). T1-F. M1-027's
  stub handler stays.

## Authorized test changes

- (none — this ticket adds two new test files
  `EmbeddingWorkerIT.java` and `ReadyPromoterIT.java`. No
  pre-existing tests are modified. M1-027's
  `NewPostListenerIT` continues to pass — its test-harness
  NOTIFY path is independent of this ticket's
  production-emit path; both fire the same payload byte
  shape so the M1-027 parser handles either without code
  change. M1-034a's `TaggerWorkerIT` continues to pass; the
  two workers operate on disjoint cursor positions and
  share no test fixtures.)

## Alternatives considered

- **Bundle V11 migration here.** Rejected per the M1-034
  split rationale (see `aborted_attempts:` on M1-034 and
  the Context section). V11 must land before
  `EmbeddingMetadataStartupGuard` runs; placing V11 with
  M1-034a's Tagger pipeline (where it is also useful for
  the eventual semantic-similarity queries downstream) is
  cleaner than racing Flyway against the guard in the
  first boot of a single ticket.
- **Route embedding through the (ModelTask, scope_language)
  router.** Rejected on spec grounds. Per
  `docs/spec/llm.md` §SPI shape "Scope of the enum. The
  embedder is not a ModelTask — EmbeddingProvider is a
  distinct SPI with its own provider selection." One
  provider per deployment; no per-language or per-task
  routing.
- **Split the embedding batch on retry.** Rejected on
  spec grounds. Per `docs/spec/llm.md` §Failure handling
  (recap): "Retry policy: on a batch failure the same
  batch is resubmitted as-is; the batch is not split on
  retry."
- **Treat dimensionality mismatch as a retry-able batch
  failure rather than a fatal-at-runtime error.** Rejected
  on spec grounds. Per `docs/spec/llm.md` §Embedding
  pipeline: "Dimensionality mismatch at runtime is fatal.
  ... The only safe recovery is a full re-embed." A retry
  against the same misconfigured provider produces the
  same wrong-dim result.
- **Auto-update `embedding_metadata` on first observed
  mismatch without the operator override flag.** Rejected
  on spec grounds. Per `docs/spec/llm.md` §Embedding
  pipeline: "An explicit operator override flag bypasses
  the check for intentional migration runs." Silent
  auto-update would let an accidental model change corrupt
  the vector store.
- **Emit `pg_notify` outside the `status='READY'`-UPDATE
  transaction.** Rejected on spec grounds. Per
  `docs/spec/architecture.md` §Inter-service communication:
  "the high-water mark advances both fields in the same DB
  transaction as the side effect it triggers." A NOTIFY
  outside the transaction would survive a rollback as a
  phantom event.
- **Set `post.status='READY'` in the EmbeddingWorker rather
  than a dedicated ReadyPromoter.** Rejected on cohesion
  grounds. The Embedding boundary is `embedding_done=true`;
  the Stage-5 promotion is the readiness boundary. A
  dedicated ReadyPromoter keeps the @Transactional
  boundary narrow (one UPDATE + one NOTIFY) and makes the
  same-transaction rule visible in a single method body.
  Also: a future ticket that adds a NEW pre-promotion
  check (e.g. content moderation) can add a flag without
  modifying the EmbeddingWorker.
- **Land the AnthropicEmbeddingProvider here so the SPI
  is exercised against two providers.** Rejected per
  session-grouping-plan §Tier 3. T3-D ships the Anthropic
  providers. v1's first concrete EmbeddingProvider is
  OpenAI-compatible only.
- **Defer the model identity guard to a later T2
  observability ticket.** Rejected on spec grounds. The
  guard is the load-bearing safety mechanism that prevents
  silent vector-store corruption; it MUST land with the
  first EmbeddingProvider impl, not later.
