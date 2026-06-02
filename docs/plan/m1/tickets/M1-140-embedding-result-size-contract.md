---
id: M1-140
title: "EmbeddingResult value semantics + embedding SPI size-equals-input contract"
status: done
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 5
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the embedding response-body size cap (covered by M1-141)
  - the embedding retry policy beyond throwing on a shape mismatch
acceptance:
  - "OpenAiCompatibleEmbeddingProvider throws EmbeddingCallFailedException when results.size() != expectedCount (currently only a WARN, so callers silently mis-attribute vectors)"
  - "EmbeddingResult no longer exposes a mutable array via a record with reference-equality equals/hashCode — either the wrapper is dropped in favour of List<float[]>, or it defensive-copies on construction+accessor with Arrays.equals/hashCode"
  - "A test asserts two equal embeddings compare equal and the size-mismatch path throws"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §SPI shape
  - docs/spec/llm.md §Embedding pipeline
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-02
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 203
      removed: 16
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-02
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-140: EmbeddingResult value semantics + embedding SPI size-equals-input contract

## Context

Two SPI-contract defects in `infochat-llm-adapter`:

- **A24** — `EmbeddingProvider.embed` javadoc says "size equals texts.size()",
  but `OpenAiCompatibleEmbeddingProvider:162-201` returns a mismatched-size list
  with only a WARN; a caller zip-indexing vectors to texts silently
  mis-attributes a vector. The spec mandates per-batch retry on shape mismatch —
  detection belongs at the SPI seam.
- **A25** — `record EmbeddingResult(float[] vector)`: `equals`/`hashCode` use
  array reference identity (two identical embeddings unequal); the accessor
  returns the live array.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A24, §A25; `opus-47-full-handout.md`
  §F-MAINT-16/17; `opus-47-only-handout.md` §M2, M3.
- Option A (recommended): drop the wrapper, expose `List<float[]>`.
