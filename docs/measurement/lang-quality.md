# Local-model language support for cs/es/tr/ru — chat feel + translation

**Date:** 2026-08-09 · **Status:** final record of the `.bench/lang-quality`
campaign (gitignored working data; this record is the promoted artifact).
Ancestors: `model-lang-coverage.md` (tokenizer screening),
`translator-slot.md`, `track-a-screening-in-progress.md`.

## Question

Can the fast local candidates conduct chat in the product languages
(cs/es/tr/ru) well enough to **simplify the English-pivot translation
pipeline**, and how good is their EN↔X translation? Two parts:

1. **CHAT FEEL** — DIRECT leg: conversing IN each language, parametric-only
   (no retrieval context), shipped system prompt minus the English-pin
   directive, shipped `UNTRUSTED_CONTENT` call shape with fixed delimiter
   ids, 4096-token runaway guard only (no lower hard cap).
2. **TRANSLATION** — EN↔X quality (CometKiwi primary, chrF secondary) on a
   1,283-row fixture (FLORES-200 native 600 · harvested real posts 203 ·
   synthetic EN 480).

**Arms:** qwen3.6-35b-a3b spec-off · same model with MTP (paired variant,
`--spec-type draft-mtp`) · gemma-4-26b-a4b · incumbent remote
DeepSeek-V4-Flash. **Bar (pre-registered, locked before runs):** tie the
incumbent on judgement + ZERO L0 defects, per language; MTP must additionally
tie its own spec-off baseline. Tie = one-sided exact binomial on discordant
scenario pairs, α=0.05 (track-a T2 convention).

## Fixtures

26 scenarios × 4 languages (cs 27 incl. s19b no-diacritics variant): 20
probe scenarios (one trap each: follow-up, recency, numbers, ambiguity,
unanswerable+pressure, code-switch, correction, multi-entity, exclusion,
provenance, opinion-bait, length-register, smalltalk, multi-part, domestic
angle, typo/colloquial, explain+ground) + 6 guided flows (5 scripted
steering turns each, factually anchored endpoints — drift/recovery testing).
Rendered via DeepL + round-trip check; cs natively reviewed; es/tr/ru
mechanically gated (reviewer not fluent — documented caveat). s07
code-switch turns constructed as DeepL clause + verbatim English clause;
s19 mechanically casualized.

## CHAT FEEL results

### L0 gates (mechanical, zero-defect bar)

| arm | defective turns | pattern |
|---|---|---|
| qwen-specoff | 12 | s07 code-switch → **whole turn in English in all 4 langs**; cs/tr s16 t2 English on pure-language input; tr refusal misfires (s13, s17, s19); ru g05 t2 runaway loop through the 4096 guard |
| qwen-mtp | 12 | same as specoff (incl. all four s07 collapses) + ru s15 t2 refusal; did NOT loop on ru g05 |
| deepseek (incumbent) | 8 | refusal-token misfires: cs s01 t2 + g01 t4, ru s01 t1+t2 + s07 t2 + s19 t1+t2, tr s06 t2 |
| gemma | 1 | cs s20 t2: `lockфайly` — Russian *файл* declensed in Czech, ×3 in 616 words; ruled a **minor blemish** under the severity amendment (see below) |

**Severity amendment (post-run, user authority, logged):** G1 graded —
whole-turn wrong language = hard defect; isolated foreign-script fragment in
an otherwise target-language reply = logged blemish, not cell-failing.
Applied uniformly it changed exactly one cell (gemma cs); qwen and incumbent
defects are all whole-turn and stay hard.

### Judgement (blind DeepSeek judge, temp 0; corrected per reviewer round)

| cell | pass % (Wilson 95%) | vs incumbent |
|---|---|---|
| qwen-specoff cs/es/tr/ru | 88.9 / 88.5 / 76.9 / 84.6 | TIE everywhere (p ≥ 0.062) |
| qwen-mtp cs/es/tr/ru | 81.5 / 92.3 / 76.9 / 84.6 | TIE everywhere (p ≥ 0.062) |
| **gemma cs/es/tr/ru** | **96.3 / 100.0 / 92.3 / 100.0** | **TIE everywhere (p ≥ 0.50)** |
| deepseek cs/es/tr/ru | 85.2 / 100.0 / 96.2 / 88.5 | — |

Corrections (reviewer round, user-ruled, applied uniformly): honest
parametric answers on s04/s13/s16 satisfy the honesty-framed requirements
(judge had failed "no count given" / "no source given" despite the
parametric-only design — 30 flips, no tie verdict changed); gemma/cs g05
failed on an invented player name (`الدورรินทร์ Dvalishvili`, reviewer-caught,
judge missed); vague-but-accurate LastPass figures on s10 passed (soft).
Refusal-token turns never benefited from flips (defect guard).

**Reviewer round:** reproducible judge + opencode/qwen3.8-max + codex/GPT,
blind labels; initial judge-vs-reviewer disagreement (45% cs slice, 24%
full sample) traced to the systematic s04/s13 over-strictness + one judge
miss, all resolved by user rulings; s08 judge verdicts upheld (models
repeated boilerplate instead of accepting corrections).

### Chat verdict per language (tie + zero defects)

| lang | qwen-specoff | qwen-mtp | gemma |
|---|---|---|---|
| cs | FAIL (L0) | FAIL (L0) | **PASS** (logged `файл` blemish) |
| es | FAIL (L0) | FAIL (L0) | **PASS** |
| tr | FAIL (L0) | FAIL (L0) | **PASS** |
| ru | FAIL (L0) | FAIL (L0) | **PASS** |

MTP tied spec-off on judgement but shares every L0 defect plus one → spec-off
remains the qwen configuration; the ≈1.2× MTP speed stays logged as a future
lever, not adopted.

## TRANSLATION results

CometKiwi (reference-free, primary), all arms 0 malformed outputs:

| arm | en2x | x2en |
|---|---|---|
| qwen-specoff | 0.877 ± 0.061 | 0.866 ± 0.037 |
| qwen-mtp | 0.878 ± 0.061 | 0.866 ± 0.035 |
| gemma | 0.878 ± 0.059 | 0.866 ± 0.040 |
| deepseek (incumbent) | 0.880 ± 0.059 | 0.868 ± 0.038 |

chrF (secondary, vs references): en2x 0.628–0.645, x2en 0.664–0.687, same
ordering. Per-language spreads ≤ 0.02 (ru x2en lowest for all arms:
0.853–0.854). **All three locals tie the incumbent** (differences ≤ 0.003,
well inside noise; zero defects) — MTP identical to spec-off to 3 decimals.

## Verdicts

1. **Chat simplification: YES for cs/es/tr/ru with gemma-4-26b-a4b.** Only
   arm clearing the bar; per-language, all four pass.
2. **Qwen is out on hygiene, not judged quality** — its judgement tied the
   incumbent everywhere, but whole-turn English collapses on code-switched
   input (all 4 languages, both decode paths), Turkish refusal misfires, and
   a runaway loop fail every cell at L0.
3. **Translation simplification is quality-supported for all three locals**
   (tie + zero defects both directions); task-level choice can weigh speed
   (qwen 58 tok/s vs gemma 48.5 raw decode) against the chat-leg findings.
4. **The incumbent itself fails its own hygiene bar in cs/ru/tr** — the
   `[REFUSAL:]` mechanism in the shipped prompt misfires without retrieval
   context (8 turns). Product finding: fixable in the prompt, not language
   incapacity.

## Findings worth keeping

- **Code-switch collapse is qwen-specific and systematic**: one embedded
  English clause flips the whole next reply to English, in every language,
  on both decode paths; gemma and the incumbent hold the scope language.
- **MTP non-losslessness concretized both ways**: ru s15 refusal appeared
  only with MTP; the ru g05 runaway loop appeared only with spec-off.
  Same weights, two flags, divergent greedy trajectories — decode mode must
  stay part of any cache key (D58 neighbour rule).
- **Register**: shipped ~461-word soft target mostly honored; qwen exceeded
  it on up to 11% of es turns, gemma on 0%. Effective chat speed (incl.
  prefill): gemma ~33 tok/s, qwen 41–52 tok/s; incumbent 57–81 tok/s (API).
- **Judge over-strictness on honest parametric answers** is a real hazard of
  LLM judging under parametric-only designs — the reviewer round (blind,
  multi-reviewer) caught it; keep reviewers in the loop for future campaigns.

## Caveats

- n = 26/27 per cell: tie = no significant evidence of inferiority; limited
  power, logged per pre-registration.
- es/tr/ru fixtures were DeepL-rendered + round-trip checked but not
  natively reviewed (campaign reviewer is cs-native); input phrasing is
  identical across arms, so comparison validity holds.
- Judge = incumbent (self-preference risk), mitigated by blind labels + two
  independent reviewers + human adjudication; all systematic disagreements
  user-ruled.
- Post-run amendments (severity grading; honesty-framing corrections) were
  made by the campaign owner, applied uniformly, and are logged with
  rationale in the campaign decision log (decisions 36–38).
- Parametric-only by design: grounded-chat behavior was not tested.

## Reproduce

Campaign data: `.bench/lang-quality/` (gitignored) — `README.md` (plan),
`DECISIONS.md` (38 decisions), `THRESHOLDS.md` (locked bar + amendment),
`RUBRIC-DRAFT.md`, `fixtures/`, `harness/` (gen/score/gate/judge/progress
scripts), `results/` (all outputs, scores, judgements, review artifacts).
