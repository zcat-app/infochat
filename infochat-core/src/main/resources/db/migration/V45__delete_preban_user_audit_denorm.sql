-- V45: Denormalize actor_contact_id / actor_adapter in the
-- delete_preban_user audit row (deep-review v2.5 finding F1; M1-235).
--
-- docs/spec/schema.md §Entities defines actor_contact_id and
-- actor_adapter as columns "denormalized at write time for
-- redaction-free historical lookup; the FK target may rotate." The
-- latest delete_preban_user body (V24) dropped both with a comment
-- claiming they were "derivable from actor_user_id" — but this
-- procedure DELETEs a users row, exactly the case where read-time
-- derivation cannot recover the actor's contact id/adapter as they
-- stood at write time. The omission also left two SECURITY DEFINER
-- audit-writing procedures disagreeing: V32 restored these columns
-- for the quarantine procedures (approve_quarantine /
-- reject_quarantine) but delete_preban_user was never remediated.
--
-- This restores the exact SELECT-JOIN the original V5 body used
-- (JOIN users a ON a.id = p_actor_id, selecting a.contact_id /
-- a.adapter). CREATE OR REPLACE discards the prior body, so V24's
-- hardening is carried forward verbatim: the SET search_path pin, the
-- actor-admin EXISTS check, and the preban-state check. The parameter
-- list stays exactly (UUID, UUID) — a changed list would CREATE an
-- overload instead of replacing — and existing ACLs (V5's GRANT
-- EXECUTE to infochat_provider) survive CREATE OR REPLACE. Only the
-- audit INSERT changes: the actor-admin check above guarantees the
-- JOIN yields exactly one row, so the procedure still writes exactly
-- one audit row and control flow is identical to V24.

CREATE OR REPLACE PROCEDURE delete_preban_user(p_user_id UUID, p_actor_id UUID)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_state TEXT;
BEGIN
    -- Actor must exist and be a bot admin.
    IF NOT EXISTS (SELECT 1 FROM users WHERE id = p_actor_id AND is_admin = TRUE) THEN
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
