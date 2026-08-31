# Re-arm winners screening — 2026-08-30/31 (max-optimized configs, owner directive)

Companion to [track-a-screening-in-progress.md](track-a-screening-in-progress.md):
the two arms marked **§** in its §3.1/§4 tables (qwen3.8-flash-rfk-mtp,
ling-3.0-flash-rfpx) are measured and documented HERE; that file's tables
carry their rows, this file carries their provenance, the re-arm
battery-frame anchors, and the readings.

**Status: evidence, not spec.** Same rule as the companion doc: nothing may
be built from this file; it records measurements only.

## Why these two arms, and why not the run-1 shape (owner directive)

Owner directive (2026-08-30, before measurement): today's candidates are
measured on **their configuration — max optimized**, the exact shape each
won its speed battery on — because "an average setup is a biased,
non-existing configuration the model will never run on." Both arms carry
their own runtime forks and sampling pins (serve commands inlined below —
this section is the committed record; the bench working copy is gitignored
and may not exist in a fresh clone). They are NOT run-1-common-shape rows,
and cross-arm sampling is not identical — the same recorded-divergence
class as the run-1 qwen arm.

```
# qwen3.8-flash-rfk-mtp — apepojken fork build 10682 @ 843d575 (Vulkan)
llama-server -m Qwen3.8-Flash-Next-UD-IQ4_XS-00001-of-00003.gguf \
  --device Vulkan0 -ngl 999 -fa on --no-mmap --jinja --reasoning off \
  --reasoning-budget 0 -c 32768 --parallel 1 -ctk q8_0 -ctv q8_0 \
  --temp 0.7 --top-p 0.8 --top-k 20 --min-p 0 --presence-penalty 1.5 \
  --spec-type draft-mtp -md jockevaupptaget-mtp-Q8_0.gguf \
  --spec-draft-ngl 999 --spec-draft-n-max 6 --spec-draft-p-min 0.75

# ling-3.0-flash-rfpx — charlie12345/ROCmFPX @ c49ebdb (Vulkan), MTP off
llama-server -m Ling-3.0-flash-ROCmFP4-STRIX-MTP-Q4_0-00001-of-00002.gguf \
  --device Vulkan0 -ngl 999 -fa on --no-mmap --jinja -c 32768 --parallel 1 \
  -fit off --temp 0.6 --top-p 0.95 --top-k 20 \
  --chat-template-kwargs '{"enable_thinking":false}'
```

### Battery-frame anchors — ALL re-arm qwen arms + bar (qwen38-rearm campaign, 2026-08-30/31; 1200-tok 5-rep probe batteries. All numbers inlined here — this is the committed record of them. Provenance rule: these anchor, the §-marked screening rows in the companion doc decide)

**Reference bar (not qwen):** I0 gemma Q6+MTP at prod shape = **46.5 t/s
decode p50** (~20 s / 1200-tok reply) — every row below is judged against this.

*Bare legs (valid on every runtime):*

| battery arm | runtime | decode t/s p50 (prose) | note |
|---|---|---|---|
| A0 — unsloth IQ4_XS bare | R-main `cc231cb` | 23.2 | the 08-27 merge moved decode nothing (run1 22.6); prefill +46% |
| a1-basewn — IQ4_XS bare | R-nw b10639 (quartet) | 23.6 | runtime-neutral baseline; quartet memory shape far better |
| A3 — ROCmFP4-FAST imatrix-v2 bare | R-lb `337c8bb` | 26.1 | author's own bare prior 25.3 — lands on it |
| rlb-bare — IQ4_XS bare | R-lb | 25.4 | agentionai card cmd minus spec |
| rfk-bare — IQ4_XS bare | R-fk `843d575` | 26.5 | fork's fused mat-vec beats every other runtime's bare |
| rfkq2-bare — UD-Q2_K_XL bare | R-fk | 31.3 (dead steady) | bytes-line endpoint: +18% over IQ4_XS, slope ~0.32 t/s per GB |
| rm-ling-bare — Ling bartowski Q4_K_M bare | R-main | 39.3 | fork-free = 85% of bar |

*Speculative legs on the OLD (pre-fix) runtimes — ⚠ PR-27742-lineage
contamination, superseded by the corrected-runtime rows; drift history
only, never quote as a current result:*

| battery arm | runtime | decode t/s p50 | note |
|---|---|---|---|
| A0b — + ngram-mod | R-main | 23.3 | wash — prose has no 24-tok repeats to mine |
| A0c — + `--lazy-mode on` | R-main | 23.6 | wash — the "36 t/s SSD offload" rumor does not reproduce |
| A1-ex — + easiix Q8_0 (n-max 4) | R-nw | 25.0 (+6%) | acceptance on-card 0.63; contaminated spec path |
| A1-dz — + dzannotti Q4_K_M (n-max 3) | R-nw | 27.0 (+15%) | OLD best qwen arm; contaminated spec path |
| A2 — imatrix-v2 + FAST head | R-lb | 23.2 (−11% vs own bare) | inversion — now understood, see rlb-mtp |
| rlb-mtp — IQ4_XS + agentionai Q8_0 (full-precision control) | R-lb | 22.5 (−11% vs own bare 25.4) | proves the inversion is R-lb's spec-path property on this box |
| rfk-mmap — rfk-mtp config, `--no-mmap` dropped | R-fk | 25.3 | A/B: the mmap path costs 25% of spec decode (spec still engaged) |

*Speculative legs on CORRECTED runtimes (valid):*

| battery arm | runtime | decode t/s p50 | note |
|---|---|---|---|
| rfk-mtp — IQ4_XS + jockeva Q8_0 (n-max 6) | R-fk | **33.8 prose / 37.5 code** | best stable Qwen arm, acc ~0.80, +28%/+42% vs same-build bare |
| rfkq2-mtp-32k — Q2_K_XL + jockeva @ `-c 32768` | R-fk | 29.7 (28.2–45.3, bimodal) | MTP not a stable bump on Q2; fast reps (acc 0.90) hit 45.3 |
| rm-ling-mtp — Ling bartowski + merged head | R-main | 40.4 (+2.8% vs own bare) | acceptance ceiling ~0.71 on our prose shape |
| rfpx-ling-mtp — Ling raulvidis + depth-2 MTP | ROCmFPX `c49ebdb` | 40.7 (−12% vs own bare) | card MTP claim does not transfer; ×24 replay-stall draft drops |

*Dense Qwen3.8-27B D-arms (valid, R-main; separate model class — bandwidth-taxed):*

| battery arm | config | decode t/s p50 | note |
|---|---|---|---|
| D1-bare | UD-Q8_K_XL (30 GB) | 7.2 | FP4-based "bare 14-28" priors did not apply to Q8_K_XL |
| D1-mtp | merged MTP head, n-max 3 | 12.5 (+74%) | merged-head MTP works fork-free |
| D1-s / d1s7 | zlab DFlash2, n-max 8 / 7 | 12.0 / 12.2 | the n-max≤8 cliff is qwen4exp-specific — dense dflash unaffected |

*Ling bar-class bare (the one arm at the bar):*

| battery arm | runtime | decode t/s p50 | note |
|---|---|---|---|
| rfpx-ling-bare — raulvidis ROCmFP4-FAST, MTP off | ROCmFPX | **46.3** | AT the bar; MTP OFF is its measured optimum |

### Screening rows — live in the companion doc (§3.1/§4, marked §)

Both arms ran the full 590-call fixture set on 2026-08-31 with the same
scorers; their pass-rate and speed/resource rows are in the main tables
track-a-screening-in-progress.md (marked §), not duplicated here.

### Readings

- **T6 interactive floor (40 t/s): Ling PASSES (47.1)** — the first
  candidate at incumbent-class speed since the run-1 survivors; TTFT 1432 ms
  beats every run-1 arm. qwen-rfk-mtp FAILS (26.3; run-1 qwen was 22.6 — the
  fixed fork moves it +16% at screening shape, not over the floor; short
  task bursts amortize MTP worse than the 1200-tok battery).
- **qwen sum-body collapsed 23.3% → 0/30**: 29/30 replies are not the
  required bare-JSON `{"summary": …}` shape — free-prose answers opening
  with refusal-style text ("I cannot fulfill the request to 300-character
  limit…"); the one parseable reply failed the language check. Audited
  2026-08-31 against the scorer source (`score_summarizer_body`: JSON
  parse first, content checks on the extracted summary; parse-fail
  defaults content flags True as not-the-reason): the scorer is sound and
  discriminates — gemma 86.7% (over-length only), Ling 26.7% (over-length
  only), qwen 0% (shape violation). A real instruction-following reading
  at this config, not a harness fault.
- Ling's quality is mid-field despite the speed: tagger 28.6% is the
  weakest of any speed-survivor-class arm; entity noisy (14 extras); judge
  70.8% with **zero gate violations** (eligible, unlike laguna/nemotron)
  but below the field's 75–83; chat 65.4 below gemma's 73.1.
- Judged (claude-opus-5) convey-predicate pass **not bought this round** —
  T3 saturation doctrine (sum-prose/compress at ceiling; qwen sum-body
  mechanically dead at 0%) — available as a follow-up if a slot decision
  needs it. rank.py significance tests also not re-run; §5 (companion) leadership
  claims below are mechanical-only like run-1's.
- Per-slot (mechanical, untested for significance): qwen-rfk-mtp takes
  tagger 58.9 (vs gemma 57.1) and entity 76.7; Ling's only above-gemma
  slot is grouping-adjacent none — its case rests entirely on speed +
  sum-body-not-collapsing.

### The four recovered † rows change two run-1 readings (2026-08-31)

The later-round arms added to the companion doc's §3.1/§4 tables were fully benched (275 calls,
247 scored quality cases each, same fixtures/scorers) but never written
into this file — recovered from the gitignored bench working copy on
2026-08-31; the rows above are now the committed record of them:

1. **Sum-body local leader is now hauhau-gemma-4-26b-qat at 96.7% (29/30)**
   — above glm-reap's 90.0% and far above gemma's 86.7%; its MTP twin ties
   it. The strongest sum-body result the bench has produced.
2. **Decode crown: hauhau-qat 59.6 t/s** (prefill 716.9, TTFT 658 ms) —
   above qwen3.6-35b-a3b's 58.0, at **15.6 GiB** file size and 41.2 GiB GTT
   (the lightest T6-surviving footprint measured), quality 173/247 vs
   gemma's 176/247, judge-eligible ✓. Caveat for any slot decision: it is
   the HauhauCS-Balanced UNCENSORED build (abliterated refusal behavior —
   the same class the owner flagged as a non-starter for prod in the
   LongCat discussion; recorded here as measurement, not candidacy).
3. pliny-qwen3.8-27b-mtp (dense obliterated 27B): full bench exists —
   21.0 t/s (T6-killed), judge ⚠ disqualified (2 `not_released`
   violations), 149/247. Its spec-off pair is NO DATA (aborted, 6 calls).
4. qwen3.8-flash-next (the original IQ4_XS arm on the PR-27742 runtime):
   entity 83.3% remains the field's best, but sum-body 23.3% and the
   contaminated runtime lineage — superseded by qwen3.8-flash-rfk-mtp
   (the §-marked screening rows in the companion doc) on the corrected fork.

## Q2 arm addendum (owner-approved 2026-08-31, third § arm)

`qwen3.8-flash-q2-rfk-bare` — unsloth UD-Q2_K_XL (78.9 GB, KLD 0.2246) on
the apepojken fork, BARE (Q2's optimum: MTP was not a stable bump on Q2 —
29.7 bimodal p50 vs 31.3 bare), unsloth instruct sampling row baked
server-side (owner-requested "unsloth recommended config"; their page pins
sampling only). Owner chose to keep R-fk over R-main after the tradeoff
was put to them (fork = max-optimized per standing directive vs
R-main = the model's native environment; quality expected
runtime-insensitive, speed ~+14% on the fork).

```
llama-server -m Qwen3.8-Flash-Next-UD-Q2_K_XL-00001-of-00003.gguf \
  --device Vulkan0 -ngl 999 -fa on --no-mmap --jinja --reasoning off \
  --reasoning-budget 0 -c 32768 --parallel 1 -ctk q8_0 -ctv q8_0 \
  --temp 0.7 --top-p 0.8 --top-k 20 --min-p 0 --presence-penalty 1.5
```

Screening (run 1, 590 calls): decode **30.7 t/s** serving-shape (fastest
qwen arm at this shape; T6 still fails < 40), prefill 182.1, TTFT 2667 ms,
load 62 s, GTT 46.9 GiB, 0 CPU-fallback. Quality: tagger 50.0%, classifier
40.0%, entity 76.7%, judge 75.0% ⚠ (1 `not_released` violation —
disqualified, unlike the IQ4 arm), **sum-body 3.3%** (1/30), sum-prose
100%, sum-rollup 42.9%, chat 88.5%, compress 100% (probe **0.741** — the
qwen family's best), grouping 43.8%.

**Reading:** the sum-body JSON-shape failure is **quant-independent** —
1/30 at Q2 vs 0/30 at IQ4_XS on the same fork family and sampling. The
over-refusal/format breakdown is a property of the model+config on these
injection-trap fixtures, not of quantization; no qwen configuration
measured (5 arms across 4 runtimes, 2 quants, spec on/off) passes this
slot. Judge-slot eligibility, unlike IQ4, is lost at Q2 (1 violation).
