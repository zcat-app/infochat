---
id: M1-865
title: "Tag tree schema and deterministic leaf resolution"
status: pending
created: 2026-08-16
last_updated: 2026-08-16
flow: tick
reproduction: >-
  to-be-written TagTreeResolutionTest.crossTopProposalResolvesToASingleBranch
  (child of a 2+ decomposition — the tree schema this test reads does not
  exist at filing time; `start` converts the marker per workflow §0).
  Intended wrong behavior it states: TaggerWorker.validate
  (TaggerWorker.java:642-661) accepts every vocabulary-valid proposal and
  the worker stores the whole set — a post the model tags {football,
  europe} (or {ai, research}) is persisted with BOTH cross-branch names
  in post.tags, so Tier-1 carries no resolved branch, no subject-beats-
  lens ordering exists anywhere in the code (grep for any resolution
  step between validate and persistCursor returns nothing), and the
  showcase's constant cross-top proposals (football+europe, esports+
  gaming, ai+cybersecurity — .bench/tag-tree-showcase/calls.jsonl)
  would all land multi-branch. The test feeds a validated multi-leaf,
  multi-top proposal set through the resolution path and asserts exactly
  ONE stored leaf per the fixed top-priority order.
analysis_ref: docs/plan/m1/tick-analysis/tag-tree-taxonomy-v2.md
blocked_by: []
files_scope:
  - infochat-core/src/main/resources/db/migration/V81__tag_tree.sql
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TagVocabulary.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TaggerWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TagTreeResolver.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/MiscShareMonitor.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TagTreeResolutionTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerTest.java
  - docs/design/05-llm-and-embeddings.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    THE TAGGER PROMPTS (tagger.md / tagger-fallback.md) — the showcase
    measured the leaf render through the UNEDITED production templates
    (calls.jsonl rows carry the verbatim tagger.md text); only the LOADED
    vocabulary changes. A diff touching prompt text has left scope.
  - >-
    SEEDING OR MIGRATING ANY VOCABULARY DATA — no tag rows are written
    here; the v2 seed + v1 lookup migration is M1-866 (blocked_by this
    ticket). This ticket lands the MECHANISM only (columns, leaf-only
    load, resolver, monitor).
  - >-
    THE TIER-2 CANDIDATE ARRAY's storage — M1-868 owns the column and the
    capture write. This ticket exposes the resolution's losing leaves to
    the caller (a record/return shape), and the interim behavior (losers
    computed then dropped until M1-868 lands) is stated, bounded, and
    acceptable — new posts between the two tickets lose side-data only.
  - >-
    ANY CONSUMER (digest, search, follow, summary) — every tree-aware READ
    site is M1-867. Nothing under infochat-provider/ is touched.
  - >-
    EDITING pre-existing tests (TagVocabularyRefreshTest, TaggerWorkerIT,
    TaggerWorkerSweepIT, TaggerWorkerBackoffTest, NoTagsRateMonitor's
    tests) — the tree columns default node_kind='leaf'/NULL parent so
    every existing fixture row keeps today's semantics; the tests assert
    relative facts and stay green unmodified. test_plan.modifies is
    empty; any edit is an unauthorized engineering-rules §8 change.
acceptance:
  - "TagTreeResolutionTest.crossTopProposalResolvesToASingleBranch (the converted reproduction) passes: given validated leaf proposals spanning multiple tops (football+europe, ai+research, esports+gaming), the resolver returns exactly ONE leaf — the branch whose top ranks highest in the fixed priority order Sport > Health > Fashion > Culture > Science > Tech > Business > News-last; within one top, the deepest leaf wins; equal-depth ties resolve by that same top order (all v2 leaves are depth-2, so the top order is the operative discriminator, and the depth rule is implemented depth-generally) — spec: docs/spec/schema.md §Sources and tags (Tag entity gains the tree shape); docs/spec/llm.md §SPI shape (Tagger) as amended by M1-869; analysis P8."
  - "News-last fallback pinned by TagTreeResolutionTest.newsIsLowestPriorityFallback: a proposal set containing ONLY News leaves (europe+world) resolves to the first News leaf in emission order, and a proposal mixing News and any other top never resolves to News — the deterministic statement of 'a news report that fits nothing else files under News->continent' (analysis P8; decision 4)."
  - "Identity-branch passthrough for pre-migration data: a vocabulary name with NO parent (node_kind='leaf', parent NULL — every row until M1-866 lands) is its own branch; a single-leaf or parentless-leaf proposal set resolves to itself, so behavior between this ticket and M1-866 is byte-identical to today — pinned by a test that feeds the CURRENT 23-name-shaped vocabulary and asserts today's stored sets (analysis P6)."
  - "TagVocabulary loads LEAVES ONLY: the SELECT gains the node filter while keeping ORDER BY name; names() iterates in query order (TagVocabularyRefreshTest passes UNMODIFIED — the order contract survives the WHERE clause) and a new assertion pins that a seeded TOP row is absent from names() — the model's render never contains tops or the priority order (analysis P7, P8; spec: docs/spec/llm.md §SPI shape)."
  - "TaggerWorker stores exactly ONE resolved leaf per post in post.tags: the resolver runs after validate() (which still normalizes, membership-checks, and caps the pre-resolution list — MAX_TAGS_PER_POST's structural bound on untrusted LLM output is preserved, M1-328), and the same single atomic cursor UPDATE writes tags + tagger_done + tagger_fallback (the Invariant-5 single-statement discipline, M1-034a/M1-726) — pinned by a TaggerWorkerTest case asserting the stored array has length 1 and the winner per the priority order (analysis P15; spec: docs/spec/llm.md §Failure handling (recap))."
  - "Failure-mode: a hostile or drifting model proposing top names (e.g. 'tech') or garbage alongside valid leaves changes nothing structurally — top names are not in the leaf vocabulary so they count invalid exactly like today's out-of-vocabulary proposals (partial-valid keeps the valid leaves); a reply of ONLY top/garbage names follows the existing zero-valid retry -> bootstrap chain. Pinned by TaggerWorkerTest cases feeding top-name proposals (analysis P8)."
  - "MiscShareMonitor mirrors NoTagsRateMonitor (M1-735 shape): it records the share of tagger completions whose resolved leaf is 'misc', fires a throttled admin alert under a DISTINCT error class (never tagger.sustained_no_tags, never tagger.fallback_to_bootstrap) when the share exceeds infochat.tagger.misc-share-threshold (default 0.10) over infochat.tagger.misc-share-min-sample, and stays SILENT below the minimum sample (cold start) and on a normal trickle — pinned by tests for all four (fires / silent-below-threshold / cold-start-silence / distinct class); the keys are declared in the collector application.properties and documented in docs/design/05-llm-and-embeddings.md so scripts/lint-config-keys.py and DocumentedConfigKeyParityTest stay green (analysis P16; decision 5; spec: docs/spec/llm.md §Failure handling (recap) — the observability pattern)."
  - "V81 (next free number at start; head is V80) adds the tree shape to tag atomically: a node-kind discriminator with DEFAULT 'leaf' and a parent reference to tag(name) (tops are rows like any other; leaf names stay globally unique via the existing UNIQUE(name), which the migration comment states is the top-derivation invariant); grants unchanged (provider already SELECT); no data rows written — pinned by a @QuarkusTest on the Flyway-migrated schema asserting the columns, the DEFAULT, and that a plain INSERT ... (name) still succeeds as a leaf (analysis P6, P9; spec: docs/spec/schema.md §Sources and tags)."
  - "The fixed top-priority order lives in Java (a constant or config on the resolver), NEVER rendered into any prompt and never sent to the model — pinned by a render test asserting the rendered prompt for a seeded tree contains leaf names only (analysis P8; decision 2)."
  - "TagVocabularyRefreshTest, TaggerWorkerIT, TaggerWorkerSweepIT, TaggerWorkerBackoffTest pass UNMODIFIED (seed-safe by construction — DEFAULT 'leaf'; relative assertions only; the sweep fingerprint mechanism is untouched and will legitimately bump when M1-866 swaps the vocabulary) (analysis P13)."
  - "mvn verify from repo root is green."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TagTreeResolutionTest.java
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerTest.java
      — new cases: single-resolved-leaf write, top-name-proposal
      failure mode, identity passthrough, leaf-only vocabulary render.
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/MiscShareMonitor
      test coverage (new class's test, fires/silent/cold-start/distinct
      class).
  modifies: []
  preserves:
    - all tests currently green on main
    - >-
      TagVocabularyRefreshTest (order + runtime-add visibility), every
      TaggerWorker fallback/partial-valid/no-tags assertion, the atomic
      single-UPDATE pin, TaggerWorkerSweepIT.
spec_refs:
  - docs/spec/schema.md §Sources and tags
  - docs/spec/llm.md §SPI shape
  - docs/spec/llm.md §Failure handling (recap)
  - docs/spec/commands.md §Surface conventions
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
remediates:
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-865: tag tree schema and deterministic leaf resolution

## Context

The v2 taxonomy's model half is measured (M1-864's showcase predecessor:
leaf proposals are clean, cross-top proposals are constant); the code
half does not exist. Today `TaggerWorker.validate`
(TaggerWorker.java:642-661) accepts every vocabulary-valid proposal and
the worker stores the whole set — `{football, europe}` lands as a
two-branch Tier-1 state with no ordering discipline anywhere, which is
the code-level shape of the defect M1-860 measured (flat names, no
boundary mechanism). This ticket lands the deterministic half of
decision 2: the tree shape on `tag`, the leaf-only prompt render, the
Java resolver that stores exactly ONE branch per post, and the misc-share
monitor (decision 5's vocabulary-growth trigger). Shared context:
`analysis_ref:` (analysis doc, Pitfalls P6–P9, P15, P16).

## Root cause

Verified: there is no resolution step between `validate` and
`persistCursor` (read in full — the valid list flows straight to the
atomic UPDATE at :679-696), no tree structure on `tag` (V6:74-84), and
`TagVocabulary` loads every row (:126-150). The vocabulary cannot
express disjoint tops or priority, so consumers cannot become
tree-aware. The fix is purely additive mechanism: columns (defaulting
to today's semantics), a load filter, a resolver, a monitor.

## Pitfalls

- P6: intermediate state — tree columns land before data (M1-866);
  parentless rows MUST behave as identity branches so behavior is
  unchanged until the seed arrives.
- P7: the leaf filter must not break the `ORDER BY name` publication
  contract (M1-751; TagVocabularyRefreshTest).
- P8: the tree, the tops, and the priority order NEVER enter the prompt;
  the resolver is pure Java, unit-tested without an LLM (D19).
- P15: `MAX_TAGS_PER_POST` keeps bounding the pre-resolution validated
  list (the LLM trust boundary, M1-328); Tier-1 output is one leaf by
  construction.
- P16: the misc monitor is a NEW aggregate with a DISTINCT error class;
  empty-proposal and fallback semantics (M1-726/M1-735) are untouched.

## Approach

- **Files to touch:** V81 migration (tree columns on `tag`),
  `TagVocabulary` (leaf-only SELECT), new `TagTreeResolver` (pure,
  injectable, unit-testable), `TaggerWorker` (resolve after validate;
  same atomic cursor UPDATE; expose losers to the caller for M1-868),
  new `MiscShareMonitor` (+ config keys), tests, and the
  05-llm-and-embeddings design note (resolver + monitor parameters).
- **Steps, in order:**
  1. Convert the reproduction marker: write
     `crossTopProposalResolvesToASingleBranch`, run it RED (no resolver
     exists).
  2. V81: `node_kind TEXT NOT NULL DEFAULT 'leaf' CHECK (node_kind IN
     ('top','leaf'))` + parent reference to `tag(name)`; comment states
     the global-leaf-uniqueness invariant rides UNIQUE(name). No data.
  3. `TagTreeResolver`: fixed priority order constant; resolve(valid
     leaves, tree) → winning leaf + losers; identity passthrough for
     parentless names (P6).
  4. `TagVocabulary`: `SELECT name FROM tag WHERE node_kind='leaf'
     ORDER BY name` — order contract preserved (P7); new test pins
     tops' absence.
  5. `TaggerWorker`: validate → resolve → persist ONE leaf in the SAME
     single UPDATE; losers returned (dropped until M1-868 — stated
     interim); top-name proposals count invalid (P8, P15).
  6. `MiscShareMonitor` + config keys + design-note documentation
     (P16).
- **Controls to preserve (engineering-rules §10):** the tagger chain's
  incidental obligations stay — three-surface fallback, partial-valid,
  empty-proposal-as-outcome, SafeLog on catch paths, the atomic cursor
  write, the injected-Clock scan floor, the sweep bookkeeping. The
  prompt-injection wrapper discipline is untouched (title already
  inside the delimiter, M1-599; render test re-asserts leaf-only).
- **Pitfall→mitigation:** P6→step 2/3 defaults + the passthrough test;
  P7→step 4 + TagVocabularyRefreshTest green unmodified; P8→step 3/5 +
  render test; P15→step 5 keeps the cap on the pre-resolution list;
  P16→step 6 distinct-class tests.

## Definition of done

Every acceptance item holds: the converted reproduction passes
(cross-top sets resolve to one branch); News-last and identity
passthrough pinned; leaf-only ordered render pinned; single-leaf atomic
write pinned; top-name failure mode pinned; the misc monitor's four
behaviors pinned with config documented; V81 lands with columns +
DEFAULT and no data; priority order never rendered; the named
pre-existing tests pass unmodified; `mvn verify` green.

## Verification

- P6 → `TagTreeResolutionTest.parentlessNameIsItsOwnBranch` + the
  current-vocabulary passthrough case (acceptance 3).
- P7 → TagVocabularyRefreshTest green unmodified + the tops-absent
  assertion (acceptance 4).
- P8 → `TagTreeResolutionTest.crossTopProposalResolvesToASingleBranch`
  and `TagTreeResolutionTest.newsIsLowestPriorityFallback` (subject-
  beats-lens `football+economy → football`; News-last `europe alone →
  europe`; equal-depth tie) and the render test (acceptances 1, 2, 9).
- P15 → the pre-resolution cap case + stored-length-1 case (acceptance
  5).
- P16 → MiscShareMonitor's fires/silent/cold-start/distinct-class tests
  (acceptance 7).
- Failure mode → acceptance 6 (top-name/garbage proposals change
  nothing structurally).
- Reproduction → acceptance 1 (the converted test, now passing).
- acceptance 11 → `mvn verify` exit 0.

## Out-of-scope

See `out_of_scope:` — no prompt-text edits (the showcase proved the
current prompts work with a leaf render), no vocabulary data (M1-866),
no candidate-array storage (M1-868), no provider/consumer changes
(M1-867), no pre-existing-test edits. If the interim losers-dropped
window bothers review, widening capture here is scope drift — M1-868 is
one ticket away and blocked only by this one.

## Census

Not class-scoped: one resolver, one load path, one monitor. (The
tree-aware READ-site census lives in M1-867.)
