---
id: M1-598
title: "Provider: render real per-post classification in /summary (union, drop 'unknown' unless sole)"
status: done
created: 2026-07-08
last_updated: 2026-07-09
blocked_by:
  - M1-597
files_budget: 17
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ClusterBlockRenderer.java
  # /retry twin projection — MUST also project p.classification for D19/D36
  # byte-identical replay (feeds the same ClusterBlockRenderer). Added via
  # budget-breach refine (Option B).
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  # Digest producers (out-of-scope, under-counted in the first budget-breach
  # refine — both construct via the fully-qualified `new EligiblePostQuery.Post(`
  # form the `new Post(` grep missed). Digest never renders classification, so
  # both carry the {unknown} placeholder.
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestPostCollector.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryIT.java
  # Mechanical new Post(...) orphan-fixes the record component necessitates
  # (Option B: mirror tags — single canonical ctor, every producer supplies
  # classification). Existing assertions unchanged; placeholder classification.
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerClockTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/ClusterTraversalTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseGeneratorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseInjectionTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/SummaryProseRefusalDegradeTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DegradedDigestRendererTest.java
  - docs/design/03-commands.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Everything the collector/schema half (M1-597) owns: the V57 migration, the
    ClassifierWorker, ModelTask/LlmRouter, prompts, ReadyPromoter, and the
    02-schema / 05-llm / architecture design updates. This ticket only CONSUMES
    the `post.classification` column M1-597 lands. Do NOT touch infochat-collector,
    infochat-llm-adapter, or infochat-core.
  - >-
    The reply bundle keys. `reply.summary.cluster.classification_label` (en + cs),
    the BundleKeys constant, and the renderer's `classification:` line already
    exist (restored by the M1-591 revert). This ticket changes only the DATA
    SOURCE of that line (tag-union copy → the real classification union); it does
    NOT add or remove any bundle key, so the D43 bilateral keyset is untouched.
  - >-
    Widening the deterministic query with anything other than the single
    `p.classification` column, or changing the ORDER BY / cluster-cap / cutoff
    logic. The retrieval determinism (docs/spec/llm.md §Determinism boundary) and
    byte-identical replay (D19/D36) are preserved: classification is read from the
    DB like tags, so both call sites (/summary + /retry) emit the same bytes for
    the same DB state.
acceptance:
  - >-
    EligiblePostQuery.selectPosts adds `p.classification` to the SELECT projection
    and the `Post` record gains a `List<String> classification` field (non-null,
    mirroring `tags`), populated from the array column. No other query change.
    EligiblePostQueryIT asserts classification is projected (a seeded post's
    classification round-trips into the Post record).
  - >-
    /retry projects classification too (D19/D36 byte-identical replay).
    RetryCommandHandler's SELECT_POSTS_BY_UIDS adds `p.classification` and
    `mapPost` populates the new Post field, so the shared ClusterBlockRenderer
    emits the SAME `classification:` line at /summary and /retry for the same
    DB state — without this, /retry's line would diverge and break replay. The
    new canonical `Post(...)` arity fans out to every other producer, which
    each gain a placeholder classification arg (`List.of("unknown")`) with NO
    change to any existing assertion — mechanical orphan-fixes the record
    component necessitates. The non-rendering producers: DigestPostCollector
    (production digest mapper — digest never renders classification, so it
    passes the {unknown} sentinel; its SELECT is not widened) plus the 9 test
    call sites (RetryCommandHandlerTest, DigestRendererTest, DigestWorkerTest,
    DigestWorkerClockTest, DegradedDigestRendererTest, ClusterTraversalTest,
    SummaryProseGeneratorTest, SummaryProseInjectionTest,
    SummaryProseRefusalDegradeTest).
  - >-
    ClusterBlockRenderer renders the `classification:` line from the REAL
    classification union, not the tag copy. A new `joinedClassifications(posts)`
    helper unions the cluster's posts' `classification` lists, then applies the
    `unknown` rule: drop `unknown` when any substantive label is present in the
    union, and emit `classification: unknown` only when the whole union is exactly
    {unknown}. The `tags:` line is unchanged (still `joinedTags`). The two lines
    are now genuinely independent — a cluster can show `classification: factual,
    technical` above `tags: security, ai`.
  - >-
    NAMED TESTS. ClusterBlockRendererTest's en and cs byte-for-byte blocks are
    updated so classification is seeded DISTINCT from tags (proving the lines no
    longer mirror), plus two new cases: (a) a cluster whose union mixes `unknown`
    with a substantive label renders the substantive label only (no `unknown`),
    and (b) a cluster whose union is only `unknown` renders `classification:
    unknown`. SummaryCommandHandlerTest asserts the `classification:` line is
    present and reflects the seeded classification (not the tag set). Red-before /
    green-after on the old tags-copy assertions.
  - >-
    docs/design/03-commands.md §`/summary` output sample keeps the distinct
    `classification:` line (already `classification: technical, urgent` above
    `tags: security, ai`) and a one-line note is added that classification is the
    per-post ingest classification union (with `unknown` shown only when nothing
    else applies). This is the only design edit here; the schema/pipeline docs are
    M1-597's.
  - mvn verify is green from the repo root.
test_plan:
  adds: []
  modifies:
    - >-
      infochat-provider/.../command/ClusterBlockRendererTest.java — classification
      seeded distinct from tags; add the union `unknown`-strip and sole-`unknown`
      cases.
    - >-
      infochat-provider/.../command/SummaryCommandHandlerTest.java — assert
      classification reflects seeded classification, not tags.
    - >-
      infochat-provider/.../summary/EligiblePostQueryIT.java — assert
      p.classification is projected into the Post record.
    - >-
      Mechanical `new Post(...)` orphan-fixes (Option B budget-breach refine) —
      add a placeholder classification arg (List.of("unknown")) to every
      construction site in: command/RetryCommandHandlerTest.java,
      digest/DigestRendererTest.java, digest/DigestWorkerTest.java,
      digest/DigestWorkerClockTest.java, digest/DegradedDigestRendererTest.java,
      summary/ClusterTraversalTest.java, summary/SummaryProseGeneratorTest.java,
      summary/SummaryProseInjectionTest.java,
      summary/SummaryProseRefusalDegradeTest.java. These tests do NOT exercise
      classification rendering; their existing assertions are unchanged.
  preserves:
    - all tests currently green on main
    - >-
      the D43 bilateral bundle keyset (no bundle key added/removed) and the
      score-plural / covered-by / summary render cases (untouched).
    - >-
      byte-identical replay (D19/D36): the shared renderer emits the same new
      shape at /summary and /retry.
spec_refs:
  - docs/design/03-commands.md §`/summary [tag] [-w 24h]`
  - docs/design/05-llm-and-embeddings.md §5.4.5 Summarizer (cluster mode)
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
  - D36
  - D43
redteam_findings: []
redteam_audits: []
reviews:
  - round: 1
    date: 2026-07-09
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 19
      added: 293
      removed: 39
escalations:
  - date: 2026-07-09
    reason: budget-breach
    reviewer_verdict_excerpt: |
      files_scope path-list breach surfaced at implement-time (pre-code static
      analysis). Acceptance item 1 mandates the `Post` record gain a
      `classification` component. `Post` is a Java record with a single
      canonical constructor, so adding a component breaks EVERY `new Post(...)`
      call site — and one of them is load-bearing for a preserve invariant this
      ticket states (D19/D36 byte-identical /summary vs /retry replay):
        - PRODUCTION, out of files_scope, MANDATORY:
          RetryCommandHandler.java — its SELECT_POSTS_BY_UIDS projects p.tags
          but not p.classification, and mapPost() feeds those Posts to the SAME
          ClusterBlockRenderer.appendClusterBlock. If /retry does not also
          project p.classification, its classification: line diverges from
          /summary's for the same DB state → replay breaks. Cannot be avoided.
        - 8 out-of-scope TEST files construct `new Post(...)` and would fail to
          compile under a new canonical arity: RetryCommandHandlerTest,
          DigestRendererTest, DigestWorkerClockTest, DigestWorkerTest,
          ClusterTraversalTest, SummaryProseGeneratorTest,
          SummaryProseInjectionTest, SummaryProseRefusalDegradeTest.
      Two resolutions (developer recommends B):
        A. Add a 9-arg convenience constructor to Post defaulting classification
           for producers that don't render it → touches only 7 files
           (6 in-scope + RetryCommandHandler); files_scope 6→7, files_budget 8
           unchanged. Cost: a convenience ctor that does NOT "mirror tags"
           (tags has no default) and gives digest/prose/cluster-test Posts an
           empty classification — a shim the reviewer may flag.
        B. Treat classification exactly like tags (single canonical ctor, every
           producer supplies it) → touches 15 files (6 in-scope + 9 out-of-scope:
           RetryCommandHandler + the 8 test files above); files_scope 6→15,
           files_budget 8→~15. No shim; matches acceptance's "mirroring tags";
           the 8 test edits are mechanical orphan-fixes the component change
           creates (surgical-rule allowed; M1-590 precedent). Larger churn.
overrides: []
revisions:
  - date: 2026-07-09
    reason: >-
      clarity-fail self-refine (auto, /m1-tick run bounded prose-refine): the
      SPEC-REFS-VALID blocker — frontmatter spec_ref
      "docs/design/05-llm-and-embeddings.md §5.4.4 Summarizer (cluster mode)"
      is stale. M1-597 (this ticket's own blocked_by, now done) inserted a new
      §5.4.4 Classifier section, renumbering the Summarizer section to §5.4.5.
      Pure citation retarget; no scope, acceptance, or files_scope change.
    snapshot: |
      spec_refs entry (pre-refine): "docs/design/05-llm-and-embeddings.md §5.4.4 Summarizer (cluster mode)"
      clarity blocker (2026-07-09): §5.4.4 is now "Classifier" (design file line
        308, inserted by M1-597); "Summarizer (cluster mode)" is at §5.4.5
        (design file line 349). ANCHOR-NOT-FOUND against the pre-refine cite.
      resolution: retarget the section number 5.4.4 -> 5.4.5, keeping the
        "Summarizer (cluster mode)" label (verified: line 349 is exactly that
        heading). files_budget / files_scope / acceptance semantics / complexity
        / risk / round_cap unchanged.
  - date: 2026-07-09
    reason: >-
      budget-breach refine (escalate→refine; user chose Option B "mirror tags"
      via the /m1-tick run halt AskUserQuestion). Acceptance item 1 mandates the
      Post record gain a classification component; Post is a single-canonical-
      constructor Java record, so the new arity breaks every new Post(...) site.
      RetryCommandHandler.java (production) MUST also project p.classification or
      /retry's classification: line diverges from /summary's — the D19/D36
      byte-identical-replay preserve invariant this ticket states. Widened
      files_scope 6→15 (+RetryCommandHandler + 8 test orphan-fix files) and
      files_budget 8→15; added the /retry-projection acceptance item plus the
      8 mechanical test edits to test_plan.modifies. The query-widening ban
      (only p.classification) and the render semantics are unchanged.
    snapshot: |
      files_budget (pre-refine): 8; files_scope: 6 paths (EligiblePostQuery,
        ClusterBlockRenderer, ClusterBlockRendererTest, SummaryCommandHandlerTest,
        EligiblePostQueryIT, docs/design/03-commands.md).
      acceptance (pre-refine): 5 items; the /retry (RetryCommandHandler)
        classification projection was implicit in the D19/D36 preserve invariant
        only, not a checkable acceptance item.
      test_plan.modifies (pre-refine): 3 test files (no digest/prose/cluster tests).
      breach: adding a Post component fans out to 9 out-of-scope files (1 prod +
        8 test), confirmed by `grep -rln 'new Post(' infochat-provider/src`.
      CORRECTION (compile-time): the `new Post(` grep under-counted by 2 — two
        digest producers construct via the fully-qualified `new
        EligiblePostQuery.Post(` form: DigestPostCollector.java (production) and
        DegradedDigestRendererTest.java (test). True fanout = 17 files; bumped
        files_scope 15→17, files_budget 15→17. Same Option-B resolution (both
        pass the {unknown} placeholder — digest never renders classification);
        surfaced by `mvn -pl infochat-provider test-compile`.
aborted_attempts: []
reopens: []
clarity_check:
  date: 2026-07-09
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-598: render real per-post classification in /summary

## Context

M1-597 lands `post.classification` (a per-post ingest evaluation over the closed
enum `{factual, opinion, technical, urgent, ongoing, unknown}`) and populates it
in the collector. This ticket is the provider half: project that column into the
`/summary` retrieval and render the cluster `classification:` line from it, so
the line finally carries real, distinct data instead of the reverted tag-union
stub.

## The fix

1. `EligiblePostQuery`: add `p.classification` to the deterministic SELECT and a
   `classification` field to the `Post` record.
2. `ClusterBlockRenderer`: replace the classification line's data source
   (`joinedTags` → a new `joinedClassifications`) that unions the cluster's
   per-post classifications and drops `unknown` unless it is the sole label.
3. Tests pin that `classification:` and `tags:` are now independent, plus the
   two `unknown` render rules.
4. `03-commands.md` §`/summary` keeps the distinct sample and notes the
   per-post-union + `unknown` semantics.

The bundle key, BundleKeys constant, and the `classification:` line itself
already exist (restored by the M1-591 revert), so no bundle change is needed —
only the line's data source changes.

## Determinism (D19/D36)

Classification is read from the DB (like tags), so the shared renderer emits the
same bytes at `/summary` and `/retry` for the same DB state — byte-identical
replay holds. This ticket does not touch the ORDER BY, cluster cap, or cutoff.

## Notes

- **Blocked by M1-597** — the `post.classification` column must exist and be
  populated first. Until M1-597 merges, the column is absent and this ticket
  cannot compile its SELECT.
- **`unknown` render rule.** `unknown` is a real per-post label but a "no-signal"
  one at cluster level: the union drops it when any substantive label is present,
  and shows `classification: unknown` only when the entire cluster union is
  `{unknown}` — so the line is always populated and never mirrors tags.
