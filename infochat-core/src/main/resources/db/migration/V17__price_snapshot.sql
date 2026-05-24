-- V17: §Operational price_snapshot (D22, D30, D33, D34, D42).
--
-- One row per (asset, sub_verb, vs_currency, captured_at) snapshot
-- produced by the AssetSnapshotFetcher (M1-055b). Snapshots are NOT
-- posts (spec §Asset commands — "Data is not posts"): they bypass
-- the post outbox, Stage 1/2, tagging, and embedding. The Provider's
-- AssetSnapshotReader (M1-055c) consumes via SELECT only.
--
-- Retention is by PARTITION DROP per spec invariant 6 — neither the
-- Collector nor the Provider holds DELETE on price_snapshot; the
-- partition rotator is operator-driven (infochat_admin role only).
--
-- Atomic Flyway migration: the whole file applies in one transaction
-- so a partial failure rolls back cleanly.

-- ---------------------------------------------------------------------
-- price_snapshot (D33, spec §Operational — Price snapshot, design §10.3)
--
-- PRIMARY KEY (id, captured_at): Postgres requires the partition key
-- to participate in every PRIMARY KEY / UNIQUE constraint on a
-- partitioned table; id alone cannot be the PK because captured_at
-- is the partition key. Same shape as V7's post (id, fetched_at).
--
-- price uses NUMERIC(24,12) for high-precision quote (crypto markets
-- routinely quote BTC pairs at 0.000000001 scales). Volume uses
-- NUMERIC(28,8) to accommodate large absolute 24h volumes without
-- precision loss. Percent-change columns are NUMERIC(8,4) (e.g.
-- "-12.3456%" → -12.3456).
--
-- raw_payload JSONB preserves the upstream response body verbatim
-- for forensic / replay purposes; absent for sources whose API
-- returns only the parsed fields.
-- ---------------------------------------------------------------------

CREATE TABLE price_snapshot (
    id              BIGSERIAL,
    asset           TEXT NOT NULL,
    sub_verb        TEXT NOT NULL,
    vs_currency     TEXT NOT NULL,
    price           NUMERIC(24,12) NOT NULL,
    volume_24h      NUMERIC(28,8),
    high_24h        NUMERIC(24,12),
    low_24h         NUMERIC(24,12),
    change_1h_pct   NUMERIC(8,4),
    change_24h_pct  NUMERIC(8,4),
    change_7d_pct   NUMERIC(8,4),
    captured_at     TIMESTAMPTZ NOT NULL,
    fetched_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    source_url      TEXT,
    raw_payload     JSONB,
    PRIMARY KEY (id, captured_at)
) PARTITION BY RANGE (captured_at);

-- Bootstrap partition: covers May 2026 (the month the schema lands).
-- Naming convention: price_snapshot_pYYYYMM (six-digit suffix =
-- monthly cadence; the `p` prefix distinguishes partition tables
-- from the parent in pg_class listings). The partition rotator
-- ticket (operator-driven) will create the next partition before
-- it is needed and drop the oldest past the retention horizon.
CREATE TABLE price_snapshot_p202605 PARTITION OF price_snapshot
    FOR VALUES FROM ('2026-05-01 00:00:00+00') TO ('2026-06-01 00:00:00+00');

-- Latest-snapshot lookup index (design §10.3). Backs
-- AssetSnapshotReader queries of the form
-- `SELECT ... FROM price_snapshot
--   WHERE asset = ? AND sub_verb = ? AND vs_currency = ?
--   ORDER BY captured_at DESC LIMIT 1`. The DESC ordering on the
-- index column lets the planner avoid a sort at query time.
-- Index is declared on the parent and propagates to all partitions.
CREATE INDEX idx_price_snapshot_lookup
    ON price_snapshot (asset, sub_verb, vs_currency, captured_at DESC);

-- ---------------------------------------------------------------------
-- Per-table GRANTs (docs/spec/security.md §DB roles, D34).
--
-- Collector writes (AssetSnapshotFetcher → PriceSnapshotStore inserts
-- one row per successful fetch); Provider reads only
-- (AssetSnapshotReader is the only Provider call site, M1-055c).
-- DELETE is REVOKEd from both service roles AND from PUBLIC —
-- retention is partition-drop (Invariant 6), and partition-drop is
-- operator-only (infochat_admin role). Grants on the parent
-- propagate to partitions per V7 precedent.
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON price_snapshot TO infochat_collector;
GRANT SELECT                 ON price_snapshot TO infochat_provider;
REVOKE DELETE ON price_snapshot FROM infochat_collector;
REVOKE DELETE ON price_snapshot FROM infochat_provider;
REVOKE DELETE ON price_snapshot FROM PUBLIC;

-- BIGSERIAL implies an underlying sequence; the Collector must hold
-- USAGE on it to INSERT (the partition-table INSERT triggers the
-- nextval call). Provider does not write so does not need USAGE.
GRANT USAGE ON SEQUENCE price_snapshot_id_seq TO infochat_collector;
