---
id: M1-221
title: "LLM retry-once backoff: sleep before the single retry (M1-192 redteam F2)"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
clarity_check: {}
blocked_by: []
remediates: M1-192
files_budget: 10
files_scope: []
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - the retry COUNT (stays at exactly one retry per docs/spec/security.md §Failure handling — this ticket changes only the timing of that retry)
  - the stage-specific failure paths themselves (INFRA_FAILURE → READY-with-Stage-1 / quarantine posture) — unchanged
  - provider-side LLM consumers (SummaryProseGenerator, ChatAgent, LlmTranslationProvider) — they have no retry-once machinery today; adding retries there is a different ticket
  - the adapter's exception types and LlmHttpSupport IF the fixed-sleep outcome is chosen (only the header-honoring outcome may touch the adapter)
acceptance:
  - "Decision recorded (in the ticket body before implementation, flagged in the commit message): the sleep is either (a) Retry-After-honoring — the adapter exceptions regain a CLAMPED retryAfterMs (ceiling ≤ 30s, hostile/overflow header values clamped, the M1-192 audit's unclamped-parse defect must not return) and the workers sleep on it when present, falling back to the fixed delay when absent — or (b) a fixed/jittered delay with no adapter changes"
  - "Stage2Worker's single retry no longer fires immediately: a named test asserts a measurable configurable delay (default on the order of ~2s, test-tuned smaller) between attempt 1 and attempt 2 on a rate-limited (429/503-shaped) failure"
  - "TaggerWorker and EntityExtractorWorker apply the same sleep-before-retry shape where their retry-once paths re-invoke the LLM, each pinned by a named test (their non-LLM retry shapes — schema-garbage / zero-valid — are out of band and unchanged)"
  - "The sleep does not hold a DB transaction or pinned platform thread open: the delay runs on the worker's virtual thread outside any transactional boundary, asserted by test or by citing the existing worker structure in the implementation notes"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test (worker backoff tests)
  modifies: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Failure handling
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
escalations: []
revisions: []
---

# M1-221: LLM retry-once backoff: sleep before the single retry (M1-192 redteam F2)

## Context

M1-192's redteam audit (F2, DOS/low —
`docs/plan/m1/redteam/M1-192-2026-06-07.md`): the spec mandates "retry
once, then apply the stage-specific failure path"
(docs/spec/security.md §Failure handling), and the collector workers
implement that retry as an IMMEDIATE re-invocation
(`Stage2Worker.invokeWithRetryOnce`, plus the analogous retry-once
paths in TaggerWorker and EntityExtractorWorker). Against a
rate-limited endpoint (429/503, possibly advising `Retry-After: 2`),
the immediate second attempt is near-certain to fail milliseconds after
the first, converting every rate-limit burst into the degraded
failure path (Stage 2: INFRA_FAILURE → READY with Stage 1 redactions
only on the default profile) even though waiting ~2 s would have
produced a healthy verdict. An adversary who can correlate feed
publishing with provider rate-limit windows gets content judged by
Stage 1 alone more often than the retry-once design intends.

M1-192 deleted the adapter's dead, unclamped Retry-After machinery
(the authorized deletion branch; it had zero consumers). This ticket
adds the consumer side properly — designed as one piece, with the
worker files in scope.

## Design space (the acceptance item 1 decision)

- **(a) Header-honoring**: re-introduce `retryAfterMs` on
  `LlmCallFailedException` (clamped at parse time, ceiling ≤ 30s);
  workers sleep on it when present. Precise, but re-adds adapter
  plumbing across LlmHttpSupport + providers and their tests.
- **(b) Fixed/jittered delay**: workers sleep a configurable ~2 s
  (jittered) before the single retry, regardless of what the endpoint
  advised. Zero adapter changes; captures most of the value since
  rate-limit bursts clear in seconds.

Either outcome satisfies the spec (which mandates the retry count,
not its timing). Pick one and say why in the commit message.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- files_budget 10 accounting (worst case, outcome (a)): 3 workers +
  2 exception types + LlmHttpSupport + 2 provider impls = 8 production
  files + worker tests; outcome (b) is ~3 production files + tests.
- security_relevant: true — the finding is a DOS-shaped degradation of
  the Stage 2 security boundary's effective coverage window.
- The M1-192 audit's out-of-model items (local-only guard TOCTOU vs
  per-call config reads; wildcard-bind test harness) are advisory and
  NOT part of this ticket.
