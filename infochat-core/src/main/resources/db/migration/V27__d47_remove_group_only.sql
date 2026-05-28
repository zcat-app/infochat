-- V27: D47 — remove the group_only registration state.
--
-- The complementary removal to V26 (which added the additive groups
-- columns). D47 consolidates the registration_state value set from
-- {preban, group_only, invited, vouched} down to
-- {preban, invited, vouched}: the group auto-registration path is gone,
-- so no user is ever minted in the 'group_only' state again.
--
-- The UPDATE must precede the CHECK alteration: any lingering
-- 'group_only' row would fail the narrowed CHECK if the ALTER ran
-- first. Flyway wraps this script in a single transaction on
-- PostgreSQL, so the UPDATE-then-ALTER sequence is atomic.
--
-- The audit row is conditional. On a fresh DB (CI / dev) no row holds
-- 'group_only', so the UPDATE affects zero rows and the CTE-fed INSERT
-- emits nothing (the HAVING filters the zero-count aggregate). On a
-- deployed DB the single row records the bulk transition with the
-- affected-row count. target_kind='system' is the only closed-set
-- value (V5 audit_log CHECK) that fits a schema-wide consolidation;
-- action carries no SQL CHECK so the D47-specific verb is raw text.

WITH updated AS (
    UPDATE users SET registration_state = 'invited'
     WHERE registration_state = 'group_only'
    RETURNING 1
)
INSERT INTO audit_log (action, target_kind, target_id, details_json)
SELECT 'D47_REGISTRATION_STATE_CONSOLIDATION',
       'system',
       'users.registration_state',
       jsonb_build_object('affected_rows', count(*))
  FROM updated
HAVING count(*) > 0;

ALTER TABLE users DROP CONSTRAINT users_registration_state_chk;
ALTER TABLE users ADD CONSTRAINT users_registration_state_chk
    CHECK (registration_state IN ('preban','invited','vouched'));
