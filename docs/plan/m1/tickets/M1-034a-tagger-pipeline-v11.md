---
id: M1-034a
title: Tagger pipeline + V11 (post_embedding + embedding_metadata)
status: pending
created: 2026-05-17
last_updated: 2026-05-17
decomposed_from: M1-034
blocked_by:
  - M1-008b
  - M1-008c
  - M1-033
files_budget: 7
files_scope:
  - infochat-core/src/main/resources/db/migration/V11__post_embedding.sql
  - infochat-llm-adapter/src/main/resources/prompts/tagger.md
  - infochat-llm-adapter/src/main/resources/prompts/tagger-fallback.md
  - infochat-collector/src/main/java/io/infochat/collector/eval/tagger/TagVocabulary.java
  - infochat-collector/src/main/java/io/infochat/collector/eval/tagger/TaggerWorker.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/io/infochat/collector/eval/tagger/TaggerWorkerIT.java
complexity: medium
risk: medium
round_cap: 3
security_relevant: false
migration_touch: true
out_of_scope:
  - any Stage 1 HTML sanitizer, Unicode normalization, prompt-injection regex set, watchdog, placeholder-id generation, or quarantine-row insertion code (M1-032 territory — consumed unchanged; the Tagger reads post.body which is already Stage-1-redacted)
  - any Stage 2 LLM judge call, BENIGN/INJECTION/MALWARE/UNKNOWN verdict handling, retry-once-then-fallback, stage2_done advance, stage2_failed flag, OpenAiCompatibleProvider, LlmRouter, or release-on-stage2-failure config-flag wiring (M1-033 territory — the (ModelTask, scope_language) router authored in M1-033 is CONSUMED for the TAGGER task)
  - any concrete EmbeddingProvider impl (OpenAiCompatibleEmbeddingProvider) — M1-034b territory. V11 creates the embedding_metadata seed row here so M1-034b's startup guard has a row to compare against, but the provider itself is M1-034b
  - any EmbeddingMetadataDao, EmbeddingMetadataStartupGuard, EmbeddingWorker, ReadyPromoter, or pg_notify('new_post', ...) emission — M1-034b territory. M1-034a writes post.tagger_done=true; M1-034b picks up from there
  - any post_embedding INSERT — the table is created here in V11 but no code in M1-034a writes a row; the EmbeddingWorker in M1-034b is the sole writer
  - any EntityExtractor, post_entity table, post_reference table, LinkingJob — T2 territory
  - any Re-evaluation job, attempt counter, QUARANTINED → NEEDS_REVIEW transition, per-source UNKNOWN auto-disable — T2-G territory
  - any throttled admin notifier wiring — T2-G; the Tagger fallback path logs at INFO/WARN with canonical error_class strings for the future notifier to pick up
  - any LLM output sanitizer — T1-F territory; tagger output is validated against the controlled vocabulary, invalid tags are silently dropped per the partial-valid rule
  - any change to V1..V10 Flyway migrations (V10 from M1-032 is consumed unchanged; this ticket adds V11)
  - any change to the M1-007b LlmProvider / EmbeddingProvider / ModelTask SPI surfaces (frozen)
  - any infochat-provider module change
  - any partition_pruner job — T2 territory
  - any embedding-model migration script (scripts/reembed.sh)
  - any Prometheus/Micrometer metric emit for tagger_partial_valid_total or per-task latency histograms
acceptance:
  - "infochat-core/src/main/resources/db/migration/V11__post_embedding.sql exists and creates the post_embedding table per docs/design/02-schema.md §2.4.2 — grep -E 'CREATE TABLE\\s+post_embedding\\s*\\(' V11__post_embedding.sql returns at least one match"
  - "V11 declares post_embedding with column post_id UUID NOT NULL — grep -E 'post_id\\s+UUID\\s+NOT NULL' V11__post_embedding.sql returns at least one match"
  - "V11 declares post_embedding with column embedding vector(768) NOT NULL (the laptop/vps default per docs/design/05-llm-and-embeddings.md §5.5; the pi/remote-llm variants are operator-selected via alternative migration file or operator override per §2.8 — document the operator path in the migration's header comment) — grep -E 'embedding\\s+vector\\s*\\(\\s*768\\s*\\)\\s+NOT NULL' V11__post_embedding.sql returns at least one match"
  - "V11 declares post_embedding with column embedding_model TEXT NOT NULL — grep -E 'embedding_model\\s+TEXT\\s+NOT NULL' V11__post_embedding.sql returns at least one match"
  - "V11 declares post_embedding with column fetched_at TIMESTAMPTZ NOT NULL — grep -E 'fetched_at\\s+TIMESTAMPTZ\\s+NOT NULL' V11__post_embedding.sql returns at least one match"
  - "V11 declares post_embedding PRIMARY KEY (post_id, fetched_at) — grep -E 'PRIMARY KEY\\s*\\(\\s*post_id\\s*,\\s*fetched_at\\s*\\)' V11__post_embedding.sql returns at least one match"
  - "V11 declares post_embedding PARTITION BY RANGE (fetched_at) — grep -E 'PARTITION BY RANGE\\s*\\(\\s*fetched_at\\s*\\)' V11__post_embedding.sql returns at least one match"
  - "V11 creates at least one initial partition of post_embedding so the schema is queryable on day one (matching M1-008c's post partitioning pattern) — grep -E 'CREATE TABLE\\s+post_embedding_[0-9]+\\s+PARTITION OF\\s+post_embedding' V11__post_embedding.sql returns at least one match"
  - "V11 creates the HNSW vector index per docs/design/02-schema.md §2.4.2 (laptop/vps/remote-llm: HNSW with m=16, ef_construction=64; the pi profile's IVFFlat variant is a separate operator choice — document in the migration header) — grep -E 'USING\\s+hnsw\\s*\\(\\s*embedding\\s+vector_cosine_ops\\s*\\)' V11__post_embedding.sql returns at least one match"
  - "V11 creates the embedding_metadata singleton table per docs/spec/llm.md §Embedding pipeline 'Model identity guard. The active embedding model's identifier and vector dimensionality are stored in a singleton metadata row on first use.' Shape: embedding_metadata(model_identifier TEXT NOT NULL, dimension INT NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now()), singleton-enforced via CREATE UNIQUE INDEX ON embedding_metadata ((TRUE)) — grep -E 'CREATE TABLE\\s+embedding_metadata\\s*\\(' V11__post_embedding.sql returns at least one match AND grep -E 'CREATE UNIQUE INDEX.*embedding_metadata.*\\(\\(TRUE\\)\\)|CREATE UNIQUE INDEX.*embedding_metadata.*\\(\\(true\\)\\)' V11__post_embedding.sql returns at least one match"
  - "V11 INSERTs the default embedding_metadata row matching the laptop/vps profile (model_identifier='nomic-embed-text', dimension=768 per docs/design/05-llm-and-embeddings.md §5.5 Model and dimension by profile) — grep -E 'INSERT\\s+INTO\\s+embedding_metadata' V11__post_embedding.sql returns at least one match AND grep -E 'nomic-embed-text' V11__post_embedding.sql returns at least one match AND grep -E '\\b768\\b' V11__post_embedding.sql returns at least one match"
  - "V11 grants Collector write access to post_embedding (it will run the embedding worker in M1-034b) — grep -E 'GRANT\\s+SELECT\\s*,\\s*INSERT\\s+ON\\s+post_embedding\\s+TO\\s+infochat_collector' V11__post_embedding.sql returns at least one match"
  - "V11 grants Provider read access to post_embedding (for later T1-F /summary and T2-D chat-agent semantic-similarity queries) — grep -E 'GRANT\\s+SELECT\\s+ON\\s+post_embedding\\s+TO\\s+infochat_provider' V11__post_embedding.sql returns at least one match"
  - "V11 grants Collector SELECT, INSERT, UPDATE on embedding_metadata (Collector enforces the startup guard in M1-034b and may UPDATE on operator override) — grep -E 'GRANT\\s+SELECT\\s*,\\s*INSERT\\s*,\\s*UPDATE\\s+ON\\s+embedding_metadata\\s+TO\\s+infochat_collector' V11__post_embedding.sql returns at least one match"
  - "V11 grants Provider read access to embedding_metadata (for diagnostic) — grep -E 'GRANT\\s+SELECT\\s+ON\\s+embedding_metadata\\s+TO\\s+infochat_provider' V11__post_embedding.sql returns at least one match"
  - "V11 does NOT create post_entity, post_reference, or any LinkingJob-related tables (T2 territory per the session-grouping-plan T1-D row) — grep -E 'CREATE TABLE\\s+(post_entity|post_reference)' V11__post_embedding.sql returns zero matches"
  - "infochat-llm-adapter/src/main/resources/prompts/tagger.md exists and follows the JSON-primary template at docs/design/05-llm-and-embeddings.md §5.4.2: instructs the model to assign 1..4 tags from a controlled vocabulary; demands JSON output {\"tags\": [\"tag1\",\"tag2\"]}; lists the vocabulary inline via Mustache/Qute iteration; wraps the post body in the per-call random delimiter — grep -E '\\{\"tags\":' tagger.md returns at least one match AND grep -E '<<<UNTRUSTED_CONTENT' tagger.md returns at least one match AND grep -E '\\{#tags\\}|\\{\\{#tags\\}\\}' tagger.md returns at least one match"
  - "infochat-llm-adapter/src/main/resources/prompts/tagger-fallback.md exists and follows the line-oriented fallback template (single 'TAGS: tag1, tag2, tag3' line, no JSON; designed for small models that struggle with JSON mode) — grep -E 'TAGS:' tagger-fallback.md returns at least one match AND grep -E '\\{#tags\\}|\\{\\{#tags\\}\\}' tagger-fallback.md returns at least one match AND grep -E '<<<UNTRUSTED_CONTENT' tagger-fallback.md returns at least one match"
  - "TagVocabulary.java is an @ApplicationScoped CDI bean that loads the controlled vocabulary from the tag table (seeded in M1-008b) into an immutable Set<String> ONCE at startup. The loaded names are normalized to NFC + Locale.ROOT lower-case + character class [a-z0-9][a-z0-9-]{0,47} (the tag stored form per docs/spec/schema.md §Tag stored form / docs/design/02-schema.md §2.2.1) so the membership check is byte-equal against the tagger output's same-rule normalization — grep -E 'class\\s+TagVocabulary' TagVocabulary.java returns at least one match AND grep -E 'SELECT\\s+name\\s+FROM\\s+tag|FROM\\s+tag\\s+ORDER' TagVocabulary.java returns at least one match AND grep -E 'Locale\\.ROOT|toLowerCase\\s*\\(\\s*Locale' TagVocabulary.java returns at least one match"
  - "TaggerWorker.java is a Collector-side @Scheduled polling worker (matching the M1-028 FetchScheduler / M1-033 Stage2Worker pattern). Pickup criteria: status='RAW' AND stage1_done=true AND (stage1_flagged=false OR stage2_done=true) AND tagger_done=false. Quarantined posts are excluded by the status='RAW' filter (Stage 2 INJ/MAL/UNK and Stage 1 watchdog fail-closed both set status='QUARANTINED') — grep -E 'class\\s+TaggerWorker' TaggerWorker.java returns at least one match AND grep -E \"status\\s*=\\s*'RAW'\" TaggerWorker.java returns at least one match AND grep -E 'tagger_done\\s*=\\s*FALSE|tagger_done\\s*=\\s*false' TaggerWorker.java returns at least one match AND grep -E '@Scheduled' TaggerWorker.java returns at least one match"
  - "TaggerWorker.java invokes the M1-033 LlmRouter with ModelTask.TAGGER and scope language 'en' (Tagger output is fixed-vocabulary tag names, not user-visible prose; scope language doesn't drive the tagger) — grep -E 'ModelTask\\.TAGGER|router\\.forTask\\s*\\(\\s*TAGGER' TaggerWorker.java returns at least one match"
  - "TaggerWorker.java's primary tagging path: load prompts/tagger.md, substitute the controlled vocabulary loaded from TagVocabulary + the per-call random {{id}} UUID + the post body + title; invoke provider.generate; parse strict JSON {\"tags\": [...]}; for each parsed tag normalize per the same NFC + Locale.ROOT lower-case + character class [a-z0-9][a-z0-9-]{0,47} rule used in TagVocabulary; check membership; keep valid tags, silently drop invalid per docs/spec/llm.md §Failure handling 'Partial-valid handling. ... the valid tags are kept and the invalid tags are silently dropped'. Records an INFO log entry naming the count of valid + invalid tags so a future operator alert on sustained high invalid rates has the data — grep -E 'tagger\\.partial_valid|valid.*invalid|tagger_partial' TaggerWorker.java returns at least one match"
  - "TaggerWorker.java's three-surface fallback chain per docs/spec/security.md §Failure handling 'Tagger failure → fall back to source.bootstrap_tags, mark the post, throttled admin notify' AND docs/spec/llm.md §Failure handling (recap): (a) schema-violating output (JSON parse throws OR the parsed object lacks a 'tags' array) → retry once with tagger-fallback.md (different prompt because re-issuing the same JSON-mode prompt to the same model produces the same garbage); (b) zero valid tags after partial-valid handling (the JSON parsed but ZERO entries passed vocabulary validation) → retry once with the SAME primary prompt (vocabulary mismatch is a content issue, not a prompt-shape issue); (c) LLM unreachable / timeout → retry once with the SAME primary prompt (transient infrastructure issue). On second failure of any path: post.tags = source.bootstrap_tags AND post.tagger_fallback=true AND log WARN with canonical error_class='tagger.fallback_to_bootstrap'. Document the per-path retry choice in TaggerWorker's class JDoc — grep -E 'tagger_fallback|tagger\\.fallback_to_bootstrap' TaggerWorker.java returns at least one match AND grep -E 'bootstrap_tags|source\\.bootstrap_tags' TaggerWorker.java returns at least one match"
  - "TaggerWorker.java's tagger_done=true UPDATE is the persistence cursor for the Tagger boundary per Invariant 5 (docs/spec/schema.md §Invariants 'the per-stage flags are the durable cursor'). UPDATE post SET tags=:tags, tagger_done=true, tagger_fallback=:fallback WHERE id=:post_id AND fetched_at=:fetched_at — the same statement writes both the tag array and the cursor flags atomically — grep -E 'tagger_done\\s*=\\s*TRUE|tagger_done\\s*=\\s*true' TaggerWorker.java returns at least one match AND grep -E 'tags\\s*=' TaggerWorker.java returns at least one match"
  - "TaggerWorker.java's concurrency is bounded by infochat.llm.tagger.max-concurrency (laptop default 4 per docs/design/05-llm-and-embeddings.md §5.7); the bounded-concurrency shape matches M1-033's Stage 2 semaphore — grep -E 'infochat\\.llm\\.tagger\\.max-concurrency|tagger\\.maxConcurrency' TaggerWorker.java returns at least one match"
  - "application.properties under infochat-collector/src/main/resources is amended to add the tagger property surface (the embedding property surface lands in M1-034b). Required keys: infochat.llm.tagger.base-url, infochat.llm.tagger.api-key, infochat.llm.tagger.model (default llama3.1:8b for laptop per docs/design/05-llm-and-embeddings.md §5.7), infochat.llm.tagger.max-concurrency (default 4 for laptop per §5.7), infochat.llm.tagger.poll-interval (default 5s matching M1-028 FetchScheduler cadence — document choice in property comment) — grep -E 'infochat\\.llm\\.tagger\\.base-url' application.properties returns at least one match AND grep -E 'infochat\\.llm\\.tagger\\.model' application.properties returns at least one match AND grep -E 'infochat\\.llm\\.tagger\\.max-concurrency' application.properties returns at least one match"
  - "TaggerWorkerIT.java is a @QuarkusTest IT against real Postgres + a stub LlmProvider replacing the production provider for the test profile (@Alternative @Priority(MAX_VALUE) — same pattern as M1-033's Stage2WorkerIT). Seven @Test methods covering: (1) happy path — stub returns valid JSON {\"tags\":[\"security\",\"news\"]} where both are vocabulary members → post.tags=[\"security\",\"news\"], tagger_done=true, tagger_fallback=false; (2) partial-valid — stub returns {\"tags\":[\"security\",\"news\",\"NOTAVALIDTAG\"]} → post.tags=[\"security\",\"news\"], tagger_fallback=false, INFO log mentions partial-valid count; (3) zero-valid → bootstrap fallback (post.tags=<source.bootstrap_tags>, tagger_fallback=true); (4) schema-violating ('this is not json') → retry with fallback prompt; if retry returns 'TAGS: security, news', uses those (tagger_done=true, tagger_fallback=false); (5) total-fail (both prompts return garbage) → bootstrap fallback; (6) LLM unreachable (stub throws on every call) → retry once → bootstrap fallback; (7) status='QUARANTINED' post NOT picked up (tagger_done stays false) — grep -E '@Test' TaggerWorkerIT.java returns at least seven matches"
  - "mvn -B -pl infochat-collector -am verify exits 0; failsafe reports include TaggerWorkerIT — grep -rE 'Tests run: [1-9]' infochat-collector/target/failsafe-reports returns at least one new match for TaggerWorkerIT"
  - "mvn -B clean verify from the repo root exits 0; all prior tests (M1-003, M1-006, M1-007/007a/b/c, M1-008/008a/b/c, M1-009, M1-017, M1-022..M1-029, M1-032, M1-033) continue to pass alongside the new V11 migration and the Tagger pipeline. DbRoleMatrixIT (M1-006) continues to pass without modification — it asserts only role-presence + NOLOGIN, not a closed expected-grants list, so the new V11 GRANTs are non-breaking (this addresses the M1-034 clarity_check_at_abort TEST-CHANGES-AUTHORIZED warning). FlywayMigrationIT (M1-017) applies V11 alongside V1..V10 without edit"
test_plan:
  adds:
    - infochat-collector/src/test/java/io/infochat/collector/eval/tagger/TaggerWorkerIT.java (@QuarkusTest IT against real Postgres + stub LlmProvider exercising happy / partial-valid / zero-valid / schema-violating / total-fail / LLM-unreachable / quarantined-exclusion paths)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a)
    - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java (M1-007b)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java (M1-007c)
    - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (M1-007)
    - infochat-collector/src/test/java/io/infochat/collector/flyway/FlywayMigrationIT.java (M1-017 — V11 must apply cleanly alongside V1..V10)
    - infochat-collector/src/test/java/io/infochat/collector/db/DbRoleMatrixIT.java (M1-006 — asserts role-presence + NOLOGIN, not a closed grants list; the new V11 GRANTs are non-breaking)
    - all M1-008a/b/c schema tests
    - all M1-022/023/024/025/026/029 ingest + SSRF tests
    - M1-027's three provider outbox ITs
    - M1-028's PostPersisterIT + OutboxRehydratorIT + FetchSchedulerIT
    - M1-032's Stage1PipelineIT + Stage1WatchdogIT + Stage1RegexSetTest
    - M1-033's LlmRouterTest + Stage2WorkerIT + LocalOnlyConflictStartupIT
spec_refs:
  - docs/spec/security.md §Failure handling
  - docs/spec/security.md §DB roles
  - docs/spec/llm.md §SPI shape
  - docs/spec/llm.md §Per-task routing rules
  - docs/spec/llm.md §Failure handling (recap)
  - docs/spec/llm.md §Hardware profile contract
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/schema.md §Invariants
  - docs/spec/schema.md §Tag stored form
  - docs/spec/architecture.md §Pipelines
  - docs/design/01-architecture.md §1.3.4 Eval pipeline workers
  - docs/design/02-schema.md §2.2.1 tag
  - docs/design/02-schema.md §2.3.1 post
  - docs/design/02-schema.md §2.4.2 post_embedding
  - docs/design/02-schema.md §2.8 Embedding model migration
  - docs/design/05-llm-and-embeddings.md §5.4.2 Tagger
  - docs/design/05-llm-and-embeddings.md §5.5 Embeddings
  - docs/design/05-llm-and-embeddings.md §5.7 Profile defaults
  - docs/design/05-llm-and-embeddings.md §5.8 Failure handling per task
decision_refs:
  - D5
  - D22
  - D27
---

# M1-034a: Tagger pipeline + V11 (post_embedding + embedding_metadata)

## Context

First of two replacement tickets for the deferred M1-034 umbrella
(see `aborted_attempts:` on M1-034 for the split rationale). This
ticket lands:

1. **V11 Flyway migration** — creates `post_embedding` (partitioned
   by `fetched_at`, HNSW vector index) and the `embedding_metadata`
   singleton (seeded with the laptop/vps default). The schema is
   shipped here so M1-034b's `EmbeddingMetadataStartupGuard` has a
   row to compare against on first boot.
2. **The Tagger pipeline** — `TagVocabulary`, `TaggerWorker`, the
   two prompt files (`tagger.md` JSON-primary +
   `tagger-fallback.md` line-oriented retry), and the
   `infochat.llm.tagger.*` property surface.
3. **One IT** — `TaggerWorkerIT` exercising all three fallback
   surfaces (schema-violating / zero-valid / LLM-unreachable),
   partial-valid handling, the quarantined-exclusion filter, and
   the bootstrap-fallback audit flag.

Pipeline boundary written by this ticket:

```
status='RAW' AND stage1_done=true AND
  (stage1_flagged=false OR stage2_done=true) AND
  tagger_done=false
    → TaggerWorker (this ticket)
       ↓
status='RAW' AND tagger_done=true AND embedding_done=false
    → EmbeddingWorker (M1-034b)
```

M1-034b picks up where this ticket leaves off (post.tagger_done=true).

## Definition of Done

- **V11 migration** at
  `infochat-core/src/main/resources/db/migration/V11__post_embedding.sql`:
  - Creates `post_embedding(post_id UUID NOT NULL, embedding
    vector(768) NOT NULL, embedding_model TEXT NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL, PRIMARY KEY (post_id,
    fetched_at)) PARTITION BY RANGE (fetched_at)`. The dimension
    is the laptop/vps default; pi (vector(384)) and remote-llm
    (vector(1536)) are operator-selected via alternative migration
    file or override — document in the migration header.
  - Creates at least one initial partition of `post_embedding`.
  - Creates the HNSW vector index `USING hnsw (embedding
    vector_cosine_ops) WITH (m=16, ef_construction=64)`. The pi
    profile's IVFFlat variant is an operator choice — document.
  - Creates `embedding_metadata(model_identifier TEXT NOT NULL,
    dimension INT NOT NULL, updated_at TIMESTAMPTZ NOT NULL
    DEFAULT now())` with singleton enforcement via
    `CREATE UNIQUE INDEX ... ON embedding_metadata ((TRUE))`
    (the simpler shape from the original M1-034 implementation
    notes — no synthetic id column).
  - INSERTs the default row `(model_identifier='nomic-embed-text',
    dimension=768)` matching the laptop/vps embedding model.
  - Per-table GRANTs per `docs/spec/security.md` §DB roles:
    `GRANT SELECT, INSERT ON post_embedding TO infochat_collector`
    (Collector writes via M1-034b's EmbeddingWorker);
    `GRANT SELECT ON post_embedding TO infochat_provider`
    (Provider reads for future semantic-similarity queries);
    `GRANT SELECT, INSERT, UPDATE ON embedding_metadata TO
    infochat_collector` (Collector enforces the M1-034b startup
    guard; UPDATE on operator override);
    `GRANT SELECT ON embedding_metadata TO infochat_provider`
    (diagnostic).
  - **Does NOT** create `post_entity`, `post_reference`, or any
    LinkingJob tables (T2 territory).
- **`tagger.md`** under
  `infochat-llm-adapter/src/main/resources/prompts/` — JSON-primary
  template per `docs/design/05-llm-and-embeddings.md` §5.4.2:
  instructs the model to assign 1..4 tags from the controlled
  vocabulary; demands `{"tags": [...]}` JSON output; iterates the
  vocabulary inline via Qute (`{#tags}`) or Mustache (`{{#tags}}`);
  wraps the post body in
  `<<<UNTRUSTED_CONTENT id="{{id}}">>>...<<<END id="{{id}}">>>`
  with the per-call random `{{id}}` UUID.
- **`tagger-fallback.md`** — line-oriented retry template (single
  line `TAGS: tag1, tag2, tag3`, no JSON; designed for small
  models that struggle with JSON mode).
- **`TagVocabulary.java`** under
  `infochat-collector/src/main/java/io/infochat/collector/eval/tagger/`
  — `@ApplicationScoped` CDI bean. Loads `SELECT name FROM tag` once
  at startup into an immutable `Set<String>`. Loaded names are
  normalized using the **tag normalization rule** —
  **NFC + Locale.ROOT lower-case + character class
  `[a-z0-9][a-z0-9-]{0,47}`** (the tag stored form per
  `docs/spec/schema.md` §Tag stored form / `docs/design/02-schema.md`
  §2.2.1; this rule is **inlined here** rather than cross-referenced,
  addressing the M1-034 clarity SELF-CONTAINED-CHECK warning). The
  TaggerWorker output is normalized with the same rule so membership
  is byte-equal.
- **`TaggerWorker.java`** — Collector-side `@Scheduled` polling
  worker (matches M1-028 `FetchScheduler` and M1-033 `Stage2Worker`
  pattern). Pickup: `status='RAW' AND stage1_done=true AND
  (stage1_flagged=false OR stage2_done=true) AND tagger_done=false`.
  Quarantined posts excluded by the `status='RAW'` filter (Stage 2
  INJ/MAL/UNK and Stage 1 watchdog fail-closed both set
  `status='QUARANTINED'`). For each post:
  - Invokes `LlmRouter.forTask(TAGGER, "en")` to get the provider.
  - Loads `tagger.md`, substitutes the vocabulary, the per-call
    random UUID `{{id}}`, the post body + title.
  - Invokes `provider.generate(TAGGER, systemPrompt, userPrompt)`.
  - Parses reply as strict JSON `{"tags": [...]}`.
  - For each parsed tag: normalize per the tag normalization rule
    above, check `TagVocabulary` membership.
  - Keeps valid tags; silently drops invalid per the partial-valid
    rule. INFO log records the valid+invalid count.
  - **Three-surface fallback chain:**
    - Schema-violating (JSON parse fails or no `tags` array):
      retry once with `tagger-fallback.md` (different prompt — same
      garbage from same prompt is wasted effort).
    - Zero valid tags after partial-valid: retry once with the
      SAME primary prompt (vocabulary mismatch is a content issue,
      not prompt-shape).
    - LLM unreachable / timeout: retry once with the SAME primary
      prompt (transient infrastructure issue).
    - On second failure of any path:
      `post.tags = source.bootstrap_tags`,
      `post.tagger_fallback = true`, log WARN with canonical
      `error_class='tagger.fallback_to_bootstrap'`.
    - Document the per-path retry choice in the class JDoc.
  - UPDATE `post SET tags=:tags, tagger_done=true,
    tagger_fallback=:fallback WHERE id=:post_id AND
    fetched_at=:fetched_at` — atomic write of tags + cursor flags.
  - Concurrency bounded by `infochat.llm.tagger.max-concurrency`
    (laptop default 4 per `docs/design/05-llm-and-embeddings.md`
    §5.7).
- **`application.properties`** amended with:
  `infochat.llm.tagger.{base-url, api-key, model, max-concurrency,
  poll-interval}` with laptop-profile defaults
  (model=`llama3.1:8b`, max-concurrency=4, poll-interval=5s).
- **`TaggerWorkerIT.java`** — `@QuarkusTest` against real Postgres
  + a stub `LlmProvider` selected via `@Alternative
  @Priority(MAX_VALUE)` for the test profile. Seven `@Test` methods
  covering all six tagger paths plus the quarantined-exclusion
  filter (see acceptance items for the per-test contract).
- `mvn -B clean verify` from the repo root exits 0.

## Implementation notes

- **Migration version is V11.** V10 lands in M1-032. If a later
  authoring session lands an unrelated migration before this
  ticket starts, re-grep the migration directory at
  `/m1-tick start` time and slide this migration to V12 (and
  rename file).
- **Singleton enforcement on `embedding_metadata`.** Use
  `CREATE UNIQUE INDEX ON embedding_metadata ((TRUE))` — the
  predicate evaluates to a constant per row so only one row can
  satisfy uniqueness. Simpler than a synthetic `id INT DEFAULT 1
  + UNIQUE(id) + CHECK(id=1)` column.
- **Profile-specific dimensions.** V11 ships
  `vector(768)`. The pi profile's `vector(384)` and the remote-llm
  profile's `vector(1536)` are operator territory (alternative
  migration file or operator-issued `ALTER TABLE` at deploy time
  per `docs/design/02-schema.md` §2.8). Document the operator
  path in the V11 header comment.
- **HNSW vs IVFFlat index.** V11 ships HNSW (laptop/vps/
  remote-llm). The pi profile's IVFFlat is also operator
  territory. Document in the migration header.
- **Tagger pickup invocation shape.** `@Scheduled` polling
  matches the M1-028 `FetchScheduler` and M1-033 `Stage2Worker`
  pattern; poll interval 5s on laptop (documented in
  application.properties).
- **Tagger's two retry shapes.** Schema-violating retries with a
  DIFFERENT (line-oriented) prompt because re-issuing the same
  JSON-mode prompt to the same small model tends to produce the
  same garbage. Zero-valid-after-validation AND LLM-unreachable
  retry with the SAME prompt because those failure modes are
  unrelated to prompt shape (vocabulary mismatch is a content
  issue; unreachability is infrastructure). Document the choice
  in `TaggerWorker.java`'s JDoc.
- **Tag normalization rule inlined.** The full rule (`NFC +
  Locale.ROOT lower-case + character class
  [a-z0-9][a-z0-9-]{0,47}`) is in the DoD here, addressing
  M1-034's clarity SELF-CONTAINED-CHECK warning. The same rule
  applies in `TagVocabulary` (loaded vocabulary) and in
  `TaggerWorker` (parsed tagger output) so membership is
  byte-equal. If M1-008b already shipped a normalization helper
  in `infochat-core`, reuse it (one less new file); otherwise
  inline a small static helper in `TaggerWorker.java` and add a
  TODO comment to extract to `infochat-core` later. The reviewer's
  negative-space check should not flag a missing `infochat-core`
  file if the helper exists already.
- **Stub provider in the IT.** `TestStubLlmProvider` selected via
  `@Alternative @Priority(Integer.MAX_VALUE)` for the test
  profile. Same shape as M1-033's Stage 2 stub. Per-test scenario
  configures the stub's response (valid JSON, partial-valid JSON,
  zero-valid JSON, garbage, throws).
- **Mustache vs Qute templating.** Qute is built-in to Quarkus
  and supports the `{#tags}...{/tags}` iteration syntax the
  prompts need. Use Qute, avoid the Mustache dependency.
  Document the choice in the prompt header.
- **DbRoleMatrixIT non-breakage.** The IT (M1-006) asserts only
  role-presence + NOLOGIN attribute; it does NOT assert a closed
  expected-grants list. The new V11 GRANTs are non-breaking
  (this addresses the M1-034 clarity TEST-CHANGES-AUTHORIZED
  warning; verified by reading
  `infochat-collector/src/test/java/io/infochat/collector/db/DbRoleMatrixIT.java`
  at authoring time of this replacement ticket).

## Big-picture notes

- **M1-034b picks up at `tagger_done=true`.** This ticket's
  TaggerWorker writes the `tagger_done=true` cursor flag; M1-034b's
  EmbeddingWorker uses that as its pickup criterion. The two
  tickets share no class-level coupling — the contract is the
  state-machine flag on `post`.
- **The `embedding_metadata` seed row is the load-bearing handoff
  to M1-034b.** M1-034b's `EmbeddingMetadataStartupGuard` reads
  this row on first Collector boot, compares to the configured
  `infochat.embeddings.model` and `infochat.embeddings.dimension`,
  refuses startup on mismatch unless
  `infochat.embeddings.allow-model-change=true`. If V11 ships
  without the seed row OR with a model/dimension different from
  M1-034b's default property values, M1-034b's first boot will
  fatal-fail. The shipped seed (`nomic-embed-text`, 768) must
  match M1-034b's default `infochat.embeddings.model` and
  `infochat.embeddings.dimension`.
- **No post_embedding rows written here.** The table is created
  but unpopulated. M1-034b's EmbeddingWorker is the sole writer.
  A SELECT against `post_embedding` between M1-034a-merge and
  M1-034b-merge returns zero rows; this is expected.
- **Tagger fallback chain audit trail.** `tagger_fallback=true`
  is the audit flag — admins query "show me posts whose tags
  came from bootstrap fallback over the last 24h" to spot
  sustained LLM outages or vocabulary-mismatch issues. The
  WARN log with `error_class='tagger.fallback_to_bootstrap'`
  feeds the future T2-G throttled admin notifier.
- **Partial-valid handling preserves useful tags.** Per
  `docs/spec/llm.md` §Failure handling: losing the whole tag
  list because one tag is out-of-vocab would degrade tagging
  quality across deployments where smaller models occasionally
  emit one bad tag in an otherwise-clean list. The per-post
  valid+invalid counter (INFO log) feeds future T2 observability.

## Out-of-scope expansion

- **Concrete EmbeddingProvider impl.** M1-034b ships
  `OpenAiCompatibleEmbeddingProvider`. V11 here seeds
  `embedding_metadata` so the M1-034b startup guard has a row
  to compare; the provider class itself is M1-034b's diff.
- **EmbeddingMetadataDao, EmbeddingMetadataStartupGuard,
  EmbeddingWorker, ReadyPromoter, pg_notify('new_post', ...).**
  All M1-034b.
- **Any post_embedding INSERT.** V11 creates the table; no code
  in this ticket writes a row.
- **Stage 1 / Stage 2 / EntityExtractor / Re-eval / quarantine
  commands / TranslationProvider / chat-agent.** Per the
  `out_of_scope:` list.
- **V1..V10 migration changes.** Frozen. V11 is purely additive.
- **infochat-provider module changes.** This ticket is
  collector + core-migration + llm-adapter only.
- **partition_pruner job.** T2 territory. V11 creates the
  initial partition; nightly DROP-PARTITION schedule lives in T2.
- **Prometheus metric emit for `tagger_partial_valid_total`.**
  T2 observability ticket. This ticket logs at INFO/WARN with
  canonical `error_class` strings.

## Authorized test changes

- (none — this ticket adds one new test file `TaggerWorkerIT.java`
  and one new Flyway migration. No pre-existing tests are
  modified. `DbRoleMatrixIT` (M1-006) and `FlywayMigrationIT`
  (M1-017) continue to pass against the new V11 GRANTs without
  edit per the Implementation notes.)

## Alternatives considered

- **Bundle the EmbeddingProvider impl + EmbeddingMetadata startup
  guard here too.** Rejected. Splitting the original M1-034 at
  the Tagger | Embedding+ReadyPromoter boundary (the rationale
  for the M1-034 abort) requires the EmbeddingProvider to live
  with its first consumer (the EmbeddingWorker in M1-034b). The
  M1-033 pattern landed `OpenAiCompatibleProvider` WITH
  `Stage2Worker`, not as a separate one-class ticket. The
  EmbeddingProvider in M1-034b matches that shape.
- **Ship V11 in M1-034b instead.** Rejected. The schema needs
  to land before the startup guard runs (the guard reads
  `embedding_metadata`); if V11 lives in M1-034b, its first
  boot would race the Flyway migration. Cleaner: V11 lands
  here in 034a (with the table + seed row); 034b reads/updates
  the existing row.
- **Use Mustache instead of Qute for prompt templating.**
  Acceptable but worse: Qute is built-in to Quarkus, no
  dependency add needed. Use Qute.
- **Inline the TagVocabulary in TaggerWorker.java instead of
  a separate class.** Rejected on cohesion grounds —
  `TagVocabulary` is a @ApplicationScoped singleton with its
  own startup loading lifecycle; conflating it with the worker
  obscures both. Two classes, single responsibility each.
