-- V2: DB role principals — the authorization boundary.
--
-- Three application roles back the trust split defined in
-- docs/spec/security.md §DB roles:
--
--   infochat_collector — fetches feeds; inserts posts; reads sources.
--                        Cannot DELETE source rows, cannot read raw
--                        quarantine originals, cannot mutate
--                        price_snapshot.
--   infochat_provider  — reads posts via deterministic SQL; writes
--                        per-(user,scope) state, audit_log, group
--                        memberships. Cannot DELETE source rows, cannot
--                        read unredacted audit_log_view, cannot read
--                        raw quarantine originals.
--   infochat_admin     — reads audit_log_view (redacted), executes the
--                        approve_quarantine / reject_quarantine
--                        procedures. NOT a superuser; NOT a bypass of
--                        the spec's least-privilege model.
--
-- M1-006 creates the principals only. Per-table GRANTs (and the
-- REVOKE of DELETE on the source table) ride with the table's own
-- CREATE migration in the M1-008 umbrella so the privilege surface
-- for each table is reviewable in one place. The heartbeat table and
-- its grants land in M1-009 (V3). The audit_log_view, approve_quarantine,
-- and reject_quarantine surfaces land with the audit-log and quarantine
-- subtickets of the M1-008 umbrella.
--
-- LISTEN/NOTIFY needs no GRANT: any role with an active session can
-- LISTEN on any channel and NOTIFY any channel. The spec language in
-- §DB roles names LISTEN/NOTIFY as a capability the role uses, not as
-- a GRANT to issue here.
--
-- NOLOGIN is the v1 default for all three roles. Until the
-- named-datasource wiring ticket lands, the bootstrap `infochat`
-- superuser remains the connecting role; the application roles are
-- principals that future per-table GRANTs name. The named-datasource
-- wiring ticket re-evaluates LOGIN on infochat_admin (operator psql
-- path) and on infochat_collector / infochat_provider (Quarkus named
-- datasource JDBC connect path).

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'infochat_collector') THEN
    CREATE ROLE infochat_collector NOLOGIN;
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'infochat_provider') THEN
    CREATE ROLE infochat_provider NOLOGIN;
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'infochat_admin') THEN
    CREATE ROLE infochat_admin NOLOGIN;
  END IF;
END $$;

-- Schema-level USAGE lets each role resolve unqualified identifiers
-- in the per-table grants that ride with M1-008. Without USAGE on the
-- schema, a per-table GRANT would not be exercisable from a session
-- that SET ROLE-s to the application role.
GRANT USAGE ON SCHEMA public TO infochat_collector;
GRANT USAGE ON SCHEMA public TO infochat_provider;
GRANT USAGE ON SCHEMA public TO infochat_admin;
