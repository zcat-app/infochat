---
id: M1-302
title: "Ops posture: Stage-2 fail-open default, readiness topology exposure (decisions)"
status: pending
created: 2026-06-11
last_updated: 2026-06-11
blocked_by: []
files_budget: 8
files_scope:
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/java/app/zcat/infochat/provider/health
  - docs/spec/deployment.md
  - docs/design/07-deployment.md
  - infochat-provider/src/test/java/app/zcat/infochat/provider/health
  - infochat-collector/src/test/java/app/zcat/infochat/collector
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The Stage-2 judge itself and the releasedStage2FailedCount counter mechanics — only its default policy and operator visibility.
  - Auth on the health endpoint — v1 keeps it unauthenticated; binding guidance + payload trimming are the levers.
acceptance:
  - "U-15 decided: the release-on-stage2-failure=true (fail-open) default in base/laptop/pi profiles is either (a) kept, with the trade-off documented in deployment docs and releasedStage2FailedCount surfaced to operators (log line or health payload) so fail-open releases are visible, or (b) flipped to fail-closed with explicit per-profile opt-in documented; one of the two, recorded with rationale; a named test pins whichever default ships."
  - "U-16 addressed: docs gain explicit binding guidance for the health port (localhost/private-interface bind; the readiness payload enumerates adapter topology, which is reconnaissance data if the port is externally reachable), and the unauthenticated readiness payload either drops adapter names or the doc records why they stay; a named test pins the shipped payload shape."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/health
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

# M1-302: Ops posture: Stage-2 fail-open default, readiness topology exposure (decisions)

## Context

Deep-review v5 carried **U-15** (policy decision, unique gpt-55 #M-04) and
**U-16** (LOW, deployment-conditional, unique gpt-55 #M-18)
(`deep-code-review/v5/UNIFIED-REPORT.md` §3 — gitignored; all load-bearing
facts inlined):

- U-15: base/laptop/pi profiles ship `release-on-stage2-failure=true` —
  posts whose Stage-2 security judgement failed are released fail-open.
  Not a code defect; a policy default worth an explicit decision.
- U-16: the readiness payload exposes adapter topology when the health
  port is reachable beyond the host. The unified report downgraded this to
  deployment-conditional — the fix is doc/binding guidance, possibly
  payload trimming.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- **⚠ U-15 is a user decision at start.** Default if the user just says
  "go": option (a) — keep fail-open, document, surface the counter. Flipping
  to fail-closed changes ingest behaviour during LLM outages (posts held,
  queues grow) and shouldn't ride in silently. M1-278 (ops posture) is the
  precedent ticket shape for this kind of item.
- gpt-55's recommendation was fail-closed; the report deliberately
  reframed it as a decision item, not a defect — don't treat (b) as the
  "correct" answer in review.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-302-*.md
```
