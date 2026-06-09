-- V47: partial index for the ReEvaluationJob candidate scan.
--
-- ReEvaluationJob.enumerateCandidates runs every poll tick (5m) with a
-- disjunctive predicate over stage2_failed / stage2_verdict /
-- re_eval_attempts and a fetched_at >= now() - (retention horizon + slack)
-- floor. No existing index covers that disjunction (the V7 post indexes key
-- on status/ready_at/status_changed_at), so before this index the planner
-- scanned the surviving partitions' heaps in full.
--
-- The partial WHERE is column-for-column the scan's disjunction, so the
-- index holds ONLY candidate rows (a tiny fraction of post) — it stays small
-- and the planner prefers it for the candidate scan. The leading
-- (fetched_at, id) columns serve both halves the scan needs together: the
-- fetched_at floor (range probe) and the ORDER BY fetched_at, id. fetched_at
-- is the RANGE partition key, so partition pruning from the floor and this
-- local index compose — pruning drops whole partitions, the index orders the
-- candidates inside the survivors.
--
-- post is partitioned, so CREATE INDEX on the parent propagates a matching
-- partial index to every child partition (same mechanism as V7's
-- idx_post_ready_at partial index). No CONCURRENTLY: Flyway migrations run in
-- a transaction and a fresh DB has no rows to lock.
--
-- No per-partition GRANTs: index maintenance rides the parent-table DML
-- privileges (same precedent as every other post index).

CREATE INDEX idx_post_reeval_candidate ON post (fetched_at, id)
    WHERE (stage2_failed = TRUE AND status <> 'NEEDS_REVIEW')
       OR (status = 'QUARANTINED' AND stage2_done = TRUE AND stage2_failed = FALSE
           AND (stage2_verdict = 'UNKNOWN' OR re_eval_attempts > 0));
