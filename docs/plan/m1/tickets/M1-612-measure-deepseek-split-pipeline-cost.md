---
id: M1-612
title: "Measure real DeepSeek per-post token/request cost of the split ingest eval pipeline"
status: pending
created: 2026-07-12
last_updated: 2026-07-12
blocked_by: []
files_budget: 4
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
provenance: >-
  M1-609 batch-vs-split discussion (2026-07-12). The ingest metadata tasks
  TAGGER, ENTITY, and CLASSIFIER are three separate provider.generate() calls
  per post (TaggerWorker.java:336, EntityExtractorWorker.java:269,
  ClassifierWorker.java:273), each re-embedding the full title+body in its own
  prompt — so the post body (the dominant input-token share) is paid three
  times per post on the paid DeepSeek API, with no caching/coalescing today
  (OpenAiCompatibleProvider explicitly rejects even a config cache). Before
  deciding whether to batch these three into one call (cheaper but all-or-
  nothing on schema failure) we need the real cost, not a guess: DeepSeek
  v4-flash is cheap and the judge fires on ~0.3% of posts / embeddings are
  local, so the metadata-body re-send may or may not be material.
out_of_scope:
  - >-
    Implementing batching, prompt reordering, prefix-cache restructuring, or
    ANY change to the routing/prompt/pipeline surface. This ticket MEASURES
    only; the batch-vs-split decision and any implementation are separate
    follow-ups gated on this measurement.
  - >-
    The entity-schema-robustness work (M1-613). This ticket counts tokens/cost;
    it does not try to fix entity extraction.
  - >-
    SECURITY_JUDGE and EMBEDDING cost. The judge runs on ~0.3% of posts (near-
    free remotely) and embeddings are local nomic (no API cost); the redundant-
    body cost lives entirely in the three metadata tasks.
acceptance:
  - >-
    A cost report (docs/plan/m1/spikes/M1-612-deepseek-cost.md or similar) that
    measures, on a sample of REAL corpus post bodies run through the ACTUAL
    production TAGGER / ENTITY / CLASSIFIER prompts against deepseek-v4-flash,
    the per-task prompt_tokens and completion_tokens reported by the DeepSeek
    usage accounting, broken down into body vs fixed-scaffold share so the
    thrice-paid body cost is explicit.
  - >-
    A per-post and per-1000-post cost figure for the current SPLIT design using
    published DeepSeek v4-flash pricing (naming the price sheet + date), and the
    specific dollar amount that batching TAGGER+ENTITY+CLASSIFIER into one call
    would remove (the redundant 2x body re-send), so the saving can be weighed
    against the robustness cost.
  - >-
    An explicit check of whether DeepSeek's automatic prefix (context) caching
    already discounts anything across the three same-post calls today — report
    the prompt_cache_hit_tokens vs prompt_cache_miss_tokens the API returns —
    since that determines how much of the "3x body" is actually paid at full
    price and whether body-as-prefix restructuring is a cheaper lever than
    batching.
  - >-
    An extrapolation to expected production volume (state the posts/day
    assumption and its source) turning the per-post figure into a monthly cost,
    with a one-line recommendation on whether the redundant-body cost is
    material enough to justify pursuing batching or prefix-caching at all.
  - >-
    A reusable measurement (extend the M1-609 harness
    docs/plan/m1/spikes/local-vs-remote-eval.py or a sibling script — it already
    captures prompt_tokens/completion_tokens per call) so the figure can be
    re-run when pricing, prompts, or the corpus change.
  - >-
    mvn verify is green from the repo root IF the harness or any change touches
    a Java/config/DB file; if the deliverable is a standalone script + report
    only (no Java/config/DB change) the diff is inert and mvn verify is N/A per
    the inert-diff rule.
test_plan:
  adds:
    - >-
      A reusable per-task cost-measurement script (or an extension of the M1-609
      harness) plus its sampled-corpus input.
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Per-task routing rules
decision_refs:
  - D56
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-612: Measure real DeepSeek per-post cost of the split ingest eval pipeline

## Context

The M1-609 spike (docs/plan/m1/spikes/M1-609-local-vs-remote.md) recommended
keeping the LLM eval tasks remote on DeepSeek. That leaves an open cost question
raised in the same discussion: TAGGER, ENTITY, and CLASSIFIER are three separate
chat/completions calls per post, each re-sending the full post body (the
dominant input-token share). Batching them into one call would cut the redundant
body tokens but trade away the split design's independent per-task degradation
(bootstrap_tags / no-entities / unknown). Before that trade can be judged we need
the real number: how much does the 3x body re-send actually cost on DeepSeek
v4-flash at production volume? This ticket measures it; the decision and any
implementation are separate follow-ups.

## Acceptance

See the YAML `acceptance:` list. In prose: run a sample of real corpus post
bodies through the production tagger/entity/classifier prompts against
deepseek-v4-flash, capture the DeepSeek usage accounting (prompt/completion
tokens, and cache hit/miss tokens), split body vs scaffold, price it against the
published v4-flash rates, extrapolate to a monthly figure, and recommend whether
the redundant-body cost is even material enough to pursue batching or
prefix-caching. Deliver a reusable measurement script + a report; no production
code change.

## Out-of-scope

No batching, no prompt/routing/pipeline change, no entity-robustness work
(that is M1-613). Judge and embedding costs are excluded (judge ~0.3% of posts,
embeddings local). This is a measurement-only ticket like M1-609.

## Notes

- Cheapest starting point: the M1-609 harness already POSTs arbitrary prompts to
  DeepSeek and records `prompt_tokens` / `completion_tokens`; extend it to run
  all three metadata prompts per sampled post and sum the usage. Pull sample post
  bodies from the corpus (the `post` table) so the token counts reflect real body
  lengths, not synthetic ones.
- DeepSeek returns cache accounting (`prompt_cache_hit_tokens` /
  `prompt_cache_miss_tokens`). If the three same-post calls already share a cached
  prefix, the "3x body" is not fully paid — measure it rather than assume.
- Feeds the batch-vs-split decision together with M1-613 (entity robustness): if
  M1-613 makes entity extraction reliable, all-or-nothing batching becomes less
  risky, changing how this cost figure should be weighed.
- If DeepSeek prefix-caching turns out to capture most of the redundant body
  cost, body-as-prefix prompt restructuring is a lower-risk lever than batching
  (it keeps independent per-task degradation) — but note the tagger/entity/
  classifier prompts deliberately place the untrusted body AFTER the instructions
  inside a delimiter wrapper, so any reorder is a security-review item, not free.
