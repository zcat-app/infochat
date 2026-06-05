-- V34: kind-6 repost edge resolution — store the original event's
-- upstream_identifier on the edge instead of a derived UUID.
--
-- architecture.md §Ingest SPIs commits the repost link to the shape
-- "(kind-6 post UID) →repost→ (original upstream_identifier)", resolved
-- to a post UID if and when the original event is also seen and stored,
-- and mandates "Implementations MUST NOT use the derived UID as the
-- join key". The V29-era Kind6Handler stored to_post as a deterministic
-- UUID-v3 of the original event id — a value that can never match a
-- persisted post.id (gen_random_uuid()), leaving every repost edge
-- structurally unresolvable. This migration makes the spec shape
-- representable:
--
--   to_upstream_identifier — the original event id, verbatim (set only
--                            for link_type='repost' edges).
--   to_post                — now nullable; NULL means "repost edge not
--                            yet resolved". Entity/semantic edges keep
--                            to_post always set (the LinkingJob only
--                            writes resolved endpoints).
--
-- Atomic Flyway migration: all statements apply in one transaction so
-- a partial failure rolls back cleanly (same shape as V29).

ALTER TABLE post_reference ADD COLUMN to_upstream_identifier TEXT;

-- The V29 PRIMARY KEY (from_post, to_post, link_type, created_at)
-- cannot survive a nullable to_post (PK columns are implicitly NOT
-- NULL, and DROP NOT NULL is refused while the column is part of the
-- PK), so the PK is replaced by a unique index over the same column
-- set. LinkingJob's INSERT has no ON CONFLICT clause, so nothing
-- depends on the constraint as an arbiter — the swap only changes how
-- uniqueness is enforced, not who relies on it. Parent-declared, so it
-- propagates to the existing partitions and to future pruner-created
-- ones; created_at (the partition key) must participate in every
-- unique index on a partitioned table, exactly as it did in the PK.
ALTER TABLE post_reference DROP CONSTRAINT post_reference_pkey;
ALTER TABLE post_reference ALTER COLUMN to_post DROP NOT NULL;
CREATE UNIQUE INDEX idx_post_ref_unique_edge
    ON post_reference(from_post, to_post, link_type, created_at);

-- Resolver lookup: "which unresolved repost edges point at this
-- newly-persisted original?" Partial — resolved edges leave the index,
-- so it stays small no matter how the edge table grows.
CREATE INDEX idx_post_ref_unresolved_target
    ON post_reference(to_upstream_identifier) WHERE to_post IS NULL;

-- Original-already-present lookup: "is the event this kind-6 reposts
-- already stored as a post?" V7's only upstream_identifier coverage is
-- UNIQUE (source_id, upstream_identifier, fetched_at), unusable without
-- the leading source_id.
CREATE INDEX idx_post_upstream_identifier ON post(upstream_identifier);

-- Resolution flips to_post from NULL to the original's post.id — the
-- one UPDATE the collector performs on this table. DELETE stays revoked
-- per V29 (Invariant 6: TTL is partition drop, not row delete).
GRANT UPDATE ON post_reference TO infochat_collector;
