---
id: M1-278
title: "Ops posture: health truth, endpoint gating, drop counters"
status: pending
created: 2026-06-09
last_updated: 2026-06-10
blocked_by: []
files_budget: 16
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/health
  - infochat-provider/src/test/java/app/zcat/infochat/provider/health
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage2
  - docker-compose.yml
  - docs/design/07-deployment.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - Flipping the base-profile release-on-stage2-failure default — a product decision per the report, not a bug fix; only visibility (docs + metric) is in scope.
  - Mid-session disconnect → readiness flow beyond the terminal-FAILED case (AdapterConnectionState's startup-only scope is a documented decision; only terminal supervisor failure is promoted).
  - The drop-newest queue policy itself — bounded queues are correct; only surfacing changes.
acceptance:
  - "A messaging subprocess supervisor reaching its terminal FAILED state feeds readiness: an adapter whose supervisor is FAILED reports DOWN on the readiness endpoint; named test."
  - "Operator documentation (deployment design note) gains explicit loopback-bind guidance for the health endpoints, addressing the adapter-name enumeration concern for unauthenticated callers."
  - "Signal endpoint configuration rejects non-loopback hosts unless an explicit opt-in property is set (parseEndpoint today validates shape/port only under a loopback trust model); named test for reject and for opt-in."
  - "Inbound queue drop-newest events are observable: a counter (metrics or status surface) increments on drop; named test."
  - "The Stage-2 fail-open posture (release-on-stage2-failure=true in base/laptop/pi) is visible to operators: documented in the deployment design note, and a metric counts posts released with stage2_failed=true (the startup audit row already exists); named test that the counter increments when release-on-stage2-failure=true and the infra-failure release path runs."
  - "docker-compose binds Postgres to 127.0.0.1 (no bare 5432:5432 publish)."
  - "The SignalConfig/SimpleXConfig eager @Startup beans in the library jar are gated so a future CDI-index addition cannot activate them in a deployment that hasn't enabled the adapter (dormant-activation footgun); named test or config-level proof."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/health
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage2
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
revisions:
  - date: 2026-06-10
    reason: clarity-fail refine (files_scope omitted the collector module that item 5's stage2_failed counter requires; item 5 lacked a named-test requirement)
    snapshot: |
      Pre-refine files_budget: 14.
      Pre-refine files_scope had no collector path — six entries only:
        infochat-provider/src/main/java/app/zcat/infochat/provider/health
        infochat-provider/src/test/java/app/zcat/infochat/provider/health
        infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging
        infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
        docker-compose.yml
        docs/design/07-deployment.md
      Pre-refine acceptance item 5 verbatim (no named-test requirement):
        "The Stage-2 fail-open posture (release-on-stage2-failure=true in
         base/laptop/pi) is visible to operators: documented in the deployment
         design note, and a metric counts posts released with stage2_failed=true
         (the startup audit row already exists)."
      All other frontmatter fields unchanged by the refine.
escalations:
  - date: 2026-06-10
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      N/A
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-278: Ops posture: health truth, endpoint gating, drop counters

## Context

Deep-review v4 verified mediums **M-P11**, **M-M7**, **M-M9**, **M-K8**
(remaining slice) and the docker-compose low
(`deep-code-review/v4/UNIFIED-REPORT.md` §2/§3; sources
`deep-code-review/v4/gpt-55/report.md` M-02/M-03/M-07/M-08/M-01/L-01/L-07):

- **M-P11:** readiness records startup result only; Signal/SimpleX
  subprocesses have terminal FAILED states that never reach readiness — a
  deployment can be "ready" with a permanently dead adapter. The readiness
  body also enumerates adapter names; the report's cut is loopback-bind
  guidance rather than auth.
- **M-M7:** the Signal endpoint accepts arbitrary hosts under a loopback
  trust model — `parseEndpoint` validates shape/port-range only.
- **M-M9:** inbound queues drop-newest with weak operational surfacing —
  the design trade-off stands; the ticket-worthy slice is drop counters.
- **M-K8:** Stage-2 infra-failure fail-open in base/laptop/pi is a
  documented, audited posture decision (startup audit row exists); the
  remaining slice is operator-doc visibility and a released-post metric.
- Lows: compose publishes `5432:5432`; `SignalConfig`/`SimpleXConfig` are
  eager `@Startup` beans in an unindexed library jar — dormant until someone
  indexes the jar, then they activate unconditionally.

## Acceptance

See frontmatter. Everything here is posture/observability; no
message-handling behavior changes.

## Out-of-scope

See frontmatter — two explicit product-decision boundaries (fail-open
default, mid-session readiness) the implementer must not cross.

## Notes

- Check what metrics infrastructure already exists (Micrometer via Quarkus?)
  before adding counters; if none is wired, the status surface
  (provider_state or the health payload) is the no-new-dependency
  alternative — a new dependency would need explicit approval first.
- The compose change affects local dev only; the default-password fallback
  noted by gpt-55 is acceptable for a loopback-bound dev DB — binding is the
  fix, not credential rotation.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-278-*.md
```
