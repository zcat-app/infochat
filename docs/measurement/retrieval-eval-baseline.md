# Retrieval-eval baseline

Baseline measurement of chat retrieval over the production fused query, and
the pre-registered rules every future retrieval change is gated against.
Settles: **what the shipped retrieval actually scores, per query class, on the
frozen test corpus** — the first quantitative statement of the three
user-confirmed live failures (location questions, recency questions, price
questions in chat). It is a measuring stick's first reading, not a direction;
per the measurement-README convention nothing is built from this file and no
spec row cites it.

Landed as **two commits on one branch**: this rules commit contains no
measured values, and the results commit appends the numbers (the
track-a/TRACK-A-THRESHOLDS.md pre-registration discipline — every rule below
is fixed before any number exists, so none of them can be refitted to whatever
the data turns out to look like).

## What is measured

The committed golden set (51 labeled queries,
`infochat-provider/src/test/resources/retrieval-eval/golden-set.jsonl`, M1-928)
executed by `RetrievalEvalRunnerIT` (M1-929) through the PRODUCTION
`SemanticSearchTool` bean against the operator-named test DB: the shipped
fused SQL, the iterative-scan GUC, the config-injected threshold/limit, the
local embedder, and the D58 query-anchor leg, by construction (the runner
injects the bean; it constructs nothing). Class composition (a fixture
property, not a result): temporal-today 5, temporal-2h 5, temporal-24h 4,
entity-location 6, entity-project 6, price 5 (all `none_expected`),
topical 8, cross-lingual 12 (3 information needs × cs/es/ru/tr); one further
`none_expected` row lives outside price. Residual divergences from a
production chat turn, restated here as the durable copy: (1) cancellation
arming is a no-op registration for the fixed eval user (the statement
timeout itself is still applied); (2) the eval scopes' world is all live
non-excluded bootstrap sources (a production user's may be narrower — labels
are valid for THIS world, pinned by the fingerprint); (3) the in-memory
query-anchor cache is per-boot, so pass 1 takes real translator calls and
pass 2 is all hits — both states recorded per record. None result-affecting.

## Artifacts — committed vs operator-local

Only versioned surfaces carry audit weight; this record cites the
operator-local ones as provenance, never as a requirement for reading it.
Committed: this record (the durable copy of every number quoted — final or
absent per the folder convention), the golden set, the harness
(`RetrievalEvalRunnerIT` / `RetrievalEvalScorer` + its CI unit test, whose
javadoc documents the operator invocation), and the idempotent
`scripts/eval-scopes-seed.sql`. Operator-local and gitignored
(`.bench/retrieval-eval/`, present only on the measuring host): the runs'
raw outputs — per-run `manifest.json`, per-query `queries.jsonl`,
`scores.json` under `results/<ts>/` — plus the harness README with the
bring-up notes. A reader of the committed repo can re-derive equivalents by
re-running the documented operator invocation against a DB matching the
pinned fingerprint (comparability is fingerprint-gated, rule D1); every
value this record states is restated here in full.

Metrics (computed by `RetrievalEvalScorer`, CI-covered by its unit test):
capped Recall@16 `|top16 ∩ E| / min(|E|,16)` AND raw recall `|top16 ∩ E| / |E|`
both reported (the mechanical ceiling at |E| > 16); MRR over the returned
order; for `none_expected` rows — recall is vacuous there — the over-return
pair (mean returned-row count, median post age from `ready_at`); lexical-only
share (`similarity: null` rows). Per-class slices with n, plus the overall
row. `none_expected` rows contribute to no recall/MRR denominator.

## Pre-registered gating rules

> **Rule G1 (decision-grade floor).** Every slice with n < 16 is a smoke
> signal — it can suggest, never gate (track-a growth rule G1). At this
> golden set's sizes that means **every per-class row is smoke-only** (n runs
> 4–12); only the overall row (n = 51) clears the floor.

> **Rule T1 (the paired-change gate).** A retrieval change is gated only
> against the SAME golden set on a matching DB fingerprint. Queries both
> retrievals serve identically carry no information; only discordant ones do.
> A query is **discordant** iff the set of expected uids inside its returned
> top-16 differs between the two retrievals (`none_expected` rows are never
> discordant — their over-return deltas are reported, not gated). Change B
> beats baseline A only if the exact two-sided sign test over the discordant
> queries yields p < 0.05. **6 one-directional discordant queries is the
> minimum that can ever reach p < 0.05, at any n** — fewer than six is never
> a result, whatever the percentages look like (TRACK-A-THRESHOLDS.md §1).

> **Rule N1 (no percentage-point thresholds, no survivor sets).** No rule
> below is expressed as a percentage of whatever survives scoring, a
> percentage-point gap, or any population a run could shrink: a threshold
> with a free variable isn't pinned (the pre-registration-free-variable
> lesson). Absolute counts over the fixed 51-query set and the sign test are
> the only gating vocabulary.

> **Rule D1 (comparability).** Scores are comparable only
> fingerprint-to-fingerprint. The runner already refuses to score across
> drift; a re-baseline after corpus drift is an explicit `supersedes` relabel
> of the golden set, and both fingerprints are then named here.

## Expected-bad classes (the reproduction this record IS)

The golden set was authored to reproduce three user-confirmed live failures
whose mechanisms are already verified in code: the fused SQL carries **no
time predicate** (nothing in the query can favor a recent post), the chat
tool surface has **no location/entity query leg** (entities are stored but no
tool reads them), and **no tool reaches `price_snapshot`** (the correct
answer to a price question is not in the post corpus at all). Therefore:

- **temporal-today / temporal-2h / temporal-24h** — recall expected far below
  what a working recency leg would return; the over-return pair (row count +
  median age) is expected to show old posts served for recency-asked queries.
- **entity-location / entity-project** — expected low for lack of any entity
  query leg.
- **price** (`none_expected`) — the honest behavior is an empty or minimal
  return; a large over-return mean documents the gap quantitatively.

**Honesty arm (rule H1).** Any of these classes scoring unexpectedly HIGH
gets a named investigation note in the results — which queries hit, whether
the label or the mechanism explains it — never a silent win. Symmetrically,
topical and cross-lingual are the classes with no near-zero expectation; an
unexpectedly LOW number there gets the same treatment.

## Determinism leg

The D19 boundary is "same DB state → same set/order". The runner self-checks
a double-run within one invocation; the record additionally names **two
separate invocations** (two result timestamps) on the shared fingerprint and
states that their per-query uid lists were byte-identical across the two
runs. A divergence is reported as a determinism failure, never averaged away.

## Pins (filled by the results commit; every key resolves or the record is
incomplete)

- repo commit at run time; harness commit (same)
- DB fingerprint: world-visible READY post count; max `ready_at`; sha256 over
  the ordered world uid set — must equal the labels' fingerprint
- effective config: semantic threshold, semantic limit, tool input-max-length
- embedder: endpoint, model
- translator: endpoint; the configured translator model key; the model the
  endpoint actually serves; fallback count (asserted 0 by the harness)
- anchor-cache state: per pass (fresh boot = miss-then-cached on pass 1, hit
  on pass 2); en scope issues zero translator calls (asserted)
- run timestamps: both invocations
- cross-lingual no-op share: per language, the share of xling rows whose
  anchored text equals the source query (the Rosetta no-op lesson, analysis
  P9; derived at authoring from the runs' operator-local `queries.jsonl` —
  the derived shares are restated below, see Artifacts)

## What these numbers do not settle

Pre-stated with the rules, because the caveats follow from n, not from the
data: at n = 51 overall, a binomial CI on a proportion runs roughly ±0.14 —
sub-floor deltas and per-class rankings are not resolvable, and every class
row is smoke-only by rule G1. Cross-language recall comparisons are confounded
by the anchor leg unless the anchored text is read (the no-op share exists to
make that checkable). Temporal labels are bound to the recorded fingerprint
(rule D1): the corpus will drift, and this file's numbers will still describe
exactly the pinned world. Nothing here measures the chat agent's tool choice
or prose — only the retrieval query.

## Corrections

None at pre-registration. Label corrections made during or after the run go
through golden-set `supersedes` records and are enumerated here with their
rationale; corrections stay visible.

## Results — 2026-08-27 (appended by the results commit)

### Pins

| pin | value |
|---|---|
| repo / harness commit | `e0edd3d43fbd8532266a84c2aced294d87843275` (this branch, rules commit) |
| DB fingerprint | `ready=5214;max_ready_at=2026-08-24 16:00:57.001472+00;uid_sha256=06ed0de15eefad172062b4b6e3dfb11713e02017b103cc8ab8e064ffbe489727` |
| label fingerprint match | yes — run fingerprint equals the golden set's `labeled_against` fingerprint (harness-asserted) |
| semantic threshold / limit | 0.40 / 16 (effective, read from the booted config) |
| tool input-max-length | 500 |
| embedder | `http://127.0.0.1:18080/v1`, model key `nomic-embed-text` — the test stack's `llamacpp-embeddings` serving `nomic-embed-text-v1.5.f16.gguf` |
| translator | endpoint `http://127.0.0.1:18081/v1` — the test stack's `llamacpp` serving `gemma-4-26B-A4B-it-UD-Q6_K_XL.gguf`; the booted config's translator model KEY reads `llama3.1:8b`, which is a name-only key against an OpenAI-compatible server that serves its loaded model regardless — the model that actually translated is the gemma GGUF |
| translator fallbacks | 0 (harness-asserted; a non-zero count aborts scoring) |
| en-scope translator calls | 0 (harness-asserted, D58 no-op leg) |
| anchor cache | fresh boot per invocation (in-memory Caffeine; no DB writes): pass 1 = miss-then-cached on all 12 xling rows, pass 2 = hit on all 12 |
| run timestamps | run 1 `2026-08-27T11:56:15Z` (all numbers below quote run 1); run 2 `2026-08-27T11:58:00Z` (determinism leg) |

Note: the fingerprint string above is the durable copy; it is mechanically
identical to the one stamped in both runs' manifests (operator-local,
gitignored, under `.bench/retrieval-eval/results/{20260827-115606,20260827-
115752}/` — see Artifacts above).

### Determinism leg (rule D1 / acceptance)

Two separate invocations on the shared fingerprint above: per-query uid lists
byte-identical across the two runs, on both passes (the runner's internal
pass-1/pass-2 self-check also passed inside each run). The anchored texts were
additionally identical across the two boots — greedy translation of the same
12 queries produced the same strings twice.

### Per-class results

`none_expected` rows are scored by over-return only (no recall/MRR
denominator); `—` marks a metric that does not apply. Every class row is
**smoke-only** by rule G1 (n < 16 each); the overall row is the only one above
the floor. Capped and raw recall are equal everywhere this run: the largest
expected set is 8, so the |E| > 16 ceiling never binds (mean label size 6.27).

| class | n | smoke | capped R@16 | raw R | MRR | over-ret mean count | over-ret median age (h) | lexical-only share |
|---|---|---|---|---|---|---|---|---|
| overall | 51 | — | 0.242 | 0.242 | 0.463 | 2.000 | 83.8 | 0.098 |
| temporal-today | 5 | smoke | 0.100 | 0.100 | 0.250 | — | — | 0.083 |
| temporal-2h | 5 | smoke | 0.100 | 0.100 | 0.233 | — | — | 0.000 |
| temporal-24h | 4 | smoke | 0.3125 | 0.3125 | 0.625 | — | — | 0.000 |
| entity-location | 6 | smoke | 0.179 | 0.179 | 0.500 | 1.0 | 214.7 | 0.125 |
| entity-project | 6 | smoke | 0.4375 | 0.4375 | 0.667 | — | — | 0.320 |
| price | 5 | smoke | — | — | — | 2.2 | 71.4 | 0.091 |
| topical | 8 | smoke | 0.153 | 0.153 | 0.435 | — | — | 0.129 |
| cross-lingual | 12 | smoke | 0.325 | 0.325 | 0.494 | — | — | 0.052 |

The overall row's over-return pair is computed over the 6 `none_expected`
rows (5 price + el-1). Cross-lingual no-op share (P9, derived from
`queries.jsonl`, operator-local — see Artifacts): **0/3 per language** —
every anchored text is a real
translation (xl-ai-cs `nejnovější zprávy o umělé inteligenci` → "latest news
about artificial intelligence"; xl-crypto-ru `новости о криптовалютах` →
"cryptocurrency news"; xl-cyber-tr `siber güvenlik haberleri` → "cyber
security news").

### Expected-bad classes against their numbers (rule H1)

- **temporal-today 0.100 / temporal-2h 0.100** — near zero as expected. The
  sparse hits ride lexical/topical overlap, the only channel that exists: the
  fused SQL has no time predicate, so nothing can favor a recent post. The
  live failure query is in the set verbatim: el-1 "what happened in Czech
  today" (none_expected) returned 1 post of median age ≈ 215 h — the
  "week-old post served as recent" observation, quantified. Four en queries
  (tt-2, t2h-1, t2h-3, t24-1) returned empty sets — for a recency question an
  empty return is the honest shape; the old-post returns are not.
- **temporal-24h 0.3125 / MRR 0.625** — the highest temporal row; named
  investigation note (honesty arm): t24-3 ("crypto and blockchain news from
  the last 24 hours", |E| = 1) hits its single expected uid at rank 1 and
  t24-2's first hit is rank 1 — both are exact-lexical head placements on
  queries whose keywords name their corpus, not temporal filtering. The
  mechanism claim is intact; the label explains the number.
- **entity-location 0.179** — low as expected, and the live observation is
  reproduced in shape: the five labeled location queries returned 1–2 posts
  each (el-2 "Czech news" → 1 post), the exact "1 seemingly random post"
  failure. The channel is title/body keyword match (czech/prague/czechia);
  no entity query surface exists in the tool allowlist.
- **entity-project 0.4375 — unexpectedly HIGH, named note (honesty arm):**
  project names (zcash, monero, qwen) are exact lexical tokens and the
  lexical arm serves them (ep-4 8/8, ep-1 5/8, ep-5 4/6). "Entity" is two
  populations: keyword-exact project names (served today) and geographic
  names (unserved — no discriminative token and no entity leg). The class
  average blends them; neither number is wrong, the split is the finding.
- **price over-return 2.2 / median age 71.4 h** — the gap quantified: price
  questions return 0–4 posts (pr-4 empty, pr-1 four rows) against a
  `none_expected` label — chat serves something whose correct answer lives in
  `price_snapshot`, unreachable from the chat tool surface.
- **topical 0.153 — unexpectedly LOW, symmetric note:** top-ml, top-oss and
  top-med each return a full 16-row window (top-med 8) with zero expected
  overlap. Whether that is retrieval weakness or pooled-label conservatism is
  exactly what n = 8 cannot settle (see the pre-stated section above); it is
  recorded as the follow-up question, not adjudicated here.
- **cross-lingual 0.325** — the anchor leg is NOT the residual: 0 no-op
  translations, 0 fallbacks, stable across boots; xl-crypto-es/ru reach 0.8
  raw recall while xl-ai-* sits at 0.125. The class's gap is the shared
  English-side retrieval leg, not translation.

### Corrections during the run

None. No golden-set `supersedes` record was created; the set is byte-identical
to M1-928's committed file. (The pre-registered corrections policy above
stands for future runs.)

## Results — 2026-08-27, re-baseline on the corrected golden set (appended by the M1-944 results commit)

Second reading of the same instrument, walking rule D1's explicit
`supersedes` relabel path: the answer key was corrected and extended
(M1-942 — 18 supersedes pairs, topical 8→16) and the harness taught to skip
retired records and pin the set's identity (M1-943), then the corrected set
was executed against the SAME frozen fingerprint. The rules T1/G1/N1/D1
above are unchanged and were NOT re-registered — nothing below was known
when they were fixed. The 2026-08-27 section above stands as the
defective-key reading; THIS section is the gating reference for the RAG
campaign's owner-run deltas. The production retrieval path is byte-identical
between the two readings (zero diffs under `infochat-provider/src/main` /
`infochat-llm/src/main` from `e0edd3d4` to `90ef5bdd`; the family's diffs are
test-scope only), so every class-level movement below measures the label
set, not retrieval.

**Pre-run fence (P7).** The frozen DB was verified intact BEFORE the run by
reading the world fingerprint directly off the quiesced stack:
`ready=5214;max_ready_at=2026-08-24 16:00:57.001472+00;uid_sha256=06ed0de15eefad172062b4b6e3dfb11713a02017b103cc8ab8e064ffbe489727`
— exactly the labels' `labeled_against` fingerprint. The harness then
asserted `label_fingerprint_match` true inside both invocations. A drifted
DB aborts scoring with the runner's named refusal; scoring across drift is
impossible by construction (the M1-933/M1-934 corpus-mutation caution — no
provider/collector boot, no backfill, no retention deploy against this DB
while the campaign runs).

### Pins

| pin | value |
|---|---|
| repo / harness commit | `90ef5bdd516df31701c6f311a74c2c56eca23241` |
| DB fingerprint | `ready=5214;max_ready_at=2026-08-24 16:00:57.001472+00;uid_sha256=06ed0de15eefad172062b4b6e3dfb11713a02017b103cc8ab8e064ffbe489727` (unchanged from the first reading) |
| label fingerprint match | yes — harness-asserted in both invocations |
| **label-set pin** | `golden_set_sha256 = 4dfed2d3df02f48b6b0369c8f0323d871d874c25d97769bbc8d445e6ba8e1154` (equals `sha256sum` of the committed `golden-set.jsonl`; asserted equal in both runs' manifests); **18 supersedes pairs; 59 active / 18 retired records** (manifest counts; retired records are skipped before execution) |
| semantic threshold / limit | 0.40 / 16 (effective, read from the booted config — unchanged) |
| tool input-max-length | 500 |
| embedder | `http://127.0.0.1:18080/v1`, model key `nomic-embed-text` — the test stack's `llamacpp-embeddings` serving `nomic-embed-text-v1.5.f16.gguf` (unchanged) |
| translator | endpoint `http://127.0.0.1:18081/v1` — the test stack's `llamacpp` serving `gemma-4-26B-A4B-it-UD-Q6_K_XL.gguf`; booted config's translator model KEY `llama3.1:8b` (name-only key, as in the first reading — the served model is the gemma GGUF) |
| translator fallbacks | 0 (harness-asserted; a non-zero count aborts scoring) |
| en-scope translator calls | 0 (harness-asserted, D58 no-op leg) |
| anchor cache | fresh boot per invocation: pass 1 = miss-then-cached on all 12 xling rows, pass 2 = hit on all 12 |
| run timestamps | run 1 `2026-08-27T18:27:29Z` (all numbers below quote run 1); run 2 `2026-08-27T18:28:53Z` (determinism leg); artifacts under `.bench/retrieval-eval/results/{20260827-182719,20260827-182844}/` (operator-local) |

### Determinism leg (rule D1 / acceptance)

Two separate invocations on the shared fingerprint: per-query uid lists
byte-identical across the two runs on both passes — 0 of 118 (pass, record)
rows differ — and the 12 anchored texts are byte-identical across the two
boots (greedy translation of the same 12 queries). The runner's internal
pass-1/pass-2 fingerprint and uid-identity self-checks passed inside each
run.

### Per-class results

`none_expected` rows scored by over-return only; `—` = not applicable.
Smoke marks per rule G1 at the NEW sizes: **topical (n = 16) clears the G1
floor and is marked decision-grade — the first class row to do so**; every
other class row is smoke-only; the overall row (n = 59) is above the floor.
Capped and raw recall are equal everywhere: the largest expected set is
exactly 16 (mean label size 8.25), so the |E| > 16 ceiling still never
binds.

| class | n | signal | capped R@16 | raw R | MRR | over-ret mean count | over-ret median age (h) | lexical-only share |
|---|---|---|---|---|---|---|---|---|
| overall | 59 | — | 0.358 | 0.358 | 0.607 | 2.000 | 83.8 | 0.091 |
| temporal-today | 5 | smoke | 0.100 | 0.100 | 0.250 | — | — | 0.083 |
| temporal-2h | 5 | smoke | 0.100 | 0.100 | 0.233 | — | — | 0.000 |
| temporal-24h | 4 | smoke | 0.3125 | 0.3125 | 0.625 | — | — | 0.000 |
| entity-location | 6 | smoke | 0.313 | 0.313 | 0.600 | 1.0 | 214.7 | 0.125 |
| entity-project | 6 | smoke | 0.4375 | 0.4375 | 0.667 | — | — | 0.320 |
| price | 5 | smoke | — | — | — | 2.2 | 71.4 | 0.091 |
| topical | 16 | **decision-grade** | 0.399 | 0.399 | 0.688 | — | — | 0.094 |
| cross-lingual | 12 | smoke | 0.513 | 0.513 | 0.771 | — | — | 0.052 |

The overall row's over-return pair is computed over the 6 `none_expected`
rows (5 price + el-1, unchanged). Cross-lingual no-op share (derived from
run 1's `queries.jsonl`): **0/3 per language** — every anchored text is a
real translation, including the 8 cascade successors (e.g. xl-cyber-tr-b
`siber güvenlik haberleri` → "cyber security news"; xl-ai-ru-b `последние
новости об искусственном интеллекте` → "latest news about artificial
intelligence").

### Movement vs the 2026-08-27 reading is DESCRIPTIVE, never a T1 result

The label set changed between the two readings (18 supersedes pairs + 8
extension rows; n 51 → 59). **Rule T1 gates a retrieval change only against
the SAME golden set — cross-set deltas are descriptive statements about the
size of the label artifact, never gated comparisons, and no retrieval
change is credited or debited by anything in this section.** The
consistency proof is in the rows that did not move: every class whose
labels are unchanged posts byte-identical numbers (temporal-today/2h/24h,
entity-project, price), and per-record the untouched top-crypto (0.4),
top-oss (0.0) and xl-crypto-* (0.4/0.8/0.8/0.4) are byte-identical to the
first reading — retrieval is deterministic across the two runs; all
movement lives exactly where the labels moved.

Decomposition of the movement (P13 — artifact vs weakness, not
regression/improvement):

- **topical 0.153 → 0.399 (n 8 → 16): mostly label artifact.** The six
  relabeled snapshots (top-ai 0.75, top-cyber 0.75, top-ml 0.75, top-med
  0.333, top-bio 0.5, top-robot 0.5 per-record raw recall) rose because
  their relevant populations were 2–3× the originally-labeled newest-8
  slice — the first reading measured "did retrieval return the same
  newest-8 sample", not relevance. **Real retrieval weakness remains,
  unchanged:** top-crypto 0.4 (precision noise) and top-oss 0.0 (the
  "open"→"OpenAI" lexical collision — recorded as an observation, NOT
  fixed here) are byte-identical rows, and several extension rows sit at
  or near zero (top-gaming 0.0, top-chips 0.0, top-physics 0.0,
  top-climate 0.111 — smoke rows, new observations with no old reading;
  top-drones 0.8 and top-misinfo 0.571 show the served end).
- **entity-location 0.179 → 0.313: denominator change, noted, not
  interpreted as movement.** The adjudicated corrections shrank/re-derived
  the sets (el-2b |E|=1 drop of the Prague-venue Zcash row; el-4b |E|=3
  drop of the two Kaspersky-attribution rows; el-5b |E|=5 adding the
  previously-unlabeled GLM-5.3 story; el-3b |E|=6 drop of the Helgoland
  Bite row). The channel itself (title/body keywords, no entity leg) is
  unchanged.
- **cross-lingual 0.325 → 0.513: cascade of the same topical artifact**
  (8 of 12 rows now carry the corrected sibling sets; xl-crypto-*
  unchanged and byte-identical). New observation, recorded not fixed:
  xl-cyber-* scores 0.25 against its sibling top-cyber-b's 0.75 — the
  anchored "cybersecurity news" retrieves a different window than
  "latest cybersecurity news"; xl-ai-* (0.688) tracks its sibling (0.75)
  closely.
- **overall 0.242 → 0.358 (n 51 → 59): the aggregate of the above plus 8
  new rows — descriptive only.**

With topical at n = 16 the sign test is now available for the campaign's
retrieval deltas on that class (T1 floor: 6 one-directional discordant
queries); everything else per-class remains smoke.

### What these numbers do not settle (restated at n = 59)

At n = 59 the overall binomial CI on a proportion still runs roughly
**±0.13** — sub-floor deltas and per-class rankings below the floor remain
unresolvable, and every class row except topical is smoke-only by rule G1.
Topical (n = 16) is decision-grade for the T1 sign test, not for
percentage-point ranking: its own CI runs roughly ±0.24, so within-class
sub-slicing (per-topic rows) stays suggestive. Cross-language recall
comparisons remain confounded by the anchor leg unless the anchored text is
read (the 0/3 no-op share per language keeps that check possible). Temporal
labels remain bound to the recorded fingerprint (rule D1): the corpus will
drift and this section will still describe exactly the pinned world.
Nothing here measures the chat agent's tool choice or prose — only the
retrieval query.

### Corrections this re-baseline consummates (18 supersedes pairs — corrections stay visible)

All 18 pairs landed in M1-942 against the frozen fingerprint, adjudicated
2026-08-27 (`.scratch/adjudication-report-20260827.md` +
`.bench/retrieval-eval/` pools, operator-local provenance); dispositions
restated here. Retired targets stay in the file byte-identical except the
added `replaced_by`; retired records are skipped before execution
(M1-943) and asserted excluded from both runs' manifests (59/18).

**Entity-location (4):**

- `el-2 → el-2b` (|E| 2→1): DROP the Zcash Shielded News row (body keyword
  "prague" is a summit venue, not a story nexus); KEEP the BenCzechMark
  story. Set = full adjudicated pool (2 rows adjudicated, 1 relevant).
- `el-4 → el-4b` (|E| 5→3): DROP Mustang Panda/CoolClient and Cavern C2
  (their only "Russia" is "Russian cybersecurity vendor Kaspersky said" —
  researcher attribution, actors are China-/Iran-nexus); KEEP Dahua and
  Manic under the tightened victims/targets-include-Russia reading; KEEP
  the Russian OAuth-hijack story. Pool 5, adjudicated 3.
- `el-5 → el-5b` (|E| 4→5): ADD "Reading Zhipu's GLM-5.3 results"
  (returned rank 1, previously unlabeled); KEEP AI-boom, Jewelbug, vCenter
  under the named China-nexus looseness and the arXiv China dry-anomaly
  row. Set = full pool, 5.
- `el-3 → el-3b` (|E| 7→6): DROP "Operation Helgoland Bite" (German-language
  op merely mentioning Ukraine — weakest defensible row); six rows stand.

**Topical relabels (6):** two-direction pooling (pooled SQL population ∪
row-by-row adjudication of the FULL returned window, including
previously-unlabeled relevant rows), deterministic 16-cap selection =
window rows in returned rank order then pooled keeps newest-first:

- `top-ai → top-ai-b` (|E| 8→16, pool 18): DROP "Fragments: August 24"
  (Martin Fowler digest, precision miss); classroom/app-discovery essays
  fall to the cap cut.
- `top-cyber → top-cyber-b` (|E| 8→16, pool 17): DROP "HTTP Client SSRF
  Mitigation" (Baeldung howto); ToxicPanda falls to the cap cut; DEFCON
  tutorial and vendor-PR rows adjudicated NOT relevant stay unlabeled.
- `top-ml → top-ml-b` (|E| 6→16, pool 18): no adjudicated drops; political-QA
  and FL-MAESTRO rows fall to the cap cut.
- `top-med → top-med-b` (|E| 8→12, pool 12 ≤ 16, no cut): 8 keeps + 4
  previously-unlabeled adjudicated-relevant rows.
- `top-bio → top-bio-b` (|E| 6→10, pool 10 ≤ 16, no cut): 6 keeps + 4
  previously-unlabeled rows.
- `top-robot → top-robot-b` (|E| 7→10, confirmatory): original labels
  adjudicated good; set = full pool (7 keeps + 3 previously-unlabeled);
  successor rides the supersedes record so the audit trail stays uniform.

**Xling cascade (8):** each inherits its corrected English sibling's set
verbatim (xling labels follow the English need; the validator asserts the
equality); the non-English query form still exercises the D58 anchor leg at
run time.

- `xl-ai-cs → xl-ai-cs-b` (|E| 8→16)
- `xl-ai-es → xl-ai-es-b` (|E| 8→16)
- `xl-ai-ru → xl-ai-ru-b` (|E| 8→16)
- `xl-ai-tr → xl-ai-tr-b` (|E| 8→16)
- `xl-cyber-cs → xl-cyber-cs-b` (|E| 8→16)
- `xl-cyber-es → xl-cyber-es-b` (|E| 8→16)
- `xl-cyber-ru → xl-cyber-ru-b` (|E| 8→16)
- `xl-cyber-tr → xl-cyber-tr-b` (|E| 8→16)

**Extension (not corrections):** 8 NEW topical information needs
(top-chips, top-climate, top-drones, top-gaming, top-misinfo, top-physics,
top-quantum, top-space) labeled via the pooling pipeline against the same
frozen fingerprint — no existing record touched.

No label was edited in place; no NEW label defect surfaced in this run (a
future one goes back through a `supersedes` correction ticket and the
re-baseline re-runs).

### Campaign gating note (queue discipline)

THIS section is the gating reference for the RAG campaign (M1-931..941
reference the baseline in their acceptance items): every retrieval-delta
landing gates its owner-run delta against this corrected reading — same
`golden_set_sha256`, same DB fingerprint, rule T1's sign test (floor 6
one-directional discordant queries; topical's n = 16 now participates
decision-grade). Non-retrieval tickets (M1-931 getPrice, M1-933 retention,
M1-939 language pinning) proceed in parallel. The frozen stack stays frozen
through the campaign's last delta run: no provider/collector boot against
the test DB, no backfill, no retention deploy — the runner's
fingerprint-refusal is the fence and scoring across drift never happens.

### Corrections during this run

None. The frozen M1-942 golden-set output was consumed read-only; its
identity is pinned above (`golden_set_sha256`) and asserted by both runs'
manifests.

## Tech-leg disposition — representativeness, demotion, drift restore (appended 2026-08-28, M1-951)

### Representativeness caveat

Every number in this record — both Results sections, the campaign ladder
built on them, and the golden set itself — describes the TECH test
instance's world: the ready=5214 posts whose tag mix runs ~90% tech
(ai 3615 / research 3009 / software-development 1134 / cybersecurity
1000 / crypto 314; tail: medicine 28 / football 20 / other-sports 12).
The product also runs live as a general-news instance (the fam
deployment: economy/world/health/sports mainstream over ~7300 READY
posts; ai 140 / cyber 94 as tail) on the SAME architecture. Findings
split:

- Instrument-local (do NOT generalize beyond the tech world): the
  width-32 magnitude, the cyber-gap ladder, per-need PRF/rerank effects,
  every per-class recall, the 4th-need selection of the pending widening.
- Architecture-level (generalize by spec, D19/D29/D58): the translation
  premise (queries anchored to English, corpus translated at rest into a
  unified English space), the multilingual-embedder rejection,
  embedding-coverage discipline, result-budget mechanics.

### Demotion of the golden set

The golden set is the TECH-instance regression suite. No decision-grade
product-wide claim may rest on this record alone; such claims gate on
both legs of the two-world instrument (the fam snapshot-replica leg plus
this tech leg — the two-leg record owns those gating rules).

### World drift and restore

Between 2026-08-24 16:00:57 UTC (the frozen max_ready_at) and 2026-08-27
23:54 UTC an external collector boot ingested posts beyond the freeze.
The 2026-08-28 restore (M1-951) re-derived the mechanical drift set —
world-visible READY posts with ready_at strictly after the frozen max —
as 47 posts, all `rss.arxiv.org/rss/cs.AI` (ready_at 2026-08-27
23:44:03–23:54:23 UTC). The same boot also produced one READY BBC post
through a user-added source; that post is world-invisible under D59 and
was left untouched. After a stopped-stack snapshot of the
`infochat-test_infochat-pgdata` volume, one transaction deleted the 47
posts and their dangling derivatives (36 `post_reference`, 67
`post_entity`, 47 `post_embedding` rows; zero `quarantine` / `saved_post`
/ `summary_anchor` rows). Post-restore fingerprint read, byte-exact the
frozen pin:

`ready=5214;max_ready_at=2026-08-24 16:00:57.001472+00;uid_sha256=06ed0de15eefad172062b4b6e3dfb11713e02017b103cc8ab8e064ffbe489727`

Operator smoke (M1-929 runner, commit `04d56d45`, 2026-08-28): both
passes report that same fingerprint and `label_fingerprint_match` true —
the campaign gating note above is runnable again. The restore script is
`scripts/tech-drift-restore.sql` (SELECT-first review; the delete
aborts unless the post-delete fingerprint equals the pin byte-exactly).

### M1-946 / M1-947 disposition

Per the ruling recorded 2026-08-28: M1-946 (xling widening to n=16) is
KEPT, re-gated on M1-951's restore — its rows label against the pin,
which exists again — and its reading consumer is the two-leg record's
tech leg, not M1-947. M1-947 (re-baseline on the widened set) is
ABANDONED as superseded: its widened-set reading IS the tech leg of the
mixed baseline (M1-952), and a separate section would duplicate the same
run against the same frozen stack.


## Two-leg gating redirect (appended 2026-08-30, M1-952)

The campaign gating reference for PRODUCT-WIDE retrieval claims moves to
[retrieval-eval-two-leg.md](retrieval-eval-two-leg.md): such claims gate
only when the per-leg sign test clears on BOTH legs independently
(rule TL1 there, an addition to — never a refit of — the T1/G1/N1/D1
rules above, which stand byte-identical). THIS record remains the TECH
LEG's reading history and the tech-instance regression reference: its
two Results sections are history (the 2026-08-27 defective-key reading
and the 2026-08-27 corrected re-baseline at the pre-widening
`golden_set_sha256`), and the current tech-instance regression reading —
the widened set at `ccea13ba…` on the same frozen fingerprint — is the
tech leg section of the two-leg record. Tech-instance claims (same-set
deltas on the tech world alone) keep gating per rule T1 against the
current tech-leg reading; corrections stay visible everywhere. The
append-only discipline is unchanged: everything above this section is
byte-identical to what landed before it.
