-- V69: approve_quarantine refuses while a first-pass Stage 2 verdict is
-- owed (M1-741).
--
-- approve_quarantine's latest body (V53) sets post.status='READY' and
-- fires the new_post NOTIFY unconditionally. An admin working the review
-- queue can approve a Stage 1 row while the post's first-pass Stage 2
-- verdict is still in flight (seconds normally, longer under semaphore
-- queue-wait + retry backoff in Stage2Worker): the post is then READY and
-- announced to users until the verdict transaction commits and re-hides
-- it — judge-condemned content is user-visible for the remainder of the
-- window, against docs/spec/security.md §Failure handling ("a degraded
-- judge must never auto-release"; here a trusted admin releases it
-- unknowingly, before the judgment exists).
--
-- Guard predicate: post.stage1_flagged = TRUE AND post.stage2_verdict
-- IS NULL AND post.status <> 'NEEDS_REVIEW' AND (post.status =
-- 'QUARANTINED' OR post.stage2_failed = FALSE) means precisely "the
-- post is hidden pending a Stage 2 judgment that has never been
-- recorded, and neither the operator nor cap exhaustion has already
-- decided its fate".
-- post.stage1_flagged is BOOLEAN NOT NULL DEFAULT FALSE
-- (V7__joins_post.sql:156) and only the regex path sets it TRUE
-- (Stage1Pipeline.updatePostBodyAndFlags); the watchdog / match-overflow
-- / sanitizer fail-closed paths never set it, so those QUARANTINED posts
-- (stage1_flagged = FALSE) stay approvable. Every judgment-recording
-- path — first-pass BENIGN, INJECTION / MALWARE / UNKNOWN, and re-eval
-- verdicts — writes post.stage2_verdict (V22, nullable; V36
-- CHECK-constrained), while the infra-failure path leaves it NULL with
-- stage2_done = TRUE, stage2_failed = TRUE
-- (Stage2VerdictHandler.applyInfraFailure).
-- The status/stage2_failed disjunct covers the two unjudged bitmaps
-- without touching the two sanctioned ones:
--   * first-pass in flight: status='RAW', stage2_failed=FALSE (Stage 1
--     leaves flagged posts RAW so Stage 2 can judge —
--     Stage1Pipeline.java "post.status stays 'RAW'... The ONLY
--     exceptions are the watchdog and sanitizer-exception fail-closed
--     paths") -> refused (stage2_failed = FALSE disjunct);
--   * fail-closed infra failure (release-on-stage2-failure=false):
--     QUARANTINED, stage2_failed=TRUE, verdict NULL, sitting in the
--     re-eval queue still owed its verdict -> refused (QUARANTINED
--     disjunct). A guard keyed on stage2_done alone silently passes
--     this class, and approve's stage2_failed clear below would
--     permanently drop it from re-evaluation unjudged (round-1 finding,
--     docs/plan/m1/redteam/M1-741-2026-08-01.md);
--   * fail-open released (release-on-stage2-failure=true): RAW,
--     stage2_failed=TRUE — the operator's configured posture already
--     released the content with redactions, and lifting them is the
--     documented admin-approve lifecycle (§Quarantine workflow; V41
--     pins it in ReEvalVerdictNotifyIT
--     .adminApprovedReleasedPostIsNeverReEnumeratedOrReHidden) ->
--     allowed;
--   * cap-exhausted NEEDS_REVIEW: re-eval gave up, so the admin's
--     review IS the judgment -> allowed.
-- The race-case quarantine row M1-739's Stage2VerdictHandler inserts is
-- likewise unaffected: that post carries a recorded verdict.
--
-- The guard runs BEFORE any write (the audit-before-effect INSERT, the
-- quarantine/post UPDATEs, both NOTIFYs), so a refused call performs no
-- row transition, no post UPDATE, no audit row, and no NOTIFY. The
-- quarantine row is already FOR UPDATE-locked at that point, so the check
-- is serialized against a concurrent reject/approve of the same row; a
-- verdict committing between the flag read and a retry simply lets the
-- retry through (fail-closed direction).
--
-- This redeclares ONLY approve_quarantine, carrying the V53 body forward
-- verbatim (the live-admin actor check, the PENDING/BENIGN_CLOSED status
-- check, the audit-before-effect INSERT, the SET search_path pin, the
-- (UUID, UUID) signature so it replaces rather than overloads and ACLs
-- survive, the M1-516 ROW_COUNT gate on the new_post NOTIFY) and adding
-- the verdict-owed guard. reject_quarantine is untouched: it never
-- publishes (no READY write, no new_post NOTIFY), and its mid-flight race
-- was closed by M1-739's FOR UPDATE serialization.

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

    -- Verdict-owed guard (M1-741): refuse while the post is hidden
    -- pending a Stage 2 judgment that has never been recorded — the
    -- first-pass-in-flight state (RAW, stage2_failed=FALSE) and the
    -- fail-closed re-eval-queue state (QUARANTINED, stage2_failed=
    -- TRUE) alike. The fail-open released state (RAW, stage2_failed=
    -- TRUE) and cap-exhausted NEEDS_REVIEW stay approvable (see the
    -- header). Before any write, so a refusal performs no row
    -- transition, no post UPDATE, no audit row, and no NOTIFY. A
    -- TTL-dropped post (no row matched) is NOT guarded here — there is
    -- nothing to re-hide and the M1-516 phantom-NOTIFY gate below
    -- already handles that case.
    IF EXISTS (SELECT 1 FROM post
                WHERE id = v_post_id AND fetched_at = v_post_fetched_at
                  AND stage1_flagged = TRUE
                  AND stage2_verdict IS NULL
                  AND status <> 'NEEDS_REVIEW'
                  AND (status = 'QUARANTINED' OR stage2_failed = FALSE)) THEN
        RAISE EXCEPTION 'quarantine row % cannot be approved: stage 2 verdict still owed',
            p_quarantine_id;
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
