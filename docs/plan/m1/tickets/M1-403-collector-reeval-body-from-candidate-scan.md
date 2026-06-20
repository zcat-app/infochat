---
id: M1-403
title: "collector: read re-eval body from the candidate scan"
status: done
created: 2026-06-19
last_updated: 2026-06-20
blocked_by: []
files_budget: 4
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
  - "enumerateCandidates carries post.body in the ReEvalCandidate row (the candidate scan already selects from post), and reconstructOriginalBody no longer issues a separate SELECT body FROM post per candidate — it reconstructs from the candidate-carried body and reads only the quarantine originals. The single connection reconstructOriginalBody opens now serves only that quarantine-originals read (it was always shared with the removed body read, never dedicated to it), so the per-tick cost drops by N redundant single-row post reads."
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
reviews:
  - round: 1
    date: 2026-06-20
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 143
      removed: 45
escalations:
  - date: 2026-06-20
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — no review round. Pre-implementation source audit found item 1's
      premise false: it required reconstructOriginalBody to "no longer open its
      own dataSource.getConnection()", but the connection at
      ReEvaluationJob.java:458 is SHARED by the body read (readPostBody) and the
      quarantine-originals read on the same conn — never dedicated to the body
      read. out_of_scope keeps the quarantine read, which needs that connection,
      so the connection acquisition is irreducible within scope; only the body
      SELECT is removable. The deep-review "N extra connection acquisitions"
      framing is the false part (the N connections are load-bearing for
      quarantine). Resolution: refine item 1 to drop the getConnection clause and
      state the real win (remove N redundant single-row body reads), keeping the
      one connection for the quarantine-originals read.
  - date: 2026-06-20
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — no review round. Pre-implementation call-site sweep: adding body to
      the ReEvalCandidate record changes its constructor signature, so all three
      construction sites must update — and they live in three separate test files
      (ReEvaluationJobTest.java:281, ReEvalVerdictNotifyIT.java:233,
      ReEvaluationJobCooldownTest.java:127), each reaching reconstructOriginalBody
      via processOne so each needs a real carried body. With the production file
      that is 4 files, over files_budget: 3. A 5-arg convenience constructor
      defaulting body=null is a forbidden backwards-compat shim (and would throw
      NULL-body in the IT/cooldown paths). files_scope's test-dir entry already
      covers all three test files; only the numeric budget is short. Resolution:
      refine files_budget 3 -> 4.
revisions:
  - date: 2026-06-20
    reason: premise-fail refine — item 1's "reconstructOriginalBody no longer opens its own dataSource.getConnection()" is falsified against ReEvaluationJob.java:457-481 (the single connection is shared with the mandatory quarantine read, not dedicated to the removed body read); reword item 1 to drop the getConnection clause and keep the connection for the quarantine-originals read
    prior_values: |
      acceptance[0]: "enumerateCandidates carries post.body in the
        ReEvalCandidate row (the candidate scan already selects from post), and
        reconstructOriginalBody no longer opens its own dataSource.getConnection()
        nor issues a separate SELECT body FROM post per candidate — it
        reconstructs from the already-loaded body and reads only the quarantine
        originals."
  - date: 2026-06-20
    reason: budget-breach refine — record-signature change ripples to 3 construction sites in 3 separate test files (+1 production) = 4 files; files_budget: 3 missed the call-site ripple (clarity check too). Widen files_budget 3 -> 4; files_scope unchanged (test-dir entry already covers all three)
    prior_values: |
      files_budget: 3
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-20
  verdict: PASS
  warnings: []
  blockers: []
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
