-- V9: §2.9.2 provider_state (per-channel high-water-mark cursor).
--
-- Lands the schema-layer commitment for the inter-service correctness
-- guarantee from docs/spec/architecture.md §Inter-service communication
-- and docs/spec/schema.md §Operational (Provider state).
--
-- ONE row per LISTEN/NOTIFY channel — `UNIQUE (channel)` is the
-- singleton-row-per-channel enforcement at the schema layer
-- (docs/spec/schema.md §Operational: "A UNIQUE constraint on channel
-- enforces the singleton-row-per-channel semantics at the schema
-- layer"). The cursor shape is channel-agnostic with per-channel
-- interpretation per docs/design/02-schema.md §2.9.2:
--
--   new_post          cursor_high = post.ready_at,
--                     cursor_low_kind = 'post',
--                     cursor_low_id   = post.id
--   quarantine_review cursor_high = quarantine.updated_at OR
--                                   post.status_changed_at,
--                     cursor_low_kind ∈ {'quarantine','post'},
--                     cursor_low_id   = quarantine id OR post id
--   new_price_snapshot (no row — best-effort, flush-on-Postgres-
--                     reconnect per §2.9.1)
--
-- The compound cursor (not cursor_high alone) is load-bearing per
-- docs/spec/schema.md §Operational: "The compound cursor (not
-- cursor_high alone) ensures two events sharing a high-key value are
-- both processed on catch-up — the earlier event advances the mark to
-- itself, the later event advances it to itself in the same
-- transaction as its side effect."
--
-- Atomic Flyway migration: the CREATE TABLE plus the GRANT/REVOKE and
-- first-boot INSERT apply in one transaction so a partial failure
-- rolls back cleanly.

CREATE TABLE provider_state (
    channel         TEXT        NOT NULL,
    cursor_high     TIMESTAMPTZ NOT NULL,
    cursor_low_kind TEXT        NOT NULL,
    cursor_low_id   TEXT        NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (channel)
);

-- ---------------------------------------------------------------------
-- Per-table GRANTs (aligned with docs/spec/security.md §DB roles).
--
-- The Provider owns the cursor: the catch-up reconciler and the LISTEN
-- worker both advance it through ProviderStateDao's compare-and-swap
-- UPDATE. The Collector reads the row for diagnostic / admin-status
-- purposes only (e.g. answering "is the Provider caught up?" on a
-- Collector-side /status path); the Collector NEVER writes the cursor.
--
-- REVOKE DELETE on both service roles AND on PUBLIC is the
-- defense-in-depth complement of the singleton UNIQUE (channel) — the
-- row is upserted, never deleted. A Provider with DELETE privilege
-- could drop the cursor row and force a full historical replay on
-- every restart; the REVOKE closes that path.
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON provider_state TO infochat_provider;
GRANT SELECT                 ON provider_state TO infochat_collector;

REVOKE DELETE ON provider_state FROM infochat_collector, infochat_provider, PUBLIC;

-- ---------------------------------------------------------------------
-- First-boot insert for the `new_post` channel — emitted by the
-- migration so the row exists before any Provider code runs.
--
-- ON CONFLICT (channel) DO NOTHING is the first-boot race guard per
-- docs/spec/schema.md §Operational: "Two fresh Provider instances
-- starting concurrently both attempt the insert, exactly one wins, and
-- the winning instance owns the cursor — no duplicate rows can be
-- produced by the first-insert race." The compound `(epoch, '', '')`
-- seed is the "no event yet" sentinel from docs/design/02-schema.md
-- §2.9.2; the CAS update overwrites all four columns on the first
-- real event because 'epoch' < any real ready_at on the leftmost
-- tuple component.
--
-- The `quarantine_review` row is NOT seeded here: that channel's
-- reconciler ships in M2 per docs/design/01-architecture.md §1.5
-- ("M1 only ships the new_post reconciler; the quarantine_review
-- reconciler lands in M2 alongside the admin quarantine-review
-- commands"). The `new_price_snapshot` channel does NOT maintain a
-- provider_state row at all (flush-on-Postgres-reconnect is the
-- correctness mechanism per §2.9.1).
-- ---------------------------------------------------------------------

INSERT INTO provider_state (channel, cursor_high, cursor_low_kind, cursor_low_id, updated_at)
VALUES ('new_post', 'epoch'::TIMESTAMPTZ, '', '', now())
ON CONFLICT (channel) DO NOTHING;
