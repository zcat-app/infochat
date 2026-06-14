---
id: M1-354
title: "collector eval: load prompt templates via the class's own loader; coalesce pgvector format rejections instead of wedging the batch"
status: done
created: 2026-06-14
last_updated: 2026-06-14
clarity_check:
  date: 2026-06-14
  verdict: PASS
  warnings: []
  blockers: []
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
  - "EmbeddingWorker's pgvector-rejection branch logs via SafeLog (or scalar fields only) and never passes the caught SQLException/Throwable to the SLF4J logger, matching the sibling non-finite (l.356) and dimension-mismatch (l.331) branches and docs/spec/security.md §Secrets handling 'the original Throwable is never passed to the underlying SLF4J logger'. (Redteam M1-354 2026-06-14 INFO-LEAK finding; round-1 negative_space: WARN.)"
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage2 (foreign-TCCL prompt-load test)
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding (pgvector-rejection coalesce test)
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
      negative_space: WARN
      acceptance: PASS
    diff_stats:
      files: 7
      added: 505
      removed: 28
  - round: 2
    date: 2026-06-14
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 680
      removed: 32
escalations:
  - date: 2026-06-14
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      /redteam M1-354 --in-progress → FINDINGS (low=1, out-of-model=1).
      INFO-LEAK (low): EmbeddingWorker.java:399 passes the raw SQLException
      `rejection` as the final LOG.error argument, contradicting
      docs/spec/security.md §Secrets handling "the original Throwable is never
      passed to the underlying SLF4J logger" and bypassing SafeLog's API-key
      redactor. Sibling paths (dimension-mismatch l.331, non-finite l.356) log
      only scalar fields; attemptEmbed l.420 uses SafeLog.warn(LOG, ..., e).
      Confirms round-1 review's negative_space: WARN.
revisions:
  - date: 2026-06-14
    reason: redteam-finding refine (round 1) — /redteam M1-354 surfaced a low INFO-LEAK: EmbeddingWorker's new pgvector-rejection branch passes the raw caught SQLException to LOG.error, contradicting docs/spec/security.md §Secrets handling "the original Throwable is never passed to the underlying SLF4J logger" and bypassing SafeLog's redactor (the sibling non-finite/dimension branches log scalars only; attemptEmbed already uses SafeLog). Adds acceptance item 5 pinning the SafeLog/scalar-only logging contract on the rejection branch; the fix is a one-line swap on the existing branch, no files_scope change. Confirms round-1 review's negative_space: WARN.
    prior_values: |
      acceptance had 4 items (classloader; pgvector coalesce; tests pin (a)+(b);
      mvn verify exits 0). No acceptance item constrained how the new
      pgvector-rejection branch logs the caught exception.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-14
    category: INFO-LEAK
    severity: low
    promise: |
      docs/spec/security.md §Secrets handling — "User content in exceptions":
      "Exception messages and stack traces emitted via the application logger
      MUST NOT contain user-authored prose ... The original `Throwable` is never
      passed to the underlying SLF4J logger. `SafeLog` also applies the closed
      API-key catalogue redactor to the caller-supplied message ..."
    gap: |
      EmbeddingWorker.java line 399: the new pgvector-rejection branch logs the
      raw caught `SQLException rejection` as the final SLF4J argument
      (LOG.error(..., rejection.getSQLState(), ERROR_CLASS_..., rejection)),
      handing the full unredacted PSQLException stack trace + server message to
      SLF4J and bypassing SafeLog's API-key redactor. This contradicts the
      categorical rule "the original Throwable is never passed to the underlying
      SLF4J logger." The sibling failure paths (dimension-mismatch l.331,
      non-finite l.356) log only scalar fields, and attemptEmbed l.420 uses
      SafeLog.warn(LOG, ..., e) — the established convention. Confirms round-1
      review's negative_space: WARN.
    repro: |
      A pgvector literal-parser data-exception (SQLState class 22) on the
      ?::vector INSERT raises a PSQLException carrying the server error text and
      chained cause; SLF4J renders the entire unredacted stack trace. The bound
      INSERT (post_embedding) has no body column, so user prose is unlikely in
      THIS exception specifically — but the spec rule is categorical so any
      API-key-shaped token reachable in the message/cause chain is emitted raw,
      having bypassed Redactor.redact.
    suggested_fix_class: other
redteam_audits:
  - date: 2026-06-14
    verdict: FINDINGS
    base: HEAD
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-354-2026-06-14.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      In-progress audit (--in-progress, ticket in-review). One low INFO-LEAK
      finding: EmbeddingWorker's new pgvector-rejection branch passes the raw
      SQLException to LOG.error, violating §Secrets handling's categorical
      "never pass the Throwable to SLF4J" rule and bypassing SafeLog redaction;
      verified at source against l.399 vs the SafeLog convention at l.420. This
      is the negative_space: WARN round-1 review already raised. One OUT-OF-MODEL
      advisory (retry vs "release without a vector") — post-security-boundary,
      not a threat-model violation. Fix lands on the same branch before commit.
  - date: 2026-06-14
    verdict: CLEAN
    base: main
    head: m1/M1-354-collector-eval-prompt-classloader-and-pgvector-wedge
    verdict_file: docs/plan/m1/redteam/M1-354-2026-06-14-post-fix.md
    out_of_model_count: 1
    note: |
      Post-fix re-audit (branch form, main...branch) after the INFO-LEAK above
      was remediated and committed (6a807174) and re-reviewed APPROVE (round 2).
      CLEAN — the raw-SQLException LOG.error argument is gone; the rejection
      branch logs scalar fields only. The prior redteam_findings entry is kept
      intact as the remediated-finding record (not blanked to []). Same
      OUT-OF-MODEL advisory (retry vs "release without a vector"), still
      post-security-boundary and advisory only. Separate same-day durable file
      to preserve the pre-fix FINDINGS record.
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
