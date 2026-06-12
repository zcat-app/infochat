---
id: M1-320
title: "Derive SimpleX bot queue address via APIShowMyAddress"
status: pending
created: 2026-06-12
last_updated: 2026-06-12
blocked_by: [M1-319]
files_budget: 22
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - Signal derivation — that is M1-319 (this ticket is blocked on it; the
    two share the spec files and the cross-adapter contract tests).
  - The D10 mention-comparison logic itself (SimpleXGroupHandler /
    SimpleXMessageCodec.extractMentionQueueAddresses) — only where the
    anchor VALUE originates changes here.
  - Bot-id well-formedness strengthening (M1-294 U-35) — the existing
    SimpleXIdentity.isWellFormed / isValidQueueAddressId are reused as-is.
  - Admin (bootstrap) contact id provenance — infochat.adapters.simplex.admin
    stays an operator property per deployment.md §Operator inputs item 2.
acceptance:
  - "SimpleXAdapter derives its bot queue address at start() by querying
    simplex-chat over the adapter's own WebSocket connection (an
    APIShowMyAddress-shaped command; SimpleXMessageCodec gains the request
    encoder and the response decode for the returned contact link), NOT
    from infochat.adapters.simplex.bot-queue-address; that property and
    its ProductionAdapterBeans injection (including the SimpleXIdentity
    construction from it) are removed. The bare queue id is extracted from
    the returned contact link — the same identifier the operator extracts
    manually today; the existing non-blank validation at start() is
    preserved against the derived value. Query, decode, or extraction
    failure fails THAT adapter's start() only (per-adapter resilience
    preserved). Named test: SimpleXAdapterIdentityDerivationTest
    .startDerivesQueueAddressFromShowMyAddress (driven by the existing
    fake simplex-chat process/WebSocket answering the query)."
  - "The query is issued only after the WebSocket is ready (the existing
    waitForWebSocketReady / rebuild sequencing), and the derived anchor is
    re-established on subprocess restart so the post-restart group routing
    uses a consistent anchor. Named test:
    SimpleXAdapterIdentityDerivationTest.restartRederivesAnchor."
  - "Decoupling invariant: admin-key rotation cannot move the bot's D10
    anchor — derivation reads only the bot's own address; no .admin-sourced
    value participates. Named test:
    SimpleXAdapterIdentityDerivationTest.derivedAnchorIndependentOfAdminConfig
    (differing infochat.adapters.simplex.admin value, anchor unchanged)."
  - "The derived queue address feeds the D10 anchor: the SimpleXIdentity
    used for group-mention routing carries the derived value. Named test
    (or assertion within the derivation test) pins that post-start() group
    routing compares against the derived value."
  - "docs/spec/deployment.md §Operator inputs item 7 states that the
    per-adapter bot contact id is derived from the adapter's own identity
    material at adapter startup (SimpleX: queried from the running
    simplex-chat; Signal: read from the signal-cli identity store) and is
    not an operator-typed property; docs/spec/security.md §Per-adapter
    admin threat profile 'Operator-typed bot-identity anchor' note is
    removed (risk closed). docs/design/06-messaging.md §6.4 bot-identity
    block, §6.7 and §6.10 are aligned to the as-built query mechanism (no
    identity-dir property exists in code; the id is queried, not parsed
    from disk)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterIdentityDerivationTest.java
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterSkeletonTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXReconnectTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXGroupHandlerTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXEditFallbackTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterChunkedSendTest.java
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
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-320: Derive SimpleX bot queue address via APIShowMyAddress

## Context

Decomposed from M1-318 (budget-breach); blocked on M1-319 (shared spec
files and cross-adapter contract tests). The SimpleX half of the
bot-contact-id derivation: replace the operator-typed
`infochat.adapters.simplex.bot-queue-address` property with a value the
adapter derives by asking the simplex-chat process it already runs.
M1-318's feasibility investigation (2026-06-12, recorded in the parent's
`escalations:` entry) confirmed the official client API (stable branch)
exposes `APIShowMyAddress` (client method `apiGetUserAddress`) returning
a `userContactLink` — the same source the operator copies the configured
value from manually today.

Closing this removes the last operator-typed bot-identity anchor and
with it the mistype/substitution risk recorded in `security.md`
§Per-adapter admin threat profile. It is also *more* drift-robust than
the property: the anchor and the `format.memberRef` values it is
byte-compared against then originate from the same simplex-chat process
at the same version, whereas the configured snapshot can go stale.

## Acceptance

See frontmatter. Summary: codec gains the self-address request encoder +
response decode; client issues the query after WebSocket readiness and
re-derives on subprocess restart; bare queue id extracted from the
returned contact link; existing non-blank validation preserved;
per-adapter-resilient failure; named derivation, restart, and decoupling
tests; derived value reaches group routing; spec/design alignment
(deployment.md item 7 full-derivation wording, security.md note removed,
design §6.4/§6.7/§6.10); full-suite verify green.

## Out-of-scope

See frontmatter. The `test_plan.modifies` list is the test-integrity
authorization: any pre-existing test constructing `SimpleXAdapter` or
`SimpleXIdentity` with an operator-style queue address is updated only
at its construction site IF the implementation changes the constructor
shape (see §Notes); behavioral assertions are unchanged. Tests pinning
the blank-bot-queue-address start failure re-target the equivalent
derivation-failure mode (failure semantics preserved; only the value's
source changes). `ProductionAdapterActivationTest` /
`BootstrapAdminParseGateTest` / `MultiAdapterProductionIT` lose or
re-source their `bot-queue-address` property references.

## Notes

- **Wire surface is new.** `SimpleXMessageCodec` /
  `SimpleXWebSocketClient` model no self-address command today; the
  request encoder, response-variant decode, and their round-trip unit
  tests are new surface. Codec first, then client sequencing, then
  adapter wiring (a query issued before the WS is up yields a spurious
  TRANSIENT failure).
- **Extraction contract.** The configured value today is the bare
  URL-safe-base64 queue id (≥43 chars, `isValidQueueAddressId`); the
  query returns a full contact link. The extraction must yield the same
  identifier the operator extracts manually today — pin it with fixtures
  in the codec round-trip test. The exact response frame shape is modeled
  in the fakes like every other simplex-chat frame (the WS bot API is
  not a stability-promised contract; drift fails loudly at start()).
- **Constructor shape is the plan-writer's call at start.** Construction
  sweep (2026-06-12): `new SimpleXAdapter(` in 9 test files + Producer;
  `new SimpleXIdentity(` in 5 of those + Producer. Option A: remove the
  identity constructor param (mechanical churn — `files_budget: 22`
  covers it). Option B: constructor-preserving (derive at start() and
  feed the existing `SimpleXIdentity` flow) — smaller diff, but must not
  introduce a production-dead branch or a test-only seam. The
  complexity:high outline must choose and justify. `SimpleXIdentity`'s
  javadoc ("sourced from operator config") needs updating either way.
- **D37 / log hygiene:** never log the raw derived queue address or the
  full contact link; failure log lines name the source, not the value.
  (Both clients already redact via SHA-256 prefix in overflow logs.)
- **No defensive code inside:** the query response is an external system
  boundary (untrusted wire data, same discipline as the inbound codec) —
  validate there; no new internal null-checks.
- Parent investigation record: M1-318 frontmatter `escalations:` entry.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-320-*.md
```
