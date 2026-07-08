-- V57: post.classification + post.classifier_done pipeline cursor (M1-597).
--
-- Lands the per-post classification label set (docs/design/05-llm-and-embeddings.md
-- §5.4 Classifier, docs/design/02-schema.md §2.3.1) and the per-stage
-- cursor flag post.classifier_done that gates the ClassifierWorker pickup
-- and the ReadyPromoter promotion. Classification is a FIXED closed enum
-- {factual, opinion, technical, urgent, ongoing, unknown} shown in
-- /summary (M1-598 lands the render side) — computed once at ingest and
-- stored, so /summary and /retry stay byte-identical on replay (D19/D36).
--
-- The classifier runs in PARALLEL after the Tagger, alongside the Entity
-- extractor and the Embedding stage (docs/spec/architecture.md §Pipelines):
-- it gates on tagger_done and gates none of the others; ReadyPromoter is
-- the single synchronization point that now also waits for classifier_done.
--
-- Atomic Flyway migration: the two ADD COLUMNs, the two CHECK constraints,
-- and the backfill apply in one transaction so a partial failure rolls
-- back cleanly. No GRANT change — post is already collector-write /
-- provider-read (V7).

-- ---------------------------------------------------------------------
-- post.classification — the closed-enum label set (NOT NULL, never empty).
--
-- DEFAULT ARRAY['unknown'] means existing rows and any un-run post are
-- non-null and carry the first-class "unknown" label (no null window).
-- ---------------------------------------------------------------------

ALTER TABLE post ADD COLUMN classification TEXT[] NOT NULL DEFAULT ARRAY['unknown']::TEXT[];

-- post.classifier_done — the durable per-stage cursor (Invariant 5).
ALTER TABLE post ADD COLUMN classifier_done BOOLEAN NOT NULL DEFAULT FALSE;

-- Closed-vocabulary CHECK — the same six values the ClassifierWorker filters
-- against in Java before the write, so one out-of-enum label never aborts a
-- write against this constraint (mirrors post_entity.entity_type, V28). The
-- <@ (contained-by) operator asserts every element is in the closed set.
ALTER TABLE post ADD CONSTRAINT post_classification_closed_set
    CHECK (classification <@ ARRAY['factual','opinion','technical','urgent','ongoing','unknown']::TEXT[]);

-- Non-empty CHECK. cardinality() (NOT array_length(x,1)) is deliberate:
-- array_length(ARRAY[]::text[], 1) returns NULL, and a CHECK treats NULL as
-- satisfied, so array_length >= 1 would silently ALLOW an empty array;
-- cardinality(ARRAY[]::text[]) returns 0, so cardinality >= 1 rejects it.
ALTER TABLE post ADD CONSTRAINT post_classification_non_empty
    CHECK (cardinality(classification) >= 1);

-- ---------------------------------------------------------------------
-- Backfill: any post that already passed the Tagger predates
-- classification. Marking it classifier_done=TRUE (classification stays
-- the {unknown} DEFAULT, no LLM re-run) mirrors V28's entity backfill and
-- keeps in-flight RAW posts from wedging on the new ReadyPromoter gate.
-- The ~4-day fetched_at partition TTL (Invariant 6) ages the {unknown}
-- backlog out within days, so a one-off re-classification job is not done.
-- ---------------------------------------------------------------------

UPDATE post SET classifier_done = TRUE WHERE tagger_done = TRUE;
