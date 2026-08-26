---
id: M1-928
title: "Golden-set fixtures for the fused-retrieval eval"
status: pending
created: 2026-08-26
last_updated: 2026-08-26
flow: tick
reproduction: >-
  Probe (fixtures ticket; no test can exist before the set does — the
  M1-844/M1-859 posture): `ls infochat-provider/src/test/resources/retrieval-eval/`
  returns "No such file or directory", and `git ls-files | grep -c
  'retrieval-eval'` returns 0 — no labeled query set for the live corpus is
  committed anywhere. Observed consequence (verified): the only labeled
  retrieval fixtures target the gitignored `.bench/m1-717/` container corpus
  (9,224 posts, a different corpus from the live/test DB) and are self-marked
  incomplete — 23 of 41 rows carry `pooling_pending` (docs/measurement/
  retrieval-separability.md:128-142) — and contain no temporal,
  entity-location, price-shaped, or cross-lingual classes; retrieval-affecting
  changes M1-916/M1-917/M1-927 therefore landed with nothing to measure
  against (M1-927 "fell flat" with no measurable why, user-confirmed brief
  2026-08-26).
analysis_ref: docs/plan/m1/tick-analysis/golden-set-retrieval-eval.md
blocked_by: []
files_scope:
  - infochat-provider/src/test/resources/retrieval-eval/golden-set.jsonl
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalGoldenSetTest.java
  - scripts/eval-scopes-seed.sql
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production / main-source change — infochat-provider/src/main/**
    (including SemanticSearchTool, QueryAnchorTranslator, config) is
    untouched; the fixtures describe shipped behavior, they do not change it.
  - >-
    The eval harness / runner / scorer — M1-929, which consumes this set;
    this ticket delivers format + labels + validator only.
  - >-
    The baseline run and its measurement record — M1-930.
  - >-
    Fixing any retrieval gap the labels expose (price tool, text filter,
    temporal parse, tag/location granularity) — separate topics; this ticket
    builds the measuring stick, and price-class rows are labeled `none_expected`
    precisely to document the gap, not fix it.
  - >-
    Completing or migrating the `.bench/m1-717/` `pooling_pending` labels —
    wrong corpus (gitignored container), superseded as a label source; its
    LESSONS (pooling discipline, rationale, freeze) are adopted, its rows are
    not.
  - >-
    Languages outside the enabled set (D43: en, cs, es, ru, tr) — th/zh/ja/ar
    query forms exist in M1-717 data but `/lang` cannot declare them.
  - >-
    ANY docs/spec/** edit — no spec promise changes; the measured contract
    (security.md semanticSearch row) is cited, not amended.
acceptance:
  - "The golden set is committed at infochat-provider/src/test/resources/retrieval-eval/golden-set.jsonl with 49-56 records covering the classes at their floor counts: temporal-today (>=5), temporal-2h (>=5), temporal-24h (>=4), entity-location (>=5, e.g. Czech/Czechia forms), entity-project (>=5, e.g. qwen, monero/zcash as topic posts), price-shaped (>=5, all `none_expected: true` with a rationale naming the structural gap: price data lives in `price_snapshot`, read only by the deterministic /zcash //monero commands (AssetSnapshotReader.java:161), no chat tool in the closed seven-tool allowlist (docs/spec/security.md:328-334) reads it), topical/semantic (>=8), cross-lingual (>=12 = >=3 information needs x all four of cs/es/ru/tr) — probe: RetrievalGoldenSetTest.classCoverageMeetsFloors parses the file and asserts every floor (analysis P4/P6)."
  - "Every record carries: id, class, query, scope_lang (in {en,cs,es,ru,tr}), expected.retrieval.{relevant_uids[] | none_expected, rationale}, labeled_at, labeled_against.db_fingerprint, supersedes (null or the id of the record it replaces — corrections NEVER edit a record in place, the track-a freeze), optional trap_class[]; `expected` is an object of named check blocks with only `retrieval` populated, so the planned tag-canonicalization drift check is added later as a new block kind without reworking the format (binding user decision; analysis P14) — probe: RetrievalGoldenSetTest.schemaRejectsMalformedRecords."
  - "Labels are derived by POOLING at least two independent derivations per query (e.g. ready_at-window SQL + tag/entity SQL + adjudication of the top-16 the tool currently returns), each record's rationale states its derivation in one sentence, and expected sets are kept <= 8 uids where the class permits (the M1-717 one-sided-incompleteness and |E|>k-ceiling lessons, retrieval-separability.md §3.1-3.3) — probe: RetrievalGoldenSetTest.rationaleAndPoolingFieldsPresent asserts a non-empty rationale referencing its derivation on every record, and prints per-class label-set sizes."
  - "Cross-lingual rows are labeled against the ENGLISH information need (same need as a named English sibling query where one exists; the non-English query form exercises the D58 anchor translation leg at run time, so translated-leg quality is part of what the sibling ticket measures — docs/spec/security.md:329 anchoring clause; docs/spec/llm.md §Translation flow) — probe: RetrievalGoldenSetTest.xlingRowsCarryNeedAnchor asserts each xling record's notes name its English sibling id and that all four languages appear per information need."
  - "Empty-window rows are explicit: `none_expected: true` with a rationale stating why no corpus post satisfies the query (price class: the answer lives in price_snapshot; empty temporal windows: no posts in window at labeling time) — probe: RetrievalGoldenSetTest.noneExpectedRowsCarryRationale (analysis P12: these rows are scored by over-return in M1-929, never by recall)."
  - "FAILURE-MODE (validator catches corruption, analysis P4/P14): RetrievalGoldenSetTest feeds deliberately corrupted in-memory copies of records and asserts each rejection fires — a record missing rationale, an unknown class value, a class below its floor, a supersedes pointing at a absent id, a supersedes whose TARGET still validates (an in-place edit collision), an expected block not keyed by check name, and a duplicate id. A validator that passes any corrupted copy fails this item."
  - "scripts/eval-scopes-seed.sql is committed and idempotently seeds five fixed-UUID dm scopes with scope_preferences.language in {en,cs,es,ru,tr}, zero source_subscription rows and zero source_exclusion rows (world = all live non-excluded bootstrap sources, D59; the declared-language leg needs the scope_preferences row, D58(c)/D43) — probe: run the script twice against the test DB and `SELECT count(*) FROM scope_preferences WHERE scope_id IN (<the five documented UUIDs>)` returns 5 both times (analysis P10)."
  - "No production or spec surface moves — probe: `git diff --name-only` names exactly the files_scope paths (plus board/frontmatter); mvn verify from repo root is green (the validator test runs in the default suite as a plain JUnit test, no DB)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalGoldenSetTest.java
      — schema/coverage/rationale/freeze validation over the committed
      golden-set.jsonl (pure JUnit, no DB; the corrupted-copy failure-mode
      legs above).
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
  - docs/spec/llm.md §Translation flow
  - docs/spec/commands.md §Chat mode
decision_refs:
  - D19
  - D29
  - D43
  - D58
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

# M1-928: Golden-set fixtures for the fused-retrieval eval

## Context

Chat retrieval quality is unmeasured end-to-end: M1-916/917/927 landed with no
labeled query set to score against, and the motivating live failures (Czech
location queries, last-2h recency, price lookups — see the analysis document)
have never been quantified. This ticket authors the golden set: ~49-56 labeled
queries across temporal / entity / price-shaped / topical / cross-lingual
classes, each labeled with expected relevant post uids (or explicit
`none_expected`), committed beside the harness that will consume it (M1-929).
Shared analysis: `analysis_ref:`.

## Root cause

Not a code defect — a missing instrument (see the analysis document). What is
verified: no committed labeled set exists for the live corpus; the only prior
set (`.bench/m1-717/`) targets a different, gitignored container corpus, is
self-marked incomplete (`pooling_pending`, 23/41 rows —
docs/measurement/retrieval-separability.md:128-142), and covers none of the
failing classes. Label-quality hazards are known and documented: one-sided
incompleteness reads as low recall (§3.3: 12 of 25 hand-checked "false" hits
were relevant), and |expected| > k caps recall mechanically (§3.1).

## Pitfalls

Numbered per the analysis document; this ticket carries P4, P5, P7 (labeling
half), P10 (seeding half), P12, P14.

- P4: label incompleteness — single-angle labels under-call relevance;
  mitigation: pooled derivations + mandatory rationale + supersedes freeze.
- P5: mechanical recall ceiling — keep expected sets <= 8 where the class
  permits; the scorer (M1-929) reports capped and raw recall and label sizes.
- P7 (labeling half): temporal labels are DB-state-bound — every record
  carries `labeled_against.db_fingerprint`; corpus drift triggers an explicit
  `supersedes` relabel, never a silent mismatch.
- P10 (seeding half): labels are valid only for the eval scopes' D59 world —
  the seed script pins that world (bootstrap-only, zero exclusions, one scope
  per declared language).
- P12: `none_expected` rows must be explicit and rationale-backed; they are
  scored by over-return in M1-929, never by recall (recall over an empty set
  is vacuous).
- P14: the format must accept the future tag-drift check without rework —
  `expected` is an object of named check blocks; only `retrieval` is populated
  now.

## Approach

- **Files to touch** — `files_scope` (the fixture JSONL, the validator test,
  the idempotent eval-scope seed SQL). Labeling working data (derivation SQL,
  adjudication notes, DB dumps) stays under gitignored `.bench/retrieval-eval/`.
- **Steps in implementation order:**
  1. Seed the five eval scopes on the test DB (seed SQL) and record the world
     fingerprint (P10, P7).
  2. Author the set class by class: derive candidate uids by pooling
     (ready_at-window SQL + tag/entity SQL) then adjudicate the tool's current
     top-16 per query; write rationale + derivation per record (P4). Price
     rows: `none_expected` with the structural-gap rationale (P12).
  3. Cross-lingual rows: take 3+ information needs, write the query form in
     each of cs/es/ru/tr, label against the English need, name the sibling
     (P8/P9 are M1-929's to enforce at run time).
  4. Write `RetrievalGoldenSetTest` (schema, floors, rationale, freeze,
     corrupted-copy failure modes) and drive the file green.
- **Controls to preserve (§10):** no production path is touched; the default
  suite gains one pure-JUnit test; the seed SQL writes only the five eval
  scope rows on the test DB.
- **Pitfall→mitigation:** P4→step 2 pooling + rationale + supersedes;
  P5→step 2 label-size discipline (sizes printed by the validator); P7→
  fingerprint per record; P10→step 1 seed + world pin; P12→price/empty rows
  shape; P14→check-blocked `expected` schema enforced by the validator.

## Definition of done

The committed golden set passes `RetrievalGoldenSetTest` (floors, schema,
rationale, freeze, failure modes); the seed SQL is idempotent on the test DB;
every record carries its fingerprint, rationale, and derivation; the diff
touches nothing outside `files_scope`; `mvn verify` is green.

## Verification

- P4 → RetrievalGoldenSetTest.rationaleAndPoolingFieldsPresent — every record
  asserts a non-empty rationale; corrupted-copy legs reject a missing
  rationale and an in-place-edit collision (supersedes whose target still
  validates).
- P5 → RetrievalGoldenSetTest prints per-class label-set sizes; acceptance
  item 1's floor assertions (sizes <= 8 where the class permits is a
  authoring rule verified at review of the file).
- P7 → every record's `labeled_against.db_fingerprint` non-empty (validator).
- P10 → acceptance item 7's idempotency probe on the test DB.
- P12 → RetrievalGoldenSetTest.noneExpectedRowsCarryRationale.
- P14 → RetrievalGoldenSetTest.schemaRejectsMalformedRecords rejects an
  `expected` block not keyed by check name.
- acceptance items → the named validator methods; the final item via
  `git diff --name-only` and mvn verify.

## Out-of-scope

Named in `out_of_scope`: any production/main-source change; the harness
(M1-929) and baseline run (M1-930); fixing retrieval gaps the labels expose;
M1-717 label completion; non-enabled languages; any spec edit. No
pre-existing test is modified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-928-golden-set-fixtures.md
```
