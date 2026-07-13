---
id: M1-617
title: "Chat retrieval-recovery: provenance transparency + hybrid semantic/lexical (RRF) post retrieval"
status: done
created: 2026-07-12
last_updated: 2026-07-13
clarity_check:
  date: 2026-07-12
  verdict: WARN
  warnings:
    - >-
      FILES-BUDGET-PLAUSIBLE: mental estimate (~13 files) is at or slightly
      over the stated files_budget: 12. Consider raising the budget to 14-15
      or pre-committing to the transparency/hybrid-retrieval split the
      ticket's own §Notes already floats, so the plan-gate isn't the first
      place this gets decided.
    - >-
      COMPLEXITY-RISK-CALIBRATED: risk: medium is defensible but sits low
      given migration_touch: true + security_relevant: true + an explicitly
      described isolation-leak failure mode with a recommended /redteam pass.
      Consider risk: high, or leave as-is with the redteam pass as the
      compensating control.
  blockers: []
outline_file: target/m1-tick-outline-M1-617.md
blocked_by: []
files_budget: 15
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
  modifies:
    - >-
      InboundRouterChatModeIT.chatModeDispatchesToAgent — update the pinned
      finalize-body expectation to reply + blank line + the
      reply.chat.provenance.general_knowledge bundle string: the assertion
      pins the exact outbound surface acceptance item 4 changes (authorized
      via escalate->refine 2026-07-13; the empty-retrieval turn now signals
      instead of staying silent).
  preserves:
    - >-
      all tests currently green on main (except the one expectation named
      under modifies, updated to the new specified outbound)
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
reviews:
  - round: 1
    date: 2026-07-13
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 17
      added: 1294
      removed: 70
escalations:
  - date: 2026-07-12
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (pre-implementation). Plan-writer outline (target/m1-tick-outline-M1-617.md)
      enumerates 11 production/doc files + 3 new test files = 14 total vs
      files_budget: 12 (ticket-template: budget counts tests; only STATUS.md and
      the ticket file are exempt). Clarity pre-flight had already WARNed the same
      (~13 estimate) and suggested raising to 14-15 or splitting per ticket §Notes.
  - date: 2026-07-13
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (r1 mvn verify, pre-review). Full suite red with EXACTLY ONE failure:
      InboundRouterChatModeIT.chatModeDispatchesToAgent:104 pins the finalized
      chat outbound to the bare agent reply; under this ticket's acceptance the
      outbound is now reply + blank line + provenance notice (actual:
      "Hello from the chat agent!\n\nNot based on your feed posts; answered
      from general knowledge." — the designed general-knowledge signal). The
      fix is a one-line expectation update in that pre-existing IT, which (a)
      test_plan does not authorize (no `modifies`) and (b) is a 15th file vs
      files_budget: 14. Both gates forbid proceeding silently. All other 257
      provider ITs green, incl. llmUnreachableReturnsFriendlyError proving the
      null-notice degrade path end-to-end.
overrides: []
revisions:
  - date: 2026-07-13
    reason: >-
      budget-breach refine (user-directed via /m1-tick run escalation menu):
      files_budget: 12 cannot fit the enumerated implementation surface; the
      plan-writer outline needs 14 files (11 production/doc + 3 new test files;
      budget counts tests per ticket-template). Clarity pre-flight had WARNed
      the same overrun (~13 estimate, suggested 14-15).
    snapshot: |
      files_budget (pre-refine): 12
      resolution: raise files_budget to 14 — the exact enumerated need
        (V58 migration; SemanticSearchTool, ChatAgent, InboundRouter,
        BundleKeys; en/cs bundle pair; 02-schema.md, commands.md,
        design 05, decisions.md D58; SemanticSearchToolHybridIT,
        ChatAgentProvenanceTest, InboundRouterChatProvenanceTest).
        No slack added; a 15th file re-escalates. All other sizing fields
        (complexity: high, risk: medium, round_cap: 3) unchanged.
  - date: 2026-07-13
    reason: >-
      budget-breach refine #2 (user-directed via /m1-tick run escalation
      menu): r1 verify red with exactly one failure —
      InboundRouterChatModeIT.chatModeDispatchesToAgent:104 pins the
      finalized chat outbound to the bare reply, the exact surface
      acceptance item 4 changes (reply + blank line + provenance notice).
      test_plan had no `modifies` authorization and the file is a 15th
      file vs files_budget: 14.
    snapshot: |
      files_budget (pre-refine): 14
      test_plan (pre-refine): adds + preserves only, no `modifies` key;
        preserves item 1 read "all tests currently green on main".
      resolution: files_budget 14 -> 15; add test_plan.modifies naming
        InboundRouterChatModeIT.chatModeDispatchesToAgent (one-line
        expectation update to reply + blank line +
        reply.chat.provenance.general_knowledge, asserted via bundle key
        per the file's own style); scope preserves item 1 accordingly.
        All other sizing fields (complexity: high, risk: medium,
        round_cap: 3) unchanged.
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-13
    verdict: CLEAN
    base: 4dcbe3765a947d7494fb9b0677493aa89a83d501
    head: m1/M1-617-chat-retrieval-recovery-hybrid-transparency (working tree, pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-617-2026-07-13.md
    out_of_model_count: 1
    note: |
      CLEAN, no findings. One out-of-model item: docs/spec/security.md
      §Prompt-injection defenses still describes semanticSearch as
      semantic-only (threshold-gated), not the new hybrid semantic+lexical
      arm — classified as retrieval-relevance/UX doc staleness, NOT a
      trust-boundary gap (subscription isolation, READY filter, D19 ordering,
      local embedding, similarity-display-only all remain delivered; the
      verification.md CI gate keys on the unchanged tool-name set). Not
      folded into M1-617 (acceptance item 5 authorizes only commands.md /
      design 05 / decisions.md; security.md is outside the approved file
      set). Recommended follow-up: a pure-doc spec: commit resyncing the
      security.md semanticSearch row after merge.
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
