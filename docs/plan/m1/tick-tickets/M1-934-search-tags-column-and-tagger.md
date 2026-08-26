---
id: M1-934
title: "search_tags column, tagger free-tags emission, sweep backfill"
status: pending
created: 2026-08-26
last_updated: 2026-08-26
flow: tick
reproduction: >-
  SearchTagsCaptureTest.freeTagsRideTheSameCallIntoSearchTags
  `to-be-written` (child of a 2+ decomposition — needs the search_tags
  column; /tick start converts the marker: write the test and run it RED
  against the unmodified code before any fix code, workflow §0).
  Intended wrong behavior it states: the tagger's single LLM call is
  answered with {"tags": ["europe"], "search_tags": ["czechia",
  "prague-eu-summit"]} and the pipeline DISCARDS the second field —
  verified in-tree: TaggerWorker.parseTags reads only the "tags" array
  (TaggerWorker.java:603-661 — the JSON branch dereferences root.get("tags")
  and nothing else), post has no free-tag column (V7's CREATE TABLE post
  lists tags only, V7__joins_post.sql:135-166; V83 added tag_candidates,
  whose Tier-2 losers semantics are the wrong payload — V83:3-11), and
  persistCursor writes four fields (TaggerWorker.java:710-731). The test
  drives processOne with a stubbed provider returning that reply and
  asserts post.tags=['europe'] (tree-resolved), post.search_tags=
  ['czechia','prague-eu-summit'], and exactly ONE provider.generate call
  (the free tags ride the existing call — zero extra LLM cost).
analysis_ref: docs/plan/m1/tick-analysis/category-tag-split.md
blocked_by: []
files_scope:
  - infochat-core/src/main/resources/db/migration/V87__post_search_tags.sql
  - infochat-llm-adapter/src/main/resources/prompts/tagger.md
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TaggerWorker.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/SearchTagsCaptureTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerTest.java
  - docs/spec/schema.md
  - docs/spec/llm.md
  - docs/design/05-llm-and-embeddings.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    ANY READER of search_tags — chat tools (searchPosts topics filter,
    M1-935), the digest footer and /topic (M1-936). This ticket STORES and
    nothing more; no provider-side file is touched (the M1-868 STORES-only
    posture). A GIN index is deliberately NOT added: every chartered reader
    is prefix-LIKE or unnest-aggregation shaped and window-bounded, so no
    index-servable operator exists yet (engineering-rules §7; V83's own
    no-index precedent; analysis P11 — a discrepancy with the brief's
    "TEXT[] + GIN", recorded in the analysis).
  - >-
    THE RESOLUTION PIPELINE — TagTreeResolver is untouched; free tags ride
    as a second output field with their own validation and never enter
    resolve() (analysis P4).
  - >-
    tag_candidates — its Tier-2 losers semantics stay exactly as M1-868
    recorded them; repurposing the fossil is rejected (analysis O1).
  - >-
    prompts/tagger-fallback.md — NOT edited: the line-oriented retry shape
    stays tags-only and its replies produce search_tags='{}' (documented
    degradation, analysis P6); the bootstrap path emits no free tags.
  - >-
    EMBEDDING-INPUT ENRICHMENT — appending tags/search_tags to
    buildInputText is a separate eval-gated decision (analysis P20).
  - >-
    THE EVAL LANE — the golden-set tag-drift check (Czechia/Czech/Česko)
    is a new expected-block kind in M1-928's fixture format consumed by
    the M1-929/930 harness; no eval fixture or measurement record is
    edited here.
  - >-
    BACKFILL VIA DEMOTE-SCRIPT — the classifier precedent (demote to RAW +
    re-promote) is rejected: it transiently removes posts from every
    READY-gated surface; the sweep extension below needs no demotion
    (analysis P8).
acceptance:
  - "REPRODUCTION closed: SearchTagsCaptureTest.freeTagsRideTheSameCallIntoSearchTags passes — a stubbed reply {\"tags\":[\"europe\"],\"search_tags\":[\"czechia\",\"prague-eu-summit\"]} persists tags=['europe'] (resolved) AND search_tags=['czechia','prague-eu-summit'] from the SAME reply with exactly one provider.generate call (binding user decision: fresh column, same call, zero extra LLM cost) (spec: docs/spec/schema.md §Posts and derivatives as amended by this ticket; docs/spec/llm.md §SPI shape as amended; analysis P5, P7)."
  - "V87 (next free number at start; head is V86, re-verify) adds post.search_tags TEXT[] NOT NULL DEFAULT '{}' via ALTER on the partitioned parent (V83 precedent — propagates to every child partition; no GRANT change: post's existing per-role grants cover it) with NO index — pinned by a @QuarkusTest on the Flyway-migrated schema asserting the column, its DEFAULT, and that pg_indexes returns NO index whose definition references search_tags (analysis P11; engineering-rules §7)."
  - "Write-side canonicalization mandatory: every stored free tag is TagNormalizer.normalize output (NFC + Locale.ROOT lower-case + ^[a-z0-9][a-z0-9-]{0,47}$, commands.md §Surface conventions); entries failing the class (e.g. \"Czech Republic\", \"Česko\", emoji) are dropped, counted and logged on the existing tagger_partial_valid line's pattern — pinned by SearchTagsCaptureTest.nonNormalizableFreeTagsAreDroppedAndCounted (failure mode: mixed valid/invalid array → only canonical survivors stored, drop count logged) (analysis P5; spec: docs/spec/llm.md §Failure handling (recap) as amended)."
  - "Bounded (M1-328 discipline re-derived): at most MAX_SEARCH_TAGS_PER_POST distinct valid free tags in emission order, duplicates-after-normalization dropped, overflow counted and logged — pinned by SearchTagsCaptureTest.capOverflowKeepsFirstEmittedFreeTagsAndLogsDropCount (failure mode: winner + > cap distinct valid free tags → exactly cap stored in emission order, drop count observable) (analysis P5)."
  - "Outcome-map pinned: a reply missing/null search_tags stores '{}' (never an error, never a retry); a clean-empty categories proposal (NO_TAGS) with non-empty search_tags stores the free tags with tags='{}' and NO retry (the M1-726 invalidCount discrimination stays keyed on the tags array alone); a TAGS:-shaped line-oriented reply produces search_tags='{}'; the BOOTSTRAP path writes search_tags='{}' — pinned by tests for all four paths (analysis P6; spec: docs/spec/llm.md §Failure handling (recap) — empty-proposal and fallback semantics unchanged)."
  - "The write rides the SAME atomic cursor UPDATE as tags + tag_candidates + tagger_done + tagger_fallback (Invariant 5): one statement moves all five fields; the sweep path (processOne reuse) writes identically on re-tagged posts — pinned by a test asserting one statement and by TaggerWorkerIT/TaggerWorkerBackoffTest staying green (analysis P7; spec: docs/spec/schema.md §Invariants)."
  - "SWEEP-BORNE BACKFILL (analysis P8): sweepFingerprint gains a sha256 leg over the loaded prompt templates (primary + fallback byte content) alongside model + sorted vocabulary names — pinned by a fingerprint test asserting the prompt-content leg changes the digest while template-loading order does not; enumerateSweepCandidates eligibility becomes tagger_done AND NOT tagger_fallback AND (tags='{}' OR search_tags='{}') AND generation/attempt caps — pinned by sweep IT legs: (a) a done non-fallback search_tags='{}' post becomes eligible after the deploy-time generation bump, (b) a tagger_fallback post NEVER becomes eligible, (c) the existing tags='{}' eligibility and per-post attempt cap hold unchanged (spec: docs/spec/llm.md §Failure handling (recap) — the M1-736 sweep contract, eligibility widened; V87's header comment states the one-time bounded backfill exactly as V84's stated the vocabulary sweep)."
  - "§8-AUTHORIZED pre-existing-test modifications (engineering-rules §8; this ticket authorizes exactly these, in plain language): (a) the cursor-statement pin updated from four to five fields — the atomic write now also moves search_tags; (b) sweep-eligibility assertions that pinned tags='{}' as the sole eligible set gain the search_tags='{}' OR-arm; (c) fingerprint assertions that pinned model+vocabulary gain the prompt leg. Every other pre-existing test passes UNMODIFIED — probe: git diff names no test file outside the authorized set."
  - "Prompt edit is the ONLY prompt change: tagger.md's reply-shape block gains the second field and its canonicalization rules (English; lower-case; single hyphenated words; no spaces; omit the field or emit [] when none fit) — the wrapper discipline, vocabulary render, and every other line are byte-identical; tagger-fallback.md untouched — pinned by a render test asserting the rendered primary prompt contains the search_tags instruction exactly once and the fallback prompt contains none (analysis P6; docs/design/05-llm-and-embeddings.md §5.4 records the two-field contract)."
  - "Spec amendments ride the diff (engineering-rules §12 — exact wording user-approved at implementation; rule-text drafts in the Approach; rides-the-diff shape, NOT a SPEC-GAP): docs/spec/schema.md §Posts and derivatives Post bullet gains the search_tags sentence (retrieval-only free tags; canonical stored form per §Surface conventions; never digest-counted, never a follow/bootstrap/tag-tree surface); docs/spec/llm.md §SPI shape Tagger row and §Failure handling (recap) Tagger bullet gain the free-tags emission contract (same call, best-effort second field, missing = empty, fallback paths emit none, the tags-array outcome chain unchanged) — probes: grep -n 'search_tags' docs/spec/schema.md and docs/spec/llm.md each return the new rows; no date/ticket-id tokens in the added prose."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/SearchTagsCaptureTest.java
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerTest.java
      — new cases: outcome-map four paths, five-field one-statement pin,
      fingerprint prompt-leg, sweep-eligibility legs (or a SearchTagsSweepIT
      if the existing sweep IT's harness is the better host — implementer's
      choice, named in the review table).
  modifies:
    - >-
      TaggerWorkerTest/TaggerWorkerSweepIT cursor-statement and
      sweep-eligibility/fingerprint assertions — ONLY the three §8-authorized
      updates of acceptance item 8.
  preserves:
    - all tests currently green on main
    - >-
      TagCandidatesCaptureTest, TaggerWorkerIT, TaggerWorkerBackoffTest,
      TagTreeResolutionTest, TagVocabularyRefreshTest, MiscShareMonitor
      tests — unmodified.
spec_refs:
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/schema.md §Invariants
  - docs/spec/llm.md §SPI shape
  - docs/spec/llm.md §Failure handling (recap)
  - docs/spec/commands.md §Surface conventions
decision_refs:
  - D5
  - D19
  - D22
  - D29
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

# M1-934: search_tags column, tagger free-tags emission, sweep backfill

## Context

`post.tags` is the bounded v2 category set (53 seeded leaves, V84); the
specificity retrieval wants (czechia, zcash, qwen) has nowhere to live:
the tree bottoms out at continents, `post_entity` is unreadable from
chat, and the tagger's free signal is discarded. Binding user decisions:
categories stay in `post.tags` (digest untouched); free tags are a NEW
separate column `post.search_tags` (NOT tag_candidates — the fossil
holds resolution losers and mapped-away v1 names under a declared
never-queryable contract); canonical form English + lowercase with
mandatory write-side normalization; the tagger emits both fields in its
EXISTING single call. Shared context: `analysis_ref:` (analysis doc,
Ground truth's write-path citations; Pitfalls P4-P8, P11, P19).

## Root cause

Verified: `TaggerWorker.parseTags` reads only the `tags` array
(TaggerWorker.java:603-661), `persistCursor` writes four fields
(:710-731), and `post` has no free-tag column (V7:135-166; V83's
`tag_candidates` is the Tier-2 losers array, V83:3-11 — wrong content,
wrong provenance, wrong contract for free tags). The data arrives in the
model's reply context today and is dropped. Historical coverage is
absent by the same mechanism (nothing backfills a column that did not
exist), and the M1-736 sweep cannot see it: `sweepFingerprint` covers
model + vocabulary names only (TaggerWorker.java:907-922) — a prompt
change is invisible to it, and eligibility is `tags='{}'` only
(:788-821).

## Pitfalls

- P5: untrusted-LLM bound — MAX_SEARCH_TAGS_PER_POST cap, TagNormalizer
  mandatory, non-normalizable dropped+counted+logged; the character
  class is also the render-safety control for M1-936's footer (a
  canonical tag cannot forge a command token).
- P6: outcome-chain decoupling — free tags never trigger retry/fallback;
  missing field = '{}'; NO_TAGS-with-free-tags stores them; line-oriented
  and bootstrap paths emit none (documented degradation, stated in the
  spec amendment).
- P7: one atomic cursor UPDATE carries the fifth field (Invariant 5) —
  never a second statement, never a crash window.
- P8: backfill without visibility loss — sweep eligibility OR +
  prompt-sha fingerprint leg (this deploy IS the one-time input change);
  fallback rows excluded (their tags are bootstrap by design); bounded
  by batch/attempt caps; live-first starvation preserved; the re-drive
  re-derives categories too (measured leaf stability 0.9684 — stated,
  not hidden). Scope decision (binding question from the brief): ALL
  tagger-done non-fallback posts inside the tagger scan window
  (retention horizon) — no last-N-days subset.
- P11: no GIN — every chartered reader is prefix-LIKE/unnest-shaped;
  §7 no machinery ahead of need (brief discrepancy recorded).
- P19: fixtures pin the post-change two-field reply shape (M1-785
  lesson) — never a shape M1-935/936 removes.

## Approach

- **Files to touch:** V87 migration, `prompts/tagger.md`,
  `TaggerWorker` (parse/normalize/cap/thread/persist + fingerprint +
  sweep eligibility), the two spec files, the design note, tests.
- **Steps, in order:**
  1. Convert the reproduction marker: write `freeTagsRideTheSameCallIntoSearchTags`,
     run RED (no column, field dropped).
  2. V87: `ALTER TABLE post ADD COLUMN search_tags TEXT[] NOT NULL
     DEFAULT '{}'` on the parent; header comment states the sweep-bump
     backfill (the V84 precedent) and the no-index decision.
  3. tagger.md reply-shape block: add `"search_tags": [...]` with the
     canonicalization rules (acceptance item 9's pin); fallback prompt
     untouched.
  4. TaggerWorker: parse the second array (strings only, mixed keeps
     strings — the parseTags precedent); normalize each via
     TagNormalizer; cap at MAX_SEARCH_TAGS_PER_POST (8 — 2× the
     design-intended 1-4, mirroring MAX_TAGS_PER_POST); drop+count+log
     rejects; thread into `persistCursor`'s single UPDATE; NO change to
     the retry/fallback/bootstrap decisions.
  5. Sweep: fingerprint += sha256(primary template bytes + NUL +
     fallback template bytes); eligibility `(tags='{}' OR
     search_tags='{}')`.
  6. Spec + design-note amendments with the user's wording approval.
- **Controls to preserve (engineering-rules §10):** the tagger chain's
  incidental obligations all travel unchanged — three-surface fallback,
  partial-valid, empty-proposal-as-outcome, no-tags/misc monitor
  classes, SafeLog on catch paths, the injected-Clock scan floor, sweep
  bookkeeping and caps, the wrapper discipline and per-call UUID. The
  atomic cursor write is EXTENDED, never split.
- **Pitfall→mitigation:** P5→step 4 cap/normalize + the two
  failure-mode tests; P6→step 4's no-chain-touch rule + the outcome-map
  tests; P7→acceptance 6's one-statement pin; P8→step 5 + the sweep IT
  legs; P11→acceptance 2's pg_indexes probe; P19→fixtures in step 1.

## Definition of done

Every acceptance item holds: the reproduction passes (both fields from
one call); V87 column + DEFAULT + no index; canonicalization and cap
failure-mode tests green; the four-path outcome map pinned; the
five-field atomic write pinned; the sweep backfill legs (eligibility
OR-arm, fallback exclusion, fingerprint prompt leg, caps) pinned; the
three §8-authorized test updates land with intent stated and the fence
probe shows no other test touched; the prompt-edit pin (one field, one
instruction, fallback untouched) holds; the schema.md/llm.md rows carry
the user-approved rule text; `mvn verify` green.

## Verification

- Reproduction → acceptance 1.
- P5 → acceptance 3 (failure mode: mixed valid/invalid array → only
  canonical survivors stored, drop count logged) and acceptance 4
  (failure mode: >cap distinct valid free tags → exactly cap kept in
  emission order, overflow counted and logged).
- P6 → acceptance 5 (missing/null → '{}'; NO_TAGS+free stored, no
  retry; line reply → '{}'; bootstrap → '{}').
- P7 → acceptance 6 (one statement, five fields; sweep path identical;
  TaggerWorkerIT/BackoffTest green).
- P8 → acceptance 7 (fingerprint prompt-leg test; three sweep-eligibility
  legs; V87 header probe).
- P11 → acceptance 2's pg_indexes assertion (a GIN added anywhere on
  search_tags fails it).
- P19 → acceptance 1's reply fixture and acceptance 5's outcome-map
  fixtures both pin the post-change two-field reply shape (the
  reproduction seeds {"tags","search_tags"} in ONE reply; the
  outcome-map legs pin that same contract's edge cases) — no fixture
  pins a single-field or losing-leaf shape a later sibling would have
  to remove; reviewer diff check over SearchTagsCaptureTest's stubbed
  replies.
- §8 fence → acceptance 8's git-diff probe.
- Prompt discipline → acceptance 9's render test.
- Spec amendments → acceptance 10's greps + user-approval record.
- acceptance 11 → `mvn verify` exit 0.

## Out-of-scope

See `out_of_scope:` — no reader wiring ever in this ticket (M1-935/936
own the readers); no GIN (P11); TagTreeResolver untouched;
tag_candidates untouched; tagger-fallback.md untouched; embedding-input
enrichment out (P20); the eval lane (M1-928/929/930 fixtures/harness)
untouched; no demote-script backfill (rejected with the visibility
argument, analysis P8). The three §8-authorized test updates are the
only pre-existing-test edits; every other suite passes unmodified.

## Census

Not class-scoped: one column, one emitter, one sweep mechanism. (The
READ-site census over search_tags lives in M1-935/M1-936.)
