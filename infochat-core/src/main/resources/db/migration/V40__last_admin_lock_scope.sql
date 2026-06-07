-- V40: scope the last-admin LOCK TABLE to admin-relevant updates.
--
-- V35's trg_last_admin_protection_update / _delete take
-- LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE as their FIRST
-- statement, and V5 wires the UPDATE trigger BEFORE UPDATE ON users
-- FOR EACH ROW with no WHEN clause. SHARE ROW EXCLUSIVE conflicts
-- with itself and with ROW EXCLUSIVE, so every users UPDATE — V15's
-- save_count bump on each /save, routine last_seen_at writes —
-- serialized globally against every other user-row write. The lock is
-- only needed when the row transition could shrink the effective
-- admin count (admin revocation, ban of an admin, delete of an
-- admin).
--
-- Both bodies below now evaluate their branch conditions BEFORE
-- taking the lock. That is safe: the conditions read only the OLD/NEW
-- row images, which are fixed inputs of the invocation — the lock
-- guards the COUNT over OTHER rows (Invariant 2's TOCTOU), not the
-- triggering row's own values. Two concurrent guarded transitions
-- still serialize at the lock exactly as before: both enter the
-- branch, one acquires the lock and commits, the other waits, then
-- counts the post-commit state and raises. The ban-self check (V24
-- Finding 2) compares only the actor GUC against the row images, so
-- it needs no table lock at all.
--
-- A NEW migration rather than an in-place edit of V35, for the same
-- reason V35 documents: an already-migrated database would keep the
-- old function bodies permanently if the edit landed in place. The
-- bodies are V35's complete current definitions with the LOCK TABLE
-- statements moved inside the admin-count branches; existing RAISE
-- messages and the IC001 ERRCODE are unchanged.
--
-- Two fail-closed checks added per the M1-190 redteam audit
-- (2026-06-07, both findings carried forward from V24/V35):
--   1. The serialize-at-the-lock argument above holds only when the
--      post-lock COUNT sees the post-commit state. READ COMMITTED
--      takes a fresh per-statement snapshot after the lock grant;
--      SERIALIZABLE detects the rw-antidependency between two
--      concurrent guarded transitions and aborts one. REPEATABLE
--      READ does neither — its transaction snapshot predates the
--      lock, so two concurrent revocations could each still count
--      the other admin and both commit. Guarded transitions under
--      REPEATABLE READ are therefore rejected outright.
--   2. The ban-self check can only run when the command layer set
--      infochat.actor_id; without it an admin self-ban passes
--      silently whenever another admin remains. Only /ban
--      (bot-admin-only) legally bans, and BanCommandHandler always
--      sets the GUC, so an actor-less ban of an admin row is a buggy
--      or out-of-band caller — rejected. Checked AFTER the count so
--      banning the LAST admin still reports IC001 (the stronger,
--      typed signal handlers branch on).

CREATE OR REPLACE FUNCTION trg_last_admin_protection_update()
RETURNS TRIGGER AS $$
DECLARE
    remaining INT;
    v_actor   TEXT;
BEGIN
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
        -- Fail closed under REPEATABLE READ; see the file header
        -- (redteam finding 1).
        IF current_setting('transaction_isolation') = 'repeatable read' THEN
            RAISE EXCEPTION 'last_admin_protection: admin-affecting UPDATE requires READ COMMITTED or SERIALIZABLE isolation (a REPEATABLE READ snapshot would make the admin count stale)';
        END IF;
        -- Serialize only the transitions that can shrink the admin
        -- count; see the file header for why the late lock is safe.
        LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;
        SELECT count(*) INTO remaining
          FROM users
         WHERE is_admin = TRUE
           AND is_banned = FALSE
           AND id <> NEW.id;
        IF remaining < 1 THEN
            RAISE EXCEPTION 'last_admin_protection: cannot leave the deployment with zero bot admins'
                USING ERRCODE = 'IC001';
        END IF;
        -- Fail closed on actor-less admin bans; see the file header
        -- (redteam finding 2). Must stay AFTER the count above.
        IF OLD.is_banned = FALSE AND NEW.is_banned = TRUE
           AND (v_actor IS NULL OR v_actor = '') THEN
            RAISE EXCEPTION 'banning an admin requires infochat.actor_id (the self-ban check cannot run without the actor identity)';
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
    IF OLD.is_admin = TRUE THEN
        -- Fail closed under REPEATABLE READ; see the file header
        -- (redteam finding 1).
        IF current_setting('transaction_isolation') = 'repeatable read' THEN
            RAISE EXCEPTION 'last_admin_protection: admin-row DELETE requires READ COMMITTED or SERIALIZABLE isolation (a REPEATABLE READ snapshot would make the admin count stale)';
        END IF;
        -- Serialize only admin-row deletes; non-admin deletes (e.g.
        -- the delete_preban_user carve-out) cannot shrink the count.
        LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;
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
