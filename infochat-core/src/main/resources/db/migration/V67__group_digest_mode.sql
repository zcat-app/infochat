-- V67: per-group digest verbosity mode — additive column on groups.
--
-- digest_mode selects how each category body of the group's periodic
-- digest renders (M1-732): 'brief' (true-count header + roll-up),
-- 'normal' (header + roll-up + up to
-- infochat.digest.category-headline-count bare headlines — the default),
-- or 'full' (the pre-M1-732 per-cluster prose). Mutated at runtime by
-- /digest brief|normal|full (M1-733). DigestWorker.readGroupMetadata is
-- the SQL-deserialization boundary: a NULL or unrecognized value resolves
-- to 'normal' with one WARN, so a row written out-of-band can never
-- break the render path.
--
-- Additive only: no DROP, no statement touches another table. Adding a
-- column with a constant DEFAULT is metadata-only on PostgreSQL 11+ (no
-- table rewrite — the argument V44__group_digest_enabled.sql:13-17 makes
-- for digest_enabled), so every existing and future group defaults to
-- 'normal' with no backfill. The CHECK constraint pins the closed set
-- DigestRenderer.DigestMode mirrors. Flyway wraps this script in a
-- single transaction.

ALTER TABLE groups ADD COLUMN digest_mode TEXT NOT NULL DEFAULT 'normal'
    CHECK (digest_mode IN ('brief', 'normal', 'full'));
