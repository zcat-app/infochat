---
id: M1-186
title: "Signal group outbound send path"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 6
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodec.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - SimpleX group outbound — not flagged by the audit; only Signal rejects group sends
  - group inbound (SignalGroupHandler) — fully wired and untouched here; mention stripping is M1-187's
  - group typing indicators — best-effort per SPI; may stay DM-only, but then the comment must say so without citing a ticket number
  - provider-side group reply logic, per-group rate caps, group-removal bookkeeping — consumers of the fixed adapter, not part of it
acceptance:
  - "A group-scope OutboundMessage sends successfully through the Signal adapter: a named test asserts the JSON-RPC request carries the group id (signal-cli send --group-id shape) and a MessageHandle is returned (today recipientFromDmScope throws PERMANENT 'group scope not supported in M1-107 (lands in M1-108)' for every group send while group inbound is fully delivered)"
  - "update and finalize work on a group-scope handle the same way they do for DM handles — a named test exercises the send→update→finalize cycle in a group scope"
  - "DM send/update/finalize behavior is unchanged — existing tests stay green unmodified"
  - "The stale 'lands in M1-108' comments in the Signal client/codec are gone (production comments must not cite ticket numbers per CLAUDE.md §Coding style)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Identity and groups
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-186: Signal group outbound send path

## Context

Signal group INBOUND is fully wired (SignalGroupHandler dispatches
mention-gated group messages), but every group OUTBOUND send is rejected:
`recipientFromDmScope` (SignalJsonRpcClient.java:269-273) throws a PERMANENT
MessagingException "group scope not supported in M1-107 (lands in M1-108)"
for any non-DM scope — the comment is stale (M1-108 shipped group inbound,
not outbound). The bot can hear groups but never reply; every group reply
fails PERMANENT, which also feeds the provider's permanent-failure
bookkeeping. Unified finding M5 (high), `deep-code-review/v2/UNIFIED.md` §2.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T10 under `deep-code-review/v2/` (kimi-folder msg
  F6).
- signal-cli's JSON-RPC `send` accepts a `groupId` param in place of
  `recipient` — the codec needs an encodeSend group variant; the handle
  registry already stores the recipient string per handle and can store the
  group id the same way.
- SignalMessageCodec comments also assert "group is M1-108" near the DM
  extraction path (:128, :145) — those describe inbound DM filtering that
  remains correct, but the ticket-number citations should be cleaned up
  where touched.
