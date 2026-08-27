---
id: M1-944
title: "Re-baseline the retrieval eval on the corrected set"
status: done
created: 2026-08-27
last_updated: 2026-08-27
flow: tick
reproduction: >-
  Probe (measurement ticket, the M1-930 posture — the missing corrected
  reading IS the wrong behavior): `grep -c '^## Results' docs/measurement/
  retrieval-eval-baseline.md` returns 1 (verified 2026-08-27: only the
  "## Results — 2026-08-27" section exists, :161) — the record's standing
  reading was produced against the DEFECTIVE answer key: its topical
  0.153 (:209) conflates a label artifact (six snapshot classes whose
  relevant populations were 2–3× the labeled newest-8 slice) with real
  retrieval weakness, its entity-location denominators include
  Kaspersky-attribution and venue-keyword rows, and its overall n = 51
  predates the extension. Observed consequence: the RAG campaign
  (M1-931..941, pending) has no decision-grade corrected reading to gate
  owner-run deltas against — rule D1's explicit relabel path is the only
  legal way to produce one, and nothing has walked it. Intended entry:
  the operator command documented in RetrievalEvalRunnerIT against the
  frozen stack, appending the new dated section to
  docs/measurement/retrieval-eval-baseline.md.
analysis_ref: docs/plan/m1/tick-analysis/golden-set-corrections.md
blocked_by: [M1-942, M1-943]
files_scope:
  - docs/measurement/retrieval-eval-baseline.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production, spec, design, config, or test change — this ticket
    appends a measurement record and runs an operator harness; doc-only
    committed diff, mvn verify N/A per the inert-diff rule (the M1-930
    posture).
  - >-
    Fixing what the corrected numbers show — the top-oss "open"→"OpenAI"
    lexical collision, top-crypto precision noise, duplicate-post
    collapsing, threshold derivation: recorded as observations in the
    record (corrections stay visible), each is its own gated ticket.
  - >-
    Re-labeling or extending the golden set — M1-942's frozen output is
    consumed read-only; if the run exposes a NEW label defect, it goes
    back to a supersedes correction ticket, never an in-place edit, and
    the re-baseline re-runs.
  - >-
    Amending the pre-registered rules T1/G1/N1/D1 — they are UNCHANGED by
    the binding decisions; this run walks D1's existing relabel path. Any
    rule edit would be refitting pre-registration and is forbidden.
  - >-
    CI-gating automation for the harness — operator-run discipline
    unchanged; the record is the durable copy of every number.
  - >-
    PROD containers — the run targets the test DB and the test stack's
    configured endpoints only (postgres + embedder + translator; no
    provider/collector, or ingest drifts the fingerprint and the run
    refuses to score).
acceptance:
  - "Pre-run fence (analysis P7): the frozen DB is verified intact BEFORE the run — world fingerprint exactly ready=5214;max_ready_at=2026-08-24 16:00:57.001472+00;uid_sha256=06ed0de1…927 — and the harness's label_fingerprint_match is true (asserted by the runner, restated in the record's pins); FAILURE-MODE: a drifted DB must ABORT scoring with the runner's named refusal, recorded as refused — a run scored across drift fails this item (the M1-933/M1-934 corpus-mutation caution is stated in the record)."
  - "The operator run executes the corrected set twice on the frozen stack and the determinism leg holds — probe: the record names both run timestamps and states per-query uid lists byte-identical across the two invocations (D19, llm.md §Determinism boundary); the manifest of each invocation carries golden_set_sha256 equal to sha256sum of the committed golden-set.jsonl and the active-record count 59 with retired records excluded (M1-943's keys)."
  - "Append-only discipline (analysis P10): the record gains a NEW dated results section; the 2026-08-27 section and the pre-registered rules T1/G1/N1/D1 are byte-identical — probe: git diff over docs/measurement/retrieval-eval-baseline.md shows pure additions after the existing content."
  - "The new section carries the full per-class table at the new n (overall 59; topical 16; every other class unchanged) with capped AND raw recall, MRR, over-return pair for none_expected rows, lexical-only share, and smoke marks per rule G1 at the NEW sizes — topical (n = 16) is marked decision-grade (the first class to clear the G1 floor); every other class row is marked smoke — probe: grep the section for the smoke marks and the topical promotion; numbers final or absent, never estimated (measurement-README convention)."
  - "The pins block of the new section gains the label-set pin (analysis P6/P8): golden_set_sha256, the supersedes-pair count (18), and the active/retired record counts, alongside the existing pins (repo commit, DB fingerprint, config, endpoints, fallback/en-call assertions, anchor-cache state, run timestamps) — probe: each pin key resolves with a value in the new section."
  - "Corrections stay visible (analysis P2/P10): the new section enumerates all 18 supersedes corrections with their rationale shape (the four entity-location fixes with approved drops/tightened keeps, the six topical relabels, the eight xling cascade rows), citing the adjudication report as operator-local provenance with the dispositions restated in the record — probe: the corrections enumeration counts 18 (grep/count)."
  - "Power and comparability are restated, not copied (analysis P8): the what-these-numbers-do-not-settle section is restated at n = 59 (overall CI roughly ±0.13; per-class rankings still unresolvable below the floor), and the section states EXPLICITLY that deltas versus the 2026-08-27 reading are DESCRIPTIVE (the label set changed; rule T1 gates only same-golden-set comparisons) with the artifact-vs-weakness decomposition (top-ai/cyber/ml/med/bio movement = label artifact; top-crypto/top-oss/top-robot = real retrieval weakness, the oss lexical collision recorded as an observation, not fixed) — probes: grep 'do not settle' returns the restated section; the descriptive-not-gated statement is present."
  - "The record names itself the gating reference for the RAG campaign's owner-run deltas (M1-931..941 reference the baseline in their acceptance items) and states the queue discipline: retrieval-delta landings gate against THIS corrected reading; non-retrieval tickets (M1-931 getPrice, M1-933 retention, M1-939 language pinning) proceed in parallel — probe: the section text carries the note."
  - "Diff fence (analysis P12): git diff --name-only names exactly docs/measurement/retrieval-eval-baseline.md plus own frontmatter/board regen; no prod URL or live-user data beyond post uids appears — probe: git diff --name-only output plus a grep over the new section for URLs."
test_plan:
  adds: []
  modifies:
    - >-
      docs/measurement/retrieval-eval-baseline.md (authorized: a new dated
      results section is APPENDED; no existing line changes — the 2026-08-27
      reading and the rules block stay byte-identical).
  preserves:
    - all tests currently green on main
  notes:
    - >-
      Doc-only committed deliverable (the M1-930/M1-860 measurement-ticket
      shape); the runnable verification is the harness invocation plus the
      probes above. mvn verify is N/A per the inert-diff rule. The run
      requires the frozen stack bring-up (postgres + embedder + translator
      ONLY — the M1-930 clarity_check bring-up note; a provider/collector
      process would ingest and drift the fingerprint).
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
  - D58
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews:
  - round: 1
    date: 2026-08-27
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS: PASS; SECURITY: PASS; TEST-ADEQUACY: NOT-APPLICABLE; MAINTAINABILITY: PASS; SCOPE: PASS"
    diff_stats: "3 files, +256/-9 (record +236 pure append, ticket frontmatter +15/-2, board +12/-9)"
    notes: "5 falsification candidates dropped with citations (mean label size 8.25 = 487/59 integer consistency; run-timestamp pin vs dir-name convention matches the first reading's own pins; loopback endpoints vs 'no prod URL' wording; writeArtifacts-before-fail-fast mooted by both runs green with label_fingerprint_match=true; two same-date Results sections disambiguated by title). 1 RECOMMENDED-NEW-TICKET recorded under Review observations (xl-cyber anchored-window gap, TOUCHED-BY-THIS-DIFF: no). Verdict: .scratch/tick-review-M1-944-r1.txt"
overrides: []
aborted_attempts: []
reopens: []
clarity_check: >-
  start 2026-08-27: lint 0 findings; blocked_by M1-942/M1-943 both done.
  Citations re-verified: record has exactly one results section
  (grep '^## Results' = 1, :161; topical 0.153 :209); golden set end
  state 77 lines / 18 supersedes / 18 retired = 59 active, sha256
  4dfed2d3df02f48b6b0369c8f0323d871d874c25d97769bbc8d445e6ba8e1154;
  M1-943 runner asserts label_fingerprint_match and refuses on drift
  (:207-211), manifest pins golden_set_sha256 + active/retired counts
  (:424-427); operator invocation in RetrievalEvalRunnerIT javadoc
  (:43-52). Frozen fingerprint + bring-up surface from
  .agents/memory-local/retrieval-eval-lane-and-rag-plan.md match the
  ticket's pins. Doc-only committed diff: no test parses the record
  (grep verified), mvn verify N/A per the inert-diff rule. No
  ambiguity found.
escalation_reason:
---

# M1-944: Re-baseline the retrieval eval on the corrected set

## Context

The corrected + extended golden set (M1-942) and the correction-aware
harness (M1-943) produce a measurement the record cannot currently express:
`docs/measurement/retrieval-eval-baseline.md` holds one reading, taken
2026-08-27 against the defective answer key — topical 0.153 conflates label
artifact with retrieval weakness, and the entity-location denominators
carry adjudicated-wrong rows. Every future retrieval change in the RAG
campaign (M1-931..941, pending) gates its owner-run deltas against this
record. This ticket walks rule D1's explicit relabel path: run the harness
against the corrected set on the SAME frozen fingerprint and append the new
dated section. Shared analysis: `analysis_ref:`. Blocked on both siblings —
the run consumes M1-942's set via M1-943's loader.

## Root cause

Not a code defect — the second reading of an instrument whose answer key
was corrected (analysis document). Verified: the record has exactly one
results section (grep '^## Results' → 1, :161) produced against the
51-record defective set; the harness changes that make the corrected set
runnable (retired-skip, identity pin) are M1-943's; the labels themselves
are M1-942's. What must not happen: re-registering rules (T1/G1/N1/D1 are
unchanged and pre-registered — re-stating them with knowledge of the new
numbers would be refitting), editing the standing reading (corrections stay
visible), or scoring across fingerprint drift (the runner refuses; that
refusal is correct).

## Pitfalls

Numbered per the analysis document; this ticket carries P7 (run half),
P8, P10, P12, P13.

- P7: fingerprint discipline — verify the frozen DB intact before the run;
  the stack brings up postgres + embedder + translator only (a collector
  or provider ingests and drifts the fingerprint); M1-933/M1-934
  deployments must not have touched the eval DB (llm.md §Determinism
  boundary grounds DB-state-pinned comparability).
- P8: power mis-statement — restate the do-not-settle caveats at the new
  n (topical n = 16 clears G1 and becomes the first decision-grade class;
  overall CI ~±0.13); the corrected-vs-old delta is DESCRIPTIVE, never a
  T1 result (T1 gates same-golden-set comparisons only).
- P10: append discipline — new dated section only; the 2026-08-27 reading
  and the rules block stay byte-identical; corrections enumerated, visible.
- P12: operator/verifiability split — every number this ticket quotes is
  RESTATED in the committed record (the operator-local runs are
  provenance); no prod containers; no live-user data beyond post uids.
- P13: conflation carried forward — the record decomposes the topical
  movement (label artifact vs real retrieval weakness) instead of
  reporting it as a regression/improvement; the entity-location
  denominator change is noted, not interpreted.

## Approach

- **Files to touch** — `files_scope`: the one record file (plus own
  frontmatter/board regen; operator artifacts under gitignored
  `.bench/retrieval-eval/results/`).
- **Steps in implementation order:**
  1. Pre-run fence (P7): verify the frozen fingerprint (the M1-943 runner
     asserts label_fingerprint_match and refuses on drift — treat any
     refusal as a stop, never an obstacle to route around).
  2. Operator run: bring up the frozen stack, execute the harness twice
     (two invocations, determinism leg); capture manifests, queries.jsonl,
     scores.json.
  3. Author the appended section (P10/P8/P13): pins including the
     label-set pin (golden_set_sha256, 18 supersedes pairs, 59 active and
     18 retired records);
     per-class table at the new n with smoke/decision-grade marks; the 18
     corrections enumerated; the restated do-not-settle section with the
     descriptive-not-gated statement and the artifact-vs-weakness
     decomposition; the campaign gating note with the parallel-ticket and
     fingerprint cautions.
  4. Commit doc-only (append); mvn verify N/A per the inert-diff rule.
- **Controls to preserve (§10):** nothing is rerouted; the harness's
  self-checks gate the run (a failed self-check means no numbers, and the
  record never reports values a refusing harness produced — the M1-930
  rule); the golden set is consumed read-only; the rules block is
  untouched.
- **Pitfall→mitigation:** P7→step 1 fence + refusal-as-stop; P8→step 3
  restated caveats + descriptive statement; P10→step 3 append-only shape
  (git-diff probe); P12→step 3 restated numbers + diff fence; P13→step 3
  decomposition note.

## Definition of done

The record carries a new dated section produced by two clean invocations
on the verified frozen fingerprint; the manifest hash matches the
committed golden set; the pins (including the label-set pin) resolve; the
per-class table is complete with correct smoke/decision-grade marks; the
18 corrections are enumerated; the restated caveats, the
descriptive-not-gated statement, and the decomposition note are present;
the 2026-08-27 section and the rules are byte-identical; the diff touches
nothing but the record (and board/frontmatter).

## Verification

- P7 → acceptance item 1: the fingerprint verified before the run and
  label_fingerprint_match true; FAILURE-MODE: a drifted DB aborts with the
  runner's named refusal and is recorded as refused — scoring across drift
  fails the item.
- P8 → acceptance item 7: grep 'do not settle' returns the section restated
  at n = 59; the descriptive-not-gated statement present; a section that
  copies the old caveat text or presents the label delta as a gated result
  fails review.
- P10 → acceptance item 3: git diff shows pure additions; the corrections
  enumeration counts 18.
- P12 → acceptance items 2, 9: both run timestamps + byte-identity stated;
  the manifest hash equals sha256sum of the committed file; git
  diff --name-only names only the record; no prod URL in the new section.
- P13 → acceptance item 7: the artifact-vs-weakness decomposition present,
  the oss lexical collision recorded as an observation (not claimed fixed).
- acceptance items → the named greps/probes per item; the run itself is
  the operator invocation documented in RetrievalEvalRunnerIT.

## Out-of-scope

Named in `out_of_scope`: any production/spec/config/test change (doc-only
committed diff); fixing what the numbers show (each observation its own
ticket); re-labeling beyond the frozen M1-942 output (a NEW label defect
goes back to a supersedes correction ticket and the re-baseline re-runs);
amending T1/G1/N1/D1; CI gating; prod containers. The record file IS
modified — authorized in `test_plan.modifies`: a new dated section is
appended and no existing line changes (engineering-rules §8; the
append-only convention the corrections section itself pre-registered).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-944-retrieval-eval-rebaseline.md
```

## Review observations

- r1 RECOMMENDED-NEW-TICKET (recorded, not filed): the re-baseline
  quantified a retrieval gap the record only observes (record :386-392) —
  the anchored cross-lingual cyber queries (xl-cyber-*-b, mean raw recall
  0.25) retrieve a materially worse window than the English sibling they
  inherit labels from (top-cyber-b, 0.75 per-record) over the identical
  expected set; the anchored text "cybersecurity news" surfaces a
  different window than the sibling's "latest cybersecurity news".
  TOUCHED-BY-THIS-DIFF: no — the diff measured a pre-existing retrieval
  path (production code byte-identical between readings); the measurement
  is new, the behavior is not. Candidate: a RAG-campaign-follow-up ticket
  on the D58 anchor leg for that information need (parity with the English
  sibling, or a recorded cause).
