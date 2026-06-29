-- V53: guard approve_quarantine's new_post NOTIFY on the post UPDATE
-- actually matching a row (deep-review 19#F2; M1-516, split from M1-493).
--
-- approve_quarantine's latest body (V50) fires pg_notify('new_post')
-- unconditionally, even when its
--   UPDATE post ... WHERE id = v_post_id AND fetched_at = v_post_fetched_at
-- matched zero rows. That UPDATE can match zero rows when the post was
-- TTL-dropped: quarantine has no FK to post (V10:27-32 — a long-lived
-- quarantine row must survive its source partition's drop), so a
-- quarantine row can outlive its post. The phantom NOTIFY then tells the
-- Provider's NewPostListener to chase a post that no longer exists.
--
-- This redeclares ONLY approve_quarantine, carrying the V50 body forward
-- verbatim (the live-admin actor check, the audit-before-effect INSERT,
-- the SET search_path pin, the (UUID, UUID) signature so it replaces
-- rather than overloads and ACLs survive) and adding a GET DIAGNOSTICS
-- ROW_COUNT check after the UPDATE post: the new_post NOTIFY now fires
-- only when a post row was actually updated. The quarantine_review NOTIFY
-- still fires unconditionally — the quarantine UPDATE always matches the
-- FOR UPDATE-locked row. delete_preban_user and reject_quarantine (also
-- redeclared in V50) are unchanged and not touched here.

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
    -- Rows touched by the UPDATE post below: 0 when the post was
    -- TTL-dropped (quarantine has no FK to post), which gates the
    -- new_post NOTIFY (M1-516).
    v_post_rows       BIGINT;
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
    GET DIAGNOSTICS v_post_rows = ROW_COUNT;

    -- Same payload shape as ReadyPromoter so the Provider's
    -- NewPostListener picks the released post up via the standard
    -- cursor. Gated on the UPDATE above touching a row: a TTL-dropped
    -- post leaves nothing for the listener to chase, so suppress the
    -- phantom NOTIFY (M1-516).
    IF v_post_rows > 0 THEN
        PERFORM pg_notify('new_post',
            jsonb_build_object('ready_at', v_ready_at, 'post_id', v_post_id)::text);
    END IF;

    -- Tagged quarantine_review payload — the cursor's reviewed_at is
    -- the quarantine row's updated_at set above, in this same
    -- transaction (architecture.md §Inter-service communication). Always
    -- fires: the quarantine UPDATE above always matches the locked row.
    PERFORM pg_notify('quarantine_review',
        jsonb_build_object('target_kind', 'quarantine',
                           'target_id', p_quarantine_id,
                           'new_status', 'APPROVED')::text);
END;
$$;
