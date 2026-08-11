-- V70: at most one PENDING flagged_by='stage2' quarantine row per post
-- (M1-742).
--
-- M1-742 makes the first-pass Stage 2 row unconditional: EVERY
-- non-BENIGN verdict inserts its own whole-body flagged_by='stage2'
-- PENDING row (M1-739's "no PENDING row" dedup predicate and its FOR
-- UPDATE lock are removed — with no check there is no time-of-check to
-- race). The race class that remains is over-reporting, never
-- under-reporting: a concurrent duplicate Stage 2 evaluation of the
-- same post (an out-of-model duplicate-verdict phantom insert) would otherwise
-- double-list the post in the admin review queue. This partial unique
-- index bounds that: the duplicate's INSERT fails with a unique
-- violation instead of committing a second PENDING stage2 row.
--
-- Partial on (flagged_by='stage2' AND status='PENDING') so the audit
-- trail is untouched: closed stage2 rows (APPROVED / REJECTED /
-- BENIGN_CLOSED) accumulate freely, Stage 1 rows are unlimited (one
-- per regex hit by design), and a fresh PENDING stage2 row IS allowed
-- once the previous one is reviewed (the re-eval re-hide path relies
-- on that). The predicate matches exactly the rows
-- quarantine_review_view projects for the stage2 writer.

CREATE UNIQUE INDEX idx_quarantine_one_pending_stage2_row
    ON quarantine (post_id)
    WHERE flagged_by = 'stage2' AND status = 'PENDING';
