---
id: M1-566
title: Signal edit frames use the real signal-cli encoding (send+editTimestamp)
status: done
created: 2026-07-04
last_updated: 2026-07-04
reviews:
  - round: 1
    date: 2026-07-04
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 215
      removed: 66
clarity_check:
  date: 2026-07-04
  verdict: PASS
  warnings: []
  blockers: []
redteam_findings: []
redteam_audits: []
blocked_by: []
files_budget: 6
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageHandle.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodecTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalEditFallbackTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClientTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the SimpleX edit path (its own transport and encoding; not implicated
    by F-live-11)
  - StageProgressNotifier / OutboundDelivery / the ProgressNotifier SPI
    (provider-side flow is correct; only the Signal wire encoding is wrong)
  - the edit-failure fresh-send fallback DESIGN (§6.3.8/§6.5.7 semantics
    stay exactly as-is — PERMANENT edit error → fresh send with the
    original correlationId, handle falls back for good, fallback frame
    draws its own rate token; live-verified working 2026-07-04)
  - a broader fake-vs-real signal-cli method-surface audit (this ticket
    reconciles only the edit frames the fakes accept)
  - AdapterMetrics counters and their names/outcomes (unchanged)
  - supportsMessageEdit / minEditInterval capability declarations
    (Signal DOES support edits; only the RPC encoding is wrong)
acceptance:
  - "SignalMessageCodec encodes the DM edit as JSON-RPC method `send`
    with params {account, recipient:[…], message, editTimestamp:<target>}
    and the group edit as method `send` with {account, groupId, message,
    editTimestamp:<target>} — replacing method `updateMessage` +
    `targetSentTimestamp`, which signal-cli 0.14.5 does not implement
    (F-live-11: not in its command list; the daemon rejects it as
    method-not-found, so every edit fell back to fresh sends on real
    wire). The encode methods are renamed to match what they now emit
    (e.g. encodeEditSend / encodeGroupEditSend). A WHY comment records
    the F-live-11 origin: jsonRpc methods mirror the CLI command
    surface, and the CLI edit is `send --edit-timestamp`. Named test:
    SignalMessageCodecTest pins BOTH frame shapes (method `send`,
    `editTimestamp` present, no `targetSentTimestamp`)."
  - "On a successful edit, SignalJsonRpcClient extracts the `timestamp`
    from the send response (extractLong, same as the original send) and
    refreshes the stored SignalMessageHandle so the NEXT edit on the
    same handle targets the just-sent revision's timestamp. WHY comment:
    official Signal clients accept an edit chain targeting the LATEST
    revision; targeting-latest also works if implementations accept the
    original's timestamp, so it is the dominant strategy under either
    chain semantic (live probe 2026-07-04 proved only a single edit
    hop). Named test: successive update() calls emit editTimestamp
    values that follow the chain (first = original send timestamp,
    second = first edit's response timestamp)."
  - "SignalEditFallbackTest and SignalJsonRpcClientTest are reconciled
    to the new frame shape without weakening: the PERMANENT-error →
    fresh-send fallback cases still pin 2 frames, correlationId reuse,
    fellBack latching, and the fallback frame's own rate-token draw;
    the fakes' edit-rejection triggers key on the edit frame's new
    shape (method `send` WITH editTimestamp) so a plain send is never
    misclassified as an edit."
  - "SignalMessageHandle javadoc no longer references `updateMessage`
    and documents the timestamp field as 'latest revision timestamp —
    original send, then refreshed by each successful edit'."
  - mvn verify is green.
test_plan:
  adds: []
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodecTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalEditFallbackTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClientTest.java
  preserves:
    - all tests currently green on main; the fallback suite's coverage
      (2-frame accounting, correlationId reuse, fellBack latching) must
      survive shape reconciliation intact
spec_refs:
  - docs/spec/messaging.md §Message handles
  - docs/spec/messaging.md §Failure handling
decision_refs: []
---

# M1-566: Signal edit frames use the real signal-cli encoding (send+editTimestamp)

## Context

Origin: **F-live-11** (docs/plan/live-e2e/HANDOFF.md §Live findings,
found 2026-07-04 by the §6 edit-fallback probe). The adapter encodes
message edits as JSON-RPC method `updateMessage`, but signal-cli 0.14.5
has no such command — its jsonRpc methods mirror the CLI command
surface, and the CLI edit is `send --edit-timestamp`. The daemon
returns method-not-found, categorized PERMANENT (non-`-32603`), so the
§6.3.8 fresh-send fallback fires on the FIRST edit of every handle and
the handle falls back for good. Green in CI because the fakes accept
`updateMessage` — D-live-9 thesis, third strike (F-live-1, F-live-10,
now this).

Live evidence (2026-07-04): a /summary progress flow over real Signal
delivered `Working on it...`, `Translating...`, and the final summary
as THREE separate dataMessages (zero editMessage envelopes); metrics
read `update_fail{reason="unknown"} 1`, `fallback_send 2`. The fix
shape is already proven on real wire: a client-to-client jsonRpc `send`
with `editTimestamp:<original ts>` rendered as a true edit
(`Edit: Target message timestamp: …`) on the receiving signal-cli.

Impact is UX + rate-budget, not correctness: content always arrives
(fallback is rate-paced per M1-359), but every progress flow degrades
to N separate messages and each fallen-back op costs 2 wire frames.

## Acceptance

Mirrors the YAML list: re-encode DM+group edit frames as
`send`+`editTimestamp`; refresh the handle timestamp from each edit
response so chains target the latest revision; reconcile the fakes and
tests to the new shape without weakening the fallback suite; javadoc
truth; `mvn verify` green.

## Out-of-scope

See frontmatter. In particular the fallback DESIGN is untouched — it
was live-verified working exactly as specified; after this fix it
simply becomes what it was meant to be (a rare-failure path, not the
every-edit path).

## Notes

- The response of an edit-shaped `send` carries a fresh `timestamp`
  (live-observed 2026-07-04: edit response `timestamp:1783200620399`
  for target `1783200602704`), so extractLong on the same key as the
  original send path works unchanged.
- The handle map already supports re-put under `synchronized (handles)`
  (fallbackSend does it for `asFallenBack()`); the timestamp refresh
  uses the same pattern and must re-check presence (concurrent
  finalize eviction), mirroring the fallback's re-put guard.
- Host validation after merge + image rebuild (not part of `mvn
  verify`): drive `/summary -w 48h` from the signal user DM (fixture
  subscription exists) and assert the client sees ONE message that
  edits in place (editMessage envelopes, no extra dataMessages), and
  `adapter_outbound_update_total{outcome="fallback_send"}` does NOT
  increment. A two-edit chain (placeholder → stage → final) also
  live-verifies the latest-revision targeting choice.
- The M1-107 ticket file mentions `updateMessage` — historical record,
  not edited (done tickets are immutable).
