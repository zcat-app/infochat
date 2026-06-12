---
id: M1-319
title: "Derive Signal bot ACI from signal-cli identity store"
status: done
created: 2026-06-12
last_updated: 2026-06-12
blocked_by: []
files_budget: 24
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
outline_file: target/m1-tick-outline-M1-319.md
out_of_scope:
  - SimpleX derivation — that is M1-320.
  - The D10 mention-comparison logic itself (SignalGroupHandler's ACI
    comparison) — only where the anchor VALUE originates changes here.
  - Bot-id well-formedness strengthening (M1-294 U-35) — the existing
    SignalIdentity.isWellFormed is reused as-is on the derived value.
  - Admin (bootstrap) contact id provenance — infochat.adapters.signal.admin
    stays an operator property per deployment.md §Operator inputs item 2.
  - getUserStatus-based derivation over JSON-RPC — considered alternative,
    recorded in §Notes; not implemented.
acceptance:
  - "SignalAdapter derives its bot ACI at start() from the signal-cli
    account's identity store (the account data file under the configured
    data-dir + account), NOT from infochat.adapters.signal.bot-aci; that
    property and its ProductionAdapterBeans injection are removed. The
    derived value is canonicalized to lowercase and validated via the
    existing SignalIdentity.isWellFormed; a missing/unreadable/malformed
    store fails THAT adapter's start() only (per-adapter resilience
    preserved). Named test: SignalAdapterIdentityDerivationTest
    .startDerivesBotAciFromIdentityStore."
  - "Decoupling invariant: admin-key rotation cannot move the bot's D10
    anchor — derivation reads only the bot's own identity store; no
    .admin-sourced value participates. Named test:
    SignalAdapterIdentityDerivationTest.derivedAnchorIndependentOfAdminConfig
    (differing infochat.adapters.signal.admin value, anchor unchanged)."
  - "The derived ACI feeds the D10 anchor: groupHandler() builds
    SignalGroupHandler with the derived value. Named test (or assertion
    within the derivation test) pins that the post-start() group-handler
    anchor equals the identity-store value."
  - "docs/spec/deployment.md §Operator inputs item 7 states that the
    Signal bot contact id is derived from the adapter's own identity
    store at adapter startup and is not an operator-typed property, while
    the SimpleX bot contact id remains operator-configured with derivation
    as planned hardening; docs/spec/security.md §Per-adapter admin threat
    profile 'Operator-typed bot-identity anchor' note is narrowed to
    SimpleX-only. docs/design/06-messaging.md §6.5.4/§6.5.5 are aligned to
    the as-built mechanism (account file under .data-dir; no identity-dir
    property exists in code)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalAdapterIdentityDerivationTest.java
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalAdapterStartFailureTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalAdapterSkeletonTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalInboundDispatchTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalReconnectTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandlerTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupEndToEndTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalMembershipAciGateTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/MembershipDispatchShapeTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalStartRaceTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/AdapterCapabilityContractTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/AdapterLifecycleContractTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/ContactIdWellFormednessTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/MultiAdapterProductionIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/ProductionAdapterActivationTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/BootstrapAdminParseGateTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/deployment.md §Operator inputs
  - docs/spec/security.md §Per-adapter admin threat profile
decision_refs: []
decomposed_from: M1-318
reviews:
  - round: 1
    date: 2026-06-12
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 22
      added: 519
      removed: 135
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-12
    verdict: CLEAN
    base: de70d149a502a84df3c0dbb884bc9ca1eb309ce1
    head: working tree of m1/M1-319-derive-signal-bot-aci-from-sig (pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-319-2026-06-12.md
    out_of_model_count: 2
    note: |
      Pre-commit audit after round-1 APPROVE. CLEAN — the derivation
      diff delivers the security.md §Per-adapter admin threat profile
      narrowing it claims (Signal anchor no longer operator-typed).
      Two advisory out-of-model observations recorded in the verdict
      file; no remediation tickets spawned.
clarity_check:
  date: 2026-06-12
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-319: Derive Signal bot ACI from signal-cli identity store

## Context

Decomposed from M1-318 (budget-breach). The Signal half of the
bot-contact-id derivation: replace the operator-typed
`infochat.adapters.signal.bot-aci` property with a value the adapter
derives from the signal-cli account it already runs against. M1-318's
feasibility investigation (2026-06-12, recorded in the parent's
`escalations:` entry) confirmed: `listAccounts` does NOT expose the ACI
(number only — `JsonAccount(String number)` in signal-cli source); the
viable mechanisms are reading the ACI from signal-cli's account data
file (the identity store under the configured data-dir — the mechanism
design §6.5.5 step 3 already specifies) or a `getUserStatus` self-query
over JSON-RPC (JSON output carries `uuid`, but it is a network
round-trip at startup). This ticket implements the identity-store read.

Removing the operator-typed anchor closes the Signal half of the
mistype/substitution risk recorded in `security.md` §Per-adapter admin
threat profile, and is *more* drift-robust than the property: the anchor
value and the mention payloads it is compared against then originate
from the same tool at the same version.

## Acceptance

See frontmatter. Summary: derivation from the identity store at
`start()` with canonicalization + `isWellFormed` validation and
per-adapter-resilient failure; named derivation test; named decoupling
test (admin rotation cannot move the anchor); derived value reaches
`SignalGroupHandler`; spec/design alignment (deployment.md item 7,
security.md note narrowed to SimpleX-only, design §6.5.4/§6.5.5);
full-suite verify green.

## Out-of-scope

See frontmatter. The `test_plan.modifies` list is the test-integrity
authorization: `SignalAdapterStartFailureTest`'s botAci-sourced failure
pins re-target the equivalent identity-store failure modes (the failure
semantics — that adapter fails start(), Provider survives — are
preserved; only the value's source changes). The remaining listed files
are construction-site updates only IF the implementation changes the
`SignalAdapter` constructor signature (see §Notes); their behavioral
assertions are unchanged. `ProductionAdapterActivationTest` /
`BootstrapAdminParseGateTest` / `MultiAdapterProductionIT` lose or
re-source their `bot-aci` property references.

## Notes

- **Mechanism decision.** Identity-store read, per design §6.5.5 step 3
  ("read the account ACI from the identity store"): local, deterministic,
  no network at startup, no CDSI rate-limit exposure. The file format is
  signal-cli-internal and may change across versions — but the whole
  transport is the provisional §6.5.1 default already, and a format change
  fails LOUDLY at start() (parse error), never silently. Alternative
  considered: `getUserStatus` self-query over the already-held JSON-RPC
  connection (confirmed to carry `uuid` in JSON output) — rejected for the
  startup network dependency; revisit if the store format proves unstable.
- **Constructor shape is the plan-writer's call at start.** Construction
  sweep (2026-06-12): `new SignalAdapter(` appears in 13 test files + the
  Producer. Option A: remove the `botAci` constructor param (mechanical
  one-arg churn across all sites — `files_budget: 24` covers this worst
  case). Option B: a constructor-preserving shape (derive at start() and
  overwrite/feed the existing field) — far smaller diff, but must not
  introduce a production-dead branch or a test-only seam. The
  complexity:high outline must choose and justify.
- **D37 / log hygiene:** never log the raw derived ACI; failure log lines
  name the store path/account property, not the value.
- **No defensive code inside:** the account file is an external system
  boundary (file I/O) — validate there; no new internal null-checks.
- Parent investigation record: M1-318 frontmatter `escalations:` entry.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-319-*.md
```
