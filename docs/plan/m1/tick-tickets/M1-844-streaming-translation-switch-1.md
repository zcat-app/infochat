---
id: M1-844
title: "Measure grounded in-language chat with the tool loop"
status: done
created: 2026-08-14
last_updated: 2026-08-16
flow: tick
reproduction: >-
  Probe: grep -n 'Parametric-only' docs/measurement/lang-quality.md
  returns :156 ("Parametric-only by design: grounded-chat behavior was not
  tested."), and grep -rli 'grounded' docs/measurement/ returns only
  lang-quality.md — the file that disclaims exactly that coverage.
  Observed evidence gap, verified against lang-quality.md:14-17 (the
  DIRECT leg was parametric-only by design): no measurement shows any
  model producing chat-quality prose in cs/es/ru/tr with English retrieved
  context injected, none shows the in-language tool loop, and none prices
  the context-translation A/B or first-token latency — yet the 2026-08-14
  user direction (future-features.md §D5) restricts direct mode to
  "models that cleared the in-language bar", a bar that today rests on
  parametric-only evidence.
analysis_ref: docs/plan/m1/tick-analysis/streaming-translation-switch.md
blocked_by: []
files_scope:
  - docs/measurement/direct-chat-e2e.md
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production code change or docs/spec/** edit. This ticket produces
    evidence; the amendments it feeds are M1-845/M1-846, the registry is
    M1-848. No verdict here is a direction by itself (translator-slot.md's
    standing rule: evidence justifies a row; it never appears inside one).
  - >-
    RE-RUNNING the lang-quality parametric legs (docs/measurement/
    lang-quality.md is a final record). This campaign adds the grounded and
    tool-loop legs the DIRECT leg never ran; it does not re-litigate the
    parametric verdicts.
  - >-
    COMMITTING the .bench working data (gitignored, the lang-quality
    posture). Only the promoted record lands at
    docs/measurement/direct-chat-e2e.md.
  - >-
    ENABLING any model or language for direct mode — the bar-clearing
    matrix this record produces seeds M1-848's code-constant registry;
    nothing becomes eligible here.
  - >-
    Languages outside the shipped set {en, cs, es, ru, tr} and model arms
    beyond the named candidates, unless the user adds one at start.
acceptance:
  - "Pre-registered thresholds lock BEFORE any arm runs: the committed record opens with the bar — per language: tie the incumbent on judgement AND zero L0 defects, hygiene columns (defect/void rates) beside the headline and never averaged, the tie statistic convention named — and `git log --follow docs/measurement/direct-chat-e2e.md` shows the thresholds commit predating every results commit (the lang-quality pre-registration posture)."
  - "GROUNDED leg measured and recorded for en (baseline) + cs/es/ru/tr: English retrieved-context block injected in the shipped UNTRUSTED_CONTENT call shape, reply in the scope language; per-language cells carry language-holding (no whole-turn collapse), grounding/citation accuracy against the injected posts, and no-bleed columns — probe: grep -n 'GROUNDED' docs/measurement/direct-chat-e2e.md shows the per-language verdict cells."
  - "TOOL-LOOP leg measured and recorded: production-shaped prompts rendered through the real prompt builders (ChatPromptBuilder + TOOL_INSTRUCTIONS + the M1-618/M1-685 directives), measuring TOOL_CALL protocol adherence, per-turn iteration counts, and hygiene per language — probe: grep -n 'TOOL-LOOP' docs/measurement/direct-chat-e2e.md shows the protocol-adherence table; a scenario whose model output collapses the protocol is a recorded defect, never dropped from the cell."
  - "A/B verdict recorded: English-context-direct vs translated-context arms compared on the three grounded columns, so the cheaper hypothesis (gemma reads English context directly) is confirmed or falsified per language — probe: grep -n 'A/B' docs/measurement/direct-chat-e2e.md shows the verdict and which arm the direct-mode prompt design inherits."
  - "LATENCY recorded on the deployment box for both context arms (prefill, first-token, steady-state), so the prefill-vs-generation split is measured, not assumed — probe: grep -n 'tok/s\\|first-token' docs/measurement/direct-chat-e2e.md shows the table."
  - "The bar-clearing matrix is stated per (model, language) pair in the record — the exact content M1-845's amendment rule and M1-848's registry seed cite — probe: grep -n 'matrix\\|registry' docs/measurement/direct-chat-e2e.md shows the per-pair PASS/FAIL cells, and a pair with any L0 defect records FAIL regardless of a judgement tie (never averaged into a headline)."
  - "Fixture discipline per the M1-717 protocol: non-English fixtures are native-authored where possible, otherwise machine-translated and back-translation-verified with every line read and corrections recorded; a fixture failing verification is VOIDED, not scored — probe: grep -n 'void' docs/measurement/direct-chat-e2e.md names any voided fixture and its correction in the decision log."
  - "The repo commit the whole run executed against is pinned in the record (the measured-surfaces-are-moving rule, translator-slot.md:69-71) — probe: grep -n 'commit' docs/measurement/direct-chat-e2e.md shows the pin."
  - "The incumbent's parametric-only refusal misfires (lang-quality.md:50,:122-125) are re-checked under grounded conditions and the result recorded either way — probe: grep -n 'refusal' docs/measurement/direct-chat-e2e.md shows the grounded-context refusal count per arm."
  - "mvn verify from repo root is green (evidence-only ticket; the build must not regress, engineering-rules §5)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
  notes:
    - >-
      The campaign harness lives under .bench/ (gitignored), the
      lang-quality posture; the promoted record is the only committed
      artifact, so there is no JUnit surface to add. mvn verify covers the
      no-regression leg.
spec_refs:
  - docs/spec/llm.md §Translation flow
  - docs/spec/llm.md §Prompt-injection-aware prompt shape
  - docs/spec/commands.md §Chat mode
decision_refs:
  - D29
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
    date: 2026-08-15
    verdict: REWORK
    checks: "SPEC-TRUTHNESS FAIL, SECURITY PASS, TEST-ADEQUACY NOT-APPLICABLE, MAINTAINABILITY FAIL, SCOPE PASS"
    diff_stats: "3 files, +288/-14"
    rework_items: 4
    verdict_file: .scratch/tick-review-M1-844-r1.txt
  - round: 2
    date: 2026-08-15
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY NOT-APPLICABLE, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "3 files, +354/-15 (round-2 fix hunks: docs/measurement/direct-chat-e2e.md only); rework items 4/4 SATISFIED"
    rework_items: 0
    verdict_file: .scratch/tick-review-M1-844-r2.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check: >-
  start 2026-08-15 — lint BLOCKER (analysis_ref unresolvable in the
  worktree) resolved: docs/plan/m1/tick-analysis/ is gitignored by design,
  the file copied into the worktree per the M1-835 precedent. Reproduction
  probes re-run clean (lang-quality.md:156 exact text; "grounded" grep
  single hit). Citations spot-checked: lang-quality.md:14-17/:50/:84-90/
  :122-125/:139-141/:156, future-features.md:386-453, live-text-streaming.
  md:157-160, translator-slot.md:69-71, D29/D58 all verified. Analysis
  pitfalls P7/P15/P18/P20 all present. blocked_by empty — no test
  enumeration owed. P20 refined by user decision: the pin must postdate
  only landed batch tickets touching the measured surfaces (start-time
  census in the P20 entry — none of the ten pending touch them); pin
  244fcf66. Arms: the two named candidates only — user declined adding
  qwen3.6-35b-a3b (its exclusion is already decided on the conjunctive
  bar; its collapse class is amplified, not rescued, by English context).
  Branch-commit shape: the record lands as two commits on the branch
  (thresholds lock before any arm runs, then results) per acceptance item
  1; the squash merge preserves the lang-quality single-commit-on-main
  posture with the lock asserted in the record header.
escalation_reason:
---

# M1-844: Measure grounded in-language chat with the tool loop

## Context

The 2026-08-14 user direction ships a switchable translation pipeline whose
direct mode is restricted to models that cleared the in-language bar
(future-features.md §D5:386-453) — today gemma-4-26b-a4b only, on the
strength of the lang-quality campaign (docs/measurement/lang-quality.md).
But that campaign's DIRECT leg was parametric-only BY DESIGN
(lang-quality.md:14-17, :156): no retrieved context, no tool loop. Direct
mode in production is exactly the unmeasured shape — English retrieved
context injected (the embedding DB is English, D29), the English TOOL_CALL
protocol and English tool results interleaved with user-language prose, and
a reply that must hold the scope's declared language. This ticket runs the
mini-E2E measurement the brief names as the first gate. Shared analysis:
`analysis_ref:`.

## Root cause

An evidence gap, not a defect: the bar-clearing verdicts
(lang-quality.md:84-90) prove parametric chat quality; the grounded leg,
the tool-loop leg, the context-translation A/B, and deployment-box latency
were never measured (design doc live-text-streaming.md:157-160 names
exactly this gap; the D5 entry's prerequisites repeat it,
future-features.md:430-435). Building the registry (M1-848) or the
amendment (M1-845) on the parametric evidence alone would repeat the
mistake the campaign was commissioned to end — guessing model behavior from
a cheaper proxy.

## Pitfalls

Numbered per the analysis document; this ticket carries P7, P15, P18, P20.

- P7: direct mode has no mechanical language net — this campaign is the
  evidence the net's substitute (the registry gate) rests on; a campaign
  that measures only judgement and skips language-holding under grounded
  conditions leaves the gate hollow.
- P15: end-state calibration — the record's bar-clearing matrix is the
  artifact M1-848's registry pins; a record that omits a (model, language)
  cell or averages hygiene into a headline forces the sibling to re-run or
  guess.
- P18: measurement discipline — production-shaped prompts through the real
  builders, repo commit pinned, thresholds locked before arms, hygiene
  columns never averaged, blind judge + reviewer round (the campaign's
  over-strictness lesson, lang-quality.md:139-141), fixtures not
  retranslations where idiom matters, and the incumbent's refusal misfires
  re-checked under grounded conditions.
- P20: soft sequencing — refined at start (user decision 2026-08-15): the
  pinned commit must postdate every landed batch ticket that touches the
  measured surfaces (chat prompt builders, tool instructions, directives,
  chat tool loop). Census at start: M1-822/827/835/836/838/839/840/841/842/
  843 files_scope reviewed — none touch the measured surfaces (restore, GPU,
  simplex, image work); the still-pending remainder is pin-neutral. Pin:
  244fcf66.

## Approach

- **Files to touch:** `docs/measurement/direct-chat-e2e.md` (new, the
  promoted record); the harness lives under `.bench/` (gitignored) per the
  lang-quality precedent.
- **Steps, in order:**
  1. Draft the fixture sets per language (en baseline + cs/es/ru/tr):
     grounded scenarios with known-citable English posts, tool-loop
     scenarios exercising searchPosts/semanticSearch/getPost/getReferences,
     and the code-switch / pressure probes the campaign showed
     discriminating (s07-class). Back-translation-verify per the M1-717
     protocol; record corrections.
  2. Write and LOCK the thresholds (acceptance item 1) before any arm runs.
  3. Render prompts through the real prompt builders against the pinned
     commit (P18, P20) — no idealized prompts.
  4. Run the arms: gemma-4-26b-a4b (the parametric bar-clearer) and the
     incumbent DeepSeek-V4-Flash (reference) minimum; grounded leg with
     English context AND with translated context (the A/B); tool-loop leg.
     Greedy decoding (the shipped configuration).
  5. Score: blind judge + reviewer round per the campaign protocol; hygiene
     columns beside the headline, never averaged; per-language verdict
     cells; the bar-clearing matrix stated per (model, language) pair.
  6. Record latency on the deployment box for both context arms.
  7. Promote the record to docs/measurement/direct-chat-e2e.md.
- **Controls to preserve (§10):** none rerouted — this ticket changes no
  code path. The record's own integrity rules (pre-registration, commit
  pin, voided-fixture disclosure) are the controls.
- **Pitfall→mitigation:** P7→the grounded leg's language-holding column is
  mandatory per cell (item 2); P15→the matrix is an explicit acceptance
  item (item 6); P18→steps 1-5 and items 1/7/8/9; P20→step 3's pin.

## Definition of done

The committed record carries: the pre-registered thresholds (committed
before results), the grounded per-language cells, the tool-loop
protocol-adherence table, the A/B verdict, the latency table, the
bar-clearing matrix per (model, language), the fixture-verification
decision log, the refusal re-check, and the commit pin — each verifiable
by its named grep probe — and mvn verify is green.

## Verification

- P7 → item 2's GROUNDED cells — feeds English-context prompts and asserts
  (via judge + reviewer) language holding; a whole-turn collapse is a
  recorded FAIL for that cell, never averaged away.
- P15 → item 6's matrix probe — the per-pair PASS/FAIL cells exist for
  every (arm × language) the registry could cite.
- P18 → items 1 (thresholds predate results), 7 (voided fixtures named), 8
  (commit pin), 9 (refusal re-check) — the record's own gates.
- P20 → item 8's pin — the recorded commit postdates every landed batch
  ticket touching the measured surfaces (start-time census in the P20
  entry).
- failure mode → items 3 and 6: a protocol-collapse scenario stays in its
  cell as a defect (never dropped), and an L0 defect forces FAIL regardless
  of a judgement tie — the campaign's zero-defect bar applied, not
  synthesized away.
- acceptance items 1-9 → the named `git log` / `grep` probes; item 10 →
  `mvn verify` from repo root.

## Out-of-scope

Named in `out_of_scope`: no code or spec change (the amendments are
M1-845/M1-846, the registry M1-848), no re-run of the final parametric
legs, no committed .bench data, no enablement of any model/language, no
languages outside the shipped set. If the grounded results falsify the
direct-mode premise for a language (e.g. gemma collapses with English
context), that is a RESULT, not a failure of this ticket — record it; the
siblings' scope shrinks accordingly and the user is told at the M1-845
wording review.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-844-streaming-translation-switch-1.md
```

## Round 1 rework

1. Finding 1: report the tool-loop leg's per-language judgement pass rates
   and discordant (D, L) counts in docs/measurement/direct-chat-e2e.md:163-180
   and make every matrix TOOL-LOOP cell (:222-226) follow from that reported
   data — verified via grep -n -i 'tool-loop'
   docs/measurement/direct-chat-e2e.md showing the tie-test data, with each
   PASS in the matrix's TOOL-LOOP column tracing to a reported, non-rejected
   sign test.
2. Finding 2: reconcile the tool-loop table's /7 denominators
   (docs/measurement/direct-chat-e2e.md:167-169) with the locked n=8 — either
   correct the denominators or add one sentence explaining the eighth
   scenario — verified via grep -nE '/7|/8'
   docs/measurement/direct-chat-e2e.md showing agreement with the lock or the
   explanatory line.
3. Finding 3: add the steady-state tok/s rate per arm to the latency table
   (docs/measurement/direct-chat-e2e.md:199-203) — verified via grep -n
   'tok/s' docs/measurement/direct-chat-e2e.md returning the new column or
   rate line.
4. Finding 4: reword the record header (docs/measurement/direct-chat-e2e.md:3-5)
   to a past-tense lock assertion naming commit d03f5d38 — verified via grep
   -n 'no result has been measured yet' docs/measurement/direct-chat-e2e.md
   returning nothing and grep -n 'd03f5d38'
   docs/measurement/direct-chat-e2e.md showing the reworded header.
