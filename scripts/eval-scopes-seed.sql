-- Eval scopes for the retrieval-eval campaign: five fixed-UUID dm scopes,
-- one per enabled language (D43); zero subscriptions/exclusions by
-- design (D59 world = live non-excluded bootstrap). Idempotent; probes expect 5/0/0.

INSERT INTO scope_preferences (scope_kind, scope_id, language)
VALUES
    ('dm', '99a41442-61e2-4c48-962d-26092c3995a7', 'en'),
    ('dm', '1213f0bd-723c-41ff-8d3e-89aaaf00dca4', 'cs'),
    ('dm', 'f568a11b-ca60-436a-832d-ec24a55bfe88', 'es'),
    ('dm', 'd7fb2b75-29e0-46ff-93cb-93fa055d953e', 'ru'),
    ('dm', '5e2578ce-c5c6-4bc3-9b66-e392802090b8', 'tr')
ON CONFLICT (scope_kind, scope_id) DO NOTHING;

-- Probes (expected: 5 / 0 / 0).
SELECT 'eval_scopes' AS check, count(*) AS n
FROM scope_preferences
WHERE scope_kind = 'dm'
  AND scope_id IN ('99a41442-61e2-4c48-962d-26092c3995a7',
                   '1213f0bd-723c-41ff-8d3e-89aaaf00dca4',
                   'f568a11b-ca60-436a-832d-ec24a55bfe88',
                   'd7fb2b75-29e0-46ff-93cb-93fa055d953e',
                   '5e2578ce-c5c6-4bc3-9b66-e392802090b8');
SELECT 'eval_scope_subscriptions' AS check, count(*) AS n
FROM source_subscription
WHERE scope_kind = 'dm'
  AND scope_id IN ('99a41442-61e2-4c48-962d-26092c3995a7',
                   '1213f0bd-723c-41ff-8d3e-89aaaf00dca4',
                   'f568a11b-ca60-436a-832d-ec24a55bfe88',
                   'd7fb2b75-29e0-46ff-93cb-93fa055d953e',
                   '5e2578ce-c5c6-4bc3-9b66-e392802090b8');
SELECT 'eval_scope_exclusions' AS check, count(*) AS n
FROM source_exclusion
WHERE scope_kind = 'dm'
  AND scope_id IN ('99a41442-61e2-4c48-962d-26092c3995a7',
                   '1213f0bd-723c-41ff-8d3e-89aaaf00dca4',
                   'f568a11b-ca60-436a-832d-ec24a55bfe88',
                   'd7fb2b75-29e0-46ff-93cb-93fa055d953e',
                   '5e2578ce-c5c6-4bc3-9b66-e392802090b8');
