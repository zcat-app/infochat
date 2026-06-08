-- V44: per-group digest delivery gate — additive column on groups.
--
-- digest_enabled is the on/off switch for a group's periodic
-- morning/evening digest, mutated at runtime by /digest on|off
-- (group admin or bot admin). The digest scheduler ANDs this flag
-- into its group-selection query, so a paused group is never
-- selected: no scheduled digest is computed, cached, or sent while
-- paused. It is independent of approval_status and removed_at — a
-- group can be approved and present yet paused — and lives on groups
-- next to timezone (V5:159) so both digest-scheduling knobs sit
-- together and the scheduler change stays a single predicate.
--
-- Additive only: no DROP, no CHECK alteration, no statement touches
-- another table. Adding a column with a constant DEFAULT is
-- metadata-only on PostgreSQL 11+ (no table rewrite), so every
-- existing and future group defaults to digests-on with no backfill.
-- Flyway wraps this script in a single transaction.

ALTER TABLE groups ADD COLUMN digest_enabled BOOLEAN NOT NULL DEFAULT true;
