-- M1-413: pre-evaluated READY-post seed fixture for retrieval-tier tests.
--
-- Inserts content in its post-evaluation TERMINAL state directly — no
-- Stage 1/2, tagging, or embedding pipeline is run (that path is exercised
-- by M1-416). Retrieval tests (and, downstream, the M1-414 dev harness and
-- the M1-415 golden-path journey) get realistic, already-evaluated content
-- without paying for an LLM or a network fetch.
--
-- DETERMINISTIC CONTRACT (keep in sync with SeedFixture.java; M1-414/M1-415
-- assert against these). Fixed UUIDs and an `m1-413-` identifier prefix keep
-- every row isolated from other @SeedDataSource ITs sharing the test DB:
--   user      00000413-0000-4000-8000-000000000001  (DM scope owner)
--   source    00000413-0000-4000-8000-000000000010  (active, non-deleted)
--   READY posts (uid):
--     m1-413-ready-security  tag {m1-413-security}  HAS an embedding row
--     m1-413-ready-ai        tag {m1-413-ai}        NULL embedding
--     m1-413-ready-java      tag {m1-413-java}      NULL embedding
--   non-READY posts (excluded from deterministic retrieval):
--     m1-413-raw         status RAW
--     m1-413-quarantined status QUARANTINED
--
-- Idempotent: the leading DELETEs remove this fixture's own rows (by fixed
-- UUID / `m1-413-` prefix) so the file is safe to apply once per test — and
-- safe for M1-414 to re-run on every dev-app startup — without races. Delete
-- order respects FKs: subscription and post(_embedding) before source; source
-- before users (source.added_by → users ON DELETE SET NULL).

DELETE FROM source_subscription
 WHERE scope_kind = 'dm' AND scope_id = '00000413-0000-4000-8000-000000000001';
DELETE FROM post_embedding WHERE post_id IN (
    '00000413-0000-4000-8000-000000000101',
    '00000413-0000-4000-8000-000000000102',
    '00000413-0000-4000-8000-000000000103',
    '00000413-0000-4000-8000-000000000201',
    '00000413-0000-4000-8000-000000000202');
DELETE FROM post   WHERE uid  LIKE 'm1-413-%';
DELETE FROM tag    WHERE name LIKE 'm1-413-%';
DELETE FROM source WHERE id = '00000413-0000-4000-8000-000000000010';
DELETE FROM users  WHERE id = '00000413-0000-4000-8000-000000000001';

INSERT INTO users (id, adapter, contact_id, is_admin, registration_state)
VALUES ('00000413-0000-4000-8000-000000000001', 'inmemory', 'm1-413-seed-user',
        FALSE, 'vouched');

INSERT INTO source (id, kind, identifier, display_name, category,
                    bootstrap_tags, status, added_by)
VALUES ('00000413-0000-4000-8000-000000000010', 'rss', 'm1-413-seed-source',
        'M1-413 Seed Source', 'news',
        ARRAY['m1-413-security', 'm1-413-ai', 'm1-413-java'], 'active',
        '00000413-0000-4000-8000-000000000001');

INSERT INTO source_subscription (scope_kind, scope_id, source_id)
VALUES ('dm', '00000413-0000-4000-8000-000000000001',
        '00000413-0000-4000-8000-000000000010');

-- Controlled-vocabulary (Tier-1) tag set. `name` is the normalized lowercase
-- key; `display` carries the user-facing casing.
INSERT INTO tag (name, display, source_origin) VALUES
    ('m1-413-security', 'Security', 'bootstrap'),
    ('m1-413-ai',       'AI',       'bootstrap'),
    ('m1-413-java',     'Java',     'bootstrap');

-- READY posts in terminal state: pipeline-done flags set, ready_at stamped.
-- published_at is staggered (1h/2h/3h ago) so ORDER BY published_at DESC is
-- deterministic; using now()-relative times keeps the posts inside a 24h
-- retrieval window whenever the suite runs (matching the in-tree IT pattern).
INSERT INTO post (id, uid, source_id, upstream_identifier, url, title, body,
                  published_at, status, ready_at,
                  stage1_done, stage2_done, tagger_done, embedding_done, tags)
VALUES
    ('00000413-0000-4000-8000-000000000101', 'm1-413-ready-security',
     '00000413-0000-4000-8000-000000000010', 'm1-413-up-security',
     'https://example.invalid/security', 'Seed: security advisory',
     'Body about a security advisory.', now() - interval '1 hour', 'READY',
     now(), TRUE, TRUE, TRUE, TRUE, ARRAY['m1-413-security']),
    ('00000413-0000-4000-8000-000000000102', 'm1-413-ready-ai',
     '00000413-0000-4000-8000-000000000010', 'm1-413-up-ai',
     'https://example.invalid/ai', 'Seed: AI model release',
     'Body about an AI model release.', now() - interval '2 hours', 'READY',
     now(), TRUE, TRUE, TRUE, FALSE, ARRAY['m1-413-ai']),
    ('00000413-0000-4000-8000-000000000103', 'm1-413-ready-java',
     '00000413-0000-4000-8000-000000000010', 'm1-413-up-java',
     'https://example.invalid/java', 'Seed: Java release',
     'Body about a Java release.', now() - interval '3 hours', 'READY',
     now(), TRUE, TRUE, TRUE, FALSE, ARRAY['m1-413-java']);

-- Non-READY posts: same source + scope, excluded from retrieval by status.
INSERT INTO post (id, uid, source_id, upstream_identifier, url, title, body,
                  published_at, status, tags)
VALUES
    ('00000413-0000-4000-8000-000000000201', 'm1-413-raw',
     '00000413-0000-4000-8000-000000000010', 'm1-413-up-raw',
     'https://example.invalid/raw', 'Seed: raw unprocessed',
     'Body still RAW.', now() - interval '1 hour', 'RAW', ARRAY['m1-413-ai']),
    ('00000413-0000-4000-8000-000000000202', 'm1-413-quarantined',
     '00000413-0000-4000-8000-000000000010', 'm1-413-up-quarantined',
     'https://example.invalid/quarantined', 'Seed: quarantined',
     'Body quarantined.', now() - interval '1 hour', 'QUARANTINED',
     ARRAY['m1-413-security']);

-- Embedding row for ONE READY post only (the security post). The AI and Java
-- posts deliberately have NO embedding row (NULL embedding) so the
-- embedding-optional retrieval path is exercised: deterministic retrieval
-- returns READY posts regardless of embedding presence. fetched_at is read
-- back from the post row so the embedding lands in the same partition.
-- The vector is a constant 768-d value matching the seeded embedding_metadata
-- dimension (V11: nomic-embed-text, 768).
INSERT INTO post_embedding (post_id, embedding, embedding_model, fetched_at)
SELECT id,
       ('[' || array_to_string(array_fill(0.1::real, ARRAY[768]), ',') || ']')::vector,
       'nomic-embed-text',
       fetched_at
  FROM post
 WHERE id = '00000413-0000-4000-8000-000000000101';
