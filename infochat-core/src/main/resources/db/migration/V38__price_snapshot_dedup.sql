-- V38: §Operational price_snapshot dedup invariant (D39).
--
-- spec schema.md §Operational — Price snapshot mandates one row per
-- (asset, sub_verb, captured_at). V17 declared PRIMARY KEY
-- (id, captured_at) (the partition key must participate in every
-- PK/UNIQUE on a partitioned table; the surrogate id keeps the PK
-- narrow) but carried no replacement UNIQUE for the spec triple, so
-- duplicate snapshots could make the latest-snapshot read
-- (ORDER BY captured_at DESC LIMIT 1, no further tiebreaker)
-- nondeterministic. The UNIQUE is legal on the partitioned parent
-- because captured_at is the partition key; it propagates to all
-- partitions. See docs/design/10-asset-commands.md
-- §"price_snapshot dedup & notify decisions".
--
-- Writer contract: PriceSnapshotStore (the table's only writer)
-- INSERTs with ON CONFLICT (asset, sub_verb, captured_at) DO NOTHING
-- — the table is INSERT-only (spec: "no updates"), so a duplicate
-- write is dropped, never updated.

ALTER TABLE price_snapshot
    ADD CONSTRAINT price_snapshot_dedup_uq
    UNIQUE (asset, sub_verb, captured_at);
