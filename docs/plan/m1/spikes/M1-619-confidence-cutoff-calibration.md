# M1-619 — chat confident-grounding cutoff calibration

Status: spike / calibration report, not spec. Sibling of the M1-616
semantic-threshold calibration (`M1-616-confidence-*`, same directory). Measured
2026-07-13 against the live READY corpus (5543 posts, `nomic-embed-text` 768-d).

## Decision (TL;DR)

**Move `ChatAgent.CONFIDENT_SIMILARITY_CUTOFF` from `0.75` to `0.65`.**

The measurement shows `0.75` is mis-calibrated for `nomic-embed-text` on this
corpus: only **4 of 11** genuinely-grounded on-domain queries reach the confident
band at `0.75` (36% affordance recall), so the M1-618 "more like this" affordance
almost never fires and nearly every real on-domain question is downgraded to a
clarifying question. `0.65` restores **~82%** affordance recall while keeping the
one spurious off-domain near-match ("banana bread recipe", best-sim 0.629) out of
the confident band, and preserves a thin marginal band `(0.60, 0.65)` above the
M1-616 grounding floor for the genuinely-weakest hits.

## What this constant does (and does not) gate

`CONFIDENT_SIMILARITY_CUTOFF` gates **reply PROSE only**. On a grounded chat turn
the pre-fetch's best semantic similarity is compared against it:

- best similarity **>= cutoff** → CONFIDENT → `AFFORDANCE_DIRECTIVE` (answer, then
  offer "you can ask for more posts related to any one I cited").
- best similarity **< cutoff** → MARGINAL → `CLARIFY_DIRECTIVE` (ask ONE narrowing
  question instead of grounding a weak guess).

It never changes **which** posts are retrieved or their order — that set is
SQL-decided and byte-identical regardless of this constant (**D19**; the M1-618
determinism regression guard `refinementDirectiveIsAppendedWithoutAlteringThe
RetrievedSet` remains the backstop). Moving the number changes only whether the
LLM writes a question or an offer.

It sits **ABOVE** the M1-616 grounding floor: a post must first clear
`infochat.chat.semantic-threshold` (cosine distance `< 0.40`, i.e.
similarity = `1 − 0.40 = 0.60`) to be retrieved at all. So retrieved semantic
posts span `(0.60, 1.0]` and the marginal band is `(0.60, cutoff)`. The 0.40 gate
(M1-616) is a **separate, lower** boundary and is out of scope here — this ticket
tunes only the higher confident/marginal boundary.

## Method

Harness: `M1-619-confidence-sweep.py` (this directory), a self-contained sibling
of `M1-616-threshold-eval.py` following the M1-609/M1-616 family conventions
(stdlib-only, `docker exec … psql`, checked-in JSONL of human labels). Per query
it:

1. embeds the query on the **same** local `nomic-embed-text` backend the provider
   uses (OpenAI-compatible `/v1/embeddings`), so the query vector is bit-identical
   to the running bot's;
2. computes the exact MIN cosine distance to any READY post via a forced
   sequential scan (`enable_indexscan=off`) — ground-truth distances, not the
   approximate HNSW set;
3. counts lexical hits via the real `search_tsv` column (V58) and the same
   `plainto_tsquery('english', …)` production's hybrid retrieval uses;
4. classifies the turn — mirroring `ChatAgent.isMarginalGrounding` exactly —
   across a cutoff sweep `0.60 … 0.80`:
   - no semantic hit under the 0.40 gate **and** no lexical hit → **EMPTY**
     (general-knowledge; no directive);
   - no semantic hit under the gate **but** a lexical hit → **MARGINAL (lexical
     -only)** — grounded only via the lexical arm, marginal by construction;
   - semantic hit under the gate, best-sim `< cutoff` → **MARGINAL (semantic)** →
     clarify;
   - semantic hit under the gate, best-sim `>= cutoff` → **CONFIDENT** →
     affordance.

Labeled set: `M1-619-query-samples.jsonl` — the 16 M1-616 queries plus an explicit
`confidence` expectation derived from M1-616's hand-verified relevant sets. The 11
on-domain queries (exact-term / on-topic / paraphrased, each with `>= 1` genuinely
relevant post) are labeled `confident` — a working grounding should answer + offer,
not clarify. The 5 off-domain queries (no genuinely-relevant post) are labeled
`empty` — they should never reach the confident band.

### Why the whole corpus, not one subscription scope

`CONFIDENT_SIMILARITY_CUTOFF`, like the M1-616 floor it sits above, is a single
scope-INDEPENDENT code constant, so — following the M1-616 harness's own rationale
— it is calibrated against the whole READY corpus, not one scope's subscribed
subset. This is also the **conservative** unit: the whole-corpus exact best-sim is
an **upper bound** on what a real chat turn's `isMarginalGrounding` observes, in
two independent ways:

- **Scope.** Production `SemanticSearchTool` filters to the user's subscribed
  sources; a subset can only remove candidates, so scope-filtered best-sim
  `<=` whole-corpus best-sim.
- **Index.** Production reads the approximate HNSW index, which can miss the true
  nearest neighbour; the exact scan here can only return an equal-or-nearer
  neighbour, i.e. equal-or-higher best-sim.

So if the whole-corpus best-sim distribution clusters below a candidate cutoff, the
production case the constant actually gates clusters at or below that **a
fortiori**. The live scope-filtered run (below) confirms the direction on the real
path.

## Measured best-grounded similarity

Whole-corpus exact scan, 2026-07-13. `band@0.75` = classification at the current
constant.

| best-sim | dist | lex | label | category | band@0.75 | query |
|---:|---:|---:|---|---|---|---|
| 0.862 | 0.138 | 1 | confident | exact-term | CONFIDENT | GhostLock Linux flaw root and container escape |
| 0.813 | 0.187 | 78 | confident | on-topic | CONFIDENT | large language model reasoning benchmark |
| 0.787 | 0.213 | 0 | confident | exact-term | CONFIDENT | Zcash Zebra node software release |
| 0.761 | 0.239 | 2 | confident | exact-term | CONFIDENT | Microsoft SharePoint RCE actively exploited |
| 0.732 | 0.268 | 1 | confident | paraphrased | MARGINAL | making large AI models smaller … less memory |
| 0.727 | 0.273 | 0 | confident | paraphrased | MARGINAL | software that autonomously carries out multi-step tasks with AI |
| 0.699 | 0.301 | 0 | confident | on-topic | MARGINAL | ransomware attack encrypting victim files |
| 0.692 | 0.308 | 1 | confident | on-topic | MARGINAL | Zcash shielded private transactions |
| 0.666 | 0.334 | 0 | confident | paraphrased | MARGINAL | how attackers hide malicious software from antivirus detection |
| 0.638 | 0.362 | 0 | confident | paraphrased | MARGINAL | keeping your web browsing private from advertisers and trackers |
| **0.629** | 0.371 | 0 | **empty** | off-domain | MARGINAL | **banana bread recipe** (spurious near-match — the M1-616 example) |
| 0.624 | 0.376 | 0 | confident | on-topic | MARGINAL | government surveillance and online censorship |
| 0.591 | 0.409 | 0 | empty | off-domain | EMPTY | how to train for a marathon |
| 0.546 | 0.454 | 0 | empty | off-domain | EMPTY | growing tomatoes in a home vegetable garden |
| 0.517 | 0.483 | 0 | empty | off-domain | EMPTY | tips for getting a toddler to sleep through the night |
| 0.484 | 0.516 | 0 | empty | off-domain | EMPTY | how to treat a sprained ankle at home |

**On-domain genuine groundings span 0.624–0.862, clustering 0.62–0.73**; only
exact-term and one well-populated on-topic query exceed 0.75. **Off-domain best-sim
tops out at 0.629**, and only "banana bread recipe" (0.629) sneaks under the 0.40
gate — the other four fall below the gate entirely (EMPTY, correctly
general-knowledge). Note the overlap at the very bottom: the weakest genuine
on-domain hit (gov surveillance, 0.624) sits *below* the single spurious near-match
(0.629), so no cutoff perfectly separates the two classes — the ~0.62–0.63 zone is
inherently noisy. This is expected and is precisely why the 0.40 gate, not this
cutoff, is the primary spurious-grounding defense (M1-616).

## The cutoff sweep

`C` = confident, `Ms` = marginal-semantic, `Ml` = marginal-lexical, `E` = empty
(16 queries total). `affRecall%` = fraction of the 11 `confident`-labeled queries
that land CONFIDENT; `spurConf%` = fraction of the 5 `empty`-labeled queries that
land CONFIDENT; `sep%` = `affRecall − spurConf` (Youden-J style figure of merit).

| cutoff | C | Ms | Ml | E | affRecall% | spurConf% | sep% |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 0.60 | 12 | 0 | 0 | 4 | 100.0 | 20.0 | 80.0 |
| 0.62 | 12 | 0 | 0 | 4 | 100.0 | 20.0 | 80.0 |
| **0.63** | 10 | 2 | 0 | 4 | 90.9 | 0.0 | **90.9** (peak) |
| 0.64 | 9 | 3 | 0 | 4 | 81.8 | 0.0 | 81.8 |
| **0.65** | 9 | 3 | 0 | 4 | **81.8** | **0.0** | 81.8 |
| 0.66 | 9 | 3 | 0 | 4 | 81.8 | 0.0 | 81.8 |
| 0.67–0.69 | 8 | 4 | 0 | 4 | 72.7 | 0.0 | 72.7 |
| 0.70–0.72 | 6 | 6 | 0 | 4 | 54.5 | 0.0 | 54.5 |
| 0.73 | 5 | 7 | 0 | 4 | 45.5 | 0.0 | 45.5 |
| **0.74–0.76** | 4 | 8 | 0 | 4 | **36.4** | 0.0 | 36.4 (**current 0.75**) |
| 0.77–0.78 | 3 | 9 | 0 | 4 | 27.3 | 0.0 | 27.3 |
| 0.79–0.80 | 2 | 10 | 0 | 4 | 18.2 | 0.0 | 18.2 |

(No query in this set is grounded only via the lexical arm — `Ml` is 0 throughout —
because every on-domain query also has a semantic hit under the gate. The
lexical-only → marginal branch is exercised on real data by the live scope-filtered
run below, where the brand-name query `Tenda` grounds via the lexical arm alone.)

## Characterising the current 0.75

At `0.75`, only **4/11 (36%)** genuine groundings are CONFIDENT — the three
exact-term queries and the one on-topic query ("LLM reasoning benchmark") backed by
15 near-duplicate arxiv posts. **Every paraphrase and 3 of 4 on-topic queries** —
all with genuinely relevant retrieved posts — are downgraded to a clarifying
question. The affordance path effectively fires only for exact-term / near-duplicate
queries; the ticket's live finding put the extreme at an exact post-title query
(~0.94). This defeats M1-618's intent: the bot was meant to answer confidently and
*offer* more when grounding succeeds, but at 0.75 it almost always asks a narrowing
question first even when the retrieval is good.

## Recommendation: 0.65

**Peak separation on the labeled set is 0.63 (90.9%)**, but 0.63 sits a razor
0.001 above the spurious "banana bread" match (0.629) — a knife-edge on one noisy
data point, not a robust boundary. The separation *plateau* is **0.64–0.66 (81.8%,
0% spurious-confident)**. `0.65` is chosen mid-plateau because it:

1. **Restores the affordance path** — 82% recall vs 36% at 0.75, a 2.25× lift; the
   affordance now fires for solid on-domain groundings instead of only exact-term
   ones.
2. **Keeps spurious matches out** — the one off-domain near-match that clears the
   0.40 gate (0.629) stays MARGINAL (clarify), so `spurConf` is 0%.
3. **Has margin** — ~0.02 above the observed spurious ceiling (0.629), so it is not
   fragile to a single embedding-noise data point the way 0.63 is.
4. **Preserves a marginal band** — `(0.60, 0.65)`, a narrow but non-empty zone just
   above the grounding floor, so M1-618's clarify recovery still fires for the
   genuinely-weakest hits (best match only ~0.60–0.65 similar, i.e. barely past the
   admit floor). Collapsing the cutoff to the floor (0.60) would delete the
   semantic clarify band entirely, which over-corrects.
5. **Holds across both measurement units** — the whole-corpus sweep and the live
   scope-filtered run (below) both give ~82% affordance recall at 0.65, so the
   a-fortiori production shift does not undermine it.

Alternatives considered: **retain 0.75** — rejected, the measured 36% recall shows
it is mis-calibrated. **0.63** (labeled-set peak) — rejected as a one-datapoint
knife-edge. **0.60** (collapse to the floor) — rejected, it deletes the marginal
band and its clarify recovery.

## Corroboration: live scope-filtered measurement

The 2026-07-13 live-verification run measured best-grounded similarity on the real
production path (scope-filtered to the test-user's subscription, HNSW index).
On-domain queries clustered **0.62–0.73** (ransomware 0.727, phishing 0.71, data
breach 0.714, critical-vuln 0.70, malware-campaign 0.702, zero-day 0.698, "what's
new in AI" 0.692, OpenAI 0.684, supply-chain 0.623, CISA 0.622); off-domain queries
went to general-knowledge; the brand query `Tenda` grounded via the lexical arm
alone (marginal-lexical → clarify). This is the same 0.62–0.73 on-domain cluster the
whole-corpus sweep shows, confirming that (a) 0.75 downgrades essentially all
on-domain queries and (b) 0.65 lands ~82% of them confident on the production path
too — consistent with the whole-corpus result, as the a-fortiori bound predicts.

## What changes

- `ChatAgent.CONFIDENT_SIMILARITY_CUTOFF`: `0.75` → `0.65` (+ the constant's
  comment: marginal band `(0.60, 0.65)`; the "floor above 0.65 → clarify never
  fires" no-op note).
- `docs/design/05-llm-and-embeddings.md` §5.4.6: the `0.75` mentions and the
  `(0.60, 0.75)` marginal-band description → `0.65` / `(0.60, 0.65)`.
- The three M1-618 boundary tests in `ChatAgentTest.java`
  (`lowConfidenceGroundingTriggersClarifyDirective`,
  `confidentGroundingSurfacesMoreLikeThisAffordanceAndDoesNotClarify`,
  `isMarginalGroundingSeparatesConfidentFromWeak`): fixtures re-pinned to the new
  0.65 boundary.

The retrieved set stays byte-identical (D19); only the prose-gate number moves.
