---
id: M1-616
title: "Calibrate infochat.chat.semantic-threshold: measure semantic-retrieval recall/precision on the live corpus"
status: done
created: 2026-07-12
last_updated: 2026-07-12
clarity_check:
  date: 2026-07-12
  verdict: WARN
  warnings:
    - >-
      FILES-BUDGET-PLAUSIBLE: if the calibration recommends changing
      infochat.chat.semantic-threshold away from 0.5, the documented default in
      docs/design/05-llm-and-embeddings.md:513 goes stale and isn't in the
      acceptance list or files_budget; bump files_budget to 5 or note the
      design-doc default is intentionally left as descriptive prose.
  blockers: []
blocked_by: []
files_budget: 6
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
provenance: >-
  2026-07-12 embedding-efficiency investigation. Embeddings are confirmed
  healthy under remote-llm (100% coverage of READY posts, all local nomic-768,
  D54 — DeepSeek never touches them), but the chat retrieval THRESHOLD has never
  been calibrated: `infochat.chat.semantic-threshold=0.5` is a hand-picked
  default. Live offline probes against the corpus showed it is loose — for a
  real query ~8% of the corpus (435/5268) sits under 0.5, and an off-domain
  query ("banana bread recipe") matched "Nano Banana Pro" at cosine distance
  0.378, i.e. UNDER the 0.5 gate, so the chat agent would fold that spurious
  post in as grounding. Meanwhile the SemanticSearchTool read path is a pure
  pgvector query, so recall/precision is directly measurable offline against the
  live corpus without standing the bot up. The verification suite deliberately
  proves retrieval PRESENCE, not quality (docs/spec/verification.md, live-e2e
  README §"presence, not quality scoring"), so no recall/precision baseline
  exists today — this ticket establishes one and calibrates the gate. Sibling of
  the measurement spikes M1-609 / M1-612.
out_of_scope:
  - >-
    Implementing hybrid semantic/lexical retrieval, provenance transparency, or
    any change to the chat retrieval LOGIC — that is M1-617. This ticket
    measures the existing semantic-only path and tunes the threshold. EXCEPTION
    (2026-07-12 escalation refine): applying the calibrated value also updates
    the `@ConfigProperty defaultValue` constant in SemanticSearchTool.java,
    because application.properties documents (lines 391-392) that the two "must
    not drift"; this is the single non-drift twin of the config value, NOT a
    change to the retrieval query/logic, which stays M1-617's.
  - >-
    Re-ranking, query rewriting, HyDE, or any LLM-in-the-retrieval-loop
    technique. Those change the RETRIEVED SET as a function of non-deterministic
    LLM output and so collide with the D19 determinism boundary; they are a
    separate, determinism-gated decision, not part of a calibration measurement.
  - >-
    The collector-side `infochat.linking.semantic-threshold` (0.18). It gates a
    different decision (post-to-post linking) over a different distance
    distribution (post->post, not query->post) and is not re-tuned here.
acceptance:
  - >-
    A labeled query set (>= 15 queries with hand-verified known-relevant post
    UIDs drawn from the LIVE corpus, spanning on-topic, paraphrased, exact-term
    /CVE/product-name, and deliberately-off-domain queries) checked into the
    spike directory, plus a reusable harness (extend the offline pgvector probe /
    the M1-609 harness family) that embeds each query on the SAME local nomic
    backend the provider uses and computes recall@k and precision@k for the
    SemanticSearchTool query shape across a sweep of threshold values
    (e.g. 0.30 - 0.60).
  - >-
    A report (docs/plan/m1/spikes/M1-616-threshold-calibration.md or similar)
    presenting the recall/precision-vs-threshold curve, characterising the
    current 0.5 behaviour INCLUDING the false-positive band (the off-domain
    grounding class), and recommending a calibrated threshold value with
    rationale. The report must note that query->post cosine distances run
    systematically LARGER than the post->post distances the 0.18 linking
    threshold sees, so the two thresholds are not directly comparable and the
    chat gate cannot simply borrow the linking value.
  - >-
    A decision: if the measurement supports a different default, update
    `infochat.chat.semantic-threshold` to the recommended figure; otherwise state
    explicitly in the report that 0.5 is retained and why. The measurement
    supported 0.40; per the 2026-07-12 escalation (operator chose "apply now"),
    the value is moved in ALL THREE places the default lives so nothing drifts:
    infochat-provider/.../application.properties, the SemanticSearchTool.java
    `@ConfigProperty defaultValue` non-drift twin, and the stale documented
    default in docs/design/05-llm-and-embeddings.md. The retrieval remains a hard
    deterministic cosine-distance gate (D19) — only the number moves, not the
    mechanism.
  - >-
    mvn verify is green from the repo root IF the properties value (or any
    Java/config/DB file) changes; if the deliverable is a standalone harness +
    labeled set + report with no Java/config/DB change, the diff is inert and
    mvn verify is N/A per the inert-diff rule.
test_plan:
  adds:
    - >-
      A reusable recall/precision measurement harness plus its checked-in
      labeled query set (query -> known-relevant UIDs).
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Embedding pipeline
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
  - D54
reviews:
  - round: 1
    date: 2026-07-12
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PARTIAL
    diff_stats:
      files: 8
      added: 672
      removed: 24
  - round: 2
    date: 2026-07-12
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 705
      removed: 25
escalations:
  - date: 2026-07-12
    reason: files-budget-exceeded
    trigger: >-
      The measurement supported lowering the default (0.50 -> 0.40). Applying it
      without drift requires application.properties + the SemanticSearchTool
      defaultValue non-drift twin + the design-doc default = 6 files vs
      files_budget:4, and touches the out_of_scope §1 code-path fence. Surfaced
      the choice (measurement-only+follow-up / apply-now-with-refine /
      app.properties-only).
    resolution: >-
      Operator chose "apply 0.40 now (refine budget)". Refine: files_budget 4->6,
      out_of_scope §1 carved out the non-drift defaultValue twin, acceptance item
      3 broadened to the three files.
overrides: []
revisions:
  - date: 2026-07-12
    what: >-
      files_budget 4->6; out_of_scope §1 exception for the SemanticSearchTool
      defaultValue non-drift twin; acceptance item 3 broadened to name the three
      files the 0.40 value lives in.
    why: >-
      Operator-approved (2026-07-12 escalation) scope expansion to apply the
      calibrated 0.40 threshold without violating the documented "must not drift"
      invariant between application.properties and SemanticSearchTool.
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-616: Calibrate infochat.chat.semantic-threshold on the live corpus

## Context

`infochat.chat.semantic-threshold` (default 0.5) is the hard cosine-distance
gate that decides, on every chat turn, whether the deterministic `semanticSearch`
pre-fetch (M1-589) folds feed posts into the prompt as grounding or the model
answers from general knowledge. The value has never been calibrated against real
retrieval quality. A 2026-07-12 offline probe against the live corpus showed the
gate is loose: for a real query ~8% of the corpus clears 0.5, and an off-domain
query surfaced a spuriously token-overlapping post (distance 0.378, under the
gate). Because the read path is a pure pgvector query, recall@k / precision@k are
measurable offline against the live corpus, and no such baseline exists — the
verification suite intentionally proves retrieval presence, not quality.

## Acceptance

See the YAML `acceptance:` list. In prose: build a labeled query set from the
live corpus, extend the offline probe / M1-609 harness family to compute
recall@k and precision@k across a threshold sweep, write a calibration report
with the recall/precision curve and a recommended value, and — if the data
supports it — move the single `infochat.chat.semantic-threshold` default. The
gate stays a deterministic cosine-distance cutoff (D19); only the number may
change.

## Out-of-scope

No retrieval code-path change (hybrid retrieval + provenance is M1-617); no
LLM-in-the-loop retrieval techniques (D19-gated); the collector linking
threshold (0.18) is not re-tuned.

## Notes

- Reuse the offline pattern already proven in this investigation: embed a query
  via the local Ollama nomic backend, run
  `ORDER BY pe.embedding <=> $vec` scoped to `status='READY'` + subscription, and
  read back distances. The M1-609 harness family under docs/plan/m1/spikes/
  already POSTs prompts and records structured results — extend it rather than
  start fresh.
- Feeds M1-617: the calibrated threshold is the semantic arm's contribution to
  the hybrid fusion, so landing this first gives M1-617 a tuned semantic input.
  They are not hard-blocking (M1-617 can proceed on 0.5), but sequencing this
  first is cheaper.

## Round 1 rework

Reviewer (round 1) — REWORK, 1 item (ACCEPTANCE-CHECK: PARTIAL):
- The report's TL;DR and §7 still described the PRE-escalation state ("the apply
  decision is escalated", "0.50 is retained in code", "vs files_budget: 4",
  code-path fence blocking), contradicting the same diff, which applied 0.40 to
  all three sites after the operator's "apply now" decision. Fixed: TL;DR and §7
  rewritten to state the operator approved applying 0.40 and that 0.40 is now the
  in-code default in application.properties, the SemanticSearchTool defaultValue
  twin, and the design doc. Docs-only fix; no Java/config/test change.
