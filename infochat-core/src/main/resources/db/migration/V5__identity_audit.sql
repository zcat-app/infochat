-- V5: identity, audit, last-admin trigger (§2.1).
--
-- Lands the foundational §2.1 Identity & access slice from
-- docs/design/02-schema.md:
--   - `users` (D44, D45, D46) with the registration_state CHECK
--      constraint and the (adapter, contact_id) UNIQUE.
--   - Last-admin protection trigger (Invariant 2): SHARE ROW EXCLUSIVE
--      table-level lock at the top of the trigger function body so
--      concurrent revocation transactions serialize and cannot both
--      observe the pre-state count of two admins (the failure mode the
--      naive READ COMMITTED count permits). Wired to BOTH the BEFORE
--      UPDATE and BEFORE DELETE paths on `users`.
--   - `groups` (D46) and `group_membership` with the partial unique
--      index `one_admin_per_group` (Invariant 3) plus the
--      admin-clear-on-remove trigger that frees the partial-unique
--      index slot during the soft-clear lifecycle.
--   - `invite_code` (D44) with the iff CHECK between `invite_type` and
--      `expected_contact_id`, the closed `status` CHECK, and the
--      partial pending-lookup index that backs the race-safe
--      single-use consume.
--   - `audit_log` (Invariants 7 + 10) with the spec-closed
--      target_kind CHECK, the five-index hot path, and the append-only
--      trigger guard bound to BOTH UPDATE and DELETE (defense-in-depth
--      against a misconfigured GRANT). The retention sweep escape
--      hatch is operator-only (infochat_admin) per spec §DB roles.
--   - The closed `audit_log.action` verb set documented as per-verb
--      line comments (one `-- VERB` per verb) so a grep keeps the
--      catalogue honest. The set is NOT pinned with a SQL CHECK on
--      `audit_log.action` because the verb catalogue is open-ended
--      for v2 additions; the application-layer audit-write helper is
--      the closure enforcer.
--   - `audit_log_view` with stub `redact_contact_id` and
--      `redact_secrets_jsonb` helpers (CREATE OR REPLACE FUNCTION so
--      the audit-write redaction-hook ticket can supersede the bodies
--      without a schema migration). The view is the single Provider
--      read path; raw `audit_log` carries no SELECT for either
--      service role.
--   - `delete_preban_user(UUID, UUID)` stored procedure — the single
--      Invariant 2 carve-out path. SECURITY DEFINER so the Provider
--      role can invoke it without carrying raw DELETE on `users`.
--      Writes the `UNBAN_PREBAN_DELETE` audit row BEFORE the DELETE
--      (audit-before-effect, Invariant 7).
--   - Per-table GRANTs aligned with docs/spec/security.md §DB roles.
--      DELETE on `users` is revoked from both service roles
--      (`delete_preban_user` is the only permitted DELETE path);
--      `audit_log` carries INSERT-only for both service roles and NO
--      direct SELECT for the Provider; `audit_log_view` carries
--      SELECT only for the Provider.
--
-- The whole file applies in one Flyway transaction; a partial failure
-- rolls back atomically so the schema cannot half-apply.

-- ---------------------------------------------------------------------
-- 2.1.1 users (D44, D45, D46, Invariant 2)
-- ---------------------------------------------------------------------

CREATE TABLE users (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    adapter            TEXT NOT NULL,
    contact_id         TEXT NOT NULL,
    display_name       TEXT,
    is_admin           BOOLEAN NOT NULL DEFAULT FALSE,
    is_banned          BOOLEAN NOT NULL DEFAULT FALSE,
    banned_at          TIMESTAMPTZ,
    banned_by          UUID REFERENCES users(id),
    ban_reason         TEXT,
    registration_state TEXT NOT NULL,
    probation_until    TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at       TIMESTAMPTZ,
    save_count         INT NOT NULL DEFAULT 0,
    CONSTRAINT users_adapter_contact_unique
        UNIQUE (adapter, contact_id),
    CONSTRAINT users_registration_state_chk
        CHECK (registration_state IN ('preban','group_only','invited','vouched'))
);

CREATE INDEX idx_users_admin  ON users(is_admin)  WHERE is_admin;
CREATE INDEX idx_users_banned ON users(is_banned) WHERE is_banned;

-- ---------------------------------------------------------------------
-- 2.1.2 Last-admin protection trigger (Invariant 2)
--
-- LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE sits at the TOP of each
-- trigger body so two concurrent revocation transactions serialize at
-- the lock acquisition. SHARE ROW EXCLUSIVE blocks other writers
-- (another /revoke-admin must wait) while permitting concurrent
-- SELECT (Provider read paths against users must not stall). The
-- naive READ COMMITTED count without the table-level lock would let
-- both transactions read the pre-state count of two admins and both
-- commit, leaving zero admins. Counting is global across adapters
-- (decision D46) — no WHERE adapter = ... filter.
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION trg_last_admin_protection_update()
RETURNS TRIGGER AS $$
DECLARE
    remaining INT;
BEGIN
    LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;

    IF (OLD.is_admin = TRUE AND NEW.is_admin = FALSE)
       OR (OLD.is_banned = FALSE AND NEW.is_banned = TRUE AND OLD.is_admin = TRUE) THEN
        SELECT count(*) INTO remaining
          FROM users
         WHERE is_admin = TRUE
           AND is_banned = FALSE
           AND id <> NEW.id;
        IF remaining < 1 THEN
            RAISE EXCEPTION 'last_admin_protection: cannot leave the deployment with zero bot admins';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_last_admin_update BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION trg_last_admin_protection_update();

-- The DELETE path is defense-in-depth. A `preban` row never has
-- is_admin = TRUE (bootstrap admins are `vouched`; preban rows minted
-- by /ban against unknown contacts never carry the admin flag), so the
-- count check always passes for the delete_preban_user carve-out. The
-- guard exists so an operator running raw SQL under the Admin role
-- concurrent with a /revoke-admin still serializes against the lock.
CREATE OR REPLACE FUNCTION trg_last_admin_protection_delete()
RETURNS TRIGGER AS $$
DECLARE
    remaining INT;
BEGIN
    LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;

    IF OLD.is_admin = TRUE THEN
        SELECT count(*) INTO remaining
          FROM users
         WHERE is_admin = TRUE
           AND is_banned = FALSE
           AND id <> OLD.id;
        IF remaining < 1 THEN
            RAISE EXCEPTION 'last_admin_protection: cannot delete the last bot admin';
        END IF;
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_last_admin_delete BEFORE DELETE ON users
    FOR EACH ROW EXECUTE FUNCTION trg_last_admin_protection_delete();

-- ---------------------------------------------------------------------
-- 2.1.3 groups (D46)
-- ---------------------------------------------------------------------

CREATE TABLE groups (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    adapter           TEXT NOT NULL,
    upstream_group_id TEXT NOT NULL,
    display_name      TEXT,
    timezone          TEXT NOT NULL DEFAULT 'UTC',
    removed_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (adapter, upstream_group_id)
);

-- ---------------------------------------------------------------------
-- 2.1.4 group_membership (D9 / Invariant 3)
--
-- The partial unique index `one_admin_per_group` enforces at most one
-- is_group_admin = TRUE row per group. The admin-clear trigger fires
-- in the same transaction that sets removed_at so the user-departure
-- soft-clear lifecycle frees the partial-unique-index slot without an
-- explicit application-layer UPDATE.
-- ---------------------------------------------------------------------

CREATE TABLE group_membership (
    group_id       UUID NOT NULL REFERENCES groups(id),
    user_id        UUID NOT NULL REFERENCES users(id),
    is_group_admin BOOLEAN NOT NULL DEFAULT FALSE,
    joined_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at     TIMESTAMPTZ,
    PRIMARY KEY (group_id, user_id)
);

CREATE INDEX idx_group_membership_user ON group_membership(user_id);

CREATE UNIQUE INDEX one_admin_per_group ON group_membership(group_id) WHERE is_group_admin = TRUE;

CREATE OR REPLACE FUNCTION trg_clear_group_admin_on_remove()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.removed_at IS NOT NULL AND OLD.removed_at IS NULL
       AND OLD.is_group_admin = TRUE THEN
        NEW.is_group_admin := FALSE;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_group_membership_clear_admin BEFORE UPDATE ON group_membership
    FOR EACH ROW EXECUTE FUNCTION trg_clear_group_admin_on_remove();

-- ---------------------------------------------------------------------
-- 2.1.5 invite_code (D44)
--
-- The iff-CHECK between invite_type and expected_contact_id encodes
-- the spec's two-mode invite shape: CONTACT_BOUND must name the
-- recipient's contact id; OPEN_ADAPTER must not. The partial
-- pending-lookup index backs the race-safe single-use consume — a
-- conditional UPDATE against status='PENDING' (spec §Identity and
-- access — Invite code).
-- ---------------------------------------------------------------------

CREATE TABLE invite_code (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                UUID NOT NULL UNIQUE,
    invite_type         TEXT NOT NULL
        CHECK (invite_type IN ('CONTACT_BOUND','OPEN_ADAPTER')),
    adapter             TEXT NOT NULL,
    expected_contact_id TEXT,
    status              TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','USED','REVOKED')),
    created_by          UUID NOT NULL REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ,
    used_at             TIMESTAMPTZ,
    used_by_contact_id  TEXT,
    CONSTRAINT invite_type_contact_iff CHECK (
        (invite_type = 'CONTACT_BOUND' AND expected_contact_id IS NOT NULL)
        OR
        (invite_type = 'OPEN_ADAPTER'  AND expected_contact_id IS NULL)
    )
);

CREATE INDEX idx_invite_code_pending
    ON invite_code(adapter, code) WHERE status = 'PENDING';
CREATE INDEX idx_invite_code_creator
    ON invite_code(created_by, created_at DESC);

-- ---------------------------------------------------------------------
-- 2.1.7 audit_log (Invariant 7, Invariant 10)
--
-- Append-only via two layers: (a) the role GRANT matrix at the bottom
-- of this file gives INSERT-only to both service roles, (b) the
-- trg_audit_log_append_only trigger raises on any UPDATE or DELETE
-- (defense-in-depth against a misconfigured grant). Only
-- infochat_admin can disable the trigger for operator-controlled
-- retention runs.
-- ---------------------------------------------------------------------

CREATE TABLE audit_log (
    id                BIGSERIAL PRIMARY KEY,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_user_id     UUID REFERENCES users(id),
    actor_contact_id  TEXT,
    actor_adapter     TEXT,
    action            TEXT NOT NULL,
    target_kind       TEXT NOT NULL
        CHECK (target_kind IN ('user','group','source','post','invite','quarantine','asset','memory','system')),
    target_id         TEXT NOT NULL,
    target_contact_id TEXT,
    scope_id          UUID,
    request_id        TEXT,
    details_json      JSONB
);

CREATE INDEX idx_audit_created_at    ON audit_log(created_at DESC);
CREATE INDEX idx_audit_actor         ON audit_log(actor_user_id, created_at DESC);
CREATE INDEX idx_audit_action_target ON audit_log(action, target_kind, target_id);
CREATE INDEX idx_audit_scope         ON audit_log(scope_id, created_at DESC) WHERE scope_id IS NOT NULL;
CREATE INDEX idx_audit_request       ON audit_log(request_id) WHERE request_id IS NOT NULL;

-- 2.1.8 audit_log.action closed verb catalogue. The verb set is NOT
-- pinned via a SQL CHECK — the catalogue is open-ended for v2 additions
-- and the application-layer audit-write helper is the closure enforcer.
-- The authoritative verb list lives in the AuditAction enum
-- (infochat-core: app.zcat.infochat.core.audit.AuditAction). The V5-era
-- per-verb line comments that sat here listed only the original v1 subset
-- and drifted as later migrations added verbs; they are replaced by this
-- pointer so the enum is the single source of truth.

CREATE OR REPLACE FUNCTION trg_audit_log_append_only()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only (Invariant 10)';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_no_update BEFORE UPDATE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION trg_audit_log_append_only();
CREATE TRIGGER trg_audit_log_no_delete BEFORE DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION trg_audit_log_append_only();

-- ---------------------------------------------------------------------
-- 2.1.9 audit_log_view + redactor stubs
--
-- The view exists from day one so the v1 grant matrix is structurally
-- valid (the Provider role's read path is the view, not the raw
-- table). The redactor functions are CREATE OR REPLACE FUNCTION stubs
-- that return their input unchanged; the audit-write redaction-hook
-- ticket supersedes the bodies with the closed regex catalogue from
-- docs/spec/security.md §Secrets handling without needing a schema
-- migration.
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION redact_contact_id(input TEXT)
RETURNS TEXT AS $$
BEGIN
    RETURN input;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

CREATE OR REPLACE FUNCTION redact_secrets_jsonb(input JSONB)
RETURNS JSONB AS $$
BEGIN
    RETURN input;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

CREATE OR REPLACE VIEW audit_log_view AS
SELECT
    id,
    created_at,
    actor_user_id,
    redact_contact_id(actor_contact_id) AS actor_contact_id,
    actor_adapter,
    action,
    target_kind,
    target_id,
    redact_contact_id(target_contact_id) AS target_contact_id,
    scope_id,
    request_id,
    redact_secrets_jsonb(details_json) AS details_json
FROM audit_log;

-- ---------------------------------------------------------------------
-- 2.1.6 delete_preban_user (Invariant 2 carve-out)
--
-- SECURITY DEFINER so the Provider role can invoke the single permitted
-- DELETE path on `users` without carrying raw DELETE privilege. The
-- procedure (a) reads registration_state FOR UPDATE so concurrent
-- updates against the same row serialize, (b) raises if the row is not
-- in 'preban' (the carve-out is preban-only — the registered-row
-- /unban path is a status flip in a later application-layer ticket),
-- (c) writes the UNBAN_PREBAN_DELETE audit row BEFORE the DELETE
-- (audit-before-effect, Invariant 7), (d) issues the DELETE FROM
-- users. The BEFORE DELETE last-admin trigger still fires as
-- defense-in-depth.
-- ---------------------------------------------------------------------

CREATE OR REPLACE PROCEDURE delete_preban_user(p_user_id UUID, p_actor_id UUID)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_state TEXT;
BEGIN
    SELECT registration_state INTO v_state FROM users WHERE id = p_user_id FOR UPDATE;
    IF v_state IS DISTINCT FROM 'preban' THEN
        RAISE EXCEPTION 'delete_preban_user: row % is not in preban state (%)', p_user_id, v_state;
    END IF;

    INSERT INTO audit_log (
        actor_user_id, actor_contact_id, actor_adapter,
        action, target_kind, target_id, target_contact_id,
        scope_id, request_id, details_json
    )
    SELECT p_actor_id,
           a.contact_id, a.adapter,
           'UNBAN_PREBAN_DELETE', 'user', u.id::TEXT, u.contact_id,
           NULL, current_setting('infochat.request_id', TRUE), '{}'::JSONB
      FROM users u
      JOIN users a ON a.id = p_actor_id
     WHERE u.id = p_user_id;

    DELETE FROM users WHERE id = p_user_id AND registration_state = 'preban';
END;
$$;

REVOKE ALL ON PROCEDURE delete_preban_user(UUID, UUID) FROM PUBLIC;
GRANT EXECUTE ON PROCEDURE delete_preban_user(UUID, UUID) TO infochat_provider;

-- ---------------------------------------------------------------------
-- Per-table GRANTs (aligned with docs/spec/security.md §DB roles).
-- DELETE on `users`, `groups`, `group_membership` is intentionally NOT
-- granted to either application role — the only permitted DELETE on
-- `users` is the delete_preban_user stored procedure (SECURITY DEFINER).
-- `audit_log` carries INSERT-only for both service roles (Invariant 10);
-- the Provider's read path is `audit_log_view`, not the raw table.
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON users TO infochat_provider;
GRANT SELECT                ON users TO infochat_collector;

GRANT SELECT, INSERT, UPDATE ON groups TO infochat_provider;
GRANT SELECT                ON groups TO infochat_collector;

GRANT SELECT, INSERT, UPDATE ON group_membership TO infochat_provider;
GRANT SELECT                ON group_membership TO infochat_collector;

GRANT SELECT, INSERT, UPDATE ON invite_code TO infochat_provider;
GRANT USAGE, SELECT ON SEQUENCE audit_log_id_seq TO infochat_provider;
GRANT USAGE, SELECT ON SEQUENCE audit_log_id_seq TO infochat_collector;

GRANT INSERT ON audit_log TO infochat_provider;
GRANT INSERT ON audit_log TO infochat_collector;

REVOKE ALL ON audit_log_view FROM PUBLIC;
GRANT SELECT ON audit_log_view TO infochat_provider;
