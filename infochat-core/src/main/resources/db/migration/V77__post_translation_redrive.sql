-- V77: ingest-translation re-drive bookkeeping (M1-760).
--
-- IngestTranslationWorker gives a post two attempts and then releases it
-- with translation_done = TRUE and title_en/body_en NULL. That release is
-- correct — a translation failure must never strand a post outside
-- retrieval — but it is permanent: a translator route that was down for an
-- hour leaves those posts anchorless forever. These two columns carry the
-- bounded, slow ladder that tries again.
--
-- Why a DURABLE STAMP rather than a predicate over the existing columns.
-- Three states are byte-identical on disk after the release —
-- attempts-exhausted (releaseNull), model-refused (releaseRefused), and
-- never-attempted (a pre-V74 row on a source later switched to non-'en') —
-- and only the first may be re-driven. A derived predicate
-- (source.language <> 'en' AND title_en IS NULL AND translation_done)
-- cannot separate them, so it would re-feed action-request content to the
-- model on a schedule, contradicting the standing decision that a
-- structured refusal is never retried. The stamp is written by the
-- releasing path itself, so membership records WHY the post is anchorless
-- instead of guessing it from the wreckage.
--
-- next_translation_redrive_at  When the post is next due for a re-drive.
--                              NULL means "not in the re-drive set" — the
--                              state of every row that was never exhausted
--                              AND of every post whose ladder has run out
--                              (terminal) or been satisfied. Nullable with
--                              no default, so every pre-V77 row is
--                              excluded: there is NO backfill, deliberately,
--                              because rows already sitting in the
--                              exhausted state are indistinguishable from
--                              the refusal and never-attempted rows above.
-- translation_redrive_attempts Re-drives spent so far. The configured cap
--                              (infochat.llm.translator.redrive.cap) reads
--                              this, so a post is re-driven at most K times
--                              and is then left alone permanently. DEFAULT 0
--                              is the correct reading for every pre-V77 row
--                              precisely because none of them carry a stamp.
--
-- post is PARTITION BY RANGE (fetched_at); ALTER TABLE on the parent
-- propagates the columns to every child partition (the V21 re_eval_attempts
-- / V52 last_reeval_at / V66 tagger_sweep_attempts mechanism). Both are PG
-- fast defaults (attmissingval) — no table rewrite. No new GRANTs: the
-- columns ride post's existing table-level grants, under which the
-- collector already holds UPDATE (V7__joins_post.sql:222).

ALTER TABLE post ADD COLUMN next_translation_redrive_at  TIMESTAMPTZ;
ALTER TABLE post ADD COLUMN translation_redrive_attempts INT NOT NULL DEFAULT 0;

-- Partial index on the stamp, mirroring idx_post_link_cursor's shape: the
-- re-drive set is a vanishingly small fraction of post, so the index holds
-- only the stamped rows and the ticker's dueness scan never touches the
-- corpus. Declared on the parent; Postgres fans it out to every partition.
CREATE INDEX idx_post_translation_redrive ON post(next_translation_redrive_at)
    WHERE next_translation_redrive_at IS NOT NULL;
