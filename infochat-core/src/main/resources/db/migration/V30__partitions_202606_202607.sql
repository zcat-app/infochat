-- V30: June + July 2026 range partitions for all five partitioned tables.
--
-- The bootstrap partitions in V7/V11/V17/V28/V29 cover only May 2026
-- ('2026-05-01' .. '2026-06-01'). With no DEFAULT partition (deliberate —
-- a fallback bucket would silently swallow out-of-range rows and could
-- never be dropped per Invariant 6), the first INSERT at or after
-- '2026-06-01' fails with "no partition of relation … found for row".
-- This migration is the immediate data-shape unblock: it adds the June and
-- July partitions ahead of need so inserts succeed on the month boundary.
-- The durable mechanism is the application-tier PartitionCreator scheduler,
-- which provisions each following month's partition before it is needed.
--
-- No per-partition GRANTs: DML privileges are granted on the parent tables
-- (V7/V11/V17/V28/V29) and propagate to every partition (same precedent as
-- the _202605 bootstrap partitions, which carry no GRANTs of their own).
--
-- Naming mirrors each parent's convention: <table>_YYYYMM, except
-- price_snapshot which uses the price_snapshot_pYYYYMM form (V17).

CREATE TABLE post_202606 PARTITION OF post
    FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');
CREATE TABLE post_202607 PARTITION OF post
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');

CREATE TABLE post_embedding_202606 PARTITION OF post_embedding
    FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');
CREATE TABLE post_embedding_202607 PARTITION OF post_embedding
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');

CREATE TABLE price_snapshot_p202606 PARTITION OF price_snapshot
    FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');
CREATE TABLE price_snapshot_p202607 PARTITION OF price_snapshot
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');

CREATE TABLE post_entity_202606 PARTITION OF post_entity
    FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');
CREATE TABLE post_entity_202607 PARTITION OF post_entity
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');

CREATE TABLE post_reference_202606 PARTITION OF post_reference
    FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');
CREATE TABLE post_reference_202607 PARTITION OF post_reference
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');
