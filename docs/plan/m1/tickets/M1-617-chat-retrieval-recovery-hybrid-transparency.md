---
id: M1-617
title: "Chat retrieval-recovery: provenance transparency + hybrid semantic/lexical (RRF) post retrieval"
status: pending
created: 2026-07-12
last_updated: 2026-07-12
blocked_by: []
files_budget: 12
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: true
provenance: >-
  2026-07-12 embedding-efficiency / retrieval-recovery investigation. Two gaps
  in the chat retrieval surface make "the answer wasn't what I wanted"
  unrecoverable today: (1) retrieval is SEMANTIC-ONLY — SearchPostsTool is
  tag + time-window only (confirmed: its SQL has no keyword/title/body/entity
  match), so a keyword-exact or CVE/product-name query that embeds poorly is
  simply missed even when the post is in the corpus (the live-test "Tenda
  backdoor in the same /summary but 'tell me more' fails" case, flagged as the
  biggest chat UX gap); and (2) when the semantic pre-fetch returns nothing under
  the threshold, the agent silently answers from general knowledge with NO
  user-visible signal that it did not consult the feed, so the user cannot tell
  a "found nothing" answer from a "didn't look" answer. This ticket adds a
  deterministic lexical retrieval arm fused with the semantic arm (Reciprocal
  Rank Fusion) and makes retrieval provenance explicit in the reply. It extends
  the D28 hybrid pattern (deterministic keyword pre-fetch + agent tool), already
  used for chat MEMORY, to POST retrieval. Per user direction (2026-07-12),
  transparency and hybrid retrieval ship as ONE ticket (one retrieval-recovery
  UX story).
out_of_scope:
  - >-
    LLM-in-the-retrieval-loop techniques — query rewriting/expansion, HyDE
    (hypothetical-document embeddings), and LLM/cross-encoder RE-RANKING. Each
    makes the RETRIEVED SET a function of non-deterministic LLM (or GPU-model)
    output, colliding with the D19 determinism boundary ("the LLM is not allowed
    to pick the set of posts a query returns") and, for cross-encoder re-rank,
    the CPU-only VPS posture (M1-609 local-model spike). These are a separate,
    explicitly determinism-gated decision; note them in the new decision entry
    as considered-and-deferred, do not implement.
  - >-
    Threshold calibration of `infochat.chat.semantic-threshold` — that is M1-616.
    This ticket consumes whatever value is configured as the semantic arm's
    input; it does not re-tune it.
  - >-
    Clarifying-question generation and surfacing getReferences ("more like
    this") as recovery affordances. Worth doing, but a separate conversational-
    refinement follow-up; this ticket is the retrieval-mechanism + transparency
    layer only.
  - >-
    Any new user-facing slash command (e.g. a deterministic `/search`). If the
    hybrid arm is exposed, it is via the existing chat tool surface, not a new
    command.
acceptance:
  - >-
    A deterministic LEXICAL retrieval arm over the post corpus using PostgreSQL
    full-text search (tsvector / plainto_tsquery over title + body), enforcing
    the SAME trust boundary as the existing semantic/tag paths: `status='READY'`,
    per-(user,scope) subscription isolation (only subscribed sources), and the
    result folded back inside the `UNTRUSTED_CONTENT` delimiter wrapper. A
    Flyway migration adds the generated tsvector column (or expression index) and
    its GIN index; the migration follows the baseline-migration conventions and
    grants the provider role SELECT-only, consistent with the embedding store.
  - >-
    Semantic and lexical results are fused DETERMINISTICALLY (Reciprocal Rank
    Fusion) with a stable, total tie-break (fused score, then post_id) so the
    returned set and its order are reproducible: same DB state -> same set, same
    order, with no LLM in the retrieval loop (D19). A test asserts identical
    output across two consecutive calls on unchanged DB state.
  - >-
    Isolation is preserved on the NEW arm: a test proves an unsubscribed or
    non-READY post can never surface through the lexical/fused path, mirroring
    the existing SemanticSearchToolIT subscription-isolation coverage. A test
    also demonstrates the target recall win — a keyword-exact post that the
    semantic-only path misses is now retrieved through the lexical arm (the
    "Tenda backdoor" class).
  - >-
    Retrieval PROVENANCE is explicit in the chat reply: the response signals
    whether it was grounded in feed posts (naming the cited post UIDs / a count)
    or answered from general knowledge because retrieval was empty. The signal
    is plain-text, translation-safe (routed through the bundle / Translation
    provider like other bot prose, with an en + cs bundle key pair per D43), and
    the previously-silent empty path stops being silent. Wording lives in design
    notes.
  - >-
    Spec + design + decisions updated in the same ticket (code-coordinated):
    docs/spec/commands.md §Chat mode gains the provenance behaviour;
    docs/design/05-llm-and-embeddings.md §5.4.6 documents the hybrid
    semantic/lexical retrieval + RRF fusion; and a new decision entry (next id
    D58) records the hybrid-retrieval choice, its determinism-preserving
    constraint (SQL-only fusion, D19), the extension of the D28 hybrid pattern
    from memory to posts, and the considered-and-deferred LLM-in-retrieval
    techniques.
  - >-
    Full `mvn verify` is green from the repo root (this ticket changes Java,
    a migration, config, and bundle resources — the inert-diff rule does not
    apply). New tests are added per the test_plan and the pre-existing suite
    stays green.
test_plan:
  adds:
    - >-
      Lexical-arm subscription-isolation IT (unsubscribed / non-READY post never
      surfaces via the lexical or fused path).
    - >-
      RRF fusion determinism test (same DB state -> byte-identical fused set and
      order across two calls).
    - >-
      Recall-win test (keyword-exact post missed by semantic-only is retrieved
      via the lexical arm).
    - >-
      Provenance-signal test (grounded reply names cited UIDs; empty-retrieval
      reply carries the general-knowledge signal), including the en/cs bundle
      key-pair presence (BundleLoaderTest keyset parity, D43).
  preserves:
    - all tests currently green on main
    - >-
      the D19 determinism guarantee for chat retrieval (retrieved set is
      SQL-decided, reproducible on unchanged DB state)
    - >-
      per-(user,scope) subscription isolation across ALL retrieval arms
spec_refs:
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
  - D28
  - D54
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-617: Chat retrieval-recovery — provenance transparency + hybrid semantic/lexical retrieval

## Context

Two gaps make an unsatisfying chat answer unrecoverable today. First, retrieval
is semantic-only: `SearchPostsTool` filters by tag + time window with no
keyword/title/body match, so a keyword-exact or identifier-heavy query
(CVE ids, product names) that embeds poorly is missed even when the post is in
the corpus — the live-test "Tenda backdoor" case, flagged as the biggest chat
UX gap. Second, when the semantic pre-fetch clears nothing under the threshold,
the agent silently answers from general knowledge with no signal that it did not
consult the feed, so the user cannot distinguish "found nothing" from "didn't
look."

This ticket adds a deterministic lexical arm (Postgres full-text) fused with the
semantic arm via Reciprocal Rank Fusion, and makes retrieval provenance explicit
in the reply. It is the direct analog, for POST retrieval, of the D28 hybrid
pattern already used for chat memory (deterministic keyword pre-fetch + tool).

## The determinism constraint (why the fancy RAG tricks are out of scope)

D19 is explicit: "Retrieval (which posts come back, in what order, with what
filters) is always SQL … the LLM is not allowed to pick the set of posts a query
returns." That is the dividing line for this ticket. Hybrid semantic + lexical
fusion by RRF stays entirely in SQL/Java and is reproducible, so it is allowed
and is the core of this ticket. Query rewriting, HyDE, and LLM/cross-encoder
re-ranking all make the retrieved SET depend on non-deterministic model output;
they are recorded in the new decision as considered-and-deferred behind a
determinism exception, not implemented here.

## Acceptance

See the YAML `acceptance:` list. In prose: add a full-text lexical retrieval arm
(migration + GIN index) that honors the same READY + subscription isolation and
UNTRUSTED_CONTENT wrapping as the existing arms; fuse it with the semantic arm by
RRF with a total, reproducible ordering (D19); make the chat reply state whether
it was feed-grounded (cite UIDs) or general-knowledge (translation-safe, en/cs
bundle pair); update commands.md §Chat mode, design §5.4.6, and add decision D58.
Full mvn verify green.

## Out-of-scope

LLM-in-the-loop retrieval (query rewrite / HyDE / re-rank — D19-gated); threshold
calibration (M1-616); clarifying-question + "more like this" refinement (separate
follow-up); any new slash command.

## Notes

- Scope check: this is a high-complexity, security-relevant, migration-touching
  ticket bundling two deliverables per user direction. If the clarity/plan gate
  at start finds it too large for one clean diff, decomposing into a
  transparency slice + a hybrid-retrieval slice is a legitimate outcome — the
  two share the retrieval surface but not the migration.
- The lexical arm is a new trust-boundary surface: the model-supplied query text
  reaches `plainto_tsquery` (parameterized, never string-concatenated), and the
  isolation predicates (READY + subscription) must sit INSIDE the fused query,
  exactly as SemanticSearchTool places them inside the index-driven query, so no
  over-fetch-then-filter path can leak an unsubscribed post. This is why the
  ticket is security_relevant and should get a /redteam pass.
- RRF is the standard deterministic fusion of two ranked lists
  (score = sum 1/(k + rank_i)); pick a fixed k and a total tie-break on post_id
  so the fused order is stable independent of input row order.
- Related but independent: M1-616 calibrates the semantic arm's threshold. This
  ticket runs correctly on the current 0.5; landing M1-616 first just gives the
  semantic input better precision before fusion.
