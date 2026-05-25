-- V21: quarantine admin — re_eval_attempts column, stored procedures,
-- Provider GRANTs, and quarantine_review provider_state seed.
--
-- Dependencies: V7 (post table), V10 (quarantine table), V5 (audit_log),
-- V9 (provider_state), V16 (admin_notification_state).
--
-- The stored procedures use SECURITY DEFINER so the Provider can call
-- them without raw quarantine table access — Provider only has SELECT
-- on quarantine_review_view (V10) plus EXECUTE on these two procedures.
-- The procedures read quarantine.original_html, perform the body restore,
-- the state-machine transition, the audit row insert, and the NOTIFY
-- all atomically inside the procedure's implicit transaction.

-- 1. ALTER post: add the re-evaluation attempt counter.
-- Defaults to 0 for all existing rows. Incremented by the re-eval job
-- on each attempt regardless of verdict.
ALTER TABLE post ADD COLUMN re_eval_attempts INTEGER NOT NULL DEFAULT 0;

-- 2. Stored procedure: approve_quarantine(quarantine_id, actor_id).
-- Transitions quarantine PENDING/BENIGN_CLOSED → APPROVED, restores the
-- original_html into the post body replacing the placeholder, writes
-- an APPROVE_QUARANTINE audit row, transitions the post to READY, and
-- fires NOTIFY new_post with the standard cursor payload.
CREATE OR REPLACE FUNCTION approve_quarantine(p_quarantine_id UUID, p_actor_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_post_id         UUID;
    v_post_fetched_at TIMESTAMPTZ;
    v_placeholder_id  TEXT;
    v_original_html   TEXT;
    v_status          TEXT;
    v_ready_at        TIMESTAMPTZ;
BEGIN
    -- Lock the quarantine row and read its fields.
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

    -- Transition quarantine row to APPROVED.
    UPDATE quarantine
       SET status = 'APPROVED', updated_at = now(), reviewed_by = p_actor_id
     WHERE id = p_quarantine_id;

    -- Restore original_html into the post body, set post to READY.
    v_ready_at := now();
    UPDATE post
       SET body = replace(body, '[REDACTED:' || v_placeholder_id || ']', v_original_html),
           status = 'READY',
           ready_at = v_ready_at,
           status_changed_at = v_ready_at
     WHERE id = v_post_id AND fetched_at = v_post_fetched_at;

    -- Audit row (APPROVE_QUARANTINE).
    INSERT INTO audit_log (actor_user_id, action, target_kind, target_id, details_json)
    VALUES (p_actor_id, 'APPROVE_QUARANTINE', 'quarantine', p_quarantine_id::TEXT,
            jsonb_build_object('post_id', v_post_id::TEXT));

    -- NOTIFY new_post — same payload shape as ReadyPromoter so the
    -- Provider's NewPostListener picks it up via the standard cursor.
    PERFORM pg_notify('new_post',
        '{"ready_at":"' || v_ready_at::TEXT || '","post_id":"' || v_post_id::TEXT || '"}');
END;
$$;

-- 3. Stored procedure: reject_quarantine(quarantine_id, actor_id).
-- Transitions quarantine PENDING/BENIGN_CLOSED → REJECTED and writes
-- a REJECT_QUARANTINE audit row. Does NOT change post status — the
-- placeholder remains in post.body permanently.
CREATE OR REPLACE FUNCTION reject_quarantine(p_quarantine_id UUID, p_actor_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_status TEXT;
BEGIN
    -- Lock and read status.
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

    -- Transition quarantine row to REJECTED.
    UPDATE quarantine
       SET status = 'REJECTED', updated_at = now(), reviewed_by = p_actor_id
     WHERE id = p_quarantine_id;

    -- Audit row (REJECT_QUARANTINE).
    INSERT INTO audit_log (actor_user_id, action, target_kind, target_id)
    VALUES (p_actor_id, 'REJECT_QUARANTINE', 'quarantine', p_quarantine_id::TEXT);
END;
$$;

-- 4. GRANT EXECUTE to infochat_provider.
GRANT EXECUTE ON FUNCTION approve_quarantine(UUID, UUID) TO infochat_provider;
GRANT EXECUTE ON FUNCTION reject_quarantine(UUID, UUID) TO infochat_provider;

-- 5. Seed provider_state row for the quarantine_review channel.
-- Same empty-sentinel pattern as V9's new_post seed; ON CONFLICT
-- is the first-boot race guard.
INSERT INTO provider_state (channel, cursor_high, cursor_low_kind, cursor_low_id, updated_at)
VALUES ('quarantine_review', 'epoch'::TIMESTAMPTZ, '', '', now())
ON CONFLICT (channel) DO NOTHING;

-- 6. GRANT INSERT, UPDATE on admin_notification_state to infochat_provider.
-- Provider needs write access for throttled admin notifications on
-- quarantine_review events (e.g. coalesced alerts when the review
-- queue depth exceeds threshold).
GRANT INSERT, UPDATE ON admin_notification_state TO infochat_provider;
