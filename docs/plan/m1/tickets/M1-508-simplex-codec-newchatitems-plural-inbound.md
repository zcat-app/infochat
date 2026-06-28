---
id: M1-508
title: "SimpleX codec: decode newChatItems (plural) inbound (v6.5.4)"
status: done
created: 2026-06-28
last_updated: 2026-06-28
blocked_by: []
files_budget: 6
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
complexity: medium
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - "DM identity rules — inbound identity MUST stay the connection-based contactId (D10); this ticket changes only WHICH frame shape is decoded as inbound, never how the contact_id is derived from it."
  - "Signal adapter — the Signal codec uses its own envelope shape; do not touch it."
  - "Operator tooling / docs (wizard, SETUP_GUIDE, design notes) — tracked by M1-507 / M1-509."
  - "The docker-compose token-env hotfix — already landed as commit a381aedf (process:); see Notes 'Prior hotfix (history)'. Do not re-do it."
  - "Pinning a different simplex-chat version — the fix targets the bundled v6.5.4 frame shape; version bumps are separate."
acceptance:
  - >-
    A direct received-message async event in the simplex-chat v6.5.4 batched
    shape — resp.type == "newChatItems" (PLURAL) with NO corrId, carrying a
    chatItems ARRAY whose entry has chatInfo.chatType=="direct" + a received
    chatItem — decodes to an Inbound whose sender().contactId() and
    ScopeRef.Dm equal the connection contactId, with the message body. Named
    test SimpleXMessageCodecTest.decodesNewChatItemsPluralDirectReceivedAsInbound
    proves it, using a REAL v6.5.4 frame fixture (not a hand-rolled singular one).
  - >-
    A send RESULT in the same plural shape — resp.type == "newChatItems" WITH a
    corrId (the response to our own /_send) — still decodes as a SendAck, not as
    an inbound message (no self-echo loop). Named test
    SimpleXMessageCodecTest.newChatItemsWithCorrIdStillDecodesAsSendAck proves it.
  - >-
    A group received message in the plural shape (chatInfo.chatType=="group")
    decodes to a group-scope Inbound (mirrors the existing singular group path).
    Named test covers it.
  - >-
    Regression guard: the existing singular newChatItem fixtures and the
    contactLink-identity regression test (M1-506) still pass; the prior
    "send-ack-without-chatItemId" drop of a real inbound DM no longer occurs
    (the bug this ticket fixes — confirmed live: 100% of SimpleX inbound was
    silently discarded on v6.5.4).
  - "mvn -B verify is green from the repo root."
test_plan:
  adds: []
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
  preserves:
    - all tests currently green on main
    - the existing singular-newChatItem decode tests and the M1-506 contactLink-identity regression test
spec_refs:
  - "docs/design/06-messaging.md §6.4.4 Event decoding"
decision_refs:
  - D10
  - D46
reviews:
  - round: 1
    date: 2026-06-28
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 319
      removed: 15
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-28
    verdict: CLEAN
    base: main
    head: "working-tree (m1/M1-508-simplex-codec-newchatitems-plural-inbound, uncommitted)"
    verdict_file: docs/plan/m1/redteam/M1-508-2026-06-28.md
    out_of_model_count: 2
    note: |
      CLEAN — plural newChatItems inbound decode preserves D10/D50 identity
      (shared decodeChatItemEntry) and the corrId discriminator cannot be
      driven to self-echo or mis-attribution by adversary-reachable input. Two
      OUT-OF-MODEL advisory items (corrId direction trust within boundary 7;
      first-only multi-item drop as an availability caveat), both non-blocking
      and documented in-code; no follow-up ticket filed.
clarity_check:
  date: 2026-06-28
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 3: group-decode criterion says 'Named test covers it' without naming the test method; developer to name it."
    - "FILES-BUDGET-PLAUSIBLE: test_plan.adds is empty but a real v6.5.4 frame fixture is required; resolved by inlining the fixture as a string constant in the test class (no new resource file)."
  blockers: []
---

# M1-508: SimpleX codec — decode newChatItems (plural) inbound (v6.5.4)

## Context

**Live-confirmed bug: 100% of SimpleX inbound is silently dropped on the
bundled simplex-chat v6.5.4.** The codec's `decode` dispatch
(`SimpleXMessageCodec` ~line 280) maps the SINGULAR `newChatItem` to inbound
decoding, but maps the PLURAL `newChatItems` to `decodeSendAck` (treating it
purely as a send acknowledgement). simplex-chat v6.5.4 delivers *received*
messages as the batched `newChatItems` (plural) async event, so every inbound
DM is routed to `decodeSendAck`, finds no `itemId` on the array-shaped
`chatItems`, and is discarded as `Ignored("send-ack-without-chatItemId")` —
never reaching `InboundRouter`. Confirmed via DEBUG on a live deployment: a
real `/help` DM produced exactly that ignored-frame log and created no users
row.

This passed CI because `SimpleXMessageCodecTest` uses hand-rolled SINGULAR
`newChatItem` fixtures — a test-vs-reality gap. It also means M1-506's
claim-token was never actually exercisable on real SimpleX (no inbound works).

## Acceptance

See the YAML `acceptance:` list. In prose: decode the plural `newChatItems`
async (no-corrId) event as inbound — direct → DM Inbound, group → group
Inbound, identity = connection contactId (D10, never the advertised
contactLink) — while a plural `newChatItems` WITH a corrId (our own send
result) still decodes as a SendAck so there is no self-echo. Cover with named
tests built from a REAL v6.5.4 frame, and keep the singular path + M1-506
contactLink regression green.

## Notes

- **Distinguish async-received from send-result by corrId.** A received-message
  batch is an async event with NO `corrId`; a send result carries the `corrId`
  of our `/_send`. Route on that, then for the no-corrId case iterate
  `resp.chatItems[]` and decode each via the existing direct/group chatItem
  logic (reuse `decodeNewChatItem`'s body, do not fork the identity rules).
- **Capture a real frame.** Get the actual v6.5.4 `newChatItems` JSON (e.g. via
  `simplex-chat` WS DEBUG against a test contact) and use it verbatim as the
  fixture so this cannot regress against the bundled version.
- **Batched arrays may carry >1 item.** Decide v1 behavior (decode each as a
  separate Inbound, or first-only with a logged drop of the rest); call it out
  explicitly and test it.
- **Prior hotfix (history).** Getting this far required wiring the new
  `INFOCHAT_SIMPLEX_ADMIN_TOKEN` env var into the provider container —
  committed as `process:` hotfix `a381aedf` (docker-compose.yml), outside the
  M1-506/M1-507 scope (neither touched the compose file). M1-507's follow-up
  should fold that into the tooling alignment and drop the now-inert
  `INFOCHAT_SIMPLEX_ADMIN_CONTACT_ID`; recorded here so the SimpleX-bring-up
  thread is traceable from M1- history.
- **Security-relevant:** this is the inbound identity-decode path; a redteam
  pass should confirm the plural path resolves identity to the connection
  contactId only and cannot be driven to self-echo or to attribute an inbound
  to the wrong contact.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-508-*.md
```
