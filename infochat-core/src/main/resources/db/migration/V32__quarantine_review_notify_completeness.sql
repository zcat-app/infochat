-- V32: quarantine_review NOTIFY channel completeness for the admin
-- review procedures (M1-134).
--
-- approve_quarantine / reject_quarantine (V21, hardened in V25) update
-- the quarantine row and write the audit row but never fire
-- pg_notify('quarantine_review', ...), so the Provider cursor cannot
-- advance for APPROVED/REJECTED transitions and the reconciler
-- over-replays them on restart. This migration redeclares both
-- procedures to:
--
--   1. emit the missing quarantine_review NOTIFY with the tagged
--      ('quarantine', <id>, 'APPROVED'|'REJECTED') payload from
--      docs/spec/architecture.md §Inter-service communication;
--   2. build every NOTIFY payload via jsonb_build_object(...)::text
--      instead of raw || concatenation — to_jsonb(timestamptz)
--      renders ISO-8601 (with the T separator), which the Provider's
--      Instant.parse accepts, where the V25 v_ready_at::TEXT payload
--      rendered the Postgres format it rejects;
--   3. re-add the actor_contact_id / actor_adapter denormalized
--      audit columns (docs/spec/schema.md §Audit log) the V25
--      bodies dropped, following V5 delete_preban_user's SELECT-JOIN
--      pattern (the actor-admin check guarantees the actor row
--      exists, so the SELECT inserts exactly one row).
--
-- CREATE OR REPLACE discards the prior body, so V25's hardening is
-- carried forward verbatim: the SET search_path pin and the
-- actor-admin check. The parameter list stays exactly (UUID, UUID) —
-- a changed list would CREATE an overload instead of replacing,
-- stranding the old body behind the same name. Existing ACLs (the
-- V21 GRANT EXECUTE to infochat_provider) survive CREATE OR REPLACE.

-- 1. Redeclare approve_quarantine.
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

    INSERT INTO audit_log (actor_user_id, actor_contact_id, actor_adapter,
                           action, target_kind, target_id, details_json)
    SELECT p_actor_id, a.contact_id, a.adapter,
           'APPROVE_QUARANTINE', 'quarantine', p_quarantine_id::TEXT,
           jsonb_build_object('post_id', v_post_id::TEXT)
      FROM users a
     WHERE a.id = p_actor_id;

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

-- 2. Redeclare reject_quarantine.
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

    INSERT INTO audit_log (actor_user_id, actor_contact_id, actor_adapter,
                           action, target_kind, target_id)
    SELECT p_actor_id, a.contact_id, a.adapter,
           'REJECT_QUARANTINE', 'quarantine', p_quarantine_id::TEXT
      FROM users a
     WHERE a.id = p_actor_id;

    PERFORM pg_notify('quarantine_review',
        jsonb_build_object('target_kind', 'quarantine',
                           'target_id', p_quarantine_id,
                           'new_status', 'REJECTED')::text);
END;
$$;
