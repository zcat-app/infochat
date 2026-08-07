-- V79: Stage 1 body remediation marker — at-most-once conversion gate
-- for pre-M1-784 stored bodies (M1-786).
--
-- Rows written before M1-784's plain-text sink — and the saved_post
-- snapshots copied from them — carry the old serializer's output
-- (allowlisted tags standing, punctuation as numeric entities). The
-- remediation job (Stage1BodyRemediationJob) converts them through the
-- same code path the live pipeline uses; these columns are the gate
-- that makes the conversion at-most-once per row as a property of the
-- schema, not of the operator remembering (P14: after conversion a row
-- can legitimately contain a raw `<` — `use <b> for bold` — and a
-- second pass would strip it).
--
-- NULL means "not known to be in the post-M1-784 representation". Two
-- writers stamp it: Stage1Pipeline on every body write (a fresh Stage 1
-- output IS the plain-text representation — the job's unescape+parse
-- conversion is not a no-op on new-format text, so new posts must never
-- enter its batch), and the job after converting a row. The job also
-- stamps saved_post rows that byte-match a stamped post.body (a /save
-- snapshot copies the post's body, so byte-equality certifies the
-- representation); the rest go through conversion.
--
-- Nullable with no DEFAULT: no table rewrite on either table. `post` is
-- declaratively partitioned; the ADD COLUMN and the parent index
-- propagate to every existing and future partition. The partial
-- indexes keep the job's per-tick NULL-marker pickup cheap once the
-- corpus is drained.
--
-- Grants: the collector role holds table-level SELECT/INSERT/UPDATE on
-- `post` (V7), which covers the new column. It holds NO privilege on
-- `saved_post` (V15 granted the provider role only), and the job
-- connects as `infochat_collector`, so the grants below are required.
-- The UPDATE is column-scoped (V62's pattern): the job sets only body
-- and body_remediated_at, and saved_post is provider-owned user state
-- (docs/spec/security.md §DB roles profiles the collector as
-- "INSERT/UPDATE on ingest-owned tables … SELECT on the rest"), so a
-- table-wide grant would hand a collector SQL-injection foothold write
-- access to bookmark titles, tags and annotations it never needs.
--
-- Interaction audit (workflow §Migrations): no higher-numbered V*.sql
-- exists on main (V78 is the top) or in the sibling worktrees
-- (M1-776, M1-777, M1-779 — all three add no migration), so there is
-- no higher-numbered migration whose interaction needs auditing.

ALTER TABLE post ADD COLUMN body_remediated_at TIMESTAMPTZ;
ALTER TABLE saved_post ADD COLUMN body_remediated_at TIMESTAMPTZ;

CREATE INDEX idx_post_body_remediation_pending
    ON post (fetched_at)
    WHERE body_remediated_at IS NULL;

CREATE INDEX idx_saved_post_body_remediation_pending
    ON saved_post (saved_at)
    WHERE body_remediated_at IS NULL;

GRANT SELECT ON saved_post TO infochat_collector;
GRANT UPDATE (body, body_remediated_at) ON saved_post TO infochat_collector;
