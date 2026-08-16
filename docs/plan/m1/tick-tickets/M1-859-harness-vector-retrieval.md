---
id: M1-859
title: "Harness: real vector retrieval for the tool-loop campaign"
status: done
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
    the vectors being measured against are the vectors prod computes —
    identity holds up to the characterized ~±0.01 llama.cpp-vs-Ollama
    runtime noise, M1-748 record §1, and THAT is the residual divergence
    P13 discloses in the README); swapping embedders is a new
    measurement, not this ticket.
  - >-
    The prefixed-vs-raw task-prefix A/B (does nomic's prefix training
    help THIS corpus?) — a separate follow-up measurement decided after
    M1-859 lands (user ruling 2026-08-16, premise-fail refine); this
    ticket embeds raw text only and builds no prefixed arm.
  - >-
    The live deployment DB or prod containers — the corpus is the
    sha-pinned posts.jsonl snapshot (measurements-never-ride-prod-
    containers); the embedder runs as a local pinned llama-server
    (the floor-check shape), the translator is the remote DeepSeek
    endpoint the campaign's incumbent arm already uses, never a prod
    container.
acceptance:
  - "The corpus snapshot is embedded with the deployment's own embedder: harness/embed_corpus.py drives the pinned llama-server (.bench/llama-b10221/llama-b10221/llama-server, --embedding --pooling mean) over .embeddings/nomic-embed-text-v1.5.f32.gguf on RAW text — no task prefix on the document side and none on the query side, matching production (M1-748 record, docs/measurement/retrieval-separability.md:53-56: production embeds raw unprefixed text on both sides; the floor-check convention transfers MINUS its search_query:/search_document: prefixes) — and writes corpus embeddings plus a manifest naming the GGUF file and its sha256 — probe: grep -nE 'search_document|search_query' over harness/embed_corpus.py, harness/dispatcher.py and harness/anchor.py returns NOTHING (an accidentally introduced prefix is the P16 trap: it would silently shift every similarity off the production-calibrated scale) and grep -n 'sha256' .bench/direct-chat-e2e/corpus/embeddings.manifest.json returns the pin."
  - "semanticSearch is served by real cosine over those vectors, replacing the lexical token-overlap approximation: the dispatcher applies the production 0.40 distance floor (admit similarity >= 0.60, the M1-616/M1-717-calibrated floor), orders distance ASC with post_id ASC tie-break, and emits the production {uid,title,url,similarity} shape — probe: grep -n 'APPROXIMATION' .bench/direct-chat-e2e/harness/dispatcher.py returns NOTHING (the stand-in docstring is gone) and grep -n 'cosine' .bench/direct-chat-e2e/harness/dispatcher.py shows the similarity path; a python3 .bench/direct-chat-e2e/harness/dispatcher.py self-check run asserts a query with no post over the floor returns [] (the general-knowledge path), never a nearest-neighbour regardless of distance."
  - "The M1-746 query anchor is modeled for model-issued queries: a non-English-scope query is translated to English BEFORE embedding by the deployed remote DeepSeek translator (greedy, temperature 0 — the ModelTask.TRANSLATOR routing the M1-746 R5 disclosure documents), memoised in a persistent per-(scope, query, language) cache (.bench/direct-chat-e2e/corpus/query-anchor-cache.jsonl, the D58(b) determinism-by-construction posture), and an 'en' scope issues no translator call — probe: grep -c 'query' .bench/direct-chat-e2e/corpus/query-anchor-cache.jsonl after a non-English spot run returns > 0 with each entry carrying (lang, source query, translation); a translator failure degrades to the raw query AND increments a counted, README-disclosed fallback counter — never a silent divergence (P16)."
  - "Spot-check agreement on >= 20 queries (mixed en and anchored non-English, drawn from the campaign fixture queries): python3 .bench/direct-chat-e2e/harness/spot_check.py asserts the harness's cosine result set under the 0.60 admit floor equals the production semantic arm's set computed in the production's RAW unprefixed text space over the same corpus and embedder (distance ASC, post_id ASC — the arm contract documented in docs/spec/security.md §Prompt-injection defenses (LLM call sites) and design 05 §5.4.6), exits nonzero on any mismatch, and records in the harness README the measured divergence, if any, from the FULL fused production result (lexical arm + RRF) — the one production feature the harness deliberately does not rebuild."
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
reviews:
  - round: 1
    date: 2026-08-16
    verdict: REWORK
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY FAIL, MAINTAINABILITY FAIL, SCOPE PASS"
    diff_stats: "tracked: 2 files +87/-46; harness deliverables under gitignored .bench/ (embed_corpus.py, anchor.py, spot_check.py, dispatcher.py rewrite, gen.py wiring, README, corpus artifacts, spot-check json)"
    rework_items: 2
    verdict_file: .scratch/tick-review-M1-859-r1.txt
  - round: 2
    date: 2026-08-16
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "fix-hunks r1->r2 tracked: 2 files +40/-2 (ticket rework/observation sections + board); harness-side: _jstr/_instant deleted, spot_check.py re-run (json ts 15:15:11 > dispatcher mtime 15:14:45, 22/22 mismatches 0), README fallback count synced 1->2"
    rework_items: 0
    verdict_file: .scratch/tick-review-M1-859-r2.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check: >-
  2026-08-16 (start pre-flight): tick-lint green (analysis symlinked into
  the worktree — tick-analysis/ is gitignored); every file:line citation
  spot-checked true; P13+P16 fully landed per the analysis cross-read;
  blocked_by empty. The self-check then surfaced a premise defect: the
  ticket pinned search_document:/search_query: prefixes as the
  deployment's convention, but the committed M1-748 record
  (docs/measurement/retrieval-separability.md:53-56) documents production
  embedding raw unprefixed text (re-verified: no prefix string in
  collector/provider sources; Ollama backend applies none). User ruling
  2026-08-16: escalate premise-fail -> refine; ticket amended to raw-text
  embedding, prefix A/B moved out of scope as a follow-up measurement.
  Re-run on the amended ticket 2026-08-16: lint green; acceptance items
  implementable without guessing; production embed shape verified
  (EmbeddingWorker.buildInputText: title + '\n\n' + body_summary OR first
  800 chars of body, raw unprefixed; snapshot carries no body_summary, so
  the 800-char fallback embeds everywhere — README-disclosed residual);
  snapshot lacks production's integer post_id, so the distance tie-break
  is uid ASC (deterministic total order preserved — README-disclosed).
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
deployment computes nomic-embed-text-v1.5 vectors (D54, local) from RAW
unprefixed text on both the document and the query side (M1-748 record,
docs/measurement/retrieval-separability.md:53-56 — verified: no prefix
string anywhere in the collector/provider sources; the Ollama backend
applies none), admits at cosine similarity >= 0.60 (1 - the 0.40 distance
floor), and anchors non-English queries to English at the tool under D58's
four conditions (M1-746). The harness implements none of the three. The
2026-08-16 floor check (.bench/m1-717/, floor_check.py +
results/floor-check.json) proves the off-shell MECHANICS: the same f32
GGUF via a pinned llama-server with mean pooling serves the embedding
shape, and anchored queries clear the 0.60 admit floor at parity with
native English (cs/es/ru/tr 34/37 admitted, best_p50 ~0.758). It ran
PREFIXED (search_query:/search_document:), so it demonstrates
harness-space self-consistency, not production parity — the 0.60/0.65
floors are calibrated in production's UNPREFIXED space, and the
convention transfers MINUS the prefixes (user ruling 2026-08-16,
premise-fail refine). Nothing unknown remains to invent — the work is
transplanting the proven mechanics into the dispatcher in the
production's raw-text space.

## Pitfalls

Numbered per the analysis document; this ticket carries P13, P16.

- P13 (the harness clauses): no prod containers — the snapshot corpus and
  a local pinned llama-server only; every remaining divergence between
  harness and production is disclosed in the harness README, never
  silent; no free variables — the floor, embedder, and raw-text (no
  prefix) embedding shape are the production constants, not harness
  knobs.
- P16: harness-fidelity traps — the deployment's own GGUF with mean
  pooling and NO task prefix, matching production (M1-748 record:
  production embeds raw unprefixed text on both sides — the trap is
  accidentally INTRODUCING a prefix, which silently shifts every
  similarity off the production-calibrated scale); the 0.40
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
     (pinned binary, GGUF, flags, health wait) but embed RAW text — no
     task prefix (production shape, M1-748 record); write vectors + the
     sha256 manifest; run once, keep the artifacts (P16's embedder pin).
  2. dispatcher.py: load the vectors, embed the (anchored) query RAW —
     no task prefix, the query-side twin of step 1 — serve semanticSearch
     by cosine similarity 1 - distance under the 0.60 admit floor, order
     distance ASC / post_id ASC, emit {uid,title,url,similarity}; delete
     the token-overlap path and its APPROXIMATION docstring; the
     over-floor empty result stays [] (the general-knowledge path the
     fixtures expect).
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
  only, README disclosure, production constants); P16→step 1's raw-text
  (no-prefix) convention + manifest, step 2's floor/order, step 3's
  live-translator justification + counted fallback, step 4's
  mismatch-exits-nonzero.

## Definition of done

The corpus is embedded with the deployment's GGUF under the manifest pin,
as RAW unprefixed text (production shape, M1-748 record);
semanticSearch is served by real cosine under the 0.60 admit floor in the
production emission shape and deterministic order; non-English
model-issued queries are anchored via the cached live translator with 'en'
a no-op and failures counted, never silent; the spot-check passes on
>= 20 queries with any fused-result divergence measured in the README;
git status shows no new tracked path; mvn verify is green from the repo
root.

## Verification

- P16 (no-prefix/embedder) → acceptance item 1's grep probes (no task
  prefix anywhere in the harness embed/query paths; manifest sha256) — an
  accidentally introduced prefix shifts similarities off the
  production-calibrated scale and fails the spot-check, which exits
  nonzero.
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
  any set mismatch against the production arm contract computed in the
  production's RAW unprefixed text space (docs/spec/
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

## Round 1 rework

REWORK ITEMS (verdict: REWORK, 2026-08-16, `.scratch/tick-review-M1-859-r1.txt`):

1. Finding 1: re-run python3 .bench/direct-chat-e2e/harness/spot_check.py
   against the delivered dispatcher and refresh the artifacts —
   results/spot-check-m1-859.json rewritten with ts newer than
   harness/dispatcher.py's mtime and "mismatches": 0; README's "Verified
   by ..." line and the anchor-fallback "Current count" line synced to the
   refreshed json and corpus/query-anchor-fallbacks.json. Evaluated via
   EVALUATED-AS of Finding 1.
2. Finding 2: delete the orphaned _jstr and _instant helpers from
   harness/dispatcher.py. Evaluated via EVALUATED-AS of Finding 2.

## Review observations

- searchPosts/getReferences result budget: every production tool carries a
  16 KiB aggregate result budget (SearchPostsTool.java:42,
  GetReferencesTool.java:52), while the harness serves those two tools under
  an 8 KiB cap (harness/dispatcher.py MAX_RESULT_BYTES = 8192). WHAT: the
  two non-semantic tools truncate large result sets earlier than production
  does. WRONG: a searchPosts response that would fit production's 16 KiB
  budget is cut at 8 KiB in the harness, so a campaign turn can see fewer
  posts than the same call would return in production. EXPECTED: the harness
  budget matches production's 16 KiB, or the record carries a measured
  justification for the smaller cap. README residual 5 already discloses
  this and M1-859 explicitly does not own those paths, so it is recorded
  here rather than raised as this round's finding.
  TOUCHED-BY-THIS-DIFF: no — the 8 KiB behavior predates the ticket and
  survives the dispatcher rewrite unchanged.
