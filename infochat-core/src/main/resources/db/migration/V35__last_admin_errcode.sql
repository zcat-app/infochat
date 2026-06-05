-- V35: give the last-admin protection triggers a dedicated SQLSTATE.
--
-- V5's trg_last_admin_protection_update / _delete raise with no USING
-- ERRCODE, so their SQLSTATE is the generic P0001 (raise_exception) —
-- shared by every plpgsql RAISE in this schema. Handlers that need to
-- distinguish "last-admin guard fired" from any other failure had to
-- substring-match the exception message, which a reword silently
-- breaks. Both RAISE clauses now carry USING ERRCODE = 'IC001'
-- (custom SQLSTATE; class "IC" is unused by the SQL standard and by
-- PostgreSQL), so callers branch on SQLException.getSQLState() instead
-- of message text. The message text itself is unchanged.
--
-- A NEW migration rather than an in-place edit: a database that
-- already executed the earlier migrations would keep the old functions
-- permanently if the edit landed in place (`flyway repair` after the
-- checksum mismatch updates the checksum without re-running the
-- migration). The bodies below are the complete current definitions —
-- the update function from V24 (which added the ban-self GUC check on
-- top of V5) and the delete function from V5 — with only the
-- last_admin_protection RAISE clauses changed. The ban-self RAISE
-- keeps the default SQLSTATE: it is not the signal the last-admin
-- handlers match. Locking and counting logic are untouched (see V5
-- for the SHARE ROW EXCLUSIVE rationale).

CREATE OR REPLACE FUNCTION trg_last_admin_protection_update()
RETURNS TRIGGER AS $$
DECLARE
    remaining INT;
    v_actor   TEXT;
BEGIN
    LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;

    v_actor := current_setting('infochat.actor_id', TRUE);

    -- Ban-self prevention (V24 Finding 2): when the GUC identifies the
    -- actor and the UPDATE flips is_banned FALSE→TRUE on the actor's
    -- own row.
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
            RAISE EXCEPTION 'last_admin_protection: cannot leave the deployment with zero bot admins'
                USING ERRCODE = 'IC001';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION trg_last_admin_protection_delete()
RETURNS TRIGGER AS $$
DECLARE
    remaining INT;
BEGIN
    LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;

    IF OLD.is_admin = TRUE THEN
        SELECT count(*) INTO remaining
          FROM users
         WHERE is_admin = TRUE
           AND is_banned = FALSE
           AND id <> OLD.id;
        IF remaining < 1 THEN
            RAISE EXCEPTION 'last_admin_protection: cannot delete the last bot admin'
                USING ERRCODE = 'IC001';
        END IF;
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;
