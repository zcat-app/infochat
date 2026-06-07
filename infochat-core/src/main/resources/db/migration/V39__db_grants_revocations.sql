-- V39: grant-layer least-privilege revocations (docs/spec/security.md
-- §DB roles, D34).
--
-- 1. PUBLIC EXECUTE on the SECURITY DEFINER quarantine functions.
--    Postgres grants EXECUTE on every new function to PUBLIC by
--    default, and CREATE OR REPLACE preserves the existing ACL across
--    the V25/V32/V41 re-declarations. V21 granted infochat_provider
--    EXECUTE but never revoked PUBLIC, so any role with schema USAGE
--    (the Collector included) could call the definer-privileged
--    approve/reject path. V5 shows the repo's intended pattern
--    (REVOKE ALL ON PROCEDURE delete_preban_user ... FROM PUBLIC);
--    apply it to both functions. Because CREATE OR REPLACE preserves
--    the ACL, the tightened state is durable against future
--    re-declarations. The V21 GRANT EXECUTE TO infochat_provider is
--    an explicit per-role grant and survives the PUBLIC revoke.
REVOKE ALL ON FUNCTION approve_quarantine(UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION reject_quarantine(UUID, UUID) FROM PUBLIC;

-- 2. price_snapshot is INSERT-only (spec schema.md §Operational:
--    "INSERT-only; no updates"). V17:85 granted UPDATE to the
--    Collector against that contract; the table's only writer
--    (PriceSnapshotStore) INSERTs with ON CONFLICT DO NOTHING, which
--    requires no UPDATE privilege, so nothing depends on the grant.
--    Access is always through the partitioned parent, whose ACL is
--    the one checked, so the parent-level revoke covers all
--    partitions.
REVOKE UPDATE ON price_snapshot FROM infochat_collector;
