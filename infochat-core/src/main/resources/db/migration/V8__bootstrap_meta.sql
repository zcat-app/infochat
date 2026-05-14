-- V8: §2.9.5 bootstrap_meta (operational helper, design-only).
--
-- Records the last successful bootstrap-sources.json load so the admin
-- /status read path can answer "are all instances running the same
-- bootstrap config?" without scanning audit_log history. The
-- authoritative audit trail is audit_log (action BOOTSTRAP_SOURCE_LOAD,
-- per V5__identity_audit.sql §2.1.8 verb catalogue); bootstrap_meta is
-- the cheap current-state view.
--
-- Atomic Flyway migration: the CREATE TABLE plus the GRANT/REVOKE
-- statements apply in one transaction so a partial failure rolls back
-- cleanly.

-- ---------------------------------------------------------------------
-- 2.9.5 bootstrap_meta (design-only operational helper)
--
-- id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1) is the single-row
-- guard: every INSERT lands on the canonical row id=1, and the
-- BootstrapLoader's upsert path is ON CONFLICT (id) DO UPDATE. The row
-- is never deleted — REVOKE DELETE below is the defense-in-depth
-- complement of the CHECK so a misconfigured GRANT cannot bypass the
-- single-row invariant.
-- ---------------------------------------------------------------------

CREATE TABLE bootstrap_meta (
    id                  SMALLINT    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    last_loaded_sha256  TEXT        NOT NULL,
    last_loaded_at      TIMESTAMPTZ NOT NULL,
    last_entry_count    INT         NOT NULL,
    last_loader_version TEXT        NOT NULL
);

-- ---------------------------------------------------------------------
-- Per-table GRANTs (aligned with docs/spec/security.md §DB roles).
--
-- The Collector's BootstrapLoader writes (INSERT + UPDATE via the ON
-- CONFLICT (id) DO UPDATE path); the Provider's admin /status read
-- path consumes the row via SELECT. DELETE is REVOKEd from both
-- service roles AND from PUBLIC — the row is the single-row guard's
-- canonical record and is only ever UPSERTed.
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON bootstrap_meta TO infochat_collector;
GRANT SELECT                ON bootstrap_meta TO infochat_provider;

REVOKE DELETE ON bootstrap_meta FROM infochat_collector, infochat_provider, PUBLIC;
