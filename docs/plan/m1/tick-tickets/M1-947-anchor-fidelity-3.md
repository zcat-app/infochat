---
id: M1-947
title: "Re-baseline the retrieval eval on the widened set"
status: pending
created: 2026-08-27
last_updated: 2026-08-27
flow: tick
reproduction: >-
  Probe (measurement ticket, the M1-944 posture — the missing widened
  reading IS the wrong behavior): `grep -c '^## Results'
  docs/measurement/retrieval-eval-baseline.md` returns 2 (verified
  2026-08-27: sections at :161 and :268) and the gating reference's
  cross-lingual row is n = 12, smoke by rule G1 (record :323-325, :340) —
  so the recorded anchor-leg defect class (xl-cyber 0.25 vs sibling 0.75,
  :386-392) has no decision-grade reading to gate a future fix's owner-run
  delta against, and the campaign gating note (:485-496) points every
  retrieval delta at a golden set whose sha the widening (M1-946) is about
  to change. Intended entry: the operator invocation documented in
  RetrievalEvalRunnerIT against the frozen stack on the M1-946 set,
  appending a new dated section to docs/measurement/
  retrieval-eval-baseline.md.
analysis_ref: docs/plan/m1/tick-analysis/anchor-leg-query-fidelity.md
blocked_by: [M1-946]
files_scope:
  - docs/measurement/retrieval-eval-baseline.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production, spec, design, config, or test change — doc-only
    committed diff plus an operator harness run; mvn verify N/A per the
    inert-diff rule (the M1-930/M1-944 posture).
  - >-
    Amending the pre-registered rules T1/G1/N1/D1 — they are UNCHANGED;
    this run re-reads the instrument on an extended set, exactly the D1
    extension path M1-942/944 walked once before. Any rule edit is
    refitting pre-registration and is forbidden.
  - >-
    Editing the two landed Results sections — the record is append-only;
    the sibling-query wording slip in the 2026-08-27 re-baseline section
    (:389-391 quotes "latest cybersecurity news"; the committed golden
    set carries "cybersecurity threats and vulnerabilities",
    golden-set.jsonl:57) is corrected by a NOTE in the new section, never
    by rewriting history (analysis P14).
  - >-
    Fixing the anchor-leg defect — the fix level is the user's decision on
    M1-945's evidence; this ticket produces the gate it will be measured
    against, nothing more (analysis P1/P2).
  - >-
    Re-labeling or extending the golden set — M1-946's output is consumed
    read-only; a NEW label defect found by the run goes back through a
    supersedes correction ticket and the re-baseline re-runs.
  - >-
    PROD containers — the run targets the test DB and the test stack's
    endpoints only (postgres + embedder + translator; no
    provider/collector, or ingest drifts the fingerprint and the run
    refuses — analysis P4).
acceptance:
  - "A NEW dated '## Results' section exists (third such section; disambiguating title naming the widened set) produced by the documented operator invocation run TWICE against the frozen stack, with per-query uid lists byte-identical across the two invocations and the 16 anchored texts byte-identical across both boots — the determinism leg in the record :311-318's shape (decision D19 per docs/spec/llm.md §Determinism boundary); probe: diff of the two runs' outputs under .bench/retrieval-eval/results/ is empty (operator-local; both run timestamps restated in the section)."
  - "Pins: the new golden_set_sha256 (M1-946's committed file, asserted by both runs' manifests), golden_set_active_records = 63 / retired = 18, and the DB fingerprint byte-identical to the frozen one (ready=5214;max_ready_at=2026-08-24 16:00:57.001472+00;uid_sha256=06ed0de1…9727) — a drifted DB aborts scoring via the runner's refusal and no number is quoted from a refusing run."
  - "The per-class table marks cross-lingual (n = 16) decision-grade by rule G1 — the FIRST reading where that is true — with the T1 sign test now available on that class (floor 6 one-directional discordant queries, the record's own :396-398 sentence restated for the new class); every other sub-16 class stays smoke-marked exactly as before (analysis P2/P5) — probe: `grep -n 'decision-grade' docs/measurement/retrieval-eval-baseline.md` returns the new section's cross-lingual row and no sub-16 claim anywhere (the smoke-flag audit)."
  - "Movement vs the 2026-08-27 re-baseline section is stated as DESCRIPTIVE (cross-set delta over a changed golden_set_sha256, never a gated comparison — the record's own :350-356 discipline), decomposed into: unchanged-label rows posting byte-identical numbers (the determinism proof) vs the four new xling rows' first readings (new observations, no old reading) — probe: `grep -n 'DESCRIPTIVE' docs/measurement/retrieval-eval-baseline.md` returns the new section's movement paragraph alongside the :350 one, and no line of the new paragraph phrases a cross-set delta as gated."
  - "The campaign gating note is updated in the new section: retrieval-delta landings after this reading gate against THIS section (same new sha, same frozen fingerprint, rule T1), and the section names the anchor-leg fix as the first expected consumer (analysis P13 — placement of that fix relative to M1-937/938's owner-run deltas stays the user's call, stated not assumed) — probe: `grep -n 'gating reference' docs/measurement/retrieval-eval-baseline.md` returns the new section's note naming THIS section."
  - "CORRECTIONS-VISIBLE note: a one-line note in the new section corrects the 2026-08-27 section's sibling-query misquote (:389-391) with the verified golden-set string — corrections stay visible, history is not rewritten (analysis P14) — probe: `grep -n 'Correction:' docs/measurement/retrieval-eval-baseline.md` returns the note quoting golden-set.jsonl:57's string ('cybersecurity threats and vulnerabilities'), and git diff shows the landed sections' lines untouched (append-only)."
  - "The section's xling numbers are cross-checked against M1-945's characterization record where both measure the same 12 rows (arm A): any divergence beyond the record's own determinism posture is investigated and named (rule-H1-style honesty note), never silently absorbed — probe: `grep -n 'anchor-leg-characterization' docs/measurement/retrieval-eval-baseline.md` returns the cross-check reference in the new section."
  - "Doc-only committed diff: git diff --name-only names exactly docs/measurement/retrieval-eval-baseline.md plus board/frontmatter regen; no harness, spec, or production file changes (probe: git diff --name-only)."
test_plan:
  adds: []
  modifies: []
  preserves:
    - >-
      The two landed Results sections byte-identical (append-only; probe:
      git diff shows added lines only in the new section's range).
    - all tests currently green on main (no test diff at all).
spec_refs:
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

# M1-947: Re-baseline the retrieval eval on the widened set

## Context

M1-946 widens the cross-lingual golden slice to n = 16; until a dated
reading of THAT set exists, the corrected 2026-08-27 section remains the
gating reference and the anchor-leg defect class stays ungated (G1: the
old reading's cross-lingual row is n = 12 smoke). This ticket produces the
new gating reference — the M1-944 posture exactly, one link later in the
same chain. It runs only if M1-946 ran (start-gate fork (a), analysis
P15; under forks (b)/(c) it is aborted with M1-946 via `/tick abort`).
Full context: `analysis_ref:`.

## Root cause

Instrument sequencing, not a code defect: a golden-set extension changes
`golden_set_sha256`, and the record's campaign gating note pins deltas to
a named sha + fingerprint (record :301, :485-496). Verified: the harness
manifest asserts the sha per run (RetrievalEvalRunnerIT.java:425) and
refuses on fingerprint drift (:203-239) — the reading simply has to be run
and recorded before any delta may cite it.

## Pitfalls

From the analysis, numbered identically: **P2** decision-grade marking is
a RULE statement (G1 at n = 16), not a boast — the sign test's own floor
(6 one-directional discordant) is restated; **P4** the run rides the
frozen stack or refuses — and it must happen before the freeze lifts;
**P5** cross-set movement is descriptive, decomposed label-artifact vs new
rows, byte-identical rows as the determinism proof (the :350-356
discipline); **P9** two invocations, byte-identity, full restatement;
**P12** rules T1/G1/N1/D1 never re-registered; new sha pinned; campaign
gating note updated; **P13** the anchor-fix placement (vs M1-937/938
owner-run deltas) is surfaced as the user's call; **P14** the
corrections-visible note for the sibling-query misquote.

## Approach

- **Files to touch:** the measurement record only (append).
- **Steps, in order:** (1) verify the frozen fingerprint pre-run (the
  M1-944 P7 fence — read it off the quiesced stack); (2) run the
  documented operator invocation twice (postgres + embedder +
  translator); (3) append the dated section with pins, table,
  determinism leg, descriptive movement decomposition, gating-note
  update, corrections note; (4) cross-check arm-A agreement with
  M1-945's record; (5) doc-only diff.
- **Controls to preserve:** the record's append-only shape; the runner's
  asserts run as-is (the ticket changes no harness line); the two landed
  sections stay byte-identical.
- **Pitfall→mitigation:** P4→pre-run fence + runner refusal; P5/P9→
  double-run + byte-identity + restatement; P12→rules-unchanged sentence
  + new sha pin; P13→gating-note wording names the decision, makes none;
  P14→the note.

## Definition of done

Every YAML `acceptance:` item, each verified by its named probe, run leg,
or git-diff check — the run legs (determinism, refusal posture) are the
harness's own, restated in the section.

## Verification

- P2 → the section's cross-lingual row cites G1/T1 with the floor, and
  grep finds no decision-grade claim on any sub-16 class.
- P4 → pre-run fence quote (fingerprint off the quiesced stack) + both
  manifests' label_fingerprint_match=true; the failure-mode posture is
  the runner's own — a drifted fingerprint aborts scoring (the run
  refuses, RetrievalEvalRunnerIT.java:203-239) and the section contains
  no number quoted from a refusing run.
- P5 → the movement paragraph names the byte-identical rows and the four
  first-read rows separately; no cross-set delta is phrased as gated.
- P9 → the determinism paragraph states 0-of-126 (pass, record) rows
  differing (63 × 2) and the 16 anchored texts byte-identical.
- P12 → the rules-unchanged sentence + the new sha; the campaign gating
  note names this section as the reference.
- P13 → the gating note states the fix-placement question without
  choosing.
- P14 → the corrections-visible note present.
- acceptance items 1..8 → the probes above.

## Out-of-scope

As the YAML block: no production/spec/test change, no rule edits, no
history rewrites, no fix, no re-labeling, no PROD containers. A doc-only
diff that names any other file is scope drift.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-947-anchor-fidelity-3.md
```
