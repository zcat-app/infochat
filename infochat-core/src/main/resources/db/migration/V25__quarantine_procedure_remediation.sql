-- V25: Harden approve_quarantine and reject_quarantine with search_path
-- pinning and actor-admin verification. Same vulnerability class as V24's
-- delete_preban_user remediation (M1-008a redteam Findings 1, 3, 4).
--
-- Both procedures are SECURITY DEFINER (run as the migration owner).
-- Without a pinned search_path, a caller who manipulates their session
-- search_path can shadow the quarantine, post, or audit_log tables.
-- Without an actor-admin check, any caller with EXECUTE can pass an
-- arbitrary UUID as the actor.

-- 1. Redeclare approve_quarantine with search_path pin + actor check.
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
    -- Actor must exist and be a bot admin.
    IF NOT EXISTS (SELECT 1 FROM users WHERE id = p_actor_id AND is_admin = TRUE) THEN
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

    UPDATE quarantine
       SET status = 'APPROVED', updated_at = now(), reviewed_by = p_actor_id
     WHERE id = p_quarantine_id;

    v_ready_at := now();
    UPDATE post
       SET body = replace(body, '[REDACTED:' || v_placeholder_id || ']', v_original_html),
           status = 'READY',
           ready_at = v_ready_at,
           status_changed_at = v_ready_at
     WHERE id = v_post_id AND fetched_at = v_post_fetched_at;

    INSERT INTO audit_log (actor_user_id, action, target_kind, target_id, details_json)
    VALUES (p_actor_id, 'APPROVE_QUARANTINE', 'quarantine', p_quarantine_id::TEXT,
            jsonb_build_object('post_id', v_post_id::TEXT));

    PERFORM pg_notify('new_post',
        '{"ready_at":"' || v_ready_at::TEXT || '","post_id":"' || v_post_id::TEXT || '"}');
END;
$$;

-- 2. Redeclare reject_quarantine with search_path pin + actor check.
CREATE OR REPLACE FUNCTION reject_quarantine(p_quarantine_id UUID, p_actor_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_status TEXT;
BEGIN
    -- Actor must exist and be a bot admin.
    IF NOT EXISTS (SELECT 1 FROM users WHERE id = p_actor_id AND is_admin = TRUE) THEN
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

    UPDATE quarantine
       SET status = 'REJECTED', updated_at = now(), reviewed_by = p_actor_id
     WHERE id = p_quarantine_id;

    INSERT INTO audit_log (actor_user_id, action, target_kind, target_id)
    VALUES (p_actor_id, 'REJECT_QUARANTINE', 'quarantine', p_quarantine_id::TEXT);
END;
$$;
