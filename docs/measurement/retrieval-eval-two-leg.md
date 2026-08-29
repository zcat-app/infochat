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
