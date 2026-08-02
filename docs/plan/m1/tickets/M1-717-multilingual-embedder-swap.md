---
id: M1-717
title: "Swap to a multilingual embedder and recalibrate thresholds"
status: abandoned
created: 2026-07-30
last_updated: 2026-08-02
blocked_by: []
deferred_on: [M1-745, M1-746]
abandoned_reason: superseded
files_budget: 12
files_scope:
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/HelpLookupTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/CommandIntentIndex.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/embedding/EmbeddingMetadataStartupGuard.java
  - docs/spec/llm.md
  - docs/design/05-llm-and-embeddings.md
complexity: high
risk: high
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Adding, enabling, or translating any language. This ticket changes
    only the vector representation and the thresholds that read it; the
    enabled language set is untouched.
  - >-
    Per-language full-text regconfig. `post.search_tsv` is a STORED
    generated column pinned to `to_tsvector('english', ...)`
    (`V58:27-31`) and BOTH sides of a lexical match must share a config,
    so changing the query side alone degrades matching. Deferred to
    `docs/plan/future-features.md`, contingent on ingesting non-English
    sources.
  - >-
    Removing or weakening `SET LOCAL hnsw.iterative_scan = strict_order`
    in `SemanticSearchTool`, or moving any world predicate outside the
    index-driven arm. That placement is the M1-589 leak-class fix; a
    dimension change must preserve it.
  - >-
    Routing embeddings to a remote provider. D54 is absolute: embeddings
    run on a local backend regardless of profile.
  - >-
    The generative decoder. Whether to move LLM tasks from DeepSeek to a
    local llama.cpp server is a separate evaluation and a separate
    ticket.
acceptance:
  - >-
    SKELETON — acceptance is authored at `start` from
    `/home/infochat/infochat/.bench/EMBEDDER-MEASUREMENT-RESULTS.md` §1
    (decision block) and §3 (calibration evidence). This ticket MUST NOT
    start until that scaffold is filled; every item below needs a
    measured value.
  - >-
    `infochat.embeddings.model` is the model named in results §1, and the
    `embedding_metadata` guard row matches it so startup does not
    fatal-fail
  - >-
    All six thresholds carry the values from results §1, each justified
    by the distribution in results §3 — noting two are cosine DISTANCE
    (`infochat.chat.semantic-threshold`,
    `infochat.linking.semantic-threshold`) and four are SIMILARITY
    (`ChatAgent.CONFIDENT_SIMILARITY_CUTOFF`,
    `HelpLookupTool.SIMILARITY_THRESHOLD`,
    `ChatAgent.INTENT_DELIVERY_SIMILARITY_THRESHOLD`,
    `CommandIntentIndex.TOPIC_SIMILARITY_THRESHOLD`)
  - >-
    `infochat.linking.semantic-threshold` carries a value for every
    profile that overrides it today — the base plus the `%pi` override,
    which is 0.20 where the others are 0.18 — or the ticket records that
    the spread collapses under the new model and says why
  - >-
    The whole corpus is re-embedded under the new model and every
    `post_embedding` row is non-null for a READY post that previously
    had one
  - mvn verify from the repo root is green
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Embedding pipeline
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D54
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

## ABANDONED 2026-08-02 — superseded by the English pivot

**Do not implement this ticket.** The evaluation it was waiting on ran, and it
did not produce the values this ticket needed — it removed the reason to want
them.

**What the measurement found** (`docs/measurement/translator-slot.md` and
`.bench/track-a/ENGLISH-ANCHOR-MEASUREMENT.md`):

- The incumbent `nomic-embed-text-v1.5` is **not beaten on English** by any
  candidate (recall@8 0.630 against 0.557–0.610) and is **2–5× faster**.
- The multilingual swap buys **+0.12 on non-English and costs 0.02–0.07 on
  English** — on a corpus that is 100% English today.
- Anchoring the corpus in English instead solves three problems (the semantic
  arm, the silently-degrading lexical arm, and reader comprehension) where the
  swap solves one, at no migration cost.

**This ticket's stated justification is therefore gone.** It rested on "a
non-English query against the corpus needs cross-lingual matching, and
translating the query is what D19 forbids." D58 was amended (`21ad3517`) to
permit query translation under four bounded determinism conditions, so the
premise no longer holds. Under the pivot **both sides of every comparison are
English and the embedder never sees non-English text**, which means no
768→1024 migration, no whole-corpus re-embed, no D54 amendment, and an embedder
that may be selected on English inputs alone.

Superseded by **M1-745** (ingest leg) and **M1-746** (query leg).

⚠ **One live problem this ticket carried does NOT die with it, and has no
home.** M1-717 bundled the embedder swap with **threshold recalibration**, and
the measurement showed that **no model — incumbent or candidate — is separable**:
`worst_true < best_false` on all five evaluated, so no single global similarity
threshold splits true matches from false ones, and the six thresholds this
ticket would have set cannot be derived from any of them. Absolute recall is
also low across the board (0.56–0.63 @ k=8). That is independent of which model
ships and is likely a fixture or metric problem rather than a model one. **It
needs its own ticket** — closing this one must not be read as closing that.

The original skeleton follows, kept as the record of what was planned.

## Context

**SKELETON.** The ID is claimed and the shape is fixed, but the values are
not knowable until the multilingual-embedder evaluation runs on the Strix
Halo box. Instructions:
`/home/infochat/infochat/.bench/EMBEDDER-HANDOFF-PROMPT.md`. Results land
in `/home/infochat/infochat/.bench/EMBEDDER-MEASUREMENT-RESULTS.md`. Both
are gitignored working files, not committed artifacts.

`nomic-embed-text` cannot represent Cyrillic: within a Russian-only pool it
ranks 0/7 with a negative margin, and random Cyrillic characters score
0.710 against a real Russian sentence versus 0.805 for a genuine
paraphrase — the vector encodes script, not meaning. Turkish is 4/7 with an
unplaceable threshold. Spanish ranks 7/7 same-language but **0/7 above the
0.60 admit line** against the English corpus, which is the shape that
actually occurs. Three replacements measured 7/7 across en/fr/ru/cs.

**Why the swap and the recalibration are ONE ticket.** True-match
similarity sits at 0.60–0.80 under `nomic` but 0.38–0.52 under every
multilingual candidate. Shipping the model without the thresholds leaves a
green build in which semantic search returns *nothing* — the admit line
rejects every genuine match. Splitting these into two tickets creates a
merge window where that is the deployed state. They must land together.

## Acceptance

See frontmatter. Author the concrete items at `start`, from the results
scaffold.

## Out-of-scope

See frontmatter `out_of_scope`.

## Notes

**Why `status: deferred` and not `pending`.** The gate on this ticket is
an out-of-repo measurement, not another ticket, so `blocked_by` cannot
express it and `scripts/regen-status.py` kept listing the ticket under
"Runnable now" — where a batch scheduler would pick it up and hit the
`acceptance: SKELETON` wall at `start`. `deferred` is what the board
understands: it drops out of the runnable set, and M1-718/719/720 (which
already carry `blocked_by: [M1-716, M1-717]`) now draw the regen-status
"blocker is deferred" warning instead of silently coming up runnable the
moment M1-716 lands. Reopen with `/m1-tick reopen M1-717` once the results
file named in §Context carries measured values in its §1 and §3; its §4
per-language verdicts are what release M1-718/719/720 individually.

**Spec amendment required.** Four of the six thresholds are hardcoded
Java constants whose comments state that changing them requires a spec
amendment rather than a config tweak. Expect a `spec_amend_for` companion
or an in-ticket spec edit; decide which at `start`.

**Why six, not five.** The evaluation's own notes counted five and missed
`CommandIntentIndex.TOPIC_SIMILARITY_THRESHOLD = 0.60`
(`CommandIntentIndex.java:63`, read at `ChatAgent.java:879`). It gates the
`doc_embedding` command-intent corpus rather than `post_embedding`, so it
is a different corpus but the *same* similarity scale, and its javadoc
states it deliberately mirrors `HelpLookupTool.SIMILARITY_THRESHOLD`.
Leaving it on nomic's scale while true matches move to 0.38–0.52 would
reproduce this ticket's central failure — a green build whose lookup
returns nothing — on the `helpLookup` topic path instead of the post path.
It is folded in here rather than split out for the same reason the swap and
the recalibration are one ticket: a separate ticket is a merge window in
which that is the deployed state. Because it reads a different corpus, it
needs its own distribution in results §3, not a copy of the post-corpus
row. Its javadoc already names live-corpus calibration as the open
follow-up this evaluation supplies.

**Dimension forks the diff.** 768-d needs no DDL — `V11`'s `vector(768)`
stands and `migration_touch` stays false. Any other dimension adds a
Flyway migration, flips `migration_touch: true`, and pulls in the
dimension-change migration deferred by design §2.8/§5.5. pgvector caps
HNSW at 2000 dims (verified on 0.8.3), so a 2560-d or 4096-d model
additionally requires Matryoshka slicing plus L2 renormalisation in
`OpenAiCompatibleEmbeddingProvider` — add that file to `files_scope` if the
chosen model needs it.

**Re-embed is a data operation, not a migration.** ~9,200 rows; measured
throughput on the old box ranged 0.31–1.71 s/post by model. The V60
`doc_embedding` command-intent corpus self-heals on boot because each row
stores a content hash plus the active model id.
