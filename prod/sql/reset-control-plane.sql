-- prod/sql/reset-control-plane.sql — live-run "workflow reset" (M1-536).
--
-- Clears ALL control-plane rows (users / groups / invites / chat / audit /
-- provider state) while leaving the fetched-and-evaluated DATA-plane (source,
-- tag, post + partitions, embeddings, entities, references, price snapshots,
-- asset_config, bootstrap_meta, embedding_metadata) byte-for-byte intact, so
-- the live-e2e loop can re-run the chat-app workflow from "scratch" app state
-- without re-fetching real feeds. This is TEST-loop tooling, NEVER a production
-- procedure — it deliberately clears the append-only audit_log (Invariant 10 /
-- D34), which prod never permits.
--
-- Run this against an ALREADY-MIGRATED schema by the DATABASE OWNER role
-- (`infochat`); the NOLOGIN application roles (infochat_provider/collector)
-- lack the TRUNCATE / DELETE / DISABLE-TRIGGER grants this needs. The wrapper
-- prod/live-reset.sh reaches the owner the same way backup.sh reaches Postgres
-- (docker compose exec, PGPASSWORD=$INFOCHAT_DB_PASSWORD, -U infochat).
--
-- ── The cross-plane FK trap (why this is not `TRUNCATE users CASCADE`) ──
-- Exactly two DATA-plane tables reference the control-plane, both into users:
--   source.{added_by,deleted_by} -> users   (ON DELETE SET NULL)
--   tag.created_by               -> users   (RESTRICT / default NO ACTION)
-- `TRUNCATE users CASCADE` cascades on the FK-constraint GRAPH (not row values),
-- so it would truncate source and tag — and via post.source_id the entire post
-- table — destroying the very corpus we preserve. Nulling the columns first does
-- NOT change that (TRUNCATE ignores row values). The FK-safe mechanism instead:
--   1. truncate every control-plane table EXCEPT users (none is referenced from
--      outside that set, so a single no-CASCADE TRUNCATE is legal and touches no
--      data-plane row);
--   2. null tag.created_by (the RESTRICT child would otherwise block the delete);
--   3. DELETE FROM users (source's SET NULL auto-resolves; users.banned_by is a
--      self-FK with NO ACTION, satisfied because all rows go in one statement).
--
-- audit_log's append-only guard (trg_audit_log_no_delete/_update) is FOR EACH
-- ROW, so TRUNCATE — which fires only statement-level triggers — bypasses it; no
-- special handling is needed there. The last-admin DELETE guard
-- (trg_users_last_admin_delete) IS row-level and WOULD raise on the final admin,
-- so it is disabled for the duration of the users delete and re-enabled before
-- COMMIT. Its sibling trg_users_last_admin_update is intentionally left enabled:
-- this reset performs no UPDATE on users, so that trigger can never fire, and
-- disabling it would be dead defence.
--
-- The whole reset runs in ONE transaction. DDL (DISABLE/ENABLE TRIGGER) is
-- transactional in PostgreSQL, so any failure ROLLBACKs the trigger state back
-- to enabled — the database is never left with the last-admin guard off.
-- Idempotent: a second run truncates already-empty tables and deletes zero
-- users, committing identical state.

\set ON_ERROR_STOP on

BEGIN;

-- Row-level last-admin DELETE guard would abort on the final admin row.
-- Transactional: a ROLLBACK on any later failure restores it.
ALTER TABLE users DISABLE TRIGGER trg_users_last_admin_delete;

-- tag.created_by is RESTRICT: clear it so DELETE FROM users is not blocked.
-- The tag ROWS are preserved (data-plane); only this provenance pointer into
-- the about-to-be-deleted users is nulled. WHERE keeps the second run a no-op.
UPDATE tag SET created_by = NULL WHERE created_by IS NOT NULL;

-- Every control-plane table EXCEPT users. Nothing outside this set references
-- into it, so no CASCADE is needed or wanted (CASCADE is the trap above). All
-- are truncated simultaneously, so intra-set FKs (group_membership->groups,
-- chat_memory->chat_session, summary_cache->groups, ...) are satisfied.
TRUNCATE TABLE
    groups,
    group_membership,
    invite_code,
    invite_code_attempt,
    source_subscription,
    scope_tag,
    scope_preferences,
    chat_session,
    chat_message,
    chat_memory,
    summary_anchor,
    summary_cache,
    saved_post,
    audit_log,
    quarantine,
    admin_notification_state,
    provider_state,
    auto_joined_group;

-- Now safe: the only remaining references to users are source's SET-NULL columns
-- (auto-resolved) and tag.created_by (nulled above). banned_by is a users->users
-- self-FK (NO ACTION), satisfied by deleting all rows in one statement.
DELETE FROM users;

ALTER TABLE users ENABLE TRIGGER trg_users_last_admin_delete;

COMMIT;
