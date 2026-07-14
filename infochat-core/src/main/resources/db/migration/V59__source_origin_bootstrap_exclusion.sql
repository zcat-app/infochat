-- V59: source.source_origin discriminator + per-scope bootstrap exclusions
-- (M1-621, decision D59 — implicit bootstrap corpus).
--
-- Bootstrap sources become an implicit public corpus every scope retrieves;
-- custom (/add-source'd) sources stay private to their subscribers. The
-- world predicate at every retrieval/digest site becomes:
--   (source_origin = 'bootstrap' AND deleted_at IS NULL
--        AND NOT excluded by this scope)
--   OR source_id IN (this scope's source_subscription).
--
-- source_origin mirrors the existing tag.source_origin pattern (V6:81-82):
-- TEXT with a closed two-value CHECK, not a Postgres ENUM, so v2 can extend
-- with one ALTER.
--
-- ONE fail-closed default: 'user' for existing rows AND future inserts.
-- Pre-V59 rows are NOT presumed operator-seeded (red-team 2026-07-14: a
-- pre-upgrade /add-source'd private custom must never be publicized by the
-- migration). BootstrapLoader's ON CONFLICT promote runs in the same
-- collector boot — Flyway (@Priority 100) before the @Startup loader
-- (@Priority 200) — labelling exactly the operator-listed rows
-- 'bootstrap', so nothing is mislabelled and the implicit corpus goes
-- live within the same startup; a mid-boot query merely sees the pre-V59
-- subscription-only world.

ALTER TABLE source
    ADD COLUMN source_origin TEXT NOT NULL DEFAULT 'user'
        CHECK (source_origin IN ('bootstrap','user'));

-- ---------------------------------------------------------------------
-- source_exclusion: a scope's opt-out of one bootstrap source
-- (/unfollow-source on a bootstrap source; /follow-all-sources clears
-- the calling scope's rows). Shape mirrors source_subscription (V7):
-- the PK is the lookup the world predicate's NOT EXISTS probe uses.
-- A separate table, NOT a flag on source_subscription — a kind column
-- there would silently change the semantics of every existing
-- "source_id IN (SELECT ... FROM source_subscription)" subquery.
-- created_at only records time (DB-clock exempt, engineering-rules §9).
-- ---------------------------------------------------------------------

CREATE TABLE source_exclusion (
    scope_kind TEXT NOT NULL CHECK (scope_kind IN ('dm','group')),
    scope_id   UUID NOT NULL,
    source_id  UUID NOT NULL REFERENCES source(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    PRIMARY KEY (scope_kind, scope_id, source_id)
);

-- Provider owns the exclusion lifecycle (insert on /unfollow-source,
-- delete on /follow-all-sources) and reads it in every world-predicate
-- query. No UPDATE: rows are insert/delete-only. Collector reads it
-- nowhere today; SELECT mirrors the source_subscription posture (V7)
-- so collector-side eligibility joins stay possible without a grant
-- migration.
GRANT SELECT, INSERT, DELETE ON source_exclusion TO infochat_provider;
GRANT SELECT                 ON source_exclusion TO infochat_collector;
