---
id: M1-496
title: "Test-integrity sweep: vacuous, ambient-gated, and over-permissive assertions"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 8
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Changing the production code under test; this ticket tightens assertions only (driven by deterministic stubs where needed)."
acceptance:
  - >-
    Each assertion below is tightened to an unconditional, exact check that fails
    when its named contract regresses: (20#F1) the no-op assertNotifyEmitted
    helper actually verifies the NOTIFY payload via a listener, and the
    re-eval/admin-review NOTIFY tests assert the emission, not just the status
    transition (ReEvaluationJobTest.java:625-634; AdminReviewTtlJobTest.java:86-103);
    (29#F3) failureHoldsAtCeiling's DB assertion no longer sits behind an
    LLM-unavailability if-branch — it runs unconditionally via a deterministic
    stub (AutoCompressTriggerTest.java:99-129); (30#F1) the /lang group-scope test
    asserts on ITS OWN scope rows, not a global scope_preferences count
    (LangCommandHandlerTest.java:216-238); (30#F2) the compress-failure test
    asserts deterministically via a failing LLM stub instead of vacuously when
    the LLM is reachable (CompressCommandHandlerTest.java:116-152); (33#F1) the
    export rate-limit IT asserts the exact expected count, not <= 2
    (InboundRouterExportIT.java:62-69); (33#F2) the probation-blocked reply is
    asserted via the bundle key, not English substring fragments
    (InboundRouterChatModeIT.java:182-185); (36#F2) the redirect-stall test
    asserts the single expected reason (BODY_READ_TIMEOUT), not a disjunction
    admitting the unreachable BODY_READ_DEADLINE_EXCEEDED
    (SsrfGuardedHttpClientTest.java:1148-1194).
  - "mvn -B verify is green from the repo root."
test_plan:
  modifies:
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobTest.java — assertNotifyEmitted made real; NOTIFY emission asserted."
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/AdminReviewTtlJobTest.java — NOTIFY emission asserted, not just status."
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/chat/AutoCompressTriggerTest.java — DB assertion made unconditional via deterministic stub."
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/LangCommandHandlerTest.java — assert own-scope rows, not global count."
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/CompressCommandHandlerTest.java — assert via failing LLM stub, not vacuously."
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterExportIT.java — exact-count assertion."
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterChatModeIT.java — assert via bundle key."
    - "infochat-ssrf/src/test/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClientTest.java — assert single expected reason."
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-496: Test-integrity sweep: vacuous, ambient-gated, and over-permissive assertions

## Context

From `/deep-code-review full` (2026-06-27), cross-cutting theme **CT5** —
reports `20#F1`, `29#F3`, `30#F1`, `30#F2`, `33#F1`, `33#F2`, `36#F2` (verified at
source). Each is an assertion that cannot fail for the reason its test name
advertises: a no-op helper, a branch gated on whether an LLM is reachable, a
global-count assertion, an inequality where the outcome is deterministic, or a
disjunction admitting an outcome unreachable under the test's own config. A real
regression in the named contract would still pass green.

## Acceptance

See frontmatter — tighten each to an unconditional exact assertion driven by a
deterministic stub. No production-code change.

## Out-of-scope

See frontmatter. These are pre-existing tests modified deliberately (authorized
here per engineering-rules §8); each named with its new stricter behavior.

## Notes

- Source: `/deep-code-review full` (2026-06-27), CT5.
- 30#F2 already has a deterministic sibling (`llmFailureDeletesNothing...`) — the
  vacuous variant should match its rigor.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-496-*.md
```
