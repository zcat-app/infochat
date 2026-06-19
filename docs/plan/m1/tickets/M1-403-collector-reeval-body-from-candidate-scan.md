---
id: M1-403
title: "collector: read re-eval body from the candidate scan"
status: pending
created: 2026-06-19
last_updated: 2026-06-19
blocked_by: []
files_budget: 3
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The splice/placeholder reconstruction algorithm (splicePlaceholders) — unchanged; only HOW the body is obtained changes, not how placeholders are spliced.
  - The quarantine-originals read (SELECT placeholder_id, original_html FROM quarantine ...) — stays a single query; only the separate per-candidate post.body read and its dedicated connection are removed.
  - The re-eval candidate selection criteria, batch size cap, cooldown, and partition-pruning window — unchanged.
acceptance:
  - "enumerateCandidates carries post.body in the ReEvalCandidate row (the candidate scan already selects from post), and reconstructOriginalBody no longer opens its own dataSource.getConnection() nor issues a separate SELECT body FROM post per candidate — it reconstructs from the already-loaded body and reads only the quarantine originals."
  - "NULL-body handling is preserved: a re-eval candidate whose post.body is NULL is still rejected (the prior readPostBody IllegalStateException semantics are kept at the scan boundary), not silently reconstructed as an empty body."
  - "Re-eval verdict behavior for existing candidates is unchanged: ReEvaluationJobTest and the reeval integration tests (ReEvalVerdictNotifyIT, ReEvaluationJobInfraFailureFanOutIT, ReEvaluationJobScheduledPathIT) remain green."
  - "A test in the eval/reeval package asserts reconstructOriginalBody returns the correctly spliced original body using the candidate-carried body (no second post read)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval (candidate-carried-body reconstruction test)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-403: read re-eval body from the candidate scan

## Context

Deep-review full (2026-06-19) collector finding **F1** (PERFORMANCE). Verified at
source 2026-06-19:

`ReEvaluationJob.reconstructOriginalBody`
(`infochat-collector/.../eval/reeval/ReEvaluationJob.java:457-481`) opens its own
Agroal connection per candidate (line 458) and calls `readPostBody` (459 → 523),
which issues `SELECT body FROM post WHERE id = ? AND fetched_at = ?` — a second
single-row `post` read in addition to the quarantine-originals read. The candidate
scan in `enumerateCandidates` (≈546) already selects from `post` under a bounded
`LIMIT batchSize`, so the body could ride that scan. As written, a re-eval tick of
N candidates performs N extra connection acquisitions and N extra single-row `post`
reads beyond what the batch enumeration could amortize — wasted work on the hot
re-eval path during an LLM-recovery burst.

Low severity: the body is small relative to the surrounding LLM call, and the work
is bounded by the existing batch cap (16). But folding the body into the existing
scan keeps the partition-pruned candidate query as the single `post` touch per
tick, matching the rest of the module (ReadyPromoter, EmbeddingWorker each touch
`post` once per batch).

## Acceptance

See frontmatter. Add `body` to the `enumerateCandidates` SELECT list and to the
`ReEvalCandidate` row; reconstruct from the carried body, reading only the
quarantine originals on one connection. Preserve the NULL-body rejection so this
stays a pure performance change with no behavior change.

## Out-of-scope

See frontmatter. The splice algorithm and the quarantine read are untouched.

## Notes

- Adjacent code: ReadyPromoter / EmbeddingWorker — the "one `post` touch per batch"
  pattern this change brings re-eval in line with.
- Carrying `body` widens the in-memory batch row by the body string for ≤16 rows —
  bounded and negligible.
