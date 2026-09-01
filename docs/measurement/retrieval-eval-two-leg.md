# Two-leg retrieval-eval record

The two-world instrument's charter and first reading: the gating rules for
any PRODUCT-WIDE retrieval claim, and both legs' baseline numbers under
those rules. The instrument re-anchors the retrieval campaign (M1-928..951)
on two legs after the representativeness finding: the TECH test world — the
frozen, restored corpus the previous campaign measured — and the FAM world —
an isolated snapshot replica of a live general-news instance running the
same architecture. [retrieval-eval-baseline.md](retrieval-eval-baseline.md)
remains the tech leg's reading history and the tech-instance regression
reference; PRODUCT-WIDE claims gate HERE. Per the measurement-README
convention nothing is built from this file and no spec row cites it.

Landed as **two commits on one branch** (the M1-930 discipline): this rules
commit contains no measured values, and the results commit appends both
legs' numbers. Every rule below is fixed before any number exists, so none
of them can be refitted to whatever the data turns out to look like.

## The two legs

- **tech leg (corner-case leg)** — the tech golden set
  (`infochat-provider/src/test/resources/retrieval-eval/golden-set.jsonl`,
  the widened cross-lingual slice included) executed by
  `RetrievalEvalRunnerIT` at `-Deval.world=tech` (the default,
  byte-identical to the pre-two-world behavior) against the RESTORED
  frozen test stack (drift-restore record: the baseline file §Tech-leg
  disposition). This world's tag mix is overwhelmingly tech (M1-951's
  representativeness caveat, in the baseline file), so its findings are
  tech-instance statements unless TL2 promotes them.
- **fam leg (broad-distribution leg)** — the fam golden set
  (`infochat-provider/src/test/resources/retrieval-eval/golden-set-fam.jsonl`,
  labeled against the replica's pinned fingerprint) executed by the same
  runner at `-Deval.world=fam` against the ISOLATED fam snapshot replica
  (committed instance-free procedure: `scripts/fam-replica-restore.sh`;
  the instance's address lives only in the gitignored operator stores).
  This world is a real instance's general-news mainstream (economy /
  world / health / sports; ai and cyber as tail). Live fam is NEVER a
  target — the runner's label-fingerprint refusal is the tripwire, and a
  mis-pointed run aborts, never silently scores.

Both legs execute the PRODUCTION `SemanticSearchTool` bean (the runner
injects it, never constructs one) over the same committed harness; each
run's manifest pins `world`, `golden_set_resource`, `golden_set_sha256`,
active/retired record counts, `world_embedding_coverage`, and both passes'
DB fingerprints, with the label-fingerprint match asserted before any
score is written. Per-leg residual divergences from a production chat
turn are restated with the results (the harness README's enumerated set:
the three base divergences, plus the fam leg's cross-instance embedder
numerics, boot writes, and the coverage pin).

## Pre-registered two-leg gating rules (ADDITIONS to T1/G1/N1/D1)

The single-world gating rules — T1 (paired-change sign test over
discordant queries), G1 (decision-grade floor), N1 (no percentage-point
thresholds, no survivor sets) and D1 (fingerprint-to-fingerprint
comparability) — are pre-registered in
[retrieval-eval-baseline.md](retrieval-eval-baseline.md) §Pre-registered
gating rules and are cited BY REFERENCE here: never restated with new
knowledge, never refit, never amended by this record. The rules below are
ADDITIONS on top of them; nothing below was known when they were fixed.

> **Rule TL1 (both-legs gate).** A PRODUCT-WIDE retrieval claim gates only
> when the per-leg T1 sign test (floor 6 one-directional discordant
> queries, same golden set, matching fingerprint) clears on BOTH legs
> independently, each at its own pinned fingerprint and golden set. A
> result on one leg alone is a leg-scoped (instance) statement.

> **Rule TL2 (corner-case extrapolation).** A corner case found on one leg
> (e.g. the tech world's xl-cyber anchor gap) is a HYPOTHESIS about the
> other leg. It becomes a product-wide decision only after the other leg
> measures the same mechanism under these rules; until then it is recorded
> leg-scoped.

> **Rule TL3 (no pooled cross-leg test).** Numbers from different legs or
> fingerprints never enter one sign test. Cross-leg differences are
> descriptive, with both fingerprints named.

> **Coverage-comparability clause.** Within a leg, runs are comparable
> only fingerprint-to-fingerprint AND coverage-pin-to-coverage-pin: the
> manifest's `world_embedding_coverage` is a pin, not an invariant, and a
> coverage-moving operation (e.g. a backfill) is a search-space mutation
> that requires a separate decision and a re-pin before any comparison —
> the coverage confound the tech world's frozen ladder exposed. A fam-leg
> reading carries its coverage pin wherever its fingerprint is named.

No rule in this record is expressed as a percentage of whatever a run
returns or of any population a run could shrink (the N1 discipline):
absolute counts over fixed golden sets, per leg, and the sign test are the
only gating vocabulary.

## Determinism legs

The determinism boundary (docs/spec/llm.md §Determinism boundary — same
DB state → same rows and order; the LLM never picks the set) is restated
per leg at that leg's own pinned world: each leg's results section names
TWO separate invocations (two result timestamps) on the leg's pinned
fingerprint and states whether their per-query uid lists were
byte-identical across the two runs. The runner's internal double-run
(pass 1 / pass 2) fingerprint and uid-identity self-checks pass inside
each invocation — harness-asserted, not asserted by this prose. A
divergence is reported as a determinism failure, never averaged away.

## Pins (filled by the results commit; every key resolves PER LEG or the record is incomplete)

- repo / harness commit at run time
- DB fingerprint — tech: the restored frozen pin, byte-equal to the
  labels' `labeled_against`; fam: the replica pin; read on both passes of
  both invocations
- label fingerprint match — harness-asserted in every invocation
- golden-set pin — `golden_set_sha256` (equals `sha256sum` of the
  committed file) plus active / retired record counts
- `world_embedding_coverage` — with-embedding count / total
- embedder — endpoint, model
- translator — endpoint, configured model key, served model; fallback
  count (asserted 0 by the harness); en-scope translator calls (asserted
  0, the D58 no-op leg)
- effective config — semantic threshold, semantic limit
- run timestamps — both invocations, per leg

## Results — 2026-08-30, both legs' first readings (appended by the results commit)

Both legs ran the same night on one engine boot (the test stack's
embedder + translator, GPU-attached; bring-up per the harness README),
from the same harness commit as the rules commit above — the branch adds
documentation only, so the measured production path is the merged main of
that commit. Each leg's DB was fenced BEFORE the run by reading the world
fingerprint directly off the container: the reads matched the legs' pins
byte-exactly, and the harness then asserted `label_fingerprint_match`
true inside all four invocations. Translator fallback records were empty
and en-scope records issued zero translator calls in every invocation
(harness-asserted — a non-zero count aborts scoring, never degrades it).

### Disclosed residuals (restated from the harness enumeration)

The three base divergences of the harness (no-op cancellation arming,
all-live-sources eval world, per-boot anchor cache) apply to both legs
unchanged. Fam-leg-only: the replica's corpus vectors were written by the
instance's own embedder process while the query vectors come from the
test stack's (same `nomic-embed-text-v1.5.f16.gguf`, different process —
bounded cross-boot drift is measured prior art,
[anchor-leg-characterization.md](anchor-leg-characterization.md): ≤0.0023
similarity with adjacent near-tie reorders); the eval boot re-embeds
changed `doc_embedding` intent rows (post-fingerprint-neutral — the
fingerprint covers post rows only); and the coverage pin below is a pin,
not an invariant (the coverage-comparability clause).

### Tech leg — first reading (the widened set)

| pin | value |
|---|---|
| repo / harness commit | `9bc418549204fa691bce6c7293a3c7c3b3a0abac` (rules commit; doc-only diff vs main `43d59469`) |
| DB fingerprint | `ready=5214;max_ready_at=2026-08-24 16:00:57.001472+00;uid_sha256=06ed0de15eefad172062b4b6e3dfb11713e02017b103cc8ab8e064ffbe489727` (the restored frozen pin; both passes, both invocations) |
| label fingerprint match | yes — harness-asserted in both invocations |
| golden-set pin | `golden_set_sha256 = ccea13baa4c7e0938be6307f78774c6739e14cb9cbccb282bd7cb7bd2400d725` (equals `sha256sum` of the committed `golden-set.jsonl`); **63 active / 18 retired** |
| `world_embedding_coverage` | 1908 / 5214 (partial — pinned, and load-bearing for comparability) |
| embedder | `http://127.0.0.1:18080/v1`, model key `nomic-embed-text` — the test stack's `llamacpp-embeddings` serving `nomic-embed-text-v1.5.f16.gguf` |
| translator | endpoint `http://127.0.0.1:18081/v1` — the test stack's `llamacpp` serving `gemma-4-26B-A4B-it-UD-Q6_K_XL.gguf`; booted config's translator model KEY `llama3.1:8b` (name-only key — the served model is the gemma GGUF) |
| translator fallbacks | 0 (harness-asserted) |
| en-scope translator calls | 0 (harness-asserted, D58 no-op leg) |
| anchor cache | fresh boot per invocation: pass 1 = miss-then-cached on all 16 xling rows, pass 2 = hit on all 16 |
| semantic threshold / limit | 0.40 / 16 (effective, read from the booted config) |
| run timestamps | run 1 `2026-08-29T22:24:29Z` (all numbers below quote run 1); run 2 `2026-08-29T22:26:01Z` (determinism leg); artifacts under `.bench/retrieval-eval/results/{20260829-222418,20260829-222550}/` (operator-local) |

**Determinism leg.** Two separate invocations on the pinned fingerprint:
per-query uid lists byte-identical across the two runs — 0 of 126
(pass, record) rows differ — and all 16 anchored texts are byte-identical
across the two boots (greedy translation of the same 16 queries; 0 no-op
anchors, 4 per language). The runner's internal pass-1/pass-2 fingerprint
and uid-identity self-checks passed inside each run.

Per-class results — `none_expected` rows scored by over-return only;
`—` = not applicable. Smoke/decision marks per rule G1 at THIS leg's own
n: **topical (n = 16) and cross-lingual (n = 16) clear the G1 floor and
are decision-grade** — cross-lingual is newly decision-grade via the
M1-946 widening; every temporal/entity/price row is smoke-only; the
overall row (n = 63) is above the floor. Capped and raw recall are equal
everywhere (the largest expected set is exactly 16; mean label size
8.23), so the |E| > 16 ceiling never binds.

| class | n | signal | capped R@16 | raw R | MRR | over-ret mean count | over-ret median age (h) | lexical-only share |
|---|---|---|---|---|---|---|---|---|
| overall | 63 | — | 0.333 | 0.333 | 0.564 | 2.000 | 83.8 | 0.090 |
| temporal-today | 5 | smoke | 0.100 | 0.100 | 0.250 | — | — | 0.083 |
| temporal-2h | 5 | smoke | 0.100 | 0.100 | 0.233 | — | — | 0.000 |
| temporal-24h | 4 | smoke | 0.3125 | 0.3125 | 0.625 | — | — | 0.000 |
| entity-location | 6 | smoke | 0.313 | 0.313 | 0.600 | 1.0 | 214.7 | 0.125 |
| entity-project | 6 | smoke | 0.4375 | 0.4375 | 0.667 | — | — | 0.320 |
| price | 5 | smoke | — | — | — | 2.2 | 71.4 | 0.091 |
| topical | 16 | **decision-grade** | 0.399 | 0.399 | 0.688 | — | — | 0.094 |
| cross-lingual | 16 | **decision-grade** | 0.384 | 0.384 | 0.578 | — | — | 0.051 |

The overall row's over-return pair is computed over the 6 `none_expected`
rows (5 price + el-1), unchanged from the prior reading.

**Movement vs the 2026-08-27 re-baseline is DESCRIPTIVE, never a T1
result.** The label set changed (M1-946 widened cross-lingual 12 → 16;
`golden_set_sha256` `4dfed2d3…1154` → `ccea13ba…`), so rule T1 gates
nothing in this paragraph and no retrieval change is credited or debited
by it. The consistency proof is in the rows that did not move: every
class whose labels are unchanged posts byte-identical numbers
(temporal-today/2h/24h, entity-location, entity-project, price, topical
— 0.399/0.688 as recorded), and per-record the untouched xl-crypto rows
are byte-identical to that reading (0.4 / 0.8 / 0.8 / 0.4). All movement
lives in the class that grew: cross-lingual 0.513 → 0.384 because the
four new `xl-chips-*` rows score 0.0 each (0–2 rows returned, no hits —
the sparse-island regime they were chosen for), and the overall row
0.358 → 0.333 is the same dilution (n 59 → 63).

### Fam leg — first reading (the broad-distribution world)

| pin | value |
|---|---|
| repo / harness commit | `9bc418549204fa691bce6c7293a3c7c3b3a0abac` (same harness as the tech leg) |
| DB fingerprint | `ready=8260;max_ready_at=2026-08-28 15:43:18.001688+00;uid_sha256=2b385059297e4fa11cf172f458b4b959d37729619d4efbb8b514415378346d51` (the replica pin; both passes, both invocations) |
| label fingerprint match | yes — harness-asserted in both invocations |
| golden-set pin | `golden_set_sha256 = cd28bf61d5d114dfec467dae98e661efc76843fd36a2a955d26f848e5fff1255` (equals `sha256sum` of the committed `golden-set-fam.jsonl`); **46 active / 0 retired** |
| `world_embedding_coverage` | 8260 / 8260 (full — no backfill question on this leg) |
| embedder | `http://127.0.0.1:18080/v1`, model key `nomic-embed-text` — the test stack's `llamacpp-embeddings` (the SAME engine boot as the tech leg; the replica's own embedder never serves eval queries) |
| translator | endpoint `http://127.0.0.1:18081/v1` — the test stack's `llamacpp` serving `gemma-4-26B-A4B-it-UD-Q6_K_XL.gguf`; booted config's translator model KEY `llama3.1:8b` (name-only key, as in the tech leg) |
| translator fallbacks | 0 (harness-asserted) |
| en-scope translator calls | 0 (harness-asserted, D58 no-op leg) |
| anchor cache | fresh boot per invocation: pass 1 = miss-then-cached on all 16 xling rows, pass 2 = hit on all 16 |
| semantic threshold / limit | 0.40 / 16 (effective, read from the booted config) |
| run timestamps | run 1 `2026-08-29T22:27:52Z` (all numbers below quote run 1); run 2 `2026-08-29T22:29:19Z` (determinism leg); artifacts under `.bench/retrieval-eval/results-fam/{20260829-222739,20260829-222907}/` (operator-local) |

**Determinism leg — one divergence, reported, never averaged away.**
Across the two invocations 90 of 92 (pass, record) uid lists are
byte-identical; ONE record diverged: `fam-xl-economy-cs` (cs,
cross-lingual) received two different greedy translations across the two
JVM boots — run 1 anchored "news from economy and business" and returned
8 rows, run 2 anchored "economy and business news" and returned 3 rows
(3-row overlap; both renderings are faithful translations of the same
query). Within each invocation the runner's internal pass-1/pass-2
identity held (the anchored text is cached after pass 1), and the class
rows are byte-identical across the runs except cross-lingual's
lexical-only share (0.171 → 0.176). This is boot-to-boot LLM decode
variance in the D58 anchor leg, NOT a determinism-boundary breach: the
boundary (docs/spec/llm.md §Determinism boundary) guarantees the same DB
state yields the same rows and order for a given retrieval input, and no
input ever saw two answers within a boot; the anchored text is an LLM
output upstream of retrieval. It is recorded here as a disclosed residual
of the instrument: greedy translation across processes is not
byte-stable in general (this leg's 16 anchored texts: 15 of 16 stable
across two boots; the tech leg's 16 of 16 the same night). 0 no-op
anchors (cs 0/12, es 0/2, ru 0/1, tr 0/1) — every anchored text is a
real translation, keeping the cross-language confound check possible.

Per-class results — smoke/decision marks per rule G1 at THIS leg's own n:
**topical (n = 16) and cross-lingual (n = 16) are decision-grade; the
three temporal rows (n = 5/5/4) are smoke-only; the overall row (n = 46)
is above the floor.** No `none_expected` rows exist in this set, so
over-return columns do not apply. Capped and raw recall are equal
everywhere (largest expected set exactly 16), but the labels are much
denser than the tech leg's: mean expected-set size 14.67 (tech: 8.23) —
22 of 46 rows carry a full 16-row expected set.

| class | n | signal | capped R@16 | raw R | MRR | lexical-only share |
|---|---|---|---|---|---|---|
| overall | 46 | — | 0.115 | 0.115 | 0.411 | 0.150 |
| temporal-today | 5 | smoke | 0.116 | 0.116 | 0.400 | 0.106 |
| temporal-2h | 5 | smoke | 0.049 | 0.049 | 0.150 | 0.000 |
| temporal-24h | 4 | smoke | 0.132 | 0.132 | 0.411 | 0.000 |
| topical | 16 | **decision-grade** | 0.132 | 0.132 | 0.487 | 0.199 |
| cross-lingual | 16 | **decision-grade** | 0.116 | 0.116 | 0.419 | 0.171 |

**Movement: none — this is the fam leg's first reading.** There is no
prior fam reading to move against; the M1-950 fam smoke run established
the harness plumbing only and its numbers are not restated here.

### Cross-leg reading (DESCRIPTIVE only — rule TL3)

With both fingerprints and both golden-set pins named (tech: the frozen
5214 pin, `ccea13ba…`, coverage 1908/5214; fam: the replica 8260 pin,
`cd28bf61…`, coverage 8260/8260): the same production retrieval path,
same harness commit, same engine boot, same effective config (0.40 / 16)
returns materially less of the adjudicated relevant material on the
broad-distribution leg — overall capped recall 0.333 (tech, n = 63) vs
0.115 (fam, n = 46), MRR 0.564 vs 0.411. These numbers never enter one
sign test (TL3); they are two legs' first readings, each leg-scoped. The
descriptive decomposition: the fam world's expected sets are nearly twice
as dense (mean 14.67 vs 8.23 — its adjudicated pools are deep), so the
same 16-row budget recovers a smaller share; and its corpus is
general-news mainstream rather than the tech-dominant distribution the
retrieval knobs were tuned against. What that gap MEASURES is exactly
what this instrument exists to settle going forward: any product-wide
claim about closing it gates on both legs under TL1.

### What these numbers do not settle

At the tech leg's n = 63 the overall binomial CI on a proportion still
runs roughly **±0.12**; at the fam leg's n = 46, roughly **±0.14** —
sub-floor deltas and per-class rankings below the G1 floor remain
unresolvable on both legs, and within the decision-grade classes the CIs
(±0.24 at n = 16) keep per-topic sub-slicing suggestive. Cross-language
recall comparisons remain confounded by the anchor leg unless the
anchored text is read (the 0 no-op shares on both legs keep that check
possible). Temporal labels remain bound to each leg's own fingerprint
(D1): each corpus will drift and each section will still describe exactly
its pinned world. The fam determinism divergence (one greedy translation
in two boots) does not settle whether cross-boot decode variance is
bounded — that is a separate measurement, not an assumption this record
may make. Nothing here measures the chat agent's tool choice or prose —
only the retrieval query.

**The width-32 lever is NOT product-decided by this record.** It remains
the tech world's best measured move (the prior campaign's shadow
reading), and this record's readings change nothing about its status:
before any `infochat.chat.semantic-limit` change, the lever re-reads as
owner-run deltas on BOTH legs — T1 per leg (same golden set, matching
fingerprint and coverage pin, floor 6 one-directional discordant queries)
— and the change decision is a SEPARATE decision/ticket after this
baseline, not an outcome of it.

### Corrections during this run

None. Both golden sets were consumed read-only; their identities are
pinned above and asserted by all four runs' manifests.

## Window-armed reading — 2026-08-31 (M1-957)

The first reading with the eval lane's temporal window ARMED: the runner
now derives per-row dispatch args through the PRODUCTION
`TemporalExpressionParser` at (ZoneOffset.UTC, the world's pinned now) —
parse-GATED, never class-gated — and one pinned `Clock.fixed(worldNow)`
instant drives both the parse and the tool's ready_at cutoff alike
(docs/spec/llm.md §Determinism boundary). A parse hit dispatches
`{query, _window}`; a parse miss or a non-en row dispatches exactly
`{query}` (byte-identical to the pre-arm runs). This is the M1-938
creditor leg: before the arm, the temporal classes scored byte-identically
across M1-938's landing because the instrument ran every row unwindowed —
the landed windowing was invisible to it. Both legs ran twice (determinism
legs), same engine boot pattern as the first reading; all four runs green
with every fence asserted (label-fingerprint match, zero translator
fallbacks, en-zero translator calls, inter-pass drift identity).

### Tech leg — window-armed reading

| pin | value |
|---|---|
| repo / harness commit | `a99d57fd` (main) + the M1-957 window-arm diff (test-scope only, uncommitted at run time; zero production-path diffs) |
| DB fingerprint | `ready=5214;max_ready_at=2026-08-24 16:00:57.001472+00;uid_sha256=06ed0de15eefad172062b4b6e3dfb11713e02017b103cc8ab8e064ffbe489727` — byte-equal to the 2026-08-30 reading; both passes, both invocations |
| label fingerprint match | yes — harness-asserted in both invocations |
| golden-set pin | `golden_set_sha256 = ccea13baa4c7e0938be6307f78774c6739e14cb9cbccb282bd7cb7bd2400d725` — byte-equal; **63 active / 18 retired** |
| `world_embedding_coverage` | 1908 / 5214 — byte-equal |
| semantic threshold / limit | 0.40 / 16 — byte-equal |
| window arm | `window_arm = true`, `window_zone = Z` (UTC), `world_now = 2026-08-24T16:00:57.001472Z` — one instant for parse and cutoff; 15 rows armed (13 temporal parse hits + 2 non-temporal, below), 48 rows dispatch unchanged |
| run timestamps | run 1 `2026-08-31T11:16:22Z` (all numbers below quote run 1); run 2 `2026-08-31T11:19:03Z` (determinism leg); artifacts under `.bench/retrieval-eval/results-957/{20260831-111600,20260831-111845}/` (operator-local) |

**Determinism leg.** Two separate invocations on the pinned fingerprint:
per-query uid lists byte-identical across the two runs — 0 of 126
(pass, record) rows differ — the per-row `window` values byte-identical,
and all 32 anchored texts byte-identical across the two boots. The
runner's internal pass-1/pass-2 fingerprint and uid-identity self-checks
passed inside each run.

Per-class results — `none_expected` rows scored by over-return only;
smoke/decision marks per rule G1 at THIS leg's own n (unchanged from the
first reading: topical and cross-lingual decision-grade, the rest smoke).

| class | n | signal | capped R@16 | raw R | MRR | over-ret mean count | over-ret median age (h) | lexical-only share |
|---|---|---|---|---|---|---|---|---|
| overall | 63 | — | 0.359 | 0.359 | 0.592 | 1.667 | 83.8 | 0.089 |
| temporal-today | 5 | smoke | 0.225 | 0.225 | 0.400 | — | — | 0.000 |
| temporal-2h | 5 | smoke | 0.175 | 0.175 | 0.400 | — | — | 0.000 |
| temporal-24h | 4 | smoke | 0.4375 | 0.4375 | 0.625 | — | — | 0.000 |
| entity-location | 6 | smoke | 0.313 | 0.313 | 0.600 | 0.0 | — | 0.143 |
| entity-project | 6 | smoke | 0.4375 | 0.4375 | 0.667 | — | — | 0.320 |
| price | 5 | smoke | — | — | — | 2.0 | 83.8 | 0.100 |
| topical | 16 | **decision-grade** | 0.399 | 0.399 | 0.688 | — | — | 0.094 |
| cross-lingual | 16 | **decision-grade** | 0.384 | 0.384 | 0.578 | — | — | 0.051 |

**Movement vs the 2026-08-30 unwindowed reading is a paired INSTRUMENT
delta (same golden set, matching fingerprint and coverage pin; the single
variable is the window arm) — and it is DESCRIPTIVE: 4 recall-discordant
temporal queries, 4 up / 0 down, below rule T1's floor of 6
one-directional discordant queries, so no decision is taken on it.** The
discordant rows, absolute recalls: `tt-1` 0.125 → 0.625, `tt-3` 0.375 →
0.500 (temporal-today), `t2h-2` 0.125 → 0.500 (temporal-2h), `t24-2`
0.125 → 0.625 (temporal-24h); the other 10 temporal rows are unchanged
(7 score 0 → 0 both sides). The parse gate also armed two NON-temporal
rows whose queries carry a temporal phrase — `el-1` "what happened in
Czech today" and `pr-3` "how much is zcash worth today" (since-midnight
window); a class-gated arm would have missed both. Their recalls are
`none_expected` rows: their over-returns shrank (entity-location
over-return mean 1.0 → 0.0; price 2.2 → 2.0, the surviving over-return
ages 71.4 h → 83.8 h). Every row whose query parses to NOTHING stayed
byte-identical to the 2026-08-30 run — 48 of 48 uid lists unchanged (the
consistency proof; the two armed non-temporal rows above are the only
non-temporal movement). `t24-4` ("security news from the past day")
parse-misses by grammar and ran unwindowed — pinned in the default-suite
parse-map leg, byte-identical to the pre-arm run (0.125 → 0.125).

### Fam leg — window-armed reading

| pin | value |
|---|---|
| repo / harness commit | same harness as the tech leg |
| DB fingerprint | `ready=8260;max_ready_at=2026-08-28 15:43:18.001688+00;uid_sha256=2b385059297e4fa11cf172f458b4b959d37729619d4efbb8b514415378346d51` — byte-equal to the first reading; both passes, both invocations |
| label fingerprint match | yes — harness-asserted in both invocations |
| golden-set pin | `golden_set_sha256 = cd28bf61d5d114dfec467dae98e661efc76843fd36a2a955d26f848e5fff1255` — byte-equal; **46 active / 0 retired** |
| `world_embedding_coverage` | 8260 / 8260 — byte-equal |
| semantic threshold / limit | 0.40 / 16 — byte-equal |
| window arm | `window_arm = true`, `window_zone = Z` (UTC), `world_now = 2026-08-28T15:43:18.001688Z`; 13 rows armed (the 13 temporal parse hits), 33 rows dispatch unchanged |
| run timestamps | run 1 `2026-08-31T11:21:47Z` (all numbers below quote run 1); run 2 `2026-08-31T11:24:33Z` (determinism leg); artifacts under `.bench/retrieval-eval/results-fam-957/{20260831-112125,20260831-112411}/` (operator-local) |

**Determinism leg.** Two separate invocations: 0 of 92 (pass, record)
uid lists differ, per-row `window` values byte-identical, all 32 anchored
texts byte-identical across the two boots — including `fam-xl-economy-cs`,
which diverged across the first reading's two boots (the disclosed greedy
decode variance). This session's two boots both anchored "economy and
business news"; the first reading's run 1 anchored "news from economy and
business" — the cross-session difference is that same disclosed residual
(anchored text is an LLM output upstream of retrieval), and the row's
class-recall is unchanged across both readings.

Per-class results — smoke/decision marks per rule G1 at THIS leg's own n
(unchanged from the first reading).

| class | n | signal | capped R@16 | raw R | MRR | lexical-only share |
|---|---|---|---|---|---|---|
| overall | 46 | — | 0.118 | 0.118 | 0.483 | 0.168 |
| temporal-today | 5 | smoke | 0.116 | 0.116 | 0.600 | 0.125 |
| temporal-2h | 5 | smoke | 0.049 | 0.049 | 0.400 | 0.000 |
| temporal-24h | 4 | smoke | 0.166 | 0.166 | 0.675 | 0.000 |
| topical | 16 | **decision-grade** | 0.132 | 0.132 | 0.487 | 0.199 |
| cross-lingual | 16 | **decision-grade** | 0.116 | 0.116 | 0.419 | 0.176 |

**Movement vs the first reading — DESCRIPTIVE, same pairing as the tech
leg: 1 recall-discordant temporal query, 1 up / 0 down, below the T1
floor of 6.** The discordant row, absolute recalls: `fam-t24-2` 0.0667 →
0.2000; the other 13 temporal rows are recall-unchanged. The window DID
re-rank inside two classes without moving recall: temporal-today MRR
0.400 → 0.600 and temporal-2h MRR 0.150 → 0.400 (relevant rows climbed
when the out-of-window rows left the fused window). Every un-armed row
stayed byte-identical to the first reading — 32 of 33 uid lists unchanged;
the one difference is the `fam-xl-economy-cs` decode variance disclosed
above, not an arm effect. `fam-t24-3` ("environment news from the past
day") parse-misses by grammar and ran unwindowed — pinned in the
default-suite parse-map leg, byte-identical (0.3333 → 0.3333).

### Cross-leg reading (DESCRIPTIVE only — rule TL3)

Armed vs unwindowed, each leg at its own fingerprint and coverage pin:
tech overall 0.333 → 0.359 capped recall (MRR 0.564 → 0.592), fam overall
0.115 → 0.118 (MRR 0.411 → 0.483). These two movements never enter one
sign test (TL3) and are not pooled: two legs, two paired instrument
deltas, each below its own T1 floor, each leg-scoped. What the arm
settles is not a product claim but the instrument's own debt: the
temporal classes now respond to the landed windowing, in both worlds, in
the direction the windowing was built for — and the next retrieval change
(the width-32 lever) reads against an instrument that can see temporal
behavior.

### Disclosures of this reading

- **Parse-miss rows ran unwindowed BY DESIGN.** `t24-4` and `fam-t24-3`
  ("… news from the past day") carry no digit and no calendar token, so
  the production grammar parse-misses them; the arm dispatched them
  exactly `{query}`. The production chat dispatch layer misses the same
  phrasings — flagged to the driver as a production observation; the
  grammar is NOT widened in the eval lane.
- **Non-en rows are un-armed because en anchoring is identity.** The arm
  parses with no LLM anchor (the D58 no-op), so only en rows can carry a
  window; no non-en temporal row exists in either set (pinned by the
  default-suite leg).
- **The fam leg's one cross-session uid difference** is the disclosed
  greedy decode variance of `fam-xl-economy-cs` (the first reading's
  do-not-settle names it), not a determinism-boundary breach: within each
  M1-957 invocation the identity held (92/92).

### What this reading does not settle

**The width-32 lever remains NOT product-decided.** These window-armed
runs are the pairing 16-side for that lever's future delta (the
single-variable rule: a width change never shares a sign test with the
window arm); before any `infochat.chat.semantic-limit` change, the lever
still re-reads as owner-run deltas on BOTH legs — T1 per leg (same golden
set, matching fingerprint and coverage pin, floor 6 one-directional
discordant queries) — and the change decision remains a SEPARATE
decision/ticket. The temporal movements above are below the T1 floor on
both legs and settle nothing beyond the instrument's own visibility; the
first reading's confidence-interval paragraph stands unchanged for both
legs' ns.

Correction (2026-09-01, M1-960): the replica-restore procedure cited above is committed as `scripts/replica-restore.sh` — the script was renamed for §13 placement; its content is unchanged and the citations above are historical.

## Day-scale grammar delta reading — 2026-09-01 (M1-961)

The digit-less day-scale family now parses: the production grammar
gained one rolling-24h arm ("past/last/previous day" — the counted
"past 24 hours" without the digit), so `t24-4` (tech) and `fam-t24-3`
(fam), the two rows the 2026-08-31 reading disclosed as grammar misses,
armed through the SAME parse-gated runner arm with zero runner change.
Single variable vs the 2026-08-31 window-armed reading: this grammar
extension (repo `c7f58dee` (main) + the M1-961 diff — one production
pattern constant, one window constant, one collect call, uncommitted at
run time; zero runner/tool/dispatch diffs). Both legs ran twice
(determinism legs), one engine boot serving all four invocations; all
four green with every fence asserted (label-fingerprint match, zero
translator fallbacks, en-zero translator calls, inter-pass drift
identity).

### Tech leg — day-scale delta reading

| pin | value |
|---|---|
| repo / harness commit | `c7f58dee` (main) + the M1-961 grammar diff (production parser, uncommitted at run time; zero runner/tool/dispatch diffs) |
| DB fingerprint | `ready=5214;max_ready_at=2026-08-24 16:00:57.001472+00;uid_sha256=06ed0de15eefad172062b4b6e3dfb11713e02017b103cc8ab8e064ffbe489727` — byte-equal to the 2026-08-31 reading; both passes, both invocations |
| label fingerprint match | yes — harness-asserted in both invocations |
| golden-set pin | `golden_set_sha256 = ccea13baa4c7e0938be6307f78774c6739e14cb9cbccb282bd7cb7bd2400d725` — byte-equal; **63 active / 18 retired** |
| `world_embedding_coverage` | 1908 / 5214 — byte-equal |
| semantic threshold / limit | 0.40 / 16 — byte-equal |
| window arm | `window_arm = true`, `window_zone = Z` (UTC), `world_now = 2026-08-24T16:00:57.001472Z`; 16 rows armed (was 15 — `t24-4` is the one newly armed), 47 rows dispatch unchanged |
| run timestamps | run 1 `2026-09-01T21:32:18Z` (all numbers below quote run 1); run 2 `2026-09-01T21:34:40Z` (determinism leg); artifacts under `.bench/retrieval-eval/results-961/{20260901-213158,20260901-213419}/` (operator-local) |

**Determinism leg.** Two separate invocations on the pinned fingerprint:
per-query uid lists byte-identical across the two runs — 0 of 126
(pass, record) rows differ — the per-row `window` values byte-identical,
and all 32 anchored texts byte-identical across the two boots (and
byte-identical to the 2026-08-31 reading). The runner's internal
pass-1/pass-2 fingerprint and uid-identity self-checks passed inside
each run.

**Movement vs the 2026-08-31 reading is a paired INSTRUMENT delta (same
golden set, matching fingerprint and coverage pin; the single variable
is the grammar extension) — and it is DESCRIPTIVE, below rule T1's floor
of 6 one-directional discordant queries BY CONSTRUCTION: exactly one
newly-armed row, its recall unchanged (0.125 → 0.125).** The row's
grounding set shrank to its window — `t24-4` returned 7 posts
unwindowed, 2 windowed, the one relevant post climbing rank 2 → 1 — so
overall capped recall is unchanged (0.359 → 0.359) and overall MRR
moved 0.592 → 0.601 (exactly the row's rank-2 → rank-1 step over the 57
expected rows). Every OTHER row's dispatch is byte-identical to the
2026-08-31 run — 62 of 63 uid lists unchanged; the armed row is the only
movement.

### Fam leg — day-scale delta reading

| pin | value |
|---|---|
| repo / harness commit | same harness as the tech leg |
| DB fingerprint | `ready=8260;max_ready_at=2026-08-28 15:43:18.001688+00;uid_sha256=2b385059297e4fa11cf172f458b4b959d37729619d4efbb8b514415378346d51` — byte-equal to the 2026-08-31 reading; both passes, both invocations |
| label fingerprint match | yes — harness-asserted in both invocations |
| golden-set pin | `golden_set_sha256 = cd28bf61d5d114dfec467dae98e661efc76843fd36a2a955d26f848e5fff1255` — byte-equal; **46 active / 0 retired** |
| `world_embedding_coverage` | 8260 / 8260 — byte-equal |
| semantic threshold / limit | 0.40 / 16 — byte-equal |
| window arm | `window_arm = true`, `window_zone = Z` (UTC), `world_now = 2026-08-28T15:43:18.001688Z`; 14 rows armed (was 13 — `fam-t24-3` is the one newly armed), 32 rows dispatch unchanged |
| run timestamps | run 1 `2026-09-01T21:37:02Z` (all numbers below quote run 1); run 2 `2026-09-01T21:39:26Z` (determinism leg); artifacts under `.bench/retrieval-eval/results-fam-961/{20260901-213640,20260901-213903}/` (operator-local) |

**Determinism leg.** Two separate invocations: 0 of 92 (pass, record)
uid lists differ, per-row `window` values byte-identical, all 32
anchored texts byte-identical across the two boots — `fam-xl-economy-cs`
anchored "economy and business news" in both, the M1-957 session's
rendering; the first reading's cross-boot divergence did not recur (the
disclosed greedy decode variance remains the recorded residual).

**Movement — DESCRIPTIVE, same pairing, below the T1 floor by
construction: 0 recall-discordant queries.** `fam-t24-3` returned 16
posts on both sides with the same 5 hits (recall 0.3333 → 0.3333); the
window re-ordered the set — the in-window posts climbed, hit ranks 1, 2,
7, 11, 13 → 1, 2, 4, 8, 9 — without moving the first-relevant rank, so
overall capped recall (0.118 → 0.118) and MRR (0.483 → 0.483) are both
unchanged. Every other row's dispatch is byte-identical to the
2026-08-31 reading — 45 of 46 uid lists unchanged.

### Disclosures of this reading

- **The two parse-miss rows of the 2026-08-31 reading are now ARMED.**
  `t24-4` and `fam-t24-3` ("… news from the past day") parse to a
  rolling PT24H through the new grammar arm and dispatched
  `{query, _window=PT24H}`; the M1-957 section's parse-miss disclosure
  is superseded IN CURRENT STATE (its text stands, append-only) — the
  production chat dispatch layer windows the same phrasings this change
  ships, per the same parse.
- **The non-en anchored-translation widening (M1-961's recorded
  judgment).** The parse runs over the anchored query, so a non-en
  day-scale ask whose greedy rendering lands in the family now windows
  while a rendering that paraphrases away misses — an already-open,
  boot-dependent class bounded by the same determinism boundary as
  every digit-bearing phrasing. No non-en row in either set carries the
  family (all active temporal rows are en, pinned by the default-suite
  leg); this reading's anchored texts were byte-identical across boots
  and vs the 2026-08-31 reading.
- **M1-959 pairing note.** M1-959 (pending) pairs its 32-side against
  "the post-M1-957 16-side runs"; with this reading landed first, the
  single-variable rule makes THIS reading's runs the 16-side pairing
  base — M1-959's owner runs re-read both sides fresh (the rule governs
  the paired runs, not history).

### What this reading does not settle

**The width-32 lever remains NOT product-decided** (restated): these
runs are a grammar-extension delta, never a width reading; before any
`infochat.chat.semantic-limit` change, the lever still re-reads as
owner-run deltas on BOTH legs — T1 per leg (same golden set, matching
fingerprint and coverage pin, floor 6 one-directional discordant
queries) — and the change decision remains a SEPARATE decision/ticket.
The movements above are below the T1 floor on both legs and settle
nothing beyond the two rows' own arm status; the first reading's
confidence-interval paragraph stands unchanged for both legs' ns.

## Width-32 delta reading — 2026-09-01 (M1-959)

The pre-registered width-32 lever re-read as owner-run deltas on BOTH
legs (the binding rule this record carries from its first section):
`infochat.chat.semantic-limit` 16→32 was the ONLY variable — same golden
sets, same frozen fingerprints and coverage pins, same window arm, same
anchored texts (all byte-verified below). The harness gained one
test-scope change first: the scorer's recall cap parameterized on the
run's effective limit (repo `258e94ae` (main) + the M1-959 diff
— scorer k-bearing overload + runner call site, test-scope only,
uncommitted at run time; zero production-path diffs). Each leg ran a
FRESH 16-side control and then the 32-side twice (determinism leg), one
engine boot serving all six invocations; all six green with every fence
asserted (label-fingerprint match, zero translator fallbacks, en-zero
translator calls, inter-pass drift identity).

**Pairing (single variable, per the M1-961 section's pairing note).**
Each leg's 16-side is the fresh control run on the SAME engine boot as
that leg's 32-side pair — and both controls are byte-identical to the
M1-961 day-scale reading's run 1 (tech `20260901-213158`, fam
`20260901-213640`): 63/63 and 46/46 (pass, record) uid lists, per-row
window values, and all 32 anchored texts byte-equal per leg. The paired
runs are same-boot, so the disclosed cross-boot decode variance cannot
enter the comparison; the single variable is the limit.

### Tech leg — width-32 delta reading

| pin | value |
|---|---|
| repo / harness commit | `258e94ae` (main) + the M1-959 scorer diff (test-scope only, uncommitted at run time; zero runner arm/tool/dispatch diffs) |
| DB fingerprint | `ready=5214;max_ready_at=2026-08-24 16:00:57.001472+00;uid_sha256=06ed0de15eefad172062b4b6e3dfb11713e02017b103cc8ab8e064ffbe489727` — byte-equal to the 2026-09-01 reading; both passes, all three invocations |
| label fingerprint match | yes — harness-asserted in all three invocations |
| golden-set pin | `golden_set_sha256 = ccea13baa4c7e0938be6307f78774c6739e14cb9cbccb282bd7cb7bd2400d725` — byte-equal; **63 active / 18 retired** |
| `world_embedding_coverage` | 1908 / 5214 — byte-equal |
| semantic threshold / limit | 0.40 / **32** (the 16-side control: 0.40 / 16, pinned in its manifest) |
| window arm | `window_arm = true`, `window_zone = Z` (UTC), `world_now = 2026-08-24T16:00:57.001472Z`; 16 rows armed, 47 dispatch unchanged — byte-identical to the 2026-09-01 reading |
| run timestamps | 16-side control `2026-09-01T22:28:40Z`; 32-side run 1 `2026-09-01T22:30:05Z` (all numbers below quote 32-side run 1); run 2 `2026-09-01T22:32:10Z` (determinism leg); artifacts under `.bench/retrieval-eval/results-959/{20260901-222829,20260901-222952,20260901-223155}/` (operator-local) |

**Determinism leg.** Two separate 32-side invocations on the pinned
fingerprint: per-query uid lists byte-identical across the two runs —
0 of 126 (pass, record) rows differ — per-row `window` values
byte-identical, all 32 anchored texts byte-identical across the two
boots. The runner's internal pass-1/pass-2 fingerprint and uid-identity
self-checks passed inside each run.

Per-class results (32-side run 1; capped R@32 equals raw R at this
leg's label sizes — max |E| = 16; smoke/decision marks per rule G1 at
THIS leg's own n, unchanged):

| class | n | signal | capped R@32 | raw R | MRR | over-ret mean count | over-ret median age (h) | lexical-only share |
|---|---|---|---|---|---|---|---|---|
| overall | 63 | — | 0.392 | 0.392 | 0.602 | 1.667 | 83.8 | 0.054 |
| temporal-today | 5 | smoke | 0.300 | 0.300 | 0.410 | — | — | 0.000 |
| temporal-2h | 5 | smoke | 0.175 | 0.175 | 0.400 | — | — | 0.000 |
| temporal-24h | 4 | smoke | 0.469 | 0.469 | 0.750 | — | — | 0.000 |
| entity-location | 6 | smoke | 0.313 | 0.313 | 0.600 | 0.0 | — | 0.143 |
| entity-project | 6 | smoke | 0.458 | 0.458 | 0.667 | — | — | 0.230 |
| price | 5 | smoke | — | — | — | 2.0 | 83.8 | 0.100 |
| topical | 16 | **decision-grade** | 0.399 | 0.399 | 0.688 | — | — | 0.056 |
| cross-lingual | 16 | **decision-grade** | 0.463 | 0.463 | 0.578 | — | — | 0.019 |

**Movement vs the same-boot 16-side control is a paired INSTRUMENT
delta (same golden set, matching fingerprint and coverage pin; the
single variable is the limit) — T1 APPLIES: 12 recall-discordant
queries, 12 up / 0 down, above the floor of 6 one-directional
discordant queries; the two-sided sign test over the 12 discordant
queries gives p = 0.0005.** The discordant rows, absolute recalls
(16-side → 32-side): `tt-1` 0.625 → 0.875, `tt-4` 0.000 → 0.125
(temporal-today), `t24-2` 0.625 → 0.750 (temporal-24h), `ep-1`
0.625 → 0.750 (entity-project), `xl-ai-cs-b` / `xl-ai-es-b` /
`xl-ai-ru-b` / `xl-ai-tr-b` 0.688 → 0.750 each, `xl-cyber-cs-b` /
`xl-cyber-es-b` 0.250 → 0.563 each, `xl-cyber-ru-b` 0.250 → 0.313,
`xl-cyber-tr-b` 0.250 → 0.313 (cross-lingual; the sixteen-expected
rows gain most — their grounding sets exceed the 16-slot return). The
other 45 expected-uid rows are recall-unchanged; the none_expected
rows' over-returns are size-unchanged (price 2.0 → 2.0,
entity-location 0.0 → 0.0). Per-class movement is DESCRIPTIVE:
cross-lingual 0.384 → 0.463, temporal-today 0.225 → 0.300,
temporal-24h 0.438 → 0.469, entity-project 0.438 → 0.458, overall
0.359 → 0.392; topical, temporal-2h, and entity-location are
recall-unchanged; overall MRR 0.601 → 0.602.

### Fam leg — width-32 delta reading

| pin | value |
|---|---|
| repo / harness commit | same harness as the tech leg |
| DB fingerprint | `ready=8260;max_ready_at=2026-08-28 15:43:18.001688+00;uid_sha256=2b385059297e4fa11cf172f458b4b959d37729619d4efbb8b514415378346d51` — byte-equal to the 2026-09-01 reading; both passes, all three invocations |
| label fingerprint match | yes — harness-asserted in all three invocations |
| golden-set pin | `golden_set_sha256 = cd28bf61d5d114dfec467dae98e661efc76843fd36a2a955d26f848e5fff1255` — byte-equal; **46 active / 0 retired** |
| `world_embedding_coverage` | 8260 / 8260 — byte-equal |
| semantic threshold / limit | 0.40 / **32** (the 16-side control: 0.40 / 16, pinned in its manifest) |
| window arm | `window_arm = true`, `window_zone = Z` (UTC), `world_now = 2026-08-28T15:43:18.001688Z`; 14 rows armed, 32 dispatch unchanged — byte-identical to the 2026-09-01 reading |
| run timestamps | 16-side control `2026-09-01T22:34:35Z`; 32-side run 1 `2026-09-01T22:36:40Z` (all numbers below quote 32-side run 1); run 2 `2026-09-01T22:38:10Z` (determinism leg); artifacts under `.bench/retrieval-eval/results-fam-959/{20260901-223413,20260901-223621,20260901-223801}/` (operator-local) |

**Determinism leg.** Two separate 32-side invocations: 0 of 92
(pass, record) uid lists differ, per-row `window` values
byte-identical, all 32 anchored texts byte-identical across the two
boots — `fam-xl-economy-cs` anchored "economy and business news" in
both (the M1-957 session's rendering; the first-reading cross-boot
divergence did not recur, the disclosed residual stands).

Per-class results (32-side run 1; capped R@32 equals raw R at this
leg's label sizes — max |E| = 15; marks unchanged):

| class | n | signal | capped R@32 | raw R | MRR | lexical-only share |
|---|---|---|---|---|---|---|
| overall | 46 | — | 0.133 | 0.133 | 0.485 | 0.169 |
| temporal-today | 5 | smoke | 0.116 | 0.116 | 0.600 | 0.125 |
| temporal-2h | 5 | smoke | 0.049 | 0.049 | 0.400 | 0.000 |
| temporal-24h | 4 | smoke | 0.182 | 0.182 | 0.675 | 0.000 |
| topical | 16 | **decision-grade** | 0.158 | 0.158 | 0.490 | 0.183 |
| cross-lingual | 16 | **decision-grade** | 0.129 | 0.129 | 0.422 | 0.197 |

**Movement vs the same-boot 16-side control — same pairing, T1
APPLIES: 8 recall-discordant queries, 8 up / 0 down, above the floor
of 6; the two-sided sign test over the 8 discordant queries gives
p = 0.008.** The discordant rows, absolute recalls (16-side →
32-side): `fam-t24-2` 0.200 → 0.267 (temporal-24h), `fam-top-ai`
0.133 → 0.333, `fam-top-cyber` 0.071 → 0.143, `fam-top-football`
0.067 → 0.133, `fam-top-middle-east` 0.000 → 0.077 (topical),
`fam-xl-football-cs` / `fam-xl-football-es` 0.067 → 0.133 each,
`fam-xl-middle-east-cs` 0.000 → 0.077 (cross-lingual). The other 30
expected-uid rows are recall-unchanged. Per-class movement is
DESCRIPTIVE: topical 0.132 → 0.158, cross-lingual 0.116 → 0.129,
temporal-24h 0.166 → 0.182, overall 0.118 → 0.133; temporal-today,
temporal-2h, and overall MRR (0.483 → 0.485) are effectively
unchanged.

### Cross-leg reading (DESCRIPTIVE only — rule TL3)

Both legs' movements are above the T1 floor and one-directional (12 up
/ 0 down tech; 8 up / 0 down fam), and both legs' discordant queries
sit overwhelmingly in the classes whose grounding sets meet or exceed
the 16-slot return (cross-lingual on the tech set, topical and
cross-lingual on the fam set). The two legs are NEVER pooled (TL3):
each leg's sign test stands on its own discordant count, and any
product-wide claim about the limit needs both legs' owner-run deltas
read together under TL1 — which this section provides, without
deciding.

### What this reading does not settle

**The width-32 lever remains NOT product-decided by these deltas
(restated): every movement above is a paired instrument delta on a
frozen world, not a product reading; before any
`infochat.chat.semantic-limit` change, the change decision is a
SEPARATE decision/ticket.** A result on one leg stays leg-scoped
(rule TL2): the tech leg's cross-lingual gain does not transfer to
the fam leg's distribution, and the fam leg's topical gain does not
transfer to the tech leg's; absolute counts only (N1), never
percentage points. The two legs' runs share one engine boot and
byte-equal pins on every key this record pins; the runs execute the
production tool bean under the runner's refusals, unchanged.
