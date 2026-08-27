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

