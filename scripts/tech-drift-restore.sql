-- Tech-world drift restore (M1-951): deletes the READY world posts whose
-- ready_at is strictly after the frozen max, plus their dangling derivative
-- rows, returning the DB to the frozen campaign fingerprint below.

-- Precondition 1: a volume snapshot of infochat-test_infochat-pgdata taken
-- BEFORE this run (pre-M1-951 snapshot; location recorded in the ticket).

-- Precondition 2: review before delete — run this file WITHOUT
--   -v execute_delete=1 first: it only prints the drift set and the
--   derivative counts. Re-run WITH -v execute_delete=1 to execute.

-- Precondition 3: the post-delete fingerprint read MUST equal the frozen pin
-- byte-exactly; a mismatch aborts the transaction below (nothing commits) and
-- is a recorded STOP — the fallback is the user's ruling, never route-around.

-- Invocation (test stack postgres container):
--   docker exec -i infochat-test-postgres-1 \
--     psql -U infochat -d infochat -X -v ON_ERROR_STOP=1 -f scripts/tech-drift-restore.sql

--   ...review the printout, then the same call plus -v execute_delete=1

-- The pin (every golden record's labeled_against value, M1-928 freeze):
--   ready=5214;max_ready_at=2026-08-24 16:00:57.001472+00;uid_sha256=06ed0de15eefad172062b4b6e3dfb11713e02017b103cc8ab8e064ffbe489727

\set frozen_max '2026-08-24 16:00:57.001472+00'

\echo '=== current fingerprint (pre-any-change) ==='
WITH world AS (
  SELECT p.uid, p.ready_at FROM post p
  WHERE p.status = 'READY' AND (
    EXISTS (SELECT 1 FROM source s_w WHERE s_w.id = p.source_id AND s_w.source_origin = 'bootstrap'
      AND s_w.deleted_at IS NULL
      AND NOT EXISTS (SELECT 1 FROM source_exclusion e_w WHERE e_w.scope_kind = 'dm'
        AND e_w.scope_id = '99a41442-61e2-4c48-962d-26092c3995a7' AND e_w.source_id = s_w.id))
    OR p.source_id IN (SELECT source_id FROM source_subscription WHERE scope_kind = 'dm'
        AND scope_id = '99a41442-61e2-4c48-962d-26092c3995a7'))
)
SELECT 'ready=' || count(*) || ';max_ready_at='
  || to_char(max(ready_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') || '+00;uid_sha256='
  || encode(sha256(string_agg(uid, '' ORDER BY uid)::bytea), 'hex') AS fingerprint
FROM world;

\echo '=== drift set: world READY posts with ready_at STRICTLY AFTER the frozen max ==='
SELECT p.uid, p.ready_at, s.kind, left(s.identifier, 44) AS source
FROM post p JOIN source s ON s.id = p.source_id
WHERE p.status = 'READY' AND (
    EXISTS (SELECT 1 FROM source s_w WHERE s_w.id = p.source_id AND s_w.source_origin = 'bootstrap'
      AND s_w.deleted_at IS NULL
      AND NOT EXISTS (SELECT 1 FROM source_exclusion e_w WHERE e_w.scope_kind = 'dm'
        AND e_w.scope_id = '99a41442-61e2-4c48-962d-26092c3995a7' AND e_w.source_id = s_w.id))
    OR p.source_id IN (SELECT source_id FROM source_subscription WHERE scope_kind = 'dm'
        AND scope_id = '99a41442-61e2-4c48-962d-26092c3995a7'))
  AND p.ready_at > :'frozen_max'
ORDER BY p.ready_at, p.uid;

\echo '=== drift composition and arithmetic ==='
SELECT s.kind, left(s.identifier, 44) AS source, count(*) AS n
FROM post p JOIN source s ON s.id = p.source_id
WHERE p.status = 'READY' AND (
    EXISTS (SELECT 1 FROM source s_w WHERE s_w.id = p.source_id AND s_w.source_origin = 'bootstrap'
      AND s_w.deleted_at IS NULL
      AND NOT EXISTS (SELECT 1 FROM source_exclusion e_w WHERE e_w.scope_kind = 'dm'
        AND e_w.scope_id = '99a41442-61e2-4c48-962d-26092c3995a7' AND e_w.source_id = s_w.id))
    OR p.source_id IN (SELECT source_id FROM source_subscription WHERE scope_kind = 'dm'
        AND scope_id = '99a41442-61e2-4c48-962d-26092c3995a7'))
  AND p.ready_at > :'frozen_max'
GROUP BY 1, 2 ORDER BY 3 DESC;

SELECT (SELECT count(*) FROM post p WHERE p.status = 'READY' AND (
    EXISTS (SELECT 1 FROM source s_w WHERE s_w.id = p.source_id AND s_w.source_origin = 'bootstrap'
      AND s_w.deleted_at IS NULL
      AND NOT EXISTS (SELECT 1 FROM source_exclusion e_w WHERE e_w.scope_kind = 'dm'
        AND e_w.scope_id = '99a41442-61e2-4c48-962d-26092c3995a7' AND e_w.source_id = s_w.id))
    OR p.source_id IN (SELECT source_id FROM source_subscription WHERE scope_kind = 'dm'
        AND scope_id = '99a41442-61e2-4c48-962d-26092c3995a7')) AND p.ready_at <= :'frozen_max')
  AS ready_at_or_below_frozen,
  (SELECT count(*) FROM post p WHERE p.status = 'READY' AND (
    EXISTS (SELECT 1 FROM source s_w WHERE s_w.id = p.source_id AND s_w.source_origin = 'bootstrap'
      AND s_w.deleted_at IS NULL
      AND NOT EXISTS (SELECT 1 FROM source_exclusion e_w WHERE e_w.scope_kind = 'dm'
        AND e_w.scope_id = '99a41442-61e2-4c48-962d-26092c3995a7' AND e_w.source_id = s_w.id))
    OR p.source_id IN (SELECT source_id FROM source_subscription WHERE scope_kind = 'dm'
        AND scope_id = '99a41442-61e2-4c48-962d-26092c3995a7')) AND p.ready_at > :'frozen_max')
  AS drift_n;

\echo '=== derivative rows that dangle with the drift set (delete targets) ==='
WITH d AS (SELECT p.id, p.uid FROM post p
  WHERE p.status = 'READY' AND (
    EXISTS (SELECT 1 FROM source s_w WHERE s_w.id = p.source_id AND s_w.source_origin = 'bootstrap'
      AND s_w.deleted_at IS NULL
      AND NOT EXISTS (SELECT 1 FROM source_exclusion e_w WHERE e_w.scope_kind = 'dm'
        AND e_w.scope_id = '99a41442-61e2-4c48-962d-26092c3995a7' AND e_w.source_id = s_w.id))
    OR p.source_id IN (SELECT source_id FROM source_subscription WHERE scope_kind = 'dm'
        AND scope_id = '99a41442-61e2-4c48-962d-26092c3995a7'))
    AND p.ready_at > :'frozen_max')
SELECT 'post_reference (from or to)' AS table_, count(*) FROM post_reference r JOIN d ON r.from_post = d.id OR r.to_post = d.id
UNION ALL SELECT 'post_entity', count(*) FROM post_entity e JOIN d ON e.post_id = d.id
UNION ALL SELECT 'post_embedding', count(*) FROM post_embedding b JOIN d ON b.post_id = d.id
UNION ALL SELECT 'quarantine', count(*) FROM quarantine q JOIN d ON q.post_id = d.id
UNION ALL SELECT 'saved_post', count(*) FROM saved_post s JOIN d ON s.post_uid = d.uid
UNION ALL SELECT 'summary_anchor', count(*) FROM summary_anchor a JOIN d ON a.post_uids @> ARRAY[d.uid]
UNION ALL SELECT 'post (the drift set itself)', count(*) FROM d
ORDER BY 1;

\if :{?execute_delete}
\echo '=== execute_delete set: transactional delete begins ==='
BEGIN;

CREATE TEMP TABLE drift_ids ON COMMIT DROP AS
SELECT p.id, p.uid FROM post p
WHERE p.status = 'READY' AND (
    EXISTS (SELECT 1 FROM source s_w WHERE s_w.id = p.source_id AND s_w.source_origin = 'bootstrap'
      AND s_w.deleted_at IS NULL
      AND NOT EXISTS (SELECT 1 FROM source_exclusion e_w WHERE e_w.scope_kind = 'dm'
        AND e_w.scope_id = '99a41442-61e2-4c48-962d-26092c3995a7' AND e_w.source_id = s_w.id))
    OR p.source_id IN (SELECT source_id FROM source_subscription WHERE scope_kind = 'dm'
        AND scope_id = '99a41442-61e2-4c48-962d-26092c3995a7'))
  AND p.ready_at > :'frozen_max';

DO $assert_nonempty$
DECLARE n bigint;
BEGIN
  SELECT count(*) INTO n FROM drift_ids;
  IF n = 0 THEN
    RAISE EXCEPTION 'drift set is empty — the world already matches or moved; nothing deleted, transaction rolled back';
  END IF;
END $assert_nonempty$;

DELETE FROM post_reference r USING drift_ids d WHERE r.from_post = d.id OR r.to_post = d.id;
DELETE FROM post_entity e USING drift_ids d WHERE e.post_id = d.id;
DELETE FROM post_embedding b USING drift_ids d WHERE b.post_id = d.id;
DELETE FROM quarantine q USING drift_ids d WHERE q.post_id = d.id;
DELETE FROM saved_post s USING drift_ids d WHERE s.post_uid = d.uid;
DELETE FROM summary_anchor a USING drift_ids d WHERE a.post_uids @> ARRAY[d.uid];
DELETE FROM post p USING drift_ids d WHERE p.id = d.id;

DO $assert_fingerprint$
DECLARE fp text;
BEGIN
  WITH world AS (
    SELECT p.uid, p.ready_at FROM post p
    WHERE p.status = 'READY' AND (
      EXISTS (SELECT 1 FROM source s_w WHERE s_w.id = p.source_id AND s_w.source_origin = 'bootstrap'
        AND s_w.deleted_at IS NULL
        AND NOT EXISTS (SELECT 1 FROM source_exclusion e_w WHERE e_w.scope_kind = 'dm'
          AND e_w.scope_id = '99a41442-61e2-4c48-962d-26092c3995a7' AND e_w.source_id = s_w.id))
      OR p.source_id IN (SELECT source_id FROM source_subscription WHERE scope_kind = 'dm'
          AND scope_id = '99a41442-61e2-4c48-962d-26092c3995a7'))
  )
  SELECT 'ready=' || count(*) || ';max_ready_at='
    || to_char(max(ready_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') || '+00;uid_sha256='
    || encode(sha256(string_agg(uid, '' ORDER BY uid)::bytea), 'hex') INTO fp
  FROM world;
  IF fp <> 'ready=5214;max_ready_at=2026-08-24 16:00:57.001472+00;uid_sha256=06ed0de15eefad172062b4b6e3dfb11713e02017b103cc8ab8e064ffbe489727' THEN
    RAISE EXCEPTION 'post-delete fingerprint differs from the frozen pin — NOTHING COMMITTED (rolled back): %', fp;
  END IF;
END $assert_fingerprint$;

COMMIT;
\echo '=== committed. post-commit fingerprint read (must equal the pin byte-exactly) ==='
WITH world AS (
  SELECT p.uid, p.ready_at FROM post p
  WHERE p.status = 'READY' AND (
    EXISTS (SELECT 1 FROM source s_w WHERE s_w.id = p.source_id AND s_w.source_origin = 'bootstrap'
      AND s_w.deleted_at IS NULL
      AND NOT EXISTS (SELECT 1 FROM source_exclusion e_w WHERE e_w.scope_kind = 'dm'
        AND e_w.scope_id = '99a41442-61e2-4c48-962d-26092c3995a7' AND e_w.source_id = s_w.id))
    OR p.source_id IN (SELECT source_id FROM source_subscription WHERE scope_kind = 'dm'
        AND scope_id = '99a41442-61e2-4c48-962d-26092c3995a7'))
)
SELECT 'ready=' || count(*) || ';max_ready_at='
  || to_char(max(ready_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') || '+00;uid_sha256='
  || encode(sha256(string_agg(uid, '' ORDER BY uid)::bytea), 'hex') AS fingerprint
FROM world;
\echo '=== restore complete: follow with the M1-929 operator smoke (label_fingerprint_match must be true) ==='
\else
\echo '=== DRY RUN ONLY — no deletes executed. Review the printout above, then re-run with -v execute_delete=1 ==='
\endif
