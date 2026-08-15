-- V80: column-scoped Provider UPDATE grant on asset_config for
-- /asset-enable (commands.md §Asset commands, security.md §DB roles).
--
-- V14 leaves the Provider SELECT-only; /asset-enable resets a tripped D42
-- ladder by writing exactly status + consecutive_failures. Never GRANT
-- UPDATE ON asset_config wholesale: the config columns (enabled,
-- is_default, default_quote_currency, attribution_url) are operator-curated
-- (D39) and bootstrap-loader-owned, and a Provider SQL-injection foothold
-- must not re-enable a bootstrap-removed pair or move the default sub-verb.
-- Closed-enumeration extension per the V31/V75 pattern; the Collector needs
-- no change (V14 gives it table-wide UPDATE).

GRANT UPDATE (status, consecutive_failures)
    ON asset_config TO infochat_provider;
