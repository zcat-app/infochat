---
id: M1-034
title: Tagger + Embedding pipeline + status→READY + new_post NOTIFY
status: deferred
created: 2026-05-16
last_updated: 2026-05-17
replaced_by:
  - M1-034a
  - M1-034b
deferred_reason: decomposed
deferred_on: M1-034a
aborted_attempts:
  - date: 2026-05-17
    prior_status: in-progress
    reviews_at_abort: []
    clarity_check_at_abort:
      date: 2026-05-17
      verdict: WARN
      warnings:
        - "TEST-CHANGES-AUTHORIZED (WARN): DbRoleMatrixIT validates the DB role grant matrix. V11 adds post_embedding and embedding_metadata with new GRANT statements. If DbRoleMatrixIT asserts a closed expected-grants list, it will need a new entry for these two tables. The ticket lists the IT under \"preserves\" but does not include it under \"Authorized test changes.\" Verify at start time whether DbRoleMatrixIT requires update; if so, list it under Authorized test changes with the new expected rows."
        - "SELF-CONTAINED-CHECK (WARN): The tag normalization character class is referenced via \"docs/spec/commands.md §Surface conventions\" without being inlined in the DoD or acceptance criteria. Implementation notes explicitly acknowledge the implementer may need to read spec/commands.md or design/02-schema.md. Recommended fix: inline the character class in the DoD TaggerWorker bullet: [a-z0-9][a-z0-9-]{0,47} (the stored form from schema.md §Tag stored form)."
      blockers: []
    revisions_at_abort: []
    reason: "Aborted before any implementation rounds — splitting the 14-file scope into M1-034a (Tagger pipeline + V11 + EmbeddingProvider, ~9 files) and M1-034b (Embedding pipeline + ReadyPromoter, ~6 files) to give each round-cap budget to a smaller diff. Plan outline (Tagger/Embedding boundary cut) confirmed the split is mechanical; ReadyPromoter naturally lives in 034b because it consumes embedding_done. See M1-034a, M1-034b under replaced_by."
blocked_by:
  - M1-008b
  - M1-008c
  - M1-033
files_budget: 14
files_scope:
  - infochat-core/src/main/resources/db/migration/V11__post_embedding.sql
  - infochat-llm-adapter/src/main/java/io/infochat/llm/impl/OpenAiCompatibleEmbeddingProvider.java
  - infochat-llm-adapter/src/main/resources/prompts/tagger.md
  - infochat-llm-adapter/src/main/resources/prompts/tagger-fallback.md
  - infochat-collector/src/main/java/io/infochat/collector/eval/tagger/TaggerWorker.java
  - infochat-collector/src/main/java/io/infochat/collector/eval/tagger/TagVocabulary.java
  - infochat-collector/src/main/java/io/infochat/collector/eval/embedding/EmbeddingWorker.java
  - infochat-collector/src/main/java/io/infochat/collector/eval/embedding/EmbeddingMetadataDao.java
  - infochat-collector/src/main/java/io/infochat/collector/eval/embedding/EmbeddingMetadataStartupGuard.java
  - infochat-collector/src/main/java/io/infochat/collector/eval/ready/ReadyPromoter.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/io/infochat/collector/eval/tagger/TaggerWorkerIT.java
  - infochat-collector/src/test/java/io/infochat/collector/eval/embedding/EmbeddingWorkerIT.java
  - infochat-collector/src/test/java/io/infochat/collector/eval/ready/ReadyPromoterIT.java
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: true
out_of_scope:
  - any Stage 1 HTML sanitizer, Unicode normalization, prompt-injection regex set, watchdog, placeholder-id generation, or quarantine-row insertion code (M1-032 territory — consumed unchanged; the Tagger reads post.body which is already Stage-1-redacted)
  - any Stage 2 LLM judge call, BENIGN/INJECTION/MALWARE/UNKNOWN verdict handling, retry-once-then-fallback, stage2_done advance, stage2_failed flag, OpenAiCompatibleProvider, LlmRouter (the SECURITY_JUDGE and TAGGER paths share the router authored in M1-033; M1-034 CONSUMES the router unchanged), or release-on-stage2-failure config-flag wiring (M1-033 territory)
  - any EntityExtractor (Stage 3 in docs/design/01-architecture.md §1.3.4), post_entity table, post_reference table, LinkingJob (§1.3.5), or shared-entity-cosine-link computation — T2 territory. T1-D's pipeline goes S1 → (S2 if S1 hit) → Tagger → Embedding → READY, skipping stage 3 (entity extraction) per session-grouping-plan §Tier 1 ("Stage 1 deterministic security, LLM + Stage 2, tagger + embedding" — entity extraction not enumerated)
  - any Re-evaluation job (docs/spec/security.md §Re-evaluation job), per-post attempt counter, QUARANTINED → NEEDS_REVIEW transition, per-source UNKNOWN auto-disable, RE_EVAL_RELEASED audit row, or source.status → 'failed' mutation — T2-G territory. This ticket SETS tagger_fallback=true and embedding_done=true-without-vector flags that may FEED future operator analyses, but the re-eval scheduler is T2-G
  - any throttled admin notifier wiring, AdminNotifier coalescing by (channel, error_class), or per-(channel, error_class) summary-message template — T2-G territory. Tagger fallbacks, embedding failures, and tagger emit-N-valid-M-invalid counters log at INFO/WARN with canonical error_class strings for the future notifier to pick up
  - any LLM output sanitizer (docs/spec/security.md §LLM output sanitizer) — T1-F territory. Tagger output is validated against the controlled vocabulary; invalid tags are silently dropped per partial-valid handling (docs/spec/llm.md §Failure handling). Embedding output is a numeric vector, not user-visible text. Neither reaches a user, so the sanitizer is not relevant here
  - any /quarantine list/approve/reject admin command or the approve_quarantine/reject_quarantine stored procedures from docs/design/02-schema.md §2.5.2 — T2-G territory
  - any Provider-side quarantine_review LISTEN listener — M2 territory
  - any AnthropicProvider native-protocol implementation or AnthropicEmbeddingProvider — T3-D per session-grouping-plan §Tier 3. v1's concrete EmbeddingProvider is OpenAiCompatibleEmbeddingProvider (covers Ollama and OpenAI per docs/design/05-llm-and-embeddings.md §5.1 / §5.5)
  - any TranslationProvider concrete impl, LlmTranslationProvider, NoopTranslationProvider, or per-scope translation routing — T1-F territory
  - any chat-agent recall tool, recallMemory tool dispatcher, or five-tool allowlist — T2-D territory
  - any change to V1..V10 Flyway migrations (V10 from M1-032 is consumed unchanged; this ticket adds V11)
  - any change to the M1-007b LlmProvider / EmbeddingProvider / ModelTask SPI surfaces (frozen)
  - any infochat-provider module change (Tagger and Embedding run in the Collector; this ticket is collector + core-migration + llm-adapter only)
  - any partition_pruner job (docs/design/02-schema.md §2.4.4 nightly pruner) — separate T2 ticket; V11 creates the post_embedding parent + initial partition only, the pruner cadence + DROP-PARTITION schedule is T2 territory per CLAUDE.md §"Where things live" boundary
  - any embedding-model migration script (scripts/reembed.sh per docs/design/02-schema.md §2.8) — the model identity guard here refuses startup on mismatch; the operator-side migration script is operational tooling, not code (lives in scripts/ at the repo root and is not part of this ticket's diff)
  - any per-task fallback chain (the (ModelTask, scope_language) → LlmProvider router authored in M1-033 resolves to exactly one provider per call; an unreachable provider degrades to the task-specific failure path)
  - any Prometheus/Micrometer metric emit for tagger_partial_valid_total, embedding_batch_failure_total, or per-task latency histograms — observability ticket later; this ticket logs at INFO/WARN with structured fields
  - any provider-side cache-invalidation, group-digest recompute, or NewPostHandler real-consumer logic (the stub NewPostHandler is M1-027 territory; this ticket emits pg_notify('new_post', …) but does NOT modify the consumer)
acceptance:
  - "infochat-core/src/main/resources/db/migration/V11__post_embedding.sql exists and creates the post_embedding table per docs/design/02-schema.md §2.4.2 — grep -E 'CREATE TABLE\\s+post_embedding\\s*\\(' V11__post_embedding.sql returns at least one match"
  - "V11 declares post_embedding with the column shape from §2.4.2 — grep -E 'post_id\\s+UUID\\s+NOT NULL' V11__post_embedding.sql returns at least one match AND grep -E 'embedding\\s+vector\\s*\\(\\s*[0-9]+\\s*\\)\\s+NOT NULL' V11__post_embedding.sql returns at least one match (the dimension is profile-driven; the migration uses the laptop/vps default of 768 — document in Implementation notes; operator running on `pi` profile selects vector(384) via a profile-specific migration variant or operator override per §2.8) AND grep -E 'embedding_model\\s+TEXT\\s+NOT NULL' V11__post_embedding.sql returns at least one match AND grep -E 'fetched_at\\s+TIMESTAMPTZ\\s+NOT NULL' V11__post_embedding.sql returns at least one match AND grep -E 'PRIMARY KEY\\s*\\(\\s*post_id\\s*,\\s*fetched_at\\s*\\)' V11__post_embedding.sql returns at least one match AND grep -E 'PARTITION BY RANGE\\s*\\(\\s*fetched_at\\s*\\)' V11__post_embedding.sql returns at least one match"
  - "V11 creates at least one initial partition of post_embedding so the schema is queryable on day one (matching M1-008c's post partitioning pattern) — grep -E 'CREATE TABLE\\s+post_embedding_[0-9]+\\s+PARTITION OF\\s+post_embedding' V11__post_embedding.sql returns at least one match"
  - "V11 creates the profile-driven vector index per docs/design/02-schema.md §2.4.2 and §2.4.4. For laptop/vps/remote-llm: HNSW with m=16, ef_construction=64. For pi: IVFFlat with lists=100. The migration uses the laptop/vps default (HNSW); the pi variant is selected via an alternative migration file or via a startup hook that issues CREATE INDEX after the table exists — document the operator choice in Implementation notes. grep -E 'USING\\s+hnsw\\s*\\(\\s*embedding\\s+vector_cosine_ops\\s*\\)' V11__post_embedding.sql returns at least one match"
  - "V11 creates the embedding_metadata singleton row per docs/spec/llm.md §Embedding pipeline 'Model identity guard. The active embedding model's identifier and vector dimensionality are stored in a singleton metadata row on first use.' The shape: a single-row table embedding_metadata(model_identifier TEXT NOT NULL, dimension INT NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now()), UNIQUE constraint on a 1-row-key per schema-layer singleton (e.g. a CHECK constraint enforcing only one row, or a UNIQUE on a synthetic constant column). The migration INSERTs the default row matching the laptop/vps embedding model (nomic-embed-text, dimension=768 per docs/design/05-llm-and-embeddings.md §5.5 Model and dimension by profile). grep -E 'CREATE TABLE\\s+embedding_metadata\\s*\\(' V11__post_embedding.sql returns at least one match AND grep -E 'INSERT\\s+INTO\\s+embedding_metadata' V11__post_embedding.sql returns at least one match AND grep -E 'nomic-embed-text' V11__post_embedding.sql returns at least one match"
  - "V11 GRANTs align with docs/spec/security.md §DB roles. Collector writes post_embedding rows (it runs the embedding worker); Provider reads them for semantic-similarity queries (later T2-C chat agent and T1-F /summary will use these). embedding_metadata is read by both services (Collector to enforce the startup guard, Provider for diagnostic). grep -E 'GRANT\\s+SELECT\\s*,\\s*INSERT\\s+ON\\s+post_embedding\\s+TO\\s+infochat_collector' V11__post_embedding.sql returns at least one match AND grep -E 'GRANT\\s+SELECT\\s+ON\\s+post_embedding\\s+TO\\s+infochat_provider' V11__post_embedding.sql returns at least one match AND grep -E 'GRANT\\s+SELECT(\\s*,\\s*INSERT\\s*,\\s*UPDATE)?\\s+ON\\s+embedding_metadata\\s+TO\\s+infochat_collector' V11__post_embedding.sql returns at least one match AND grep -E 'GRANT\\s+SELECT\\s+ON\\s+embedding_metadata\\s+TO\\s+infochat_provider' V11__post_embedding.sql returns at least one match"
  - "V11 does NOT create post_entity, post_reference, or the LinkingJob-related tables/indexes from docs/design/02-schema.md §2.4.1 / §2.4.3 (those are T2 territory per the session-grouping-plan T1-D row excluding entity extraction) — grep -E 'CREATE TABLE\\s+post_entity|CREATE TABLE\\s+post_reference' V11__post_embedding.sql returns zero matches"
  - "OpenAiCompatibleEmbeddingProvider.java implements EmbeddingProvider per docs/design/05-llm-and-embeddings.md §5.1 / §5.5 (the OpenAI-compatible embeddings endpoint covers Ollama and OpenAI per the multi-provider design). The class issues POST <base-url>/embeddings with a JSON body {\"model\": \"...\", \"input\": [\"text1\", \"text2\", ...]} and returns a List<EmbeddingResult> in input order per the M1-007b SPI shape. Distinguished at runtime by (base-url, api-key, model). grep -E 'class\\s+OpenAiCompatibleEmbeddingProvider\\s+implements\\s+EmbeddingProvider' OpenAiCompatibleEmbeddingProvider.java returns at least one match AND grep -E '/embeddings|/v1/embeddings' OpenAiCompatibleEmbeddingProvider.java returns at least one match AND grep -E 'infochat\\.embeddings\\.base-url|infochat\\.embeddings\\.model' OpenAiCompatibleEmbeddingProvider.java returns at least one match"
  - "OpenAiCompatibleEmbeddingProvider.java does NOT route through the (ModelTask, scope_language) router from M1-033 per docs/spec/llm.md §SPI shape 'Scope of the enum. The embedder is not a ModelTask — EmbeddingProvider is a distinct SPI with its own provider selection.' EmbeddingProvider resolution uses ONE provider per deployment (one base-url, one api-key, one model) — there is no per-language or per-task routing for embeddings. grep -E 'ModelTask|LlmRouter' OpenAiCompatibleEmbeddingProvider.java returns zero matches (the embedding provider does NOT depend on the (ModelTask, scope_language) router)"
  - "infochat-llm-adapter/src/main/resources/prompts/tagger.md exists and follows the template at docs/design/05-llm-and-embeddings.md §5.4.2 'Tagger': instructs the model to assign 1..4 tags from a controlled vocabulary; demands JSON output {\"tags\": [\"tag1\",\"tag2\"]}; lists the vocabulary inline via Mustache iteration; wraps the post body in <<<UNTRUSTED_CONTENT id=\"{{id}}\">>>...<<<END id=\"{{id}}\">>> with the per-call random delimiter. grep -E '\\{\"tags\":' tagger.md returns at least one match AND grep -E '<<<UNTRUSTED_CONTENT' tagger.md returns at least one match AND grep -E '\\{\\{#tags\\}\\}|\\{\\{tags\\}\\}' tagger.md returns at least one match (the vocabulary list Mustache iteration)"
  - "infochat-llm-adapter/src/main/resources/prompts/tagger-fallback.md exists and follows the line-oriented fallback template from §5.4.2 'On parse failure, the worker retries once with a different, simplified prompt': demands a single line 'TAGS: tag1, tag2, tag3' format with no JSON, designed to work with small models like llama3.2:1b that struggle with JSON mode. grep -E 'TAGS:' tagger-fallback.md returns at least one match AND grep -E '\\{\\{#tags\\}\\}|\\{\\{tags\\}\\}' tagger-fallback.md returns at least one match AND grep -E '<<<UNTRUSTED_CONTENT' tagger-fallback.md returns at least one match"
  - "TaggerWorker.java is the Collector-side worker that picks up posts ready for tagging. Pickup criteria (matching Invariant 5's per-stage flag cursor): status='RAW' AND stage1_done=true AND (stage1_flagged=false OR stage2_done=true) AND tagger_done=false. The worker is invoked downstream of the Stage 1 worker (for clean Stage 1) and downstream of M1-033's Stage 2 verdict handler (for Stage-2-cleared posts). Quarantined posts are excluded by the status='RAW' filter (Stage 2 INJECTION/MALWARE/UNKNOWN and the watchdog fail-closed path both set status='QUARANTINED'). grep -E 'class\\s+TaggerWorker' TaggerWorker.java returns at least one match AND grep -E \"status\\s*=\\s*'RAW'\" TaggerWorker.java returns at least one match AND grep -E 'tagger_done\\s*=\\s*FALSE|tagger_done\\s*=\\s*false' TaggerWorker.java returns at least one match"
  - "TaggerWorker.java invokes the M1-033 LlmRouter with ModelTask.TAGGER and the scope-default language ('en' — Tagger output is normalized tags from a fixed-vocabulary, not user-visible prose; scope language doesn't drive the tagger). Verify by reading: the call site is router.forTask(TAGGER, 'en') (or the scope_language constant from a future scope-aware path). grep -E 'TAGGER|ModelTask\\.TAGGER' TaggerWorker.java returns at least one match"
  - "TaggerWorker.java's primary tagging path: load prompts/tagger.md, substitute the controlled vocabulary loaded from TagVocabulary, substitute the per-call random {{id}} UUID, substitute the post body + title; invoke provider.generate; parse the reply as strict JSON {\"tags\": [...]}; for each tag in the parsed array: normalize per docs/spec/commands.md §Surface conventions (NFC + lower-case + character class; the normalization helper may live in infochat-core if already authored, otherwise inline the rule), then check membership in the controlled vocabulary. Keep the valid tags; silently drop the invalid ones per docs/spec/llm.md §Failure handling 'Partial-valid handling. When the LLM emits a list of tags and only some entries pass the controlled-vocabulary validation ... the valid tags are kept and the invalid tags are silently dropped'. Record an INFO log entry naming the count of valid + invalid tags so a future operator alert on sustained high invalid rates has the data — grep -E 'tagger\\.partial_valid|valid.*invalid|tagger_partial' TaggerWorker.java returns at least one match"
  - "TaggerWorker.java's bootstrap-tags fallback fires per docs/spec/security.md §Failure handling 'Tagger failure → fall back to source.bootstrap_tags, mark the post, throttled admin notify' on each of: (a) schema-violating output (the JSON parse throws OR the parsed object lacks a 'tags' array); (b) zero valid tags after partial-valid handling (the JSON parsed but ZERO entries passed vocabulary validation); (c) LLM unreachable / timeout. Path: retry once with the tagger-fallback.md prompt for case (a), or retry once with the same primary prompt for cases (b) and (c) (b) is a vocabulary-mismatch which is not solved by changing the prompt format; (c) is a transient infrastructure issue. Document the retry choice in TaggerWorker's class JDoc. After the retry: if the retry's output is also unusable (same case (a)/(b)/(c)), fall back to source.bootstrap_tags AND set post.tagger_fallback=true AND log WARN with canonical error_class='tagger.fallback_to_bootstrap'. grep -E 'tagger_fallback|tagger\\.fallback_to_bootstrap' TaggerWorker.java returns at least one match AND grep -E 'bootstrap_tags|source\\.bootstrap_tags' TaggerWorker.java returns at least one match"
  - "TaggerWorker.java's tagger_done=true UPDATE is the persistence cursor for the Tagger boundary per Invariant 5. UPDATE post SET tags=:tags, tagger_done=true, tagger_fallback=:fallback WHERE id=:post_id AND fetched_at=:fetched_at; the same statement writes both the tag array and the cursor flags atomically. grep -E 'tagger_done\\s*=\\s*TRUE|tagger_done\\s*=\\s*true' TaggerWorker.java returns at least one match AND grep -E 'tags\\s*=' TaggerWorker.java returns at least one match"
  - "TagVocabulary.java loads the controlled vocabulary from the tag table seeded in M1-008b. The vocabulary is loaded ONCE at startup (or on first tagger invocation; document the choice) into an immutable Set<String> for fast lookup. The vocabulary contents are normalized at load time using the same NFC + lower-case rule the tagger output is normalized with, so the membership check is byte-equal. grep -E 'class\\s+TagVocabulary' TagVocabulary.java returns at least one match AND grep -E 'SELECT\\s+name\\s+FROM\\s+tag|FROM\\s+tag\\s+ORDER' TagVocabulary.java returns at least one match"
  - "EmbeddingWorker.java picks up posts ready for embedding: status='RAW' AND tagger_done=true AND embedding_done=false. The worker batches posts by infochat.embeddings.batch-size (profile-driven; default 16 per implementation note — document in Implementation notes; the value is a design-tier choice) and flushes when the batch is full OR when a profile-driven flush timer fires. grep -E 'class\\s+EmbeddingWorker' EmbeddingWorker.java returns at least one match AND grep -E 'tagger_done\\s*=\\s*TRUE|tagger_done\\s*=\\s*true' EmbeddingWorker.java returns at least one match AND grep -E 'embedding_done\\s*=\\s*FALSE|embedding_done\\s*=\\s*false' EmbeddingWorker.java returns at least one match AND grep -E 'infochat\\.embeddings\\.batch-size|batch.size|batchSize' EmbeddingWorker.java returns at least one match"
  - "EmbeddingWorker.java invokes EmbeddingProvider.embed(List<String>) with the per-post input text per docs/design/05-llm-and-embeddings.md §5.5 'For each post that reaches EmbeddingWorker: 1. Build input text: title + \"\\n\\n\" + (body_summary or first 800 chars of body). 2. Call EmbeddingProvider.embed(text).' For a batch of N posts the worker builds the N input texts and calls embed once. The provider returns N EmbeddingResults in input order per the M1-007b SPI shape. grep -E 'embed\\s*\\(' EmbeddingWorker.java returns at least one match"
  - "EmbeddingWorker.java's one-failure-fails-batch retry policy per docs/spec/llm.md §Embedding pipeline 'One-failure-fails-batch retry. If the provider returns a batch result of the wrong shape, an exception, or any per-element error the Collector cannot map back to a specific post, the entire batch retries once. If retry also fails, every post in the batch follows the embedding-failure release path (release without a vector).' Verify by reading: a try/catch around the embed call retries exactly once on any failure (exception, wrong-shape result, per-element error); per docs/spec/llm.md §Failure handling (recap) 'Retry policy: on a batch failure the same batch is resubmitted as-is; the batch is not split on retry' — the retry uses the SAME batch (no per-post split). On the second failure, EVERY post in the batch advances: embedding_done=true, NO post_embedding row inserted, log WARN with canonical error_class='embedding.batch_failure'. grep -E 'embedding\\.batch_failure|embedding_batch' EmbeddingWorker.java returns at least one match"
  - "EmbeddingWorker.java's success path inserts one post_embedding row per post via batch INSERT per docs/design/05-llm-and-embeddings.md §5.5 step 3 'Insert one row into post_embedding(post_id, embedding, embedding_model, fetched_at)'. embedding_model is the active model identifier from embedding_metadata (NOT the provider's reported value — the metadata is the canonical record per the model identity guard). grep -E 'INSERT\\s+INTO\\s+post_embedding' EmbeddingWorker.java returns at least one match AND grep -E 'embedding_model' EmbeddingWorker.java returns at least one match"
  - "EmbeddingWorker.java's embedding_done=true UPDATE is the persistence cursor for the Embedding boundary per Invariant 5. On success: post.embedding_done=true (the post_embedding row was inserted). On batch failure: post.embedding_done=true (no row inserted, embedding-less release path per docs/spec/security.md §Failure handling 'Embedding failure → release without a vector; the post is otherwise normal and fully visible'). grep -E 'embedding_done\\s*=\\s*TRUE|embedding_done\\s*=\\s*true' EmbeddingWorker.java returns at least one match"
  - "EmbeddingWorker.java fails fatally on per-element dimensionality mismatch per docs/spec/llm.md §Embedding pipeline 'Dimensionality mismatch at runtime is fatal. Storing vectors of mixed dimensions in the same pgvector column silently corrupts cosine similarity scores. The only safe recovery is a full re-embed.' If the provider returns a vector whose length differs from embedding_metadata.dimension, the worker throws immediately (no retry — this is NOT a batch-failure-retry case but a metadata-invariant violation). The thrown exception unwinds the batch transaction (no post_embedding rows inserted, embedding_done NOT advanced — the post stays in flight; operator runs the re-embed procedure). grep -E 'dimension|EmbeddingResult\\.vector\\(\\)\\.length|getDimension' EmbeddingWorker.java returns at least one match AND grep -E 'throw\\s+new|RuntimeException|IllegalStateException' EmbeddingWorker.java returns at least one match in the dimensionality-check code path"
  - "EmbeddingMetadataDao.java is the SOLE write path to embedding_metadata in M1. Two SQL shapes: (1) READ the singleton row (SELECT model_identifier, dimension FROM embedding_metadata LIMIT 1); (2) UPDATE the singleton row (UPDATE embedding_metadata SET model_identifier=?, dimension=?, updated_at=now()) — used ONLY by the operator-override path when infochat.embeddings.allow-model-change=true. grep -E 'class\\s+EmbeddingMetadataDao' EmbeddingMetadataDao.java returns at least one match AND grep -rE 'INSERT\\s+INTO\\s+embedding_metadata|UPDATE\\s+embedding_metadata' infochat-collector/src/main/java/ returns matches ONLY inside EmbeddingMetadataDao.java"
  - "EmbeddingMetadataStartupGuard.java is a Collector-side @Startup bean that enforces the model identity guard per docs/spec/llm.md §Embedding pipeline 'On every startup the EmbeddingProvider reports its current identifier and dimensionality; if either differs from the stored row, startup is refused with a descriptive error referencing the re-embed procedure.' Implementation: at startup, read the EmbeddingProvider's reported model (a property-driven value, infochat.embeddings.model, since the provider doesn't have a separate /model identity endpoint in v1) and its declared dimensionality (the value the operator configured matching the active profile). Compare to embedding_metadata. On mismatch, refuse startup with a fatal log line naming the stored value, the reported value, and the re-embed procedure path. The guard's @Priority is between Flyway (100) and the M1-033 LlmRouterStartupGuard (150) so the model identity check runs early — e.g. @Priority(125). grep -E '@Startup' EmbeddingMetadataStartupGuard.java returns at least one match AND grep -E 'infochat\\.embeddings\\.model' EmbeddingMetadataStartupGuard.java returns at least one match AND grep -E 'embedding_metadata' EmbeddingMetadataStartupGuard.java returns at least one match"
  - "EmbeddingMetadataStartupGuard.java honors the operator override per docs/spec/llm.md §Embedding pipeline 'An explicit operator override flag bypasses the check for intentional migration runs; its property key and semantics are in design notes.' Property key: infochat.embeddings.allow-model-change=true. When set, the guard: (a) does NOT refuse startup on mismatch; (b) UPDATEs embedding_metadata with the new model_identifier and dimension; (c) logs WARN with the rotation (old → new). grep -E 'infochat\\.embeddings\\.allow-model-change' EmbeddingMetadataStartupGuard.java returns at least one match"
  - "ReadyPromoter.java handles the Stage-5 RAW → READY transition per docs/design/01-architecture.md §1.3.4 step 5 'UPDATE post.status=READY, post.ready_at=now(), NOTIFY new_post with payload (ready_at, post_id)'. Pickup criteria: status='RAW' AND stage1_done=true AND (stage1_flagged=false OR stage2_done=true) AND tagger_done=true AND embedding_done=true. The transition writes status='READY', ready_at=now(), status_changed_at=now() AND emits pg_notify('new_post', json) — ALL IN THE SAME DB TRANSACTION per docs/spec/architecture.md §Inter-service communication 'the high-water mark advances both fields in the same DB transaction as the side effect it triggers, making processing idempotent'. The NOTIFY payload is the cursor key only: {ready_at, post_id} per docs/design/02-schema.md §2.9.1. grep -E 'class\\s+ReadyPromoter' ReadyPromoter.java returns at least one match AND grep -E \"status\\s*=\\s*'READY'\" ReadyPromoter.java returns at least one match AND grep -E 'pg_notify|NOTIFY\\s+new_post' ReadyPromoter.java returns at least one match AND grep -E '@Transactional|TransactionManager|setAutoCommit\\s*\\(\\s*false\\s*\\)' ReadyPromoter.java returns at least one match"
  - "ReadyPromoter.java's NOTIFY payload uses the JSON form documented in M1-027's NewPostListener (verified at authoring: infochat-provider/src/main/java/io/infochat/provider/outbox/NewPostListener.java carries the parser as a package-private static `parsePayload(String)` plus regexes `\\\"ready_at\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"` and `\\\"post_id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"`). The emit format is the JSON object `{\"ready_at\":\"<iso8601-instant>\",\"post_id\":\"<uuid>\"}` — `ready_at` MUST be an `Instant.toString()` ISO-8601 form (e.g. `2026-05-16T12:34:56.789Z`) since the parser calls `Instant.parse`, and `post_id` MUST be a canonical `UUID.toString()` form since the parser calls `UUID.fromString`. The payload is built inline in ReadyPromoter (no shared helper extracted — M1-027's parser stays in infochat-provider per this ticket's out-of-scope 'no infochat-provider module change' rule; the format compatibility is contract-level, not class-level). Verify by reading: the emit code produces a string containing both `\"ready_at\":` and `\"post_id\":` field labels with the values quoted; the values pass `Instant.parse` and `UUID.fromString` round-trip. grep -E '\"ready_at\"' ReadyPromoter.java returns at least one match AND grep -E '\"post_id\"' ReadyPromoter.java returns at least one match"
  - "ReadyPromoter.java NEVER promotes posts with status='QUARANTINED' to READY (Stage 2 INJ/MAL/UNK and Stage 1 watchdog fail-closed paths both write status='QUARANTINED' and must NEVER advance). Verify by reading: the pickup query's status='RAW' clause is the only filter; there is no path that mutates status='QUARANTINED' to 'READY' in this class. grep -E \"status\\s*=\\s*'QUARANTINED'\" ReadyPromoter.java returns zero matches (the only status the promoter writes is 'READY')"
  - "Quarantined posts are excluded from the Tagger AND Embedding workers: a post with status='QUARANTINED' does NOT appear in TaggerWorker's pickup or EmbeddingWorker's pickup. Verify by reading: the SELECT statements in both workers filter on status='RAW'; the status='QUARANTINED' filter is not present in either body because the inverse filter status='RAW' already excludes it"
  - "Posts with stage2_failed=true AND status='RAW' (the release-on-stage2-failure=true infra-failure path from M1-033) ARE picked up by TaggerWorker and EmbeddingWorker. The infra-failure path still requires tag assignment and embedding for the user-facing post to be useful — stage2_failed=true is a metadata flag for the future re-eval job, NOT a downstream-pipeline blocker. Verify by reading: the TaggerWorker pickup query does NOT filter on stage2_failed; the EmbeddingWorker pickup query does NOT filter on stage2_failed"
  - "application.properties under infochat-collector/src/main/resources is amended to add the embedding + tagger property surface. Required keys: infochat.embeddings.base-url (default http://localhost:11434/v1 for laptop Ollama), infochat.embeddings.api-key (default 'ignored' for local Ollama), infochat.embeddings.model (default nomic-embed-text for laptop/vps; the property name matches docs/design/05-llm-and-embeddings.md §5.7 row), infochat.embeddings.dimension (default 768 for laptop/vps), infochat.embeddings.batch-size (default 16; document choice), infochat.embeddings.max-concurrency (default 4 for laptop per §5.7), infochat.embeddings.allow-model-change (default false); infochat.llm.tagger.base-url, infochat.llm.tagger.api-key, infochat.llm.tagger.model (default llama3.1:8b for laptop per §5.7), infochat.llm.tagger.max-concurrency (default 4 for laptop per §5.7). Verify: grep -E 'infochat\\.embeddings\\.base-url' application.properties returns at least one match AND grep -E 'infochat\\.embeddings\\.model' application.properties returns at least one match AND grep -E 'infochat\\.embeddings\\.dimension' application.properties returns at least one match AND grep -E 'infochat\\.embeddings\\.allow-model-change' application.properties returns at least one match AND grep -E 'infochat\\.llm\\.tagger\\.model' application.properties returns at least one match"
  - "TaggerWorkerIT.java is a @QuarkusTest IT against real Postgres + a STUB LlmProvider replacing OpenAiCompatibleProvider for the test profile. Scenarios end-to-end (each writes a real post row with status='RAW' AND stage1_done=true AND (stage1_flagged=false OR stage2_done=true) AND tagger_done=false): (1) Tagger happy path: stub returns valid JSON {\"tags\": [\"security\", \"news\"]} where both are in the seeded vocabulary → post.tags=[\"security\",\"news\"], tagger_done=true, tagger_fallback=false; (2) Tagger partial-valid: stub returns {\"tags\": [\"security\", \"news\", \"NOTAVALIDTAG\"]} where the first two are vocabulary members and the third is not → post.tags=[\"security\",\"news\"], tagger_done=true, tagger_fallback=false, INFO log mentions the partial-valid count; (3) Tagger zero-valid: stub returns {\"tags\": [\"NOTAVALIDTAG\", \"ALSOINVALID\"]} → fall back to source.bootstrap_tags, post.tags=<bootstrap_tags>, tagger_fallback=true; (4) Tagger schema-violating: stub returns 'this is not json' (or a malformed JSON like '{\"tags\": ') → retries with the fallback prompt; if the retry returns 'TAGS: security, news', uses those; tagger_done=true; (5) Tagger total fail: both primary and fallback prompts return garbage → fall back to source.bootstrap_tags, tagger_fallback=true; (6) Tagger LLM unreachable: stub throws on every call → retries once; on second failure falls back to source.bootstrap_tags, tagger_fallback=true; (7) Quarantined post exclusion: a post with status='QUARANTINED' is seeded; after invoking the Tagger worker tick, the post is NOT picked up (tagger_done STAYS false) — grep -E '@Test' TaggerWorkerIT.java returns at least seven matches"
  - "EmbeddingWorkerIT.java is a @QuarkusTest IT against real Postgres + a STUB EmbeddingProvider replacing OpenAiCompatibleEmbeddingProvider. Scenarios end-to-end (each seeds posts with status='RAW' AND tagger_done=true AND embedding_done=false): (1) Embedding happy path: stub returns N=2 vectors for N=2 input texts → 2 post_embedding rows inserted, embedding_done=true for each, post_embedding.embedding_model matches the active model identifier; (2) Embedding batch failure: stub throws on the FIRST call → retries; the retry also throws → all N posts in the batch follow the no-vector release path (embedding_done=true, ZERO post_embedding rows inserted, WARN log with error_class='embedding.batch_failure'); (3) Embedding partial failure (provider returns N=1 result for N=2 inputs — wrong shape): same as batch failure (the entire batch retries once, then no-vector release); (4) Embedding dimensionality mismatch at runtime: stub returns a vector of dimension D' ≠ D (e.g. 384 when the metadata says 768) → throws RuntimeException immediately, the post stays in flight (no post_embedding row, embedding_done STAYS false); (5) status→READY-ready: a post with tagger_done=true, embedding_done=true, status='RAW' is NOT yet promoted by EmbeddingWorker — the ReadyPromoter is a separate concern tested in ReadyPromoterIT — grep -E '@Test' EmbeddingWorkerIT.java returns at least five matches"
  - "ReadyPromoterIT.java is a @QuarkusTest IT that asserts the Stage-5 transition + NOTIFY emit. Scenarios: (1) status→READY happy path: a post with stage1_done=true, stage1_flagged=false, tagger_done=true, embedding_done=true, status='RAW' is updated to status='READY' with ready_at and status_changed_at set; one NOTIFY new_post payload {ready_at, post_id} is observed by a JDBC LISTEN test fixture (real Postgres NOTIFY, not an in-process mock — same pattern as M1-027's NewPostListenerIT); (2) same-transaction rule: a deliberate failure between the UPDATE and the pg_notify (force the transaction to roll back via @Transactional + throwing inside the boundary) leaves status='RAW' AND no NOTIFY observable; (3) quarantined post exclusion: a post with status='QUARANTINED' AND tagger_done=true AND embedding_done=true is NOT promoted (status stays 'QUARANTINED', no NOTIFY); (4) stage2_failed=true release path: a post with stage2_failed=true AND status='RAW' AND tagger_done=true AND embedding_done=true IS promoted to status='READY' (the release-on-stage2-failure=true infra path is still a release); (5) startup model identity guard: pre-seed embedding_metadata with model='alpha' dimension=768; configure infochat.embeddings.model='beta'; Collector startup FAILS with the descriptive error mentioning 'alpha' and 'beta'; configure infochat.embeddings.allow-model-change=true; startup succeeds and embedding_metadata is overwritten — grep -E '@Test' ReadyPromoterIT.java returns at least five matches AND grep -E 'pg_notify|NOTIFY\\s+new_post' ReadyPromoterIT.java returns at least one match"
  - "mvn -B -pl infochat-collector -am verify exits 0; failsafe reports show TaggerWorkerIT, EmbeddingWorkerIT, and ReadyPromoterIT executed — grep -rE 'Tests run: [1-9]' infochat-collector/target/failsafe-reports returns at least three new matches across the three new IT classes"
  - "mvn -B clean verify from the repo root exits 0; all prior tests (M1-003, M1-007, M1-007a/b/c, M1-008/008a/b/c, M1-009, M1-017, M1-022..M1-029, M1-032, M1-033) continue to pass alongside the new V11 migration, the Tagger and Embedding workers, the ReadyPromoter, and the startup guard"
test_plan:
  adds:
    - infochat-collector/src/test/java/io/infochat/collector/eval/tagger/TaggerWorkerIT.java (@QuarkusTest IT against real Postgres + stub LlmProvider exercising happy / partial-valid / zero-valid / schema-violating / total-fail / LLM-unreachable / quarantined-exclusion paths)
    - infochat-collector/src/test/java/io/infochat/collector/eval/embedding/EmbeddingWorkerIT.java (@QuarkusTest IT against real Postgres + stub EmbeddingProvider exercising happy / batch-failure / wrong-shape / dimensionality-mismatch / pre-promotion paths)
    - infochat-collector/src/test/java/io/infochat/collector/eval/ready/ReadyPromoterIT.java (@QuarkusTest IT for the Stage-5 RAW → READY transition + pg_notify(new_post) emit + same-transaction-as-side-effect rule + model-identity startup guard)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a)
    - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java (M1-007b)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java (M1-007c)
    - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (M1-007)
    - infochat-collector/src/test/java/io/infochat/collector/flyway/FlywayMigrationIT.java (M1-017 — V11 must apply cleanly alongside V1..V10)
    - infochat-collector/src/test/java/io/infochat/collector/db/DbRoleMatrixIT.java (M1-006 / M1-017)
    - all M1-008a/b/c schema tests
    - all M1-022/023/024/025/026/029 ingest + SSRF tests
    - M1-027's three provider outbox ITs (the NewPostListenerIT will now observe NOTIFY fired by THIS ticket's ReadyPromoter, end-to-end)
    - M1-028's PostPersisterIT + OutboxRehydratorIT + FetchSchedulerIT
    - M1-032's Stage1PipelineIT + Stage1WatchdogIT + Stage1RegexSetTest
    - M1-033's LlmRouterTest + Stage2WorkerIT + LocalOnlyConflictStartupIT
spec_refs:
  - docs/spec/security.md §Failure handling
  - docs/spec/security.md §DB roles
  - docs/spec/llm.md §SPI shape
  - docs/spec/llm.md §Per-task routing rules
  - docs/spec/llm.md §Embedding pipeline
  - docs/spec/llm.md §Failure handling (recap)
  - docs/spec/llm.md §Hardware profile contract
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/schema.md §Invariants
  - docs/spec/architecture.md §Inter-service communication
  - docs/spec/architecture.md §Pipelines
  - docs/spec/architecture.md §Architectural principles
  - docs/design/01-architecture.md §1.3 Key data flow ingest
  - docs/design/01-architecture.md §1.3.1 Polled Fetcher → outbox
  - docs/design/01-architecture.md §1.3.4 Eval pipeline workers
  - docs/design/01-architecture.md §1.4.3 Startup-bean ordering
  - docs/design/02-schema.md §2.3.1 post
  - docs/design/02-schema.md §2.4.2 post_embedding
  - docs/design/02-schema.md §2.8 Embedding model migration
  - docs/design/02-schema.md §2.9.1 LISTEN / NOTIFY channels
  - docs/design/05-llm-and-embeddings.md §5.1 SPI overview
  - docs/design/05-llm-and-embeddings.md §5.4.2 Tagger
  - docs/design/05-llm-and-embeddings.md §5.5 Embeddings
  - docs/design/05-llm-and-embeddings.md §5.7 Profile defaults
  - docs/design/05-llm-and-embeddings.md §5.8 Failure handling per task
decision_refs:
  - D5
  - D22
  - D27
  - D32
---

# M1-034: Tagger + Embedding pipeline + status→READY + new_post NOTIFY

## Context

Third and final ticket of T1-D (eval pipeline). Picks up
posts that have completed Stage 1 (and Stage 2 if Stage 1
flagged) and are in `status='RAW'` awaiting tagging,
embedding, and the final advance to `status='READY'`.

The pipeline shape is:
```
status='RAW' AND stage1_done=true AND
  (stage1_flagged=false OR stage2_done=true) AND
  tagger_done=false
    → TaggerWorker
       ↓
status='RAW' AND tagger_done=true AND embedding_done=false
    → EmbeddingWorker
       ↓
status='RAW' AND tagger_done=true AND embedding_done=true
    → ReadyPromoter
       ↓
status='READY', ready_at=now(), NOTIFY new_post {ready_at, post_id}
```

Posts in `status='QUARANTINED'` (from Stage 2 INJECTION /
MALWARE / UNKNOWN, from Stage 2 release-on-stage2-failure=false
infra fail, or from Stage 1 watchdog fail-closed) are
automatically excluded by the `status='RAW'` filter.

This ticket also lands:

1. The **first concrete `EmbeddingProvider` impl** —
   `OpenAiCompatibleEmbeddingProvider` per
   `docs/design/05-llm-and-embeddings.md` §5.1 (covers
   Ollama and OpenAI; the wire shape is the OpenAI-compatible
   `/embeddings` endpoint).
2. The **`embedding_metadata` singleton row + startup guard**
   per `docs/spec/llm.md` §Embedding pipeline "Model identity
   guard." On every Collector startup, the configured
   embedding model identifier and dimension are compared to
   the stored row; mismatch refuses startup. An operator
   override flag (`infochat.embeddings.allow-model-change`)
   bypasses the check for intentional migration runs.
3. The **Tagger fallback chain** per
   `docs/spec/security.md` §Failure handling and
   `docs/spec/llm.md` §Failure handling (recap):
   primary prompt → fallback prompt on schema violation →
   `source.bootstrap_tags` on zero-valid or total-fail.
4. The **partial-valid handling** for tags: valid tags are
   kept, invalid tags are silently dropped, INFO log records
   the per-post valid+invalid counter.
5. The **one-failure-fails-batch retry** for embeddings: any
   batch failure (exception, wrong-shape result, per-element
   error) retries the entire batch ONCE; on second failure
   every post in the batch follows the no-vector release path
   (`embedding_done=true`, no `post_embedding` row).
6. The **dimensionality-fatal-at-runtime guard**: a returned
   vector whose length differs from
   `embedding_metadata.dimension` throws immediately (no retry
   — this is NOT a batch-failure-retry case).
7. The **Stage 5 RAW → READY transition** with `ready_at` set
   and `pg_notify('new_post', {ready_at, post_id})` emitted in
   the SAME DB transaction (idempotency invariant from
   `docs/spec/architecture.md` §Inter-service communication).

This ticket fires the FIRST `new_post` NOTIFY in the
codebase. M1-027's `NewPostListener` is the Provider-side
listener; this ticket is the Collector-side emitter. The
NewPostListenerIT from M1-027 used a test-harness-emitted
NOTIFY; after this ticket, the end-to-end ingest pipeline
emits the real NOTIFY and the Provider listener reacts.

## Definition of Done

- **V11 Flyway migration** under
  `infochat-core/src/main/resources/db/migration/V11__post_embedding.sql`:
  - Creates `post_embedding` per
    `docs/design/02-schema.md` §2.4.2: `(post_id UUID NOT
    NULL, embedding vector(768) NOT NULL, embedding_model
    TEXT NOT NULL, fetched_at TIMESTAMPTZ NOT NULL, PRIMARY
    KEY (post_id, fetched_at)) PARTITION BY RANGE
    (fetched_at)`. The dimension is the laptop/vps default
    (768 — nomic-embed-text); the `pi` profile's 384-d
    variant is handled by an alternative migration file or
    operator override (document the operator choice).
  - Creates at least one initial partition of `post_embedding`
    so the schema is queryable on day one.
  - Creates the HNSW vector index per §2.4.2: `USING hnsw
    (embedding vector_cosine_ops) WITH (m=16,
    ef_construction=64)`. The pi profile's IVFFlat variant is
    a separate operator choice.
  - Creates `embedding_metadata(model_identifier TEXT NOT
    NULL, dimension INT NOT NULL, updated_at TIMESTAMPTZ NOT
    NULL DEFAULT now())` as a schema-enforced singleton (e.g.
    via a CHECK constraint or a UNIQUE on a synthetic constant
    column). Seeds the default row matching the laptop/vps
    embedding model.
  - Per-table GRANTs per `docs/spec/security.md` §DB roles:
    - `GRANT SELECT, INSERT ON post_embedding TO
      infochat_collector` (Collector writes via
      EmbeddingWorker).
    - `GRANT SELECT ON post_embedding TO infochat_provider`
      (Provider reads for future T1-F `/summary` and T2-D
      chat-agent semantic-similarity queries).
    - `GRANT SELECT, INSERT, UPDATE ON embedding_metadata TO
      infochat_collector` (Collector enforces the startup
      guard; UPDATE on operator override).
    - `GRANT SELECT ON embedding_metadata TO
      infochat_provider` (Provider reads for diagnostic).
  - **Does NOT** create `post_entity`, `post_reference`, or
    LinkingJob tables (T2 territory).
- **`OpenAiCompatibleEmbeddingProvider.java`** implements
  `EmbeddingProvider` from M1-007b. Issues `POST
  <base-url>/embeddings` with body `{"model": "...", "input":
  ["text1", "text2", ...]}` and returns a `List<EmbeddingResult>`
  in input order. Reads `(base-url, api-key, model)` from
  `@ConfigProperty` injections (`infochat.embeddings.base-url`,
  `infochat.embeddings.api-key`, `infochat.embeddings.model`).
  Does NOT route through the `(ModelTask, scope_language)`
  router — embedding has its own resolution path per
  `docs/spec/llm.md` §SPI shape "Scope of the enum."
- **`tagger.md`** and **`tagger-fallback.md`** under
  `infochat-llm-adapter/src/main/resources/prompts/` follow
  the templates at `docs/design/05-llm-and-embeddings.md`
  §5.4.2 (JSON primary + line-oriented fallback for small
  models).
- **`TagVocabulary.java`** loads the controlled vocabulary
  from the `tag` table seeded in M1-008b into an immutable
  `Set<String>`. Loaded once at startup.
- **`TaggerWorker.java`** picks up posts on `status='RAW'
  AND stage1_done=true AND (stage1_flagged=false OR
  stage2_done=true) AND tagger_done=false`. For each post:
  - Invokes `LlmRouter.forTask(TAGGER, "en")` to get the
    provider.
  - Loads `tagger.md`, substitutes the vocabulary list, the
    per-call random UUID, and the post body + title.
  - Invokes `provider.generate(TAGGER, systemPrompt,
    userPrompt)`.
  - Parses the reply as strict JSON `{"tags": [...]}`.
  - For each tag in the parsed array: normalize per
    `docs/spec/commands.md` §Surface conventions (NFC +
    lower-case + character class), then check membership in
    `TagVocabulary`.
  - Keeps the valid tags; silently drops the invalid ones per
    `docs/spec/llm.md` §Failure handling "Partial-valid
    handling." Records an INFO log with the valid+invalid
    counter.
  - **Fallback chain**:
    - Schema-violating output (JSON parse fails OR no `tags`
      array): retry once with `tagger-fallback.md`. On second
      failure: bootstrap fallback.
    - Zero valid tags after partial-valid handling: retry
      once with the same primary prompt (vocabulary mismatch
      isn't solved by changing prompt format). On second
      failure: bootstrap fallback.
    - LLM unreachable / timeout: retry once. On second
      failure: bootstrap fallback.
    - **Bootstrap fallback**: `post.tags =
      source.bootstrap_tags`, `post.tagger_fallback=true`, log
      WARN with `error_class='tagger.fallback_to_bootstrap'`.
  - UPDATE `post SET tags=:tags, tagger_done=true,
    tagger_fallback=:fallback WHERE id=:post_id AND
    fetched_at=:fetched_at` — atomic write of the tag array
    and the cursor flags.
- **`EmbeddingMetadataDao.java`** is the SOLE write path to
  `embedding_metadata`. Two SQL shapes: read singleton +
  UPDATE singleton (only on operator override).
- **`EmbeddingMetadataStartupGuard.java`** is a Collector-side
  `@Startup` bean at `@Priority(125)` (between Flyway @ 100
  and M1-033's `LlmRouterStartupGuard` @ 150). Reads the
  configured `infochat.embeddings.model` and
  `infochat.embeddings.dimension`, compares to
  `embedding_metadata`, refuses startup on mismatch with a
  fatal log line naming both values + the re-embed procedure
  path. When `infochat.embeddings.allow-model-change=true`,
  bypasses the check, updates `embedding_metadata` with the
  new values, logs WARN.
- **`EmbeddingWorker.java`** picks up posts on `status='RAW'
  AND tagger_done=true AND embedding_done=false`. Batches by
  `infochat.embeddings.batch-size` (default 16). For each
  batch:
  - Builds the per-post input text: `title + "\n\n" +
    (body_summary OR first 800 chars of body)` per
    `docs/design/05-llm-and-embeddings.md` §5.5 step 1.
  - Invokes `provider.embed(List<String>)` with the N inputs.
  - On per-vector dimensionality mismatch: throws
    immediately (no retry).
  - On batch failure (exception, wrong-shape result):
    retries ONCE with the SAME batch. On second failure:
    every post follows the no-vector release path
    (`embedding_done=true`, NO `post_embedding` row, log
    WARN `error_class='embedding.batch_failure'`).
  - On batch success: INSERTs N `post_embedding` rows;
    UPDATE each post's `embedding_done=true`.
  - Concurrency bounded by
    `infochat.embeddings.max-concurrency` (laptop 4 / vps 2
    / pi 1 / remote-llm 8 per §5.7).
- **`ReadyPromoter.java`** picks up posts on `status='RAW'
  AND stage1_done=true AND (stage1_flagged=false OR
  stage2_done=true) AND tagger_done=true AND
  embedding_done=true`. For each post (or in batched ticks
  — implementation choice, document it):
  - UPDATE `post SET status='READY', ready_at=now(),
    status_changed_at=now() WHERE id=:post_id AND
    fetched_at=:fetched_at`.
  - Emit `pg_notify('new_post', '<JSON payload>')` where
    payload is `{"ready_at": "<iso8601>", "post_id":
    "<uuid>"}` — the cursor key per
    `docs/design/02-schema.md` §2.9.1.
  - **Both operations in the SAME DB transaction** per
    `docs/spec/architecture.md` §Inter-service communication
    "the high-water mark advances both fields in the same DB
    transaction as the side effect it triggers, making
    processing idempotent."
- **`application.properties`** amended with embedding +
  tagger property keys and laptop-profile defaults.
- **Three new tests**:
  - `TaggerWorkerIT.java` (`@QuarkusTest`) — seven scenarios.
  - `EmbeddingWorkerIT.java` (`@QuarkusTest`) — five
    scenarios including the dimensionality-mismatch
    fatal-at-runtime case.
  - `ReadyPromoterIT.java` (`@QuarkusTest`) — Stage-5
    transition + real NOTIFY emit + same-transaction rule +
    quarantined exclusion + stage2_failed release path +
    model-identity startup guard.
- `mvn -B clean verify` from repo root exits 0.

## Implementation notes

- **Option B (3 tickets) chosen at the top of this authoring
  session.** This is the third ticket. See M1-032's
  "Alternatives considered" for the full rationale.
- **Migration version is V11.** V10 lands in M1-032 (the
  `quarantine` table). If a later authoring session lands
  M1-021's identity/audit redteam remediation migration
  before this ticket starts, re-grep the migration directory
  at `/m1-tick start` time and slide this migration to V12.
- **Embedding dimension per profile.** V11 ships the
  laptop/vps default of 768 (`nomic-embed-text`). The pi
  profile's 384 (`all-minilm:33m`) and the remote-llm
  profile's 1536 (`text-embedding-3-small`) are
  selected at deploy time via either (a) a profile-specific
  migration file (V11_pi__post_embedding.sql etc.) or (b) a
  startup hook that issues `ALTER TABLE` against the column
  type. Either is operator territory; the canonical migration
  ships the laptop/vps default. Document the operator path
  in the migration's header comment.
- **HNSW vs IVFFlat index.** V11 ships HNSW (laptop/vps/
  remote-llm). The pi profile's IVFFlat is also operator
  territory. Document in the migration header.
- **Singleton enforcement on `embedding_metadata`.** Two
  shapes: (a) `CREATE UNIQUE INDEX ON embedding_metadata
  ((TRUE))` — only one row matching the constant predicate;
  (b) a synthetic `id INT NOT NULL DEFAULT 1` column with
  `UNIQUE (id)` and a CHECK that `id = 1`. Either works.
  Pick (a) — simpler, no superfluous column.
- **Tagger pickup invocation shape.** Three options:
  1. Periodic polling (`@Scheduled` every N seconds, SELECT
     for posts to tag).
  2. Push from upstream — Stage 1's clean-finish AND Stage
     2's release-path call into TaggerWorker directly.
  3. A new SmallRye channel `tagger-queue` that Stage 1 and
     Stage 2 emit to.

  (1) matches the M1-028 `FetchScheduler` polling pattern
  and is simpler; the polling interval is profile-driven
  (default 5s on laptop, document choice). (2) reduces
  latency but couples three classes. (3) duplicates
  channel machinery for no benefit. Pick (1); document the
  rejected alternatives.
- **EmbeddingWorker pickup invocation shape.** Same three
  options. Same recommendation: (1) periodic polling with a
  separate batch-flush timer that fires when the in-memory
  batch buffer is full OR a profile-driven timeout elapses.
- **ReadyPromoter invocation shape.** Same again — periodic
  polling for posts ready to promote.
- **The Tagger's vocabulary normalization.** Per
  `docs/spec/commands.md` §Surface conventions, the tag
  normalization rule is "NFC + lower-case + character
  class." The "character class" is the canonical ASCII
  identifier shape (lower-case letters + digits + hyphen +
  underscore — confirm by reading `docs/spec/commands.md`
  if needed at implementation time, or by re-reading
  `docs/design/02-schema.md` §2.2.1 if that's where the
  exact char class lives). The normalization helper lives
  in `infochat-core` if already authored by M1-008b; if
  not, inline a small helper in `TaggerWorker.java` with a
  TODO comment to extract to `infochat-core` later
  (M1-008b's V6 migration includes the tag controlled
  vocabulary so the normalization rule has at least one
  reference point).
- **The Tagger's two retry shapes.** The spec is precise:
  schema-violating output retries with a DIFFERENT
  fallback prompt (line-oriented) because re-issuing the
  same JSON-mode prompt to the same small model tends to
  produce the same garbage. Zero-valid-after-validation
  AND LLM-unreachable retry with the SAME prompt because
  those failure modes are unrelated to the prompt shape
  (vocabulary mismatch is a content issue; unreachability
  is an infrastructure issue). Document the choice in
  `TaggerWorker.java`'s JDoc.
- **One-failure-fails-batch and the not-split-on-retry
  rule.** Per `docs/spec/llm.md` §Failure handling (recap)
  "Retry policy: on a batch failure the same batch is
  resubmitted as-is; the batch is not split on retry. If
  batch size correlates with failures, operators reduce
  the profile-driven batch size — the spec does not
  introduce a per-retry split path." The retry passes the
  IDENTICAL `List<String>` input to `provider.embed`; no
  per-failure halving, no per-post fallback to a single-element
  batch.
- **Dimensionality mismatch is fatal at runtime.** Per
  `docs/spec/llm.md` §Embedding pipeline "Storing vectors
  of mixed dimensions in the same pgvector column silently
  corrupts cosine similarity scores. The only safe
  recovery is a full re-embed." Throwing immediately on
  dimensionality mismatch keeps the worker from advancing
  `embedding_done=true` and lets the operator surface the
  issue via logs. The startup guard catches the
  configuration case; the runtime check catches the
  mid-process provider-version-switch case (e.g. Ollama
  pulls a different model version between calls).
- **`ready_at` is set ONLY in `ReadyPromoter`.** Per
  Invariant 5, `status='RAW'` is the in-flight
  representation and the per-stage flags are the durable
  cursor. The transition to `status='READY'` happens here
  in M1-034 at Stage 5; no earlier ticket sets
  `ready_at`. The M1-027 `NewPostListener` parses the
  payload's `ready_at` field — that field is set HERE for
  the first time.
- **The NOTIFY payload format must match M1-027's parser
  contract.** M1-027's `NewPostListener.parsePayload` is a
  package-private static method in
  `infochat-provider/src/main/java/io/infochat/provider/outbox/NewPostListener.java`
  that extracts `ready_at` via the regex
  `"ready_at"\s*:\s*"([^"]+)"` then calls `Instant.parse`,
  and `post_id` via `"post_id"\s*:\s*"([^"]+)"` then calls
  `UUID.fromString`. The two regexes are independent so
  field order does not matter; whitespace between key and
  colon is tolerated. ReadyPromoter builds the payload
  inline (no shared helper extracted — M1-027's parser
  lives in `infochat-provider` and extracting it would
  violate this ticket's out-of-scope rule against
  Provider-module edits). The emit code uses
  `Instant.now().toString()` (or the ISO-8601 form returned
  by the JDBC `TIMESTAMPTZ` round-trip) and
  `UUID.toString()` so both `Instant.parse` and
  `UUID.fromString` round-trip cleanly. The contract is the
  JSON byte shape, not a shared class.
- **`status_changed_at` is set on every status transition.**
  Per `docs/design/02-schema.md` §2.3.1 `status_changed_at
  TIMESTAMPTZ NOT NULL DEFAULT now() — updated on every
  status transition`. The RAW → READY transition fires the
  `status_changed_at = now()` write alongside the
  `status = 'READY'` write. The `quarantine_review` cursor
  for `post.status → NEEDS_REVIEW` (T2-G territory) uses
  `status_changed_at` — keeping the column accurate here
  is the foundation for that future feature.
- **The `EmbeddingMetadataStartupGuard` placement.**
  `@Priority(125)` — between Flyway (100) and the M1-033
  `LlmRouterStartupGuard` (150). The model identity check
  must run AFTER Flyway (the `embedding_metadata` row
  must exist) and BEFORE the LLM router guard (the order
  is operator-debugging friendly: model-identity is the
  most fundamental data invariant; router config is
  operator preference).
- **`embedding_model` column in `post_embedding`.** Per
  `docs/design/02-schema.md` §2.4.2, the column records the
  active embedding model identifier (e.g.
  `'nomic-embed-text:v1'`) so a future re-embed can compare
  per-row models without consulting the metadata table.
  The value is read from `embedding_metadata.model_identifier`
  (the canonical record) at INSERT time.
- **Bounded concurrency.** Two workers, two semaphores:
  `infochat.llm.tagger.max-concurrency` (laptop 4) and
  `infochat.embeddings.max-concurrency` (laptop 4) per
  §5.7. Same shape as M1-033's Stage 2 semaphore.
- **Stub providers in the ITs.** Hand-written
  `TestStubLlmProvider` (for TaggerWorkerIT — separate
  instance from M1-033's, or reuse if the M1-033 stub is
  in a shared test source root) and `TestStubEmbeddingProvider`
  (for EmbeddingWorkerIT). Both selected via
  `@Alternative @Priority(Integer.MAX_VALUE)` for the test
  profile. The same pattern as M1-033.

## Big-picture notes

- **The pipeline is now end-to-end.** With M1-032 +
  M1-033 + M1-034 landed, an RSS feed → Fetcher (M1-023)
  → SSRF gate (M1-024/025/026) → outbox (M1-028) →
  Stage 1 (M1-032) → Stage 2 if flagged (M1-033) →
  Tagger (this ticket) → Embedding (this ticket) →
  status='READY' + NOTIFY (this ticket) → Provider
  catch-up listener (M1-027) is a runnable chain. T1-D
  closes the eval pipeline; T1-E adds the messaging
  adapter and T1-F adds the first user-facing commands.
- **No EntityExtractor in T1-D.** Per the session-grouping-
  plan T1-D row "Stage 1 deterministic security, LLM +
  Stage 2, tagger + embedding" — entity extraction is
  intentionally not enumerated. The pipeline skips
  `docs/design/01-architecture.md` §1.3.4 step 3
  (EntityExtractor → `post_entity` rows). LinkingJob
  (§1.3.5) is also T2 because it consumes `post_entity`
  rows that don't exist yet. T1-D's pipeline is S1 →
  (S2 if S1 hit) → Tagger → Embedding → READY. The
  reviewer's negative-space check should not flag the
  gap.
- **The post is fully visible to the Provider once
  status='READY' fires.** M1-027's `NewPostListener` is
  the Provider-side consumer. Once this ticket fires
  `pg_notify('new_post', {ready_at, post_id})`, the
  Provider's listener wakes, invokes
  `NewPostHandler.handle(...)`, advances the
  `provider_state` cursor for the `new_post` channel. The
  stub handler from M1-027 logs the event; T1-F adds the
  real cache-invalidation + group-digest-recompute logic
  inside the same `@Transactional` boundary.
- **The Embedding model identity guard is load-bearing.**
  Per `docs/spec/llm.md` §Embedding pipeline,
  dimensionality mismatch corrupts cosine similarity
  scores. The startup guard makes the failure mode
  reachable only via explicit operator override; the
  runtime fatal-throw catches the case where a provider
  silently switches model versions mid-process. Without
  the guard, an operator who changes
  `infochat.embeddings.model` without re-embedding gets a
  silently-corrupted vector store.
- **The `one-failure-fails-batch` retry is intentional.**
  Per `docs/spec/llm.md` §Embedding pipeline: "Silently
  dropping some posts from a batch result without a clean
  per-post error mapping is a worse failure mode than a
  uniform retry." A partial-success path (insert N-1
  rows, drop one post) would silently lose the dropped
  post; a uniform retry-then-no-vector-for-all path is
  audit-friendly (the operator sees the batch-failure
  WARN log and knows N posts have no vector). The
  trade-off favors auditability over per-post
  optimization.
- **The Tagger fallback chain has THREE failure surfaces.**
  Schema violation, zero-valid, and unreachability all
  fall through to `source.bootstrap_tags`. The chain
  matters because `/add-source --tags` is mandatory per
  `docs/spec/security.md` and the bootstrap-tags exist
  precisely as the deterministic fallback so every source
  has working tags regardless of LLM outage.
  `tagger_fallback=true` is the audit flag — admins can
  query "show me posts whose tags came from bootstrap
  fallback over the last 24h" to spot sustained LLM
  outages or vocabulary-mismatch issues.
- **The partial-valid handling preserves useful tags.**
  Per `docs/spec/llm.md` §Failure handling "When the LLM
  emits a list of tags and only some entries pass the
  controlled-vocabulary validation ... the valid tags are
  kept and the invalid tags are silently dropped — losing
  useful information because of one bad entry would
  degrade tagging quality across deployments where the
  smaller models occasionally emit one out-of-vocab tag
  in an otherwise-clean list." The per-post valid+invalid
  counter feeds a future operator alert on sustained high
  invalid rates (T2 observability), which is the signal
  that the prompt or the vocabulary needs adjustment.
- **The `pg_notify` is inside the SAME transaction as
  the `status='READY'` UPDATE.** Per
  `docs/spec/architecture.md` §Inter-service
  communication "the high-water mark advances both
  fields in the same DB transaction as the side effect
  it triggers, making processing idempotent. ... a
  duplicate NOTIFY or a repeated catch-up pass for the
  same row produces no additional side effect." If the
  NOTIFY emit were outside the transaction, a rollback
  AFTER the NOTIFY would leave a phantom event on the
  wire (no row to back it); the Provider listener would
  process it and CAS-advance the cursor past a
  non-existent post. Same transaction is the
  correctness invariant.
- **Subticket isolation against M1-032 / M1-033.** This
  ticket lives under `infochat-collector/.../eval/tagger/`,
  `infochat-collector/.../eval/embedding/`,
  `infochat-collector/.../eval/ready/`,
  `infochat-llm-adapter/.../impl/OpenAiCompatibleEmbeddingProvider.java`,
  and the V11 migration. M1-032 lives under
  `infochat-collector/.../eval/stage1/` and adds V10.
  M1-033 lives under `infochat-collector/.../eval/stage2/`
  and `infochat-llm-adapter/.../impl/` +
  `infochat-llm-adapter/.../routing/` and adds no
  migration. The three `files_scope` lists are disjoint
  at the file path level.

## Out-of-scope expansion

- **Stage 1 HTML sanitizer, regex set, watchdog,
  quarantine-row insertion.** M1-032 territory; consumed
  unchanged.
- **Stage 2 LLM judge, OpenAiCompatibleProvider,
  LlmRouter, release-on-stage2-failure flag.** M1-033;
  consumed unchanged. (The (ModelTask, scope_language)
  router is consumed for the TAGGER task; embedding has
  its own provider resolution per spec §SPI shape.)
- **EntityExtractor, post_entity table, post_reference
  table, LinkingJob.** T2.
- **Re-evaluation job, attempt counter, NEEDS_REVIEW
  transition, per-source UNKNOWN auto-disable.** T2-G.
- **Throttled admin notifier wiring.** T2-G. This ticket
  logs at INFO/WARN with canonical `error_class` strings.
- **LLM output sanitizer.** T1-F. Tagger output is
  validated against the controlled vocabulary;
  embedding output is a numeric vector — neither
  reaches a user.
- **`/quarantine` admin commands, stored procedures.**
  T2-G.
- **Provider-side `quarantine_review` LISTEN listener.**
  M2.
- **AnthropicProvider, AnthropicEmbeddingProvider.**
  T3-D.
- **TranslationProvider impl.** T1-F.
- **Chat-agent recall tool, five-tool allowlist.** T2-D.
- **V1..V10 migration changes.** Frozen. V11 adds
  `post_embedding` + `embedding_metadata` only.
- **M1-007b SPI surface changes.** Frozen.
- **`infochat-provider` module changes.** Collector +
  core-migration + llm-adapter only.
- **partition_pruner job.** T2 territory. V11 creates the
  initial partition; the nightly DROP-PARTITION schedule
  lives in T2.
- **Embedding-model migration script (`scripts/reembed.sh`).**
  Operator tooling, not code. The startup guard refuses
  on mismatch; the operator handles the migration
  out-of-band.
- **Per-task fallback chain.** v2 candidate.
- **Prometheus metric emit** for `tagger_partial_valid_total`
  etc. Observability ticket later.
- **Provider-side cache invalidation, group-digest
  recompute, NewPostHandler real-consumer logic.** T1-F.
  This ticket emits the NOTIFY; M1-027's stub handler
  picks it up; T1-F replaces the stub with real consumer
  logic.

## Authorized test changes

- (none — this ticket adds three new test files under
  `infochat-collector/src/test/java/io/infochat/collector/eval/tagger/`,
  `.../embedding/`, and `.../ready/`, and one new Flyway
  migration under `infochat-core`. No pre-existing tests
  are modified. The V11 migration applies cleanly
  alongside V1..V10; `FlywayMigrationIT` (M1-017)
  continues to pass without edit; `DbRoleMatrixIT`
  (M1-006) continues to pass against the new GRANT
  additions without edit. M1-027's
  `NewPostListenerIT` continues to pass — its test
  harness emits NOTIFY directly via JDBC, which is
  unrelated to this ticket's production-emit path; both
  paths fire the same payload shape.)

## Alternatives considered

- **Option A — combine Stage 1 + Stage 2 into one
  ticket.** Rejected at the top of this authoring
  session. See M1-032's "Alternatives considered."
- **Option C — split Tagger off from Embedding into
  separate tickets.** Rejected. The tagger and embedding
  cores share the `status='RAW' → tagger_done → embedding_done`
  state-machine advance and ultimately feed the same
  ReadyPromoter at Stage 5. Splitting them forces the
  reviewer to follow the same state-machine across two
  diffs and ship the ReadyPromoter in either the tagger
  or the embedding ticket (or in a fourth ticket) — each
  shape introduces awkwardness. The 3-ticket split keeps
  the tagger fallback chain, the embedding batch retry,
  the model identity guard, and the Stage-5 promoter all
  in one diff where their interactions are visible.
- **Route embedding through the (ModelTask, scope_language)
  router.** Rejected on spec grounds. Per
  `docs/spec/llm.md` §SPI shape "Scope of the enum. The
  embedder is not a ModelTask — EmbeddingProvider is a
  distinct SPI with its own provider selection." The
  router was authored in M1-033 for LlmProvider tasks
  only; this ticket's embedding pipeline uses a separate
  resolution path (one provider per deployment, no
  per-language or per-task routing).
- **Split the embedding batch on retry.** Rejected on
  spec grounds. Per `docs/spec/llm.md` §Failure handling
  (recap) "Retry policy: on a batch failure the same
  batch is resubmitted as-is; the batch is not split on
  retry. If batch size correlates with failures,
  operators reduce the profile-driven batch size — the
  spec does not introduce a per-retry split path."
- **Treat dimensionality mismatch as a retry-able batch
  failure rather than a fatal-at-runtime error.**
  Rejected on spec grounds. Per
  `docs/spec/llm.md` §Embedding pipeline "Dimensionality
  mismatch at runtime is fatal. ... The only safe
  recovery is a full re-embed." A retry against the same
  misconfigured provider produces the same wrong-dim
  result; the right action is to stop processing and
  surface the error.
- **Auto-update `embedding_metadata` on first observed
  mismatch without the operator override flag.**
  Rejected on spec grounds. Per
  `docs/spec/llm.md` §Embedding pipeline "An explicit
  operator override flag bypasses the check for
  intentional migration runs." Silent auto-update would
  let an accidental model change corrupt the vector
  store; the explicit flag forces the operator to opt in.
- **Use Mustache for the Tagger prompt template
  substitution.** Acceptable here — the Tagger prompt has
  a Mustache iteration over the vocabulary list (`{{#tags}}
  - {{name}} {{/tags}}`), which is more than the
  two-substitution case M1-033's Stage 2 prompt has.
  Quarkus has built-in Qute templating; reuse it. Or
  pull in a small Mustache dep; either is acceptable.
  Document the choice. The M1-033 Stage 2 prompt's
  `String.replace`-only approach doesn't generalize to
  iteration.
- **Emit `pg_notify` outside the
  `status='READY'`-UPDATE transaction.** Rejected on spec
  grounds. Per `docs/spec/architecture.md`
  §Inter-service communication "the high-water mark
  advances both fields in the same DB transaction as the
  side effect it triggers." A NOTIFY outside the
  transaction would survive a rollback as a phantom
  event.
- **Skip the partition_pruner job here and rely on
  unbounded growth until T2.** Acceptable for M1 — the
  retention horizon per `docs/design/02-schema.md`
  §2.4.4 is 4 days for `post_embedding`, so unbounded
  growth in M1 is bounded by the actual ingest rate.
  Document the explicit T2 boundary in out_of_scope.
- **Land the AnthropicEmbeddingProvider here so the SPI
  is exercised against two providers.** Rejected per
  session-grouping-plan §Tier 3. T3-D ships the
  Anthropic providers (both LlmProvider and
  EmbeddingProvider, since Anthropic's wire format
  differs from OpenAI's). v1's first concrete
  EmbeddingProvider is OpenAI-compatible only.
- **Defer the model identity guard to a later T2
  observability ticket.** Rejected on spec grounds. The
  guard is the load-bearing safety mechanism that
  prevents silent vector-store corruption; it MUST land
  with the first EmbeddingProvider impl, not later.
- **Set `post.status='READY'` in M1-032 (clean Stage 1)
  or M1-033 (BENIGN or infra-fail-release) instead of
  here in M1-034.** Rejected on Invariant 5 grounds.
  Per `docs/spec/schema.md` §Invariants Invariant 5
  "Posts in RAW with one or more stage-outcome flags
  already set resume from the next uncompleted stage;
  the per-stage flags are the durable cursor. There is
  no distinct 'evaluating' status — RAW plus the flag
  bitmap is the complete representation of in-flight
  evaluation state." Setting `status='READY'` before
  Tagger and Embedding run would break the
  Invariant-5 representation (a post would appear
  "done" while still in-flight). The clean interpretation
  is: `status='RAW'` throughout the eval pipeline, with
  per-stage flags as the cursor; `status='READY'` is the
  Stage-5 promotion. The
  `docs/design/04-security.md` §4.7 row that reads
  "post.status='READY'" on Stage-2 infra-failure is
  treated as shorthand for "follow the release path that
  ends at READY" — the literal flip happens in Stage 5.
