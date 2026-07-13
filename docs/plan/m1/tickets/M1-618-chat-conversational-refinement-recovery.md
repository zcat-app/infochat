---
id: M1-618
title: "Chat conversational-refinement recovery: clarifying-question on low-confidence retrieval + surface getReferences as 'more like this'"
status: done
created: 2026-07-12
last_updated: 2026-07-13
clarity_check:
  date: 2026-07-13
  verdict: WARN
  warnings:
    - "FILES-BUDGET-PLAUSIBLE: estimated 7-9 files touched vs files_budget: 6; bump budget or consolidate at plan time."
    - "SECURITY-FLAG-CONSISTENT: security_relevant: false despite acceptance item 3 reasoning about prompt-injection containment (UNTRUSTED_CONTENT wrapper) for new LLM-authored prose."
  blockers: []
blocked_by:
  - M1-617
files_budget: 6
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
provenance: >-
  2026-07-12 retrieval-recovery investigation; carved out of M1-617's
  out_of_scope. M1-617 builds the retrieval MECHANISM (hybrid semantic/lexical
  fusion) and the provenance SIGNAL (grounded vs general-knowledge). This ticket
  adds the two conversational-refinement affordances that sit on top of that
  surface, for the case where the first answer still is not what the user wanted:
  (1) when retrieval confidence is low/ambiguous the agent asks ONE clarifying
  question to narrow intent rather than returning a weak grounded guess; and
  (2) grounded replies surface the existing (but hidden) deterministic
  getReferences tool as a "more like this" affordance. Both are determinism-safe:
  a clarifying question is reply PROSE (not a change to the retrieved set), and
  getReferences is already an isolated, deterministic SQL tool (GetReferencesTool
  — related posts via post_reference entity+semantic links, subscription-scoped
  on both endpoints, score-ordered). In a plain-text messaging app with no
  buttons/facets, conversational refinement IS the recovery UX.
out_of_scope:
  - >-
    Any new or changed retrieval MECHANISM. This ticket must not alter WHICH
    posts are retrieved or their order — that is M1-617 (hybrid) and M1-616
    (threshold). The clarifying question changes reply prose only; a determinism
    regression guard asserts the retrieved set is unchanged by this ticket.
  - >-
    LLM-in-the-retrieval-loop techniques (query rewrite / HyDE / re-ranking).
    Deferred and D19-gated, per M1-617's decision entry.
  - >-
    Auto-executing getReferences on every grounded turn (an always-on extra
    fetch adds latency for no asked-for value). The affordance is an OFFER, or an
    on-request tool call the user triggers ("tell me more about p-XXX") — not an
    unconditional pre-fetch.
acceptance:
  - >-
    When a deterministic retrieval-confidence signal is LOW (e.g. the grounding
    block cleared only marginal results near the threshold, or the results span
    multiple distinct link-clusters/topics — the exact heuristic decided at the
    plan phase), the agent asks ONE clarifying question to narrow intent instead
    of returning a weak grounded answer. The confidence/ambiguity signal is
    computed in JAVA from deterministic retrieval metadata and passed into the
    prompt — the LLM does not invent "confidence"; it only writes the question.
    Retrieval stays SQL-decided and reproducible (D19) — this ticket changes
    reply prose, never the retrieved set.
  - >-
    A grounded reply surfaces getReferences as a "more like this" affordance:
    when the answer is grounded in specific posts, the agent makes the existing
    deterministic related-posts tool discoverable (offer related posts, or tell
    the user they can ask "tell me more about p-XXX"), so getReferences stops
    being a hidden capability. No unconditional extra fetch.
  - >-
    Any NEW fixed bot-prose string is translation-safe: an en + cs bundle key
    pair (D43 bilateral keyset, BundleLoaderTest parity). LLM-generated
    clarifying prose routes through the normal per-scope translation path like
    other chat output; it must not echo untrusted post content outside the
    UNTRUSTED_CONTENT wrapper (prompt-injection hygiene — the clarifying question
    is derived from retrieval METADATA, not raw body text).
  - >-
    Spec + design updated (code-coordinated): docs/spec/commands.md §Chat mode
    gains the clarifying-question + refinement-affordance behaviour, and
    docs/design/05-llm-and-embeddings.md §5.4.6 documents it alongside the
    M1-617 hybrid/provenance surface it builds on.
  - >-
    Full `mvn verify` is green from the repo root (this ticket changes Java,
    prompt/config, and possibly bundle resources). New tests are added per the
    test_plan and the pre-existing suite stays green.
test_plan:
  adds:
    - >-
      Low-confidence-triggers-clarify test (the deterministic signal fires the
      clarifying-question path).
    - >-
      Confident-result-does-NOT-clarify test (a strong grounded result answers
      directly, no spurious clarifying question).
    - >-
      More-like-this affordance present on a grounded reply.
    - >-
      Determinism regression guard: the retrieved SET and order for a fixed DB
      state are identical with and without this ticket's prose changes (D19).
    - >-
      en/cs bundle keyset parity if a fixed prose key is added (D43).
  preserves:
    - all tests currently green on main
    - >-
      the D19 determinism guarantee (retrieved set is SQL-decided, unchanged by
      this ticket)
    - per-(user,scope) subscription isolation on getReferences
spec_refs:
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
  - D28
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
      files: 6
      added: 367
      removed: 34
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-13
    verdict: CLEAN
    base: "merge-base main (fork point 3bdd9f64)"
    head: "working tree (uncommitted, in-review)"
    verdict_file: docs/plan/m1/redteam/M1-618-2026-07-13.md
    out_of_model_count: 0
    note: |
      Extra-rigor audit (security_relevant:false) prompted by the clarity
      check flagging acceptance item 3's prompt-injection requirement and the
      diff touching LLM chat-prompt assembly. CLEAN — refinement directives sit
      after the UNTRUSTED_CONTENT close and never echo post content; confidence
      signal is a read over tool JSON that leaves the retrieved set unchanged.
---

# M1-618: Chat conversational-refinement recovery

## Context

M1-617 gives chat retrieval two new things: a hybrid semantic/lexical mechanism
and an explicit grounded-vs-general-knowledge provenance signal. This ticket adds
the conversational-refinement layer on top, for the case the earlier design
discussion framed as "the user still doesn't like the answer — what's next?":

1. **Clarifying question on low confidence.** When retrieval is weak or
   ambiguous, a general assistant should ask one narrowing question rather than
   confidently grounding a guess. The trigger is a deterministic confidence/
   ambiguity signal computed from retrieval metadata; the LLM only writes the
   question.
2. **"More like this" affordance.** `getReferences` already returns related posts
   (entity + semantic links, subscription-isolated, deterministic) but is a
   hidden capability. Grounded replies should surface it so the user can pivot to
   related posts.

In a plain-text messaging surface there are no buttons, chips, or facets — so
conversational refinement (a follow-up question, an offered pivot) is the only
available recovery UX.

## Why this is determinism-safe (D19)

D19 forbids the LLM from picking the set of posts a query returns. Neither
affordance touches that: the clarifying question is reply PROSE emitted instead
of (or before) a grounded answer, and getReferences is an existing deterministic
SQL tool. The confidence signal that triggers the clarifying question is computed
in Java from deterministic retrieval metadata, not asked of the model. A
regression guard asserts the retrieved set is byte-identical with and without
this ticket.

## Acceptance

See the YAML `acceptance:` list. In prose: compute a deterministic
low-confidence/ambiguity signal from the M1-617 retrieval output, pass it into
the prompt so the agent asks one clarifying question when it fires; surface
getReferences as a "more like this" affordance on grounded replies; keep all new
fixed prose translation-safe (en/cs bundle pair); update commands.md §Chat mode
and design §5.4.6. Full mvn verify green, retrieved set provably unchanged.

## Out-of-scope

No retrieval-mechanism change (M1-617) and no threshold change (M1-616); no
LLM-in-retrieval techniques (D19-gated); no unconditional getReferences
pre-fetch.

## Notes

- Blocked on M1-617: the confidence signal is most naturally derived from the
  fused-retrieval output and the provenance block that ticket introduces, and the
  "more like this" affordance pairs with its grounded-vs-general-knowledge
  signal. Running 617 first avoids building a throwaway signal against the
  semantic-only path.
- The confidence heuristic is the main design question for the plan phase.
  Candidate signals: marginal top-1 similarity (just inside the threshold),
  multi-topic spread across the returned link-clusters, or (post-M1-617) a thin
  fused result. Pick the simplest that separates "confident" from "ambiguous" on
  the M1-616 labeled query set if it is available.
- Keep the clarifying question to ONE question and never block the user: if they
  re-ask or ignore it, the agent proceeds with the best available grounding —
  the clarifying question is an offer to narrow, not a gate.
