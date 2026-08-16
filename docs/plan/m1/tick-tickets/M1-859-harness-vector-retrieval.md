---
id: M1-859
title: "Harness: real vector retrieval for the tool-loop campaign"
status: pending
created: 2026-08-16
last_updated: 2026-08-16
flow: tick
reproduction: >-
  Probe (harness-only ticket; no test can exist — the .bench tree is
  gitignored, the M1-844 posture): grep -n 'APPROXIMATION'
  .bench/direct-chat-e2e/harness/dispatcher.py returns :8 — "semanticSearch:
  [{uid,title,url,similarity}] — APPROXIMATION: deterministic lexical
  token-overlap score over (title_en||title + body_en||body) instead of
  pgvector cosine" — the stand-in the whole campaign measured through
  (disclosed at docs/measurement/direct-chat-e2e.md:203-212 as harness
  divergence). Observed consequence (user decision 2026-08-16): every
  tool-loop number the record carries was produced by retrieval that is
  not the production shape, and the quantity the leg measures (does a
  call pay off: results useful, model continues, grounds, cites) is a
  function of retrieval quality — so M1-858 re-run against the same
  stand-in would produce another number we later discount, which is why
  this ticket blocks it.
analysis_ref: docs/plan/m1/tick-analysis/tool-loop-hardening.md
blocked_by: []
files_scope:
  - .bench/direct-chat-e2e/harness/
  - .bench/direct-chat-e2e/corpus/
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production code — infochat/** is untouched: the real
    SemanticSearchTool, QueryAnchorTranslator, QueryTranslationCache and
    the embedding stack are the MODEL this harness imitates, never the
    modification target. mvn verify runs as the no-regression leg only.
  - >-
    ANY committed artifact — docs/spec/**, docs/design/**, and
    docs/measurement/** are untouched (the record update is M1-858's);
    everything this ticket produces stays under gitignored
    .bench/direct-chat-e2e/.
  - >-
    CHANGING the fixtures, the locked bar, or the scenario set — M1-858
    owns the campaign; this ticket only replaces what the dispatcher
    serves underneath it.
  - >-
    A DIFFERENT embedder or dimension than the deployment's — the
    deployment's own nomic-embed-text-v1.5 f32 GGUF
    (.embeddings/nomic-embed-text-v1.5.f32.gguf) is pinned (D54 posture:
    the vectors being measured against are the vectors prod computes);
    swapping embedders is a new measurement, not this ticket.
  - >-
    The live deployment DB or prod containers — the corpus is the
    sha-pinned posts.jsonl snapshot (measurements-never-ride-prod-
    containers); the embedder runs as a local pinned llama-server
    (the floor-check shape), the translator is the remote DeepSeek
    endpoint the campaign's incumbent arm already uses, never a prod
    container.
acceptance:
  - "The corpus snapshot is embedded with the deployment's own embedder: harness/embed_corpus.py drives the pinned llama-server (.bench/llama-b10221/llama-b10221/llama-server, --embedding --pooling mean) over .embeddings/nomic-embed-text-v1.5.f32.gguf with the search_document: prefix on every post (the search_query: query-side twin of the floor-check convention, .bench/m1-717/floor_check.py:33) and writes corpus embeddings plus a manifest naming the GGUF file and its sha256 — probe: grep -n 'search_document' .bench/direct-chat-e2e/harness/embed_corpus.py shows the prefix and grep -n 'sha256' .bench/direct-chat-e2e/corpus/embeddings.manifest.json returns the pin."
  - "semanticSearch is served by real cosine over those vectors, replacing the lexical token-overlap approximation: the dispatcher applies the production 0.40 distance floor (admit similarity >= 0.60, the M1-616/M1-717-calibrated floor), orders distance ASC with post_id ASC tie-break, and emits the production {uid,title,url,similarity} shape — probe: grep -n 'APPROXIMATION' .bench/direct-chat-e2e/harness/dispatcher.py returns NOTHING (the stand-in docstring is gone) and grep -n 'cosine' .bench/direct-chat-e2e/harness/dispatcher.py shows the similarity path; a python3 .bench/direct-chat-e2e/harness/dispatcher.py self-check run asserts a query with no post over the floor returns [] (the general-knowledge path), never a nearest-neighbour regardless of distance."
  - "The M1-746 query anchor is modeled for model-issued queries: a non-English-scope query is translated to English BEFORE embedding by the deployed remote DeepSeek translator (greedy, temperature 0 — the ModelTask.TRANSLATOR routing the M1-746 R5 disclosure documents), memoised in a persistent per-(scope, query, language) cache (.bench/direct-chat-e2e/corpus/query-anchor-cache.jsonl, the D58(b) determinism-by-construction posture), and an 'en' scope issues no translator call — probe: grep -c 'query' .bench/direct-chat-e2e/corpus/query-anchor-cache.jsonl after a non-English spot run returns > 0 with each entry carrying (lang, source query, translation); a translator failure degrades to the raw query AND increments a counted, README-disclosed fallback counter — never a silent divergence (P16)."
  - "Spot-check agreement on >= 20 queries (mixed en and anchored non-English, drawn from the campaign fixture queries): python3 .bench/direct-chat-e2e/harness/spot_check.py asserts the harness's cosine result set under the 0.60 admit floor equals the production semantic arm's set over the same corpus and embedder (distance ASC, post_id ASC — the arm contract documented in docs/spec/security.md §Prompt-injection defenses (LLM call sites) and design 05 §5.4.6), exits nonzero on any mismatch, and records in the harness README the measured divergence, if any, from the FULL fused production result (lexical arm + RRF) — the one production feature the harness deliberately does not rebuild."
  - "No production or committed surface changes — probe: git status --porcelain after the work shows no new tracked path (all outputs under gitignored .bench/); mvn verify from repo root is green (no-regression only, engineering-rules §5)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
  notes:
    - >-
      Harness work under gitignored .bench/ (the M1-844 posture); the
      verifications are the harness scripts' own self-checks, the
      spot-check script, and the file-level probes above. mvn verify
      covers the no-regression leg.
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Embedding pipeline
decision_refs:
  - D54
  - D58
  - D19
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-859: Harness: real vector retrieval for the tool-loop campaign

## Context

The M1-844 campaign harness serves semanticSearch from a lexical
token-overlap approximation (dispatcher.py:8-10) and does not model the
M1-746 query anchor — disclosed in the record (:203-212) as harness
divergence. The user decision of 2026-08-16: the tool-loop leg's quantity
(does a call pay off — results useful, model continues, grounds, cites)
is a function of retrieval quality, so measuring it against the stand-in
has no meaning until the harness integrates real vector retrieval; a
re-measure on the stand-in would be another number to discount. This
ticket upgrades the harness to production-shaped retrieval; M1-858 (the
re-measure) is blocked on it. Shared analysis: `analysis_ref:`.

## Root cause

A stand-in substitution made for citability (fixture uids served from a
static snapshot) was left approximating the retrieval MECHANISM too. The
deployment computes nomic-embed-text-v1.5 vectors (D54, local), admits at
cosine similarity >= 0.60 (1 - the 0.40 distance floor), and anchors
non-English queries to English at the tool under D58's four conditions
(M1-746). The harness implements none of the three. The 2026-08-16 floor
check (.bench/m1-717/, floor_check.py + results/floor-check.json) proves
the exact convention works off-shell: the same f32 GGUF via a pinned
llama-server with search_query:/search_document: prefixes and mean
pooling reproduces the deployed vectors' behavior, and anchored queries
clear the 0.60 admit floor at parity with native English (cs/es/ru/tr
34/37 admitted, best_p50 ~0.758). Nothing unknown remains to invent —
the work is transplanting a proven convention into the dispatcher.

## Pitfalls

Numbered per the analysis document; this ticket carries P13, P16.

- P13 (the harness clauses): no prod containers — the snapshot corpus and
  a local pinned llama-server only; every remaining divergence between
  harness and production is disclosed in the harness README, never
  silent; no free variables — the floor, embedder, and prefixes are the
  production constants, not harness knobs.
- P16: harness-fidelity traps — the deployment's own GGUF with its
  search_document:/search_query: prefix convention and mean pooling (a
  missing or wrong prefix silently shifts every similarity); the 0.40
  distance floor applied on the cosine arm, not a harness-chosen cutoff;
  deterministic order (distance ASC, post_id ASC) so repeated runs are
  reproducible (D19 posture); and an anchor translation that covers
  RUNTIME-CHOSEN model queries — a recorded-translation cache cannot
  (model-issued queries are not known in advance; a miss would fall back
  to the raw query and reintroduce the divergence this ticket removes),
  so the choice is the live greedy remote translator with a persistent
  cache (D58(b): the cache is what makes repeat queries stable by
  construction), with every fallback counted and disclosed.

## Approach

- **Files to touch:** `files_scope` — new harness/embed_corpus.py (the
  one-off embedding pass over corpus/posts.jsonl; outputs
  corpus/embeddings + corpus/embeddings.manifest.json), the
  dispatcher.py semanticSearch path (cosine + floor + order, replacing
  _score's token overlap), a new harness/anchor.py + the persistent
  query-anchor cache (translator call, memoisation, fallback counter),
  and harness/spot_check.py (the agreement check); README.md under
  .bench/direct-chat-e2e/ gains the disclosure block.
- **Steps, in implementation order:**
  1. embed_corpus.py: reuse the floor_check.py server bring-up verbatim
     (pinned binary, GGUF, flags, health wait) but embed with the
     search_document: prefix; write vectors + the sha256 manifest; run
     once, keep the artifacts (P16's embedder pin).
  2. dispatcher.py: load the vectors, serve semanticSearch by cosine
     similarity 1 - distance under the 0.60 admit floor, order distance
     ASC / post_id ASC, emit {uid,title,url,similarity}; delete the
     token-overlap path and its APPROXIMATION docstring; the over-floor
     empty result stays [] (the general-knowledge path the fixtures
     expect).
  3. anchor.py: for a non-English scope, translate the model-issued
     query via the remote DeepSeek endpoint (temperature 0), memoise in
     corpus/query-anchor-cache.jsonl keyed (scope_kind, scope_id, query,
     lang); 'en' short-circuits; failure degrades to the raw query and
     increments the fallback counter (logged, README-disclosed).
  4. spot_check.py: >= 20 fixture queries (en + anchored non-English),
     assert set-equality with the production semantic arm's expected
     output over the same corpus/embedder/floor/order; record any
     divergence from the full fused (lexical + RRF) production result in
     the README (the one deliberately-unrebuilt feature — the campaign's
     model-issued queries are topical, the lexical arm exists for
     keyword-exact lookups, and the delta is measured rather than
     assumed away).
  5. README.md: the disclosure block — what the harness now models and
     the remaining deltas with their measurements.
- **Controls to preserve (§10):** this ticket reroutes no production
  path. The controls are the harness's own integrity rules: the pinned
  embedder manifest, the append-only anchor cache, the counted fallback,
  the reproducible order, and the README disclosure (the M1-844
  harness-discipline posture).
- **Pitfall→mitigation:** P13→steps 1-5 (local pinned server, snapshot
  only, README disclosure, production constants); P16→step 1's prefix
  convention + manifest, step 2's floor/order, step 3's live-translator
  justification + counted fallback, step 4's mismatch-exits-nonzero.

## Definition of done

The corpus is embedded with the deployment's GGUF under the manifest pin;
semanticSearch is served by real cosine under the 0.60 admit floor in the
production emission shape and deterministic order; non-English
model-issued queries are anchored via the cached live translator with 'en'
a no-op and failures counted, never silent; the spot-check passes on
>= 20 queries with any fused-result divergence measured in the README;
git status shows no new tracked path; mvn verify is green from the repo
root.

## Verification

- P16 (prefix/embedder) → acceptance item 1's grep probes (search_document
  prefix present; manifest sha256) — a wrong or missing prefix shifts
  similarities and fails the spot-check, which exits nonzero.
- P16 (floor/order/shape) → acceptance item 2's probes — the
  APPROXIMATION docstring grep returns nothing, and the dispatcher
  self-check feeds a query with no post over the floor and asserts []
  (never a below-floor nearest neighbour) — a missing floor fails this
  check red.
- P13 + P16 (anchor) → acceptance item 3's probes — cache entries carry
  (lang, query, translation); 'en' issues no call; FAILURE-MODE: a
  translator failure feeds the fallback path and asserts the raw query is
  used AND the counter increments — a silent fallback (no counter) fails.
- P16 (agreement) → acceptance item 4 — spot_check.py exits nonzero on
  any set mismatch against the production arm contract (docs/spec/
  security.md §Prompt-injection defenses (LLM call sites), design 05
  §5.4.6); the fused-result delta is measured, never assumed zero.
- P13 (no-regression, nothing committed) → acceptance item 5 — git status
  --porcelain shows no new tracked path; mvn verify green.

## Out-of-scope

Named in `out_of_scope`: production code (the real tool/translator stack
is the model, not the target), any committed artifact (the record update
is M1-858's), fixture/bar changes, a different embedder or dimension, and
prod containers (snapshot + local pinned server + the remote translator
endpoint the campaign already meters). If the spot-check exposes a
production-arm surprise, that is a finding for the record — file it, do
not tune the floor to pass.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-859-harness-vector-retrieval.md
```
