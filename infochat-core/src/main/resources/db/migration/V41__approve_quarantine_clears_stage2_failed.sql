-- V41: approve_quarantine clears stage2_failed — admin approval is
-- terminal over re-evaluation (M1-182 redteam finding 2, AUDIT-EVASION).
--
-- The re-evaluation queue enumerates stage2_failed = TRUE posts in any
-- status except NEEDS_REVIEW. The V32 body never cleared the flag, so
-- an admin-approved infra-failure post (READY, span restored, audited)
-- stayed in the queue and the next non-BENIGN roll re-hid it to
-- QUARANTINED — silently reversing the audited admin decision. Per
-- docs/spec/security.md §Quarantine workflow, /quarantine approve is
-- the terminal review authority; clearing the flag removes the post
-- from both enumeration branches for good.
--
-- Only the stage2_failed write is new. Everything else is V32's body
-- verbatim: the V25 hardening (SET search_path pin, actor-admin
-- check), the jsonb_build_object payloads, the actor_contact_id /
-- actor_adapter audit denormalization, and the new_post +
-- quarantine_review NOTIFYs. The parameter list stays exactly
-- (UUID, UUID) so CREATE OR REPLACE replaces rather than overloads;
-- existing ACLs survive. reject_quarantine is untouched.

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
           status_changed_at = v_ready_at,
           -- Terminal admin review: drop the post out of the re-eval
           -- queue's infra-failure branch so it can never be re-hidden.
           stage2_failed = FALSE
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
