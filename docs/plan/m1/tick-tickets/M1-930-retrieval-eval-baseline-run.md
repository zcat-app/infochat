---
id: M1-930
title: "Baseline retrieval-eval measurement record"
status: pending
created: 2026-08-26
last_updated: 2026-08-26
flow: tick
reproduction: >-
  Probe (measurement ticket): `ls docs/measurement/ | grep retriev` returns
  exactly retrieval-separability.md (verified 2026-08-26) — a threshold
  -calibration record, not an end-to-end retrieval measurement: no per-class
  Recall@16/MRR baseline against the live corpus exists anywhere committed.
  Observed consequence: the three user-confirmed live failures (Czech
  location, last-2h recency, price lookup) have never been quantified, and
  M1-916/917/927 could not be attributed or gated. The baseline run IS the
  reproduction (brief, Reproduction section): M1-929's runner executed
  against today's test DB, recording per-class scores that are expected to be
  near zero for the temporal/entity/price classes — that record is the first
  quantitative statement of the observed failures. Intended entry: the
  operator command documented in RetrievalEvalRunnerIT, producing
  docs/measurement/retrieval-eval-baseline.md.
analysis_ref: docs/plan/m1/tick-analysis/golden-set-retrieval-eval.md
blocked_by: [M1-928, M1-929]
files_scope:
  - docs/measurement/retrieval-eval-baseline.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production, spec, design, config, or test change — this ticket lands a
    measurement record only (doc-only diff; mvn verify N/A per the
    inert-diff rule, memory: doc-only-edits-skip-verify).
  - >-
    Fixing what the numbers show (price tool, text filter, temporal parse,
    category/tag split, tag canonicalization) — separate improvement topics;
    this record is the measuring stick's first reading, explicitly NOT a
    direction (docs/measurement/README.md: evidence, not spec).
  - >-
    CI gating automation for the harness — operator-run first (binding user
    decision); any gating wiring is a separately-decided topic.
  - >-
    Re-labeling the golden set beyond drift-triggered `supersedes` corrections
    — the set is M1-928's frozen contract; if the baseline exposes label
    defects, corrections go through supersedes with their own rationale and
    are recorded in the record's corrections section (corrections stay
    visible — measurement-README convention).
  - >-
    Threshold derivation — the record reports what is; any threshold change
    is its own gated ticket (the M1-748 posture: values move through
    tickets, not config tweaks).
acceptance:
  - "PRE-REGISTRATION (the tag-tree/M1-860 discipline; analysis P6/P13): the record lands in TWO commits on this ticket's branch — commit 1 contains the reading and gating rules with NO numbers (per-class table skeletons; the paired-change rule: a retrieval change is gated by the exact sign test over discordant queries with the floor of 6 one-directional discordants, TRACK-A-THRESHOLDS.md §1; every class with n < 16 labeled a smoke signal per track-a G1; no percentage-point thresholds against survivor sets — memory: pre-registration-free-variable), and commit 2 appends the results — probe: `git log --follow docs/measurement/retrieval-eval-baseline.md` on the branch shows the rules commit preceding every results commit."
  - "The record carries the full pins block (analysis P13): repo commit, DB fingerprint (world-visible READY post count, max ready_at, sha256 over the ordered world uid set), effective config values (threshold, limit), embedder + translator endpoints/models, anchor-cache state (fresh vs pre-warmed, hit counts, fallback count = 0 asserted by the harness), run timestamps — probe: each pin key resolves in the record with a value."
  - "Per-class results table with n: capped Recall@16 AND raw recall, MRR, over-return (mean returned count + median post age) for none_expected rows, lexical-only share — every class present, numbers final or absent, never estimated (measurement-README convention) — probe: `grep -c 'smoke' docs/measurement/retrieval-eval-baseline.md` marks every n<16 class row."
  - "The expected-bad classes are called out against their observed numbers (temporal/entity/price classes are expected near zero — the baseline IS the reproduction of the live failures), and the HONESTY ARM holds: any class that scores unexpectedly HIGH gets a named investigation note (which queries hit, whether the label or the mechanism explains it) rather than a silent win — probe: each of the three failing classes has either its low number or its investigation note, never neither."
  - "DETERMINISM leg (D19, docs/spec/llm.md:475-480): the operator re-runs the harness on unchanged DB state (matching fingerprint) and the record states per-query uid lists were byte-identical across the two runs — probe: the record names the two run timestamps and the shared fingerprint."
  - "WHAT-THE-NUMBERS-DO-NOT-SETTLE section present (measurement-README convention): at n=49-56 overall (CI on a proportion roughly +-0.14) and 4-12 per class, the record states that per-class rankings and sub-floor deltas are not resolvable, that cross-language recall comparisons are confounded by the anchor leg unless the anchored text is read (P9's no-op check), and that temporal labels are bound to the recorded fingerprint (P7) — probe: `grep -n 'do not settle' docs/measurement/retrieval-eval-baseline.md` returns the section."
  - "Corrections stay visible: any label correction made during the run goes through golden-set `supersedes` records and is listed in the record's corrections section with its rationale — probe: the corrections section enumerates them or states 'none'."
  - "Diff fence (analysis P13/P15): git diff --name-only names exactly docs/measurement/retrieval-eval-baseline.md (plus own frontmatter / board regen); no prod URL or live-user data beyond post uids appears in the record — probe: `git diff --name-only` output."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
  notes:
    - >-
      Doc-only deliverable (the M1-860/M1-864 measurement-ticket shape); the
      runnable verification is the harness invocation + probes above. mvn
      verify is N/A per the inert-diff rule.
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
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-930: Baseline retrieval-eval measurement record

## Context

The golden set (M1-928) and the harness (M1-929) exist; nothing has been
measured. This ticket runs the harness against today's test DB and lands
`docs/measurement/retrieval-eval-baseline.md` — the first quantitative record
of the three user-confirmed live failures (Czech location, last-2h recency,
price lookup), and the reading every future retrieval change is gated
against (eval-first sequencing, binding user decision). Shared analysis:
`analysis_ref:`. Blocked on both siblings.

## Root cause

Not a code defect — the first reading of a new instrument (analysis
document). Verified: no end-to-end retrieval measurement exists committed
(docs/measurement holds threshold calibration only); the failing classes'
scores are expected near zero — the fused SQL has no time predicate
(SemanticSearchTool.java:224-275), no location/entity query surface (no chat
tool reads `post_entity`; security.md:328-334), and no price tool
(`price_snapshot` is reachable only via AssetSnapshotReader for the asset
commands). The record documents these as measured numbers, not assertions.

## Pitfalls

Numbered per the analysis document; this ticket carries P6, P13, P15, plus
the re-run half of P7 and P12's reporting.

- P6: power over-claiming — pre-registered rules commit before results; sign
  -test floor 6; n<16 classes marked smoke signals; no pp-thresholds.
- P13: record discipline — pins, final-or-absent numbers, corrections
  visible, what-the-numbers-do-not-settle section; no spec row cites it.
- P7 (re-run half): determinism leg on a matching fingerprint; temporal
  labels bound to the recorded fingerprint; re-baseline after drift is an
  explicit supersedes relabel, recorded.
- P12: none_expected rows reported by over-return + median age — the
  quantitative shape of "week-old posts served as recent".
- P15: prod isolation — the run targets the test DB and the test stack's
  endpoints only; the record carries no prod URLs or live-user data.

## Approach

- **Files to touch** — `files_scope`: the one record file (plus own
  frontmatter/board regen).
- **Steps in implementation order:**
  1. Write and commit the pre-registered reading/gating rules section FIRST
     (no numbers) — the whole value of the gate depends on it predating the
     results (P6).
  2. Operator smoke-run the harness (M1-929 acceptance already proved the
     plumbing), then the full run; capture manifest + outputs.
  3. Fill the results: per-class table, expected-bad callouts, honesty-arm
     notes for anything unexpectedly high, determinism re-run, pins,
     corrections, do-not-settle section (P13).
  4. Append-only from here: later re-baselines are new dated sections or new
     records, never edits (corrections stay visible).
- **Controls to preserve (§10):** nothing is rerouted; the record cites no
  spec change and no threshold moves; the golden set changes only via
  supersedes with recorded rationale.
- **Pitfall→mitigation:** P6→step 1; P13→steps 3-4; P7→step 3 determinism
  leg + fingerprint pinning; P12→over-return columns in the table; P15→
  the diff fence + content probe.

## Definition of done

The record exists with the pre-registration commit preceding every results
commit; the per-class table is complete with all metrics and smoke-signal
marks; the three failing classes carry numbers or investigation notes; the
determinism re-run is stated; pins and the do-not-settle section are present;
the diff touches nothing but the record (and board/frontmatter).

## Verification

- P6 → acceptance item 1: `git log --follow` shows rules-before-results; the
  gating rule names the sign-test floor; smoke marks on every n<16 row.
- P13 → acceptance items 2-3, 6-7: pins resolve; table complete; the
  do-not-settle grep; corrections enumerated.
- P7 → acceptance item 5: two run timestamps + shared fingerprint stated.
- P12 → acceptance item 3: over-return + median-age columns for
  none_expected rows.
- P15 → acceptance item 8: diff fence + content probe.
- Honesty arm → acceptance item 4: every failing class has its number or its
  investigation note.

## Out-of-scope

Named in `out_of_scope`: any production/spec/design/config/test change (doc
-only deliverable); fixing what the numbers show; CI gating; re-labeling
beyond supersedes; threshold derivation. If the run surfaces a harness
defect, it goes back to M1-929 — the record never reports numbers produced
by a harness whose self-checks failed.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-930-retrieval-eval-baseline-run.md
```
