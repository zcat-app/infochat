-- V50: SECURITY DEFINER actor checks reject banned admins
-- (deep-review v5 U-08; M1-290). U-56 column drop rides along.
--
-- delete_preban_user (latest body V45), approve_quarantine and
-- reject_quarantine (latest bodies V48) gate their actor on
-- is_admin = TRUE only. The spec defines a *live* admin as the
-- conjunction is_admin = TRUE AND is_banned = FALSE, and V40's
-- last-admin triggers already implement it. Under the SQL-injection-
-- foothold threat model these SECURITY DEFINER actor checks were added
-- for (an attacker who can call the procedures but not bypass them), a
-- banned admin's id remains a usable actor — a defense-in-depth gap.
-- This redeclares all three with the conjunction.
--
-- CREATE OR REPLACE carries each prior body forward verbatim (V45's
-- audit denormalization, V48's audit-before-effect reorder, the
-- SET search_path pins, the NOTIFY payloads, the stage2_failed clear).
-- The parameter lists stay exactly (UUID, UUID) so each statement
-- replaces rather than overloads, and existing ACLs survive. Only the
-- actor EXISTS predicate changes — a banned admin now fails the same
-- check a non-admin fails and gets the identical 'actor is not a bot
-- admin' error shape.

-- 1. Redeclare delete_preban_user (V45 body; actor must be a live admin).
CREATE OR REPLACE PROCEDURE delete_preban_user(p_user_id UUID, p_actor_id UUID)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_state TEXT;
BEGIN
    -- Actor must exist and be a live (non-banned) bot admin.
    IF NOT EXISTS (SELECT 1 FROM users WHERE id = p_actor_id AND is_admin = TRUE AND is_banned = FALSE) THEN
        RAISE EXCEPTION 'actor is not a bot admin (id=%)', p_actor_id;
    END IF;

    SELECT registration_state INTO v_state FROM users WHERE id = p_user_id FOR UPDATE;
    IF v_state IS DISTINCT FROM 'preban' THEN
        RAISE EXCEPTION 'delete_preban_user: row % is not in preban state (%)', p_user_id, v_state;
    END IF;

    -- Audit-before-effect (Invariant 7). actor_contact_id / actor_adapter
    -- are denormalized at write time (schema.md §Entities) so the acting
    -- admin stays attributable after their contact id/adapter rotates and
    -- after this procedure DELETEs the target row, at which point read-time
    -- derivation could no longer reach them. The actor-admin check above
    -- guarantees the JOIN on users a yields exactly one row.
    INSERT INTO audit_log (
        actor_user_id, actor_contact_id, actor_adapter,
        action, target_kind, target_id, target_contact_id,
        scope_id, request_id, details_json
    )
    SELECT p_actor_id, a.contact_id, a.adapter,
           'UNBAN_PREBAN_DELETE', 'user', u.id::TEXT, u.contact_id,
           NULL, current_setting('infochat.request_id', TRUE), '{}'::JSONB
      FROM users u
      JOIN users a ON a.id = p_actor_id
     WHERE u.id = p_user_id;

    DELETE FROM users WHERE id = p_user_id AND registration_state = 'preban';
END;
$$;

-- 2. Redeclare approve_quarantine (V48 body; actor must be a live admin).
CREATE OR REPLACE FUNCTION approve_quarantine(p_quarantine_id UUID, p_actor_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_post_id         UUID;
    v_post_fetched_at TIMESTAMPTZ;
    v_placeholder_id  TEXT;
    v_original_html   TEXT;
    v_status          TEXT;
    v_ready_at        TIMESTAMPTZ;
BEGIN
    -- Actor must exist and be a live (non-banned) bot admin.
    IF NOT EXISTS (SELECT 1 FROM users WHERE id = p_actor_id AND is_admin = TRUE AND is_banned = FALSE) THEN
        RAISE EXCEPTION 'actor is not a bot admin (id=%)', p_actor_id;
    END IF;

    SELECT post_id, post_fetched_at, placeholder_id, original_html, status
      INTO v_post_id, v_post_fetched_at, v_placeholder_id, v_original_html, v_status
      FROM quarantine
     WHERE id = p_quarantine_id
       FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'quarantine row % not found', p_quarantine_id;
    END IF;

    IF v_status NOT IN ('PENDING', 'BENIGN_CLOSED') THEN
        RAISE EXCEPTION 'quarantine row % has status %; expected PENDING or BENIGN_CLOSED',
            p_quarantine_id, v_status;
    END IF;

    -- Audit-before-effect (Invariant 7): the audit row lands before the
    -- quarantine/post mutations. v_post_id was captured at the FOR UPDATE
    -- above, so the details_json payload is identical to the prior body.
    INSERT INTO audit_log (actor_user_id, actor_contact_id, actor_adapter,
                           action, target_kind, target_id, details_json)
    SELECT p_actor_id, a.contact_id, a.adapter,
           'APPROVE_QUARANTINE', 'quarantine', p_quarantine_id::TEXT,
           jsonb_build_object('post_id', v_post_id::TEXT)
      FROM users a
     WHERE a.id = p_actor_id;

    UPDATE quarantine
       SET status = 'APPROVED', updated_at = now(), reviewed_by = p_actor_id
     WHERE id = p_quarantine_id;

    v_ready_at := now();
    UPDATE post
       SET body = replace(body, '[REDACTED:' || v_placeholder_id || ']', v_original_html),
           status = 'READY',
           ready_at = v_ready_at,
           status_changed_at = v_ready_at,
           -- Terminal admin review: drop the post out of the re-eval
           -- queue's infra-failure branch so it can never be re-hidden.
           stage2_failed = FALSE
     WHERE id = v_post_id AND fetched_at = v_post_fetched_at;

    -- Same payload shape as ReadyPromoter so the Provider's
    -- NewPostListener picks the released post up via the standard
    -- cursor.
    PERFORM pg_notify('new_post',
        jsonb_build_object('ready_at', v_ready_at, 'post_id', v_post_id)::text);

    -- Tagged quarantine_review payload — the cursor's reviewed_at is
    -- the quarantine row's updated_at set above, in this same
    -- transaction (architecture.md §Inter-service communication).
    PERFORM pg_notify('quarantine_review',
        jsonb_build_object('target_kind', 'quarantine',
                           'target_id', p_quarantine_id,
                           'new_status', 'APPROVED')::text);
END;
$$;

-- 3. Redeclare reject_quarantine (V48 body; actor must be a live admin).
CREATE OR REPLACE FUNCTION reject_quarantine(p_quarantine_id UUID, p_actor_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_status TEXT;
BEGIN
    -- Actor must exist and be a live (non-banned) bot admin.
    IF NOT EXISTS (SELECT 1 FROM users WHERE id = p_actor_id AND is_admin = TRUE AND is_banned = FALSE) THEN
        RAISE EXCEPTION 'actor is not a bot admin (id=%)', p_actor_id;
    END IF;

    SELECT status INTO v_status
      FROM quarantine
     WHERE id = p_quarantine_id
       FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'quarantine row % not found', p_quarantine_id;
    END IF;

    IF v_status NOT IN ('PENDING', 'BENIGN_CLOSED') THEN
        RAISE EXCEPTION 'quarantine row % has status %; expected PENDING or BENIGN_CLOSED',
            p_quarantine_id, v_status;
    END IF;

    -- Audit-before-effect (Invariant 7): the audit row lands before the
    -- quarantine UPDATE.
    INSERT INTO audit_log (actor_user_id, actor_contact_id, actor_adapter,
                           action, target_kind, target_id)
    SELECT p_actor_id, a.contact_id, a.adapter,
           'REJECT_QUARANTINE', 'quarantine', p_quarantine_id::TEXT
      FROM users a
     WHERE a.id = p_actor_id;

    UPDATE quarantine
       SET status = 'REJECTED', updated_at = now(), reviewed_by = p_actor_id
     WHERE id = p_quarantine_id;

    PERFORM pg_notify('quarantine_review',
        jsonb_build_object('target_kind', 'quarantine',
                           'target_id', p_quarantine_id,
                           'new_status', 'REJECTED')::text);
END;
$$;

-- 4. U-56 rider: drop the dead column scope_preferences.digest_enabled
-- (V7:89). It has zero readers/writers — the live per-group digest flag
-- is groups.digest_enabled (V44). Recorded in
-- docs/plan/m1/drafts/v4-deep-review-backlog.md as "bundle with the next
-- schema-touching ticket".
ALTER TABLE scope_preferences DROP COLUMN digest_enabled;
