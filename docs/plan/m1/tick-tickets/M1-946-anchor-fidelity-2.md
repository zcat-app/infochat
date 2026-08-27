---
id: M1-946
title: "Widen the cross-lingual golden slice to n = 16"
status: pending
created: 2026-08-27
last_updated: 2026-08-27
flow: tick
reproduction: >-
  Child of a 2+ decomposition (analysis
  docs/plan/m1/tick-analysis/anchor-leg-query-fidelity.md); /tick start
  converts the marker: write the test, run it RED against the unmodified
  golden set before any fixture edit, workflow §0. The wrong behavior: the
  cross-lingual class is UNGATEABLE — n = 12 active rows against rule G1's
  decision-grade floor of 16 (docs/measurement/retrieval-eval-baseline.md
  :66-69, :323-325), so the recorded anchor-leg defect (xl-cyber 0.25 vs
  sibling 0.75, :386-392) can never clear rule T1's per-class sign test
  (available on topical only, :396-398) no matter what fix is later
  chosen. RED test
  RetrievalGoldenSetTest#classCoverageMeetsFloors with the cross-lingual
  floor raised 12 → 16 (written FIRST; observed today: "class-below-floor:
  cross-lingual has 12, floor is 16" — the exact failure shape the
  existing validator produces, RetrievalGoldenSetTest.java:174-176). Probe
  today: active cross-lingual rows = 20 class lines − 8 replaced_by
  retirees = 12 (golden-set.jsonl :40-51 retired, :44-47/:62-69 active).
analysis_ref: docs/plan/m1/tick-analysis/anchor-leg-query-fidelity.md
blocked_by: [M1-945]
files_scope:
  - infochat-provider/src/test/resources/retrieval-eval/golden-set.jsonl
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalGoldenSetTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production / main-source change — git diff names no src/main path
    (probe: git diff --name-only). Labels DESCRIBE shipped retrieval; they
    do not change it (the M1-942 posture).
  - >-
    Touching any EXISTING golden record — no in-place edits, no new
    supersedes pairs: the extension is NEW records only (M1-942's
    extension precedent), and the 12 existing xling rows + 3 siblings are
    byte-identical after this ticket (probe: git diff shows added lines
    only).
  - >-
    The re-baseline run and record — M1-947; no run quotes numbers off the
    widened set until then.
  - >-
    Choosing the new need by taste — the selection cites M1-945's
    characterization record (breadth/attribution table); a need chosen
    without that citation fails review (analysis P11).
  - >-
    ANY docs/spec/** edit, and any change to the eval harness
    (RetrievalEvalRunnerIT counts and pins derive from the loaded set at
    run time — nothing to edit there).
  - >-
    Booting provider/collector, backfill, or retention against the test
    DB — labeling rides the frozen fingerprint read-only (analysis P4);
    this ticket MUST land before the campaign's freeze lifts.
acceptance:
  - "REPRODUCTION closed: RetrievalGoldenSetTest.classCoverageMeetsFloors passes at cross-lingual floor 16 — the floor bump is written RED first (observed: 'class-below-floor: cross-lingual has 12, floor is 16') and the four new records land to green it; the M1-942 failure-mode legs (failureModeRetiredRecordDoubleCounts, failureModeOversizedExpectedSet, failureModeXlingSetDriftsFromSibling) stay green UNMODIFIED unless this ticket's own text authorizes a change (test_plan.modifies names any touched leg)."
  - "Exactly FOUR new active cross-lingual records — one information need × {cs, es, ru, tr} — each carrying: a genuinely NEW non-English query form for the chosen need (not a paraphrase of the sibling's query or of the existing 12 xling queries), scope_lang per language, labeled_against.db_fingerprint byte-equal to the frozen fingerprint (ready=5214;max_ready_at=2026-08-24 16:00:57.001472+00;uid_sha256=06ed0de1…9727), notes naming its ACTIVE English sibling, and the sibling's adjudicated expected set VERBATIM (validator-enforced equality, RetrievalGoldenSetTest.java:235-241; four-language per-need coverage, :242-245)."
  - "Need selection is evidence-cited: the ticket body names the chosen sibling need and quotes M1-945's characterization record (the per-need |Δ| / overlap row that justifies it — analysis P11) — probe: the quoted row is byte-present in docs/measurement/anchor-leg-characterization.md (`grep -F` the body's quoted row against the committed record returns it); the chosen sibling passes RetrievalGoldenSetTest's active-record legs (an ACTIVE topical row with an adjudicated set — the M1-942 extension needs qualify)."
  - "Validator totals re-derived over the new active set with the M1-942 cap/floor re-derivation precedent (floors sum, total cap band) — the re-derived constants are stated in the ticket body with their arithmetic; classCoverageMeetsFloors passes over 63 active records (59 + 4)."
  - "FAILURE-MODE (freeze integrity): a new RetrievalGoldenSetTest leg feeds a copy with one new xling record's set drifted by one uid and asserts validateAll throws 'xling-set-drift' — the verbatim-inheritance leg discriminates on the NEW rows too, not only the M1-942 cascade."
  - "FAILURE-MODE (fingerprint pin): a leg feeds a copy with one new record's labeled_against fingerprint altered and asserts the validator refusal — new rows are bound to the frozen world (D1/discipline per docs/spec/llm.md §Determinism boundary's pinned-world reading the record's rule D1 enforces)."
  - "mvn verify from the repo root is green (the validator runs in the default suite, no DB); git diff --name-only names exactly the files_scope paths plus board/frontmatter regen."
test_plan:
  adds:
    - >-
      RetrievalGoldenSetTest — the new-rows presence/coverage leg (RED
      first per reproduction), the xling-drift failure-mode leg over the
      new rows, and the new-record fingerprint-pin leg.
  modifies:
    - >-
      RetrievalGoldenSetTest.CLASS_FLOORS ("cross-lingual" 12 → 16) and
      the total-cap band constants — AUTHORIZED by this ticket: the floor
      bump is the ticket's own RED leg, and the cap re-derivation is the
      M1-942 precedent (floors sum 57 → 61; active 59 → 63; cap band
      re-stated with arithmetic in the body).
  preserves:
    - >-
      Every other RetrievalGoldenSetTest leg — schema, 16-uid cap,
      supersedes integrity, retired-skip, per-need language coverage —
      green over the widened file.
    - all tests currently green on main.
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

# M1-946: Widen the cross-lingual golden slice to n = 16

## Context

The corrected baseline records a general anchor-leg defect (xl-cyber 0.25
vs sibling 0.75 over identical expected sets; `docs/measurement/
retrieval-eval-baseline.md:386-392`), but cross-lingual n = 12 is smoke by
rule G1 — the class can never gate a fix (T1's per-class sign test exists
only on topical, :396-398). Whatever fix level the user later chooses, its
owner-run delta has NO legal gate on the current set. This ticket widens
the class to n = 16 via the only legal path — NEW records labeled against
the same frozen fingerprint (the M1-942 extension precedent) — so M1-947's
re-baseline can mark the class decision-grade. Full context:
`analysis_ref:`.

**Start gate (user decision — analysis P15).** The paragraph above states
the CONSERVATIVE T1/G1 reading — the one that drives this ticket. The
liberal reading disagrees (the overall row at n ≥ 59 is decision-grade,
record :66-69/:325, and T1's sign test runs over discordant queries
class-agnostically, record :71-80, so an anchor-only fix flipping 10+ of
the 12 xling rows could clear the overall gate on the CURRENT set),
which would make this ticket unnecessary. The ruling belongs to the
user: this ticket starts ONLY after the user has ruled, citing M1-945's
characterization record, on the three-way fork — **(a) fix + widen +
gate** (the conservative reading holds; this ticket runs), **(b) fix +
overall-row gate** under the liberal T1 reading (set untouched; this
ticket and M1-947 are then aborted via `/tick abort`), **(c) no fix**
(both aborted). The ruling must land while the frozen fingerprint still
matches — BEFORE the wave-3/4 deployments (M1-933 retention deploy,
M1-934 backfill) touch the test DB; after drift, a widening requires a
full re-label cycle against a new corpus world (D1), not four
verbatim-inheritance rows. The gate is procedural and carried in this
body only; the frontmatter mechanics are unchanged (blocked_by stays
[M1-945]).

## Root cause

Not a code defect: an instrument-coverage gap. The golden set froze with 3
cross-lingual needs × 4 languages (12 rows, `golden-set.jsonl` :40-69);
G1's floor is 16 (record :66-69). Verified: `RetrievalGoldenSetTest`
`CLASS_FLOORS` carries "cross-lingual", 12 (:40-43) — the validator pins
today's smoke size as its floor, which this ticket re-derives upward
alongside the rows that satisfy it.

## Pitfalls

From the analysis, numbered identically: **P2** the widened class gates
nothing by itself — gating begins at M1-947's reading (do not claim
decision-grade in this ticket); **P3** freeze discipline — NEW records
only, frozen fingerprint, never in-place edits, M1-943's sha/skip
machinery untouched; **P4** this ticket must land + label while the DB
matches the frozen fingerprint (before the campaign's freeze lifts);
**P5** no numbers are quoted off the widened set here — M1-947 owns the
reading; **P11** verbatim sibling inheritance (validator-enforced),
four-language coverage, evidence-cited need selection, floor/cap
re-derivation with arithmetic; **P14** new query forms are authored
strings committed in the fixture — restate them in the body verbatim;
**P15** the start gate (Context block above): no work starts before the
user's recorded ruling — forks (b)/(c) abort this ticket via /tick abort.

## Approach

- **Files to touch:** the golden set + `RetrievalGoldenSetTest` only.
- **Steps, in order:** (1) read M1-945's characterization record, pick the
  sibling need citing its per-need row, author the four query forms; (2)
  write the RED floor leg (observed 12 < 16 failure); (3) bump
  `CLASS_FLOORS` + re-derive the cap band (arithmetic in the body);
  (4) append the four records (frozen fingerprint, verbatim sets,
  sibling-naming notes); (5) add the two failure-mode legs; (6) `mvn
  verify` green.
- **Controls to preserve:** every existing `RetrievalGoldenSetTest` leg
  except the two AUTHORIZED modifications in `test_plan.modifies`; the
  harness needs nothing (manifest counts derive from the loaded set).
- **Pitfall→mitigation:** P3→added-lines-only diff probe; P4→land before
  freeze lift (queue note to the driver); P11→verbatim/coverage/floor
  legs + citation requirement; P14→body restates the four query strings.

## Definition of done

Every YAML `acceptance:` item, each verified by its named test or probe —
including both failure-mode items and the git-diff shape probes.

## Verification

- P2 → the ticket body contains no recall numbers and no
  decision-grade claim for the widened class (reviewer diff check).
- P3 → git diff over golden-set.jsonl shows added lines only.
- P4 → driver queue note: start only while the frozen fingerprint is
  intact (the M1-947 run re-asserts it mechanically).
- P5 → no reading is quoted off the widened set here: probe
  `git diff infochat-provider/src/test/resources/retrieval-eval/golden-set.jsonl`
  shows the four added records carrying fixture fields only (query,
  scope_lang, labeled_against, expected set, notes) — no recall or scored
  number rides the fixture; the body's only recall figures are the
  already-recorded 12-row observation
  (docs/measurement/retrieval-eval-baseline.md:386-392); the first reading
  off the widened set is M1-947's.
- P11 → classCoverageMeetsFloors (floor 16, 63 active), verbatim-equality
  + language-coverage legs, the need-selection citation.
- P14 → the four query strings restated verbatim in the body.
- P15 → the Context start-gate block names the three-way fork, the
  /tick-abort branches, and the pre-drift deadline; no commit precedes
  the user's recorded ruling (board/reviewer check).
- acceptance items 1..7 → the named tests/probes.

## Out-of-scope

As the YAML block: no production change, no existing-record edits, no
re-baseline (M1-947), no taste-based need selection, no spec edit, no
harness change, no provider/collector boot. Any modification to a
pre-existing `RetrievalGoldenSetTest` leg beyond the two authorized in
`test_plan.modifies` is an engineering-rules §8 violation.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-946-anchor-fidelity-2.md
```
