---
id: M1-186
title: "Signal group outbound send path"
status: done
created: 2026-06-07
last_updated: 2026-06-08
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
  - "A group-scope OutboundMessage sends successfully through the Signal adapter: SignalJsonRpcClientTest.groupSendCarriesGroupIdAndReturnsHandle asserts the JSON-RPC request carries the group id (signal-cli send groupId shape, no recipient field) and a MessageHandle is returned (today recipientFromDmScope throws PERMANENT 'group scope not supported in M1-107 (lands in M1-108)' for every group send while group inbound is fully delivered)"
  - "update and finalize work on a group-scope handle the same way they do for DM handles — SignalJsonRpcClientTest.groupSendUpdateFinalizeCycleSucceeds exercises the send→update→finalize cycle in a group scope and asserts each edit's JSON-RPC request carries the group id"
  - "DM send/update/finalize behavior is unchanged — existing tests stay green unmodified"
  - "The stale 'lands in M1-108' comments in the Signal client/codec are gone (production comments must not cite ticket numbers per CLAUDE.md §Coding style)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  modifies:
    - "SignalJsonRpcClientTest.groupScopeSendRejectedPermanent — its PERMANENT-rejection premise is reversed by acceptance item 1 (group-scope send now succeeds); the method is replaced by the group-send-succeeds tests named in acceptance items 1-2, both in the same SignalJsonRpcClientTest file already listed under test_plan.adds"
  preserves:
    - all DM send/update/finalize tests currently green on main
    - all non-Signal-group-outbound tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Identity and groups
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-08
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 226
      removed: 39
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
escalations:
  - date: 2026-06-08
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — discovered at /m1-tick start before any implementation.
      Existing green test SignalJsonRpcClientTest.groupScopeSendRejectedPermanent
      (lines 466-486) asserts a group-scope send throws PERMANENT. Acceptance
      item 1 requires a group-scope send to SUCCEED and return a MessageHandle.
      The two are mutually exclusive: the implementation must replace that test,
      but test_plan.preserves ("all tests currently green on main") authorizes no
      such modification and test_plan has no `modifies` field. The clarity
      pre-flight recorded TEST-CHANGES-AUTHORIZED: NOT-APPLICABLE and did not
      catch the conflict.
revisions:
  - date: 2026-06-08
    reason: premise-fail refine (test_plan.preserves keeps a pre-existing test
      whose PERMANENT-rejection premise acceptance item 1 reverses; no modifies
      authorization existed for replacing it)
    snapshot:
      status: escalated
      escalation_reason: premise-fail
      test_plan_at_snapshot:
        adds:
          - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
        preserves:
          - all tests currently green on main
clarity_check:
  date: 2026-06-08
  verdict: WARN
  warnings:
    - "Acceptance items 1 and 2 say 'a named test' without naming the test class or method; reviewer must discover the test name from the diff at review time rather than verifying it against the ticket."
  blockers: []
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
