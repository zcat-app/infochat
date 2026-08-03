# Retrieval-separability measurement results (M1-748)

**Status: THE QUESTION IS ANSWERED.** The M1-717 finding — `worst_true <
best_false` on every evaluated embedder, so no single global similarity
threshold separates true from false matches — is **real but misread**. It
survives fixture correction arithmetically, yet what it measures is not a
model failure: it is the joint product of (a) label sets their own author
marked incomplete, (b) a metric that pools designed-adversarial query
categories into a threshold test they were built to defeat, and (c) a
recall ceiling that is a property of the labels, not of any model. A
single global threshold was never the right shape for this system, and
the per-surface distributions below show what is. The recommended
value changes are implemented in this same ticket (M1-748).

**Status: evidence, not spec.** This file records how a conclusion was
reached; it is not itself a direction and nothing may be built from it.
No spec row cites this file. Directions live in `docs/spec/decisions.md`
(D19, D54) and `docs/design/05-llm-and-embeddings.md`.

Working copy and harness live in `.bench/m1-717/` (gitignored):
`M1-717-embedder-eval.py`, `M1-717-post-query-samples.jsonl` (56 query
rows), `M1-717-intent-phrases.jsonl`, and `results/*.json` (five embedder
runs, 2026-08-02). Corpus: the `m1-717-corpus` container — 9,224 `READY`
posts embedded per production's input surface (title + `body_summary` |
first 800 body chars), plus the **production** `post_embedding` (768-d
nomic) and `doc_embedding` (51 rows) tables. New arms in this record were
measured 2026-08-03 (llama.cpp b10221, CPU, nomic-embed-text-v1.5 F32).

---

## 1. What is being measured

Six live thresholds, on two units, gating three different questions:

| # | Threshold | Value | Unit | Gates |
|---|---|---|---|---|
| 1 | `infochat.chat.semantic-threshold` | 0.40 | cosine **distance** | floor on chat semantic retrieval (posts admitted to grounding) |
| 2 | `infochat.linking.semantic-threshold` | 0.18 (`%pi` 0.20) | cosine **distance** | post↔post same-story linking, 48 h co-temporal window |
| 3 | `ChatAgent.CONFIDENT_SIMILARITY_CUTOFF` | 0.65 | similarity | confident-vs-marginal grounding prose (never the retrieved set) |
| 4 | `HelpLookupTool.SIMILARITY_THRESHOLD` | 0.60 | similarity | free-text phrase → command/topic doc admit |
| 5 | `ChatAgent.INTENT_DELIVERY_SIMILARITY_THRESHOLD` | 0.70 | similarity | deterministic usage-block delivery trigger (M1-665) |
| 6 | `CommandIntentIndex.TOPIC_SIMILARITY_THRESHOLD` | 0.60 | similarity | topic-doc match in the intent index |

Thresholds 1 and 3 read the **post** store; 4–6 read the **doc** store (a
different corpus with its own distribution); 2 reads post↔post distances,
a third distribution no query arm measures. #3–#6 are code constants
whose javadocs each record "recalibration against a real query corpus is
a follow-up" (M1-619 / M1-649 / M1-665). This record is that follow-up's
evidence. M1-717 was abandoned as superseded (`f47269e2`); its
recalibration half had no owner until M1-748.

**Two embedding-space caveats apply to every number below.**
The harness embeds with nomic's trained task prefixes (`search_query: `
/ `search_document: `); production embeds raw unprefixed text on both
the query and document side (verified: no prefix string anywhere in
`infochat-collector` / `infochat-provider` main sources). Harness
similarities and production similarities are therefore *close but not
identical* scales — §6 marks each distribution HARNESS-SPACE or
PRODUCTION-SPACE. Second, the 2026-08-03 arms embed queries with
llama.cpp/F32 while the stored production doc/post vectors came from
Ollama; cross-runtime cosine adds small noise (the A/B/A determinism
probe passes; rankings in §6.4 are coherent), but absolute values carry
~±0.01 uncertainty.

---

## 2. The M1-717 finding, re-examined on the incumbent

Stored incumbent run (`results/nomic_v1_5.json`, full corpus, k=8), en
queries, base form, pooled per M1-717's `arm_headroom` (all true-hit
similarities vs all false-hit similarities across labelled queries):

| | worst_true | best_false | headroom | separable |
|---|---|---|---|---|
| en | 0.615 | 0.872 | −0.257 | **no** |

All nine languages are pooled-inseparable on all five models (headroom
−0.09 to −0.41). That is the finding as M1-717 stated it. Three facts
change its meaning:

**Per-query separability is the majority case.** Within a single query,
`min(true) > max(false)` holds for **22 of 41** labelled en/base queries
on the incumbent — 23/41 bge-m3, 23/41 e5-large, 26/41 nomic-v2-moe,
22/41 qwen3-0.6b. Pooling is what destroys separability: absolute
similarity is not calibrated *across* queries, so query A's true hits at
0.62 pool below query B's false hits at 0.87. Across all languages and
forms: 205/285 labelled query-forms are individually separable.

**The pooled best_false is a designed distractor.** en best_false
(0.872) is a spot-Bitcoin-ETF *inflows* post retrieved for the
`near-miss` query "US spot Bitcoin ETFs record net outflows" — a
category constructed so that "only the outflow posts answer" against "
near-identical phrasing, entities and structure" (the fixture's own
note). A threshold test that pools `near-miss` and `exact-term`
categories is unpassable by construction; those categories measure
ranking on hard distinctions, not threshold placement.

**Top-8 censoring makes worst_true optimistic.** `true_sims` only
contains true matches that reached the top 8; relevant posts ranked
below k have strictly lower similarity and are invisible to the pooled
minimum. True separability is *worse* than the stored numbers say —
which strengthens, not weakens, the conclusion that no global absolute
cutoff exists.

---

## 3. Primary hypothesis: fixture/metric problem vs model problem

**Answer: both fixture and metric, with a bounded residual model
limitation. The uniform recall ceiling is a property of the labels, not
of the models.**

### 3.1 The recall ceiling is mechanical

`recall@8 = |relevant ∩ top8| / |relevant|`, and 18 of the 41 labelled
queries carry more than 8 relevant posts (up to 35 for "ransomware").
The mean *maximum achievable* recall@8 over labelled en/base queries is
**0.828**; over all query forms it is lower still. Measured en/base
means: 0.630 (nomic-v1.5), 0.610 (nomic-v2-moe), 0.605 (qwen3-0.6b),
0.592 (bge-m3), 0.557 (e5-large) — i.e. **0.67–0.76 of the ceiling**,
not of 1.0. The "model-independent ceiling ≈ 0.6" that motivated this
ticket's hypothesis is model-independent because it is mostly the label
count distribution divided by k. Five architectures did not fail
identically; they were graded against the same cap. (Correction to the
M1-717 reading, kept visible per this folder's conventions.)

### 3.2 The labels are incomplete — by their author's own marker

- **23 of 41** labelled fixture rows carry `pooling_pending: true` —
  the author's explicit marker that the `relevant_uids` set was built
  from a title-level filter and awaits completion by pooling retrieved
  results. One note states the body-level match reaches 27 rows where
  the label set holds 15.
- **Sibling-row inconsistency, proven mechanically:** the ENCFORGE and
  JadePuffer ransomware posts are labelled relevant in the "ransomware"
  row (35 uids) and absent from its sibling "ransomware attack
  encrypting victim files" (9 uids, a strict subset). The same posts
  count as true for one phrasing and false for its paraphrase. Same
  shape in the ETF trio: `579a4f5f…` is true for "bitcoin ETF" and
  false for the near-miss outflows row (there by design); the two
  age-verification paraphrase rows share identical 15-uid sets, but
  both miss the France/Missouri/Utah posts below.

### 3.3 Hand-check of the labels (rule pre-registered)

Selection rule, fixed before any post text was read: **Sample A** =
from the incumbent's en/base labelled queries, every FALSE-labelled hit
whose similarity exceeds that query's worst TRUE hit ("inverting false
hits", 52 total), ordered by similarity descending, top 25 distinct
(query, uid) pairs. **Sample B** (control) = all TRUE-labelled en/base
hits ordered by similarity, one per decile (10 posts). Judgment: does
the post answer the query, read against the post's title + first-800
body chars (production's embedded surface) and the fixture row's note.

Results — Sample A (25): **12 RELEVANT** (label incomplete), **5
borderline**, **8 correctly false**. Six of the 8 correct-false are the
*designed* hard negatives (5× ETF inflow-vs-outflow, 1× Poolin-vs-
Rhodium entity swap); only 2 are ordinary semantic drift. The 12
confirmed misses include posts literally titled for the query ("Zcash
from First Principles | Shielded Transactions" false-labelled for
"Zcash shielded private transactions"; "Robust Reasoning Benchmark"
false-labelled for "large language model reasoning benchmark").
Sample B (10): **10/10 genuinely relevant** — the positive labels are
sound; the defect is one-sided incompleteness.

### 3.4 Does the finding still hold after the fixture review?

Relabelling exactly the 12 confirmed pairs and recomputing: en/base
pooled `worst_true` = 0.615, `best_false` = 0.872 — **still
inseparable**, because the pooled maximum is the designed near-miss
distractor, which no label correction removes. Per-query separability
rises only 22 → 23 of 41 (a lower bound — only the top 25 of 52
inverting hits were reviewed). So: *the fixtures are materially
incomplete, and fixing them still does not produce a global threshold*,
because pooled absolute separability is the wrong success criterion
(§4).

### 3.5 What remains a real model limitation

Two failure axes survive every correction, on the incumbent and on all
four candidates: **directional polarity** (inflow vs outflow posts at
similarity 0.84–0.87 against a worst-true of 0.825) and **entity
identity** (Rhodium-bankruptcy vs Poolin-bankruptcy at 0.747 vs 0.729).
These are ranking errors above the query's own true matches; no
threshold at any layer distinguishes them. They bound what
similarity-gating can ever do here and are the standing argument for
keeping deterministic SQL (tags, entities, `searchPosts`) as the primary
retrieval path (D19) with embedding similarity as a secondary signal.

---

## 4. Is a single global threshold the right model? No.

- The six thresholds ask **three different questions** ("is this post
  relevant to this query", "are these two posts the same story", "did
  this phrase mean this command") on **two units** across **three
  distributions** (query→post, post→post, phrase→doc) in **two spaces**
  (harness-prefixed, production-unprefixed).
- Even within one surface, absolute similarity is uncalibrated across
  queries (§2): more than half of individual queries are separable
  while the pool never is. A global cutoff is dominated by whichever
  query's false cluster sits highest.
- The codebase already concluded this empirically — it grew six values
  on two units. The correct model is **per-surface thresholds, each
  read from its own live-space distribution** (below), with layered
  gates where a surface needs them (chat already has floor + confident
  band + marginal band).

"No single global threshold exists" is therefore the *correct design
statement*, not a measurement failure. The six thresholds M1-717 wanted
to derive from one model comparison were underivable for structural
reasons, not because the wrong embedder was measured.

---

## 5. Per-surface distributions and recommendations

Each subsection names the distribution it was read from. The values are
**starting values with stated margins**, shipped by this ticket.

### 5.1 `infochat.chat.semantic-threshold` = 0.40 distance (0.60 sim) — keep

HARNESS-SPACE, incumbent en/base, k=8: TRUE hit sims n=119: min 0.615,
p25 0.703, median 0.743, p75 0.792, max 0.916. FALSE hit sims n=209:
min 0.608, median 0.691, max 0.872. Off-domain top-8 sims n=120: median
0.569, p95 0.626, max 0.654. Non-en worst_true reaches 0.56 (cs, ja).

Every on-domain top-8 hit — true and false alike — clears the 0.60
floor, and even off-domain p95 (0.626) clears it: the floor performs no
true/false separation and never can (the distributions overlap by
0.26). Its real function is a garbage floor under the k-limit, and it
does that. Tightening it to exclude off-domain p95 (≥0.63 sim ⇒ ≤0.37
distance) would cut genuine non-English true hits at 0.56–0.62.
**Recommendation: no change.** Separation is not this layer's job.

### 5.2 `infochat.linking.semantic-threshold` = 0.18 / `%pi` 0.20 — collapse the spread, keep 0.18

PRODUCTION-SPACE (the live 768-d `post_embedding` vectors, LinkingJob's
own window shape). Rule pre-registered: driving posts = 500 lowest-uid
READY posts; for each, nearest-neighbour cosine distance among posts
with `fetched_at` in the trailing 48 h window (self excluded); 483/500
had a co-temporal neighbour. NN-distance distribution: min 0.0001, p10
0.132, p25 0.200, median 0.250, p75 0.287, p90 0.327, max 0.446.
Cumulative: **≤0.18: 18.4%, ≤0.20: 25.1%** — the `%pi` value links a
third more driving posts than the base value.

Hand-check (5 pairs nearest each boundary): just below 0.18, 1/5 pairs
is genuinely the same story (a rate-limit announcement + its own thread
reply, d=0.179); 4/5 are same-subtopic arXiv pairs. In (0.18, 0.20]:
series traps ("Zcash Shielded News Vol.26" ↔ "Vol.25", d=0.184) and
same-account boilerplate replies (d=0.180). In (0.20, 0.25]: all
related-but-distinct. There is **no distance gap** between "same story"
and "same subtopic" anywhere in the region — the threshold is a
precision/recall dial on a continuum, and the dial's cost concentrates
in the corpus's densest cluster (arXiv ML papers).

Per-profile values, as the acceptance requires: base **0.18 — keep**;
`%laptop`/`%vps`/`%remote-llm` explicit 0.18 rows — keep; **`%pi` 0.20 —
collapse to 0.18**. The spread's premise is dead: the 0.20 margin was
tuned "to compensate for the smaller embedding model on Pi"
(`application.properties` comment), but v1 ships 768-d nomic on every
profile and `infochat.embeddings.model` has no per-profile override —
design 05 §5.5 already concedes the per-profile embedder is deferred
beyond v1. Same model ⇒ same space ⇒ no reason Pi should buy 36% more
links, all of it from the series/boilerplate band just shown.

### 5.3 `ChatAgent.CONFIDENT_SIMILARITY_CUTOFF` = 0.65 — keep

HARNESS-SPACE true-hit distribution (§5.1): 115/119 true hits ≥ 0.65,
94/119 ≥ 0.70 — consistent with M1-619's live unprefixed sweep
(on-domain groundings 0.62–0.73), which is the calibration precedent
for this constant and already produced today's value. False hits also
clear 0.65 (median 0.691): confidence-above-cutoff is *not* evidence of
correctness, but this gate only shapes prose (D19 — never the retrieved
set), so its false-positive cost is tone. The M1-619 trade (0.65 keeps
~82% of genuine groundings confident) stands. **Recommendation: no
change.**

### 5.4 The three doc-store thresholds — lower; measured in production space for the first time

The stored harness runs never ran the intent arm, and when run
(2026-08-03) it measures a stub doc surface (`"command intent: add
source"`) that is **not** what production embeds
(`CommandIntentIndexBuilder.composeIntentText`) — hit@1 2/14 on the
stub vs 9/14 against the real vectors; its numbers are discarded as a
harness artifact, recorded here so nobody re-derives thresholds from
that arm.

PRODUCTION-SPACE measurement: the 51 stored `doc_embedding` vectors
(production's own, `nomic-embed-text`) against the 16 en fixture
phrases embedded unprefixed. n is small (14 labelled + 2 off-domain);
treat band edges, not third decimals.

- Expected-doc similarity: min 0.398, median 0.628, max 0.706;
  hit@1 = 9/14.
- **6/14 genuine phrasings fall below the 0.60 admit gate** (#4, #6) —
  including "is everything running properly right now" → `status`,
  which ranks top-1 at 0.5285 and is refused.
- **12/14 fall below the 0.70 delivery trigger** (#5): only the two
  strongest phrasings (0.7052, 0.7061) would ever fire it. The
  deterministic delivery path is effectively dead at 0.70.
- Off-domain phrases top-1: 0.428 / 0.424 — the usable rejection band
  ends ~0.43, leaving ≈0.10 of clearance below the weakest correct
  top-1 hit (0.5285).

**Recommendations:** `HelpLookupTool.SIMILARITY_THRESHOLD` 0.60 →
**0.52**, `CommandIntentIndex.TOPIC_SIMILARITY_THRESHOLD` 0.60 →
**0.52** (admits 12–13/14 phrasings, keeps ≥0.09 over off-domain max),
`INTENT_DELIVERY_SIMILARITY_THRESHOLD` 0.70 → **0.62** (preserves its
designed strictness relative to the tool gate — the +0.10 offset — on
the scale phrasings actually occupy). Ranking-vs-label subtleties in
the misses ("give me a roundup of what happened today" scores
`summary` 0.625 over its labelled `digest` 0.488 — arguably a label
choice, not a model error) cap what any cutoff can add: three of the
five hit@1 misses are near-synonym command pairs.

---

## 6. What the numbers do not support

- **Exact production values from harness-space distributions.** §5.1/
  §5.3 are prefixed-space; production is unprefixed. They support
  keep/no-keep decisions (the margins are gross), not third-decimal
  tuning. Anything finer needs the M1-619-style live sweep.
- **Translated-leg thresholds.** Non-en query forms were measured only
  in harness space and with MT-quality confounds; no per-language
  threshold conclusions are drawn.
- **The intent-arm numbers in `results/nomic_v1_5.intent.json`** — stub
  doc surface, wrong space (§5.4). Do not reuse.
- **Same-story linking ground truth.** §5.2 has no labelled same-story
  set; its hand-check is 15 pairs. It supports "no gap exists" and the
  spread collapse, not a re-derived optimal value.
- **The 0.52/0.62 doc-threshold values as final.** n=14 labelled
  phrases. They are defensible starting values with stated margins;
  confirming them against live `/help` free-text traffic follows the
  M1-619 live-verification pattern once real usage accumulates.
- **Prefix adoption.** Production's unprefixed use of a
  prefix-trained model is a real observation, but adopting prefixes
  would re-embed every stored vector under a frozen-model contract
  (`allow-model-change=false`, D54) for an unmeasured net gain. **No
  production change is recommended**; the observation stands recorded
  for whenever a re-embed migration exists for its own reasons.
- **Fixture completion as a prerequisite.** The `pooling_pending` label
  debt matters only if someone re-derives *post-retrieval* thresholds
  from the harness. Every recommendation above reads production-space
  or gross-margin data; M1-757 needs no fixture work. If a future
  ticket resumes harness-based threshold derivation, completing the
  labels (§3.3's rule generalizes: pool top-k, adjudicate, relabel) is
  its entry cost.

## 7. Ownership

The recommended changes — collapse the `%pi` linking spread (§5.2) and
recalibrate the three doc-store thresholds (§5.4), leaving #1 and #3
unchanged per §5.1 and §5.3 — ship in this ticket's own diff (M1-748,
refined 2026-08-03 to fold the implementation in). Every other finding
in this record recommends no production change, stated inline with
reasons (§6).
