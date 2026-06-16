---
id: M1-396
title: "SimpleX auth-model doc reconcile: align the §6.4.1 cookie/session + AUTH_FAILED narrative, the §6.4.6/§6.12 references, and the §7.14 runbook to the shipped subprocess+WebSocket adapter, and resolve the unimplemented adapter.simplex.auth.fail metric"
status: pending
created: 2026-06-16
last_updated: 2026-06-16
blocked_by: [M1-387]
files_budget: 4
files_scope:
  - docs/design/06-messaging.md
  - docs/design/07-deployment.md
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/metrics/AdapterMetrics.java
complexity: medium
risk: medium
out_of_scope:
  - The adapter config-key contract (property names) — owned by M1-387; this ticket builds on that landed shape and touches only the behavioral auth-model narrative, the metric, and the runbook.
  - Building real SimpleX auth-failure detection into SimpleXWebSocketClient if it turns out absent — if the investigation shows the auth-failure/AUTH_FAILED behavior is genuinely unimplemented AND wanted, that is a feature ticket, not this doc-reconcile. This ticket either documents the model the code actually implements or removes/marks-reserved the documentation of behavior the code does not implement.
acceptance:
  - "Investigation step (record findings in the commit message): determine, from SimpleXAdapter / SimpleXWebSocketClient / SimpleXSubprocess, the adapter's ACTUAL connection model (it spawns a local simplex-chat subprocess and speaks its loopback WebSocket bot API; bot identity lives in data-dir), whether it performs ANY session/cookie/token authentication, whether it classifies WebSocket close codes into auth-vs-network buckets, and whether it has an AUTH_FAILED terminal state. Each subsequent acceptance item is reconciled to those findings, not to the current (suspected-stale) prose."
  - "docs/design/06-messaging.md §6.4.1 'Authentication' line and 'Auth-failure distinction' paragraph, and the §6.4.6 'Auth-failure policy' paragraph (which cite §6.4.6 reconnection and §6.12 metrics), are reconciled to the actual model: the cookie/session-token authentication description and any AUTH_FAILED behavior the code does not implement are removed or rewritten to match the subprocess+loopback-WebSocket reality."
  - "docs/design/07-deployment.md §7.14 'Rotate a SimpleX session token' runbook and the §7.x metric/troubleshooting references to adapter.simplex.auth.fail / session-token rotation are reconciled to the actual model (removed or rewritten to the subprocess identity-in-data-dir reality)."
  - "The registered-but-unimplemented adapter.simplex.auth.fail metric (AdapterMetrics.java:206; the :61 docstring states its count 'is not implemented in the adapter') is resolved: either the registration is removed (if the auth-failure model is confirmed absent and unwanted) or its docstring is changed from 'not implemented' to an explicit 'reserved, wired by <ticket>' with the wiring tracked. The AdapterMetrics docstring (lines ~28, ~55, ~61, ~159) is updated to match whichever resolution is chosen."
  - "mvn -B verify from the repo root exits 0; no test that pins the current auth.fail metric registration is weakened (if such a test exists, it is updated to match the resolution and the change is justified in the commit message)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/06-messaging.md §6.4 SimpleX Chat adapter
  - docs/design/06-messaging.md §6.12 Observability
  - docs/design/07-deployment.md §7.14 Operator runbook
decision_refs:
  - D46
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-396: SimpleX auth-model doc reconcile + auth.fail metric resolution

## Context

Surfaced during M1-387 (adapter config-key contract reconcile). M1-387 fixes
the adapter *configuration* keys across the wizard and design docs, aligning
them to the shipped `SimpleXConfig` / `ProductionAdapterBeans` /
`SignalConfig`. While doing so it found that the SimpleX *behavioral* narrative
in the design docs describes a connection/auth model the shipped adapter does
not appear to implement:

- `06-messaging.md` §6.4.1 documents "cookie-based session authentication against
  the local simplex-cli" configured via `infochat.adapters.simplex.session-token`,
  a WebSocket-close-code classification into auth-vs-network failures, and a 3-
  consecutive-auth-failure → terminal `state=AUTH_FAILED`; §6.4.6 adds the
  auth-failure reconnection policy and session-token rotation (referencing the
  §6.12 metrics).
- `07-deployment.md` §7.14 has a "Rotate a SimpleX session token" runbook and
  references the `adapter.simplex.auth.fail` metric.
- The shipped `SimpleXAdapter` instead spawns a local `simplex-chat`
  **subprocess** (`SimpleXSubprocess`) and speaks its **loopback WebSocket** bot
  API (`SimpleXWebSocketClient`); bot identity lives in `data-dir`. A grep of
  `SimpleXAdapter` finds no cookie/session/token/auth concept.
- `AdapterMetrics.java:206` registers `adapter.simplex.auth.fail`, but the
  class docstring (`:61`) states "what `adapter.simplex.auth.fail` counts is
  not implemented in the adapter" — a registered-but-never-incremented stub.

M1-387 deliberately scoped this out because (a) reconciling a behavioral
narrative is a different, judgment-laden task than a deterministic key rename,
and (b) resolving the metric stub may require a **Java change**, which M1-387's
`out_of_scope` forbids.

**This ticket's first job is to verify the actual model** (the grep above is a
hint, not proof — `SimpleXWebSocketClient` may classify close codes even if
`SimpleXAdapter` does not), then reconcile the docs and the metric to whatever
the code actually does. If the auth-failure model is genuinely absent and
unwanted, the docs and the metric stub are removed; if absent but wanted, that
is a separate feature ticket and the docs are marked accordingly.

## Acceptance / Out-of-scope

See frontmatter.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-396-*.md
```
