-- V48: audit-before-effect reorder for the quarantine review procedures
-- (M1-246; deep-code-review v3 T7 / opus arch #F2).
--
-- approve_quarantine (latest body V41) and reject_quarantine (latest body
-- V32) both wrote audit_log *after* their UPDATE mutations. Invariant 7
-- (docs/spec/schema.md §Invariants — "Audit-before-effect") requires the
-- audit write to precede the side effects, so an interrupted command
-- leaves a record of intent; the sibling delete_preban_user (V45) already
-- has this shape. This migration redeclares both procedures with the
-- INSERT INTO audit_log moved ahead of the UPDATEs, matching the
-- reference shape and restoring the invariant.
--
-- Behavior-preserving reorder only. v_post_id is still captured at the
-- SELECT ... FOR UPDATE, so the approve payload's details_json
-- (jsonb_build_object('post_id', v_post_id::TEXT)) is byte-identical; the
-- reject audit row carries no details_json. The pg_notify calls stay last
-- and their payloads are unchanged (new_post still reads v_ready_at, set
-- by the post UPDATE that follows the audit write). The parameter list
-- stays exactly (UUID, UUID) so CREATE OR REPLACE replaces rather than
-- overloads; existing ACLs (V21 GRANT EXECUTE to infochat_provider, V43 to
-- infochat_admin) survive. The V41 stage2_failed clear and the V25/V32
-- hardening (SET search_path pin, actor-admin check, jsonb_build_object
-- payloads, actor_contact_id / actor_adapter denormalization) are carried
-- forward verbatim.

-- 1. Redeclare approve_quarantine (audit-before-effect).
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

-- 2. Redeclare reject_quarantine (audit-before-effect).
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
