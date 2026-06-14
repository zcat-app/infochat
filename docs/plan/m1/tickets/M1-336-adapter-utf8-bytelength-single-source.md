---
id: M1-336
title: "Adapter inbound: single-source UTF-8 byte length across codec cap + metric"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 7
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/Utf8.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/metrics/AdapterMetrics.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "InboundMessage is NOT modified. The byte length is single-sourced via the Utf8 util plus a once-per-message computation in InboundRouter, NOT threaded through the message record. SignalJsonRpcClient, the Signal/SimpleX group handlers, and InMemoryAdapter (the other InboundMessage construction sites) are therefore untouched, and the 12 provider-test new InboundMessage(...) call sites keep compiling unchanged."
  - "The metric name adapter.message.bytes and its dimensions — unchanged; only the computation is single-sourced."
  - "The codec cap value MAX_INBOUND_TEXT_BYTES (16384) and the provider defense-in-depth cap value maxInboundBodyBytes — both unchanged."
  - "The two cap LAYERS stay independent: the adapter codec cap (decode-time, MAX_INBOUND_TEXT_BYTES) and the provider cap (maxInboundBodyBytes, M1-038 defense-in-depth) are NOT merged. Only the per-walk arithmetic is shared across them."
  - "The SimpleX outbound cap site (MAX_OUTBOUND_TEXT_BYTES, SimpleXMessageCodec ~line 225) — out of scope; this ticket is inbound only."
acceptance:
  - "A single Utf8 utility (new class in the messaging package of infochat-messaging-adapter) is the sole source of the UTF-8 byte-length arithmetic: Utf8.byteLength(String) (full alloc-free walk) and Utf8.exceedsByteLength(String, int limit) (alloc-free walk that returns true as soon as the running count passes the limit). The previously hand-copied implementations — AdapterMetrics.utf8ByteLength and InboundRouter.exceedsUtf8ByteLength (the latter documented at AdapterMetrics.java:242 as a copy) — are removed or reduced to thin delegates to the util, so 'UTF-8 byte length' is computed one way everywhere and cannot drift between the cap and the metric."
  - "Both codecs enforce the inbound cap allocation-free: SignalMessageCodec.exceedsInboundByteCap and the two SimpleXMessageCodec inbound sites (DM ~line 360, group ~line 471) call Utf8.exceedsByteLength(body, MAX_INBOUND_TEXT_BYTES) instead of body.getBytes(StandardCharsets.UTF_8).length > MAX_INBOUND_TEXT_BYTES. This restores the AdapterMetrics 'a hostile flood must not buy a byte-array allocation per message' commitment at the adapter ingest boundary, and via early exit bounds the walk of an oversize hostile body to ~the cap rather than its full attacker-chosen length."
  - "InboundRouter.onMessage computes the inbound body's UTF-8 byte length exactly once (Utf8.byteLength(raw)) and threads that one int to both enforcement points: the adapter.message.bytes summary (via a new AdapterMetrics.messageBytes(String adapter, Direction direction, int utf8ByteLength) overload) and the M1-038 defense-in-depth body-size cap (len > maxInboundBodyBytes). The body is no longer walked twice in the provider, and the metric value and the cap decision read the same int. The existing AdapterMetrics.messageBytes(..., String) overload is retained for the outbound callers (OutboundDelivery, StageProgressNotifier), which are unchanged."
  - "A test pins single-sourcing: (a) Utf8.byteLength / Utf8.exceedsByteLength agree with a reference UTF-8 length across ASCII, 2-byte, 3-byte, and surrogate-pair inputs, and the existing exceedsUtf8ByteLength boundary assertions stay green (re-pointed at the util if the InboundRouter method becomes a delegate); (b) a boundary-length inbound body (just over maxInboundBodyBytes) is rejected by the provider cap, and the recorded adapter.message.bytes value equals that same single-computed length (not a re-walk). InboundMessage is unchanged, so existing inbound cap + metric tests stay green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging (Utf8 util: byteLength/exceedsByteLength agreement + alloc-free codec-cap boundary)
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging (assert recorded adapter.message.bytes == the single computed length; re-point exceedsUtf8ByteLength boundary assertions if that method becomes a Utf8 delegate)
  preserves:
    - all tests currently green on main (InboundMessage unchanged, so the 12 new InboundMessage(...) construction sites compile untouched)
spec_refs: []
decision_refs: []
reviews: []
escalations:
  - date: 2026-06-14
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      N/A — clarity pre-flight FAIL: FILES-BUDGET-PLAUSIBLE + TEST-CHANGES-AUTHORIZED.
      Root cause: the original design threaded the codec's precomputed int across
      the module boundary via InboundMessage, fanning to ~7 production + 12 test
      files past files_budget:6. Resolved by refine (see revisions[0]).
revisions:
  - date: 2026-06-14
    reason: clarity-fail refine — redesigned from threading the codec's
      precomputed length through InboundMessage (which fanned past files_budget
      to ~7 prod + 12 provider-test construction sites and tripped both clarity
      blockers) to single-sourcing the byte-length ALGORITHM in one Utf8 util,
      leaving InboundMessage and its construction sites untouched. Same goal
      (no per-message byte[] alloc, no redundant walk, no drift), smaller and
      cleaner surface.
    snapshot:
      status: escalated
      escalation_reason: clarity-fail
      files_budget_at_snapshot: 6
      files_scope_at_snapshot:
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/metrics/AdapterMetrics.java
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodec.java
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
        - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
        - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
      acceptance_at_snapshot:
        - "The inbound UTF-8 byte length is computed once per message and shared between the codec's inbound-cap check and the AdapterMetrics size summary, instead of being computed twice (codec: body.getBytes(UTF_8).length, which both allocates a byte[] AND walks the string; AdapterMetrics.utf8ByteLength: a second full walk). The codec computes the walk-only length once (no byte[] allocation), enforces the cap against it, surfaces it on the decoded result, and AdapterMetrics gains an overload that records a precomputed int length."
        - "The codec's inbound-cap check no longer calls body.getBytes(StandardCharsets.UTF_8).length: both the Signal and SimpleX codecs use the alloc-free walk for the cap, and the two enforcement points (cap + metric) read the same value so they cannot drift in interpretation of 'UTF-8 byte length'."
        - "A test pins single-sourcing: a boundary-length body (just over MAX_INBOUND_TEXT_BYTES) is rejected by the cap using the walk-only length, and the recorded adapter.message.bytes value equals that same length (not a re-walked or re-allocated recomputation). Existing inbound cap + metric tests stay green."
        - "mvn -B clean verify from the repo root exits 0."
      out_of_scope_at_snapshot:
        - "The metric name adapter.message.bytes and its dimensions — unchanged; only the computation is single-sourced."
        - "The MAX_INBOUND_TEXT_BYTES cap value — unchanged."
        - "The alloc-free utf8ByteLength arithmetic itself — kept (it is the correct walk-only form); the fix is to compute it ONCE and thread it, not to change the algorithm."
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-336: Adapter inbound — single-source UTF-8 byte length

## Context

Deep-review v5.5 (opus-47, `05-module-infochat-messaging-adapter.md` F3) found
that each inbound body is walked redundantly and allocated once per message.
**Verified at source 2026-06-14:** the same UTF-8 byte-length walk is
hand-copied in three places — `AdapterMetrics.utf8ByteLength`
(AdapterMetrics.java:244, full count, alloc-free), `InboundRouter.exceedsUtf8ByteLength`
(InboundRouter.java:1117, early-exit, alloc-free; AdapterMetrics.java:242 even
documents one as a copy of the other), and the codecs' inbound cap
`body.getBytes(StandardCharsets.UTF_8).length` (SignalMessageCodec.java:296;
SimpleXMessageCodec.java:360 DM and :471 group) — the codec form being a full
walk **plus** a `byte[]` allocation.

`AdapterMetrics`'s own javadoc boasts that "a hostile flood must not buy a
byte-array allocation per message" — a commitment honored in `utf8ByteLength` but
broken by the codecs' `getBytes(...).length`. The independent re-derivations are
also a drift risk in what "UTF-8 byte length" means at each enforcement point.

## Design (refined 2026-06-14 after clarity-fail)

Single-source the **algorithm**, not the **value**. The original design (see
`revisions[0]`) tried to compute the length once in the codec and thread the
`int` across the module boundary via `InboundMessage`; that fanned out to ~7
production and 12 provider-test construction sites — far past budget — and
polluted a core message record with a transport-metrics concern. Instead:

- New `Utf8` utility in the `app.zcat.infochat.messaging` package (the lower
  module, visible to both adapter and provider): `byteLength(String)` (full
  alloc-free walk, moved from `AdapterMetrics.utf8ByteLength`) and
  `exceedsByteLength(String, int)` (early-exit walk, moved from
  `InboundRouter.exceedsUtf8ByteLength`). One definition of the arithmetic, so
  no drift.
- Both codecs' inbound caps call `Utf8.exceedsByteLength(body, MAX_INBOUND_TEXT_BYTES)`
  — alloc-free and early-exiting at the adapter ingest boundary. Outbound caps
  unchanged.
- `AdapterMetrics` gains `messageBytes(adapter, Direction, int)`; the existing
  `String` overload stays for the outbound callers and delegates its internal
  walk to `Utf8.byteLength`.
- `InboundRouter.onMessage` computes `int len = Utf8.byteLength(raw)` once and
  feeds it to both the metric (`messageBytes(..., len)`) and the M1-038
  defense-in-depth size cap (`len > maxInboundBodyBytes`).
  `InboundRouter.exceedsUtf8ByteLength` becomes a thin delegate to
  `Utf8.exceedsByteLength` (preserving its existing boundary tests) or is
  removed with its tests moved to the `Utf8` test.

The two cap layers (adapter codec cap, provider defense-in-depth cap) remain
independent — only the per-walk arithmetic is shared.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.
