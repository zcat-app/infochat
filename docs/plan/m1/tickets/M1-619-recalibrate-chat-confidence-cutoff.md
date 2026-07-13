---
id: M1-619
title: "Recalibrate the chat confident-grounding cutoff (M1-618 CONFIDENT_SIMILARITY_CUTOFF): measure clarify-vs-affordance separation on the labeled query set"
status: done
created: 2026-07-13
last_updated: 2026-07-13
blocked_by: []
files_budget: 6
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Changing the RETRIEVED SET or its order. This ticket moves only the
    prose-gate confidence constant that decides clarify-vs-affordance WORDING;
    it must not touch the hybrid retrieval mechanism (M1-617) or the
    grounding threshold (M1-616). The M1-618 determinism regression guard
    (retrieved set byte-identical on unchanged DB state, D19) stays green.
  - >-
    The `infochat.chat.semantic-threshold` grounding gate (0.40, M1-616). That
    is a SEPARATE, lower boundary (the grounding floor). This ticket tunes the
    confident/marginal boundary that sits ABOVE it (similarity >= floor). The
    0.40 gate is not re-touched.
  - >-
    LLM-in-the-retrieval-loop techniques (query rewrite / HyDE / re-ranking) —
    D19-gated and recorded considered-and-deferred in M1-617's D58. Not part of
    a prose-gate calibration.
  - >-
    Promoting `CONFIDENT_SIMILARITY_CUTOFF` from a fixed code constant to a
    runtime `@ConfigProperty`. Keep it a code constant (its current shape,
    ChatAgent.java) and move only the number, UNLESS the measurement
    specifically motivates per-deployment tuning — in which case that is a
    scoped escalate->refine decision, not a silent expansion.
acceptance:
  - >-
    A measurement harness (reuse/extend the M1-616 harness family under
    docs/plan/m1/spikes/ — M1-616-threshold-eval.py + M1-616-query-samples.jsonl)
    that embeds each labeled query on the SAME local nomic backend the provider
    uses and, per query, computes the best grounded similarity
    (1 - min cosine distance among subscribed READY posts UNDER the 0.40
    grounding gate) and classifies the turn into confident / marginal / empty
    across a SWEEP of candidate cutoff values (e.g. 0.60 - 0.80). This mirrors
    ChatAgent.isMarginalGrounding: similarity = 1 - distance; a turn grounded
    only via the lexical arm (no numeric similarity) is marginal by construction.
  - >-
    A report (docs/plan/m1/spikes/M1-619-confidence-cutoff-calibration.md or
    similar) presenting how the confident/marginal split shifts across the
    cutoff sweep, characterising the CURRENT 0.75 behaviour (the 2026-07-13
    live-verification finding: on-domain best-grounded similarity clusters at
    ~0.61-0.73, so essentially ALL on-domain queries fall in the marginal
    /clarify band and the confident "more like this" affordance fires only for
    near-duplicate queries such as an exact-title match at ~0.94), and
    recommending a cutoff value (or retaining 0.75) with rationale. The report
    must state that this constant gates REPLY PROSE (clarify question vs
    affordance offer), NOT the retrieved set (D19), and that it sits ABOVE the
    M1-616 grounding floor (1 - 0.40 = 0.60).
  - >-
    A decision: if the measurement supports a different value, update
    CONFIDENT_SIMILARITY_CUTOFF in ChatAgent.java and the design-doc mention in
    docs/design/05-llm-and-embeddings.md §5.4.6; otherwise state explicitly in
    the report that 0.75 is retained and why. If the constant moves, the three
    M1-618 boundary tests in ChatAgentTest.java whose fixtures pin the old
    cutoff are updated to the new value (named under test_plan.modifies). The
    retrieved set stays byte-identical (D19) — only the prose-gate number moves.
  - >-
    mvn verify is green from the repo root IF ChatAgent.java or a test changes;
    if the deliverable is a standalone harness + labeled set + report with the
    0.75 value RETAINED (no Java/config/test change), the diff is inert and
    mvn verify is N/A per the inert-diff rule.
test_plan:
  adds:
    - >-
      A reusable confident-vs-marginal cutoff measurement harness plus its
      labeled/analysed query set (reuse or extend M1-616-query-samples.jsonl
      with confidence labels).
  modifies:
    - >-
      CONDITIONAL — only if the measurement moves the cutoff: the three M1-618
      boundary tests in
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
      (lowConfidenceGroundingTriggersClarifyDirective,
      confidentGroundingSurfacesMoreLikeThisAffordanceAndDoesNotClarify,
      isMarginalGroundingSeparatesConfidentFromWeak) — their fixtures pin the
      old 0.75 boundary and are updated to the new value. If 0.75 is retained,
      NO test is modified.
  preserves:
    - all tests currently green on main
    - >-
      the D19 determinism guarantee (retrieved set SQL-decided, unchanged by
      this ticket — the M1-618 regression guard stays green)
spec_refs:
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
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
      files: 8
      added: 619
      removed: 32
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-07-13
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-619: Recalibrate the chat confident-grounding cutoff (M1-618 CONFIDENT_SIMILARITY_CUTOFF)

## Context

M1-618 asks ONE clarifying question when chat retrieval is low-confidence and
surfaces a "more like this" affordance when it is confident. The confident/
marginal boundary is a single fixed code constant,
`CONFIDENT_SIMILARITY_CUTOFF = 0.75` (`ChatAgent.java`), where
similarity = 1 - cosine distance and the marginal band is
(grounding floor 0.60, 0.75). Live verification of M1-616/617/618 on the real
app (2026-07-13, remote-llm/DeepSeek over SimpleX) showed the cutoff is
mis-calibrated for `nomic-embed-text` on the live corpus: typical short
on-domain queries top out at best-grounded similarity ~0.73, so ESSENTIALLY
ALL on-domain queries land in the marginal band and trigger the clarifying
question, while the confident affordance path fires only for near-duplicate
queries (an exact post-title query measured ~0.94). This is the direct sibling
of M1-616, which calibrated the 0.40 grounding threshold; here we calibrate the
higher prose-gate boundary that decides clarify-vs-affordance. Because the read
path is a pure pgvector query, the confident/marginal separation is measurable
offline against the live corpus, and the existing M1-616 harness family already
embeds queries on the same nomic backend the provider uses.

## Acceptance

See the YAML `acceptance:` list. In prose: extend the M1-616 harness to compute
per-query best-grounded similarity and classify confident/marginal/empty across
a cutoff sweep; write a calibration report characterising the current 0.75
behaviour and recommending a value; and — if the data supports it — move the
single `CONFIDENT_SIMILARITY_CUTOFF` constant (updating the M1-618 boundary
tests and the design-doc mention) or retain 0.75 with documented rationale. The
constant gates reply PROSE only; the retrieved set stays byte-identical (D19).

## Out-of-scope

No retrieval-mechanism change (M1-617) and no grounding-threshold change
(M1-616); no LLM-in-retrieval techniques (D19-gated, M1-617 D58); do not turn
the code constant into a runtime config property absent a specific motivation.

## Notes

- Sibling of M1-616. Reuse the offline pattern already proven there and in this
  ticket's provenance investigation: embed a query via local Ollama nomic, scope
  to `status='READY'` + subscription, compute the best distance UNDER the 0.40
  gate, and derive similarity = 1 - distance. The classification logic must match
  `ChatAgent.isMarginalGrounding` exactly (max over numeric similarities; a
  lexical-only grounded turn — no numeric similarity — is marginal).
- The main design question for the plan phase: is the fix simply lowering 0.75,
  or does the marginal band also need a floor adjustment so genuinely-weak hits
  still clarify while decent hits get the affordance? Pick the simplest change
  that separates "answer was good, offer more" from "too weak, ask first" on the
  M1-616 labeled set.
- D19-neutral: this gates whether the LLM writes a question or an offer, never
  which posts are retrieved or their order. The M1-618 determinism regression
  guard remains the backstop.
- Live-verification provenance and the measured similarity distribution are
  recorded in the session memory `rag-hybrid-retrieval-verified-live`.
