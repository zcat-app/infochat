-- prod/sql/seed-synthetic-corpus.sql — live-run synthetic post corpus (M1-537).
--
-- Loads a deterministic, ALREADY-EVALUATED post corpus into a RUNNING
-- deployment's database so the content commands (/summary, /follow-tag,
-- /save→/saved, digests) return stable rows without fetching a real feed or
-- paying for an LLM. This is the synthetic "future" half of the live-e2e data
-- strategy (docs/plan/live-e2e/README.md §3); the real once-fetched corpus is
-- preserved in place by M1-536's control-plane reset.
--
-- Row shapes and the deterministic-UID scheme are lifted from the M1-413 test
-- fixture (infochat-provider/src/test/resources/fixtures/seed-ready-posts.sql)
-- so the golden-path assertions and this live seed stay consistent; this file
-- is NOT a new fixture design, only the same corpus made loadable into a live
-- deployment DB (not @QuarkusTest) with live-controllable timestamps.
--
-- Run under the DATABASE OWNER role (`infochat`): post/source/tag are
-- collector-owned tables and the NOLOGIN provider role has SELECT-only on them
-- (M1-163). The wrapper prod/live-seed.sh reaches the owner the same way
-- backup.sh / live-reset.sh do (docker compose exec, PGPASSWORD, -U infochat).
-- Inserts into the existing schema only — no DDL, no Flyway change.
--
-- DETERMINISTIC CONTRACT (fixed UUIDs + an `m1-537-` uid/identifier prefix keep
-- every seeded row isolated from the real data-plane and from other seeds):
--   user      00000537-0000-4000-8000-000000000001  (DM scope owner)
--   source    00000537-0000-4000-8000-000000000010  (rss, active, non-deleted)
--   READY posts (uid):
--     m1-537-ready-security  tag {m1-537-security}  HAS an embedding row
--     m1-537-ready-ai        tag {m1-537-ai}        NULL embedding
--     m1-537-ready-java      tag {m1-537-java}      NULL embedding
--   non-READY control posts (excluded from deterministic retrieval by status):
--     m1-537-raw          status RAW
--     m1-537-quarantined  status QUARANTINED
--
-- TIMESTAMPS ARE THE CLOCK. The prod Clock is hardcoded Clock.systemUTC() and
-- is NOT mocked here (out of scope); time-window behaviour is controlled by the
-- DATA. published_at/ready_at are set to `now() - offset_minutes`, so the caller
-- places rows inside or outside a given /summary window without touching the
-- app clock. The offset is the `offset_minutes` psql variable (default 30 —
-- within the last hour); the three READY posts are staggered by 1-minute steps
-- off that base so ORDER BY published_at DESC is deterministic. fetched_at (the
-- partition key) is deliberately left at now() so the row always lands in the
-- current monthly partition (post_202607) regardless of the window offset.
--
-- IDEMPOTENT. Flat tables upsert on their natural keys (source by
-- (kind,identifier), tag by name, users by (adapter,contact_id), subscription
-- by PK). post is partitioned by fetched_at, so UNIQUE(uid,fetched_at) makes
-- uid alone an invalid ON CONFLICT target; the corpus is instead delete-by-uid-
-- then-insert (the M1-413 fixture's own mechanism). A second run neither
-- duplicates rows nor errors.

\set ON_ERROR_STOP on

-- Standalone default so the SQL is valid when run directly; prod/live-seed.sh
-- always passes -v offset_minutes explicitly (its own default is the same 30).
\if :{?offset_minutes}
\else
  \set offset_minutes 30
\endif

BEGIN;

-- DM scope owner. Upsert on the (adapter, contact_id) natural key; after a
-- control-plane reset (M1-536) users is empty, so a fresh insert creates it.
INSERT INTO users (id, adapter, contact_id, is_admin, registration_state)
VALUES ('00000537-0000-4000-8000-000000000001', 'inmemory', 'm1-537-seed-user',
        FALSE, 'vouched')
ON CONFLICT (adapter, contact_id) DO NOTHING;

-- Active, non-deleted source. Upsert on (kind, identifier); DO UPDATE reactivates
-- the row (status/deleted_at) so a re-run after any soft-delete restores it.
INSERT INTO source (id, kind, identifier, display_name, category,
                    bootstrap_tags, status, added_by)
VALUES ('00000537-0000-4000-8000-000000000010', 'rss', 'm1-537-seed-source',
        'M1-537 Live Seed Source', 'news',
        ARRAY['m1-537-security', 'm1-537-ai', 'm1-537-java'], 'active',
        '00000537-0000-4000-8000-000000000001')
ON CONFLICT (kind, identifier) DO UPDATE
    SET status = 'active',
        deleted_at = NULL,
        deleted_by = NULL,
        display_name = EXCLUDED.display_name,
        bootstrap_tags = EXCLUDED.bootstrap_tags;

-- Subscribe the DM scope to the seeded source so retrieval returns its posts.
INSERT INTO source_subscription (scope_kind, scope_id, source_id)
VALUES ('dm', '00000537-0000-4000-8000-000000000001',
        '00000537-0000-4000-8000-000000000010')
ON CONFLICT (scope_kind, scope_id, source_id) DO NOTHING;

-- Controlled-vocabulary (Tier-1) tag set. `name` is the normalized lowercase
-- key; `display` carries the user-facing casing. Vocabulary is append-only.
INSERT INTO tag (name, display, source_origin) VALUES
    ('m1-537-security', 'Security', 'bootstrap'),
    ('m1-537-ai',       'AI',       'bootstrap'),
    ('m1-537-java',     'Java',     'bootstrap')
ON CONFLICT (name) DO NOTHING;

-- post is partitioned by fetched_at (UNIQUE is (uid, fetched_at)), so uid alone
-- cannot be an ON CONFLICT target. Delete this seed's own rows by uid prefix,
-- then re-insert — idempotent by uid, and it touches no real data-plane row
-- (the `m1-537-` prefix and fixed UUIDs are unique to this seed). Delete the
-- embedding rows first: post_embedding carries no FK to post (V11) but shares
-- the (post_id, fetched_at) PK, so stale rows would otherwise collide on re-run.
DELETE FROM post_embedding WHERE post_id IN (
    '00000537-0000-4000-8000-000000000101',
    '00000537-0000-4000-8000-000000000102',
    '00000537-0000-4000-8000-000000000103');
DELETE FROM post WHERE uid LIKE 'm1-537-%';

-- READY posts in terminal state: all pipeline flags set, ready_at stamped.
-- published_at is `now() - offset_minutes` staggered by 1-minute steps so the
-- default (30) places all three inside a /summary -w 24h window; a large offset
-- places them outside it. fetched_at stays now() (partition key → post_202607).
INSERT INTO post (id, uid, source_id, upstream_identifier, url, title, body,
                  published_at, status, ready_at,
                  stage1_done, stage2_done, tagger_done, embedding_done, tags)
VALUES
    ('00000537-0000-4000-8000-000000000101', 'm1-537-ready-security',
     '00000537-0000-4000-8000-000000000010', 'm1-537-up-security',
     'https://example.invalid/security', 'Seed: security advisory',
     'Body about a security advisory.',
     now() - make_interval(mins => :offset_minutes), 'READY',
     now() - make_interval(mins => :offset_minutes),
     TRUE, TRUE, TRUE, TRUE, ARRAY['m1-537-security']),
    ('00000537-0000-4000-8000-000000000102', 'm1-537-ready-ai',
     '00000537-0000-4000-8000-000000000010', 'm1-537-up-ai',
     'https://example.invalid/ai', 'Seed: AI model release',
     'Body about an AI model release.',
     now() - make_interval(mins => :offset_minutes + 1), 'READY',
     now() - make_interval(mins => :offset_minutes + 1),
     TRUE, TRUE, TRUE, FALSE, ARRAY['m1-537-ai']),
    ('00000537-0000-4000-8000-000000000103', 'm1-537-ready-java',
     '00000537-0000-4000-8000-000000000010', 'm1-537-up-java',
     'https://example.invalid/java', 'Seed: Java release',
     'Body about a Java release.',
     now() - make_interval(mins => :offset_minutes + 2), 'READY',
     now() - make_interval(mins => :offset_minutes + 2),
     TRUE, TRUE, TRUE, FALSE, ARRAY['m1-537-java']);

-- Non-READY control posts: same source + scope, excluded from retrieval by
-- status. They prove the deterministic SQL path filters on status='READY'.
INSERT INTO post (id, uid, source_id, upstream_identifier, url, title, body,
                  published_at, status, tags)
VALUES
    ('00000537-0000-4000-8000-000000000201', 'm1-537-raw',
     '00000537-0000-4000-8000-000000000010', 'm1-537-up-raw',
     'https://example.invalid/raw', 'Seed: raw unprocessed',
     'Body still RAW.', now() - make_interval(mins => :offset_minutes), 'RAW',
     ARRAY['m1-537-ai']),
    ('00000537-0000-4000-8000-000000000202', 'm1-537-quarantined',
     '00000537-0000-4000-8000-000000000010', 'm1-537-up-quarantined',
     'https://example.invalid/quarantined', 'Seed: quarantined',
     'Body quarantined.', now() - make_interval(mins => :offset_minutes),
     'QUARANTINED', ARRAY['m1-537-security']);

-- Embedding row for ONE READY post only (the security post). The AI and Java
-- READY posts deliberately have NO embedding row (NULL embedding) so the
-- embedding-optional retrieval path is exercised: deterministic retrieval
-- returns READY posts regardless of embedding presence. fetched_at is read back
-- from the post row so the embedding lands in the same partition. The vector is
-- a constant 768-d value matching the V11 embedding_metadata dimension
-- (nomic-embed-text, 768).
INSERT INTO post_embedding (post_id, embedding, embedding_model, fetched_at)
SELECT id,
       ('[' || array_to_string(array_fill(0.1::real, ARRAY[768]), ',') || ']')::vector,
       'nomic-embed-text',
       fetched_at
  FROM post
 WHERE id = '00000537-0000-4000-8000-000000000101';

COMMIT;
