-- V86: flip reddit feeds registered kind='rss' to kind='reddit', in place
-- (M1-915). Prod's reddit feeds were declared kind='rss' with .rss-suffixed
-- identifiers, so they fetch over the RSS path, which has no engagement
-- fields; the dedicated reddit path (dispatch interval, page cap, enable
-- gates, score→likes parser mapping) is fully enabled and selects no feed.
-- The defect is DATA: KindResolver resolves reddit.com / redd.it hosts for
-- NEW registrations only; existing rows keep their declared kind.
--
-- Census predicate (re-runnable — docs/design/07-deployment.md's pre-check
-- uses the same one): kind='rss' AND deleted_at IS NULL AND identifier host
-- is reddit.com / redd.it (any subdomain) AND path ends .rss (optional '/',
-- any case — the strip below is case-insensitive to match the census).
--
-- Normalization strips the trailing /.rss / .rss / .rss/ suffix, leaving the
-- canonical listing URL the reddit fetcher requests via identifier || '/.rss' and
-- the corrected bootstrap file declares. ONLY kind and identifier change:
-- source_id, source_origin (D59), subscriptions, exclusions and posts are
-- keyed on source_id and survive; stored uids are NEVER rewritten (the
-- /save handle) — the JSON path's different uid derivation re-ingests
-- listing-resident items once, accepted per analysis P12 (D33/D38 bound it).
--
-- Collisions (analysis P14): UNIQUE(kind, identifier) is the identity key
-- (D38); soft-deleted reddit twins hold their key too. A row whose
-- normalized key already exists as kind='reddit' is SKIPPED and reported by
-- RAISE NOTICE for ops (/remove-source) — never failing the boot, never
-- merging rows.
DO $$
DECLARE
    r RECORD;
    flipped_count INT := 0;
    skipped_count INT := 0;
BEGIN
    FOR r IN
        SELECT id, identifier,
               regexp_replace(identifier, '/?\.rss/?$', '', 'i') AS normalized
        FROM source
        WHERE kind = 'rss'
          AND deleted_at IS NULL
          AND identifier ~* '^https?://([a-z0-9-]+\.)*(reddit\.com|redd\.it)(:[0-9]+)?(/|$)'
          AND identifier ~* '\.rss/?$'
    LOOP
        IF EXISTS (SELECT 1 FROM source s
                   WHERE s.kind = 'reddit' AND s.identifier = r.normalized) THEN
            RAISE NOTICE 'V86 reddit-kind flip: SKIPPED source % — a kind=''reddit'' row already holds identifier % (resolve by /remove-source)',
                    r.id, r.normalized;
            skipped_count := skipped_count + 1;
        ELSE
            -- The handler covers a reddit row INSERTed concurrently (e.g.
            -- /add-source) between the EXISTS check and this UPDATE.
            BEGIN
                UPDATE source SET kind = 'reddit', identifier = r.normalized WHERE id = r.id;
                flipped_count := flipped_count + 1;
            EXCEPTION WHEN unique_violation THEN
                RAISE NOTICE 'V86 reddit-kind flip: SKIPPED source % — a kind=''reddit'' row took identifier % while the migration ran (resolve by /remove-source)',
                        r.id, r.normalized;
                skipped_count := skipped_count + 1;
            END;
        END IF;
    END LOOP;
    RAISE NOTICE 'V86 reddit-kind flip complete: % rows flipped, % collisions skipped',
            flipped_count, skipped_count;
END $$;
