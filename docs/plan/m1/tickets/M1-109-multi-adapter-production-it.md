---
id: M1-109
title: "Multi-adapter production shape IT"
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by:
  - M1-108
  - M1-105
files_budget: 4
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/MultiAdapterProductionIT.java
  - infochat-provider/src/test/resources/application.properties
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-core/** — no SPI changes
  - infochat-collector/** — no collector changes
  - any change to SimpleXAdapter or SignalAdapter internals — frozen
  - any change to InMemoryAdapter — unchanged
  - any change to MessagingAdapter SPI — not modified
  - any change to AdapterRegistry — M1-105 is frozen
acceptance:
  - "MultiAdapterProductionIT runs with SimpleX + Signal adapters simultaneously enabled (using test doubles for the subprocesses)"
  - "Cross-adapter blast radius: a SimpleX adapter failure (subprocess crash) does not affect the Signal adapter — messages on Signal continue flowing"
  - "Cross-adapter blast radius: a Signal adapter failure does not affect SimpleX"
  - "(adapter, contact_id) isolation across SimpleX and Signal: a SimpleX user and a Signal user with coincidentally identical contact_id strings are distinct users with independent state"
  - "Last-admin protection is global across adapters: cannot leave zero admins even when revoking from one adapter while the other has no admins"
  - "/grant-admin on SimpleX does not elevate on Signal and vice versa"
  - "MultiAdapterProductionIT.simpleXCrashDoesNotAffectSignal passes — SimpleX subprocess crashes; Signal messages continue flowing"
  - "MultiAdapterProductionIT.signalCrashDoesNotAffectSimpleX passes — Signal subprocess crashes; SimpleX messages continue flowing"
  - "MultiAdapterProductionIT.crossAdapterIsolation passes — same contact_id on both adapters produces distinct user rows"
  - "MultiAdapterProductionIT.lastAdminGlobalAcrossAdapters passes — revoking the sole admin on SimpleX is blocked when Signal also has zero admins"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/MultiAdapterProductionIT.java
  preserves:
    - all tests currently green on main
    - MultiAdapterIsolationIT from M1-105 passes unchanged
spec_refs:
  - docs/spec/messaging.md §Per-adapter trust level and identity
  - docs/spec/security.md §Per-adapter admin threat profile
decision_refs:
  - D46
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-109: Multi-adapter production shape IT

## Context

The final v1 integration test: SimpleX + Signal running simultaneously
in the same Provider. Proves the D46 multi-adapter commitment with
both production adapters, not just SimpleX + InMemory (M1-105).

`security_relevant: true` — cross-adapter blast radius and admin
isolation are security-load-bearing.

## Acceptance

See frontmatter. This is a test-only ticket — no production code
changes.

## Out-of-scope

- Adapter internals — all adapter tickets are frozen.
- AdapterRegistry — M1-105 is frozen.
- InMemoryAdapter — this IT exercises production adapters only.

## Notes

- **Test doubles for subprocesses.** The IT uses FakeSimpleXProcess
  (from M1-103) and FakeSignalCli (from M1-107) to avoid needing
  real adapter binaries in CI. The test doubles speak the correct
  protocol subsets to satisfy the adapters' startup and messaging
  paths.
- **Blast radius shape.** The IT forces one adapter's subprocess to
  crash (kill the fake process) and verifies the other adapter
  continues accepting messages. This is the D46 "at-least-one-up
  readiness" commitment under failure.
- **Adjacent code:** M1-105's MultiAdapterIsolationIT tests
  SimpleX + InMemory. This IT tests SimpleX + Signal — the
  production deployment shape.
- **D47 impact.** If D47 tickets (M1-110..M1-114) have landed before
  this ticket runs, group @mentions routed through InboundRouter hit
  the approval gate at step 3.5. The IT must pre-approve any test
  groups (INSERT a groups row with approval_status='approved') in
  test setup so the blast-radius and isolation tests reach command
  dispatch. The acceptance criteria themselves are unchanged — they
  test cross-adapter blast radius, identity isolation, and last-admin
  protection, none of which are affected by D47.
