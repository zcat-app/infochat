---
id: M1-273
title: "Transport classification matrix + Signal start race"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 14
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalSubprocess.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The AdapterRegistry wiring order — verified correct (handler set before transport start); only the in-start() connect-before-attach window is in scope.
  - The respawn backoff *parameters* (base, cap) — only the jitter rule changes to match design.
  - Capability flags and maxMessageBytes (M1-274).
  - The SPI isWellFormedContactId hoist (M1-270) — the bot-aci leg here may consume it if M1-270 lands first, else call SignalIdentity directly.
acceptance:
  - "SignalAdapter.start() attaches the inbound handler to the client before connect: no envelope flushed by signal-cli at connect time can hit the 'dropped — no InboundHandler set' path; a named test covers an envelope delivered immediately on connect."
  - "SignalJsonRpcClient.extractLong (and sibling typed extractors) reject wrong-typed response fields with MessagingException instead of letting ClassCastException escape send(); named test with a non-numeric field."
  - "SignalSubprocess respawn backoff uses equal jitter [exp/2, exp] per design 06 §595 (supervisor respawn rule, not the §6.3.6 full-jitter rule its comment cites); a named test pins samples within the bound."
  - "Interrupted-awaiting-ack and closed-before-ack classify identically across SimpleX and Signal per one documented classification matrix (interrupted → TRANSIENT both; closed-before-ack → PERMANENT both, or as the matrix decides — one rule, two adapters); a shared contract test asserts both adapters against the matrix."
  - "A missing JSON-RPC error code classifies as PERMANENT per the documented default (today it falls into -32603→TRANSIENT against the contract comment one screen below); named test."
  - "SignalAdapter validates bot-aci well-formedness at start (SignalIdentity.isWellFormed); a malformed ACI fails startup instead of silently breaking mention recognition; named test. (The existing lowercase canonicalization is deliberate and stays.)"
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
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

# M1-273: Transport classification matrix + Signal start race

## Context

Deep-review v4 verified mediums **M-M1..M-M4**, **M-M8**, and the JSON-RPC
default-code low (`deep-code-review/v4/UNIFIED-REPORT.md` §2/§3; sources
`deep-code-review/v4/opus-47/05-module-infochat-messaging-adapter.md#F1/#F2/#F3`,
`deep-code-review/v4/fable5/05-module-infochat-messaging-adapter.md#F1/#F2/#F4`):

- **M-M1:** inside `SignalAdapter.start()` the order is `connectClient`
  (reader thread starts; signal-cli flushes queued envelopes) →
  `attachClient` (handler propagated). The drop path is confirmed
  (`handler == null → LOG.debugf("…dropped — no InboundHandler set")`). The
  window is one method call wide — real but small; downgraded from opus-47's
  HIGH because the registry-level wiring order is correct.
- **M-M2:** `extractLong` checks `containsKey` but `getJsonNumber` CCEs on a
  non-numeric value — escaping the MessagingException contract at a trust
  boundary (signal-cli subprocess output).
- **M-M3:** design 06 §595 mandates `[exp/2, exp]` equal jitter for
  supervisor respawns (explicitly contrasted with §6.3.6 full jitter);
  `SignalSubprocess.computeBackoffDelay` samples `[0, bound)` and cites the
  wrong section.
- **M-M4:** the same semantic transport state classifies differently across
  adapters (interrupted-awaiting-ack → SimpleX TRANSIENT :270 vs Signal
  PERMANENT; closed-before-ack → SimpleX PERMANENT vs Signal IOException
  path).
- **M-M8:** only `isBlank()` is checked on `bot-aci`; a typo'd ACI silently
  breaks mention recognition.
- JSON-RPC low: missing error code defaults into the -32603→TRANSIENT branch
  against the documented default-PERMANENT contract.

## Acceptance

See frontmatter. The classification matrix is the one design artifact this
ticket produces: a single documented mapping from transport states to
TRANSIENT/PERMANENT that both adapters implement, pinned by a shared
contract test.

## Out-of-scope

See frontmatter. Note the M1-270 interaction on the bot-aci leg: whichever
lands second adapts (direct `SignalIdentity` call vs SPI method) — a
one-line difference.

## Notes

- The matrix lives as a code-level doc (javadoc on the shared classification
  contract or a package doc in the messaging module), not in docs/spec —
  design 06 already carries the failure-handling rules; if the matrix
  contradicts design 06, amend the design in the same diff (design files may
  change without spec amendment).
- M-M3's design text is written about the SimpleX supervisor; the report's
  read (adopted here) is that Signal is bound by the same discipline. If
  the implementer disagrees, the alternative is amending design 06 to bless
  full jitter for Signal — surface that choice rather than silently picking.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-273-*.md
```
