-- V29: §2.4.3 post_reference — D6 cross-source linking edge table.
--
-- Lands the §2.4.3 post_reference partitioned table from
-- docs/design/02-schema.md. Sibling to V28's post_entity: V28 is the
-- named-entity half of D6, V29 is the link-graph the LinkingJob
-- (this ticket's collector/linking/LinkingJob) writes after consuming
-- both the post_entity rows and the V11 post_embedding rows.
--
-- link_type 'entity'   — Tier-2 named-entity match (post_entity).
-- link_type 'semantic' — pgvector cosine-similarity match (post_embedding).
-- link_type 'repost'   — Nostr kind-6 cross-source linking; the value is
--                        included in the CHECK constraint here so the
--                        M1-100 LinkingJob extension does not require a
--                        schema amendment (M1-100 frontmatter:
--                        migration_touch: false).
--
-- Bidirectional edges: LinkingJob writes both (A → B) and (B → A) rows
-- so a single-direction scan from either endpoint surfaces the link.
--
-- Atomic Flyway migration: the CREATE TABLE + partition + indexes +
-- GRANTs apply in one transaction so a partial failure rolls back
-- cleanly.

-- ---------------------------------------------------------------------
-- 2.4.3 post_reference — partitioned by created_at (Invariant 6).
--
-- The from_post / to_post columns are intentionally NOT FKs into post:
-- post is partitioned and Invariant 6 commits to partition-drop TTL,
-- so a cross-partition FK would block the per-partition DROP (same
-- rationale as V11 post_embedding and V28 post_entity). The link-graph
-- is its own partition lifecycle; a dropped post partition leaves
-- orphan post_reference rows that the pruner ages out independently.
--
-- created_at is the partition key (not post.fetched_at) — the link is
-- a Collector-tier event whose lifecycle is independent of either
-- endpoint's lifetime; the LinkingJob may relink an old post on a
-- later tick, producing a new edge in a later partition.
--
-- PRIMARY KEY (from_post, to_post, link_type, created_at): the
-- partition key (created_at) must participate in every unique
-- constraint on a partitioned table. Including link_type lets the
-- same (from, to) pair carry both an entity and a semantic edge.
-- Including created_at allows re-linking on a later tick (the
-- LinkingJob's per-direction dedup guard within the lookback window
-- prevents duplicate logical edges on the normal path).
--
-- score REAL holds either the count of shared entities (link_type
-- 'entity', integer-valued stored as REAL) or the cosine similarity
-- (link_type 'semantic', 0..1 float). The unit is per-link_type;
-- callers do not compare scores across link types.
-- ---------------------------------------------------------------------

CREATE TABLE post_reference (
    from_post  UUID NOT NULL,
    to_post    UUID NOT NULL,
    link_type  TEXT NOT NULL
        CHECK (link_type IN ('entity', 'semantic', 'repost')),
    score      REAL NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (from_post, to_post, link_type, created_at)
) PARTITION BY RANGE (created_at);

-- Bootstrap partition: covers May 2026 (the month the schema lands).
-- Same monthly cadence + naming convention (post_reference_YYYYMM) as
-- V28's post_entity_202605 and V11's post_embedding_202605. The
-- nightly partition_pruner (docs/design/02-schema.md §2.4.4) creates
-- the next partition before it is needed; this migration creates ONE
-- bootstrap partition so the table is insertable on day one.
CREATE TABLE post_reference_202605 PARTITION OF post_reference
    FOR VALUES FROM ('2026-05-01 00:00:00+00') TO ('2026-06-01 00:00:00+00');

-- Outbound-edge scan: the LinkingJob bidirectional INSERT + the
-- GetReferencesTool "edges out of this post" query both predicate on
-- (from_post, link_type). Declared on the parent so it propagates to
-- every partition.
CREATE INDEX idx_post_ref_from ON post_reference(from_post, link_type);

-- Reverse-edge scan: ClusterTraversal walks both directions of the
-- graph; lookup by to_post is the second leg. Same parent-declared
-- pattern as idx_post_ref_from.
CREATE INDEX idx_post_ref_to ON post_reference(to_post);

-- ---------------------------------------------------------------------
-- Per-table GRANTs (aligned with docs/spec/security.md §DB roles).
--
-- post_reference is Collector-write: the LinkingJob INSERTs the
-- bidirectional edges after consuming post_entity and post_embedding.
-- Provider reads post_reference for the Tier-2 cross-source surfaces
-- (GetReferencesTool, ClusterTraversal). NEITHER role holds DELETE —
-- Invariant 6 commits TTL to partition drop, not row delete;
-- partition drop is operator-only.
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT ON post_reference TO infochat_collector;
GRANT SELECT         ON post_reference TO infochat_provider;
REVOKE DELETE ON post_reference FROM infochat_collector;
REVOKE DELETE ON post_reference FROM infochat_provider;
REVOKE DELETE ON post_reference FROM PUBLIC;
