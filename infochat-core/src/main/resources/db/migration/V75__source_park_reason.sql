-- V75: park-reason discriminator + re-probe state on source (M1-754, D42 as
-- amended by M1-752).
--
-- status='failed' has three writers with different recovery rights: the D42
-- consecutive-fetch-failure ladder (auto-re-probed), the Stage 2 UNKNOWN-rate
-- auto-disable and D38's all-relays-bad cycle cap (both manual-only security
-- parks). The row records which one fired; re-probe eligibility is decided on
-- that reason, never on bare status='failed' (schema.md §Sources and tags).
--
-- NO BACKFILL of pre-existing status='failed' rows (D42 property (c)):
-- they predate the distinction, may have been parked by either security
-- control, and a NULL reason is fail-closed (never re-probed, manual-only).
-- Recovering the already-parked corpus is an operator /source-enable, never
-- a migration side effect.
--
-- TEXT-with-CHECK over a Postgres ENUM, the V6 status/source_origin style,
-- so a future reason extends with one ALTER.

ALTER TABLE source
    ADD COLUMN park_reason TEXT
        CHECK (park_reason IN ('fetch-failure','unknown-rate','stream-cycle-cap')),
    ADD COLUMN parked_at TIMESTAMPTZ,
    ADD COLUMN reprobe_count INT NOT NULL DEFAULT 0,
    ADD COLUMN next_reprobe_at TIMESTAMPTZ,
    ADD COLUMN reprobe_restored_at TIMESTAMPTZ;

-- Column semantics:
--   park_reason         — why the row sits at status='failed'; NULL when not
--                         parked or parked before this migration (fail-closed).
--   parked_at           — record-only stamp for the parked-set summary's
--                         "parked since"; never read by decision logic.
--   reprobe_count       — absolute re-probe cap counter (D42). NOT cleared by
--                         a successful restore; cleared only after the
--                         sustained-success window (flap containment).
--   next_reprobe_at     — backoff state owned by the re-probe job, which both
--                         writes and reads it on its injected Clock (no
--                         app-vs-DB clock split; engineering-rules §9).
--   reprobe_restored_at — sustained-success-window anchor set by the restore.

-- Provider grant: /source-enable resets park/re-probe state in the same
-- UPDATE that sets status='active', so the V31 column-scoped UPDATE grant is
-- extended BY NAMING the new columns (security.md §DB roles: the column list
-- is a closed enumeration that grows only by explicit, column-scoped
-- extension). Identity columns (kind, identifier, display_name, category,
-- added_by) stay revoked — never GRANT UPDATE ON source wholesale.
-- The Collector needs no grant change: V6 gives it table-wide UPDATE.
GRANT UPDATE (park_reason, parked_at, reprobe_count, next_reprobe_at, reprobe_restored_at)
    ON source TO infochat_provider;
