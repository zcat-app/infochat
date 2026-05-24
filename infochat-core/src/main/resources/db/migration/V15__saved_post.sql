-- V15: §2.6.1 saved_post (Invariant 1 carve-out, Invariant 6 carve-out).
--
-- Lands the per-user-global saved-post library from
-- docs/design/02-schema.md §2.6.1: one table plus the
-- save_count-maintaining trigger pair. `/save`, `/saved`, `/unsave`
-- (M1-052) consume this surface; `/forget` (T2-E) and `/export`
-- (T2-E) join on user_id later.
--
-- Per-user-globally (decision D13, spec §Per-user state):
-- saved_post carries `user_id` ONLY — no `scope_kind` / `scope_id`
-- discriminator columns. This is the documented exception to
-- Invariant 1 (per-(user, scope) isolation): a save made in DM is
-- visible from any group the user is in, and vice versa.
--
-- Snapshot-on-save (Invariant 6 carve-out): the body / title / url /
-- author / published_at / source_id / snapshot_tags columns are
-- copied at /save time and never re-resolved against `post`. The
-- post-partition-drop TTL on `post` (Invariant 6) does not break the
-- bookmark — the snapshot survives.
--
-- save_count denormalization on `users` (V5 declared the column;
-- this migration lands the triggers that maintain it). The
-- 1000-save cap (per-user) is enforced application-side BEFORE
-- INSERT by reading users.save_count under SELECT ... FOR UPDATE on
-- the actor's users row; the trigger keeps the counter consistent
-- across concurrent ops.
--
-- Atomic Flyway migration: the whole file applies in one transaction
-- so a partial failure rolls back cleanly.

-- ---------------------------------------------------------------------
-- 2.6.1 saved_post (D13, Invariant 1 carve-out, Invariant 6 carve-out)
--
-- PRIMARY KEY (user_id, post_uid) — per-user-global; a duplicate /save
-- against the same (user, uid) pair collides at the storage layer
-- and the handler surfaces the friendly error.save.already_saved
-- reply.
--
-- source_id keeps a soft FK to source(id) so /list-sources
-- --include-deleted can join sources for the bookmark display. The
-- FK carries NO ON DELETE CASCADE — Invariant 4 (soft-delete only).
--
-- post_uid is TEXT (the spec UID, not a UUID FK to post.id) because
-- `post` is partitioned by fetched_at and the partition-drop TTL
-- will remove the underlying row; the snapshot is the durable
-- representation.
-- ---------------------------------------------------------------------

CREATE TABLE saved_post (
    user_id       UUID NOT NULL REFERENCES users(id),
    post_uid      TEXT NOT NULL,
    saved_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Snapshot fields (Invariant 6 — copied at /save time, never re-resolved
    -- against post). source_id stays as a soft FK so /list-sources
    -- --include-deleted can join sources for the bookmark display.
    source_id     UUID NOT NULL REFERENCES source(id),
    title         TEXT NOT NULL,
    body          TEXT,
    url           TEXT,
    author        TEXT,
    published_at  TIMESTAMPTZ,
    snapshot_tags TEXT[] NOT NULL DEFAULT '{}',

    -- User annotations:
    personal_tags TEXT[] NOT NULL DEFAULT '{}',
    note          TEXT,

    PRIMARY KEY (user_id, post_uid)
);

CREATE INDEX idx_saved_user_personal_tags ON saved_post USING gin (personal_tags);
CREATE INDEX idx_saved_user_at            ON saved_post(user_id, saved_at DESC);

-- ---------------------------------------------------------------------
-- save_count denormalization triggers (powers the 1000-save cap in O(1)).
--
-- The handler reads users.save_count under SELECT ... FOR UPDATE on
-- the actor's row to enforce the cap atomically; the trigger keeps
-- the counter aligned with the row count without an explicit
-- application-tier UPDATE on every /save or /unsave.
--
-- AFTER INSERT / AFTER DELETE fire in the same transaction as the
-- saved_post mutation; the trigger's UPDATE rolls back together with
-- the underlying mutation on any tx-wide rollback (so a /save that
-- aborts on the cap check or on a SQLException cannot leave the
-- counter desynced from the row count).
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION trg_saved_post_count()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE users SET save_count = save_count + 1 WHERE id = NEW.user_id;
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE users SET save_count = save_count - 1 WHERE id = OLD.user_id;
        RETURN OLD;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_saved_post_count_ins
    AFTER INSERT ON saved_post
    FOR EACH ROW EXECUTE FUNCTION trg_saved_post_count();

CREATE TRIGGER trg_saved_post_count_del
    AFTER DELETE ON saved_post
    FOR EACH ROW EXECUTE FUNCTION trg_saved_post_count();

-- ---------------------------------------------------------------------
-- Per-table GRANTs (aligned with docs/spec/security.md §DB roles).
--
-- saved_post is Provider-write: /save, /unsave, /forget mutate
-- through the Provider. Collector reads nothing here (Collector's
-- only saved_post interaction is via potential future ingest-tier
-- joins, which v1 does not have). T2-E's /forget runs DELETE on
-- saved_post under the Provider role.
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, DELETE ON saved_post TO infochat_provider;
