-- V58: post.search_tsv generated tsvector + GIN index (M1-617, D58).
--
-- Lands the storage for the lexical arm of hybrid semantic/lexical chat
-- retrieval (docs/design/05-llm-and-embeddings.md §5.4.6): a STORED
-- generated tsvector over title + body, and a GIN index declared on the
-- partitioned parent so it cascades to existing partitions and
-- auto-applies to future ones created by the partition scheduler.
--
-- The 2-arg to_tsvector('english', ...) form is REQUIRED here: the 1-arg
-- form reads default_text_search_config at call time (only STABLE), which
-- Postgres rejects in a generated column; pinning the regconfig also keeps
-- the stored vector — and therefore the fused retrieval set (D19) —
-- independent of any session/database GUC. body is nullable (V7), so it
-- is COALESCEd; title is NOT NULL but gets the same treatment so the
-- expression shape stays uniform with the query side.
--
-- Cost note: adding a STORED generated column rewrites every post
-- partition, and the GIN build scans the whole corpus — a one-time
-- migration cost, acceptable at v1 scale.
--
-- Grants: no new GRANT is possible or needed for a column on an existing
-- table — V7's table-level `GRANT SELECT ON post TO infochat_provider`
-- already covers search_tsv, keeping the provider read-only over the
-- lexical store exactly as V11 keeps it read-only over the embedding
-- store (collector-write / provider-read).

ALTER TABLE post ADD COLUMN search_tsv tsvector
    GENERATED ALWAYS AS (
        to_tsvector('english',
                    coalesce(title, '') || ' ' || coalesce(body, ''))
    ) STORED;

CREATE INDEX idx_post_search_tsv ON post USING GIN (search_tsv);
