-- V71: post.summary_done pipeline cursor (M1-715).
--
-- Lands the per-stage cursor flag post.summary_done that gates the new
-- BodySummaryWorker pickup and joins the EmbeddingWorker and
-- ReadyPromoter gates (docs/design/05-llm-and-embeddings.md §5.5
-- Input-text decision, docs/design/02-schema.md §2.3.1). The worker
-- writes post.body_summary — the V7 column nothing has ever written —
-- as an LLM abstract for posts whose body exceeds
-- infochat.summarizer.threshold-chars; EmbeddingWorker already prefers
-- body_summary as embedding input when present.
--
-- The summarizer runs AFTER the Tagger and BEFORE Embedding/Promotion
-- (docs/spec/architecture.md §Pipelines): it gates on tagger_done, and
-- embedding/promotion gate on (summary_done OR length(body) <=
-- threshold), so under-threshold posts never reach the LLM and never
-- wait.
--
-- Atomic Flyway migration: the ADD COLUMN and the backfill apply in one
-- transaction so a partial failure rolls back cleanly. No GRANT change —
-- post is already collector-write / provider-read (V7).

-- ---------------------------------------------------------------------
-- post.summary_done — the durable per-stage cursor (Invariant 5).
--
-- Backfill: any post that already passed the Tagger predates the
-- summarizer. Marking it summary_done=TRUE (body_summary stays NULL, no
-- LLM re-run) mirrors V28's entity and V57's classification backfill:
-- the prefix-embedded corpus is never re-summarized (roll-forward
-- decision, M1-715 — re-embedding is explicitly out of scope), and
-- in-flight RAW posts do not wedge on the new gates. Posts that have
-- not yet passed the Tagger stay FALSE and flow through the summarizer
-- when they arrive — they carry no vector yet, so summarizing them IS
-- the steady-state behavior, not a re-embed.
-- ---------------------------------------------------------------------

ALTER TABLE post ADD COLUMN summary_done BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE post SET summary_done = TRUE WHERE tagger_done = TRUE;
