---
id: M1-868
title: "Store losing tag proposals as a Tier-2 search array"
status: pending
created: 2026-08-16
last_updated: 2026-08-16
flow: tick
reproduction: >-
  to-be-written TagCandidatesCaptureTest.resolutionLosersLandInTagCandidates
  (child of a 2+ decomposition — needs M1-865's resolution, which returns
  the losing leaves to the caller; `start` converts the marker per
  workflow §0). Intended wrong behavior it states: M1-865's resolver
  computes the losing validated leaves (the showcase measured them
  constant: football+europe, esports+gaming, ai+cybersecurity+world —
  .bench/tag-tree-showcase/calls.jsonl) and then DROPS them — no column
  exists to hold them (post's columns verified at V7/V66: tags, no
  candidate array), so the information the user's decision 1 explicitly
  wants kept ("the losing proposals already exist in model output —
  zero extra LLM calls") is discarded on every post, and M1-866's
  entity-continuity migration (old claude/openai/... names landing in
  the array) has no landing site.
analysis_ref: docs/plan/m1/tick-analysis/tag-tree-taxonomy-v2.md
blocked_by:
  - M1-865
files_scope:
  - infochat-core/src/main/resources/db/migration/V82__post_tag_candidates.sql
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TaggerWorker.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TagCandidatesCaptureTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - >-
    ANY CONSUMER of the array — it is Tier-2-class internal data (D5
    pattern): never rendered, never digest-counted, never
    /follow-tag-addressable, never a searchPosts filter surface. Wiring
    any reader (chat, RAG, admin surface) is a future ticket if ever;
    D59 keeps chat/RAG search broad so no consumer conflict exists
    today. This ticket STORES and nothing more.
  - >-
    BACKFILL — M1-866's migration is the sole writer of historical
    content into the array (mapped-away entity names); this ticket adds
    no re-evaluation, no LLM, no sweep of old posts.
  - >-
    THE RESOLUTION ALGORITHM — M1-865 owns it; this ticket only consumes
    its losers output. A diff touching resolver ordering has left scope.
  - >-
    EDITING pre-existing tests — TaggerWorkerTest/TaggerWorkerIT stay
    green unmodified (the column defaults '{}' so existing assertions on
    tags/tagger flags are unaffected); test_plan.modifies is empty.
acceptance:
  - "TagCandidatesCaptureTest.resolutionLosersLandInTagCandidates (the converted reproduction) passes: a stubbed LLM reply whose validated proposals span multiple tops (e.g. football+europe) persists the resolved winner in post.tags AND the losing leaves in the new post.tag_candidates array — captured from the SAME reply with zero additional LLM calls (decision 1: the losers already exist in model output) (spec: docs/spec/schema.md §Posts and derivatives as amended by M1-869; decision D5 Tier-1/Tier-2 pattern)."
  - "V82 (next free number at start; after M1-865's V81) adds post.tag_candidates TEXT[] NOT NULL DEFAULT '{}' via ALTER on the partitioned parent (the V66 precedent — columns propagate to every child partition; no GRANT change: post's existing per-role grants cover it) — pinned by a @QuarkusTest on the Flyway-migrated schema asserting the column, its DEFAULT, and that the GIN index strategy mirrors idx_post_tags_gin only if a query needs it (no index without a reader — engineering-rules §7, no machinery ahead of need) (analysis P15)."
  - "The write rides the SAME atomic cursor UPDATE as tags + tagger_done + tagger_fallback (TaggerWorker.persistCursor's single statement, the Invariant-5 discipline — M1-034a/M1-726): a crash can never leave candidates written without the cursor, or vice versa; the sweep path (processOne reuse) writes candidates identically on re-tagged posts — pinned by a test asserting one statement moves all four fields and by the existing TaggerWorkerIT suite staying green (spec: docs/spec/llm.md §Failure handling (recap))."
  - "Bounded (M1-328 discipline re-derived for the new unit), pinned by TagCandidatesCaptureTest.capOverflowKeepsFirstEmittedLosersAndLogsDropCount: the array holds at most MAX_TAGS_PER_POST distinct losing leaves, in emission order, duplicates-after-normalization dropped; overflow is counted and logged on the existing tagger_partial_valid line's pattern (observable, not silent) — the failure-mode test stubs a reply proposing the winner plus > cap distinct valid losers, asserts exactly cap candidates persist in emission order, and asserts the drop count is logged (analysis P15)."
  - "Outcome-map pinned: LLM empty-proposal (NO_TAGS) writes candidates='{}' (nothing was proposed); BOOTSTRAP fallback writes candidates from the failed attempts' validated losers if any, else '{}' — pinned by tests for both paths, asserting the array never contains the Tier-1 winner or any non-leaf (analysis P15)."
  - "Internal-only pinned: grep for tag_candidates over infochat-provider/src/main/java and the bundle/renders returns ZERO matches — no user-facing surface reads it (decision 1; D59)."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TagCandidatesCaptureTest.java
  modifies: []
  preserves:
    - all tests currently green on main
    - >-
      TaggerWorkerTest/TaggerWorkerIT (cursor/flag assertions), the
      atomic single-UPDATE pin, TaggerWorkerSweepIT — unmodified.
spec_refs:
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/llm.md §Failure handling (recap)
decision_refs:
  - D5
  - D59
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-868: store losing tag proposals as a Tier-2 search array

## Context

Decision 1: the Tagger's other validated-but-losing candidate leaves are
stored as a Tier-2-class search array — internal, never user-facing,
never digest-counted, never /follow-tag-addressable — at zero extra LLM
cost, because the losers already exist in the model's output (measured:
the showcase's cross-top proposals are constant; M1-860 B1 showed fashion
fixtures propose culture 13/18 and business 5/18). M1-865's resolver
computes and returns the losers; today they are dropped, and M1-866's
entity-continuity migration needs the landing site this ticket creates.
Shared context: `analysis_ref:` (analysis doc, Pitfalls P15, P19;
decision 1).

## Root cause

Not a defect — a missing storage site. Verified: `post` has no candidate
column (V7 columns + V66 sweep columns; nothing since adds one), and
`TaggerWorker.persistCursor` (TaggerWorker.java:679-696) writes only
tags/tagger_done/tagger_fallback. The data exists in-process at
resolution time and is discarded.

## Pitfalls

- P15: bound the array (M1-328's structural-bound discipline re-derived
  for the new unit); count and log overflow, never silently drop.
- P19: tests assume M1-865's resolution output (fixture leaves, one
  winner + losers).
- Atomicity: the array rides the EXISTING single cursor UPDATE — never a
  second statement (Invariant 5).

## Approach

- **Files to touch:** V82 migration (the column), TaggerWorker
  (candidates into the cursor UPDATE; outcome map), the new test class.
- **Steps, in order:**
  1. Convert the reproduction marker: write
     `resolutionLosersLandInTagCandidates`, run RED (no column).
  2. V82: ALTER post ADD COLUMN tag_candidates TEXT[] NOT NULL DEFAULT
     '{}' (parent ALTER, V66 precedent; no index — no reader exists).
  3. TaggerWorker: thread M1-865's losers into persistCursor's single
     UPDATE; cap + drop-count logging; NO_TAGS/BOOTSTRAP outcome map.
  4. Internal-only probe (grep).
- **Controls to preserve:** the atomic cursor write (extended, not
  split); SafeLog/logging conventions; the sweep path's identical
  semantics; nothing provider-side changes.
- **Pitfall→mitigation:** P15→step 3 cap + failure-mode test; P19→
  fixtures per M1-865's resolver; atomicity→acceptance 3's one-statement
  pin.

## Definition of done

Every acceptance item holds: the converted reproduction passes (losers
persist alongside the winner, zero extra LLM calls); V82 column with
DEFAULT and no premature index; the single-statement atomic write; the
cap with logged overflow; the NO_TAGS/BOOTSTRAP outcome map; the
internal-only grep returns zero; `mvn verify` green.

## Verification

- Reproduction → acceptance 1.
- P15 → acceptance 4, TagCandidatesCaptureTest.capOverflowKeepsFirstEmittedLosersAndLogsDropCount
  (failure mode: > cap distinct losers → exactly cap stored in emission
  order, drop count logged).
- P19 → the reproduction and acceptance 1 feed resolver-output shapes
  (one winner + losing leaves, e.g. football+europe) produced by
  M1-865's resolution, never v1 flat sets — the fixtures pin the
  post-865/868 END state.
- Atomicity → acceptance 3 (one statement, four fields; sweep path
  identical; existing ITs green unmodified).
- Outcome map → acceptance 5 (empty proposal → '{}'; fallback path).
- Internal-only → acceptance 6 grep probe.
- acceptance 7 → `mvn verify` exit 0.

## Out-of-scope

See `out_of_scope:` — no consumer wiring ever in this ticket (Tier-2
internal per D5/D59), no backfill (M1-866's migration is the sole
historical writer), no resolver changes, no pre-existing-test edits. A
GIN index is deliberately NOT added: no query reads the column (§7 — no
machinery ahead of need; index when a reader ticket exists).
