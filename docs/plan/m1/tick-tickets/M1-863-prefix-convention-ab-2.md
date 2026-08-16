---
id: M1-863
title: "Run the prefix A/B; record the adopt-or-drop number"
status: pending
created: 2026-08-16
last_updated: 2026-08-16
flow: tick
reproduction: >-
  Probe (evidence ticket; no test can exist — the committed artifact is a
  measurement record, the M1-844 posture): grep -rn 'prefix'
  docs/measurement/*.md returns ONLY retrieval-separability.md's caveat
  and §6 bullet (:52-63, :337-342 — "for an unmeasured net gain. No
  production change is recommended; the observation stands recorded") —
  no A/B record exists, so the adopt-or-drop decision on the nomic task
  prefixes has no local number, and the M1-748 §6 posture ("no change",
  parked as unmeasured) can never be revisited or confirmed by evidence.
  Observed consequence: the deployment keeps running an unprefixed
  prefix-trained model on pure external-benchmark folklore in BOTH
  directions — neither adopting nor dropping is justified today (the
  gemma guard: no mechanism story, only a pre-registered local
  measurement).
analysis_ref: docs/plan/m1/tick-analysis/prefix-convention-ab.md
blocked_by: [M1-862]
files_scope:
  - docs/measurement/embedding-prefix-ab.md
  - docs/measurement/README.md
  - .bench/prefix-ab/
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production code, config, spec, or threshold change — adoption is
    a SEPARATE follow-up analysis/ticket chain opened only if prefixed
    wins on every pre-registered conjunct (D54 frozen contract;
    docs/spec/llm.md §Embedding pipeline "must not change … without a
    re-embed plan"; the chain re-embeds both corpora and recalibrates all
    SIX live thresholds — M1-748 record :35-42). This ticket records a
    decision; it never starts the chain (ask-before-creating-tickets).
  - >-
    RE-WRITING or editing docs/measurement/retrieval-separability.md —
    the M1-748 record is terminal evidence; the new record cites its §6
    prefix bullet and supplies the measurement it says is missing
    (corrections stay visible: the new record, not an edit, carries the
    update; git diff on the old record is empty).
  - >-
    RE-RUNNING or re-tuning the pre-registered criterion — the constants
    below are fixed in this file (and the analysis document) BEFORE any
    arm runs; any post-hoc constant edit voids the run and requires a
    fresh pre-registration. No free variables: denominators are frozen at
    M1-862's coverage commit; effects are absolute (0.02 / 0.05 / −1 /
    25%-of-frozen-set), never percentages of survivor sets that move
    their own trigger points.
  - >-
    The doc-store thresholds' distributions and the post↔post linking
    distribution — not measured by this A/B (no real
    CommandIntentIndexBuilder surface in the rig — the M1-748 §5.4 stub
    trap; no labelled same-story set — §5.2/§6); the record's
    does-not-settle section names them so the adoption chain cannot
    quietly assume them.
  - >-
    COMMITTING the .bench working data (gitignored, the campaign posture)
    or touching prod containers in any way (M1-862's rig only).
acceptance:
  - "PRE-REGISTRATION LOCK (P1/P11): this ticket file and the analysis document carry the win criterion and its constants, committed BEFORE any measurement cell runs — probe: git log --follow docs/plan/m1/tick-tickets/M1-863-prefix-convention-ab-2.md shows its creation commit predating every results file under .bench/prefix-ab/results/ (filesystem + git order), and the criterion constants in docs/measurement/embedding-prefix-ab.md byte-match this file's (grep the record for '0.05', '0.02', '-1', '25%')."
  - "The win criterion, pre-registered (all conjuncts must hold on the four-cell run over the M1-862-frozen labelled set; floor = 0.60 admit similarity (1 − 0.40 distance, production's current value), ranking = top-8, similarity = 1 − cosine distance): W1 admit precision at the 0.60 floor — (true hits / all hits with sim >= 0.60 in top-8, pooled over labelled queries) prefixed − raw >= +0.05; W2 best-match-vs-floor margin — median over labelled queries of (best true-hit similarity − 0.60), prefixed − raw >= +0.02; W3 anchored admit parity — for EACH of cs/es/ru/tr (campaign anchored fixtures + M1-717 anchored set, frozen n per language), prefixed admitted-count >= raw − 1; W4 ranking stability — the best true hit changes identity between arms in <= 25% of the frozen labelled queries where both arms admit at least one true hit. A run passes or fails as a whole; partial wins are recorded as failures with the numbers."
  - "NOT-A-WIN GUARD (P2): the record's criterion table carries the global similarity shift (median over all top-8 hits, prefixed − raw) as a DESCRIPTIVE row with weight zero and states why (thresholds recalibrate on adoption; a global shift is meaningless — M1-748 §4/§6) — probe: grep -n 'descriptive' docs/measurement/embedding-prefix-ab.md returns the row; no similarity-scale delta appears in any win conjunct."
  - "All four cells measured on the M1-862 rig over the frozen labelled set: raw-doc×raw-query (production incumbent), prefixed-doc×prefixed-query (candidate), AND the two cross cells (raw-doc×prefixed-query, prefixed-doc×raw-query) recorded as warning metrics quantifying the half-adoption mismatch (P9) — probe: grep -n 'cross' docs/measurement/embedding-prefix-ab.md returns both cross-cell tables; the record restates the M1-748 §2 designed-distractor caveat wherever pooled numbers appear (per-query metrics are the statistics; pooled is context — P3)."
  - "The anchored-query admit counts are recorded per language for BOTH arms against the 34/37 cross-space reference with its space labeled — probe: grep -n '34/37' docs/measurement/embedding-prefix-ab.md returns the reference row stating it is the PREFIXED pair on llama.cpp over the 9,224-post m1-717 corpus (floor-check.json native_en; floor_check.py:33), NOT the production incumbent's number — the incumbent's unprefixed count is this run's baseline cell, measured here for the first time."
  - "The record follows docs/measurement/README.md conventions: numbers final or absent; a what-these-numbers-do-NOT-settle section (doc-store distributions, linking distribution, the fused lexical+RRF result, non-enabled languages, one-sided M1-717 label incompleteness — arm deltas are the statistic); instrument disclosure (the M1-862 manifest pins, the runtime's observed prefix behavior from the discrimination probe, determinism result) — probe: grep -n 'do.*not.*settle\\|not settle' docs/measurement/embedding-prefix-ab.md returns the section."
  - "THE DECISION IS RECORDED EITHER WAY (P1, an A/B that can only return one answer is not an A/B): prefixed wins on ALL conjuncts → the record names the adoption follow-up as a SEPARATE analysis (scope: re-embed posts + doc corpus, all six thresholds, coordinated cutover) WITHOUT starting it; otherwise the idea is dropped with the measured numbers and the M1-748 §6 posture is resolved by evidence — probe: grep -n 'decision' docs/measurement/embedding-prefix-ab.md returns the paragraph naming the outcome and, on a win, the follow-up shape with no ticket filed by this work."
  - "docs/measurement/README.md gains a row for the new record in its table; docs/measurement/retrieval-separability.md is byte-unchanged and no docs/spec/** or docs/design/** file is in the diff (P13; the layering axiom — no spec row cites the record) — probe: git diff --name-only shows exactly the two docs/measurement paths; git diff docs/measurement/retrieval-separability.md is empty."
  - "mvn verify from repo root is green (evidence ticket; the build must not regress, engineering-rules §5 — the committed diff is docs-only, the inert-diff M1-616 posture, so this is the no-regression leg only)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
  notes:
    - >-
      Measurement run under gitignored .bench/prefix-ab/ on the M1-862 rig;
      the promoted record + README row are the only committed artifacts, so
      there is no JUnit surface to add. mvn verify covers the
      no-regression leg.
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Embedding pipeline
decision_refs:
  - D54
  - D19
  - D58
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

# M1-863: Run the prefix A/B; record the adopt-or-drop number

## Context

M1-862 delivers the instrument: one isolated Ollama container (the
deployment's own embedder class), the 11,789-post sha-pinned campaign
snapshot embedded raw AND `search_document:`-prefixed, the frozen labelled
query set (campaign fixtures en + anchored cs/es/ru/tr; the M1-717 anchored
set pre-anchored), anchor-once semantics, and the self-checks. This ticket
runs the four cells, scores them against the criterion pre-registered IN
THIS FILE (committed before any arm runs — the gemma guard made mechanical),
and writes the record that resolves the M1-748 §6 parked question
("an unmeasured net gain") with a local number in BOTH directions: either
prefixed wins on every conjunct and the adoption chain is named as a
separate follow-up, or the idea dies with the measurement attached.
Shared analysis: `analysis_ref:`.

## Root cause

Not a code defect — an unmeasured decision (analysis Root cause). What is
proven: the harness convention (prefixed) and the production convention
(raw) diverged silently for the whole M1-717/M1-748 measurement history
(record :52-63); every existing distribution is single-convention; the
deployment's incumbent numbers on the anchored set do not exist. What
remains unproven until this run: whether the prefix convention widens
true-match-vs-floor separation on THIS corpus and languages — and that is
the entire deliverable. The ticket is safe to start only because M1-862's
rig made the inputs checkable (frozen set, pinned runtime, discrimination
probe) and the criterion is fixed here before any result exists.

## Pitfalls

Numbered per the analysis document; this ticket carries P1, P2, P3, P9,
P11, P12, P13, P14.

- P1: external-benchmark mechanism trust — the model card says prefixes
  help; the criterion is fixed in this committed file and the record
  scores against it, nothing else. An A/B that can only return one answer
  is not an A/B: "drop" is a first-class outcome.
- P2: "similarities went up" false win — the global shift is recorded as a
  descriptive weight-zero row; no win conjunct reads an absolute scale.
- P3: pooled separability unpassable by construction — statistics are
  per-query (margins, precision, counts, churn); pooled numbers appear
  only as context with the M1-748 §2 designed-distractor caveat restated.
- P9: half-adoption mismatch — the two cross cells are mandatory warning
  metrics; recording only the matched pairs would hide what query-side-only
  adoption costs.
- P11: free variables — every constant is absolute (0.05, 0.02, −1, 25% of
  the coverage-frozen set) and frozen before data exists; post-hoc edits
  void the run.
- P12: adoption scope creep — a win NAMES the follow-up chain (re-embed
  both corpora, all six thresholds at record :35-42, cutover) and files
  nothing; no threshold, config, or code value moves in this ticket.
- P13: record integrity — new record file + README row; the M1-748 record
  stays byte-identical; conventions (final-or-absent, does-not-settle,
  corrections visible) apply; no spec row cites the record.
- P14: ordering — blocked_by M1-862; the run consumes the frozen labelled
  set and the rig's self-check results as inputs.

## Approach

- **Files to touch:** `files_scope` — `docs/measurement/embedding-prefix-ab.md`
  (new record), `docs/measurement/README.md` (table row), working data under
  `.bench/prefix-ab/` (gitignored).
- **Steps, in implementation order:**
  1. Re-verify the M1-862 rig's self-checks pass and read coverage.json:
     the frozen n per (set, language) and the drop counts become the
     record's denominators (P11's freeze is consumed, not recomputed).
  2. Confirm the lock: this ticket and the analysis document are committed
     (they precede the run by construction of the flow); no constant has
     been edited since creation (git log).
  3. Run the four cells over the frozen labelled set through the rig —
     matched pairs and cross pairs; every cell on the same pinned
     container; anchors served from M1-859's cache, byte-identical across
     cells.
  4. Score with `score.py`: W1–W4 per the constants above; the global
     shift as a descriptive row; per-language anchored admit counts for
     both arms beside the labeled 34/37 cross-space reference; ranking
     churn; cross-cell deltas.
  5. Write the record: instrument disclosure (manifest pins, the
     runtime's observed prefix behavior, determinism result), the
     criterion table with constants and measured values, the four cells'
     tables, the does-not-settle section, and the decision paragraph
     (win → name the separate adoption follow-up; lose → drop with the
     numbers). Add the README row.
- **Controls to preserve (§10):** no production path is rerouted; the
  controls are the measurement family's integrity rules — the lock's git
  order, the frozen denominators, append-only record history (the M1-748
  record untouched), README conventions, and mvn verify green.
- **Pitfall→mitigation:** P1→step 2 + acceptance item 1's lock probe +
  the either-way decision item; P2→acceptance item 3; P3→acceptance item
  4's caveat restatement; P9→acceptance item 4's cross-cell tables;
  P11→acceptance items 1-2 (constants byte-match; frozen denominators);
  P12→acceptance item 7 + out_of_scope; P13→acceptance items 6, 8;
  P14→frontmatter blocked_by + step 1.

## Definition of done

The record exists at `docs/measurement/embedding-prefix-ab.md` with: the
instrument disclosure and the rig's self-check results; the pre-registered
criterion table (constants byte-matching this file) with measured values
for W1–W4; all four cells' tables including both cross-cell warning
metrics; per-language anchored admit counts for both arms against the
labeled 34/37 reference; the global-shift descriptive row; the
what-these-numbers-do-not-settle section; and a decision paragraph that
either names (without starting) the separate adoption follow-up chain or
drops the prefix idea with the numbers. The README table carries the new
row; `retrieval-separability.md` and every spec/design file are
byte-unchanged; the lock's git order holds; mvn verify is green.

## Verification

- P1 (FAILURE-MODE) → acceptance item 7: the decision paragraph exists for
  BOTH outcomes — feed the losing case: if prefixed fails any conjunct,
  the record must still land with the numbers (a run whose only
  recordable outcome is a win is the gemma failure mode); probe the
  decision grep.
- P1/P11 → acceptance item 1: git-order lock probe; constants byte-match
  between record and this ticket — a tuned constant fails the byte-match.
- P2 → acceptance item 3: the descriptive-row grep; no win conjunct reads
  an absolute similarity scale (W1–W4 are deltas/counts/parity/stability
  only).
- P3 → acceptance item 4: the designed-distractor caveat grep wherever
  pooled numbers appear.
- P9 → acceptance item 4: both cross-cell tables present (grep 'cross').
- P11 → acceptance item 2: W3's parity is per-language absolute (≥ raw −
  1), W4's denominator is the coverage-frozen set — no survivor-set
  percentages.
- P12 → acceptance item 7: on a win, the follow-up is named with no ticket
  filed by this work (probe the record; the board shows no new ticket
  from this run).
- P13 → acceptance items 6, 8: does-not-settle grep; `git diff
  --name-only` shows exactly the two docs/measurement paths.
- P14 → frontmatter blocked_by [M1-862]; step 1 consumes M1-862's frozen
  set verbatim.
- acceptance item 9 → mvn verify from repo root green.

## Out-of-scope

Named in `out_of_scope`: any production/spec/config/threshold change and
the adoption chain itself (separate follow-up analysis if prefixed wins —
all six thresholds at record :35-42, both corpora, cutover; D54 frozen
contract); editing `retrieval-separability.md` (terminal evidence; the new
record carries the update per corrections-stay-visible); re-tuning or
re-running the pre-registered criterion (post-hoc edits void the run); the
doc-store and linking distributions (does-not-settle names them); committing
.bench working data; touching prod containers. If the run exposes a rig
defect (e.g., a coverage surprise), the fix goes back through M1-862 —
never a mid-run criterion adjustment.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-863-prefix-convention-ab-2.md
```
