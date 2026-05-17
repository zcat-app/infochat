-- V11: §2.4.2 post_embedding + §Embedding pipeline embedding_metadata.
--
-- Lands the §2.4.2 post_embedding partitioned table from
-- docs/design/02-schema.md and the singleton embedding_metadata table
-- from docs/spec/llm.md §Embedding pipeline ("Model identity guard.
-- The active embedding model's identifier and vector dimensionality
-- are stored in a singleton metadata row on first use."). V11 ships
-- the laptop/vps/remote-llm 768-d shape and seeds embedding_metadata
-- with the laptop/vps default (`nomic-embed-text`, 768) per
-- docs/design/05-llm-and-embeddings.md §5.5 / §5.7.
--
-- M1-034a creates the schema and seeds the metadata row but writes
-- NO rows into post_embedding — the EmbeddingWorker landing in
-- M1-034b is the sole writer; M1-034a's contract ends at
-- post.tagger_done=true. Shipping the schema here lets M1-034b's
-- EmbeddingMetadataStartupGuard read the seeded row on first boot.
--
-- Atomic Flyway migration: the CREATE TABLE + partition + index +
-- INSERT + GRANTs apply in one transaction so a partial failure
-- rolls back cleanly.
--
-- Operator overrides:
--   * Vector dimension. V11 ships vector(768) for laptop / vps /
--     remote-llm. The pi profile (vector(384), all-minilm) and
--     future remote-llm overrides (vector(1536), text-embedding-3-small)
--     are operator-selected via an alternative migration file or an
--     operator-issued ALTER TABLE at deploy time per
--     docs/design/02-schema.md §2.8 (`scripts/reembed.sh` automates
--     the column-add + re-embed loop; the script itself is T2
--     territory).
--   * Index type. V11 ships HNSW (`m=16, ef_construction=64`) per
--     docs/design/02-schema.md §2.4.2 for laptop / vps / remote-llm.
--     The pi profile's IVFFlat variant (`WITH (lists = 100)`) is the
--     same operator choice — switch by dropping the HNSW index and
--     creating the IVFFlat index in a follow-up migration.
--   * Index build cost. Empty partition at migration time means the
--     initial HNSW build is O(1). Operators switching profiles at a
--     later date should expect a non-trivial rebuild cost on the
--     ALTER-TABLE path.

-- ---------------------------------------------------------------------
-- 2.4.2 post_embedding — partitioned by fetched_at (Invariant 6)
--
-- The post_id FK is intentionally omitted: post is partitioned and
-- Invariant 6 commits to partition-drop TTL, so a cross-partition FK
-- would block the per-partition DROP. Application-tier writes happen
-- in the same transaction that wrote the post row, so referential
-- integrity is the writer's responsibility (the EmbeddingWorker in
-- M1-034b enforces it). The `fetched_at` column duplicates
-- `post.fetched_at` so the partition key is local to this table
-- (matching the §2.4.1 post_entity pattern).
--
-- PRIMARY KEY (post_id, fetched_at) — Postgres requires the partition
-- key (fetched_at) to participate in every unique constraint on a
-- partitioned table; per-post dedup happens via the (post_id,
-- fetched_at) pair.
--
-- embedding_model carries the model identifier the row was generated
-- under (e.g. 'nomic-embed-text:v1') so a profile switch can locate
-- rows that need re-embedding per §2.8.
-- ---------------------------------------------------------------------

CREATE TABLE post_embedding (
    post_id         UUID NOT NULL,
    embedding       vector(768) NOT NULL,
    embedding_model TEXT NOT NULL,
    fetched_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (post_id, fetched_at)
) PARTITION BY RANGE (fetched_at);

-- Bootstrap partition: covers May 2026 (the month the schema lands).
-- Same monthly cadence + naming convention (post_embedding_YYYYMM) as
-- V7's post_202605 bootstrap partition. The application-tier partition
-- scheduler that creates the next partition before it is needed lands
-- in T2 (partition_pruner job); this migration creates ONE bootstrap
-- partition so the schema is queryable on day one.
CREATE TABLE post_embedding_202605 PARTITION OF post_embedding
    FOR VALUES FROM ('2026-05-01 00:00:00+00') TO ('2026-06-01 00:00:00+00');

-- HNSW vector-cosine index per docs/design/02-schema.md §2.4.2
-- (laptop / vps / remote-llm tuning). Declared on the parent so it
-- propagates to every partition automatically.
CREATE INDEX idx_post_embedding_hnsw ON post_embedding
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- ---------------------------------------------------------------------
-- embedding_metadata — singleton model-identity guard.
--
-- docs/spec/llm.md §Embedding pipeline: "Model identity guard. The
-- active embedding model's identifier and vector dimensionality are
-- stored in a singleton metadata row on first use. On every
-- subsequent Collector boot, the configured embedding-provider model
-- and dimension are compared against this row. Mismatch refuses
-- startup unless an explicit operator override flag is set."
--
-- Singleton enforcement uses CREATE UNIQUE INDEX ON
-- embedding_metadata ((TRUE)) — the predicate evaluates to a
-- constant per row so only one row can satisfy uniqueness. Simpler
-- than a synthetic id INT DEFAULT 1 + UNIQUE(id) + CHECK(id=1)
-- column (the M1-034a clarity SELF-CONTAINED-CHECK note that
-- documented this choice).
--
-- updated_at moves forward on every operator UPDATE so an operator-
-- approved model change is visible in the audit trail; the row is
-- never DELETEd (the seed below stays the sole row for the lifetime
-- of the deployment unless explicitly UPDATEd by the M1-034b
-- startup guard's operator-override path).
-- ---------------------------------------------------------------------

CREATE TABLE embedding_metadata (
    model_identifier TEXT NOT NULL,
    dimension        INT NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_embedding_metadata_singleton
    ON embedding_metadata ((TRUE));

-- Seed row matching the laptop / vps default model+dimension per
-- docs/design/05-llm-and-embeddings.md §5.5 / §5.7. M1-034b's startup
-- guard reads this row and compares to the configured
-- infochat.embeddings.model + .dimension; mismatch fatal-fails
-- startup unless infochat.embeddings.allow-model-change=true.
INSERT INTO embedding_metadata (model_identifier, dimension)
    VALUES ('nomic-embed-text', 768);

-- ---------------------------------------------------------------------
-- Per-table GRANTs (aligned with docs/spec/security.md §DB roles).
--
-- post_embedding is Collector-write: the EmbeddingWorker in M1-034b
-- INSERTs one row per embedded post. Provider reads post_embedding
-- for the T1-F /summary semantic-similarity queries and the T2-D
-- chat-agent retrieval. NEITHER role holds DELETE on post_embedding
-- — Invariant 6 commits TTL to partition drop, not row delete;
-- partition drop is operator-only (infochat_admin) and lives in the
-- partition_pruner job (T2).
--
-- embedding_metadata is Collector-write (SELECT + INSERT + UPDATE):
-- the M1-034b startup guard reads the seed row on every boot and
-- may UPDATE it on the operator-override path. Provider reads
-- embedding_metadata for diagnostic surfaces (future /status or
-- /admin-status command output).
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT ON post_embedding TO infochat_collector;
GRANT SELECT         ON post_embedding TO infochat_provider;
REVOKE DELETE ON post_embedding FROM infochat_collector;
REVOKE DELETE ON post_embedding FROM infochat_provider;
REVOKE DELETE ON post_embedding FROM PUBLIC;

GRANT SELECT, INSERT, UPDATE ON embedding_metadata TO infochat_collector;
GRANT SELECT                 ON embedding_metadata TO infochat_provider;
