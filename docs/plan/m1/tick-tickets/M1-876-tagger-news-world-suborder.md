---
id: M1-876
title: "Rank region leaves above world within the News top"
status: pending
created: 2026-08-17
last_updated: 2026-08-17
flow: tick
reproduction: >-
  TagTreeResolutionTest.newsIsLowestPriorityFallback — the flipped-pin
  reproduction: TagTreeResolutionTest.java:123's expected stored leaf flips
  from world to europe
  (assertResolution(List.of("europe"), List.of("world"), List.of("world",
  "europe"))), run RED at start before any fix code; observed wrong output
  stored=[world], losers=[europe] — the current resolver stores the first
  News leaf in emission order, which is the M1-865 acceptance-2 pin this
  ticket supersedes on the M1-864 record's demand (record:355-359: within-
  News priority must rank world BELOW the region leaves regardless of
  emission order). The assertion's final shape lives in the new hand-built
  test newsFallbackLeafYieldsToRegionLeaves (acceptance 1) because the fix's
  data half (the fallback marking) arrives with the V84 seed via M1-878; the
  DB-backed reversed leg is deleted with §8 authorization (acceptance 3).
analysis_ref: docs/plan/m1/tick-analysis/news-world-below-regions.md
blocked_by: []
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TagTreeResolver.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TagVocabulary.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TagTreeResolutionTest.java
  - docs/design/05-llm-and-embeddings.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    THE FALLBACK DATA — no migration, no seed, no tag row is written here.
    The fallback column + the world marking + the TagVocabulary SELECT edit
    ride M1-866's V84 seed migration via M1-878's amendment (this ticket
    lands the MECHANISM only; until V84 no row carries the marking and the
    resolver behaves byte-identically to today — the family P6 interim
    discipline).
  - >-
    TaggerWorker.java, the prompts, MiscShareMonitor, and every migration —
    the resolve() call site, the pre-resolution MAX_TAGS_PER_POST cap, the
    single atomic cursor UPDATE, the leaf-only render, and the misc monitor
    semantics are all untouched (engineering-rules §10).
  - >-
    THE SWEEP FINGERPRINT — the tree-shape-fingerprint question is M1-869's
    carried fold-in (the M1-865 review RECOMMENDED-NEW-TICKET), not this
    fix (§1).
  - >-
    ANY docs/spec/** edit — all spec text for the family lands in M1-869
    (single user-approvable diff, engineering-rules §12); M1-878's
    acceptance 3 ensures the M1-869 Tagger-SPI wording includes this
    sub-order.
  - >-
    AMENDING M1-865 — the merged commit is never amended (workflow.md:291);
    this ticket is the new-commit fix with remediates: M1-865.
acceptance:
  - "TagTreeResolutionTest.newsFallbackLeafYieldsToRegionLeaves (new, hand-built tree — no DB, no LLM: news top, europe/africa/world leaves with world marked fallback) passes: {world, europe} resolves to europe; {europe, world} resolves to europe; {world, europe, africa} resolves to the first-emitted REGION leaf (europe) — emission order stays the tiebreak AMONG non-fallback leaves; {world} alone resolves to world (the fallback leaf stores only when it is the only News leaf proposed); a DEEPER leaf still wins over a shallower fallback leaf (depth is the primary key; the composition is depth → non-fallback-before-fallback → emission order) — spec: docs/spec/llm.md §SPI shape as amended by M1-869; analysis P3, P5, P7."
  - "The supersession is explicit: this ticket SUPERSEDES M1-865 acceptance 2's tiebreak pin ('a proposal set containing ONLY News leaves (europe+world) resolves to the first News leaf in emission order') — the new contract is 'resolves to the region leaf whenever a region leaf is proposed; the fallback leaf stores only when it is the ONLY News leaf proposed' — the M1-865 acceptance text is left untouched (no-amend), and this ticket's acceptance text is the operative contract — probe: grep -n 'first News leaf in emission order' docs/plan/m1/tick-tickets/M1-865-tag-tree-schema-and-resolution.md returns the untouched M1-865 acceptance-2 text; grep -n 'SUPERSEDES M1-865 acceptance 2' docs/plan/m1/tick-tickets/M1-876-tagger-news-world-suborder.md returns this item; the operative contract is the green TagTreeResolutionTest.newsFallbackLeafYieldsToRegionLeaves (acceptance 1)."
  - "The violating pin is flipped with §8 authorization: TagTreeResolutionTest.newsIsLowestPriorityFallback:123's world-first leg is DELETED from the DB-backed test (which keeps europe-first → europe at :120 and News-mixed-with-another-top → ai at :124), and its replacement is the stronger both-directions assertion in newsFallbackLeafYieldsToRegionLeaves — the ticket names the test, the line, and the new expected behavior in plain language (engineering-rules §8 Test-modification authorization); no assertion is weakened, the DB-backed test simply no longer pins the interim world-first behavior, which this ticket does not promise and whose real fix arrives with the V84 data (M1-878)."
  - "No leaf name is hardcoded in main code: grep for 'world' over infochat-collector/src/main/java returns zero matches — the fallback designation is READ from the tree (TagNode gains a boolean component), never named; the M1-866 acceptance-8 probe stays green for every line this diff adds (analysis P2, P3)."
  - "D19/P8 discipline: the sub-order is pure Java in TagTreeResolver, pinned by pure unit tests without an LLM; no prompt file changes; grep over infochat-llm-adapter/src/main/resources/prompts/ for fallback/priority/geographic tokens returns nothing; the existing leaf-only render test stays green (analysis P5)."
  - "Pre-seed interim (family P6): TagVocabulary's loader passes false for the new component (the TRUE value of every row until V84 — no shim, no feature flag, §7) and every DB-backed resolution test keeps today's emission-order behavior green unmodified except the authorized deletion in acceptance 3; behavior between this merge and V84 is byte-identical to today (analysis P4)."
  - "Controls preserved: TagTreeResolutionTest's remaining tests, TagVocabularyRefreshTest, TaggerWorkerTest/TaggerWorkerIT/TaggerWorkerBackoffTest, TaggerWorkerSweepIT, and the MiscShareMonitor tests pass UNMODIFIED — News-last fallback, identity passthrough, losers in emission order, the MAX_TAGS_PER_POST pre-resolution cap, the single atomic cursor UPDATE, and the misc monitor semantics all unchanged — probe: git diff --name-only main shows no hunk for TaggerWorker.java, MiscShareMonitor.java, any prompts/ file, or any db/migration file (the only main-code hunks are TagTreeResolver.java and TagVocabulary.java); the named suites green in the mvn verify log of record (acceptance 9) (engineering-rules §10; analysis P6)."
  - "docs/design/05-llm-and-embeddings.md §5.4.2 gains ONE sentence recording the within-top sub-order (depth → non-fallback-before-fallback → emission order, the fallback designation arriving with the V84 seed) — the design-note sync each family ticket owns (M1-869's out_of_scope expects it) — probe: grep -n 'fallback' docs/design/05-llm-and-embeddings.md returns the §5.4.2 sentence naming the depth → non-fallback-before-fallback → emission order (analysis P3)."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TagTreeResolutionTest.java
      — newsFallbackLeafYieldsToRegionLeaves (hand-built fallback-marked
      tree; both directions; region-vs-region emission tiebreak; world-alone;
      depth-composition case).
  modifies:
    - >-
      TagTreeResolutionTest (newsIsLowestPriorityFallback) — the
      world-first leg at line 123 is deleted; authorized by acceptance 3
      (engineering-rules §8: the ticket names the test, the line, and the
      replacement assertion, which is STRONGER, not weakened).
  preserves:
    - all tests currently green on main
    - >-
      TagTreeResolutionTest.crossTopProposalResolvesToASingleBranch,
      parentlessVocabularyResolvesToItself,
      deepestLeafWinsWithinOneTop_equalDepthKeepsEmissionOrder,
      unlistedTopRanksBelowNews, leafOnlyLoadExcludesTopsAndKeepsQueryOrder,
      v82TreeColumnsDefaultLeafAndPlainInsertStillWorks — unmodified.
    - >-
      TagVocabularyRefreshTest, TaggerWorkerTest, TaggerWorkerIT,
      TaggerWorkerBackoffTest, TaggerWorkerSweepIT, the MiscShareMonitor
      tests, the leaf-only render test — unmodified.
spec_refs:
  - docs/spec/llm.md §SPI shape
  - docs/spec/llm.md §Failure handling (recap)
  - docs/spec/schema.md §Sources and tags
decision_refs:
  - D5
  - D19
  - D22
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates: M1-865
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-876: rank region leaves above world within the News top

## Context

M1-864's record measured the news-distribution FAIL: gemma co-proposes
`world` with nearly every regional News leaf, world's validated-tuple node
share is 1.0 (record:318), and the record's demand — "M1-865's within-News
priority must rank world BELOW the region leaves (the geographic-fallback
design intent), or every News post will store a world co-tag"
(record:355-359) — was lost in the M1-865 hand-off: M1-865's acceptance 2
pins the opposite tiebreak (first News leaf in emission order) and its test
asserts world-wins-when-first at TagTreeResolutionTest.java:123, while
M1-865's review never cross-checked the record (zero world/emission-order
mentions). This ticket, a NEW ticket on top of the merged M1-865
(workflow.md:291 — never amend a passed commit), lands the mechanism half of
the fix: a data-driven fallback designation that the resolver reads, and the
pin flip that supersedes M1-865 acceptance 2. Shared context:
`analysis_ref:` (analysis doc, Pitfalls P1–P8).

## Root cause

Verified: within the News top the shipped resolver has exactly one
discriminator — emission order — because the v2 tree is uniformly depth-2
and the tiebreak is strictly-greater depth (TagTreeResolver.java:64-75), and
the tree data carries nothing that distinguishes `world` from a region leaf
(TagVocabulary.java:92, :143-152: node_kind + parent only). The demand
(record:355-359) therefore cannot be satisfied by code alone without either
naming the leaf (which breaks M1-866 acceptance 8's grep probe and rots
silently — analysis P2/P3) or adding data. The mechanism lands here; the
data (the fallback column + the world marking + the SELECT) lands in
M1-866's V84 seed via M1-878. Nothing remains unproven; the only
unverifiable artifact in the chain is the adjacent M1-874 ticket body
(packed object — see the analysis's Ground truth).

## Pitfalls

- P1: merged-commit discipline — new ticket/new commit; the test edit is an
  §8-authorized flip, replacement STRONGER, never a weakened assertion.
- P2: name-agnostic discipline — no leaf name in `**/src/main/java`; the
  M1-866 acceptance-8 probe must stay green for this diff (and it already
  matches M1-865's MISC_LEAF — M1-878 reconciles, not this diff).
- P3: silent-rot asymmetry — the designation is DATA (a boolean component on
  TagNode), so a renamed/dropped leaf cannot silently disable the guard.
- P4: pre-seed interim — fallback-absent trees resolve exactly as today; the
  loader's `false` is the true pre-V84 value, no shim (§7).
- P5: D19/P8 — pure Java, no prompt change, no LLM in the tests.
- P6: controls to preserve (§10) — News-last, identity passthrough, losers
  order, the cap, the atomic UPDATE, the misc monitor, the leaf-only render
  all untouched.
- P7: depth-generality — the tiebreak composes as depth → fallback →
  emission; the existing depth test stays green.
- P8: fixture calibration — tests pin the fallback-MARKED END state; the
  `fallback` field name is pre-authorized by M1-878 so the V84 column and
  this TagNode component cannot drift.

## Approach

- **Files to touch:** `TagTreeResolver.java` (the within-top tiebreak +
  javadoc), `TagVocabulary.java` (TagNode + the loader's `false`), the test
  file (pin flip + new test), `docs/design/05-llm-and-embeddings.md`
  §5.4.2 (one sentence).
- **Steps, in order:**
  1. Flip line 123's expectation (world → europe) and run RED — observed
     wrong output stored=[world], losers=[europe] (the reproduction).
  2. `TagVocabulary.TagNode` gains `boolean fallback`; the loader passes
     `false` with a comment: the value arrives with the V84 seed (M1-878) —
     no column exists until then, and `false` is the true value of every
     row at that moment (P4).
  3. `TagTreeResolver`: the winning-leaf scan compares depth, then
     non-fallback-before-fallback, keeping strictly-greater/less so emission
     order remains the final tiebreak among equal-ranked leaves (P7). The
     class javadoc states the order with ONE stable pointer (the design
     note §5.4.2); no chronicle (§11).
  4. Re-scope the test: delete the DB-backed reversed leg (line 123) per
     acceptance 3; add `newsFallbackLeafYieldsToRegionLeaves` with
     hand-built trees marking world (P8, P5).
  5. Sync the design note §5.4.2 (one sentence).
  6. Full `mvn verify`.
- **Controls to preserve (engineering-rules §10):** enumerated in the
  analysis's Controls section — the diff reroutes ONLY the within-top
  tiebreak; `rank()`, the identity guard, the fast path, the losers copy,
  and every TaggerWorker/monitor/prompt line are untouched (M1-876 changes
  no TaggerWorker line at all).
- **Pitfall→mitigation:** P1→acceptances 2/3; P2→acceptance 4; P3→step 2/3
  (data, not name); P4→acceptance 6 + step 2; P5→acceptance 5; P6→
  acceptance 7; P7→acceptance 1's depth case; P8→acceptance 1 fixtures +
  M1-878 acceptance 4.

## Definition of done

Every acceptance item holds: the reproduction's assertion passes in its
final shape (both directions resolve to the region leaf on a
fallback-marked tree; world alone stores; region-vs-region keeps emission
order; depth composes first); the supersession of M1-865 acceptance 2 is
explicit; the pin flip is §8-authorized with the STRONGER replacement; no
leaf name in main code; pure Java, no prompt change; the pre-seed interim is
byte-identical; all named pre-existing suites green unmodified; the design
note synced; `mvn verify` green.

## Verification

- P1 → acceptance 3 (named test + line + replacement) and acceptance 2 (the
  supersession text); the merged commit is untouched (workflow.md:291).
- P2 → acceptance 4 (`grep 'world' infochat-collector/src/main/java` → zero
  matches; M1-866 acceptance 8 green for this diff).
- P3 → acceptance 4 + the design itself (data-driven; the analysis's O1/O2
  rejection carries the Alternatives-considered rationale).
- P4 → acceptance 6 (DB-backed tests green with emission-order behavior;
  loader passes the true value, no shim).
- P5 → acceptance 5 (no prompt hunk; grep over prompts/ clean; hand-built
  trees, zero LLM).
- P6 → acceptance 7 (the named suites green unmodified; test_plan.modifies
  lists exactly the authorized test).
- P7 → acceptance 1's depth-composition case + the existing
  deepestLeafWinsWithinOneTop_equalDepthKeepsEmissionOrder green.
- P8 → acceptance 1 (fixtures pin the fallback-MARKED tree; no test
  re-asserts world-wins as a contract) + M1-878 acceptance 4 (name
  pre-authorization).
- Failure mode → the reproduction itself (world-first proposal feeds the
  resolver and must yield europe — the record's measured failure class,
  record:318/349-351).
- acceptance 9 → `mvn verify` exit 0.

## Out-of-scope

See `out_of_scope:` — no migration/seed (M1-866 via M1-878), no
TaggerWorker/prompt/monitor/migration touch, no sweep-fingerprint change
(M1-869's fold-in), no docs/spec/** edit (M1-869 owns all spec text), and
no amendment of M1-865 (the merged commit is never amended; `remediates:
M1-865` records the lineage). The one authorized pre-existing-test edit
(newsIsLowestPriorityFallback:123) is named here with its replacement.

## Census

Not class-scoped: one resolver tiebreak, one record component, one test
flip — a fixed three-file change.
