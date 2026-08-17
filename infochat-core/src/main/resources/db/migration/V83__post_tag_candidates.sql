-- V83: post.tag_candidates — Tier-2 candidate array (M1-868, decision 1).

-- The Tagger resolution's losing leaves (football+europe, esports+gaming,
-- ai+cybersecurity+world — constant cross-top proposals the showcase
-- measured) are stored at zero extra LLM cost — the losers already exist.

ALTER TABLE post ADD COLUMN tag_candidates TEXT[] NOT NULL DEFAULT '{}';

-- Tier-2-class (D5): never rendered, never digest-counted, never
-- /follow-tag-addressable, never a searchPosts filter. No GIN index: no
-- query reads the column (§7 — no machinery ahead of need).

-- M1-866's entity-continuity migration is the sole historical writer.

-- post is PARTITION BY RANGE (fetched_at); ALTER TABLE on the parent
-- propagates to every child partition (V66 precedent). No new GRANTs: the
-- column rides post's existing per-role grants (V7). Atomic Flyway migration.
