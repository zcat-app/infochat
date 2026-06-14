---
id: M1-353
title: "collector eval: give ReadyPromoter its own poll-interval and add a partition-scan floor to per-stage pickup queries"
status: done
created: 2026-06-14
last_updated: 2026-06-14
clarity_check:
  date: 2026-06-14
  verdict: WARN
  warnings:
    - "TEST-CHANGES-AUTHORIZED: test_plan.modifies names the eval test directory rather than the specific test files whose existing assertions will be updated (likely ReadyPromoterIT.java for the cadence-key change, plus EmbeddingWorkerIT/TaggerWorkerIT/EntityExtractorWorkerIT for the pickup-floor). The intent is deducible from the acceptance items, but naming the files explicitly would make the authorization unambiguous."
  blockers: []
blocked_by: []
files_budget: 9
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/ready/ReadyPromoter.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TaggerWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/entity/EntityExtractorWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/PartitionScan.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The 5s default cadence (observable behaviour) — preserved; ReadyPromoter's new key defaults to the same value the embedding poll uses today.
  - The partition-aware scanners that already carry the floor (ReEvaluationJob, PerSourceUnknownTracker) — unchanged; this ticket brings the four pickup queries up to their existing pattern.
  - The pickup-query SELECT columns, status predicates, and LIMIT — unchanged apart from the added fetched_at floor.
acceptance:
  - "ReadyPromoter.onTick is scheduled off a dedicated key (e.g. infochat.eval.ready-promoter.poll-interval), not infochat.embeddings.poll-interval; the key is added to application.properties (all profile blocks) with the same default as the embedding poll today, and the class javadoc states the cadence is independent of the embedding stage."
  - "EmbeddingWorker, TaggerWorker, EntityExtractorWorker, and ReadyPromoter per-tick pickup queries each gain an `AND fetched_at >= now() - ?::INTERVAL` floor, with the bound sourced from the post retention horizon + PARTITION_SCAN_SLACK via a shared eval/PartitionScan helper (same arithmetic ReEvaluationJob already uses)."
  - "A test pins that an over-horizon RAW/pending post is not returned by a pickup query while an in-window one is (the partition floor is effective), and that the four workers share one PartitionScan source."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/PartitionScan.java (shared partition-window helper)
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval (pickup-floor + cadence-key assertions)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-14
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 311
      removed: 18
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-353: ReadyPromoter cadence + per-stage partition floor

## Context

Two deep-review v6 findings on the collector eval pipeline, grouped (same four
worker classes):

- **opus-47 `06-module-infochat-collector.md` F3** (high,
  MAINTAINABILITY-RULES-DRIFT) — `ReadyPromoter` (a distinct Stage-5 step) is
  scheduled off `infochat.embeddings.poll-interval`. **Verified 2026-06-14:**
  `ReadyPromoter.java:120` uses `{infochat.embeddings.poll-interval}` while
  `EntityExtractorWorker:187` uses `{infochat.llm.entity.poll-interval}` and
  `TaggerWorker:207` uses `{infochat.llm.tagger.poll-interval}` — each stage owns
  its key except ReadyPromoter, which silently couples promote latency to
  embedding tuning.
- **opus-47 `06-module-infochat-collector.md` F6** (medium, PERFORMANCE) — the
  four per-stage pickup queries over the `RANGE(fetched_at)`-partitioned `post`
  table omit a `fetched_at` floor, so the planner cannot prune partitions; the
  backlog/rehydrator scenario amplifies the cost. `ReEvaluationJob` and
  `PerSourceUnknownTracker` already added `PARTITION_SCAN_SLACK` floors for
  exactly this reason.

opus-47's withdrawn LinkingJob entry (F2) is **not** part of this ticket — the
reviewer confirmed the inner ANN probe already carries a `pe.fetched_at` floor.
opus-48's collector pass reported no findings; F3/F6 are verified above.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- The semantic trade-off of the floor ("a post older than horizon+slack drops
  out of pickup") is already accepted by the two partition-aware scanners — a
  post past the retention horizon is about to be partition-dropped anyway.
