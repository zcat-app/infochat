-- V61: digest_replay_state — persisted render output + per-category delivery
-- records (M1-652).
--
-- Lands the two tables the gap-filling /retry --digest path needs so a
-- mid-sequence Provider death stops silently losing undelivered digest
-- categories. arm (b) of the M1-652 fork (user decision 2026-07-20): the
-- rendered section list is PERSISTED at render time as the exact delivery
-- bytes, and /retry --digest replays only the categories with no delivery
-- record — deterministic, byte-faithful, LLM-free. A full re-run fallback
-- covers section-less slots (pre-V61 rows, degraded slots, zero-post slots,
-- crash-stranded cache rows); see docs/spec/commands.md §Conversation control
-- and decision D65.
--
-- Both tables are keyed by (group_id, window_start, category_slug) — the SAME
-- tuple DigestDelivery composes the per-(slot, category) correlationId from
-- (DigestDelivery.java:82), so the persisted delivery-state key and the
-- outbound correlationId cannot drift. window_start joins summary_cache.
-- slot_fired_at exactly (the cache upsert keys on windowStart ==
-- DigestSlot.windowStart()); slot_kind is omitted because morning/evening
-- slots use distinct center hours (DigestScheduler), so (group_id,
-- window_start) cannot collide across slot kinds — the pinned PKs are safe.
--
-- Atomic Flyway migration: both CREATE TABLEs + grants apply in one
-- transaction so a partial failure rolls back cleanly.

-- ---------------------------------------------------------------------
-- digest_section — the persisted render output, one row per section in
-- renderSections() order. Written alongside the summary_cache upsert at
-- render time (DigestWorker.executeSlot, after renderSections() returns
-- and before per-category delivery) so a crash between render and deliver
-- leaves the sections durably readable for replay. Deleted-then-inserted
-- atomically per slot (a slot's prior sections are wiped before the new
-- list is written, in one transaction with the matching delivery-record
-- wipe — see DigestSectionRepository.replaceSlotSections — so replay never
-- half-applies when a regeneration supersedes an existing row).
--
-- group_id        Internal groups.id; joins summary_cache.group_id.
-- window_start    DigestSlot.windowStart(); joins summary_cache.slot_fired_at.
-- category_slug   The section's tag string as-is, literal "other" for the
--                 null (Other) bucket — identical to DigestDelivery.java:81.
-- position        0-based list index of the section in renderSections()
--                 output; drives replay's position-order traversal.
-- content         The EXACT delivery bytes — RenderedSection.text(),
--                 affordance folded into the last section and flag-on
--                 roll-up prefixes inside their sections (M1-642 arm-(b) pin).
-- ---------------------------------------------------------------------

CREATE TABLE digest_section (
    group_id       UUID NOT NULL,
    window_start   TIMESTAMPTZ NOT NULL,
    category_slug  TEXT NOT NULL,
    position       INT NOT NULL,
    content        TEXT NOT NULL,
    PRIMARY KEY (group_id, window_start, category_slug)
);

-- position-order traversal of a slot's sections. The PK already covers
-- (group_id, window_start) lookups via the prefix, but a position-ordered
-- read would otherwise need a sort; this secondary index keeps the replay
-- path a single ordered scan.
CREATE INDEX idx_digest_section_slot_order
    ON digest_section (group_id, window_start, position);

-- ---------------------------------------------------------------------
-- digest_category_delivery — the per-(slot, category) delivery record.
-- A row exists iff the adapter accepted that category's message (recorded
-- by the delegating MessagingAdapter wrapper inside DigestDelivery on a
-- normal return from send(); a failed send records nothing, so the existing
-- per-category TRANSIENT/PERMANENT ladder is unchanged). /retry --digest
-- reads this to compute which categories still need sending and skips the
-- rest — the gap-fill.
--
-- delivered_at is a record-only write (it logs WHEN the adapter accepted,
-- it gates nothing), so it stays on SQL now() rather than the injected
-- java.time.Clock the replay decision gates use (CLAUDE.md §Injectable
-- time in decision logic — the display/record exemption).
-- ---------------------------------------------------------------------

CREATE TABLE digest_category_delivery (
    group_id       UUID NOT NULL,
    window_start   TIMESTAMPTZ NOT NULL,
    category_slug  TEXT NOT NULL,
    delivered_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, window_start, category_slug)
);

-- ---------------------------------------------------------------------
-- Per-table GRANTs (aligned with docs/spec/security.md §DB roles, V60's
-- narrow-grant posture the M1-648 r2 CLEAN audit verified).
--
-- Both tables are Provider-owned: DigestWorker writes sections at render
-- time, DigestDelivery records deliveries on adapter acceptance, and the
-- retry path reads both. The replace shape is DELETE-then-INSERT (one
-- transaction), the delivery record is idempotent INSERT ON CONFLICT DO
-- NOTHING, and pruning is a prefix DELETE — so INSERT + DELETE + SELECT
-- is the full surface. UPDATE is withheld and REVOKEd: nothing in the
-- Provider ever needs to mutate a row in place (every change is a full
-- slot-replace or a no-op-conflict insert), and withholding UPDATE keeps
-- the grant surface minimal in case a role-inheritance surprise widens
-- the defaults.
--
-- The Collector is granted NOTHING on either table. Replay state is
-- Provider-only; the Collector's summary_cache grants (V23/V46) are
-- unchanged.
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, DELETE ON digest_section TO infochat_provider;
REVOKE UPDATE ON digest_section FROM infochat_provider;
REVOKE UPDATE ON digest_section FROM PUBLIC;

GRANT SELECT, INSERT, DELETE ON digest_category_delivery TO infochat_provider;
REVOKE UPDATE ON digest_category_delivery FROM infochat_provider;
REVOKE UPDATE ON digest_category_delivery FROM PUBLIC;
