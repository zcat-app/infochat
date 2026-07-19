-- V60: doc_embedding — the second embedded corpus (M1-664).
--
-- Lands the provider-owned docs-embedding table from docs/spec/llm.md
-- §Embedding pipeline ("second embedded corpus"). The first corpus,
-- post_embedding (V11), is Collector-written, partitioned by fetched_at
-- for TTL-by-partition-drop, and grant-locked to provider SELECT only.
-- This table is the structural opposite on every one of those axes: it
-- is Provider-written, NOT partitioned (the docs corpus has no TTL — a
-- command-intent document is correct until the command itself changes),
-- and grant-opened to provider INSERT + DELETE so the
-- {@code CommandIntentIndexBuilder} can run its DELETE-then-INSERT
-- upsert at startup.
--
-- The dimension is **768** — the single app-wide dimension pinned by
-- {@code embedding_metadata}'s singleton seed ({@code 'nomic-embed-text', 768},
-- V11 line ~145) and by {@code infochat.embeddings.dimension=768} at
-- {@code infochat-collector/src/main/resources/application.properties:548}.
-- D54 permanently supersedes the per-profile dimension table in
-- {@code docs/design/05-llm-and-embeddings.md}. Two corpora MUST share
-- one model + dimension or {@code EmbeddingMetadataStartupGuard} refuses
-- startup; this migration must not parameterize the dimension.
--
-- HNSW vector-cosine index, identical operator class to V11's
-- {@code idx_post_embedding_hnsw}. NOT partitioned, so the index lives
-- directly on the table (no parent-partition propagation).
--
-- Atomic Flyway migration: the CREATE TABLE + index + grants apply in
-- one transaction so a partial failure rolls back cleanly.

-- ---------------------------------------------------------------------
-- doc_embedding — non-partitioned, provider-written docs corpus.
--
-- doc_id         Stable identifier within (doc_kind, target_ref). For
--                command intents: the catalogue command name. PRIMARY
--                KEY because every upsert keys off it and the corpus
--                is small (~41 rows), so a synthetic id buys nothing.
-- doc_kind       Corpus discriminator. v1 ships exactly one value,
--                'command_intent'; future corpora (USER_GUIDE topics
--                per M1-649) layer on as additional doc_kind values
--                without altering this schema. Filtered INSIDE every
--                lookup WHERE so two corpora cannot cross-match.
-- target_ref     The runtime artefact the matched document points at.
--                For command intents: the catalogue command name
--                (identical to doc_id in v1, but kept separate so a
--                future corpus can have a doc_id that differs from its
--                target_ref — e.g. a USER_GUIDE topic whose target_ref
--                is a section anchor). The HelpLookupTool binds
--                target_ref = ANY(?) with the caller's visible
--                command-name set so the tier filter runs INSIDE the
--                query, before the result leaves SQL
--                (docs/spec/security.md §Prompt-injection defenses,
--                tier-filter-before-return).
-- content_hash   SHA-256 of the source text the embedding was
--                generated from. The startup builder skips rows whose
--                content_hash + embedding_model both match, so a
--                restart with an unchanged corpus performs zero
--                embedding calls (M1-664 acceptance item 2). A change
--                to the source text or to the active embedding model
--                forces a re-embed (M1-664 acceptance item 3) — a
--                stale vector can never outlive its source text.
-- embedding      vector(768). See the dimension note above.
-- embedding_model  The model identifier the row was embedded under
--                  (e.g. 'nomic-embed-text'); matched against the
--                  configured model on every startup to detect a
--                  mid-deployment model switch that needs a re-embed.
-- ---------------------------------------------------------------------

CREATE TABLE doc_embedding (
    doc_id          TEXT NOT NULL,
    doc_kind        TEXT NOT NULL,
    target_ref      TEXT NOT NULL,
    content_hash    TEXT NOT NULL,
    embedding       vector(768) NOT NULL,
    embedding_model TEXT NOT NULL,
    PRIMARY KEY (doc_id)
);

-- HNSW cosine index, same operator class + tuning as V11's
-- idx_post_embedding_hnsw. The intent corpus is small (~41 rows) so
-- the initial build is O(1); operators switching embedding profiles
-- pay the rebuild on the ALTER-TABLE path (see V11's index build cost
-- note for the same hazard on post_embedding).
CREATE INDEX idx_doc_embedding_hnsw ON doc_embedding
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- Lookup support: the startup builder's "which rows need re-embedding"
-- scan filters by doc_kind, and the chat tool's lookup filters by
-- doc_kind + target_ref set. A plain btree on each is cheap on a
-- 41-row table and keeps the O(log n) probe off the HNSW index (which
-- serves the vector arm only).
CREATE INDEX idx_doc_embedding_kind ON doc_embedding (doc_kind);
CREATE INDEX idx_doc_embedding_target ON doc_embedding (doc_kind, target_ref);

-- ---------------------------------------------------------------------
-- Per-table GRANTs (aligned with docs/spec/security.md §DB roles).
--
-- doc_embedding is Provider-owned: the {@code CommandIntentIndexBuilder}
-- runs in the Provider at startup and is the sole writer; the
-- {@code HelpLookupTool} runs in the Provider per chat turn and is the
-- sole reader. The DELETE-then-INSERT upsert shape (in one transaction,
-- UPDATE withheld) is what the narrow INSERT + DELETE grant permits:
-- the Provider never needs UPDATE on a docs corpus (every change is a
-- full re-embed of the affected row), and withholding UPDATE keeps the
-- grant surface minimal. NEITHER role holds UPDATE; REVOKE is belt-and-
-- suspenders against a role-inheritance surprise.
--
-- The Collector is granted NOTHING on doc_embedding. The docs corpus is
-- Provider-only; the Collector's post_embedding grants (V11) are
-- unchanged.
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, DELETE ON doc_embedding TO infochat_provider;
REVOKE UPDATE ON doc_embedding FROM infochat_provider;
REVOKE UPDATE ON doc_embedding FROM PUBLIC;
