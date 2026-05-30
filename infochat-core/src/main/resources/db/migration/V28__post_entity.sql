-- V28: §2.4.1 post_entity + post.entity_done pipeline cursor.
--
-- Lands the §2.4.1 post_entity partitioned table from
-- docs/design/02-schema.md and the per-stage cursor flag
-- post.entity_done that gates the EntityExtractorWorker pickup and the
-- ReadyPromoter promotion. Entity extraction is the Tier-2 named-entity
-- half of D6 cross-source linking; the LinkingJob that consumes these
-- rows lands in a follow-up ticket.
--
-- Entity extraction and embedding run in PARALLEL after the Tagger
-- (docs/spec/architecture.md §Pipelines): both gate on tagger_done and
-- neither gates the other. ReadyPromoter is the single synchronization
-- point that waits for BOTH entity_done and embedding_done.
--
-- Atomic Flyway migration: the ALTER + backfill + CREATE TABLE +
-- partition + index + GRANTs apply in one transaction so a partial
-- failure rolls back cleanly.

-- ---------------------------------------------------------------------
-- post.entity_done — the durable per-stage cursor (Invariant 5).
--
-- Backfill: any post that already passed the Tagger predates entity
-- extraction. Marking it entity_done=TRUE (with no post_entity rows)
-- mirrors the failure-release semantics — Tier-2 linking coverage is
-- degraded for these pre-V28 posts, but deterministic retrieval is
-- unaffected (docs/spec/llm.md §Failure handling). Re-running entity
-- extraction over the backlog is deliberately NOT done here.
-- ---------------------------------------------------------------------

ALTER TABLE post ADD COLUMN entity_done BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE post SET entity_done = TRUE WHERE tagger_done = TRUE;

-- ---------------------------------------------------------------------
-- 2.4.1 post_entity — partitioned by fetched_at (Invariant 6).
--
-- The post_id FK is intentionally omitted (same rationale as
-- post_embedding in V11): post is partitioned and Invariant 6 commits
-- to partition-drop TTL, so a cross-partition FK would block the
-- per-partition DROP. The fetched_at column duplicates post.fetched_at
-- so the partition key is local to this table (no cross-partition JOIN
-- needed for partition pruning on the 4-day TTL window).
--
-- PRIMARY KEY (post_id, entity_text, entity_type, fetched_at) — the
-- partition key (fetched_at) must participate in every unique
-- constraint on a partitioned table; the 4-tuple also collapses
-- duplicate (text, type) pairs extracted for the same post.
--
-- The entity_type CHECK enforces the controlled vocabulary at the DB
-- layer; the EntityExtractorWorker drops out-of-vocab types in Java
-- BEFORE INSERT so a single bad type never aborts a multi-row batch.
-- ---------------------------------------------------------------------

CREATE TABLE post_entity (
    post_id     UUID NOT NULL,
    entity_text TEXT NOT NULL,
    entity_type TEXT NOT NULL
        CHECK (entity_type IN ('cve', 'product', 'org', 'person', 'location', 'project')),
    fetched_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (post_id, entity_text, entity_type, fetched_at)
) PARTITION BY RANGE (fetched_at);

-- Bootstrap partition: covers May 2026 (the month the schema lands).
-- Same monthly cadence + naming convention (post_entity_YYYYMM) as
-- V11's post_embedding_202605 and V7's post_202605. The nightly
-- partition_pruner (docs/design/02-schema.md §2.4.4) creates the next
-- partition before it is needed; this migration creates ONE bootstrap
-- partition so the table is queryable on day one.
CREATE TABLE post_entity_202605 PARTITION OF post_entity
    FOR VALUES FROM ('2026-05-01 00:00:00+00') TO ('2026-06-01 00:00:00+00');

-- Tier-2 entity-match lookup index per docs/design/02-schema.md §2.4.1.
-- Declared on the parent so it propagates to every partition.
CREATE INDEX idx_post_entity_text ON post_entity(entity_text, entity_type);

-- ---------------------------------------------------------------------
-- Per-table GRANTs (aligned with docs/spec/security.md §DB roles).
--
-- post_entity is Collector-write: the EntityExtractorWorker INSERTs one
-- row per extracted entity. Provider reads post_entity for the Tier-2
-- cross-source linking surface (M1-093 LinkingJob / GetReferencesTool).
-- NEITHER role holds DELETE — Invariant 6 commits TTL to partition
-- drop, not row delete; partition drop is operator-only.
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT ON post_entity TO infochat_collector;
GRANT SELECT         ON post_entity TO infochat_provider;
REVOKE DELETE ON post_entity FROM infochat_collector;
REVOKE DELETE ON post_entity FROM infochat_provider;
REVOKE DELETE ON post_entity FROM PUBLIC;
