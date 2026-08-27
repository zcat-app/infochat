---
id: M1-945
title: "Characterize the anchor-leg gap: probes + counterfactuals"
status: pending
created: 2026-08-27
last_updated: 2026-08-27
flow: tick
reproduction: >-
  Child of a 2+ decomposition (analysis
  docs/plan/m1/tick-analysis/anchor-leg-query-fidelity.md); /tick start
  converts the markers: write the tests, run them RED against the
  unmodified tree before any fix code, workflow §0. The wrong behavior
  (measurement ticket, the M1-944 posture — the missing instrument IS the
  defect): nothing in the committed corpus can answer WHY the anchored
  cross-lingual rows miss — the baseline record states the observation
  (xl-cyber-* 0.25 vs sibling top-cyber-b 0.75 over the identical
  expected set, docs/measurement/retrieval-eval-baseline.md:386-392,
  "recorded not fixed") and attributes nothing: no instrument computes
  sibling-window overlap or hit-rank distributions (the harness emits
  anchored_text + returned rows only, RetrievalEvalRunnerIT.java:431-441),
  and no instrument scores what a canonical phrasing or the raw source
  text would retrieve against the same frozen expected sets. Probes today:
  `grep -rn "counterfactual\|window overlap" docs/measurement/` → no
  hits; `ls docs/measurement/anchor-leg-characterization.md` → ENOENT.
  Tests `to-be-written`:
  AnchorLegCharacterizerTest#siblingPairDerivations — pure-function legs
  (window overlap, hit-rank distribution, per-pair raw-recall delta) over
  a fixture pair; RED today (the class does not exist);
  RetrievalEvalCharacterizationIT#threeArmsRideTheProductionTool —
  operator leg (see test_plan), RED today (the IT does not exist).
analysis_ref: docs/plan/m1/tick-analysis/anchor-leg-query-fidelity.md
blocked_by: []
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/AnchorLegCharacterizer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/AnchorLegCharacterizerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalCharacterizationIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalRunnerIT.java
  - docs/measurement/anchor-leg-characterization.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production change — git diff names no src/main path in any module
    (probe: git diff --name-only). The shipped anchor path
    (QueryAnchorTranslator, SemanticSearchTool, ChatAgent) is measured,
    not touched; a characterization that "fixes while measuring" has left
    scope (analysis P1).
  - >-
    ANY docs/spec/** edit — the measured contract (security.md
    semanticSearch row; llm.md §Translation flow) is cited, never
    amended. The fix-level amendment is the user's decision on this
    ticket's evidence, a later ticket.
  - >-
    The golden set — consumed read-only (same golden_set_sha256
    4dfed2d3…1154); widening it is M1-946, the re-baseline M1-947.
  - >-
    Generating canonical phrasings with an expansion-instructed translator
    prompt — D58 (d) forbids that behavior, so measuring it would score an
    illegal fix; arm B's phrasings are authored fixture strings (analysis
    P8).
  - >-
    English-side phrasing sensitivity (top-oss "open"→"OpenAI" lexical
    collision, top-crypto precision noise, duplicate-post collapsing) —
    different defect class, own queue (lane memory known-defects); analysis
    P10.
  - >-
    Booting provider/collector, backfill, or retention deploys against the
    test DB — harness boots are postgres + embedder + translator only
    (frozen-stack discipline; analysis P4).
acceptance:
  - "REPRODUCTION closed: AnchorLegCharacterizerTest.siblingPairDerivations passes — over a fixture (xling row, sibling row) pair with known uid lists, window-overlap count, hit-rank list, and raw-recall delta are computed exactly (pure function, CI-runnable, no DB)."
  - "Three-arm counterfactual, production-surface legs (analysis P6/P7): RetrievalEvalCharacterizationIT (operator invocation, same bring-up and eval-property gating as RetrievalEvalRunnerIT — CI stays green without the eval stack) executes, for every active cross-lingual golden row and its named active English sibling, THREE arms against the frozen stack and fingerprint-fenced DB: A the shipped anchor path (unmodified SemanticSearchTool dispatch under the row's language scope), B the authored canonical English phrasing for the need (committed fixture strings, one per need, dispatched on the en eval scope), C the raw source-language query dispatched on the en eval scope (the pre-M1-746 no-anchor world; en scope is the strict no-op, QueryAnchorTranslator.java:202-204). Every arm's rows come from the production tool dispatch — asserted by the tool's emission shape (uid/title/url/ready_at/similarity, similarity null on lexical-only rows)."
  - "Scope/cache hygiene (analysis P7): each (arm × language) dispatch uses its own eval scope so no unintended QueryTranslationCache entry is shared; arm C asserts ZERO translator-call delta (the existing harness counter); arm A's anchored texts are recorded per row."
  - "No-expansion failure-mode (analysis P8): a probe leg asserts every translator call the characterization issues carries a prompt byte-derived from the shipped PROMPT_TEMPLATE (the language-only prompt pinned by docs/spec/security.md §Prompt-injection defenses) — a leg that coaxes canonicalization out of the translator fails here."
  - "Fingerprint failure-mode (analysis P4/P9): the characterization refuses to score on label-fingerprint mismatch or inter-pass drift, reusing the runner's refusal posture (RetrievalEvalRunnerIT.java:203-239); a probe leg feeds a mismatched fingerprint and asserts the named refusal, never a number."
  - "Determinism (analysis P9, decision D19 per docs/spec/llm.md §Determinism boundary): the operator invocation runs twice; per-arm per-record uid lists and anchored texts are byte-identical across the two invocations, asserted by the RetrievalEvalCharacterizationIT double-invocation determinism leg (named in test_plan); every number the record states derives from run 1 and is restated in full."
  - "Committed record docs/measurement/anchor-leg-characterization.md exists (probe: ls docs/measurement/anchor-leg-characterization.md returns the file — the reproduction's ENOENT is closed) and restates EVERY number (durable-copy convention, baseline record :39-54): per (need × language × arm) raw recall + hit ranks, per-pair sibling window overlap, verified anchored texts (the committed golden-set query strings, not the brief's misquote — analysis P14), the translator/embedder/fingerprint pins, both run timestamps — and labels every table at n = 12 as SMOKE/descriptive, never a T1 result (analysis P5). The record's headline answers the three decision questions in prose: breadth (how many needs × languages show |Δ| ≥ 0.25), attribution (anchor text vs neighborhood density), counterfactual value (does B close cyber without losing crypto's +0.4; does C recover or collapse). The closing note surfaces — never answers — the eventual fix's queue-placement question (analysis P13)."
  - "The standard scored-run instrument is untouched in behavior: RetrievalEvalRunnerIT diffs are limited to sharing bring-up/helpers with the new IT (no change to manifest fields, assertions, or scored outputs) — probe: the M1-944 record's pins still hold and characterization runs write to a separate results directory."
  - "mvn verify from the repo root is green with the eval stack ABSENT (unit leg in the default suite; the characterization IT is eval-property-gated exactly like RetrievalEvalRunnerIT)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/AnchorLegCharacterizerTest.java
      — siblingPairDerivations (pure-function derivations) and the
      no-expansion prompt-corpus leg where CI-runnable.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalCharacterizationIT.java
      — operator legs: threeArmsRideTheProductionTool, scope-hygiene,
      fingerprint-refusal, double-invocation determinism; javadoc documents
      the operator invocation (the RetrievalEvalRunnerIT convention).
  modifies: []
  preserves:
    - >-
      All RetrievalEvalRunnerIT assertions and manifest fields
      (label-fingerprint refusal, pass identity, en-scope zero-translator
      calls, zero-fallback abort, golden_set pins) — byte-stable scored
      outputs (analysis Controls).
    - all tests currently green on main.
spec_refs:
  - docs/spec/llm.md §Translation flow
  - docs/spec/llm.md §Determinism boundary
  - docs/spec/security.md §Prompt-injection defenses
decision_refs:
  - D58
  - D19
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

# M1-945: Characterize the anchor-leg gap — sibling-pair probe + three-arm counterfactual record

## Context

The corrected baseline measured a general defect class: for non-English
scopes the D58 anchor leg is a system-chosen, unvalidated re-representation
of the user's need, and retrieval serves the anchor's embedding
neighborhood (xl-cyber 0.25 vs sibling 0.75 over identical expected sets;
xl-ai 0.688 vs 0.75; xl-crypto 0.4/0.8/0.8/0.4 vs 0.4 — same mechanism,
harm −0.5..+0.4; `docs/measurement/retrieval-eval-baseline.md:386-392`).
The record observes and does not attribute. No fix level can be chosen on
evidence today: canonicalization contradicts D58 (d), a fidelity gate or
fusion adds a fallback cause the `semanticSearch` row enumeratively
exhausts, and the multilingual embedder is falsified prior art (M1-717).
This ticket builds the missing instrument and the decision-grade (at the
class's smoke size: decision-INFORMING, explicitly not decision-GATING,
G1) evidence; its record is also the DECISION INPUT for the M1-946/947
start gate (the user's ruling on the T1/G1 reading fork, analysis P15).
Full context: `analysis_ref:` above.

## Root cause

The anchored string is a correct literal translation used verbatim
(`QueryAnchorTranslator.java:260-261,313`) under a prompt that forbids
normalization (:141-143), with only blank/length acceptance checks
(:279-298) — the phrasing the retrieval arms see is the translator's, not
the user's and not the need's canonical form, and the English-centric
nomic space is phrasing-sensitive (the sibling's different phrasing of the
same need retrieves a window hitting 0.75 of the adjudicated set). What is
NOT proven — breadth, attribution, counterfactual value — is exactly what
this ticket measures; the ticket is safe to start because it changes no
behavior (test-scope + record only).

## Pitfalls

Carried from the analysis, numbered identically (the full trap + rule
citations live there): **P1** no fix rides this ticket; **P4**
frozen-stack fence (postgres + embedder + translator only); **P5** every
number labeled smoke/descriptive, never T1; **P6** arms ride the
production tool, never an ad-hoc probe; **P7** eval-scope/cache hygiene,
arm C on the en no-op path; **P8** canonical phrasings are authored
fixtures, never translator output; **P9** two-invocation determinism, full
restatement in the committed record; **P10** English-side phrasing defects
excluded; **P13** no fix-placement assumptions; **P14** verified strings
only (sibling cyber query is "cybersecurity threats and vulnerabilities",
golden-set.jsonl:57 — not the brief's/record's "latest cybersecurity
news").

## Approach

- **Files to touch:** the four test-scope files + the new measurement
  record above; `RetrievalEvalRunnerIT` only for bring-up/helper sharing
  (its assertions and outputs byte-stable).
- **Steps, in order:** (1) `AnchorLegCharacterizer` pure derivations +
  unit test, RED first; (2) the characterization IT (arms, hygiene,
  refusal, determinism legs), operator-invoked like the runner; (3) run
  the operator invocation twice on the frozen stack; (4) write the
  committed record restating every number; (5) `mvn verify` green.
- **Controls to preserve:** the runner's fences (fingerprint refusal, pass
  identity, en-zero-translator-calls, zero-fallback abort) run UNDER the
  characterization, never around it; the golden set consumed read-only;
  the record states run-1 numbers with the determinism leg alongside
  (M1-944 conventions).
- **Pitfall→mitigation:** P4/P9→fence + double-run legs; P5→record
  labels; P6→emission-shape assertion; P7→scope matrix + translator
  counter; P8→prompt-corpus probe; P14→fixture strings from the committed
  golden set.

## Definition of done

Every YAML `acceptance:` item above, each verified by its named test,
probe, or operator leg — including the two failure-mode items
(no-expansion prompt corpus; fingerprint refusal) and the mvn-verify-green
item with the eval stack absent.

## Verification

- P1 → git diff --name-only names no src/main and no docs/spec path.
- P4 → RetrievalEvalCharacterizationIT failure-mode fingerprint-refusal
  leg: it feeds a mismatched label fingerprint (the hostile input) and
  asserts the run refuses — the named refusal, never a score.
- P5 → record audit: every table in
  docs/measurement/anchor-leg-characterization.md carries its
  smoke/descriptive label; no table is phrased as a T1 result.
- P6 → threeArmsRideTheProductionTool emission-shape assertion.
- P7 → scope-hygiene leg + arm-C zero translator delta.
- P8 → prompt-corpus probe leg over every translator call issued.
- P9 → double-invocation byte-identity leg; full restatement check.
- P10 → English-side phrasing defects stay excluded: probe
  `grep -n "top-oss" docs/measurement/anchor-leg-characterization.md`
  returns no scored row — the English-side known-defects queue (top-oss
  "open"→"OpenAI" collision, top-crypto precision noise, duplicate
  collapsing) is not measured here; the out_of_scope block holds.
- P13 → queue ordering surfaced, never assumed: the record's closing note
  asks where an eventual fix lands (before/within/after the campaign; the
  M1-937/938 owner-run-delta interaction) and answers nothing — probe:
  `grep -n "placement" docs/measurement/anchor-leg-characterization.md`
  returns the question; this ticket's own text makes no must-land-before/
  after claim (reviewer diff check).
- P14 → fixture strings byte-equal to golden-set.jsonl query strings.
- acceptance items 1..9 → the named tests/probes above.

## Out-of-scope

Prose in the YAML `out_of_scope` block; emphasized: no production change,
no spec edit, no golden-set change, no fix-level commitment, no
English-side phrasing work, no provider/collector boot against the test
DB. `RetrievalEvalRunnerIT` behavior changes (assertions, manifest,
scored outputs) would break the campaign's gating reference and are
forbidden here — sharing helpers is fine, rerouting the instrument is not.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-945-anchor-fidelity-1.md
```
