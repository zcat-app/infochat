---
id: M1-230
title: "Semantic-link query: use an HNSW index probe, not a self-join"
status: done
created: 2026-06-08
last_updated: 2026-06-09
blocked_by: []
files_budget: 4
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/linking/LinkingJob.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/linking/LinkingJobSemanticProbeIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The entity-linking query (LinkingJob equality join on shared entities) — it does not use vector distance and must not change.
  - The HNSW index definition itself (infochat-core V11__post_embedding.sql) — the index is correct; only the query that consumes it changes.
  - ef_search / index-build tuning and the profile-driven HNSW-vs-IVFFlat selection — out of scope; the rewrite must work under both index types.
  - maxLinksPerPost / DRIVING_BATCH_SIZE / semantic-window-hours values — unchanged.
acceptance:
  - "LinkingJob's semantic-candidate query is rewritten so the driving post's embedding is bound as a query parameter (?::vector), not referenced as a second column in a self-join, so PostgreSQL can drive the ORDER BY embedding <=> ? LIMIT k probe with idx_post_embedding_hnsw."
  - "The driving embedding is read first (single-row PK lookup); if the driving post has no embedding row the method returns an empty candidate list (no semantic links) without issuing the probe."
  - "A named test asserts the rewritten query returns the same candidate set (respecting the distance threshold, NOT EXISTS dedup, ordering, and LIMIT) as the prior behavior for a fixture of co-temporal embeddings against Testcontainers pgvector."
  - "The probe requests at least maxLinksPerPost candidates so approximate-NN recall is acceptable for the linking heuristic."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/linking/LinkingJobSemanticProbeIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Inter-service communication
  - docs/spec/llm.md §Embedding pipeline
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-09
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 382
      removed: 34
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-08
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-230: Semantic-link query: use an HNSW index probe, not a self-join

## Context

Deep-review finding `deep-code-review/v2.5/opus-48/06-module-infochat-collector.md#F1`
(high PERFORMANCE). `LinkingJob` issues its semantic-candidate query as a
`post_embedding` self-join with **both** operands of the `<=>` cosine-distance
operator being column references (`pe1.embedding <=> pe2.embedding`,
`LinkingJob.java:261-267`). pgvector's HNSW index
(`idx_post_embedding_hnsw`, `vector_cosine_ops`, declared in
`infochat-core/.../V11__post_embedding.sql`) only accelerates the shape
`ORDER BY embedding <=> <constant/param> LIMIT k`. Because `pe1.embedding`
is a per-row column value, not a plan-time constant, the planner cannot
probe the index; the query degrades to a full scan of the co-temporal
embedding slice **per driving post** (up to `DRIVING_BATCH_SIZE`=64 per
tick). The HNSW index is paid for on every INSERT but never used by the
one query that exists to consume it, and the cost compounds as ingest
grows.

## Acceptance

See frontmatter. In prose: read the driving post's embedding first as a
single-row PK lookup, then issue an indexable top-k ANN probe with the
driving vector bound as a `?::vector` parameter so the HNSW index drives
`ORDER BY embedding <=> ? LIMIT k`; preserve the identical candidate set
(distance threshold, `NOT EXISTS` dedup, ordering, cap); a missing driving
embedding yields no semantic links without a probe; a Testcontainers
pgvector test pins candidate-set equivalence; `mvn verify` is 0.

## Out-of-scope

See frontmatter. The entity-linking equality join, the index definition,
profile/ef_search tuning, and the linking constants are untouched. This is
purely a query-shape change to restore index usage; result semantics must
be identical (modulo HNSW approximate recall, which is acceptable for a
best-effort cross-source linking signal and which the IVFFlat/pi profile
already implies).

## Notes

- Recommended fix and the exact rewritten SQL are in the source finding.
- Two SQL round-trips per driving post replace one (the extra read is a
  single-row PK lookup, negligible against the scan it removes).
- Approximate-NN caveat: request `LIMIT >= maxLinksPerPost`; the hard
  distance threshold still filters post-probe.
