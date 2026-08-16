---
id: M1-858
title: "Re-measure the tool loop with the levers landed"
status: done
created: 2026-08-16
last_updated: 2026-08-16
flow: tick
reproduction: >-
  Probe (evidence ticket; no test can exist — the M1-844 precedent):
  grep -n 'FAIL' docs/measurement/direct-chat-e2e.md returns the
  bar-clearing matrix with EVERY (gemma, language) pair FAIL (:260-273)
  — five pairs failing the zero-L0 conjunct on G5 citation-discipline
  defects, tr additionally on the unbridged-dialect collapse, and the
  TOOL-LOOP expected-call rows at 0/7 for gemma in every language
  (:169-171) — measured against prompt surfaces this set's two code
  tickets (M1-856 tool prompt + bridge; M1-857 citation wording) are
  mandated to change. The record is stale by construction until
  re-measured: the matrix the pending siblings M1-845 (wording review)
  and M1-848 (registry seed) consume predates the levers.
analysis_ref: docs/plan/m1/tick-analysis/tool-loop-hardening.md
blocked_by: [M1-856, M1-857, M1-859]
files_scope:
  - docs/measurement/direct-chat-e2e.md
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production code or docs/spec/** change — the levers are M1-856 and
    M1-857; this ticket produces evidence only. A verdict here is never a
    direction by itself (the M1-844 rule: evidence justifies a row; it
    never appears inside one).
  - >-
    RE-WRITING the existing record — the 244fcf66 campaign results are
    terminal evidence; the re-measure lands as a NEW campaign section
    (own commit pin, own pre-registration), append-only (P13; the spec
    layering axiom: nothing normative points at a measurement).
  - >-
    RE-LITIGATING settled verdicts — the A/B context-translation verdict
    (EN-context arm inherited for every language) and the lang-quality
    parametric legs are final; this campaign re-runs the GROUNDED and
    TOOL-LOOP legs only, because M1-857 changes the grounded-turn prompt
    and M1-856 changes the tool-loop prompt.
  - >-
    LATENCY legs and the refusal re-check — latency is data-only with no
    bar, and the zero-refusal result is unchanged surface; re-recording
    either is optional color, never a gate (note the prompt grew by the
    example + sentences if prefill is re-observed incidentally).
  - >-
    ENABLING any (model, language) pair — the bar-clearing matrix feeds
    M1-848's registry seed; nothing becomes eligible here.
  - >-
    COMMITTING the .bench working data (gitignored, the campaign posture)
    or running any leg against prod containers (measurements-never-ride-
    prod-containers): the deployment's own GGUF on the pinned local
    llama-server, the isolated/test instance shape the prior campaign
    used.
acceptance:
  - "Pre-registration BEFORE any arm: the appended record section opens with the same LOCKED bar (tie table, L0 gates G1-G7, zero-defect conjunct, hygiene never averaged) plus the named delta (the M1-856/M1-857 prompt surfaces, byte-cited at the pin) and NO free variable — and git log --follow docs/measurement/direct-chat-e2e.md shows the lock commit predating every results commit of this campaign (P13)."
  - "The repo commit pin postdates M1-856 and M1-857 landing (the P20 measured-surfaces rule; start-time census of pending tickets touching chat prompt/tool surfaces) — probe: grep -n 'commit' docs/measurement/direct-chat-e2e.md shows the new campaign's pin."
  - "Harness fidelity stated as production-shaped retrieval, not a divergence disclosure: the record's re-measure section states 'the harness models production retrieval (M1-859): real cosine over deployment-embedder vectors, queries anchored per M1-746'; the G6 tool-protocol gate is EXTENDED to the two accepted emission dialects (a bridged native emission is protocol-adherent, not a collapse), and any REMAINING divergence from production is still enumerated (campaign-harnesses-must-disclose-excluded-paths) — probe: grep -n 'models production retrieval' docs/measurement/direct-chat-e2e.md returns the sentence in the new section."
  - "The t07 no-unnecessary-call control holds: zero false tool calls per arm per language, and any false call is a recorded defect in its cell, never averaged away — the levers must not buy calling with false positives (P13) — probe: grep -n 't07' docs/measurement/direct-chat-e2e.md returns the new campaign's control row naming each arm's t07 call count (expected: zero on every arm; a nonzero count appears only as a named cell-defect entry, never inside a headline average)."
  - "Both models measured on BOTH re-run legs (GROUNDED, TOOL-LOOP), all five languages, same fixtures (16 grounded + 8 tool-loop per language, the locked n) — the legs re-measure the chat behavior governed by docs/spec/commands.md §Chat mode under the prompt conventions of docs/spec/llm.md §Prompt-injection-aware prompt shape — probe: grep -n 'TOOL-LOOP' docs/measurement/direct-chat-e2e.md shows the new section's tables with every (arm × language) cell."
  - "G5 citation columns recorded per cell for BOTH arms (the actual bar blocker): expected-citation hit/miss counts and any URL-outside-set event, beside the headline, never averaged — probe: grep -n 'G5\\|citation' docs/measurement/direct-chat-e2e.md shows the per-cell rows in the new section."
  - "The bar-clearing matrix is RESTATED per (model, language) pair from the new cells (the artifact M1-848's registry seed consumes), each PASS tracing to reported data — probe: grep -n 'matrix' docs/measurement/direct-chat-e2e.md shows the new matrix; the old matrix remains intact above it (append-only — git diff shows no removed line in the 244fcf66 section)."
  - "The epistemic-stance residual (t05 chained calls, t06 check-before-claiming-absence, t08 two-fetch comparison) is recorded as defect rows wherever it persists — never dropped from a cell, never prompt-engineered mid-campaign (P8's honesty rule: the English-plane sentence is anchor agreement, not a calling lever; no arm re-tests the ab negative) — probe: grep -n 't05\\|t06\\|t08' docs/measurement/direct-chat-e2e.md returns the new campaign's per-scenario residual rows, each naming the arm's outcome (cleared or still-zero), so a persistent miss stays visible in its cell."
  - "Working data stays under .bench/direct-chat-e2e/ (gitignored), the campaign DECISIONS.md logs every corrected number (decision-20/21 posture), and the corpus snapshot is re-pinned or re-used with its sha256 stated — probe: grep -n 'sha256' docs/measurement/direct-chat-e2e.md."
  - "mvn verify from repo root is green (evidence-only ticket; the build must not regress, engineering-rules §5)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
  notes:
    - >-
      Campaign harness under .bench/ (gitignored), the M1-844 posture;
      the promoted record section is the only committed artifact, so
      there is no JUnit surface to add. mvn verify covers the
      no-regression leg.
spec_refs:
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Prompt-injection-aware prompt shape
decision_refs:
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
reviews:
  - round: 1
    date: 2026-08-16
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY NOT-APPLICABLE, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "tracked: 3 files +276/-9 (record append +269/-1, board regen, ticket frontmatter); campaign working data under gitignored .bench/ (harness upgrades, 2x2x5 arm runs, judge/reviewer/adjudication files)"
    rework_items: 0
    verdict_file: .scratch/tick-review-M1-858-r1.txt
    note: >-
      run by the implementation session itself with a substituted reviewer
      model — untrusted, superseded by round 2
  - round: 2
    date: 2026-08-16
    verdict: REWORK
    checks: "SPEC-TRUTHNESS FAIL, SECURITY PASS, TEST-ADEQUACY NOT-APPLICABLE, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "full branch diff re-review: 3 files +284/-9"
    rework_items: 4
    verdict_file: .scratch/tick-review-M1-858-r2.txt
  - round: 3
    date: 2026-08-16
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY NOT-APPLICABLE, MAINTAINABILITY PASS, SCOPE PASS (all 4 round-2 items SATISFIED)"
    diff_stats: "fix hunks: 3 files +54/-16 (record corrections 19 +/-, board regen, ticket bookkeeping); verify log r3 exit 0"
    rework_items: 0
    verdict_file: .scratch/tick-review-M1-858-r3.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-858: Re-measure the tool loop with the levers landed

## Context

M1-844's campaign left every (gemma, language) pair FAILing the bar on two
design defects this set's code tickets fix at the prompt/parser level:
gemma's 0/7 expected-call rate under the shipped text grammar (with one
unbridged-dialect collapse reaching a user, tr t02) and both models' G5
citation omission (gemma 13 misses + 1 mutated-URL cluster; incumbent
7-11 per cell). The spike-proven levers (worked example 0→4 calls;
native-dialect bridge converting slips, combined 5/15; zero false calls
on the t07 control in every arm) live in .bench working data — citable,
never committed. The committed record the pending siblings consume
(M1-845's wording review reads it; M1-848's registry seeds from its
matrix) predates the levers and is stale by construction until this
re-measure lands. Shared analysis: `analysis_ref:`.

## Root cause

An evidence dependency, not a code defect: the bar-clearing matrix is the
artifact the sibling tickets cite, and it was measured against prompt
surfaces M1-856/M1-857 are mandated to change (TOOL_INSTRUCTIONS, the
emission grammar, the framing citation sentence, the post-result
instruction line). Shipping the levers without re-measuring leaves the
registry seed (M1-848) reading FAIL cells the levers may have cleared —
or, if the levers regress something (false calls, language holding,
protocol hygiene), leaves that invisible. The record's own rule says the
registry seeds from the cells "as-is unless a new measurement clears
them" (direct-chat-e2e.md:281-282); this ticket is that measurement.

## Pitfalls

Numbered per the analysis document; this ticket carries P8, P13, P14.

- P8: negative-result honesty — the English-plane sentence is anchor
  agreement, NOT a calling lever (ab spike: 0 calls in both arms; do not
  re-test); no arm or claim isolates it as a lever.
- P13: re-measure discipline — same fixtures, same locked bar locked
  BEFORE any arm; commit pin postdates M1-856+M1-857; the record APPENDS a
  new campaign section, never rewrites the 244fcf66 results; the harness
  discloses the anchor divergence and every excluded path; G6 extends to
  the accepted dialects; the t07 zero-false-call control stays; no prod
  containers; no free variables in thresholds.
- P14: ordering — blocked_by M1-856 + M1-857 + M1-859 (it measures their
  combined end state: prompt surfaces AND production-shaped retrieval);
  the user/driver should sequence this ahead of M1-848's registry
  seed (848's blocked_by is not editable from here — flagged in the
  analysis decomposition; at minimum 848 re-verifies its seed against the
  updated record).

## Approach

- **Files to touch:** `docs/measurement/direct-chat-e2e.md` (the appended
  campaign section — the only committed artifact); harness, fixtures, and
  results under `.bench/direct-chat-e2e/` (gitignored, running on the
  M1-859-upgraded harness — production-shaped retrieval over the same
  corpus and fixtures).
- **Steps, in order:**
  1. Re-verify the reproduction probe (the all-FAIL matrix + 0/7 rows)
     and re-run the start-time P20 census: the pin must postdate
     M1-856/M1-857, the M1-859 harness upgrade, and every other landed
     ticket touching chat prompt/tool surfaces.
  2. Write and LOCK the new section's pre-registration: the same locked
     bar restated + the named delta (the byte-cited prompt surfaces of
     856/857) + the harness statement (production-shaped retrieval per
     M1-859, the G6 dialect extension, every remaining excluded path).
     Commit the lock before any arm runs.
  3. Run the arms: gemma (deployment GGUF, pinned llama-server, greedy)
     and the incumbent reference, GROUNDED and TOOL-LOOP legs, five
     languages, same fixtures, same judge/reviewer/adjudication protocol.
  4. Score per the locked gates; record G5 per cell for both arms, the
     t07 control, iteration counts, and the epistemic-stance residual
     rows.
  5. Restate the bar-clearing matrix from the new cells; append the
     section; log every corrected number in DECISIONS.md.
- **Controls to preserve (§10):** this ticket reroutes no code path. The
  record's own integrity rules are the controls: pre-registration, commit
  pin, append-only history, voided-fixture disclosure, gitignored working
  data, no prod containers.
- **Pitfall→mitigation:** P8→step 2's delta statement names the sentence's
  rationale; P13→steps 2-5 and acceptance items 1-4, 7, 9; P14→step 1's
  census + the blocked_by frontmatter + the ordering note to the user.

## Definition of done

The appended campaign section carries: the pre-registered lock (committed
before results), the post-856/857/859 commit pin, the production-retrieval
statement with the extended G6 gate, per-cell GROUNDED + TOOL-LOOP results
for both models and all five languages, per-cell G5 citation columns, the
t07 zero-false-call control row, the restated bar-clearing matrix, the
epistemic-stance residual rows, and the corpus sha256 — each verifiable
by its named grep probe, with the 244fcf66 section byte-intact above —
and mvn verify is green.

## Verification

- P8 → the pre-registration's delta statement (item 1) — no arm isolates
  the sentence; the grep probe shows the named delta.
- P13 → items 1 (lock-before-arms git order), 2 (pin postdates the code
  tickets and the M1-859 harness upgrade), 3 (production-retrieval
  statement + G6 extension), 4 (t07 control), 7 (append-only diff),
  9 (DECISIONS.md log + sha256).
- P14 → item 2's census recorded in the section header; the blocked_by
  frontmatter; the user-facing ordering note at landing.
- Failure mode → items 4 and 8: a false call on t07 or a persistent
  epistemic-stance miss stays in its cell as a recorded defect — the
  campaign's never-drop rule applied to the exact failure classes the
  levers could plausibly worsen.
- acceptance item 10 → mvn verify from repo root.

## Out-of-scope

Named in `out_of_scope`: any code or spec change, any history rewrite,
the settled A/B and parametric verdicts, the latency and refusal legs,
any enablement of a pair, committed working data, and prod containers.
If the levers REGRESS a cell (false calls, language collapse, new L0
class), that is a RESULT recorded in the matrix — the code tickets'
follow-up is a new ticket, not a mid-campaign edit.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-858-toolloop-remeasure.md
```

## Round 2 rework

1. Finding 1: correct the t06 residual row and closing sentence
   (docs/measurement/direct-chat-e2e.md:509, :512-514) to record the
   expected-lookup miss the data shows, judging-fact stated separately,
   plus a DECISIONS.md correction entry — evaluated via
   grep -n 't06' docs/measurement/direct-chat-e2e.md (row no longer claims
   the calling behavior cleared; asserts gemma 0/5) and
   grep '"id": "t06"' .bench/direct-chat-e2e/results/remeasure-2026-08-16/toolloop/gemma/*.jsonl
   | grep -c '"n_iterations": 0' → 10.
2. Finding 2: change "D=3" to "D=2" at docs/measurement/direct-chat-e2e.md:524
   (and decision 25) — evaluated via grep -n 'D=3'
   docs/measurement/direct-chat-e2e.md → no match.
3. Finding 3: change "class A (19 rows" to "class A (17 rows" at
   docs/measurement/direct-chat-e2e.md:435 (and decision 24) — evaluated
   via grep -c '"agree":false'
   .bench/direct-chat-e2e/results/remeasure-2026-08-16/judge/review-codex.jsonl
   → 25 = 17+5+3.
4. Finding 4: replace the two Wilson intervals at
   docs/measurement/direct-chat-e2e.md:453-454 with [0.640, 0.965] (14/16)
   and [0.717, 0.989] (15/16), or drop the sentence, logging the
   derivation in DECISIONS.md — evaluated via
   grep -n '0.616\|0.698' docs/measurement/direct-chat-e2e.md → no match.

## Review observations

- (round 2, RECOMMENDED-NEW-TICKET, TOUCHED-BY-THIS-DIFF: no) The t06
  fixture cannot see the defect it names: its must_convey items grade only
  the honesty of the not-in-feed wording, so a reply asserting "the search
  came back empty" with zero recorded calls passes judgement while the
  expect_tools record misses — the judged layer is structurally unable to
  detect check-before-claiming-absence failures (and the incumbent's
  replies assert a search ran when none did, which no gate scores).
  Relevant to any future epistemic-stance lever ticket and to
  M1-845/M1-848's reading of this record. Filing is the user's call.
