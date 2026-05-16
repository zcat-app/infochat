-- V10: §2.5.1 quarantine table + view + per-table GRANTs.
--
-- Lands the security audit surface for Stage 1 (and the future
-- Stage 2) per docs/design/02-schema.md §2.5.1. One row per Stage-1
-- regex hit; one row per Stage-1 watchdog abort (whole-body span);
-- a future Stage-2 row per LLM-judge non-BENIGN verdict (flagged_by
-- = 'stage2', landed by M1-033).
--
-- The original suspicious span (or the whole body on a watchdog
-- abort) is preserved in `original_html` so an admin can review the
-- exact bytes that tripped the filter. The Provider role MUST NOT
-- see this column — admins read the redacted shape via
-- `quarantine_review_view`, which deliberately omits `original_html`
-- from its SELECT list. Raw `original_html` is admin-only
-- (psql / future T2-G stored procedures); the role isolation is the
-- defense against an LLM-output-injection vector that smuggles the
-- raw span back through the Provider chat surface (docs/spec/security.md
-- §DB roles).
--
-- The §2.5.2 `approve_quarantine` / `reject_quarantine` stored
-- procedures (T2-G territory) and their EXECUTE grants are
-- INTENTIONALLY ABSENT from V10. Those procedures land alongside
-- the `/quarantine approve` / `/quarantine reject` admin commands
-- in T2-G; landing them now would expose unused server-side
-- privilege surface in M1 without a caller. V10 contains only the
-- table, the view, the per-table GRANTs, and the three indexes.
-- (The CREATE TABLE FOREIGN KEY references users(id) for
-- `reviewed_by` exists, but no FK to post(id, fetched_at): the post
-- table is partitioned and Invariant 6 commits to partition-drop
-- TTL, so a long-lived quarantine row must survive its source
-- partition's drop — the cross-partition FK would block that drop.
-- The `post_uid` denormalization is the survival mechanism.)
--
-- Atomic Flyway migration: the CREATE TABLE + indexes + view + GRANTs
-- apply in one transaction. A partial failure rolls back cleanly so
-- the schema cannot half-apply.

CREATE TABLE quarantine (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id         UUID        NOT NULL,
    post_uid        TEXT        NOT NULL,
    post_fetched_at TIMESTAMPTZ NOT NULL,
    flagged_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    flagged_by      TEXT        NOT NULL
                                CHECK (flagged_by IN ('stage1','stage2')),
    rule_id         TEXT,
    span_start      INT,
    span_end        INT,
    original_html   TEXT        NOT NULL,
    placeholder_id  TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING'
                                CHECK (status IN ('PENDING','BENIGN_CLOSED','APPROVED','REJECTED')),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_by     UUID        REFERENCES users(id),
    review_note     TEXT
);

-- Indexes per docs/design/02-schema.md §2.5.1: the admin review queue
-- scans (status, flagged_at) for PENDING work; the per-post lookup
-- uses post_uid; the quarantine_review NOTIFY cursor scans
-- (updated_at, id) for state-machine transitions newer than the
-- Provider's high-water mark (M2 territory).
CREATE INDEX idx_quarantine_status         ON quarantine (status, flagged_at);
CREATE INDEX idx_quarantine_post           ON quarantine (post_uid);
CREATE INDEX idx_quarantine_review_cursor  ON quarantine (updated_at, id);

-- Provider-role view: every column from `quarantine` EXCEPT
-- `original_html`. The Provider holds SELECT on the view only and
-- NO privilege on the underlying `quarantine` table, so a buggy
-- Provider-side path cannot leak the raw span back to a user even
-- if the LLM-output sanitizer (T1-F) regresses.
CREATE OR REPLACE VIEW quarantine_review_view AS
SELECT id, post_id, post_uid, post_fetched_at, flagged_at, flagged_by,
       rule_id, span_start, span_end, placeholder_id, status, updated_at,
       reviewed_by, review_note
  FROM quarantine;

-- ---------------------------------------------------------------------
-- Per-table GRANTs (aligned with docs/spec/security.md §DB roles).
--
-- Collector is the ONLY M1 writer of `quarantine`. Stage 1
-- (M1-032) INSERTs one row per regex hit and one row per watchdog
-- abort; the future Stage 2 (M1-033) UPDATEs the row's status to
-- BENIGN_CLOSED on a BENIGN verdict (no Provider-side write path
-- in M1). The Collector therefore needs SELECT + INSERT + UPDATE.
--
-- Provider gets SELECT on the redacted view ONLY — it never reads
-- `quarantine` directly, so `original_html` is unreachable from the
-- Provider role even with the worst-case SQL injection. The REVOKE
-- ALL ON quarantine_review_view FROM PUBLIC is the defense-in-depth
-- complement to the explicit Provider GRANT — a stray Postgres
-- principal with PUBLIC-only privileges still cannot read the view.
--
-- NO GRANT on `quarantine` to `infochat_provider`: this is the
-- spec-load-bearing rule from docs/spec/security.md §DB roles
-- ("the Provider has NO SELECT on quarantine.original_html"). The
-- Provider's quarantine review path goes exclusively through the
-- view.
--
-- T2-G's `/quarantine approve` / `/quarantine reject` admin commands
-- write through the §2.5.2 stored procedures (not landed in V10).
-- The procedures' EXECUTE grants would name `infochat_provider` or
-- a dedicated admin role; those grants are absent from V10 because
-- the procedures themselves are absent from V10.
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON quarantine TO infochat_collector;

REVOKE ALL ON quarantine_review_view FROM PUBLIC;
GRANT SELECT ON quarantine_review_view TO infochat_provider;
