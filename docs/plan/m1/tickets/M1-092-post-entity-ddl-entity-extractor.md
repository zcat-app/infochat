---
id: M1-092
title: "post_entity DDL + EntityExtractor pipeline stage"
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by: []
files_budget: 10
files_scope:
  - infochat-core/src/main/resources/db/migration/V26__post_entity.sql
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/entity/EntityExtractorWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/entity/EntityExtractionResult.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/ready/ReadyPromoter.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/entity/EntityExtractorWorkerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/entity/EntityExtractorWorkerIT.java
  - infochat-collector/src/test/resources/application.properties
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: true
out_of_scope:
  - infochat-provider/** — Provider-side tools and commands are not modified
  - infochat-messaging-adapter/** — no adapter changes
  - any change to EmbeddingWorker.java pickup criteria — entity and embedding run in parallel after tagger; both must complete before ReadyPromoter promotes, but neither gates the other
  - any change to EmbeddingWorkerTest.java or EmbeddingWorkerIT.java — existing embedding tests pass unchanged
  - any change to Stage1Worker, Stage2Worker, TaggerWorker — upstream pipeline stages are not modified
  - any change to Stage1WorkerIT, Stage2WorkerIT, TaggerWorkerTest — existing tests pass unchanged
  - post_reference DDL or LinkingJob — M1-093
  - GetReferencesTool or ClusterTraversal wiring — M1-093
  - any change to ReadyPromoterIT.java beyond what the entity_done gate requires (if the IT seeds posts, it must now set entity_done=TRUE on seeded rows)
  - D42 fetcher failure wiring — M1-094
  - Nostr kind-6 linking — M3 scope
acceptance:
  - "Flyway migration V26__post_entity.sql applies cleanly on a fresh DB and on a DB with V1–V25 already applied"
  - "V26 ALTERs the post table to add column entity_done BOOLEAN NOT NULL DEFAULT FALSE"
  - "V26 updates all existing rows: UPDATE post SET entity_done = TRUE WHERE tagger_done = TRUE — any post that has already passed tagger has implicitly passed entity extraction (no entities extracted, same as a failure-release)"
  - "V26 creates the post_entity table partitioned by fetched_at with columns (post_id UUID NOT NULL, entity_text TEXT NOT NULL, entity_type TEXT NOT NULL CHECK (entity_type IN ('cve','product','org','person','location','project')), fetched_at TIMESTAMPTZ NOT NULL) and PRIMARY KEY (post_id, entity_text, entity_type, fetched_at)"
  - "V26 creates index idx_post_entity_text ON post_entity(entity_text, entity_type)"
  - "V26 GRANTs INSERT, SELECT on post_entity to infochat_collector; GRANTs SELECT on post_entity to infochat_provider"
  - "EntityExtractorWorker is a scheduled CDI bean in collector/eval/entity/ that picks up posts matching status='RAW' AND tagger_done=TRUE AND entity_done=FALSE"
  - "EntityExtractorWorker calls LlmRouter.forTask(ModelTask.ENTITY, ...) to obtain the LLM provider, sends the post body, and parses the structured response into (entity_text, entity_type) pairs"
  - "EntityExtractorWorker normalizes entity_text (lower-cased, stripped) before INSERT into post_entity"
  - "EntityExtractorWorker sets entity_done=TRUE on the post after successful extraction (with rows) or after failure-release (without rows)"
  - "On LLM failure: 1 retry, then release without entities (entity_done=TRUE, no post_entity rows), throttled admin notification via ThrottledAdminNotifier"
  - "ReadyPromoter's promotion criteria include entity_done=TRUE — a post with entity_done=FALSE is not promoted to READY"
  - "EntityExtractorWorkerTest.successfulExtraction_insertsEntitiesAndSetsFlag passes — a fake LLM returns 3 entities of mixed types; all 3 are inserted into post_entity with normalized text; entity_done=TRUE on the post"
  - "EntityExtractorWorkerTest.noEntitiesFound_setsFlag passes — a fake LLM returns an empty entity list; no post_entity rows; entity_done=TRUE on the post"
  - "EntityExtractorWorkerTest.llmFailureAfterRetry_releasesWithoutEntities passes — LLM fails twice (initial + 1 retry); entity_done=TRUE with no post_entity rows; throttled admin notification fires"
  - "EntityExtractorWorkerTest.entityTextNormalization_lowerCasesAndStrips passes — entities with mixed case and whitespace are stored lower-cased and stripped"
  - "EntityExtractorWorkerTest.invalidEntityType_droppedSilently passes — an entity_type not in the CHECK constraint set is dropped; valid entities from the same response are kept"
  - "EntityExtractorWorkerIT.endToEndEntityExtraction passes — seeds a RAW post with tagger_done=TRUE, entity_done=FALSE; after worker tick, entity_done=TRUE and post_entity rows exist in the DB"
  - "All pre-existing ReadyPromoterIT test methods pass (seeded posts must set entity_done=TRUE)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/entity/EntityExtractorWorkerTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/entity/EntityExtractorWorkerIT.java
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/ready/ReadyPromoterIT.java (seeded posts must set entity_done=TRUE to reach promotion; authorization: entity_done gate addition)
  preserves:
    - all tests currently green on main
    - EmbeddingWorkerTest, EmbeddingWorkerIT — unchanged
    - Stage1WorkerIT, Stage2WorkerIT, TaggerWorkerTest — unchanged
spec_refs:
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/architecture.md §Pipelines
  - docs/spec/llm.md §Failure handling
decision_refs:
  - D6
  - D22
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-092: post_entity DDL + EntityExtractor pipeline stage

## Context

The eval pipeline currently runs Stage 1 → Stage 2 → Tagger → Embedding
→ ReadyPromoter. The spec commits to an entity extraction step between
tagger and embedding (`architecture.md` §Pipelines: "tagger → entity
extraction → embedding → mark READY → NOTIFY"). `ModelTask.ENTITY`
exists in the enum and `LlmRouter` routes it, but no extraction code
exists. No `post_entity` DDL exists in any migration. This ticket
creates the table and the pipeline stage worker.

D6 commits to hybrid cross-source linking using named-entity match
(precision) plus cosine similarity (recall). This ticket delivers the
entity extraction half; M1-093 delivers the LinkingJob that consumes
the extracted entities.

## Acceptance

**V26 migration.** Adds `entity_done BOOLEAN NOT NULL DEFAULT FALSE`
to the `post` table. Backfills existing rows: any post with
`tagger_done=TRUE` gets `entity_done=TRUE` (these posts have already
passed through the pipeline and should not re-enter entity extraction).
Creates the `post_entity` table partitioned by `fetched_at` with the
design-note schema (§2.4.1): `(post_id, entity_text, entity_type,
fetched_at)` with a CHECK on entity_type and an index on
`(entity_text, entity_type)`. Grants Collector INSERT/SELECT and
Provider SELECT.

**EntityExtractorWorker.** Scheduled worker in
`collector/eval/entity/`. Picks up `status='RAW' AND tagger_done=TRUE
AND entity_done=FALSE`. Calls `LlmRouter.forTask(ModelTask.ENTITY)`
for the LLM provider. Sends the post body and parses the structured
response into `(entity_text, entity_type)` pairs. Normalizes
`entity_text` (lower-cased via `Locale.ROOT`, whitespace-stripped)
before INSERT. Sets `entity_done=TRUE` after successful extraction or
after failure-release.

**Failure policy (D22).** 1 retry on LLM failure, then release
without entities (`entity_done=TRUE`, no `post_entity` rows). Throttled
admin notification via `ThrottledAdminNotifier`. A post released
without entities still reaches READY — Tier-2 linking coverage is
degraded for that post but deterministic retrieval is unaffected.

**ReadyPromoter update.** The promotion criteria add
`entity_done=TRUE`. A post whose entity extraction has not completed
(or not started) is not promoted.

**Entity and embedding run in parallel.** Both are gated on
`tagger_done=TRUE` and independent of each other. Neither gates the
other's pickup. ReadyPromoter checks both flags.

## Out-of-scope

- **EmbeddingWorker pickup criteria** — unchanged. Entity and
  embedding are parallel stages; no sequential dependency.
- **Post reference / LinkingJob** — M1-093 (consumes the entities
  this ticket produces).
- **GetReferencesTool / ClusterTraversal** — M1-093.
- **Upstream pipeline stages** (Stage 1, Stage 2, Tagger) — unchanged.
- **D42 failure ladder** — M1-094.
- **Nostr kind-6 linking** — M3 scope.

## Notes

- **Entity types from design notes** (`02-schema.md` §2.4.1):
  `'cve','product','org','person','location','project'`. The CHECK
  constraint on `entity_type` enforces this set. The LLM prompt
  should enumerate the types so the model produces values within the
  constraint; out-of-vocab types in the LLM response are dropped
  silently (similar to tagger partial-valid handling in M1-081a).
- **LLM prompt shape.** The prompt sends the post body (after
  Stage 1 sanitization — all content reaching entity extraction has
  already passed Stage 1) and asks for structured JSON output:
  `[{"text": "...", "type": "..."}]`. The exact prompt template
  is an implementation detail; the acceptance criteria pin the
  behavioral contract (normalization, insertion, flag-setting), not
  the prompt text.
- **Parallel stages.** The spec's arrow notation ("entity extraction
  → embedding") describes pipeline ordering in the ingest flow
  diagram, not a strict sequential dependency. Both stages consume
  the post body independently. ReadyPromoter is the synchronization
  point that waits for both. This avoids modifying EmbeddingWorker
  and keeps the two stages independently scalable.
- **Partition alignment.** `post_entity` is partitioned by
  `fetched_at` (duplicated from `post.fetched_at`) so partition
  pruning on the 4-day TTL window works without a cross-partition
  JOIN. The partition lifecycle job (`02-schema.md` §2.4.4) manages
  `post_entity` partitions alongside `post_embedding` and
  `post_reference`.
- **Adjacent code patterns.** EmbeddingWorker for the pickup/retry/
  flag-setting pattern. TaggerWorker for the LLM-call/response-parse
  pattern. ThrottledAdminNotifier for failure notification.
- **Design reference:** `docs/design/01-architecture.md` §1.3.4
  (entity extraction step), `docs/design/02-schema.md` §2.4.1
  (post_entity DDL).
