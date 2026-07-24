-- V62: narrow the Provider's grants on the identity/authz tables
-- (M1-672; 2026-07-22 audit finding CORE-1).
--
-- V5:391-400 gave infochat_provider table-level SELECT, INSERT, UPDATE on
-- users, groups, group_membership and invite_code, and no later migration
-- narrowed it. A SQL-injection foothold in the Provider could therefore
-- mint a bot admin, unban itself, force registration_state = 'vouched',
-- flip groups.approval_status past the D47 gate, or grant group-admin --
-- no trigger guards any of those transitions (the last-admin trigger
-- covers admin *removal*; the V24 ban-self and audit-actor checks read a
-- GUC a raw-SQL attacker simply does not set).
--
-- The repo already treats this scenario as in-model elsewhere: V31
-- column-scoped the `source` UPDATE, V50 added SECURITY DEFINER actor
-- checks, V10 keeps original_html unreachable "even with the worst-case
-- SQL injection". This migration applies the same treatment to the four
-- identity tables, where the payoff is full compromise.
--
-- Shape:
--   * the privilege COLUMNS (users.is_admin / is_banned + ban metadata /
--     registration_state, groups.approval_status,
--     group_membership.is_group_admin, and all of invite_code) become
--     unwritable by the role -- directly, at all;
--   * every legitimate transition on them moves into a narrow,
--     single-purpose SECURITY DEFINER routine below;
--   * the non-privilege columns the Provider legitimately writes keep
--     working via column-scoped GRANT UPDATE / GRANT INSERT (the V31
--     precedent);
--   * SELECT is retained on all four tables.
--
-- Residuals, recorded honestly in docs/spec/security.md §DB roles: the
-- four system-actor routines carry no DB-side gate (the role must reach
-- them with no human actor), and the admin-gated routines resolve their
-- actor from a GUC the calling role sets itself. Against an attacker who
-- already controls Provider SQL the gate raises the bar without being a
-- boundary. The control that actually bites is that the privilege
-- columns are no longer directly writable.
--
-- ORDERING IS LOAD-BEARING (see the REVOKE/GRANT block at the bottom):
-- routines first, then the table REVOKEs, then the column re-GRANTs.
-- Postgres drops the matching column privileges when the table-level
-- privilege of the same kind is revoked, so a column GRANT written above
-- its REVOKE is silently wiped -- and the resulting 42501 is
-- indistinguishable from a column missing from the grant list.

-- ---------------------------------------------------------------------
-- 0. Shared actor gate for the admin-gated routines.
-- ---------------------------------------------------------------------
-- Departs from V50, which takes p_actor_id as a parameter: resolving the
-- actor from the GUC is what makes refusal-when-unset expressible (and
-- testable) at all. The GUC is the same infochat.actor_id the V24/V40
-- triggers already read, so every caller that sets it for those triggers
-- needs no new plumbing.
--
-- Checks is_admin alone -- deliberately NOT V50's live-admin conjunction
-- (is_admin = TRUE AND is_banned = FALSE). Every handler routed through
-- these routines gates on is_admin alone today, and the V5/V24/V40
-- last-admin trigger path is reachable through BanCommandHandler and
-- RevokeAdminCommandHandler ONLY with an is_admin=TRUE AND is_banned=TRUE
-- actor. A live-admin conjunction would refuse that actor here, before
-- the UPDATE, so IC001 would never surface and both handlers' typed
-- last-admin replies would degrade to an internal error -- a user-visible
-- behaviour change. The conjunction also buys no defence against this
-- ticket's own threat model: a foothold that can set the GUC retains
-- SELECT on users and can name a non-banned admin's id as readily as a
-- banned one.
--
-- NEVER raises IC001. That SQLSTATE means last-admin protection and the
-- handlers branch on it; labelling an authorization refusal with it would
-- make BanCommandHandlerTest.banOfOnlyAdminSurfacesLastAdminError and
-- RevokeAdminCommandHandlerTest.revokeLastAdminTriggerFiresAndRollsBack
-- pass for the wrong reason. Plain RAISE EXCEPTION surfaces as P0001.
CREATE OR REPLACE FUNCTION require_bot_admin_actor()
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_actor    TEXT;
    v_actor_id UUID;
BEGIN
    v_actor := current_setting('infochat.actor_id', TRUE);
    IF v_actor IS NULL OR v_actor = '' THEN
        RAISE EXCEPTION 'actor is not a bot admin (infochat.actor_id is unset)';
    END IF;
    v_actor_id := v_actor::UUID;
    IF NOT EXISTS (SELECT 1 FROM users WHERE id = v_actor_id AND is_admin = TRUE) THEN
        RAISE EXCEPTION 'actor is not a bot admin (id=%)', v_actor_id;
    END IF;
    RETURN v_actor_id;
END;
$$;

-- ---------------------------------------------------------------------
-- 1. System-actor routines -- NO admin gate, by necessity.
-- ---------------------------------------------------------------------
-- The in-repo precedent for a no-human-actor arm is V24's
-- trg_audit_log_actor_check (V24:94-115), whose comments document the
-- GUC-unset and NULL-actor allowances. It is NOT V50: all three V50
-- routines raise unconditionally on a non-admin actor, so V50 has no
-- system-actor arm to copy.
--
-- Each of these four runs on a path that has no admin to name:
-- AdminBootstrap runs at every boot (before any admin need exist),
-- SimpleXAdminClaim's proof is the claim token, InviteCodeConsumer's is
-- the invite-code match, and GroupAutoPromoteService's actor is BY
-- DEFINITION a non-admin (D47 first-mention auto-promote writes the
-- promoted user as the actor).

-- Bootstrap-admin ensure (AdminBootstrap.ENSURE_ADMIN_SQL). Returns the
-- users id exactly when this call changed something -- the INSERT arm
-- fired, or the conflict arm promoted a non-admin row -- and NULL when
-- the contact was already an admin. That distinction is the caller's
-- audit gate: re-running startup with unchanged configuration must write
-- no duplicate BOOTSTRAP_ADMIN rows.
CREATE OR REPLACE FUNCTION bootstrap_ensure_admin(p_adapter TEXT, p_contact_id TEXT)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_id UUID;
BEGIN
    INSERT INTO users (adapter, contact_id, is_admin, is_banned,
                       registration_state, probation_until)
    VALUES (p_adapter, p_contact_id, TRUE, FALSE, 'vouched', NULL)
    ON CONFLICT (adapter, contact_id)
    DO UPDATE SET is_admin = TRUE WHERE users.is_admin = FALSE
    RETURNING id INTO v_id;
    RETURN v_id;
END;
$$;

-- SimpleX first-admin claim (SimpleXAdminClaim.CLAIM_ADMIN_SQL). The
-- WHERE NOT EXISTS arm is the single-use gate; ON CONFLICT DO NOTHING is
-- defense-in-depth against the contact already having a row. A returned
-- id means THIS presentation established the SimpleX admin.
CREATE OR REPLACE FUNCTION claim_simplex_admin(p_adapter TEXT, p_contact_id TEXT)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_id UUID;
BEGIN
    INSERT INTO users (adapter, contact_id, is_admin, is_banned,
                       registration_state, probation_until)
    SELECT p_adapter, p_contact_id, TRUE, FALSE, 'vouched', NULL
    WHERE NOT EXISTS (
        SELECT 1 FROM users WHERE adapter = p_adapter AND is_admin = TRUE)
    ON CONFLICT (adapter, contact_id) DO NOTHING
    RETURNING id INTO v_id;
    RETURN v_id;
END;
$$;

-- Invite consume (InviteCodeConsumer.CONSUME_INVITE_SQL). The presented
-- code IS the proof of authority, so there is no actor to check. Returns
-- the invite_code id on a successful consume, NULL when the code matched
-- nothing PENDING/unexpired/adapter-scoped/contact-eligible.
-- p_contact_id fills both the used_by_contact_id write and the
-- expected_contact_id comparison, exactly as the replaced statement bound
-- the same Java value to both slots.
CREATE OR REPLACE FUNCTION consume_invite_code(p_contact_id TEXT, p_code UUID, p_adapter TEXT)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_id UUID;
BEGIN
    UPDATE invite_code
       SET status = 'USED', used_at = NOW(), used_by_contact_id = p_contact_id
     WHERE code = p_code
       AND status = 'PENDING'
       AND (expires_at IS NULL OR expires_at > NOW())
       AND adapter = p_adapter
       AND (invite_type = 'OPEN_ADAPTER' OR expected_contact_id = p_contact_id)
    RETURNING id INTO v_id;
    RETURN v_id;
END;
$$;

-- New 'invited' users row for a consumed invite
-- (InviteCodeConsumer.INSERT_USER_SQL). ON CONFLICT DO NOTHING guards a
-- concurrent registration on the same (adapter, contact_id); the caller
-- reads the winning row back on NULL.
CREATE OR REPLACE FUNCTION insert_invited_user(p_adapter TEXT, p_contact_id TEXT,
                                               p_probation_until TIMESTAMPTZ)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_id UUID;
BEGIN
    INSERT INTO users (adapter, contact_id, is_admin, registration_state, probation_until)
    VALUES (p_adapter, p_contact_id, FALSE, 'invited', p_probation_until)
    ON CONFLICT (adapter, contact_id) DO NOTHING
    RETURNING id INTO v_id;
    RETURN v_id;
END;
$$;

-- D47 first-mention auto-promote (GroupAutoPromoteService.AUTO_PROMOTE_SQL).
-- Returns the affected row count so the caller keeps its `!= 1 =>
-- rollback, return false` leg. Deliberately carries NO exception
-- handler: the 23505 unique violation from the one_admin_per_group
-- partial index is the caller's race guard and must propagate out of the
-- call untouched. A plpgsql BEGIN ... EXCEPTION block would open a
-- subtransaction and re-raise under a different SQLSTATE.
CREATE OR REPLACE FUNCTION auto_promote_group_admin(p_group_id UUID, p_user_id UUID)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_count INTEGER;
BEGIN
    INSERT INTO group_membership (group_id, user_id, is_group_admin)
    VALUES (p_group_id, p_user_id, TRUE)
    ON CONFLICT (group_id, user_id) DO UPDATE
    SET is_group_admin = TRUE
    WHERE group_membership.removed_at IS NULL
      AND group_membership.is_group_admin = FALSE;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$;

-- ---------------------------------------------------------------------
-- 2. Admin-gated routines.
-- ---------------------------------------------------------------------
-- Each opens with require_bot_admin_actor(). None issues COMMIT or
-- ROLLBACK -- they run inside the caller's transaction, matching all
-- three V50 routines, so the caller's existing audit-before-effect
-- ordering and rollback-on-fault behaviour are preserved exactly.
--
-- No routine writes an audit row. Every affected caller already
-- pre-writes its row through AuditLogWriter + RedactionHook (Invariant 7)
-- and the call joins that same transaction, so the existing row and the
-- mutation stay atomic. A routine-side INSERT would double the rows the
-- handler tests count and would bypass the Java redaction hook.

-- /ban, unknown target: the pre-ban placeholder row. The caller supplies
-- the id so the audit row it pre-wrote can already reference it.
CREATE OR REPLACE FUNCTION insert_preban_user(p_id UUID, p_adapter TEXT, p_contact_id TEXT,
                                              p_banned_by UUID, p_ban_reason TEXT)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    PERFORM require_bot_admin_actor();
    INSERT INTO users (id, adapter, contact_id, is_admin, is_banned,
                       registration_state, banned_at, banned_by, ban_reason)
    VALUES (p_id, p_adapter, p_contact_id, FALSE, TRUE,
            'preban', NOW(), p_banned_by, p_ban_reason);
END;
$$;

-- /ban, known target.
CREATE OR REPLACE FUNCTION ban_known_user(p_user_id UUID, p_banned_by UUID, p_ban_reason TEXT)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    PERFORM require_bot_admin_actor();
    UPDATE users
       SET is_banned = TRUE, banned_at = NOW(),
           banned_by = p_banned_by, ban_reason = p_ban_reason
     WHERE id = p_user_id;
END;
$$;

-- /unban, non-preban target.
CREATE OR REPLACE FUNCTION unban_user(p_user_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    PERFORM require_bot_admin_actor();
    UPDATE users
       SET is_banned = FALSE, banned_at = NULL,
           banned_by = NULL, ban_reason = NULL
     WHERE id = p_user_id;
END;
$$;

-- /grant-admin.
CREATE OR REPLACE FUNCTION grant_bot_admin(p_user_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    PERFORM require_bot_admin_actor();
    UPDATE users SET is_admin = TRUE WHERE id = p_user_id;
END;
$$;

-- /revoke-admin. The V40 last-admin trigger fires on this UPDATE and its
-- IC001 propagates out of the call to the handler's typed catch.
CREATE OR REPLACE FUNCTION revoke_bot_admin(p_user_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    PERFORM require_bot_admin_actor();
    UPDATE users SET is_admin = FALSE WHERE id = p_user_id;
END;
$$;

-- Group-admin flag transitions. Each routine is a 1:1 replacement of one
-- pre-existing statement, deliberately NOT a merged "move the admin
-- slot" operation: promote_group_admin must keep raising 23505 from the
-- one_admin_per_group partial index when the slot is already taken,
-- because GroupMembershipRepository.promoteToAdmin reports exactly that
-- as its false return.

-- /promote step 2, and GroupMembershipRepository.promoteToAdmin.
CREATE OR REPLACE FUNCTION promote_group_admin(p_group_id UUID, p_user_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    PERFORM require_bot_admin_actor();
    UPDATE group_membership SET is_group_admin = TRUE
     WHERE group_id = p_group_id AND user_id = p_user_id AND removed_at IS NULL;
END;
$$;

-- /promote step 1: clear whoever currently holds the group's admin slot,
-- freeing the partial-index slot the promote above then takes.
CREATE OR REPLACE FUNCTION demote_group_admins(p_group_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    PERFORM require_bot_admin_actor();
    UPDATE group_membership SET is_group_admin = FALSE
     WHERE group_id = p_group_id AND is_group_admin = TRUE AND removed_at IS NULL;
END;
$$;

-- /demote, and GroupMembershipRepository.demoteAdmin.
CREATE OR REPLACE FUNCTION demote_group_admin(p_group_id UUID, p_user_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    PERFORM require_bot_admin_actor();
    UPDATE group_membership SET is_group_admin = FALSE
     WHERE group_id = p_group_id AND user_id = p_user_id AND removed_at IS NULL;
END;
$$;

-- /invite mint.
CREATE OR REPLACE FUNCTION mint_invite_code(p_code UUID, p_invite_type TEXT, p_adapter TEXT,
                                            p_expected_contact_id TEXT, p_created_by UUID,
                                            p_expires_at TIMESTAMPTZ)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    PERFORM require_bot_admin_actor();
    INSERT INTO invite_code (code, invite_type, adapter, expected_contact_id,
                             status, created_by, created_at, expires_at)
    VALUES (p_code, p_invite_type, p_adapter, p_expected_contact_id,
            'PENDING', p_created_by, NOW(), p_expires_at);
END;
$$;

-- Row locking split out from the revokes below, in BOTH invite paths.
-- Revoking the table-level UPDATE also revokes plain FOR UPDATE reads on
-- invite_code (a row lock is a write-intent), so the locks have to move
-- in here. They cannot merge into the revoke routines: both callers
-- pre-write one INVITE_REVOKE audit row per locked id BETWEEN the lock
-- and the revoke (BanCommandHandler.executeBan :317-335,
-- InviteCommandHandler.executeRevoke :816-829). Merging would put the
-- effect before the audit -- an Invariant 7 violation.

-- /invite revoke, step 1: lock the single PENDING row and hand its id
-- back for the audit row. NULL means nothing was PENDING under the code.
CREATE OR REPLACE FUNCTION lock_pending_invite(p_code UUID)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_id UUID;
BEGIN
    PERFORM require_bot_admin_actor();
    SELECT id INTO v_id FROM invite_code
     WHERE code = p_code AND status = 'PENDING'
       FOR UPDATE;
    RETURN v_id;
END;
$$;

-- /invite revoke, step 2. Returns the row count: the caller treats 0 as
-- the (unreachable, since the row is locked) race guard that keeps the
-- audit-row-iff-mutation invariant.
CREATE OR REPLACE FUNCTION revoke_invite_code(p_code UUID)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_count INTEGER;
BEGIN
    PERFORM require_bot_admin_actor();
    UPDATE invite_code SET status = 'REVOKED'
     WHERE code = p_code AND status = 'PENDING';
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$;

-- /ban step 1: lock every PENDING contact-bound invite for the target and
-- return their ids, so the caller can pre-write one INVITE_REVOKE audit
-- row each. The locks are held until the caller's commit, so no other
-- transaction can flip an invite out from under the pre-written rows.
CREATE OR REPLACE FUNCTION lock_pending_contact_bound_invites(p_adapter TEXT,
                                                              p_expected_contact_id TEXT)
RETURNS SETOF UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    PERFORM require_bot_admin_actor();
    RETURN QUERY
    SELECT id FROM invite_code
     WHERE adapter = p_adapter
       AND invite_type = 'CONTACT_BOUND'
       AND expected_contact_id = p_expected_contact_id
       AND status = 'PENDING'
       FOR UPDATE;
END;
$$;

-- /ban step 2: revoke the invites locked above.
CREATE OR REPLACE FUNCTION revoke_contact_bound_invites(p_adapter TEXT,
                                                        p_expected_contact_id TEXT)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    PERFORM require_bot_admin_actor();
    UPDATE invite_code SET status = 'REVOKED'
     WHERE adapter = p_adapter
       AND invite_type = 'CONTACT_BOUND'
       AND expected_contact_id = p_expected_contact_id
       AND status = 'PENDING';
END;
$$;

-- /approve-group + /reject-group. Returns TRUE when one row moved, FALSE
-- on the no-op (already in the target state) path -- the caller
-- distinguishes those without a second SELECT.
CREATE OR REPLACE FUNCTION set_group_approval_status(p_group_id UUID, p_new_status TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_count INTEGER;
BEGIN
    PERFORM require_bot_admin_actor();
    UPDATE groups SET approval_status = p_new_status
     WHERE id = p_group_id AND approval_status <> p_new_status;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count = 1;
END;
$$;

-- ---------------------------------------------------------------------
-- 3. Routine ACLs.
-- ---------------------------------------------------------------------
-- Postgres grants EXECUTE to PUBLIC by default on every new routine.
-- Without the REVOKE, infochat_collector -- which holds SELECT-only on
-- all four tables -- would silently gain the ability to mint bot admins,
-- widening the exact surface this migration narrows. V5:379-380 and
-- V39:16-17 are the repo's pattern.
REVOKE ALL ON FUNCTION require_bot_admin_actor() FROM PUBLIC;
REVOKE ALL ON FUNCTION bootstrap_ensure_admin(TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION claim_simplex_admin(TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION consume_invite_code(TEXT, UUID, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION insert_invited_user(TEXT, TEXT, TIMESTAMPTZ) FROM PUBLIC;
REVOKE ALL ON FUNCTION auto_promote_group_admin(UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION insert_preban_user(UUID, TEXT, TEXT, UUID, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION ban_known_user(UUID, UUID, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION unban_user(UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION grant_bot_admin(UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION revoke_bot_admin(UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION promote_group_admin(UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION demote_group_admins(UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION demote_group_admin(UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION mint_invite_code(UUID, TEXT, TEXT, TEXT, UUID, TIMESTAMPTZ) FROM PUBLIC;
REVOKE ALL ON FUNCTION lock_pending_invite(UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION revoke_invite_code(UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION lock_pending_contact_bound_invites(TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION revoke_contact_bound_invites(TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION set_group_approval_status(UUID, TEXT) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION bootstrap_ensure_admin(TEXT, TEXT) TO infochat_provider;
GRANT EXECUTE ON FUNCTION claim_simplex_admin(TEXT, TEXT) TO infochat_provider;
GRANT EXECUTE ON FUNCTION consume_invite_code(TEXT, UUID, TEXT) TO infochat_provider;
GRANT EXECUTE ON FUNCTION insert_invited_user(TEXT, TEXT, TIMESTAMPTZ) TO infochat_provider;
GRANT EXECUTE ON FUNCTION auto_promote_group_admin(UUID, UUID) TO infochat_provider;
GRANT EXECUTE ON FUNCTION insert_preban_user(UUID, TEXT, TEXT, UUID, TEXT) TO infochat_provider;
GRANT EXECUTE ON FUNCTION ban_known_user(UUID, UUID, TEXT) TO infochat_provider;
GRANT EXECUTE ON FUNCTION unban_user(UUID) TO infochat_provider;
GRANT EXECUTE ON FUNCTION grant_bot_admin(UUID) TO infochat_provider;
GRANT EXECUTE ON FUNCTION revoke_bot_admin(UUID) TO infochat_provider;
GRANT EXECUTE ON FUNCTION promote_group_admin(UUID, UUID) TO infochat_provider;
GRANT EXECUTE ON FUNCTION demote_group_admins(UUID) TO infochat_provider;
GRANT EXECUTE ON FUNCTION demote_group_admin(UUID, UUID) TO infochat_provider;
GRANT EXECUTE ON FUNCTION mint_invite_code(UUID, TEXT, TEXT, TEXT, UUID, TIMESTAMPTZ) TO infochat_provider;
GRANT EXECUTE ON FUNCTION lock_pending_invite(UUID) TO infochat_provider;
GRANT EXECUTE ON FUNCTION revoke_invite_code(UUID) TO infochat_provider;
GRANT EXECUTE ON FUNCTION lock_pending_contact_bound_invites(TEXT, TEXT) TO infochat_provider;
GRANT EXECUTE ON FUNCTION revoke_contact_bound_invites(TEXT, TEXT) TO infochat_provider;
GRANT EXECUTE ON FUNCTION set_group_approval_status(UUID, TEXT) TO infochat_provider;

-- require_bot_admin_actor() is deliberately NOT granted to
-- infochat_provider: it is an internal helper the routines above call as
-- their definer, and a role that could call it directly would learn
-- nothing it cannot already read via SELECT on users.

-- ---------------------------------------------------------------------
-- 4. Narrow the table grants. MUST come after every GRANT EXECUTE above
--    and MUST keep REVOKE-before-GRANT order within this block.
-- ---------------------------------------------------------------------
-- SELECT stays on all four tables. DELETE was never granted (V5:384-386)
-- and is not granted here.

REVOKE INSERT, UPDATE ON users            FROM infochat_provider;
REVOKE INSERT, UPDATE ON groups           FROM infochat_provider;
REVOKE INSERT, UPDATE ON group_membership FROM infochat_provider;
REVOKE INSERT, UPDATE ON invite_code      FROM infochat_provider;

-- users: no INSERT re-grant. All four INSERT sites set a privilege
-- column at insert time (pre-ban ban metadata, the two admin claims, the
-- invited-user registration_state) and are routed through the routines
-- above.
--   probation_until -- /vouch (VouchCommandHandler) and the probation
--     expiry sweep (ProbationCheck).
--   save_count      -- no Java writer. Required because V15's
--     trg_saved_post_count is SECURITY INVOKER, so /save and /unsave
--     drive its UPDATE under the caller's role.
GRANT UPDATE (probation_until, save_count) ON users TO infochat_provider;

-- groups: timezone (/timezone), digest_enabled (/digest),
-- removed_at (soft-remove + restore). approval_status is routine-only.
-- member_count is not a column -- it is a computed COUNT in
-- GroupRepository.LIST_GROUPS_PAGE -- so there is nothing to grant.
GRANT UPDATE (timezone, digest_enabled, removed_at) ON groups TO infochat_provider;
GRANT INSERT (adapter, upstream_group_id, activated_by) ON groups TO infochat_provider;

-- group_membership: removed_at (the user-left path). is_group_admin is
-- routine-only. The V5 trigger trg_group_membership_clear_admin still
-- clears is_group_admin when removed_at goes NULL -> non-NULL: Postgres
-- checks column UPDATE privileges against the statement's SET list, not
-- against a BEFORE trigger's assignment to NEW.
GRANT UPDATE (removed_at) ON group_membership TO infochat_provider;
GRANT INSERT (group_id, user_id) ON group_membership TO infochat_provider;

-- invite_code: no INSERT, no UPDATE. Mint, revoke and consume are all
-- routine-only, and with zero granted UPDATE columns the role also loses
-- plain FOR UPDATE reads on this table -- which is why the two row-lock
-- reads moved into routines above.
