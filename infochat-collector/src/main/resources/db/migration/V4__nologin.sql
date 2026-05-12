-- V4: enforce NOLOGIN on the three application roles.
--
-- V2__roles.sql creates each role guarded by a pg_roles existence
-- check, so a pre-seeded role with LOGIN would survive V2 with the
-- wrong attribute set. ALTER ROLE is idempotent on the attribute,
-- so this migration flips any pre-existing LOGIN to NOLOGIN and
-- is a no-op for the common case where V2 created the role with
-- the correct attribute.

ALTER ROLE infochat_collector NOLOGIN;
ALTER ROLE infochat_provider  NOLOGIN;
ALTER ROLE infochat_admin     NOLOGIN;
