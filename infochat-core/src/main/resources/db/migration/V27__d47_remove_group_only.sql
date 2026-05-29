-- V27: D47 — remove the group_only registration state.
--
-- The complementary removal to V26 (which added the additive groups
-- columns). D47 consolidates the registration_state value set from
-- {preban, group_only, invited, vouched} down to
-- {preban, invited, vouched}: the group auto-registration path is gone,
-- so no user is ever minted in the 'group_only' state again.
--
-- Legacy disposition (schema.md §Identity and access "Migration (D47)"):
-- existing 'group_only' rows are mapped to the canonical pre-ban shape
-- ('preban' + is_banned=TRUE), NOT to 'invited'. These users were
-- auto-registered under the pre-D47 group-@mention path without ever
-- passing the DM invite gate, and held group-scope access only (DM was
-- explicitly denied). Transitioning them to 'invited' would GRANT DM
-- access they never held — a group-side registration bypass that
-- security.md §Invite-code registration forbids ("there is no
-- group-side registration bypass"). The pre-ban disposition blocks them
-- at intake in both scopes; an admin re-admits a contact via /unban
-- (which deletes the pre-ban row) followed by a fresh invite.
--
-- is_banned=TRUE is required, not registration_state='preban' alone: the
-- DM invite gate keys off row ABSENCE, so an existing row is blocked in
-- DM only by the step-4 BanCheck (SELECT is_banned), while
-- registration_state='preban' on its own triggers only the step-3 group
-- drop. Both must hold to block the row in both scopes.
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
-- value (V5 audit_log CHECK) that fits a schema-wide migration; action
-- carries no SQL CHECK so the D47-specific verb is raw text. actor_user_id
-- is left NULL (system migration, no admin actor); banned_by is likewise
-- left NULL on the converted rows for the same reason.

WITH updated AS (
    UPDATE users
       SET registration_state = 'preban',
           is_banned          = TRUE,
           banned_at          = NOW(),
           ban_reason         = 'D47: group_only registration path removed; re-admission requires /unban followed by a fresh DM invite.'
     WHERE registration_state = 'group_only'
    RETURNING 1
)
INSERT INTO audit_log (action, target_kind, target_id, details_json)
SELECT 'D47_GROUP_ONLY_PREBAN_CONVERSION',
       'system',
       'users.registration_state',
       jsonb_build_object('affected_rows', count(*))
  FROM updated
HAVING count(*) > 0;

ALTER TABLE users DROP CONSTRAINT users_registration_state_chk;
ALTER TABLE users ADD CONSTRAINT users_registration_state_chk
    CHECK (registration_state IN ('preban','invited','vouched'));
