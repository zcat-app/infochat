-- V51: widen the price_snapshot dedup key to include vs_currency (M1-351).
--
-- V38 added UNIQUE (asset, sub_verb, captured_at) to enforce the spec's
-- "one row per snapshot" invariant, but omitted vs_currency. A row is
-- READ per (asset, sub_verb, vs_currency) — V17's idx_price_snapshot_lookup
-- and the Provider's AssetSnapshotReader WHERE clause both key on the
-- four-column tuple — yet was DEDUPLICATED per (asset, sub_verb) at a given
-- captured_at. If two quote currencies ever land at the same captured_at,
-- the second INSERT is silently dropped by ON CONFLICT DO NOTHING.
--
-- Not a live v1 bug (one default_quote_currency per row, sub-microsecond
-- captured_at), but the per-currency Fetcher SPI (supportedQuoteCurrencies,
-- fetchSnapshot(asset, vs)) and the read index actively invite the case the
-- narrow key would corrupt. Widening the dedup key to match the read key
-- closes the latent multi-currency silent-drop. See spec schema.md
-- §Operational — Price snapshot and docs/design/02-schema.md §2.7.2.
--
-- The UNIQUE is legal on the partitioned parent because captured_at is the
-- partition key; it propagates to all partitions. Atomic Flyway migration:
-- DROP + ADD apply in one transaction, so a partial failure rolls back.

ALTER TABLE price_snapshot
    DROP CONSTRAINT price_snapshot_dedup_uq;

ALTER TABLE price_snapshot
    ADD CONSTRAINT price_snapshot_dedup_uq
    UNIQUE (asset, sub_verb, vs_currency, captured_at);
