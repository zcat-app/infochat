# M1-616 — Calibrating `infochat.chat.semantic-threshold` on the live corpus

Status: spike measurement, not spec. Data captured 2026-07-12 against the live
`remote-llm` corpus (5268 READY posts, all embedded with local `nomic-embed-text`
768-d, 100% coverage — D54). Reproduce with the sibling harness:

```
python3 docs/plan/m1/spikes/M1-616-threshold-eval.py \
    --samples docs/plan/m1/spikes/M1-616-query-samples.jsonl \
    --out .scratch/m1-616/sweep.json
```

## TL;DR

- **The current default `infochat.chat.semantic-threshold = 0.5` is too loose.**
  It is *strictly dominated* by 0.42: both give identical recall@8 (60.1%), but
  0.5 admits an 4.5× larger candidate pool (mean 558 vs 123 posts under the gate)
  and fires spurious off-domain grounding on 4 of 5 off-domain probes vs 2 of 5.
- **Recommended: lower the default to `0.40`.** It sits just below the recall
  knee (0.42), sacrificing only 2.6 pp of macro recall@8 — all of it from a
  single query whose relevant posts are anomalously distant — while cutting
  off-domain spurious grounding from 4/5 queries to 1/5 and shrinking the
  grounding candidate pool ~8× (558 → 71 posts under the gate).
- The gate remains a hard, deterministic cosine-distance cutoff (D19); only the
  number moves, not the mechanism.
- **Do NOT borrow the collector's 0.18 linking threshold.** Query→post distances
  run systematically larger than the post→post distances that gate sees (§5); the
  two are not comparable.
- Applying the value was **more than a one-line edit** (it collides with this
  ticket's original scope — see §7). The operator approved applying it, so 0.40
  is now the in-code default in all three sites the value lives in.

## 1. What was measured, and how faithfully

The chat `semanticSearch` pre-fetch (`SemanticSearchTool`, M1-589) runs, on every
chat turn, a pgvector cosine query and folds the survivors into the prompt as
grounding — or, when nothing clears the gate, the model answers from general
knowledge. The gate is `(pe.embedding <=> query) < infochat.chat.semantic-threshold`
(a **strict** `<`, cosine distance), followed by a top-k cap
(`infochat.chat.semantic-limit`, default **8**).

The harness reproduces that read path faithfully:

- **Query embedding** — `POST http://localhost:11434/v1/embeddings`,
  `{"model":"nomic-embed-text","input":[query]}`, `data[0].embedding`. This is the
  OpenAI-compatible shape `OpenAiCompatibleEmbeddingProvider` uses, so the query
  vector is bit-identical to the running bot's (embedding confirmed deterministic:
  three repeat calls returned identical vectors).
- **Retrieval** — exact pgvector cosine `<=>` over `post JOIN post_embedding`
  scoped to `status='READY'`, ordered by distance. This is the inner subquery of
  `SemanticSearchTool` **minus the `source_subscription` scope filter**: the sweep
  is measured over the whole READY corpus deliberately (§6).
- **Returned set at (k, T)** — posts with distance strictly `< T`, nearest first,
  truncated to k. The k nearest under T are always a prefix of the N nearest
  overall, so one top-N pull per query supplies every returned set in the sweep.

### Harness-fidelity note: force exact scan, not the HNSW index

An early run reported the nearest post for `"banana bread recipe"` at 0.549 and
did **not** surface the "Nano Banana" image-model post — contradicting the
ticket's provenance (0.378) and the harness's own whole-corpus band counts. Cause:
`ORDER BY embedding <=> const LIMIT n` drives the **HNSW index**, whose
*approximate* search at the default `ef_search` silently missed the true near
neighbours. Forcing a sequential exact scan (`SET enable_indexscan = off`) fixed
it — the exact nearest is **0.371** (the "Nano Banana 2" post), reproducing the
provenance's 0.378 within query-wording noise.

Two consequences:
1. Threshold calibration needs **exact** ground-truth distances, so the harness
   forces the exact scan. All numbers below are exact.
2. The HNSW-recall gap is real and production-relevant: at default `ef_search` the
   index can miss a neighbour sitting at distance 0.371. That is a *retrieval
   quality* concern independent of the threshold, and it belongs to **M1-617**
   (hybrid retrieval), not here. Production mitigates it partly via
   `hnsw.iterative_scan = strict_order`, but the recall ceiling of the ANN index
   is worth M1-617's attention.

## 2. The labeled query set

16 queries (`M1-616-query-samples.jsonl`), spanning the four required classes:

| class | n | purpose |
|---|---|---|
| exact-term / product / CVE | 3 | tight recall probes (Zebra releases ×7, SharePoint RCE ×2, GhostLock ×1) |
| on-topic | 4 | ransomware, Zcash shielded, LLM-reasoning benchmarks, surveillance/censorship |
| paraphrased | 4 | topic asked in words the target posts do **not** use (malware evasion, anti-tracking, model compression, AI agents) |
| deliberately off-domain | 5 | banana bread, marathon training, sprained ankle, growing tomatoes, toddler sleep — **empty relevant set**, every returned post is a false positive |

**Labels are human, not embedding-derived.** Relevant UIDs were drawn by keyword /
full-text search on `title`+`body` (independent of the vector) and verified by
reading the posts, then finalised by TREC-style pooling of the retrieved top-12
(clearly on-topic retrieved posts folded in; topically-adjacent-but-distinct posts
confirmed non-relevant and excluded). Keyword-seeded relevant posts that the
embedding ranks *poorly* are retained (e.g. the Zebra release posts, outranked by
Zcash digests) so recall is not silently defined by the embedding itself.

## 3. Recall / precision vs threshold (k = 8, the production default)

`recall@8` and `precision@8` are macro-averaged over the 11 on-corpus queries;
`pool` is the mean whole-corpus count of posts under the gate (all 16 queries);
`off-FP` is how many of the 5 off-domain queries return ≥1 spurious post.

| T | recall@8 | prec@8 | mean pool | off-FP | off mean FP |
|------|---------|--------|-----------|--------|-------------|
| 0.30 | 28.0% | 66.5% | 3.4 | 0/5 | 0.0 |
| 0.32 | 34.4% | 67.2% | 5.8 | 0/5 | 0.0 |
| 0.34 | 37.0% | 51.4% | 10.7 | 0/5 | 0.0 |
| 0.36 | 49.5% | 49.0% | 19.9 | 0/5 | 0.0 |
| 0.38 | 52.9% | 53.8% | 38.2 | 1/5 | 0.2 |
| **0.40** | **57.5%** | **54.5%** | **71.5** | **1/5** | **0.2** |
| 0.42 | 60.1% | 53.4% | 123.2 | 2/5 | 0.4 |
| 0.44 | 60.1% | 53.4% | 192.8 | 2/5 | 1.0 |
| 0.46 | 60.1% | 53.4% | 290.4 | 3/5 | 2.2 |
| 0.48 | 60.1% | 53.4% | 408.2 | 3/5 | 2.2 |
| **0.50** | **60.1%** | **53.4%** | **558.5** | **4/5** | **3.2** |
| 0.52 | 60.1% | 53.4% | 757.2 | 5/5 | 4.2 |
| 0.54 | 60.1% | 53.4% | 1039.8 | 5/5 | 6.6 |
| 0.56 | 60.1% | 53.4% | 1404.0 | 5/5 | 8.0 |
| 0.58 | 60.1% | 53.4% | 1869.5 | 5/5 | 8.0 |
| 0.60 | 60.1% | 53.4% | 2414.0 | 5/5 | 8.0 |

Reading the curve:

- **Recall saturates at 0.42.** Above it, recall@8 is dead flat (60.1%): every
  relevant post that will ever be retrieved already clears 0.42. Raising the gate
  past 0.42 buys **zero** recall.
- **0.50 is strictly dominated by 0.42** — same recall, but 0.50 quadruples the
  candidate pool (123 → 558) and doubles the off-domain false-positive rate
  (2/5 → 4/5). There is no metric on which 0.50 beats 0.42.
- **Precision is best at the low end** and is flat-to-slightly-declining above
  0.36. The pool explosion above 0.42 is nearly all noise: the marginal posts
  admitted between 0.42 and 0.60 (123 → 2414) add no recall and drag precision.

The full sweep and per-query detail live in `.scratch/m1-616/sweep.json` (not
checked in — regenerate with the harness).

## 4. The false-positive band (off-domain probes)

The central concern the ticket raised — spurious grounding — is measured directly
by the off-domain queries (no post in an AI/security/crypto corpus is genuinely
relevant, so every returned post is a false positive). Nearest-post distance per
off-domain query, and where each first breaches the gate:

| off-domain query | nearest post | nearest d | mechanism |
|---|---|---|---|
| banana bread recipe | "Nano Banana 2" (image model) | **0.371** | lexical collision on "banana" |
| how to train for a marathon | "StackLLaMA: train LLaMA with RLHF" | 0.409 | "train" → model *training* |
| growing tomatoes in a home garden | "broccoli farmer running his farm with [AI]" | 0.454 | farming/"growing" bleed |
| toddler sleep through the night | "Omni-Sleep: A Sleep Foundation Model" | 0.495 | "sleep" bleed |
| treat a sprained ankle at home | "SmartHomeSecure…" / "tune in from home" | 0.516 | "home" bleed |

Off-domain false positives by threshold (posts returned, k=8):

| query | 0.37 | 0.40 | 0.42 | 0.46 | 0.50 | 0.55 |
|---|---|---|---|---|---|---|
| banana bread | 0 | 1 | 1 | 2 | 4 | 8 |
| marathon | 0 | 0 | 1 | 8 | 8 | 8 |
| growing tomatoes | 0 | 0 | 0 | 1 | 3 | 8 |
| toddler sleep | 0 | 0 | 0 | 0 | 1 | 6 |
| sprained ankle | 0 | 0 | 0 | 0 | 0 | 8 |

At the current **0.50, four of five** nonsense queries pull real posts into the
prompt as grounding (e.g. "how to train for a marathon" folds in **8** ML-training
papers). Only a gate at **≤ 0.37** shuts them all out — but that costs ~10 pp of
recall (recall@8 drops to 49.5% at 0.36). The single hardest case, banana bread at
0.371, is a genuine lexical collision no distance gate above 0.37 can exclude
without gutting recall; it is better handled by M1-617's provenance/transparency
work than by the threshold.

## 5. Why the 0.18 linking threshold cannot be borrowed

The collector's `infochat.linking.semantic-threshold` (0.18) gates **post→post**
linking; this gate is **query→post**. The two distributions differ: the *nearest*
post to a well-formed on-topic query in this measurement ranges 0.14–0.38, and
even exact named-entity queries land at 0.14 (GhostLock) to 0.24 (SharePoint) —
i.e. a query→post gate at 0.18 would return almost nothing (only the single
closest exact-term hits). Query strings are short and lack the shared phrasing two
posts on the same topic share, so query→post cosine distances run systematically
**larger** than post→post. The chat gate must be tuned on its own distribution;
0.40 here is not comparable to and does not contradict 0.18 there.

## 6. Scope caveat: whole-corpus vs subscription-scoped

Production restricts candidates to the caller's subscribed sources
(`source_subscription`, keyed by `(scope_kind, scope_id)`); this sweep measures the
**whole** READY corpus. That is deliberate: (a) the threshold is a single
scope-independent default, so it should be calibrated against the worst case, and
whole-corpus is the *largest* candidate pool = the most opportunities for a
spurious match; (b) it matches the query shape of the ticket's provenance probe.
A scope with few subscribed sources sees a smaller pool and *fewer* false
positives than reported here, so the recommendation is conservative — lowering the
gate never hurts a narrowly-scoped caller and clearly helps a broadly-scoped one.

## 7. Decision and the apply-scope collision

**The measurement supports lowering the default to `0.40`.** Rationale, in
priority order for a chat *grounding* gate:

1. **0.50 is dominated; the recall knee is 0.42.** Moving off 0.50 is free of
   recall cost down to 0.42, and costs only 2.6 pp of macro recall@8 down to 0.40
   — and that 2.6 pp comes *entirely* from one query ("government surveillance and
   online censorship") whose relevant posts are anomalously distant (nearest
   0.376); every other query has identical recall@8 at 0.40 and 0.50. All three
   exact-term probes are unaffected (their posts sit at 0.14–0.35, far under 0.40).
2. **Asymmetric cost.** A spurious grounding post *actively misleads* the model; a
   missed post degrades gracefully to a general-knowledge answer. Erring toward
   precision is the right bias for this gate, which argues for the low side of the
   0.40–0.42 knee.
3. **0.40 cuts off-domain spurious grounding from 4/5 queries to 1/5** and shrinks
   the grounding pool ~8× (558 → 71 posts under the gate, 10.6% → 1.3% of corpus).

`0.42` is the reasonable alternative for anyone prioritising maximum recall over
false-positive suppression (full 60.1% recall@8, but admits the marathon→ML-training
collision and a 123-post pool). Anything ≥ 0.44 is strictly worse than 0.42.

**Applying the value is not the "single-line" edit the ticket budgeted, and it
collides with this ticket's own scope:**

- The config site carries an explicit invariant (application.properties, lines
  391–392): *"Both values are duplicated as @ConfigProperty defaultValue in
  SemanticSearchTool; the two must not drift."* So changing
  `application.properties` **requires** also changing
  `SemanticSearchTool.java`'s `defaultValue = "0.5"` — otherwise the two drift and
  a later removal of the property silently reverts behaviour to 0.5.
- But `out_of_scope` §1 fences off *"any change to the chat retrieval CODE PATH —
  that is M1-617,"* and `SemanticSearchTool.java` **is** that code path.
- The design-doc default (`docs/design/05-llm-and-embeddings.md:513`,
  "default 0.5") also goes stale.

The honest, non-drifting apply touches `application.properties` +
`SemanticSearchTool.java` + the design doc — 3 files beyond the 3 spike artifacts
(6 total) vs the ticket's original `files_budget: 4`, and it crosses the
out-of-scope code-path fence. Acceptance item 3 ("update the single value in
application.properties") as originally scoped could not be satisfied without
either violating the documented non-drift invariant or exceeding the budget and
scope, so — per the engineering rule *"if a ticket's acceptance criteria cannot
be satisfied without violating another rule, escalate"* — the apply decision was
surfaced to the operator rather than resolved unilaterally.

**Resolution (2026-07-12 escalation): the operator chose "apply 0.40 now."** The
ticket was refined (`files_budget` 4→6; `out_of_scope` §1 carved out the
`SemanticSearchTool` `defaultValue` non-drift twin; acceptance item 3 broadened to
the three files), and **0.40 is now the in-code default** in all three sites:
`infochat.chat.semantic-threshold=0.40` (application.properties), the
`SemanticSearchTool` `@ConfigProperty defaultValue = "0.40"` twin, and the
`docs/design/05-llm-and-embeddings.md` documented default. The retrieval mechanism
is unchanged (D19, hard deterministic cosine-distance gate); only the number moved.
