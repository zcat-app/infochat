-- V84: seed the v2 tag tree and migrate the flat vocabulary (M1-866).
-- The M1-864 record's frozen list (docs/measurement/tag-tree-taxonomy.md)
-- is cited verbatim: 9 tops, 46 leaves, display=name, source_origin
-- 'bootstrap', append-only ON CONFLICT (name) DO NOTHING (the M1-861
-- mechanics, salvaged onto the v2 shape) — PLUS seven per-top residual
-- leaves (other-sports/health/fashion/culture/science/tech/business)
-- added by product ruling at start so content that fits a top but has
-- no specific leaf is never excluded from the vocabulary; the residuals
-- are fallback-marked (see the leaves seed below) and unmeasured. The
-- flat operator profile is
-- migrated onto the tree by a deterministic, zero-LLM lookup; nostr and
-- video are deliberately UNMAPPED (platform/medium names — twitter/reddit
-- bootstrap entries carry no platform tag): any occurrence in tag /
-- post.tags / source.bootstrap_tags / scope_tag fails this migration
-- loudly; the operator removes them and re-runs.
--
-- Sweep interaction (current-truth behavior): the vocabulary change
-- changes the sweep fingerprint (SHA-256 over the sorted vocabulary
-- names, TaggerWorker.sweepFingerprint), bumping the M1-736 sweep
-- generation; previously tags='{}' posts are re-tagged within the
-- existing caps (batch-size 4/tick, max-attempts 3) — bounded,
-- one-time, expected. Mapped non-empty historical tags are NOT
-- re-tagged: sweep eligibility is tags='{}' only.
--
-- Loader-gate interaction: BootstrapLoader now fails fast on
-- bootstrap-sources.json tags[] names that are not existing tree nodes;
-- the operator must move the file to tree names before the next
-- Collector startup (prod/config/bootstrap-sources.json is updated in
-- this ticket's diff; its Video and Nostr tags are removed per the same
-- ruling). The prod-DB cleanup of any stored nostr/video rows is a
-- separate operator-runbook ticket blocked by this one.

-- The fallback designation the M1-876 resolver reads (TagVocabulary.TagNode):
-- within the News top, non-fallback leaves outrank the fallback leaf, so the
-- region leaves beat the world co-tag the M1-864 record measured. Data, not
-- code: the behavior travels with the row that declares it. No GRANT change —
-- the column rides the tag table's existing per-role grants (V6, V31).
ALTER TABLE tag ADD COLUMN fallback BOOLEAN NOT NULL DEFAULT false;

-- Seed the nine tops. ON CONFLICT promotes a colliding pre-existing v1 row to
-- top (only 'news' can collide from the flat profile; an operator coinage of
-- a top name was a free-form v1 union and must become the top for the tree's
-- parent links to resolve — tops are product structure, not operator data).
INSERT INTO tag (name, display, source_origin, node_kind, fallback) VALUES
    ('sport',    'sport',    'bootstrap', 'top', FALSE),
    ('health',   'health',   'bootstrap', 'top', FALSE),
    ('fashion',  'fashion',  'bootstrap', 'top', FALSE),
    ('culture',  'culture',  'bootstrap', 'top', FALSE),
    ('science',  'science',  'bootstrap', 'top', FALSE),
    ('tech',     'tech',     'bootstrap', 'top', FALSE),
    ('business', 'business', 'bootstrap', 'top', FALSE),
    ('news',     'news',     'bootstrap', 'top', FALSE),
    ('others',   'others',   'bootstrap', 'top', FALSE)
ON CONFLICT (name) DO UPDATE SET node_kind = 'top', parent_name = NULL;

-- Seed the 53 leaves under their recorded tops. world and the seven per-top
-- residual leaves (other-*) are fallback-marked: a specific leaf outranks its
-- top's residual at equal depth, and the residual stores only when it is the
-- only proposed leaf of that top (the M1-876 tiebreak, generalized from
-- within-News to within-any-top). The residuals exist so content that fits a
-- top but has no specific leaf is never excluded from the vocabulary (product
-- ruling at start; unmeasured leaves — deliberate dump-guard shape). Leaf
-- names stay globally unique via UNIQUE(name) — the top-derivation invariant
-- (V82). ON CONFLICT DO NOTHING: an operator row colliding with a leaf name
-- survives verbatim (the collision test).
INSERT INTO tag (name, display, source_origin, node_kind, parent_name, fallback) VALUES
    ('football', 'football', 'bootstrap', 'leaf', 'sport', FALSE),
    ('basketball', 'basketball', 'bootstrap', 'leaf', 'sport', FALSE),
    ('hockey', 'hockey', 'bootstrap', 'leaf', 'sport', FALSE),
    ('tennis', 'tennis', 'bootstrap', 'leaf', 'sport', FALSE),
    ('motorsport', 'motorsport', 'bootstrap', 'leaf', 'sport', FALSE),
    ('athletics', 'athletics', 'bootstrap', 'leaf', 'sport', FALSE),
    ('esports', 'esports', 'bootstrap', 'leaf', 'sport', FALSE),
    ('other-sports', 'other-sports', 'bootstrap', 'leaf', 'sport', TRUE),
    ('medicine', 'medicine', 'bootstrap', 'leaf', 'health', FALSE),
    ('nutrition', 'nutrition', 'bootstrap', 'leaf', 'health', FALSE),
    ('fitness', 'fitness', 'bootstrap', 'leaf', 'health', FALSE),
    ('mental-health', 'mental-health', 'bootstrap', 'leaf', 'health', FALSE),
    ('public-health', 'public-health', 'bootstrap', 'leaf', 'health', FALSE),
    ('other-health', 'other-health', 'bootstrap', 'leaf', 'health', TRUE),
    ('style', 'style', 'bootstrap', 'leaf', 'fashion', FALSE),
    ('beauty', 'beauty', 'bootstrap', 'leaf', 'fashion', FALSE),
    ('luxury', 'luxury', 'bootstrap', 'leaf', 'fashion', FALSE),
    ('other-fashion', 'other-fashion', 'bootstrap', 'leaf', 'fashion', TRUE),
    ('art', 'art', 'bootstrap', 'leaf', 'culture', FALSE),
    ('movies', 'movies', 'bootstrap', 'leaf', 'culture', FALSE),
    ('music', 'music', 'bootstrap', 'leaf', 'culture', FALSE),
    ('tv', 'tv', 'bootstrap', 'leaf', 'culture', FALSE),
    ('books', 'books', 'bootstrap', 'leaf', 'culture', FALSE),
    ('gaming', 'gaming', 'bootstrap', 'leaf', 'culture', FALSE),
    ('other-culture', 'other-culture', 'bootstrap', 'leaf', 'culture', TRUE),
    ('space', 'space', 'bootstrap', 'leaf', 'science', FALSE),
    ('environment', 'environment', 'bootstrap', 'leaf', 'science', FALSE),
    ('biology', 'biology', 'bootstrap', 'leaf', 'science', FALSE),
    ('physics', 'physics', 'bootstrap', 'leaf', 'science', FALSE),
    ('research', 'research', 'bootstrap', 'leaf', 'science', FALSE),
    ('other-science', 'other-science', 'bootstrap', 'leaf', 'science', TRUE),
    ('ai', 'ai', 'bootstrap', 'leaf', 'tech', FALSE),
    ('software-development', 'software-development', 'bootstrap', 'leaf', 'tech', FALSE),
    ('cybersecurity', 'cybersecurity', 'bootstrap', 'leaf', 'tech', FALSE),
    ('robotics', 'robotics', 'bootstrap', 'leaf', 'tech', FALSE),
    ('hardware', 'hardware', 'bootstrap', 'leaf', 'tech', FALSE),
    ('internet', 'internet', 'bootstrap', 'leaf', 'tech', FALSE),
    ('other-tech', 'other-tech', 'bootstrap', 'leaf', 'tech', TRUE),
    ('markets', 'markets', 'bootstrap', 'leaf', 'business', FALSE),
    ('economy', 'economy', 'bootstrap', 'leaf', 'business', FALSE),
    ('crypto', 'crypto', 'bootstrap', 'leaf', 'business', FALSE),
    ('startups', 'startups', 'bootstrap', 'leaf', 'business', FALSE),
    ('personal-finance', 'personal-finance', 'bootstrap', 'leaf', 'business', FALSE),
    ('other-business', 'other-business', 'bootstrap', 'leaf', 'business', TRUE),
    ('world', 'world', 'bootstrap', 'leaf', 'news', TRUE),
    ('africa', 'africa', 'bootstrap', 'leaf', 'news', FALSE),
    ('americas', 'americas', 'bootstrap', 'leaf', 'news', FALSE),
    ('asia', 'asia', 'bootstrap', 'leaf', 'news', FALSE),
    ('europe', 'europe', 'bootstrap', 'leaf', 'news', FALSE),
    ('middle-east', 'middle-east', 'bootstrap', 'leaf', 'news', FALSE),
    ('personal', 'personal', 'bootstrap', 'leaf', 'others', FALSE),
    ('opinion', 'opinion', 'bootstrap', 'leaf', 'others', FALSE),
    ('misc', 'misc', 'bootstrap', 'leaf', 'others', FALSE)
ON CONFLICT (name) DO NOTHING;

-- Flat-profile identity rows (ai/crypto/research) pre-exist on the operator
-- DB and the seed's DO NOTHING left them parentless; re-parent the survivors
-- to their tops (idempotent — display/source_origin untouched).
UPDATE tag SET parent_name = v.parent FROM (VALUES ('ai','tech'),('crypto','business'),('research','science')) AS v(name,parent) WHERE tag.name = v.name AND tag.node_kind = 'leaf' AND tag.parent_name IS NULL;

-- Loud validation, tag table: every pre-existing row is either a seeded node
-- (a colliding operator row among them survives per the seed's DO NOTHING) or
-- a lookup key. Anything else — nostr, video, ai-image, a stray coinage —
-- fails the migration with the names listed; silent persistence is forbidden.
DO $$
DECLARE
    leftover TEXT[];
BEGIN
    SELECT array_agg(t.name ORDER BY t.name)
      INTO leftover
      FROM tag t
     WHERE t.name NOT IN (
           VALUES ('sport'), ('health'), ('fashion'), ('culture'), ('science'),
                  ('tech'), ('business'), ('news'), ('others'),
                  ('football'), ('basketball'), ('hockey'), ('tennis'), ('motorsport'),
                  ('athletics'), ('esports'), ('other-sports'), ('medicine'), ('nutrition'),
                  ('fitness'), ('mental-health'), ('public-health'), ('other-health'),
                  ('style'), ('beauty'), ('luxury'), ('other-fashion'),
                  ('art'), ('movies'), ('music'), ('tv'), ('books'), ('gaming'),
                  ('other-culture'), ('space'), ('environment'), ('biology'), ('physics'),
                  ('research'), ('other-science'),
                  ('ai'), ('software-development'), ('cybersecurity'), ('robotics'),
                  ('hardware'), ('internet'), ('other-tech'), ('markets'), ('economy'), ('crypto'),
                  ('startups'), ('personal-finance'), ('other-business'),
                  ('world'), ('africa'), ('americas'),
                  ('asia'), ('europe'), ('middle-east'), ('personal'), ('opinion'), ('misc'))
       AND NOT EXISTS (
           SELECT 1
             FROM (VALUES
                 ('claude', 'ai', TRUE), ('openai', 'ai', TRUE), ('anthropic', 'ai', TRUE),
                 ('qwen', 'ai', TRUE), ('google', 'ai', TRUE),
                 ('zcash', 'crypto', TRUE),
                 ('malware', 'cybersecurity', FALSE), ('privacy', 'cybersecurity', FALSE),
                 ('security', 'cybersecurity', FALSE),
                 ('quarkus', 'software-development', TRUE),
                 ('java', 'software-development', FALSE),
                 ('spring-io', 'software-development', TRUE),
                 ('langchain4j', 'software-development', TRUE),
                 ('oracle', 'software-development', TRUE),
                 ('development', 'software-development', FALSE),
                 ('comfyui', 'software-development', TRUE),
                 ('news', 'world', FALSE),
                 ('glmai', 'misc', TRUE), ('kimiai', 'misc', TRUE)
             ) AS m(v1, leaf, entity)
            WHERE m.v1 = t.name);
    IF leftover IS NOT NULL THEN
        RAISE EXCEPTION 'V84: unmapped tag row(s) % — no v2 node exists for these names; remove them (and any post.tags / source.bootstrap_tags / scope_tag references) or map them, then re-run',
            leftover;
    END IF;
END $$;

-- Loud validation, arrays: every post.tags and source.bootstrap_tags element
-- is either a seeded leaf (identity passthrough: ai, crypto, research) or a
-- lookup key. nostr/video and any other leftover fail here with names listed.
DO $$
DECLARE
    leftover TEXT[];
BEGIN
    SELECT array_agg(e.name ORDER BY e.name)
      INTO leftover
      FROM (SELECT unnest(tags) AS name FROM post
            UNION ALL
            SELECT unnest(bootstrap_tags) AS name FROM source) e
     WHERE e.name NOT IN (
           VALUES ('football'), ('basketball'), ('hockey'), ('tennis'), ('motorsport'),
                  ('athletics'), ('esports'), ('other-sports'), ('medicine'), ('nutrition'),
                  ('fitness'), ('mental-health'), ('public-health'), ('other-health'),
                  ('style'), ('beauty'), ('luxury'), ('other-fashion'),
                  ('art'), ('movies'), ('music'), ('tv'), ('books'), ('gaming'),
                  ('other-culture'), ('space'), ('environment'), ('biology'), ('physics'),
                  ('research'), ('other-science'),
                  ('ai'), ('software-development'), ('cybersecurity'), ('robotics'),
                  ('hardware'), ('internet'), ('other-tech'), ('markets'), ('economy'), ('crypto'),
                  ('startups'), ('personal-finance'), ('other-business'),
                  ('world'), ('africa'), ('americas'),
                  ('asia'), ('europe'), ('middle-east'), ('personal'), ('opinion'), ('misc'))
       AND NOT EXISTS (
           SELECT 1
             FROM (VALUES
                 ('claude', 'ai', TRUE), ('openai', 'ai', TRUE), ('anthropic', 'ai', TRUE),
                 ('qwen', 'ai', TRUE), ('google', 'ai', TRUE),
                 ('zcash', 'crypto', TRUE),
                 ('malware', 'cybersecurity', FALSE), ('privacy', 'cybersecurity', FALSE),
                 ('security', 'cybersecurity', FALSE),
                 ('quarkus', 'software-development', TRUE),
                 ('java', 'software-development', FALSE),
                 ('spring-io', 'software-development', TRUE),
                 ('langchain4j', 'software-development', TRUE),
                 ('oracle', 'software-development', TRUE),
                 ('development', 'software-development', FALSE),
                 ('comfyui', 'software-development', TRUE),
                 ('news', 'world', FALSE),
                 ('glmai', 'misc', TRUE), ('kimiai', 'misc', TRUE)
             ) AS m(v1, leaf, entity)
            WHERE m.v1 = e.name);
    IF leftover IS NOT NULL THEN
        RAISE EXCEPTION 'V84: unmapped tag array element(s) % in post.tags / source.bootstrap_tags — remove them or map them, then re-run',
            leftover;
    END IF;
END $$;

-- post.tags := mapped leaves, one set-based UPDATE per array, zero-LLM,
-- order-preserving and deduplicated. Mapped-away ENTITY names (the vendor
-- tail) additionally land in tag_candidates verbatim — search continuity
-- (decision 6); category names (development/security/malware/privacy) do not.
-- Identity leaves (ai, crypto, research) pass through unchanged.
UPDATE post p
   SET tags = m.mapped,
       tag_candidates = p.tag_candidates || m.entities
  FROM (
    SELECT p2.id,
           (SELECT COALESCE(array_agg(x.mapped ORDER BY x.ord), '{}')
              FROM (SELECT DISTINCT ON (CASE WHEN lm.v1 IS NULL THEN e.name ELSE lm.leaf END)
                           CASE WHEN lm.v1 IS NULL THEN e.name ELSE lm.leaf END AS mapped,
                           e.ord
                      FROM unnest(p2.tags) WITH ORDINALITY AS e(name, ord)
                      LEFT JOIN (VALUES
                          ('claude', 'ai', TRUE), ('openai', 'ai', TRUE), ('anthropic', 'ai', TRUE),
                          ('qwen', 'ai', TRUE), ('google', 'ai', TRUE),
                          ('zcash', 'crypto', TRUE),
                          ('malware', 'cybersecurity', FALSE), ('privacy', 'cybersecurity', FALSE),
                          ('security', 'cybersecurity', FALSE),
                          ('quarkus', 'software-development', TRUE),
                          ('java', 'software-development', FALSE),
                          ('spring-io', 'software-development', TRUE),
                          ('langchain4j', 'software-development', TRUE),
                          ('oracle', 'software-development', TRUE),
                          ('development', 'software-development', FALSE),
                          ('comfyui', 'software-development', TRUE),
                          ('news', 'world', FALSE),
                          ('glmai', 'misc', TRUE), ('kimiai', 'misc', TRUE)
                      ) AS lm(v1, leaf, entity) ON lm.v1 = e.name
                     ORDER BY CASE WHEN lm.v1 IS NULL THEN e.name ELSE lm.leaf END, e.ord) x) AS mapped,
           (SELECT COALESCE(array_agg(e2.name ORDER BY e2.ord), '{}')
              FROM unnest(p2.tags) WITH ORDINALITY AS e2(name, ord)
              JOIN (VALUES
                  ('claude', 'ai', TRUE), ('openai', 'ai', TRUE), ('anthropic', 'ai', TRUE),
                  ('qwen', 'ai', TRUE), ('google', 'ai', TRUE),
                  ('zcash', 'crypto', TRUE),
                  ('quarkus', 'software-development', TRUE),
                  ('spring-io', 'software-development', TRUE),
                  ('langchain4j', 'software-development', TRUE),
                  ('oracle', 'software-development', TRUE),
                  ('comfyui', 'software-development', TRUE),
                  ('glmai', 'misc', TRUE), ('kimiai', 'misc', TRUE)
              ) AS em(v1, leaf, entity) ON em.v1 = e2.name AND em.entity) AS entities
      FROM post p2
     WHERE p2.tags <> '{}'
  ) m
 WHERE p.id = m.id;

-- source.bootstrap_tags := mapped leaves via the same lookup. This keeps the
-- tagger's three-surface fallback (which writes bootstrap_tags into post.tags
-- unvalidated) and the /unfollow-tag ALL-to-EXPLICIT seed (which joins
-- bootstrap_tags names against tag.name) functional on tree names.
UPDATE source s
   SET bootstrap_tags = m.mapped
  FROM (
    SELECT s2.id,
           (SELECT COALESCE(array_agg(x.mapped ORDER BY x.ord), '{}')
              FROM (SELECT DISTINCT ON (CASE WHEN lm.v1 IS NULL THEN e.name ELSE lm.leaf END)
                           CASE WHEN lm.v1 IS NULL THEN e.name ELSE lm.leaf END AS mapped,
                           e.ord
                      FROM unnest(s2.bootstrap_tags) WITH ORDINALITY AS e(name, ord)
                      LEFT JOIN (VALUES
                          ('claude', 'ai', TRUE), ('openai', 'ai', TRUE), ('anthropic', 'ai', TRUE),
                          ('qwen', 'ai', TRUE), ('google', 'ai', TRUE),
                          ('zcash', 'crypto', TRUE),
                          ('malware', 'cybersecurity', FALSE), ('privacy', 'cybersecurity', FALSE),
                          ('security', 'cybersecurity', FALSE),
                          ('quarkus', 'software-development', TRUE),
                          ('java', 'software-development', FALSE),
                          ('spring-io', 'software-development', TRUE),
                          ('langchain4j', 'software-development', TRUE),
                          ('oracle', 'software-development', TRUE),
                          ('development', 'software-development', FALSE),
                          ('comfyui', 'software-development', TRUE),
                          ('news', 'world', FALSE),
                          ('glmai', 'misc', TRUE), ('kimiai', 'misc', TRUE)
                      ) AS lm(v1, leaf, entity) ON lm.v1 = e.name
                     ORDER BY CASE WHEN lm.v1 IS NULL THEN e.name ELSE lm.leaf END, e.ord) x) AS mapped
      FROM source s2
     WHERE s2.bootstrap_tags <> '{}'
  ) m
 WHERE s.id = m.id;

-- scope_tag remap: follows of superseded v1 names re-point at the mapped
-- node's row (INSERT new + DELETE old, idempotent), so the later retirement
-- never orphans an FK and a followed name keeps resolving.
INSERT INTO scope_tag (scope_kind, scope_id, tag_id)
SELECT DISTINCT st.scope_kind, st.scope_id, target.id
  FROM scope_tag st
  JOIN tag old ON old.id = st.tag_id
  JOIN (VALUES
      ('claude', 'ai'), ('openai', 'ai'), ('anthropic', 'ai'),
      ('qwen', 'ai'), ('google', 'ai'),
      ('zcash', 'crypto'),
      ('malware', 'cybersecurity'), ('privacy', 'cybersecurity'), ('security', 'cybersecurity'),
      ('quarkus', 'software-development'), ('java', 'software-development'),
      ('spring-io', 'software-development'), ('langchain4j', 'software-development'),
      ('oracle', 'software-development'), ('development', 'software-development'),
      ('comfyui', 'software-development'),
      ('news', 'world'),
      ('glmai', 'misc'), ('kimiai', 'misc')
  ) AS m(v1, leaf) ON m.v1 = old.name
  JOIN tag target ON target.name = m.leaf
ON CONFLICT (scope_kind, scope_id, tag_id) DO NOTHING;

DELETE FROM scope_tag st
 USING tag old
 WHERE st.tag_id = old.id
   AND EXISTS (SELECT 1 FROM (VALUES
       ('claude'), ('openai'), ('anthropic'), ('qwen'), ('google'),
       ('zcash'),
       ('malware'), ('privacy'), ('security'),
       ('quarkus'), ('java'), ('spring-io'), ('langchain4j'), ('oracle'),
       ('development'), ('comfyui'), ('news'), ('glmai'), ('kimiai')
   ) AS r(v1) WHERE r.v1 = old.name);

-- Retire the superseded v1 rows. Identity names (ai, crypto, research) are
-- seeded leaves and stay; everything mapped away goes — except the promoted
-- 'news' TOP (the v1 'news' row became the tree top above; node_kind guard).
-- The scope_tag remap above guarantees the DELETE has no FK dependents left.
DELETE FROM tag
 WHERE node_kind = 'leaf'
   AND name IN (
       VALUES ('claude'), ('openai'), ('anthropic'), ('qwen'), ('google'),
              ('zcash'),
              ('malware'), ('privacy'), ('security'),
              ('quarkus'), ('java'), ('spring-io'), ('langchain4j'), ('oracle'),
              ('development'), ('comfyui'), ('news'), ('glmai'), ('kimiai'));
