---
id: M1-968
title: "Measure KB-miss rate and query-class mix (M0) + Brave spike"
status: pending
created: 2026-09-01
last_updated: 2026-09-01
flow: tick
reproduction: >-
  Probe form (docs-only-plus-metric change; no failing behavior test can
  exist before the counter does — the M0 evidence gate standing in for a
  defect reproduction per the brief's reproduction-status note). Today's
  wrong behavior, probe-verified on this checkout 2026-09-01: the KB-miss
  rate — the volume formula's one unknown input — is UNMEASURABLE:
  (1) grep -rn 'grounded\|kbmiss\|kb_miss' infochat-provider/src/main/java
  returns no metric or log emission anywhere on the pre-fetch path (the
  pre-fetch outcome at ChatAgent.java:962-968 flows only into the prompt
  block and the notice decision at :877-888, never into an observable
  counter); (2) the provenance notice is NOT persisted — the pending
  commit writes only the user turn and the sanitized assistant turn to
  chat_session (ChatAgent.java:855-858) — so chat_message rows cannot be
  classified post hoc by DB query; (3) no spike script or seed corpus
  exists: ls scripts/ | grep -i 'spike\|brave\|websearch' returns nothing.
  The intended test this ticket adds (to-be-written next to it):
  ChatAgentGroundingMetricTest#preFetchOutcomeClassifiesGroundedMarginalKbmiss.
analysis_ref: docs/plan/m1/tick-analysis/websearch-grounding-lane.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentGroundingMetricTest.java
  - scripts/websearch-spike/brave-spike.py
  - scripts/websearch-spike/seed-corpus.json
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY web-search production capability — no Brave client, no fusion, no
    prompt injection, no notice change. This ticket only makes the
    existing pre-fetch outcome observable and runs the measurement. The
    lane itself is M1-969..M1-972.
  - >-
    Persisting the provenance notice or ANY user-content-bearing
    measurement row — the counter carries counts only (outcome label,
    no user id, no scope id, no text: D37 user-content logging and §13
    aggregate-over-real-users rules). Class-mix labeling reads
    chat_message content OPERATOR-SIDE on the deployment host; its
    outputs (aggregate shares) land in .agents/memory-local/, never in
    the repo.
  - >-
    Any chat-notice, ladder, or prompt-byte change — the counter is a
    pure observation seam appended to the existing step-3 flow; the
    notice decision (:877-888) and ChatPromptBudgetTest are untouched.
  - >-
    M1-965/966/967's notice/ladder seams — the counter classifies the
    PRE-FETCH outcome only and does not touch the admitted-set logic
    those tickets own; land serially with any of them that is in flight.
acceptance:
  - "REPRODUCTION closed (the observable-outcome half): ChatAgentGroundingMetricTest.preFetchOutcomeClassifiesGroundedMarginalKbmiss passes — three scripted pre-fetch outcomes (a non-empty confident result, a non-empty marginal result, an empty result) drive the counter via an injected MeterRegistry (the QuarkusTest idiom; plain JUnit with a SimpleMeterRegistry is preferred if the class needs no container) and assert the outcome label lands in exactly one of grounded|marginal|kbmiss per turn; a mutation classifying every turn kbmiss fails the grounded arm (non-vacuity)."
  - "BREAKER-SKIP classification (failure-mode, analysis P5): ChatAgentGroundingMetricTest.breakerOpenTurnCountsAsKbmissNotSkipped — a turn whose chat-endpoint breaker wouldShortCircuit (stubbed true, the ChatAgent.java:584-589 gate) counts as kbmiss (the pre-fetch was skipped, so the turn has no corpus grounding — the same class the general-knowledge notice covers per security.md:1731-1736); a silent-skip mutation (no counter increment) fails."
  - "D37/§13 DISCIPLINE: the counter carries NO user/scope/text dimension — probe: grep -n 'userId\\|scopeId\\|userMessage' over the counter-increment hunk returns only the pre-existing step-3 locals, never a meter tag; the meter name and its single 'outcome' tag are the only label surface (reviewer diff check)."
  - "Spike vehicle commits (analysis P13/P14): scripts/websearch-spike/seed-corpus.json — the seven user-supplied seed queries (2026-09-01) expanded to the matrix axes {en,cs,es,ru,tr} × {native, english, fused} × {local-topic, global-topic}, with per-cell expected-relevance labels (binary: does the cell's top-5 contain a source that answers the query) — instance-free (no deployment identifiers, public-topic queries only); scripts/websearch-spike/brave-spike.py — a generic runner taking BRAVE_API_KEY from the environment, issuing the corpus against the pinned host's owner-verified web-search endpoint, writing per-cell metrics (top-1/top-5 hit rate, reciprocal rank) to a path argument, AND recording per cell whether the result object carries a per-result age/date field (the checklist item the reference page leaves unanswered — it settles the C6/C7 timestamp-honesty design: whether page-age honesty rides a vendor field or the query's concrete dates alone). Probes: python3 -c 'import json;json.load(open(\"scripts/websearch-spike/seed-corpus.json\"))' exits 0; grep -rn 'api\\.search\\.brave\\.com' scripts/websearch-spike/brave-spike.py returns the ONE pinned-host constant (the endpoint path is owner-live-verified and still lands as that one named constant)."
  - "MEASUREMENT PROCEDURE recorded (the operator-run leg, analysis P13): the ticket body (Approach step 4) is the procedure of record — (a) KB-miss rate: read the counter ratio over a ≥14-day window on the deployment and record the number with its window dates in .agents/memory-local/websearch-m0.md; (b) class mix: on the deployment host, sample ≥200 recent chat turns and label each against the analysis's C0-C9 taxonomy (operator judgment or the deployment's own chat model, reading chat_message directly — never via a committed script that would couple repo code to private data), recording only the aggregate shares in the same local file; (b2) question-ness (analysis P19): label the SAME sampled turns question / small-talk / rhetorical / anaphoric-follow-up / command / fragment, with short anaphoric follow-ups (\"and what about Brno?\") and rhetorical questions GUARANTEED in the golden set — the classes length heuristics wrongly kill — and record the measured confusion matrix the intent gate's thresholds are set from; (c) spike: run brave-spike.py with the operator key, outputs to .bench/websearch-spike/, and record the H1/H2/H3 verdicts (H1 local topics: native-primary fusion ≥ English-only; H2 global topics: fused within ε=0.1 top-5 of English-only; H3 ru/tr native pools are the thin ones) plus the per-result age/date-field verdict in the same local file. Probe: grep -rn 'KB-miss rate' .agents/memory-local/websearch-m0.md is executed OPERATOR-SIDE (nothing in the repo names a deployment, instance, or live rate — §13)."
  - "No drift in the measured path: ChatAgentTest's pre-fetch suites and ChatAgentProvenanceTest pass UNCHANGED — the counter observes the existing decision points (retrievedPostUids/clarifyTurn/preFetch empty at :604-605,:962-968) and reroutes nothing; probe: git diff over infochat-provider/src/main names exactly one hunk in ChatAgent.java (the counter seam)."
  - "mvn verify from the repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentGroundingMetricTest.java
      — preFetchOutcomeClassifiesGroundedMarginalKbmiss (the reproduction)
      and breakerOpenTurnCountsAsKbmissNotSkipped (failure-mode).
    - scripts/websearch-spike/brave-spike.py + seed-corpus.json (the
      operator-run spike vehicle; no CI execution — it needs the
      operator's key and network).
  preserves:
    - >-
      all tests currently green on main — explicitly every ChatAgentTest
      and ChatAgentProvenanceTest assertion (the counter adds an
      observation seam only).
spec_refs:
  - docs/spec/security.md §Rate limiting
  - docs/spec/commands.md §Chat mode
decision_refs:
  - D28
  - D58
---

# M1-968: Measure KB-miss rate and query-class mix (M0) + Brave spike

## Context

The web-grounding lane's budget formula (searches/mo ≈ chat turns/day ×
KB-miss rate × 2 for non-`en` scopes, ×1 for `en`) has exactly one
unknown input: the KB-miss rate — and the deterministic question-intent
gate (analysis P19, landing in M1-972) needs a MEASURED confusion
matrix, not an assumed one: skipping search on a real question is a
mild failure, firing on a non-question is pure spend and pure egress,
so the gate's bias follows that asymmetry and the evidence sizes the
trade. The lane's spec wording (M1-969) and its language rule (whether
the dual-query English arm pays for itself per locale) must likewise be
pinned from measured evidence, not free variables (the analysis's
sequencing expectation; brief point 6 and §M0). Today none of it is
measurable: the pre-fetch outcome never reaches a counter, the notice
is not persisted, and no spike vehicle exists. Shared analysis:
`analysis_ref:` (this ticket carries P5, P13, P14, P19).

## Root cause

Verified: the pre-fetch outcome at `ChatAgent.java:962-968` flows only
into the prompt block and the notice decision (`:877-888`); no metric,
counter, or D37-safe log line observes it (grep over provider main
returns no grounding metric); `persistTurn` writes only the user turn
and the sanitized assistant turn (`:855-858`), so `chat_message` cannot
be classified post hoc; `scripts/` has no spike artifact. The gap is
observability, not behavior — no production behavior changes in this
ticket.

## Pitfalls

Carried from the analysis: P5 (the counter must classify exactly the
notice path's own inputs, including the breaker-skip class), P13 (all
measurement OUTPUTS and the API key are operator-local; only
instance-free vehicles commit), P14 (only pricing/qps/SOC-2 remain
unverified — the endpoint and parameter surface is owner-live-verified
and still lands as one named constant), P19 (the question-ness label
and its confusion matrix are a DELIVERABLE of the labeling pass, with
short anaphoric follow-ups and rhetorical questions guaranteed in the
golden set — the classes length heuristics wrongly kill). Also:
same-seam care with pending M1-965/966/967 (serial landing; the
counter does not touch their logic).

## Approach

Derived from `spec_refs:` — §Rate limiting owns the volume-bound
posture the measurement feeds; §Chat mode owns the pre-fetch/notice
behavior that must not drift while being observed.

- **Files to touch:** `files_scope` (one production seam, one test
  class, two operator-run vehicle files).
- **Pre-decided shapes (implementation is execution):**
  1. Counter seam: inject `io.micrometer.core.instrument.MeterRegistry`
     (the provider's existing metric idiom — `InviteCodeConsumer`,
     `AdapterRegistry`) into `ChatAgent`; in `doHandle` step 3's
     aftermath increment ONE counter (name
     `infochat_chat_pre_fetch_outcome` or the repo's prevailing
     convention — follow the existing naming) with tag
     `outcome=grounded|marginal|kbmiss`, where the labels reuse the
     exact locals the notice path computes: `grounded =
     !retrievedPostUids.isEmpty() && !clarifyTurn-signalled-marginal`,
     `marginal = clarifyTurn`, `kbmiss` = pre-fetch EMPTY including the
     breaker-skip arm (`ChatAgent.java:584-589`, `:604-605`,
     `:962-968`). Post-M1-965 semantics: if M1-965 has landed, classify
     on its pre-fetch-outcome seam without touching its admitted-set
     logic — the counter observes the PREFETCH outcome, not the notice.
  2. `ChatAgentGroundingMetricTest` per `test_plan.adds` (a
     `SimpleMeterRegistry` plain-JUnit rig if no container is needed;
     the scripted-outcome seam may use the existing
     `ChatAgentTest.TestChatAgent` subclass idiom).
  3. `seed-corpus.json`: the seven seed queries (carried verbatim from
     the analysis's table; public-topic, instance-free), each expanded
     to its locale × mode × topic cells with binary expected-relevance
     labels; `brave-spike.py`: reads `BRAVE_API_KEY` from env, pins the
     host constant, runs the corpus, emits per-cell top-1/top-5 hit
     rates + reciprocal rank as JSON to a `--out` path; no repo-internal
     imports.
  4. The operator-run procedure of record is acceptance item 5's text —
     nothing else in the repo carries it.
- **Steps, in implementation order:** counter seam + tests RED-first →
  spike vehicle files → verify → hand the operator-run legs (rate read,
  class labeling, spike run) to the user with the local-store record
  obligation.
- **Controls to preserve (§10):** the step-3 flow reroutes NOTHING —
  the breaker gate, the shared `TurnContext`, the audit row, the
  notice decision, and the degrade paths are untouched; D37 (no user
  prose in any meter) holds by construction.
- **Pitfall→mitigation:** P5→the classification tests; P13→item 4/5
  probes; P14→the single host constant + run-time verification note;
  sibling-serialization→land before or after M1-965, never interleaved
  in-flight.

## Definition of done

The classification and breaker-skip tests pass; the counter carries no
user/scope/text dimension; the spike vehicle commits with a valid
seed corpus and the one pinned-host constant; the operator-run legs are
handed over with the `.agents/memory-local/` / `.bench/` record
obligations; every pre-existing chat suite passes unchanged; `mvn
verify` green from the repo root.

## Verification

- P5 → `ChatAgentGroundingMetricTest.preFetchOutcomeClassifiesGrounded…`
  (grounded/marginal/kbmiss discriminated; mislabeling mutation fails)
  and `…breakerOpenTurnCountsAsKbmissNotSkipped` (silent-skip mutation
  fails).
- P13 → probes in acceptance items 4-5 (valid JSON; single host
  constant; nothing committed names a deployment or a live rate).
- P14 → the constant's run-time verification note; H1/H2/H3 plus the
  age/date-field verdict recorded operator-side.
- P19 → acceptance item 5's (b2) labeling leg and golden-set
  composition requirement (the confusion matrix is a deliverable, not
  an assumption; the anaphoric/rhetorical classes are guaranteed
  present).
- acceptance item 6 → the git-diff fence probe (one ChatAgent hunk).
- FAILURE-MODE coverage → the breaker-skip drive feeds the hostile
  input (a doomed turn) and asserts the protected behavior (counted,
  not skipped silently).

## Out-of-scope

Named in `out_of_scope`: any web-search capability; persisting notices
or user-content-bearing rows; class-mix OUTPUTS in the repo (operator
local); notice/ladder/prompt changes; M1-965/966/967's seams. No
pre-existing test is modified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-968-websearch-kbmiss-measurement.md
```
