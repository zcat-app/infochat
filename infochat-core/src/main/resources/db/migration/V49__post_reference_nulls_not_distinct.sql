-- V49: reject duplicate unresolved repost edges.
--
-- V34's idx_post_ref_unique_edge replaced the V29 PRIMARY KEY with a
-- unique index over the same columns (from_post, to_post, link_type,
-- created_at) to let to_post go NULL for not-yet-resolved repost
-- edges. Unique indexes default to NULLS DISTINCT, under which two
-- NULL to_post values never compare equal — so two identical
-- unresolved edges (same from_post, same link_type, same created_at)
-- are both admitted, where the same rows with a resolved to_post
-- would be rejected. Rebuild with NULLS NOT DISTINCT (PostgreSQL 15+;
-- the project runs pgvector/pgvector:pg16) so NULL to_post values
-- compare equal and duplicate unresolved edges are rejected exactly
-- like resolved ones. Non-NULL behavior is unchanged.
--
-- Parent-declared, like V34: the DROP and CREATE cascade to existing
-- partitions and propagate to future pruner-created ones. created_at
-- (the partition key) must keep participating in the index. Atomic
-- Flyway migration: both statements apply in one transaction.

DROP INDEX idx_post_ref_unique_edge;
CREATE UNIQUE INDEX idx_post_ref_unique_edge
    ON post_reference(from_post, to_post, link_type, created_at)
    NULLS NOT DISTINCT;
