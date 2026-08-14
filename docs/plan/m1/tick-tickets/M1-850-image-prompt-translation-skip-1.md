---
id: M1-850
title: "Measure Krea native-prompt holding vs the translation leg"
status: pending
created: 2026-08-14
last_updated: 2026-08-14
flow: tick
reproduction: >-
  Probe: `grep -rni 'prompt-holding\|prompt holding\|krea'
  docs/measurement/` returns nothing, and the only multilingual
  image-prompt evidence in-tree is the single-scene table at
  docs/design/future/image-generation.md:306-310 — one 5-element scene, one
  image per (model, language) cell, cs/tr/es only (no ru), with a
  native-English reference arm rather than the production-translated
  English a non-en scope actually submits. Observed evidence gap: the
  2026-08-14 user direction (future-features.md §D6:455-489) gates the
  /image translation-skip flag on D5's bar-clearing rule ("measured, or
  the leg stays"), and no per-language prompt-holding campaign for Krea
  exists — live observation and a smoke table are the whole record.
analysis_ref: docs/plan/m1/tick-analysis/image-prompt-translation-skip.md
blocked_by: []
files_scope:
  - docs/measurement/image-prompt-holding.md
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production code change or docs/spec/** edit. This ticket produces
    evidence; the amendment and the flag it feeds are M1-851. No verdict
    here is a direction by itself (translator-slot.md's standing rule:
    evidence justifies a row; it never appears inside one).
  - >-
    RE-MEASURING Mage-Flow or Z-Image. §D6 keeps the translation leg
    mandatory for them regardless, and the 2026-08-07 table
    (image-generation.md:306-312) already recorded their degradation; this
    campaign's skip question is Krea-only.
  - >-
    COMMITTING the .bench working data (gitignored, the lang-quality
    posture). Only the promoted record lands at
    docs/measurement/image-prompt-holding.md.
  - >-
    ENABLING the skip for any model or tier — the per-(tier, language)
    matrix this record produces seeds M1-851's wizard table; nothing
    becomes eligible here.
  - >-
    Languages outside the shipped non-English set {cs, es, ru, tr} (plus
    the en reference arm) and model arms beyond the two Krea tiers' shipped
    encoder variants, unless the user adds one at start.
acceptance:
  - "Pre-registered thresholds lock BEFORE any arm runs: the committed record opens with the bar — per (tier, language): the native arm TIES the production-translated arm AND shows ZERO hard hygiene defects (whole-scene subject collapse, gibberish burned-in text), the tie statistic convention named (one-sided exact binomial on discordant scene-element pairs, α=0.05 — the track-a T2 convention lang-quality.md:24-26 used), hygiene columns beside the headline and never averaged — and `git log --follow --format='%h %ad %s' docs/measurement/image-prompt-holding.md` shows the thresholds commit predating every results commit (the lang-quality pre-registration posture)."
  - "Arms and cells recorded for cs/es/ru/tr plus the en reference: per language, arm A = the native prompt verbatim in the production-shaped Krea graph, arm B = the SAME prompt run through the production translation leg (the QueryAnchorTranslator prompt shape and fallback semantics on the deployment's translator routing — never a human reference translation, analysis P7) into the same graph — the campaign is the evidence for the per-model conditional that docs/spec/commands.md §Content's /image entry does not yet state; fixed seeds per scene across arms so the comparison isolates prompt language — probe: `grep -n 'arm' docs/measurement/image-prompt-holding.md` shows the per-language cell tables and the harness section naming the production translator shape used for arm B."
  - "Both Krea tiers' shipped encoder variants are measured as separate cells — krea_bf16's qwen3vl_4b_bf16 and krea_small's qwen3vl_4b_fp8_scaled (prod/scripts/4b-image.sh:106-107; the multilingual surface lives in the encoder, analysis discrepancy 2) — OR any tier left unmeasured is recorded explicitly as NOT skip-eligible — probe: `grep -n 'fp8\\|bf16' docs/measurement/image-prompt-holding.md` shows the per-tier cells or the exclusion note."
  - "Scene set and scoring discipline: the 2026-08-07 5-element scene protocol (image-generation.md:301-305) extended to a fixed set of scenes × seeds per cell, scored blind (shuffled arm labels) on element presence/faithfulness with a reviewer round (the lang-quality over-strictness lesson, lang-quality.md:139-141); a scene whose native arm collapses records a hard defect for that cell, NEVER dropped or averaged away (FAILURE-MODE discipline) — probe: `grep -n -i 'blind\\|reviewer\\|defect' docs/measurement/image-prompt-holding.md` shows the scoring protocol and the per-cell defect columns."
  - "Fixture discipline per the M1-717 protocol: non-English prompts native-authored where possible, otherwise machine-rendered and back-translation-verified with every correction recorded; a fixture failing verification is VOIDED, not scored — probe: `grep -n -i 'void' docs/measurement/image-prompt-holding.md` names any voided fixture and its correction in the decision log."
  - "The prior 2026-08-07 micro-measurement is cited as prior evidence with its limits named (single scene, one image per cell, cs/tr/es only, native-English reference arm — analysis discrepancy 1) — probe: `grep -n '2026-08-07' docs/measurement/image-prompt-holding.md` shows the citation and the limits paragraph."
  - "The bar-clearing matrix is stated per (tier, language) pair — the exact artifact M1-851's wizard skip table cites (analysis P12) — probe: `grep -n -i 'matrix' docs/measurement/image-prompt-holding.md` shows the per-pair PASS/FAIL cells, and a pair with any hard hygiene defect records FAIL regardless of a tie verdict (never averaged into a headline)."
  - "Translator-leg latency is recorded on the deployment box (the round-trip the skip saves — the direction's user-perceived gain, measured not assumed) — probe: `grep -n -i 'latency\\|round-trip' docs/measurement/image-prompt-holding.md` shows the table."
  - "The repo commit the whole run executed against is pinned in the record (the measured-surfaces-are-moving rule, translator-slot.md:69-71) — probe: `grep -n 'commit' docs/measurement/image-prompt-holding.md` shows the pin."
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
  - docs/spec/commands.md §Content
  - docs/spec/decisions.md §Decisions log
decision_refs:
  - D73
  - D75
  - D77
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

# M1-850: Measure Krea native-prompt holding vs the translation leg

## Context

The 2026-08-14 user direction (future-features.md §D6:455-489) ships a
per-model flag that lets a multilingual-capable image model skip the /image
prompt-translation leg — today Krea 2 only — and gates it on D5's
bar-clearing rule: measured, or the leg stays. The in-tree evidence is a
single-scene 2026-08-07 table (image-generation.md:306-310: Krea 5/5 en,
5/5 cs, 4.5/5 tr, 5/5 es) plus live observation — suggestive, but one image
per cell, no ru, and a native-English reference arm that is NOT what a
non-en scope submits today (production submits the translator's English
output). This ticket runs the per-language prompt-holding campaign the gate
names. Shared analysis: `analysis_ref:`.

## Root cause

An evidence gap, not a defect: the decision question — "does the output
reflect a native-language prompt as faithfully as the translated-English
one?" — has never been measured at campaign bar. The 2026-08-07 table
compared native-X against native-en (a scene-phrasing confound) and stopped
at three languages; the shipped set is cs/es/ru/tr (LanguageRegistry.java:73-78,
D43). Building M1-851's wizard table on the smoke table would repeat the
mistake the lang-quality campaign was commissioned to end — guessing model
behavior from a cheaper proxy.

## Pitfalls

Numbered per the analysis document; this ticket carries P6 (measurement
half), P7, P12.

- P6 (measurement half): the cell granularity is (tier, language) —
  krea_small's fp8-scaled qwen3vl_4b encoder (4b-image.sh:107) is a
  different artifact than krea_bf16's bf16 encoder (:106), and the
  multilingual surface lives in the encoder; a campaign that measures only
  one tier leaves the other unprovable. (The wizard-side half — the table is
  seeded only from PASS cells — is M1-851's.)
- P7: measurement discipline — pre-registered thresholds committed before
  arms run; fixtures native-authored or back-translation-verified with
  voids disclosed; blind scoring plus a reviewer round; hygiene columns
  never averaged; commit pinned; fixed seeds per scene across arms; and
  arm B is the PRODUCTION translation leg's output (QueryAnchorTranslator's
  exact prompt shape), never a human reference translation.
- P12: end-state calibration — the record's per-(tier, language) PASS/FAIL
  matrix is the artifact M1-851's wizard table cites verbatim; a record
  that reports only a headline verdict forces the sibling to re-run or
  guess.

## Approach

- **Files to touch:** `docs/measurement/image-prompt-holding.md` (new, the
  promoted record); the harness lives under `.bench/` (gitignored) per the
  lang-quality precedent.
- **Steps, in order:**
  1. Draft the fixture sets per language (cs/es/ru/tr + en reference):
     multi-element scenes extending the 2026-08-07 protocol, natively
     authored where possible, else machine-rendered and
     back-translation-verified per the M1-717 protocol; record corrections.
  2. Write and LOCK the thresholds (acceptance item 1) before any arm runs.
  3. Pin the repo commit; render arm A (native prompt verbatim) and arm B
     (the same prompt through the production QueryAnchorTranslator prompt
     shape on the deployment's translator routing) into the
     production-shaped Krea graph for BOTH tiers' encoder variants, fixed
     seeds per scene across arms.
  4. Score blind (shuffled arm labels) on element presence/faithfulness,
     with a reviewer round; hygiene columns beside the headline, never
     averaged.
  5. Record the translator-leg latency on the deployment box.
  6. State the per-(tier, language) PASS/FAIL matrix; promote the record to
     docs/measurement/image-prompt-holding.md.
- **Controls to preserve (§10):** none rerouted — this ticket changes no
  code path. The record's own integrity rules (pre-registration, commit
  pin, voided-fixture disclosure, blind scoring) are the controls.
- **Pitfall→mitigation:** P6→item 3's per-tier cells-or-exclusion; P7→steps
  1-4 and items 1/2/4/5/9; P12→item 7's matrix.

## Definition of done

The committed record carries: the pre-registered thresholds (committed
before results), the per-language × per-tier cells for both arms with fixed
seeds, the blind-scoring + reviewer-round protocol, the fixture decision log
with voids, the 2026-08-07 citation with its limits, the per-(tier,
language) PASS/FAIL matrix, the translator-leg latency table, and the
commit pin — each verifiable by its named grep probe — and mvn verify is
green.

## Verification

- P6 → item 3's probe — per-tier cells exist for both shipped encoder
  variants, or the unmeasured tier is recorded NOT skip-eligible.
- P7 → items 1 (thresholds predate results), 2 (arm B is the production
  translator shape), 4 (blind + reviewer round), 5 (voids named), 9 (commit
  pin) — the record's own gates.
- P12 → item 7's matrix probe — per-pair PASS/FAIL cells exist for every
  (tier, language) pair M1-851's wizard table could cite; a hard defect
  forces FAIL regardless of a tie.
- failure mode → item 4: a collapsed native-arm scene stays in its cell as
  a hard defect, never dropped and never averaged into a headline — the
  zero-defect bar applied, not synthesized away.
- acceptance items 1-9 → the named `git log` / `grep` probes; item 10 →
  `mvn verify` from repo root.

## Out-of-scope

Named in `out_of_scope`: no code or spec change (the amendment and flag are
M1-851), no Mage-Flow/Z-Image re-measurement (they keep the leg per §D6),
no committed .bench data, no skip enablement, no languages outside the
shipped set. If the results falsify the skip premise for a language or a
tier (e.g. krea_small's fp8 encoder collapses on cs), that is a RESULT, not
a failure of this ticket — record it; M1-851's wizard table shrinks
accordingly, and if Krea fails wholesale M1-851 is abandoned at start and
the user is told with the record in hand.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-850-image-prompt-translation-skip-1.md
```
