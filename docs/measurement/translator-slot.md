# Translator-slot measurement results

**Status: THE MODEL QUESTION IS ANSWERED.** Rounds 1–3 complete: nine arms,
both legs each, including the deployed incumbent. **Nothing beats the model
already in the `translator` slot** — see §5. Numbers are final for every arm
listed; an arm is either measured or absent, never estimated.

Still open, and none of it blocks the D29/D58 amendments: entity preservation
(gated on the 248-post hand review), reference-free QE, and the local-vs-remote
DeepSeek quantisation pair.

**What decides what:** this measurement answers *which model fills
`ModelTask.TRANSLATOR`*, not "which MT model is best". Half the arms are
general-purpose models, deliberately: `ModelTask.TRANSLATOR` already exists as a
routed slot with its own `infochat.llm.translator.model` key, so a dedicated MT
model has to beat **the model already in that slot**, not beat nothing. If a
generalist already in the deployment matches the specialists, the right outcome
is to ship no new model at all.

Companion documents: `SESSION-HANDOFF.md` §0D (the English-pivot decision these
numbers gate), §0H (metric design and the three hygiene columns), §0I (the arm
queue this executes). Embedder-side counterpart:
`EMBEDDER-MEASUREMENT-RESULTS.md`.

**Status: evidence, not spec.** This file records how a decision was reached; it
is not itself a direction and nothing may be built from it. The spec states
directions and is the source of truth — **no spec row cites this file, and none
should.** Evidence justifies a row; it never appears inside one. Read
`docs/spec/decisions.md` D29 and D58 for what the system must do.

Working copy and harness live in `.bench/m1-717/` (gitignored). This is a
snapshot taken when the model question closed.

---

## 1. What is being measured

The English pivot adds **two new translation legs** to the architecture, with
opposite latency/quality profiles. They are measured separately and never
averaged:

| leg | direction | volume | metric |
|---|---|---|---|
| **query** | user's language → English | one short string per search | recall@8 against a fixed corpus |
| **ingest** | source post → English | every non-English post, once | number preservation + hygiene |

A third leg — presentation translation of bot prose, EN→user — already ships
under D29 and is **not measured here**. Whether all three share one `translator`
slot or need separate task keys is an open design call.

### Reference points for the query leg

From the English-anchor run (`track-a/ENGLISH-ANCHOR-MEASUREMENT.md`):

```
0.630  perfect-translation ceiling (en slice, incumbent nomic-v1.5)
0.550  best multilingual embedder on NATIVE non-English  <- the bar the swap would buy
0.430  incumbent embedder on native non-English          <- today's behaviour
```

The pivot is worth taking if a measured arm clears **0.550**, because that is
what the alternative — swapping to a multilingual embedder — would buy, at the
cost of a D54 amendment, a 768→1024 migration and a re-embed of three corpora.

---

## 2. Run context

| | |
|---|---|
| repo commit | **`d1d93b68`** (pin per §7 of the handoff; re-pin and re-run if arms are compared across a code change) |
| runtime | native `llama-b10221` (`b10221-815a2a591`), Vulkan / RADV STRIX_HALO |
| server flags | `-ngl 999 --parallel 1 -c 8192 --reasoning off --reasoning-budget 0 --temp 0` |
| ingest fixtures | 248 harvested real non-English posts, 9 languages; **182 qualify** on ≥2 scoreable numeric mentions |
| query fixtures | 204 machine-translation jobs over the labelled query set, 8 languages |
| query embedder | `nomic-embed-text-v1.5` (f32) against `post_embedding_nomic_v1_5`, 9,224 rows — **pinned, not a variable** |
| decoding | greedy (`--temp 0`) on every arm |

**Greedy is deliberate and is a deviation from several model cards.** A shipped
deployment must cache and reproduce query translations to stay inside D19/D58,
so temperature 0 is the shipped configuration and therefore the one worth
measuring. These are not the models' tuned-quality best.

### Reproducing

```bash
cd .bench/m1-717
./run-arm.sh       <gguf> <label> <prompt-style> [server flags]   # ingest leg
./run-query-arm.sh <gguf> <label> <prompt-style> [server flags]   # query leg
./progress.sh                                                     # live view
python3 rescore.py results/doc-mt-*.json                          # comparable table
```

---

## 3. Ingest leg — number preservation

All arms re-derived through one code path by `rescore.py`. **Never quote the
`overall_number_preservation` stored inside a result file**: it was computed by
whatever metric version was live when that file was written, and the older files
read low (the Hy-MT2 smoke file stores 0.936 against its true 0.971).

| arm | quant | GiB | num-pres | scored/182 | drop | inv | era | defect | p50 s | tok/s |
|---|---|---|---|---|---|---|---|---|---|---|
| **Qwen3.6-35B-A3B** | Q6_K_XL | 30.4 | **1.000** | 182 | **0** | 1 | 0 | 0.0% | 2.25 | 35.4 |
| **DeepSeek-V4-Flash (remote)** | full/API | — | 0.997 | 182 | 1 | 1 | 0 | 0.0% | **1.39** | **63.2** |
| Qwen3.6-27B (dense) | Q6_K_XL | 24.2 | 0.997 | 182 | 1 | 2 | 0 | 0.0% | **8.80** | **7.6** |
| Hy-MT2-7B | Q6_K | 5.7 | 0.994 | 182 | 2 | 1 | 0 | 0.0% | 2.11 | 29.9 |
| GLM-4.7-Flash | UD-Q6_K_XL | 24.4 | 0.991 | 180 | 3 | 3 | 0 | **1.1%** | 1.57 | 40.0 |
| translategemma-4b | Q6_K | 3.0 | 0.985 | 182 | 5 | 3 | 0 | 0.0% | 1.49 | 44.2 |
| Hy-MT2-1.8B | Q6_K | **1.4** | 0.982 | 182 | 5 | 2 | 1 | 0.0% | **0.61** | **108** |
| rosetta-4b | Q6_K | 3.0 | 0.982 | 182 | 6 | 7 | 0 | 0.0% | 1.40 | 45.8 |
| GLM-4.7-Flash-REAP | UD-Q6_K_XL | 18.8 | 0.990 | 170 | 3 | 3 | 0 | **6.6%** | — | — |

⚠ **The `defect` column above is CORRECTED and supersedes every earlier figure**
(which read 0.5–7.7% across the arms). See §3.1 — the copy detector was
producing false positives on proper-noun-dense posts. **Preservation is
unaffected**: re-scoring every stored arm under the corrected detector moves it
by +0.000 for eight of nine arms.

**Read the defect rate before the preservation score.** A score computed after
voiding is conditional on the void rate: REAP's 0.990 is measured on 168
documents with its own failures removed, which is not comparable to 0.991 on
178. That is what the hygiene columns exist to make visible.

`defect` = documents yielding no usable answer, over documents in the file:
untranslated copies + malformed output + truncations + failed requests. Each is
counted once — the columns are an enum, not three booleans, so they cannot
double-count a denominator.

### 3.1 The copy detector was over-firing — corrected 2026-08-02

`looks_untranslated` voids a document whose output shares >X of the source's
4+-character tokens, on the theory that a model handed the wrong template can
COPY its input instead of translating it. At **X = 0.60** it was flagging
*correct* translations of short, proper-noun-dense posts.

The clearest case, from the DeepSeek arm — a fully fluent, wholly correct
translation, voided:

```
SRC: Beşiktaş'ta Serdal Adalı, Alexander Sørloth transferi için
     Atletico Madrid Başkanı ile görüşecek. (Sabah)
OUT: In Beşiktaş, Serdal Adalı will meet with the President of
     Atletico Madrid regarding the transfer of Alexander Sørloth. (Sabah)
```

8 of 12 source tokens are shared (0.67) and **all 8 are proper nouns**
(Beşiktaş, Adalı, Sørloth, Atletico, Madrid, Sabah, Serdal, Alexander). The
4 real vocabulary words were all translated.

**The threshold is now 0.90, measured rather than guessed.** Across nine arms
the voided documents are strictly bimodal — genuine copies at **0.97–1.00**,
false positives at **0.64–0.78**. 0.90 sits in an empty gap. Same discipline as
the script-share threshold (real translations ≤0.09 non-Latin, copies 0.94–1.00
→ 0.50).

**What it changed, exactly:**

| | stored @0.60 | rescored @0.90 |
|---|---|---|
| arms with a non-zero defect rate | **9 of 9** | **2 of 9** (both GLM builds) |
| DeepSeek copies | 3 | **0** |
| Hy-MT2-7B / Qwen-35B / Qwen-27B copies | 1 each | **0** |
| rosetta / translategemma / Hy-MT2-1.8B | 3 / 2 / 2 | **0** |
| GLM-4.7-Flash copies | 4 | **2** (genuine) |
| **preservation, all arms** | — | **Δ +0.000** (8 of 9) |

Only GLM genuinely copies: two Spanish posts returned verbatim, both of which
every other arm translated correctly — so it is an arm defect, not the fixture
property §0H took it for. REAP's +0.016 preservation shift comes from the CJK
script test post-dating its file, not from this threshold.

**Nothing had to be re-run.** Every result file stores `source_text` and
`machine_english` for every document *including voided ones*, and `rescore.py`
recomputes `looks_untranslated` rather than reading it. This is the concrete
payoff of the rule that stored headlines are never quoted and every arm is
re-derived through one code path.

**Trade-off, stated rather than hidden:** a PARTIAL copy (first paragraph
echoed, rest translated) lands near 0.5 and is now missed. No such case exists
in this corpus; if one appears it needs a different test, not a lower
threshold, because lowering it re-admits the proper-noun band.

### What the ingest leg establishes

1. **Model size buys nothing here.** Hy-MT2-7B beats the 24.4 GiB generalist on
   every quality and hygiene column at **23% of its size**, and the 1.4 GiB
   arm comes within 0.012 of it.
2. **The arms are tightly bunched** (0.982–0.994) and separate better on *how*
   they fail than on the headline: rosetta invents 7 numbers where Hy-MT2-7B
   invents 1, and only Hy-MT2-1.8B leaves a Buddhist-era year unconverted. Same
   score, different defect — which is why these columns are never averaged.
3. **Czech long scale discriminates between arms.** `39,4 bil. dolarů` (10¹²)
   must become "trillion", not "billion". **Hy-MT2-7B is the only arm that gets
   it right**; Hy-MT2-1.8B and both GLM builds are wrong by a factor of 1000.
   Czech is the one non-English language v1 ships. (§0H recorded "no arm gets it
   right" — true of the three arms it measured, falsified by Hy-MT2-7B.)
4. **Architecture dominates parameter count, and it is a selection rule not a
   footnote.** Qwen3.6-27B is *smaller on disk* than Qwen3.6-35B-A3B (24.2 vs
   30.4 GiB) and translates just as well (0.997 vs 1.000), yet is **4.7× slower
   per document** (8.80 s vs 2.25 s, 7.6 vs 35.4 tok/s) because the MoE
   activates ~3B parameters per token against the dense model's 27B. Its p95 is
   50.8 s. The ingest leg runs on **every** non-English post, so a dense model
   at this size is disqualified by throughput regardless of quality.
5. **REAP is a damaged build, not a llama.cpp bug.** The pruned GLM truncates
   multi-byte UTF-8 sequences; llama.cpp correctly refuses to serialise them.
   Its 7.7% defect rate is an arm property. Use the unpruned GGUF.

---

## 4. Query leg — recall@8

| arm | quant | GiB | recall@8 | 95% CI (n=204) | hit@1 | translate p50 |
|---|---|---|---|---|---|---|
| **DeepSeek-V4-Flash (remote)** | full/API | — | **0.578** | [0.510, 0.646] | — | 0.951 s |
| Qwen3.6-27B (dense) | Q6_K_XL | 24.2 | 0.577 | [0.509, 0.644] | — | **3.220 s** |
| Hy-MT2-7B | Q6_K | 5.7 | 0.574 | [0.506, 0.642] | — | — |
| Qwen3.6-35B-A3B | Q6_K_XL | 30.4 | 0.571 | [0.503, 0.639] | — | 0.462 s |
| GLM-4.7-Flash | UD-Q6_K_XL | 24.4 | 0.570 | [0.502, 0.638] | 0.691 | 0.354 s |
| translategemma-4b | Q6_K | 3.0 | 0.559 | [0.491, 0.627] | — | — |
| rosetta-4b | Q6_K | 3.0 | 0.558 | [0.490, 0.626] | — | — |
| Hy-MT2-1.8B (card) | Q6_K | 1.4 | 0.546 | [0.478, 0.615] | — | — |
| Hy-MT2-1.8B (strict) | Q6_K | 1.4 | 0.519 | [0.450, 0.587] | — | — |

⚠ **No ordering in this column is real.** The CI is ±0.068 at n=204 and the
entire spread 0.519–0.574 sits inside it; every arm is statistically tied with
every other, and all of them are tied with the 0.550 bar. **Retrieval quality
does not decide the pivot.** It is decided on coverage and migration cost — the
pivot solves three problems where the embedder swap solves one, at no migration
cost.

`translate p50` is the pivot's **new** cost. Embed and search are excluded
deliberately: both are paid today on every query in any language, so a total
would charge the architecture for work it already does.

---

## 5. Both legs, per model

§0I's central gap — *no model had both legs measured, so "does a dedicated MT
model earn its place" was unanswerable* — is **DISCHARGED, including for the
model that actually ships.**

| model | quant | GiB | query | ingest | defect | ingest p50 |
|---|---|---|---|---|---|---|
| **DeepSeek-V4-Flash (remote)** | full/API | — | **0.578** | 0.997 | **0.0%** | **1.39 s** |
| Qwen3.6-27B (dense) | Q6_K_XL | 24.2 | 0.577 | 0.997 | 0.0% | 8.80 s |
| Hy-MT2-7B | Q6_K | **5.7** | 0.574 | 0.994 | 0.0% | 2.11 s |
| Qwen3.6-35B-A3B | Q6_K_XL | 30.4 | 0.571 | **1.000** | 0.0% | 2.25 s |
| GLM-4.7-Flash | UD-Q6_K_XL | 24.4 | 0.570 | 0.991 | 1.1% | 1.57 s |
| translategemma-4b | Q6_K | 3.0 | 0.559 | 0.985 | 0.0% | 1.49 s |
| rosetta-4b | Q6_K | 3.0 | 0.558 | 0.982 | 0.0% | 1.40 s |
| Hy-MT2-1.8B | Q6_K | 1.4 | 0.546 | 0.982 | 0.0% | **0.61 s** |
| ~~Qwen3.5-122B-A10B~~ | Q4_K_XL | 73.2 | **dropped** | **dropped** | — | — |
| DeepSeek-V4-Flash (local) | UD-IQ3_XXS | 95.9 | ❌ | ❌ | ❌ | ❌ |

### The answer to §0E's question: NOTHING BEATS THE INCUMBENT

The bar was that a dedicated MT model must beat **the model already in the
slot**. No candidate clears it:

- DeepSeek posts the **highest query recall** of any arm (0.578).
- Its ingest preservation (0.997) is **one numeric mention** behind the best
  score recorded, out of 340.
- It has **zero defects**.
- It is the **fastest ingest arm measured** — 1.39 s/document and 63.2 tok/s
  *including network round-trips*, beating every local model on a dedicated GPU.

So the architecture gains nothing from adding a dedicated MT model, and the
`translator` slot needs no new model to support the English pivot. That is a
decision, not a tie: for a CANDIDATE, matching the incumbent is not a reason to
adopt it.

⚠ **This is "nothing beats the incumbent", NOT "these models are equivalent."**
The ingest metric is saturated (top four arms span 3 mentions in 340), the query
leg is one undifferentiated tie, and entity preservation is unmeasured. A
sharper metric could still separate them — it would just have to overturn a
result where the incumbent already leads on speed and ties on quality.

### What the local DeepSeek GGUF would still add

The one measurement left with genuine information in it: the local
`UD-IQ3_XXS` is the **same model at ~3 bits** against this remote arm at full
precision. That pair measures quantisation loss directly, which nothing else in
the programme does. It does not affect the decision above — it informs whether
a self-hosted DeepSeek is viable at all.

⚠ **Quant is not uniform across this table.** Every Round 1 arm is ~6-bit;
Qwen3.5-122B is 4-bit and the local DeepSeek is ~3-bit. A weaker result from
either is partly a compression result and cannot be read as a clean model
comparison. The local-vs-remote DeepSeek pair is the exception where that is the
*point* — same model, very different compression, so it measures quantisation
loss directly.

---

## 6. What these numbers do NOT settle

- ~~**The decision itself.**~~ **ANSWERED 2026-08-02 — see §5.** DeepSeek is
  measured on both legs and nothing beats it. What remains open is *which* of
  the pivot's three legs share the `translator` slot, not which model fills it.
- **Entity preservation.** Every ingest number above is `--numbers-only`. The
  entity metric is gated on a hand review of 248 posts that must *add* localised
  names the extractor never proposed, not merely tidy the ones it did. Until
  then no entity figure exists, and the D4 concern — terminology preservation on
  long technical bodies — is only partly probed by the number metric.
- **Overall translation quality.** Number preservation is a targeted check, not
  a quality score. Reference-free QE (CometKiwi) is the field standard and
  matches our no-gold-reference situation; whether to adopt it is open.
- **Absolute retrieval quality.** Recall is 0.52–0.63 at k=8 across every arm
  and embedder, and no model is separable under a single global threshold. Both
  are open independent of this decision.
- ⚠ **The number metric has SATURATED at the top.** Qwen3.6-35B-A3B preserved
  339/339 mentions, so there is no headroom left to separate it from Hy-MT2-7B
  (0.994) or GLM (0.991) — five mentions out of 339 span the top three arms.
  The metric still discriminates *downward* (it catches the Czech long-scale
  error, era conversions and fabrication) and the hygiene columns still
  separate arms, but **no top-of-table ranking should rest on this column
  alone.** This is the strongest argument yet for finishing the entity metric
  and for evaluating reference-free QE.

---

## 7. Run log

| date | what |
|---|---|
| 2026-08-02 | Round 1: Hy-MT2-1.8B re-run at the 2048 cap (0.971 → **0.982**, its truncation contamination removed); Hy-MT2-7B, translategemma-4b, rosetta-4b ingest; GLM-4.7-Flash query. All five on-disk models now have both legs. |
| 2026-08-02 | Harness fix: the `gemma` template reverse-maps a language *name* to a code through a table that carried the query leg's 8 languages while the ingest corpus has 9, raising a bare `KeyError: 'Portuguese'` 111 documents into the TranslateGemma arm and losing the run. `pt` added; arm re-run clean. |
| 2026-08-02 | Round 2: Qwen3.6-35B-A3B (**1.000** ingest, the number metric's ceiling) and Qwen3.6-27B, both legs each. |
| 2026-08-02 | **Qwen3.5-122B-A10B dropped before running**, by decision. With the ingest metric saturated at 1.000 and every query arm inside one ±0.068 CI, its only distinguishable outcome was scoring *worse*, which its Q4_K_XL quant confounds against every other arm's ~6-bit. A candidate that can only tie uninformatively is not worth 30–45 min of box time ahead of the arm that decides the question. Contrast DeepSeek, where a tie IS the decision — see §6. |

| 2026-08-02 | **DeepSeek-V4-Flash (remote), both legs** — the incumbent. Highest query recall (0.578), 0.997 ingest, zero defects, fastest ingest of any arm. §0E answered: nothing beats it. |
| 2026-08-02 | **Copy detector corrected** (0.60 → 0.90 token overlap, §3.1). Every prior defect rate is superseded; preservation is unchanged (Δ +0.000 on 8 of 9 arms). Re-derived offline from stored records — no arm re-run. |
| 2026-08-02 | Harness: `401/403/429` now halt as environment failures instead of being scored as arm defects. The remote key is a `${INFOCHAT_LLM_API_KEY}` **placeholder** in `application.properties`; the real value is quote-wrapped in the sibling `secrets.env` (mode 600). Both cost a 401 before a one-call smoke test caught them. |

### Corrections to earlier notes, so they are not re-derived

- §0H: *"All three arms share the Czech long-scale error… No arm gets it
  right."* Falsified by Hy-MT2-7B, which renders `bil.` as "trillion".
- §0I: *"rosetta-4b — expect `looks_untranslated` to void it."* That prediction
  came from rosetta's broken-prompt era, when a paraphrased instruction made it
  copy its input (0.420 against a 0.430 untranslated baseline). With the model
  card's verbatim instruction it translates normally: 179/182 scored, 3 copies.
- §0H: *Hy-MT2-1.8B's 0.971 is understated.* Confirmed — 0.982 on re-run.
- §0H: *GLM's remaining Spanish failures are "a fixture property, not an arm
  defect."* **Half right.** The 0.64–0.78 band is exactly that, and applies to
  every arm. But GLM also returns two Spanish posts **verbatim** at 1.00
  overlap, and every other arm translates those same documents — a genuine arm
  defect, and after the §3.1 correction the only one left in the table.
- §0I: *base URL and key are in `runtime/application.properties`.* The key is
  **not** there; that property holds `${INFOCHAT_LLM_API_KEY}`. See the run log.
- **Provisional finding, retracted:** a one-call smoke test suggested DeepSeek
  failed the Czech long-scale case (`bil.` → "billion"). It does not — the real
  corpus document yields "$39.4 **trillion**", and Czech scores 31/31. The
  smoke input was a hand-simplified ASCII string (`dolaru`/`rocne` for
  `dolarů`/`ročně`), which is not the fixture. n=1 on hand-typed input is not
  evidence.
