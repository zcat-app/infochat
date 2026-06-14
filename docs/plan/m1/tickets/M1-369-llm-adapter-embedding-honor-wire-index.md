---
id: M1-369
title: "llm-adapter: honor the OpenAI embeddings wire index field instead of trusting positional order"
status: done
created: 2026-06-14
last_updated: 2026-06-15
clarity_check:
  date: 2026-06-14
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 3
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleEmbeddingProvider.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The per-coordinate numeric-type check and the count/size-divergence check — unchanged; the index-aware placement subsumes the count check but the existing type check stays.
  - The EmbeddingWorker one-failure-fails-batch retry contract — unchanged; an index violation throws the same EmbeddingCallFailedException.
  - MeteredEmbeddingProvider dimension recording and the collector-side model-identity guard — unchanged (out of this SPI's scope).
acceptance:
  - "parseEmbeddings reads each data[] element's wire `index` field and places the parsed vector at that declared input slot rather than zip-indexing by array position. Out-of-range, duplicate, missing, or gapped indices throw EmbeddingCallFailedException at the seam (subsuming the existing count check). An already-in-order response produces identical output to today."
  - "A unit test feeds a response whose data[] is deliberately reordered (e.g. index [1,0]) and asserts the returned EmbeddingResult list is back in input order; a second test feeds a duplicate / out-of-range index and asserts EmbeddingCallFailedException."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl (reordered-index + invalid-index tests)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-15
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 123
      removed: 23
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-369: embedding parse honors the wire `index` field

## Context

Deep-review v7 (opus-48) llm-adapter finding **F1**. Verified at source
2026-06-14:

`OpenAiCompatibleEmbeddingProvider.parseEmbeddings`
(`infochat-llm-adapter/.../impl/OpenAiCompatibleEmbeddingProvider.java:201-239`)
iterates `data.get(i)` positionally and never reads `data[i].index`. The OpenAI
`/embeddings` contract returns an `index` field per element precisely so clients
can recover input order; order is *recoverable via index*, not guaranteed
positional. A provider that returns elements out of order would silently
attribute the wrong vector to a post — the exact silent-cosine-corruption class
the existing per-coordinate-type and size checks were built to prevent — while
passing both checks.

**Severity note (honest):** the default local provider (Ollama, OpenAI-compatible)
returns elements in order in practice, so the live probability is low; the
consequence (silent, unretried mis-attribution that corrupts search ranking) is
severe and the hardest failure to detect after the fact. That asymmetry is why
it is worth fixing, and why it is correctness-class rather than security-class.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- Index-aware placement naturally subsumes the count check (a full, gap-free,
  duplicate-free `[0..n)` index set of the expected size is the same guarantee);
  keep the per-coordinate numeric-type check.
