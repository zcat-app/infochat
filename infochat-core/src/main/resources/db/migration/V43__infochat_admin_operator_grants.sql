-- V43: infochat_admin operator grants — make the documented admin
-- surface real.
--
-- V2 created infochat_admin as the operator principal and deferred its
-- grants; V31 settled the LOGIN posture (stays NOLOGIN) but granted
-- nothing. Until now the role held only schema USAGE (V2:65) while six
-- migrations describe operator escape hatches as belonging to it —
-- none of which a USAGE-only role can perform. This migration grants
-- exactly that documented surface (docs/spec/security.md §DB roles,
-- least privilege per D34):
--
--   * audit_log_view SELECT — the redacted read path (V2:15). The raw
--     audit_log table stays ungranted: routine operator audit reads go
--     through the same redaction the Provider uses; unredacted
--     forensic reads remain an owner-role action.
--   * approve_quarantine / reject_quarantine EXECUTE — V2:15 names the
--     admin as an executor of the quarantine-review escape hatches;
--     V39's PUBLIC revoke (correctly) cut the implicit path, so the
--     explicit per-role grant lands here.
--   * quarantine SELECT — raw original_html inspection is admin-only
--     relative to the Provider (V10:14; security.md §DB roles "raw
--     quarantine inspection").
--   * heartbeat SELECT + DELETE — V3:22: only infochat_admin may
--     delete heartbeat rows (operator path). SELECT rides along
--     because the documented path is row-targeted (DELETE ... WHERE
--     service = ...) and Postgres requires SELECT privilege on
--     columns referenced in the WHERE clause.
--   * invite_code_attempt TRUNCATE — V12:51: the operator-side
--     TRUNCATE is the only purge path.
--   * source SELECT + DELETE — Invariant 4's manual escape hatch
--     (V6:101; security.md §DB roles "Invariant 4 enforcement"):
--     hard-delete is admin-only and row-targeted, hence the
--     WHERE-clause SELECT.
--
-- NOT granted, intentionally — the ownership-level operations the
-- corpus also mentions stay with the owner role, because Postgres
-- cannot GRANT them to a non-owner:
--   * partition drop (Invariant 6 retention; V7:209, V11:136, V17:81)
--     — DROP TABLE requires ownership; the partition-rotation feature
--     that would justify a SECURITY DEFINER wrapper does not exist
--     yet, and a definer procedure with no caller is speculative
--     attack surface.
--   * audit_log append-only trigger disable for retention sweeps
--     (V5:246) — DROP/DISABLE TRIGGER requires ownership.
--   * migrations — Flyway runs on the owner datasource (V31).
--
-- LOGIN posture, re-affirmed from V31: infochat_admin stays NOLOGIN.
-- It is a privilege bundle, not a connectable principal. Operators
-- attach via a personal LOGIN role:
--     CREATE ROLE ops_alice LOGIN PASSWORD '...';
--     GRANT infochat_admin TO ops_alice;
-- so no shared admin credential exists and psql actions remain
-- attributable to a person.

GRANT SELECT ON audit_log_view TO infochat_admin;

GRANT EXECUTE ON FUNCTION approve_quarantine(UUID, UUID) TO infochat_admin;
GRANT EXECUTE ON FUNCTION reject_quarantine(UUID, UUID) TO infochat_admin;

GRANT SELECT ON quarantine TO infochat_admin;

GRANT SELECT, DELETE ON heartbeat TO infochat_admin;

GRANT TRUNCATE ON invite_code_attempt TO infochat_admin;

GRANT SELECT, DELETE ON source TO infochat_admin;
