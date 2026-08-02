-- V74: English corpus anchor — derived English fields, declared source
-- language, and the translation pipeline cursor (M1-749, D29).
--
-- D29 (amended) makes English the corpus anchor: a non-English source post
-- is translated to English ONCE at ingest into derived fields, the original
-- title/body are retained byte-identical (they stay what the user is
-- shown), and both retrieval arms run against the English field. This
-- migration lands the storage for that decision:
--
--   post.title_en / post.body_en  — derived English text, NULL until the
--     IngestTranslationWorker has run. Nullable is deliberate: the outbox
--     pattern requires "persisted but not yet translated" to be
--     representable, and the coalesce fallbacks below keep that state
--     searchable and embeddable from the original text.
--
--   source.language  — the DECLARED language of every post from that
--     source (never inferred over the body). NOT NULL DEFAULT 'en': the
--     entire current corpus is English, so existing rows backfill to 'en'
--     with no rewrite (PG fast default via attmissingval). The write path
--     (/add-source --lang, bootstrap entry) is M1-750; nothing here writes
--     the column beyond the DEFAULT.
--
--   post.translation_done  — the durable cursor that orders the pipeline:
--     EmbeddingWorker's pickup gains AND translation_done = TRUE and the
--     translator is the only writer that flips it. The two-step default
--     dance is load-bearing: ADD ... NOT NULL DEFAULT TRUE makes every
--     pre-V74 row read TRUE (they are already English and mid-pipeline
--     rows must finish embedding from original text — no upgrade-time
--     stall), then ALTER ... SET DEFAULT FALSE gates every post-V74
--     insert. Both steps use the PG fast default (attmissingval); neither
--     rewrites the table. Do NOT collapse this into one statement.
--
-- search_tsv replacement: the generated column now reads the English
-- field with the original as fallback, regconfig still hard-pinned to
-- 'english' (per-language regconfig is the rejected alternative — ONE
-- configuration is the point of the anchor). Every existing row has
-- title_en/body_en NULL, so coalesce(x_en, x) reads exactly the text V58
-- read — the change is a no-op for the current corpus. DROP COLUMN takes
-- idx_post_search_tsv with it (cascade, parent and per-partition), so the
-- index is re-CREATEd explicitly on the parent; dropping without
-- recreating turns every lexical query into a sequential scan with no
-- error anywhere. The re-ADD rewrites every partition — same one-time
-- cost profile as V58.
--
-- Grants: none new — the collector role holds table-level
-- SELECT/INSERT/UPDATE on post (V7) and source (V6), which covers the new
-- columns on both tables.

ALTER TABLE post ADD COLUMN title_en TEXT;
ALTER TABLE post ADD COLUMN body_en TEXT;

ALTER TABLE source ADD COLUMN language TEXT NOT NULL DEFAULT 'en';

ALTER TABLE post ADD COLUMN translation_done BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE post ALTER COLUMN translation_done SET DEFAULT FALSE;

ALTER TABLE post DROP COLUMN search_tsv;
ALTER TABLE post ADD COLUMN search_tsv tsvector
    GENERATED ALWAYS AS (
        to_tsvector('english',
                    coalesce(title_en, title, '') || ' '
                    || coalesce(body_en, body, ''))
    ) STORED;

CREATE INDEX idx_post_search_tsv ON post USING GIN (search_tsv);
