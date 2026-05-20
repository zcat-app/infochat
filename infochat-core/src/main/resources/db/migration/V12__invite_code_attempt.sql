-- V12: §Invite-code registration brute-force counter table.
--
-- Lands the per-(adapter, contact_id) brute-force counter that backs
-- the invite-consume short-circuit at authorization step 2 per
-- docs/spec/security.md §Invite-code registration. The
-- InviteCodeConsumer (M1-044a) reads a window-bounded
-- `SELECT count(*) FROM invite_code_attempt WHERE adapter = ?
-- AND contact_id = ? AND attempted_at > NOW() - INTERVAL '<window>'`
-- BEFORE the race-safe conditional UPDATE against invite_code; if the
-- count is at or above threshold, the consume short-circuits with the
-- BruteForceThresholdBreached outcome and the
-- INVITE_BRUTE_FORCE_BREACH audit row is written exactly once per
-- breach event. On a Rejected outcome, the consumer INSERTs one row
-- into this table so the window count grows over time.
--
-- The table is separate from `users` and `invite_code` because the
-- spec scopes the counter to (adapter, contact_id) — which for
-- unknown contacts has no `users` row to attach to. The composite
-- index `(adapter, contact_id, attempted_at DESC)` backs the
-- window-bounded count query.
--
-- Append-on-Rejected semantics. Rows accumulate forever in v1; no
-- TTL. The operator-side TRUNCATE under the admin role is the
-- incident-response purge path — mirroring the audit-log append-only
-- treatment so a misconfigured GRANT cannot delete the patient-
-- brute-force evidence. DELETE is intentionally NOT granted to either
-- service role.
--
-- Atomic Flyway migration: CREATE TABLE + CREATE INDEX + GRANTs apply
-- in one transaction so a partial failure rolls back cleanly.

-- ---------------------------------------------------------------------
-- invite_code_attempt
-- ---------------------------------------------------------------------

CREATE TABLE invite_code_attempt (
    adapter      TEXT        NOT NULL,
    contact_id   TEXT        NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_invite_code_attempt_lookup
    ON invite_code_attempt(adapter, contact_id, attempted_at DESC);

-- ---------------------------------------------------------------------
-- Per-table GRANTs (aligned with docs/spec/security.md §DB roles).
--
-- The Provider's InviteCodeConsumer is the only writer (INSERT) and the
-- only reader (SELECT count(*) for the window-bounded gate). The
-- Collector has no business here. DELETE is intentionally NOT granted —
-- the operator-side TRUNCATE under infochat_admin is the only purge
-- path, mirroring audit_log's append-only treatment.
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT ON invite_code_attempt TO infochat_provider;

-- ---------------------------------------------------------------------
-- audit_log.action verb catalogue addition.
--
-- V5 §2.1.8 documents the closed verb set as per-line comments; this
-- migration extends the catalogue with the new verb consumed by
-- InviteCodeConsumer when the per-(adapter, contact_id) attempt
-- counter breaches threshold within the window. The
-- application-layer audit-write helper is the closure enforcer; the
-- comment makes the catalogue greppable.
-- ---------------------------------------------------------------------

-- INVITE_BRUTE_FORCE_BREACH
