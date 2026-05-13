-- V6: §2.2 Sources & tags catalogues.
--
-- Lands the §2.2.1 source and §2.2.2 tag tables from
-- docs/design/02-schema.md. Both are global catalogue tables: no
-- per-(user, scope) discriminator, no user-content rows. The
-- bootstrap loader and the (later) /add-source / /follow-tag
-- command handlers are the privileged write paths; the per-scope
-- subscription join is M1-008c.
--
-- Atomic Flyway migration: the whole file applies in one
-- transaction so a partial failure rolls back cleanly.
--
-- FK note: source.added_by / source.deleted_by / tag.created_by
-- reference users(id) from V5. V5 must apply before V6; the
-- migration version ordering encodes that and the M1-008b ticket
-- documents the practical wait-for-M1-008a constraint.

-- ---------------------------------------------------------------------
-- 2.2.1 source (D7, D38, D42, Invariant 4)
--
-- The (kind, identifier) UNIQUE is the upsert key the bootstrap
-- loader and /add-source target (D38). status is a closed three-state
-- machine ('active' | 'failed' | 'disabled'); the schema enforces the
-- set, the application layer drives the transitions (D42's per-source
-- failure counter feeds the active → failed flip). deleted_at /
-- deleted_by encode soft-delete; raw DELETE is REVOKEd from both
-- service roles below (Invariant 4 — soft-delete only).
-- bootstrap_tags is a scalar TEXT[] (D22 tagger fallback); the
-- per-element normalization invariant is application-tier (Postgres
-- has no portable per-array-element CHECK in v1).
-- The partial activity index supports the fetcher / stream-worker
-- scheduler's SELECT on status WHERE deleted_at IS NULL.
-- ---------------------------------------------------------------------

CREATE TABLE source (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind                 TEXT NOT NULL,
    identifier           TEXT NOT NULL,
    config               JSONB NOT NULL DEFAULT '{}'::JSONB,
    display_name         TEXT NOT NULL,
    category             TEXT NOT NULL,
    bootstrap_tags       TEXT[] NOT NULL DEFAULT '{}',
    status               TEXT NOT NULL DEFAULT 'active'
                         CHECK (status IN ('active','failed','disabled')),
    added_by             UUID REFERENCES users(id) ON DELETE SET NULL,
    added_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_fetch_at        TIMESTAMPTZ,
    last_success_at      TIMESTAMPTZ,
    consecutive_failures INT NOT NULL DEFAULT 0,
    deleted_at           TIMESTAMPTZ,
    deleted_by           UUID REFERENCES users(id) ON DELETE SET NULL,
    UNIQUE (kind, identifier)
);

CREATE INDEX idx_source_status ON source(status) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------
-- 2.2.2 tag (D5 Tier-1 controlled vocabulary)
--
-- name is the normalized storage form (NFC + Locale.ROOT lower-case
-- + character-class filter happen in the application-tier
-- normalizer); the CHECK regex is the second line of defense at the
-- storage layer. The class allows trailing hyphens (the spec prose
-- talks about "internal hyphens" but the canonical regex
-- ^[a-z0-9][a-z0-9-]{0,47}$ admits a terminal hyphen; the
-- prose-vs-regex gap is a separate spec-quality concern).
-- display preserves the original casing for user-facing output.
-- source_origin is a closed two-value set distinguishing bootstrap-
-- seeded vocabulary from /add-source --tags additions; TEXT-with-
-- CHECK over a Postgres ENUM so v2 can extend with one ALTER.
-- Vocabulary is append-only in v1 (spec §Vocabulary lifecycle).
-- ---------------------------------------------------------------------

CREATE TABLE tag (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          TEXT NOT NULL
                  CHECK (name ~ '^[a-z0-9][a-z0-9-]{0,47}$'),
    display       TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID REFERENCES users(id),
    source_origin TEXT NOT NULL DEFAULT 'bootstrap'
                  CHECK (source_origin IN ('bootstrap','user')),
    UNIQUE (name)
);

-- ---------------------------------------------------------------------
-- Per-table GRANTs (aligned with docs/spec/security.md §DB roles and
-- docs/design/04-security.md §infochat_collector / §infochat_provider).
--
-- source is Collector-write: the bootstrap loader's idempotent upsert
-- writes via INSERT; the fetcher writes status / last_fetch_at /
-- last_success_at / consecutive_failures via UPDATE. Provider is
-- read-only on source (joins for /list-sources et al); any Provider-
-- initiated source writes (/add-source, /source-enable, etc.) route
-- through a future handoff path and are out of scope for this
-- migration.
-- tag is likewise Collector-write (the tagger writes new vocabulary
-- during ingest; the bootstrap loader seeds the initial vocabulary on
-- Collector startup) and Provider-read.
-- DELETE on source is REVOKEd from both service roles (Invariant 4 —
-- soft-delete only; infochat_admin is the sole DELETE path).
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON source TO infochat_collector;
GRANT SELECT                ON source TO infochat_provider;
REVOKE DELETE ON source FROM infochat_collector;
REVOKE DELETE ON source FROM infochat_provider;
REVOKE DELETE ON source FROM PUBLIC;

GRANT SELECT, INSERT, UPDATE ON tag TO infochat_collector;
GRANT SELECT                ON tag TO infochat_provider;
