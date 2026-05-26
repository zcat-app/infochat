-- V24: Remediates five M1-008a redteam findings on the identity/audit schema.
--
-- Finding 1 (AUDIT-EVASION) + Finding 3 (PERM-ESCAL): delete_preban_user
--   silently skips the audit row when p_actor_id has no matching users row
--   (the JOIN returns zero rows) and does not verify the actor is an admin.
--   Fix: explicit existence + is_admin check before the audit INSERT.
--
-- Finding 4 (PERM-ESCAL): delete_preban_user is SECURITY DEFINER without
--   a pinned search_path.
--   Fix: proc-level SET search_path = pg_catalog, public.
--
-- Finding 2 (PERM-ESCAL): trg_last_admin_protection_update does not know
--   who the actor is, so cannot enforce "cannot ban self."
--   Fix: read session GUC infochat.actor_id; raise when actor = target on
--   a FALSE→TRUE is_banned transition.
--
-- Finding 5 (AUDIT-EVASION): any code with INSERT on audit_log can mint
--   rows attributing arbitrary actions to arbitrary actors.
--   Fix: BEFORE INSERT trigger compares NEW.actor_user_id to the session GUC.

-- 1. Redeclare delete_preban_user with search_path pin + actor checks.
CREATE OR REPLACE PROCEDURE delete_preban_user(p_user_id UUID, p_actor_id UUID)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_state TEXT;
BEGIN
    -- Actor must exist and be a bot admin (Findings 1 + 3).
    IF NOT EXISTS (SELECT 1 FROM users WHERE id = p_actor_id AND is_admin = TRUE) THEN
        RAISE EXCEPTION 'actor is not a bot admin (id=%)', p_actor_id;
    END IF;

    SELECT registration_state INTO v_state FROM users WHERE id = p_user_id FOR UPDATE;
    IF v_state IS DISTINCT FROM 'preban' THEN
        RAISE EXCEPTION 'delete_preban_user: row % is not in preban state (%)', p_user_id, v_state;
    END IF;

    -- Audit-before-effect (Invariant 7). The actor existence is guaranteed
    -- by the check above, so no JOIN needed; actor_contact_id and
    -- actor_adapter are omitted — derivable from actor_user_id by any
    -- reader that needs them, avoiding a second SELECT round-trip.
    INSERT INTO audit_log (
        actor_user_id,
        action, target_kind, target_id, target_contact_id,
        scope_id, request_id, details_json
    )
    SELECT p_actor_id,
           'UNBAN_PREBAN_DELETE', 'user', u.id::TEXT, u.contact_id,
           NULL, current_setting('infochat.request_id', TRUE), '{}'::JSONB
      FROM users u
     WHERE u.id = p_user_id;

    DELETE FROM users WHERE id = p_user_id AND registration_state = 'preban';
END;
$$;

-- 2. Redeclare trg_last_admin_protection_update with ban-self check.
CREATE OR REPLACE FUNCTION trg_last_admin_protection_update()
RETURNS TRIGGER AS $$
DECLARE
    remaining INT;
    v_actor   TEXT;
BEGIN
    LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;

    v_actor := current_setting('infochat.actor_id', TRUE);

    -- Ban-self prevention (Finding 2): when the GUC identifies the actor
    -- and the UPDATE flips is_banned FALSE→TRUE on the actor's own row.
    IF v_actor IS NOT NULL AND v_actor <> ''
       AND v_actor::UUID = NEW.id
       AND OLD.is_banned = FALSE AND NEW.is_banned = TRUE THEN
        RAISE EXCEPTION 'cannot ban self (actor=%)', v_actor;
    END IF;

    IF (OLD.is_admin = TRUE AND NEW.is_admin = FALSE)
       OR (OLD.is_banned = FALSE AND NEW.is_banned = TRUE AND OLD.is_admin = TRUE) THEN
        SELECT count(*) INTO remaining
          FROM users
         WHERE is_admin = TRUE
           AND is_banned = FALSE
           AND id <> NEW.id;
        IF remaining < 1 THEN
            RAISE EXCEPTION 'last_admin_protection: cannot leave the deployment with zero bot admins';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 3. Audit-row actor integrity trigger (Finding 5).
CREATE OR REPLACE FUNCTION trg_audit_log_actor_check()
RETURNS TRIGGER AS $$
DECLARE
    v_actor TEXT;
BEGIN
    v_actor := current_setting('infochat.actor_id', TRUE);

    -- When the GUC is set and the row claims a human actor, they must match.
    -- GUC-unset path: allowed (preserves bootstrap-admin and pre-wiring paths).
    -- System-actor path (NEW.actor_user_id IS NULL): allowed unconditionally.
    IF v_actor IS NOT NULL AND v_actor <> ''
       AND NEW.actor_user_id IS NOT NULL
       AND NEW.actor_user_id IS DISTINCT FROM v_actor::UUID THEN
        RAISE EXCEPTION 'audit_log actor mismatch (claimed=%, session=%)', NEW.actor_user_id, v_actor;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_actor_check BEFORE INSERT ON audit_log
    FOR EACH ROW EXECUTE FUNCTION trg_audit_log_actor_check();
