---
id: M1-396
title: "SimpleX auth-model doc reconcile: align the §6.4.1 cookie/session + AUTH_FAILED narrative, the §6.4.6/§6.12 references, and the §7.14 runbook to the shipped subprocess+WebSocket adapter, and resolve the unimplemented adapter.simplex.auth.fail metric"
status: done
created: 2026-06-16
last_updated: 2026-06-18
clarity_check:
  date: 2026-06-18
  verdict: WARN
  warnings:
    - "Item 1 (Investigation step) is not independently verifiable — it functions as an implementation instruction rather than a checkable acceptance criterion; downstream items 2–4 are the verifiable outcomes."
    - "files_scope omits the test file path that acceptance item 5 conditionally authorizes modifying; budget headroom (4 vs 3 listed) covers it numerically — add the test path to files_scope if the investigation reveals one exists."
  blockers: []
blocked_by: [M1-387]
files_budget: 6
files_scope:
  - docs/design/06-messaging.md
  - docs/design/07-deployment.md
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/metrics/AdapterMetrics.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/metrics
  - docs/plan/m1/tickets/
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The adapter config-key contract (property names) — owned by M1-387; this ticket builds on that landed shape and touches only the behavioral auth-model narrative, the metric, and the runbook.
  - Implementing session-token / cookie auth for the SimpleX transport — explicitly NOT in scope. The v1 transport dials a co-located simplex-chat subprocess over loopback IPC, which needs no auth; this ticket records that deferral decision honestly, it does not add auth. (If auth were ever wanted it would be a separate feature ticket.)
  - The adapter.identity.assert.fail counter — also registered-but-unwired, but a separate concern; left as-is.
  - The SimpleX WebSocket transient/permanent failure classification on the dial path — unchanged.
  - The Signal (§6.5) AUTH_FAILED model, its §7.14 "Re-register signal-cli" runbook, and its alert rule — Signal's account-unregistered auth failure is real and implemented; this ticket touches ONLY the SimpleX narrative and the single Signal cross-reference (§6.5, "parallel to the SimpleX session-token-revoked path in §6.4.6") that the SimpleX edit orphans.
acceptance:
  - "Investigation step (findings recorded in the commit message): determine, from SimpleXAdapter / SimpleXWebSocketClient / SimpleXSubprocess, the adapter's ACTUAL connection model. FINDINGS (verified at source 2026-06-18): the adapter spawns a local simplex-chat subprocess (SimpleXSubprocess) and speaks its loopback WebSocket bot API (SimpleXWebSocketClient); bot identity lives in data-dir (SimpleXIdentity). There is NO session/cookie/token authentication anywhere (SimpleXConfig has no such key). SimpleXWebSocketClient.onClose does NOT classify close codes into auth-vs-network buckets — any peer close → PERMANENT, supervisor restarts; onError → TRANSIENT. There is NO AUTH_FAILED terminal state for SimpleX. adapter.simplex.auth.fail (AdapterMetrics.java:206) is registered but never incremented. Conclusion: the SimpleX auth-failure model is absent AND architecturally inapplicable (loopback IPC to a co-located subprocess needs no auth), so it is removed/deferred, not reserved."
  - "docs/design/06-messaging.md §6.4.1 'Authentication' line and the 'Auth-failure distinction' paragraph are rewritten to the subprocess+loopback-WebSocket reality: no cookie/session-token authentication; bot identity is the data-dir; remove the WebSocket-close-code auth-vs-network classification and the 3-consecutive-auth-failure → terminal AUTH_FAILED claim."
  - "docs/design/06-messaging.md §6.4.6/§6.4.7 record that session-token auth and the terminal AUTH_FAILED classification are DEFERRED for the v1 loopback-IPC SimpleX transport (a local subprocess on loopback needs no auth), removing any implication that the shipped code implements them. The section is NOT restructured beyond this; the §6.4.7 failure-surfaces 'Session token revoked / invalid (401)' row is reconciled to the deferral. The single §6.5 Signal cross-reference that this edit orphans ('parallel to the SimpleX session-token-revoked path in §6.4.6') is minimally fixed; the Signal AUTH_FAILED model itself is untouched."
  - "docs/design/06-messaging.md §6.12 observability catalogue no longer lists adapter.simplex.auth.fail (consistent with the registration removal below)."
  - "docs/design/07-deployment.md §7.14 'Rotate a SimpleX session token' runbook and the §7.x adapter.simplex.auth.fail / SimpleX session-token references are removed or rewritten to the subprocess identity-in-data-dir reality. The Signal AUTH_FAILED runbook and alert in the same file are NOT touched."
  - "The unwired adapter.simplex.auth.fail counter registration in AdapterMetrics.bindAdapter (AdapterMetrics.java:206) is removed, along with the SIMPLEX_NAME gate constant if removing the registration leaves it unused, and the 'registered-but-unwired' javadoc clause that references it (the catalogue <li> at ~28, the §53-63 paragraph, and the ~159 constant javadoc) — so the catalogue no longer advertises a counter no production path can increment. A brief auth-deferral rationale (loopback IPC needs no auth) is recorded at SimpleXAdapter."
  - "The metrics test (infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/metrics — AdapterMetricsTest.simplexAuthFailCounterIsRegisteredOnlyForTheSimplexAdapter) no longer expects adapter.simplex.auth.fail in the catalogue and the suite stays green; the change is justified in the commit message."
  - "mvn -B verify from the repo root exits 0; no test that pins the current auth.fail metric registration is weakened (the auth.fail test is removed/updated to match the resolution, justified in the commit message)."
  - "Ticket housekeeping: docs/plan/m1/tickets/M1-372-*.md (the originating deep-review v7 F1 ticket whose scope this ticket absorbs) is set status: deferred with deferred_reason: superseded-by-M1-396; STATUS.md is regenerated."
test_plan:
  adds: []
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/metrics (drop the auth.fail catalogue expectation)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/design/06-messaging.md §6.4 SimpleX Chat adapter
  - docs/design/06-messaging.md §6.12 Observability
  - docs/design/07-deployment.md §7.14 Operator runbook
decision_refs:
  - D46
supersedes: M1-372
reviews:
  - round: 1
    date: 2026-06-18
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 116
      removed: 78
escalations:
  - date: 2026-06-18
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — investigation (acceptance item 1) revealed M1-396 overlaps the
      pending originating ticket M1-372 (deep-review v7 F1) on the auth.fail
      metric removal, the metrics test, and §6.4.6. User chose (2026-06-18) to
      consolidate the work into M1-396 and supersede M1-372. Consolidation
      adds SimpleXAdapter.java, the metrics test dir, and the tickets dir to
      files_scope (M1-372's code+test scope plus the M1-372 closure), exceeding
      the original files_budget: 4 — hence budget-breach → refine.
revisions:
  - date: 2026-06-18
    reason: "Consolidate M1-372 (deep-review v7 F1) scope into M1-396 and supersede it (user decision 2026-06-18). Pre-refine frontmatter: files_budget 4; files_scope [06-messaging.md, 07-deployment.md, AdapterMetrics.java]; security_relevant unset (false); 5 acceptance items (metric resolution framed remove-OR-reserve); out_of_scope 2 items. Resolves clarity WARN #2 (test path now in files_scope)."
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-18
    verdict: CLEAN
    base: b448ab3e
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-396-2026-06-18.md
    out_of_model_count: 1
    note: |
      In-progress (--in-progress) pre-commit audit of the in-review branch tip
      plus uncommitted working-tree changes. Doc-reconcile + dead-metric removal
      (adapter.simplex.auth.fail). CLEAN: no auth/authz/ban/audit property
      weakened. One out-of-model advisory recorded in the verdict file; advisory
      only, feeds nothing mandatory.
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
the code actually does.

## Consolidation with M1-372 (user decision 2026-06-18)

The investigation (acceptance item 1) confirmed the auth-failure model is
**absent and architecturally inapplicable**: SimpleX dials a co-located
`simplex-chat` subprocess over **loopback IPC**, which needs no authentication;
there is no session token, no close-code auth classification, and no
`AUTH_FAILED` state. The metric is therefore **removed**, not reserved.

That same conclusion is the subject of the pre-existing ticket **M1-372**
("align the SimpleX auth-failure design note with the loopback-trusted v1
transport and drop the dead auth.fail meter"), which traces to **deep-review v7
finding F1**. M1-396 (surfaced independently from M1-387) overlaps M1-372 on the
metric removal, the metrics test, and §6.4.6, and is broader on docs (§6.4.1,
§6.12, §7.14/07-deployment) that M1-372 did not cover. To avoid two conflicting
commits and an intermediate internally-inconsistent design doc, the user chose
to **consolidate the work into M1-396** and supersede M1-372:

- M1-396 absorbs M1-372's `files_scope` (`SimpleXAdapter.java`, the metrics test
  dir) and adopts M1-372's **DEFER framing** for §6.4.6/§6.4.7 (record auth is
  deferred for loopback-IPC, do not just delete the narrative) and its
  `security_relevant: true` flag.
- M1-372 is set `status: deferred`, `deferred_reason: superseded-by-M1-396`
  (the tickets dir is in `files_scope` for this housekeeping; see acceptance).

## Acceptance / Out-of-scope

See frontmatter.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-396-*.md
```
