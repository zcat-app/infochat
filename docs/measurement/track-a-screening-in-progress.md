# Track A slot-screening results — in progress

**Status: SCREENING ROUND 1 (run 1 of 2) IS MEASURED; THE SLOT DECISION IS NOT
CLOSED.** Nine local arms measured against the deployed incumbent on the full
Track A fixture set, plus three arms blocked by the pinned runtime. Every number
below is a run-1 mechanical reading with **no T5 noise floor (run 2) and no
judged predicates** — see §6 for what this round does not settle. This record is
an incomplete measurement kept because a decision that sits on it is
half-supported: the leaders (§5) are candidates, not answers.

Companion documents: the authoritative working copy and harness live in
`.bench/track-a/` (gitignored); `.bench/SESSION-HANDOFF.md` §0W is the running
record of the campaign, §0T–§0U define the scoring model (gate/decision split,
T1–T8) this file reads numbers through, and §0V is the round protocol
(generation + free stats only, **no judge LLM** — user decision 2026-08-03).

**Status: evidence, not spec.** This file records how a decision may eventually
be reached; it is not itself a direction and nothing may be built from it. The
spec states directions and is the source of truth — no spec row cites this file,
and none should.

---

## 1. What is being measured

Track A's goal (§0C D3): **which model per task** — each of the seven
`ModelTask` slots (tagger, classifier, entity, security-judge, summarizer×3
shapes, chat, compress) resolved by `infochat.llm.<task>.model` is a separate
decision, measured against the model production actually runs. GROUPING (the
deterministic cluster-header layer) is a derived fixture: it measures
tagger+classifier+entity through the frozen `grouping.py` chain.

| slot | metric (decision / graded) | n |
|---|---|---|
| TAGGER/primary | pass/fail (exact tag set) | 40 |
| TAGGER/fallback | promoted Jaccard (T8) | 16 |
| CLASSIFIER | pass/fail | 35 |
| ENTITY | pass/fail (recall) | 30 |
| SECURITY_JUDGE | pass/fail + `not_released` hard-fail gate | 24 authored (52 after panel gold) |
| SUMMARIZER/body | mechanical (300-char cap etc.) | 30 |
| SUMMARIZER/prose | pass/fail | 14 |
| SUMMARIZER/rollup | pass/fail | 14 |
| CHAT_AGENT | pass/fail | 26 |
| COMPRESS | promoted probe-hit ratio (T8) | 18 |
| GROUPING | promoted pairwise F1 (T8) | 16 scenarios (315 ingest calls) |

T1–T8, the gate/decision split and the G1/G2 growth rules are pre-registered in
`.bench/track-a/TRACK-A-THRESHOLDS.md` and were **not** amended after any arm
ran.

---

## 2. Run context

| | |
|---|---|
| repo commit | **`d1d93b68`** (pin per handoff §7; re-pin and re-run if arms are compared across a code change) |
| runtime | native `llama-b10221` (`b10221-815a2a591`), Vulkan / RADV STRIX_HALO, 1.0 GiB VRAM carve-out + 124 GiB GTT |
| server flags | `-ngl 999 --parallel 1 -c 8192 --reasoning off --reasoning-budget 0` — **NO `--temp 0`** (default sampling, `_RESOLVED_temperature`: T5's noise floor would be vacuous for a deterministic local arm) |
| harness | `serve.py` → `run-arm.py` (generation only, resume-safe, raw input+output stored) → `mechanical.py` (free scorer, gate/decision split) → `t3-sweep.py` → `profile.py` → `rank.py` (T1/T2/T4/T8) |
| fixtures | full Track A set, **590 calls/arm** (275 scored cases + 315 grouping ingest calls); judge's 40 `not-scored` excluded by design (R4) |
| judged predicates | **NOT RUN** (user decision: round 1 is generation + free stats only — no claude-opus-5 spend, no gold panel) |
| costs | all local, free; ~5,300 generation calls across the campaign |

### Reproducing

```bash
cd .bench/track-a
python3 serve.py --arm <arm> --task all     # generate + resources (local arms)
python3 mechanical.py --arm <arm> --task all # free scoring
python3 t3-sweep.py --arm <arm>              # T3 reading
python3 profile.py --tsv                     # one row per arm
python3 rank.py --task all                   # pairwise significance vs incumbent
```

---

## 3. The arms

### 3.1 Measured — pass rates, speed, gates

| arm | quant | GiB | dec. tok/s | tagger | class. | entity | judge | sum-body | prose | rollup | chat | compress (probe) | grouping |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **incumbent** (remote DeepSeek-V4-Flash) | full/API | — | — | 35.7% | 40.0% | 53.3% | 83.3% ✓ | 63.3% | 100% | 85.7% | 80.8% | 100% (0.69) | 6.2% |
| glm-4.7-flash | UD-Q6_K_XL | 24.4 | 52.3 | 32.1% | 40.0% | **73.3%** | 70.8% ⚠ | 80.0% | 100% | 50.0% | 76.9% | 100% (0.63) | 31.2% |
| glm-4.7-flash-reap | UD-Q6_K_XL | 18.8 | 52.3 | 25.0% | 40.0% | 60.0% | 79.2% ⚠ | **90.0%** | 92.9% | 57.1% | 80.8% | 100% (0.65) | 25.0% |
| qwen3.6-27b (dense) | UD-Q6_K_XL | 24.2 | 8.9 | 41.1% | 42.9% | 56.7% | 79.2% ⚠ | 53.3% | 92.9% | 42.9% | 88.5% | 94.4% (0.71) | **50.0%** |
| qwen3.6-35b-a3b | UD-Q6_K_XL | 30.4 | 58.0 | 41.1% | 40.0% | 56.7% | 83.3% ✓ | 66.7% | 92.9% | 71.4% | 88.5% | 100% (0.64) | 12.5% |
| gemma-4-26b-a4b | UD-Q6_K_XL | 21.7 | 48.5 | **57.1%** | **51.4%** | 63.3% | 83.3% ✓ | 86.7% | 100% | 71.4% | 73.1% | 100% (0.70) | 25.0% |
| laguna-s-2.1 | UD-Q4_K_XL | 68.4 | 30.6 | 23.2% | 48.6% | 60.0% | 79.2% ⚠ | 10.0% | 100% | 71.4% | 61.5% | 100% (0.71) | 43.8% |
| nemotron-3-super-120b | UD-Q3_K_XL | 58.3 | 19.3 | 33.9% | 45.7% | 56.7% | 75.0% ⚠ | 33.3% | 92.9% | 71.4% | 88.5% | 100% (0.68) | 31.2% |
| qwen3.5-122b-a10b | UD-Q4_K_XL | 73.2 | 23.2 | 39.3% | 48.6% | 66.7% | 83.3% ✓ | 43.3% | 92.9% | 64.3% | **96.2%** | 100% (**0.72**) | 18.8% |
| deepseek-v4-flash-local | UD-IQ3_XXS | 95.9 | 12.5 | 7.1% | 22.9% | 43.3% | 45.8% ⚠ | 66.7% | 92.9% | 85.7% | 57.7% | 27.8% (0.29) | 18.8% |

✓ = zero `not_released` gate violations (judge slot eligible). ⚠ = ≥1
`not_released` violation — **judge slot disqualified** (released content gold
quarantines; a hard-fail gate, exempt from the window arithmetic).

### 3.2 Blocked on the pinned runtime — not quality readings

| arm | quant | GiB | blocker |
|---|---|---|---|
| step-3.7-flash | UD-Q2_K_XL | 61.3 | `step35` architecture: `--reasoning off` is inert on b10221 — the model thinks on every call (18/18 tagger rows came back empty, `finish_reason: length`, ~4k reasoning chars). StepFun's own llama.cpp fork is CUDA/Metal-oriented. |
| inkling-small | UD-IQ3_XXS | 91.2 | `error loading model: unknown model architecture: 'inkling'` on b10221. Unsloth's `b10225-mix` build ships a Vulkan binary with the TML-Inkling patch — untried. |
| gpt-oss-120b | Q6_K | 58.9 | native peg-native output wrapper (`<|channel|>final <|constrain|>JSON<|message|>…`) 500s in b10221's parser on ~80% of long outputs; **4 of 10 task sets measured**: tagger 30.4%, classifier 40.0%, entity 56.7%, judge 75.0% ✓ |

### 3.3 Deprioritised (dense architecture — §3.4)

gemma-4-31b, ornith-1.0-35b, gemma-4-12b-obliterated, qwen3.6-27b-fable,
mistral-small-4-119b — not run.

---

## 4. Speed and resources

T6 (pre-registered): chat/compress needs **≥ 40 tok/s decode**; speed is a
constraint, not a term.

| arm | prefill tok/s | decode tok/s | TTFT ms | load s | peak GTT GiB | CPU-fallback ops |
|---|---|---|---|---|---|---|
| glm-4.7-flash | 545.8 | 52.3 | 581 | 6.0 | 24.2 | 0 |
| glm-4.7-flash-reap | 623.0 | 52.3 | 504 | 14.0 | 18.6 | 0 |
| qwen3.6-27b | 121.0 | **8.9** | 4005 | 6.0 | 22.8 | 0 |
| qwen3.6-35b-a3b | 402.3 | **58.0** | 1191 | 20.0 | 29.2 | 0 |
| gemma-4-26b-a4b | 394.4 | 48.5 | 1263 | 18.0 | 21.8 | 0 |
| laguna-s-2.1 | 219.5 | 30.6 | 2083 | 82.1 | 68.2 | 0 |
| nemotron-3-super-120b | 134.6 | 19.3 | 3437 | 42.1 | 57.8 | 0 |
| qwen3.5-122b-a10b | 196.3 | 23.2 | 2339 | 84.4 | 71.0 | 0 |
| deepseek-v4-flash-local | 95.3 | 12.5 | 4760 | 124.6 | 96.1 | **4** |

**T6 kills the interactive slots for:** qwen3.6-27b (8.9), deepseek-local
(12.5), nemotron (19.3), qwen3.5-122b (23.2), laguna (30.6). Surviving:
qwen3.6-35b-a3b, glm, glm-reap, gemma-4-26b-a4b.

---

## 5. Per-slot leaders (run-1 mechanical only — see §6)

| slot | incumbent | local leader | runner-up |
|---|---|---|---|
| tagger | 35.7% | **gemma-4-26b-a4b 57.1%** (Jacc 0.808, 5 gates) | qwen3.6-27b / qwen3.6-35b-a3b 41.1% |
| classifier | 40.0% | **gemma-4-26b-a4b 51.4%** | laguna / qwen3.5-122b 48.6% |
| entity | 53.3% | **glm-4.7-flash 73.3%** (extras 19, over 1) | qwen3.5-122b 66.7% (recall 0.778, extras 11) |
| judge | 83.3% | tie **qwen3.6-35b-a3b / gemma / qwen3.5-122b 83.3%**, all ✓ | gpt-oss 75.0% ✓ (partial arm) |
| sum-body | 63.3% | **glm-reap 90.0%** | gemma 86.7% |
| sum-prose | 100% | tie 100% (glm, gemma, laguna, incumbent) | — |
| sum-rollup | 85.7% | **incumbent holds** (best local 71.4%) | — |
| chat | 80.8% | **qwen3.5-122b 96.2%** | qwen3.6-35b-a3b / nemotron 88.5% |
| compress | 100% (probe 0.69) | probe **qwen3.5-122b 0.718** | laguna 0.708 |
| grouping | 6.2% | **qwen3.6-27b 50%** (F1 0.667) | laguna 43.8% |

The one significance test run so far (glm-4.7-flash, `rank.py`): **GROUPING
candidate wins** vs incumbent (9–0 discordant, p=0.0039, Holm ✓); TAGGER/fallback
loses (0–11, p=0.001); SECURITY_JUDGE disqualified; all other rankable slices
keep incumbent (T2).

---

## 6. What these numbers do NOT settle

- **Nothing is a slot decision yet.** Every leader above is run-1 only, with **no
  run 2 (T5 noise floor)** — between-arm discordance has not been checked against
  either arm's own within-arm variance, and no significance test exists for any
  arm except glm-4.7-flash's grouping. Run 2 + `rank.py` is owed for the leaders
  (gemma-4-26b-a4b, qwen3.6-35b-a3b, qwen3.5-122b-a10b, glm-4.7-flash) and the
  incumbent.
- **No judged predicates were run.** `must_convey` / `must_not_convey` /
  `not_released`-beyond-the-mechanical parse are unmeasured; summarizer-body's
  promoted claim-level metric (T3 reading) is therefore unread. The judge gate
  (`not_released`) IS measured mechanically and disqualifies 5 of 9 arms, but the
  judge slot's positive ranking is gated on **panel gold for the 28**
  `pending-panel` cases (n 24 → 52) and on batching `score-judged.py` by case
  (the §0U ⛔ STOP).
- **SECURITY_JUDGE and several slices are saturated at the incumbent** (T3), so
  no candidate result may be read as a ranking there until the panel lands or G2
  growth is applied.
- **deepseek-v4-flash-local's collapse is a quant result, not a model result.**
  The IQ3_XXS build (43/56 schema-violating tagger attempts) is the same model as
  the incumbent at ~3 bits; it cannot be read as "DeepSeek is bad" — it is
  evidence about 3-bit compression on this architecture.
- **gpt-oss-120b is half-measured** (4 of 10 task sets); its leaderboard rows are
  incomplete and its judge row rests on 18/24 like the others.
- **The dense arms are unmeasured.** The 2026-08-03 finding (qwen3.6-27b at 8.9
  tok/s, 99% CPU) disqualified dense candidates by throughput; the six remaining
  dense GGUFs were deprioritised by that decision, not by measurement.
- **Absolute quality.** Pass rates are against hand-authored trap-heavy gold; no
  arm's absolute correctness is claimed beyond the fixture's own semantics.
- **Speed is machine-specific.** tok/s numbers hold on this Strix Halo box
  (1 GiB carve-out + 124 GiB GTT, Vulkan); they are decision-grade here and
  indicative elsewhere.

---

## 7. Run log

| date | what |
|---|---|
| 2026-08-03 | Round 1, glm-4.7-flash (first candidate): 589/589 calls, all `finish=stop`; `rank.py` reading (GROUPING win, fallback loss, judge disqualification). |
| 2026-08-03 | glm-4.7-flash-reap: prune hurts tagger (25%, 21 gates) and grouping (25%); sum-body 90% (best). |
| 2026-08-03 | qwen3.6-27b (dense): **finding — dense models unusable at this size** (8.9 tok/s, 99% CPU, ~40-min round). Best grouping so far (50%). |
| 2026-08-03 | qwen3.6-35b-a3b: first **clean judge** (83.3%, 0 gates); chat 88.5%. |
| 2026-08-03 | gemma-4-26b-a4b: best quality profile (tagger 57.1%, classifier 51.4%, clean judge, probe 0.70). |
| 2026-08-03 | step-3.7-flash **skipped** (reasoning cannot be suppressed on b10221; 18/18 thinking-burn rows quarantined). inkling-small **skipped** (unknown architecture). |
| 2026-08-03 | laguna-s-2.1: sum-body 10% — all 27 failures are the 300-char cap (summaries 409–1142 chars). |
| 2026-08-03 | nemotron-3-super-120b: mid quality, 19.3 tok/s (< T6 floor), judge disqualified. |
| 2026-08-03/04 | qwen3.5-122b-a10b: chat 96.2% (best of all arms), clean judge, 23.2 tok/s (< T6 floor). |
| 2026-08-03/04 | deepseek-v4-flash-local: **quality collapse** (tagger 7.1%, 43/56 schema violations); two peg-native 500 crashes mid-grouping → **`llm.py` gained a bounded HTTP-500 retry (3×)**; round completed. |
| 2026-08-04 | gpt-oss-120b **blocked** — peg-native wrapper 500s (~80% of long outputs); 4 of 10 task sets measured. |

### Harness changes made during the round (all gitignored bench code)

- `profile.py`: timing keys fixed to b10221's shape (`prompt_per_token_ms` /
  `predicted_per_token_ms`, old names as fallback) — speed stats now render.
- `llm.py`: bounded HTTP-500 retry (3×, escalating 2 s backoff, then
  InfraFailure) for stochastic peg-native grammar rejections; 401/403/429 still
  halt immediately (lesson 4).
- `serve.py` health-wait hang after an immediate model-load failure: kill
  `serve.py` by PID (the llama-server child exits on its own) — recorded in the
  handoff so it is not re-discovered.
