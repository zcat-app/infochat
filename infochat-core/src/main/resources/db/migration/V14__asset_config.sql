-- V14: §Operational asset_config (D33, D34, D39, D42).
--
-- One row per (asset, sub_verb) pair. The bootstrap loader
-- (BootstrapAssetsLoader) is the only path that creates rows; the
-- fetcher (M1-055b) writes consecutive_failures / last_success_at /
-- last_failure_at; the Provider reads via SELECT only. Per spec
-- §Operational — Asset config, the lifecycle is enable/disable, never
-- hard-delete: rows soft-disabled by the bootstrap loader (entries
-- absent from the latest bootstrap-assets.json) remain queryable so
-- prior price_snapshot rows (M1-055b territory) keep their FK target.
-- The status enumeration mirrors source.status.
--
-- Atomic Flyway migration: the whole file applies in one transaction
-- so a partial failure rolls back cleanly.

-- ---------------------------------------------------------------------
-- asset_config (D39, spec §Operational — Asset config)
--
-- PRIMARY KEY (asset, sub_verb): the bootstrap loader's upsert key,
-- and the fetcher's natural per-row scheduling key.
--
-- is_default: the partial unique index uq_asset_config_default below
-- enforces at-most-one is_default = true per asset, marking which
-- sub-verb resolves bare /zcash | /monero invocations. The
-- complementary default ⇒ enabled invariant (the
-- "default-but-disabled" rejection) is application-tier — see
-- BootstrapAssetsLoader's bootstrap-time check, because the partial
-- unique index alone cannot enforce "default implies enabled".
--
-- status: closed three-state machine ('active' | 'failed' | 'disabled'),
-- same taxonomy as source.status. The fetcher transitions
-- active → failed on consecutive failures (D42); operator recovery is
-- /asset-enable (docs/design/10-asset-commands.md §10.8b), with the
-- §10.8b SQL as the host-level fallback when the Provider is down.
-- ---------------------------------------------------------------------

CREATE TABLE asset_config (
    asset                  TEXT NOT NULL,
    sub_verb               TEXT NOT NULL,
    enabled                BOOLEAN NOT NULL DEFAULT true,
    default_quote_currency TEXT NOT NULL,
    attribution_url        TEXT NOT NULL,
    consecutive_failures   INT NOT NULL DEFAULT 0,
    last_success_at        TIMESTAMPTZ,
    last_failure_at        TIMESTAMPTZ,
    is_default             BOOLEAN NOT NULL DEFAULT false,
    status                 TEXT NOT NULL DEFAULT 'active'
                           CHECK (status IN ('active', 'failed', 'disabled')),
    PRIMARY KEY (asset, sub_verb)
);

-- Partial unique index: at most one is_default = true row per asset.
-- The bootstrap loader clears the old default before setting a new
-- one on the same asset so the index does not fire on default-flip
-- re-runs; concurrent loaders cannot collide because the loader runs
-- @Startup once per Collector instance (single-instance enforced by
-- M1-009's advisory lock).
CREATE UNIQUE INDEX uq_asset_config_default
    ON asset_config (asset) WHERE is_default = true;

-- ---------------------------------------------------------------------
-- Per-table GRANTs (docs/spec/security.md §DB roles, D34).
--
-- Collector writes (bootstrap loader inserts/updates; fetcher updates
-- consecutive_failures / last_success_at / last_failure_at / status);
-- Provider reads only (the command handlers join asset_config at
-- dispatch time to resolve sub-verbs and to surface the attribution
-- URL in replies). DELETE is REVOKEd from both service roles — soft-
-- disable is the lifecycle path; hard-delete is operator-side only
-- (mirrors V6's REVOKE pattern on source).
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON asset_config TO infochat_collector;
GRANT SELECT                 ON asset_config TO infochat_provider;
REVOKE DELETE ON asset_config FROM infochat_collector;
REVOKE DELETE ON asset_config FROM infochat_provider;
REVOKE DELETE ON asset_config FROM PUBLIC;
