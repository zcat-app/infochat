---
id: M1-093
title: "post_reference DDL + LinkingJob + tool wiring"
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by:
  - M1-092
files_budget: 12
files_scope:
  - infochat-core/src/main/resources/db/migration/V27__post_reference.sql
  - infochat-collector/src/main/java/app/zcat/infochat/collector/linking/LinkingJob.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/GetReferencesTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/ClusterTraversal.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/linking/LinkingJobTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/linking/LinkingJobIT.java
  - infochat-collector/src/test/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/GetReferencesToolTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/ClusterTraversalTest.java
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: true
out_of_scope:
  - infochat-messaging-adapter/** — no adapter changes
  - any change to EntityExtractorWorker — M1-092 is frozen
  - any change to EmbeddingWorker — not modified
  - any change to Stage1Worker, Stage2Worker, TaggerWorker — upstream pipeline stages are not modified
  - any change to ReadyPromoter — M1-092's entity_done gate is frozen
  - any change to the post_entity DDL (V26) — M1-092 is frozen
  - Nostr kind-6 cross-source linking via upstream_identifier — M1-100; LinkingJob processes only entity-match and cosine-similarity link types in this ticket
  - any modification to EmbeddingWorkerTest, EmbeddingWorkerIT, ReadyPromoterIT, or any other pre-existing test
  - StreamSource or Nostr relay infrastructure — M3 scope
acceptance:
  - "Flyway migration V27__post_reference.sql applies cleanly on a fresh DB and on a DB with V1–V26 already applied"
  - "V27 creates the post_reference table partitioned by created_at with columns (from_post UUID NOT NULL, to_post UUID NOT NULL, link_type TEXT NOT NULL CHECK (link_type IN ('entity','semantic','repost')), score REAL NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()) and PRIMARY KEY (from_post, to_post, link_type, created_at)"
  - "V27 creates index idx_post_ref_from ON post_reference(from_post, link_type) and idx_post_ref_to ON post_reference(to_post)"
  - "V27 GRANTs INSERT, SELECT on post_reference to infochat_collector; GRANTs SELECT on post_reference to infochat_provider"
  - "LinkingJob is a scheduled CDI bean in collector/linking/ that runs on a configurable interval (infochat.linking.interval, profile-driven)"
  - "LinkingJob driving set: READY posts where last_linked_at IS NULL OR last_linked_at < fetched_at, bounded to a configurable lookback window (infochat.linking.lookback-days, default 4 days)"
  - "For each driving post, LinkingJob finds entity-match candidates: posts sharing at least one (entity_text, entity_type) pair in post_entity within the lookback window; inserts post_reference rows with link_type='entity' and score=count of shared entities"
  - "For each driving post with an embedding, LinkingJob finds semantic candidates: posts within a configurable time window (infochat.linking.semantic-window-hours, default 48h) whose cosine_distance < infochat.linking.semantic-threshold; inserts post_reference rows with link_type='semantic' and score=cosine similarity"
  - "Semantic candidates filter WHERE embedding IS NOT NULL per spec (schema.md §Posts and derivatives)"
  - "Post references are written bidirectionally (A→B and B→A) per design notes §2.4.3"
  - "Outbound links per post are capped at infochat.linking.max-links-per-post (default 10); highest score wins when cap is exceeded"
  - "LinkingJob updates post.last_linked_at = now() for each processed driving post"
  - "GetReferencesTool returns actual post_reference rows instead of an empty JSON array — query joins post_reference with post to return (from_post, to_post, link_type, score, post title/url) for the requested post_id"
  - "ClusterTraversal computes connected components from post_reference edges instead of singleton clusters — walks the bidirectional graph up to a configurable depth limit"
  - "LinkingJobTest.entityMatch_createsBidirectionalReferences passes — two posts sharing 2 entities produce post_reference rows in both directions with link_type='entity' and score=2"
  - "LinkingJobTest.semanticMatch_createsBidirectionalReferences passes — two posts within the semantic window with cosine_distance below threshold produce post_reference rows with link_type='semantic' and score=cosine similarity"
  - "LinkingJobTest.linkCap_highestScoreWins passes — a driving post with >10 candidates keeps only the top 10 by score"
  - "LinkingJobTest.lastLinkedAtAdvances_skipsDrivingPostOnNextRun passes — a post with last_linked_at set after its fetched_at is not in the driving set on the next run"
  - "LinkingJobTest.noEmbedding_semanticSkipped_entityStillWorks passes — a post without an embedding row produces entity-match links but no semantic links"
  - "LinkingJobIT.endToEndLinking passes — seeds READY posts with post_entity and post_embedding rows; after LinkingJob tick, post_reference rows exist and last_linked_at is set"
  - "GetReferencesToolTest.returnsLinkedPosts passes — seeds post_reference rows and verifies the tool returns them with post metadata"
  - "ClusterTraversalTest.connectedComponents passes — seeds a graph with two clusters and verifies the traversal returns two distinct component sets"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/linking/LinkingJobTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/linking/LinkingJobIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/GetReferencesToolTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/ClusterTraversalTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/architecture.md §Pipelines
  - docs/spec/schema.md §Invariants
decision_refs:
  - D6
  - D22
  - D33
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-093: post_reference DDL + LinkingJob + GetReferencesTool wiring

## Context

D6 commits to hybrid cross-source linking: named-entity match
(precision) plus cosine similarity over embeddings (recall). M1-092
delivers entity extraction; this ticket delivers the linking layer
that consumes those entities plus the existing embeddings.

Three artifacts currently exist as stubs waiting for data:
`GetReferencesTool` (returns empty JSON array), `ClusterTraversal`
(singleton clusters only), and the V7 comment referencing a future
`LinkingJob`. No `post_reference` DDL exists. This ticket creates the
table, implements `LinkingJob`, and wires the Provider-side tools to
real data.

`blocked_by: [M1-092]` — entity-match linking requires `post_entity`
rows to exist.

## Acceptance

**V27 migration.** Creates the `post_reference` table partitioned by
`created_at` per design notes §2.4.3. Bidirectional edges
(`from_post`, `to_post`) with `link_type ∈ {'entity','semantic','repost'}` and
a `score` column. Indexes on `(from_post, link_type)` and `(to_post)`.
Collector gets INSERT/SELECT; Provider gets SELECT.

**LinkingJob.** Scheduled Collector bean on a configurable interval.
Driving set: READY posts where `last_linked_at IS NULL OR
last_linked_at < fetched_at`, bounded to a lookback window (default
4 days). For each driving post:
- **Entity match:** posts sharing ≥1 `(entity_text, entity_type)`
  pair in `post_entity` → `link_type='entity'`, `score=count`.
- **Semantic match:** posts within a time window (default 48h) whose
  `cosine_distance < threshold` → `link_type='semantic'`,
  `score=cosine similarity`. Only posts with embeddings are eligible
  (`WHERE embedding IS NOT NULL`).
- Both link types written bidirectionally (A→B and B→A).
- Outbound links per post capped at 10 (highest score wins).
- `post.last_linked_at = now()` after processing.

**GetReferencesTool wiring.** Returns actual `post_reference` rows
with post metadata instead of the empty array.

**ClusterTraversal wiring.** Walks the bidirectional `post_reference`
graph to compute real connected components instead of returning
singleton clusters.

## Out-of-scope

- **EntityExtractorWorker** (M1-092) — frozen.
- **EmbeddingWorker / ReadyPromoter** — unchanged.
- **Nostr kind-6 cross-source linking** via `upstream_identifier` —
  M1-100. LinkingJob in this ticket processes only
  `entity` and `semantic` link types.
- **Partition lifecycle job updates** — the design notes commit to
  the pruner managing `post_reference` partitions; the pruner
  implementation is a separate concern (it may already handle the
  table if the partition-create pattern is generic, or may need a
  one-line addition — either way, it's mechanical and scoped to the
  pruner, not this ticket).
- **Pre-existing tests** — all pass unchanged.

## Notes

- **LinkingJob driving set efficiency** (`01-architecture.md`
  §1.3.5): new runs only process posts that arrived or were
  re-evaluated since the previous run (the `last_linked_at <
  fetched_at` filter). The candidate window for each driving post is
  the full lookback (so a fresh post can link backward to older READY
  posts), but the bidirectional INSERT ensures both endpoints are
  written without a second pass.
- **Cosine similarity query shape.** The `post_embedding` table has a
  pgvector index. The semantic-match query is a nearest-neighbor
  search within the time window, filtered by `cosine_distance <
  threshold`. The exact SQL shape (KNN operator `<=>` vs
  `cosine_distance()`) is an implementation choice; pgvector supports
  both.
- **GetReferencesTool current state.** Returns `"[]"` with a comment:
  "post_reference table is v2-deferred (no migration exists)". This
  ticket removes that stub and wires real queries.
- **ClusterTraversal current state.** Computes singleton clusters with
  a comment: "the real graph traversal lands when post_reference
  table is added". This ticket replaces the stub with a BFS/DFS
  walk over `post_reference` edges, depth-limited.
- **Cap enforcement shape.** The per-post link cap (N=10) can be
  enforced either in-query (`ORDER BY score DESC LIMIT 10` per
  driving post) or post-query (truncate in Java). In-query is
  preferred for large candidate sets.
- **Design reference:** `docs/design/01-architecture.md` §1.3.5
  (LinkingJob), `docs/design/02-schema.md` §2.4.3 (post_reference
  DDL), `docs/design/02-schema.md` §2.4.4 (partition lifecycle).
