---
id: M1-372
title: "messaging: align the SimpleX auth-failure design note with the loopback-trusted v1 transport and drop the dead auth.fail meter"
status: deferred
created: 2026-06-14
last_updated: 2026-06-18
deferred_on: [M1-396]
deferred_reason: superseded-by-M1-396
blocked_by: []
files_budget: 5
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/metrics/AdapterMetrics.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/metrics
  - docs/design/06-messaging.md
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The adapter.identity.assert.fail counter (also registered-but-unwired) — separate concern, left as-is unless trivially co-located; this ticket is the auth.fail / §6.4.6 auth path only.
  - Implementing session-token auth itself — explicitly NOT in scope; v1 dials a local subprocess over loopback IPC, which needs no auth. This ticket records that decision honestly, it does not add auth.
  - The SimpleX WebSocket transient/permanent failure classification on the dial path — unchanged.
acceptance:
  - "docs/design/06-messaging.md §6.4.6/§6.4.7 records that session-token auth and the terminal AUTH_FAILED classification are DEFERRED for the v1 loopback-IPC SimpleX transport (a local subprocess on loopback needs no auth), removing any implication that the shipped code implements them."
  - "The unwired adapter.simplex.auth.fail counter registration in AdapterMetrics.bindAdapter and its 'registered-but-unwired' javadoc clause are removed, so the metrics catalogue no longer advertises a counter that no production path can increment. The auth-deferral rationale lives at SimpleXAdapter and the design note."
  - "The metrics test in infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/metrics no longer expects adapter.simplex.auth.fail in the catalogue and stays green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/metrics (drop auth.fail catalogue expectation)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-372: SimpleX auth design/metrics alignment

> **SUPERSEDED (2026-06-18) — deferred onto M1-396.** This ticket's scope (drop
> the dead `adapter.simplex.auth.fail` meter, update the metrics test, reframe
> §6.4.6/§6.4.7 as auth-deferred for the loopback-IPC transport) was absorbed
> into **M1-396**, which independently surfaced the same drift from M1-387 and
> is broader (it also reconciles §6.4.1, §6.12, and the §7.14/07-deployment
> runbook). M1-396 carries forward this ticket's `security_relevant: true` flag
> and DEFER framing. No work is lost; this ticket stays deferred for the audit
> trail rather than being reopened.

## Context

Deep-review v7 (opus-48) messaging-adapter finding **F1**. Verified at source
2026-06-14 — **spec/metrics drift, not a security hole**:

`SimpleXWebSocketClient.start` (`.../impl/simplex/SimpleXWebSocketClient.java:186-209`)
dials the WebSocket to the local `simplex-chat` subprocess over loopback with no
authentication, and `AdapterMetrics` (`.../metrics/AdapterMetrics.java:53-63`)
registers `adapter.simplex.auth.fail` while its own javadoc states the §6.4.6
session-token auth classification "is not implemented in the SimpleX transport."
Design §6.4.6/§6.4.7 describes a full auth-failure path (session-token auth, 401
classification, terminal AUTH_FAILED, admin notify, the counter) that the code
does not implement.

Because the v1 transport is loopback IPC to a co-located subprocess, **no auth is
required and none is a defect** — the gap is three artifacts (design note, metrics
catalogue, code) disagreeing about whether auth handling exists. The fix aligns
the design note to the loopback-trusted reality and removes the dead meter
(reviewer Option A). No functional/security behavior changes.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- This touches a design doc and code together, so it is a ticket (not a `spec:`
  commit). Keep the design edit to the §6.4.6/§6.4.7 deferral statement; do not
  restructure the section.
