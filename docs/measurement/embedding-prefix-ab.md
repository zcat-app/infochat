# Embedding-prefix A/B measurement results (M1-863)

**Status: THE QUESTION IS ANSWERED — the prefix convention is DROPPED.**
The four-cell A/B on one pinned runtime fails its pre-registered W1
conjunct: prefixed admit precision is *lower* by −0.0314 (0.1503 vs
0.1818), below the required +0.05. A run passes or fails as a whole, so
the run fails and the idea is dropped with the numbers below — the
either-way outcome the pre-registration guarantees (P1). This resolves
the M1-748 §6 parked posture ("adopting prefixes would re-embed every
stored vector under a frozen-model contract … for an unmeasured net
gain. **No production change is recommended**; the observation stands
recorded") by measurement rather than argument: the net gain is now
measured, and it does not earn adoption. No production config, spec, or
code value moves on the strength of this record (P12).

**Status: evidence, not spec.** This file records how a conclusion was
reached; it is not itself a direction and nothing may be built from it.
No spec row cites this file. Directions live in `docs/spec/decisions.md`
(D19, D54).

Working copy and harness live in `.bench/prefix-ab/` (gitignored): the
M1-862 rig (`manifest.json`, `embed_docs.py`, `embed_queries.py`,
`coverage.py`, `selfcheck.py`), this run's `score.py`, and
`results/score.json`. Corpus: the sha-pinned campaign snapshot
`.bench/direct-chat-e2e/corpus/posts.jsonl` — 11,789 posts. Run date:
2026-08-16. All four cells on one pinned local container.

---

## 1. Instrument disclosure

- **Container** `prefix-ab-ollama` (own volume, fresh — the user's
  directive at M1-862), endpoint `http://127.0.0.1:11435`; image
  `ollama/ollama:0.30.8`; model `nomic-embed-text:latest`, digest
  `sha256:970aa74c0a90ef7482477cf803618e776e173c007bf957f635f1015bfcfef0e6`
  (from `ollama show`), nomic-bert 137M params, 768-d, F16. The
  production compose endpoint appears nowhere in the rig (P6) — prod
  containers and the prod DB were not touched by this measurement.
- **Corpus** sha256 `580706dd522d88f9144e4de71e5f91bab7e11fe9a180094d67424a768806ef7e`,
  11,789 posts; doc surface = title + "\n\n" + first 800 chars of body
  (production's `buildInputText` composition, adapted verbatim from
  M1-859). Both doc stores assert count equality with the corpus.
- **Conventions**: doc_raw `""`, doc_prefixed `"search_document: "`,
  query_raw `""`, query_prefixed `"search_query: "`.
- **Runtime prefix behavior (observed, never assumed — P5)**: the
  discrimination self-check embedded one fixed probe document raw and
  prefixed; cosine distance 0.076595 > 0.01 → the Ollama runtime does
  NOT strip or normalize the prefix; the two arms received genuinely
  distinct inputs. Byte-log: `probe-inputs.log`.
- **Determinism (re-verified before this run)**: the probe document
  embedded twice per convention returned byte-identical vectors, both
  arms; count equality 11,789 == 11,789, both stores.
- **Frozen labelled query set** (`coverage.json`, committed-freeze
  semantics — P7/P11): campaign fixtures (scenarios-grounded +
  scenarios-toolloop turns; labels = `context_uids` entries with no
  null-valued field; null-field entries are same-genre distractors, P8)
  n=32 per language en/cs/es/ru/tr; m1-717 samples n=38 (en) and 34
  (cs/es/ru/tr), pre-anchored via the deployed translator's recorded
  `machine_english`. Total 334 labelled queries. Non-English campaign
  queries anchored ONCE through M1-859's `anchor.py` + persistent
  cache; the anchored text hash is asserted byte-identical across
  conventions per query. Disclosed drops: 570 unresolvable label
  entries (listed in `coverage.json`), 60 zero-label queries excluded
  from labelled metrics and listed. Denominators are the frozen n —
  never percentages of survivor sets (P11).
- **Excluded paths** (this A/B does not measure them): the lexical arm
  + RRF fusion (semantic arm only, on its own vectors); the doc-store
  corpora (`doc_embedding` command intents/topics); the post↔post
  linking distribution; the four non-enabled languages (th/zh/ja/ar);
  the GGUF/llama-server harness runtime.
- **Scoring** (`score.py`, numpy only — no runtime calls): definitions
  below; the pipeline was hand-verified on a spot query and on the
  pooled W1 recomputation (byte-match).

## 2. Pre-registered criterion and measured values

**The lock (P1/P11).** The criterion constants below are fixed in the
ticket file `docs/plan/m1/tick-tickets/M1-863-prefix-convention-ab-2.md`
committed 2026-08-16 15:11:59+02:00 (commit `4186922067`), before any
measurement cell ran — `results/score.json` is the first results file,
created 2026-08-16 19:42. No constant was edited after the lock; a
post-hoc edit would have voided the run. The record's constants
byte-match the ticket's (`0.05`, `0.02`, `-1`, `25%`).

Definitions, fixed by the ticket: similarity = 1 − cosine distance;
floor = 0.60 admit similarity (production's current value, 1 − 0.40
distance); ranking = top-8 by similarity.

Three reading rules from the scoring script (`score.py`, gitignored
rig — restated here so the committed record is self-contained and the
W1/W2 values are reproducible from it alone):

- W2/W3's best true-hit similarity is read full-corpus: the max
  similarity of any label uid anywhere in the ranking, no top-8
  restriction (the ticket restricts only W1's admit set to top-8).
- W4 ties for the max label similarity are compared as
  sorted uid tuples between arms; a differing set counts as a change.
- A query with zero admitted hits contributes nothing to either W1
  pool (the convention is arm-symmetric and fixed, so per-arm pool
  composition is not a free variable).

| Conjunct | Criterion (pre-registered) | raw | prefixed | delta | Pass |
|---|---|---|---|---|---|
| W1 admit precision | true hits / admitted (top-8, sim ≥ 0.60), pooled over labelled queries; prefixed − raw ≥ +0.05 | 0.1818 | 0.1503 | −0.0314 | **FAIL** |
| W2 best-match-vs-floor margin | median over labelled queries of (best true-hit similarity − 0.60); prefixed − raw ≥ +0.02 | 0.0685 | 0.1082 | +0.0397 | PASS |
| W3 anchored admit parity | per language cs/es/ru/tr, prefixed admitted-count ≥ raw − 1 | 23/66 each | 37/66 each | +14 each | PASS |
| W4 ranking stability | best true hit changes identity between arms in ≤ 25% of frozen labelled queries where both arms admit ≥ 1 true hit | — | — | 50/227 = 22.0% | PASS |
| *(descriptive)* global similarity shift | median over all top-8 hits, prefixed − raw; weight zero | 0.6578 | 0.6963 | +0.0385 | — |

The **descriptive** row carries weight zero by design (P2): prefixing
shifts the whole similarity distribution, and on adoption every
threshold recalibrates anyway (M1-748 §4/§6), so an absolute-scale move
is meaningless as a criterion. No similarity-scale delta appears in any
win conjunct (W1–W4 are deltas, counts, parity and stability only). The
**pooled W1 numbers appear with the M1-748 §2 designed-distractor
caveat restated (P3)**: the pooled best_false is a designed near-miss
distractor and the labels are one-sided incomplete (§3.3), which is why
pooled precision is low in absolute terms on both arms — the ARM DELTA
is the statistic, and one-sided incompleteness biases both arms
identically. Per-query metrics are the statistics (W2 margin, W3
counts, W4 churn); pooled numbers are context.

The pass is all-or-nothing: W1 FAIL → the run fails as a whole; the
partial wins are recorded above with their numbers (a run whose only
recordable outcome is a win is the gemma failure mode — not this run).

## 3. The four cells

All cells share the pinned container, corpus and frozen query set;
matched pairs are the primary cells, the two cross cells are warning
metrics (P9).

### 3.1 Matched cells

| Cell | W1 pooled precision | W2 median margin | median top-8 similarity | admits en (n=70) | admits cs/es/ru/tr (n=66 each) |
|---|---|---|---|---|---|
| raw-doc × raw-query (production incumbent) | 0.1818 | 0.0685 | 0.6578 | 25 | 23 each |
| prefixed-doc × prefixed-query (candidate) | 0.1503 | 0.1082 | 0.6963 | 39 | 37 each |

The prefix convention widens true-match-vs-floor separation (W2
+0.0397) and admits more queries at the floor (W3 +14 per language) —
but it admits *more non-true hits too*, and W1's precision pays for it.

### 3.2 Cross cells — warning metrics, the half-adoption cost (P9)

| Cross cell | W1 pooled precision | W2 median margin | median top-8 similarity | admits en | admits cs/es/ru/tr | churn vs raw baseline |
|---|---|---|---|---|---|---|
| raw-doc × prefixed-query (query-side-only adoption) | 0.1689 | 0.0774 | 0.6757 | 35 | 33 each | 15/226 = 6.6% |
| prefixed-doc × raw-query (doc-side-only adoption) | 0.1656 | 0.0802 | 0.6653 | 31 | 29 each | 56/227 = 24.7% |

Half-adoption lands between the matched cells on every headline metric
and never beats the incumbent on W1 precision; doc-side-only prefixing
carries the highest best-true-hit churn (24.7%, the matched pair runs
22.0%). These cells are recorded with numbers and are non-gating, as
pre-registered.

### 3.3 The designed-distractor caveat (P3)

M1-748 §2: the pooled best_false is a designed near-miss distractor and
the labels are one-sided incomplete (§3.3), so pooled worst_true vs
best_false can never separate on this corpus — every pooled number
above (W1 precision, medians) is context restating that caveat. The
statistics this run decided on are per-query (margins, counts, churn)
and arm deltas.

## 4. Anchored admit counts vs the cross-space reference

Per language cs/es/ru/tr, n = 66 each (campaign 32 + m1-717 34):

| Language | raw admits (this run's baseline cell — the production incumbent's unprefixed count, measured here for the first time) | prefixed admits | reference: 34/37 |
|---|---|---|---|
| cs | 23 | 37 | 34/37 |
| es | 23 | 37 | 34/37 |
| ru | 23 | 37 | 34/37 |
| tr | 23 | 37 | 34/37 |
| en (context, not a W3 conjunct) | 25 (n=70) | 39 (n=70) | — |

The 34/37 reference row is the **PREFIXED pair on llama.cpp over the
9,224-post m1-717 corpus** (`floor-check.json` `native_en`;
`floor_check.py:33` `QUERY_PREFIX = "search_query: "`), NOT the
production incumbent's number — the incumbent's unprefixed count is the
raw column above, and the prefixed column is its same-runtime
counterpart. The reference differs in runtime (llama.cpp/F32) and
corpus (m1-717 snapshot) and is labeled as such: cross-space context,
not a conjunct.

## 5. Decision

**The prefix convention is dropped — the adopt-or-drop decision is
recorded either way, and this run's outcome is drop (P1).** W1 fails
(−0.0314 against a required +0.05); the pre-registered rule is that a
run passes or fails as a whole, so the adoption question is settled in
the negative with the measured numbers. The partial wins — W2 margin +0.0397, W3 parity
+14 admits per language, W4 churn 22.0% ≤ 25% — are recorded above as
partial wins; they do not reopen the question because the criterion was
fixed before the data existed and no conjunct may be re-tuned after the
run (P11; re-running or re-tuning the criterion is a fresh
pre-registration, out of scope here). There is no adoption follow-up to
name: the M1-748 §6 posture is resolved by evidence, and no ticket was
filed by this work (P12).

## 6. What these numbers do not settle

- **The doc-store distributions** (`HelpLookupTool`,
  `CommandIntentIndex` thresholds) — not measured: the rig has no real
  `CommandIntentIndexBuilder` surface (the M1-748 §5.4 stub trap).
- **The post↔post linking distribution** — no labelled same-story set
  exists (M1-748 §5.2/§6).
- **The fused lexical+RRF result** — the A/B measured the semantic arm
  only, on its own vectors.
- **The four non-enabled languages** (th/zh/ja/ar) — absent from the
  frozen set.
- **The one-sided M1-717 label incompleteness** (`pooling_pending`
  marker rows) — arm deltas are the statistic; absolute precision is
  context only.
- **Anything about llama.cpp-space thresholds** — the 34/37 reference
  row is cross-space context, not a conjunct (see §4).

## 7. Ownership

Run + record: ticket M1-863 (tick flow). Rig: M1-862. Shared analysis:
`docs/plan/m1/tick-analysis/prefix-convention-ab.md`. The M1-748 record
(`retrieval-separability.md`) is byte-unchanged — this record supplies
the measurement its §6 prefix bullet says was missing (corrections stay
visible: the new record, not an edit, carries the update).
Reproduction: re-running `python3 .bench/prefix-ab/score.py` against
the frozen `.npz` stores and `coverage-queries.json` reproduces every
number above byte-for-byte (pinned inputs, deterministic numpy, no
runtime calls).
