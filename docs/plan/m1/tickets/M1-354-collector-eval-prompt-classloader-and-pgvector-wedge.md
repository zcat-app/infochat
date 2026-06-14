---
id: M1-354
title: "collector eval: load prompt templates via the class's own loader; coalesce pgvector format rejections instead of wedging the batch"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 6
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/Stage2Worker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TaggerWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorker.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage2
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The prompt template CONTENT and the resource paths themselves — unchanged.
  - The existing NaN/Infinity / dimension-mismatch guard in EmbeddingWorker (M1-327) — kept; this adds the parser-rejection branch alongside it, reusing the same notify-once + skip shape.
  - The watchdog/normalisation ordering in Stage 1 — out of scope.
acceptance:
  - "Stage2Worker.loadPromptTemplate and TaggerWorker.loadResource load the resource via the class's own classloader (Stage2Worker.class.getClassLoader() / TaggerWorker.class.getClassLoader()) rather than preferring Thread.currentThread().getContextClassLoader(); the security-judge / tagger prompt can no longer be shadowed by a foreign TCCL entry."
  - "EmbeddingWorker.formatVector's SQLException-from-pgvector path (a parser rejection of the ?::vector literal that survives the in-Java NaN/Infinity guard) is routed to the same coalesced ThrottledAdminNotifier.notifyOnce + skip-without-INSERT shape M1-327 uses for non-finite vectors, so a single bad vector no longer aborts the whole batch transaction and re-wedges every tick."
  - "Tests pin: (a) the prompt loads correctly with a hostile/foreign TCCL set, and (b) a vector whose literal pgvector rejects is alerted-once and skipped (embedding_done stays FALSE) rather than throwing out of the batch loop."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage2 (foreign-TCCL prompt-load test)
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding (pgvector-rejection coalesce test)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-354: prompt-load classloader + pgvector format-rejection coalescing

## Context

Two deep-review v6 security findings on the collector eval pipeline, grouped
(same module, both "provider/runtime-controlled input crossing a boundary"):

- **opus-47 `06-module-infochat-collector.md` F4** (medium, SECURITY) — the
  Stage 2 security-judge prompt (and the tagger prompt) are loaded with the
  thread context classloader at construction. **Verified 2026-06-14:**
  `Stage2Worker.java:302` and `TaggerWorker.java:532` both
  `Thread.currentThread().getContextClassLoader()` with a fallback to
  `<Class>.getClassLoader()`. In Quarkus virtual-thread / scheduler / reactive
  dispatch contexts the TCCL can be the system loader; a stray
  `prompts/security-judge.md` on another classpath entry would load silently.
  The fallback is already the correct answer — make it the only path.
- **opus-47 `06-module-infochat-collector.md` F5** (medium, SECURITY) —
  `EmbeddingWorker.formatVector` builds a `?::vector` literal from
  provider-supplied floats; the M1-327 guard handles NaN/Infinity, but any other
  pgvector parser rejection raises `SQLException` from inside the batch
  transaction, which rolls back and the next tick re-picks the same wedged
  batch (the exact "stack-trace-per-poll wedge" M1-327 named). **Verified
  2026-06-14:** `formatVector` at EmbeddingWorker.java:488-506 wraps the
  `setValue` SQLException as `IllegalStateException`; the NaN/Infinity guard is a
  separate in-Java check upstream.

opus-48's collector pass reported no findings; both are verified above.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- `<Class>.getClassLoader()` is by construction the loader that carries the
  module's `src/main/resources`; the TCCL preference has no useful semantics
  here.
- The pgvector branch reuses M1-327's pattern verbatim (canonical error class +
  notify-once + skip) so a later good batch unwedges the post.
