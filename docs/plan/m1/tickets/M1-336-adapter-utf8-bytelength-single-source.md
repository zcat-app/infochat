---
id: M1-336
title: "Adapter inbound: single-source UTF-8 byte length across codec cap + metric"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 6
files_scope:
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
  - The metric name adapter.message.bytes and its dimensions — unchanged; only the computation is single-sourced.
  - The MAX_INBOUND_TEXT_BYTES cap value — unchanged.
  - The alloc-free utf8ByteLength arithmetic itself — kept (it is the correct walk-only form); the fix is to compute it ONCE and thread it, not to change the algorithm.
acceptance:
  - "The inbound UTF-8 byte length is computed once per message and shared between the codec's inbound-cap check and the AdapterMetrics size summary, instead of being computed twice (codec: body.getBytes(UTF_8).length, which both allocates a byte[] AND walks the string; AdapterMetrics.utf8ByteLength: a second full walk). The codec computes the walk-only length once (no byte[] allocation), enforces the cap against it, surfaces it on the decoded result, and AdapterMetrics gains an overload that records a precomputed int length."
  - "The codec's inbound-cap check no longer calls body.getBytes(StandardCharsets.UTF_8).length (the allocation the AdapterMetrics javadoc's 'a hostile flood must not buy a byte-array allocation per message' commitment is broken by today): both the Signal and SimpleX codecs use the alloc-free walk for the cap, and the two enforcement points (cap + metric) read the same value so they cannot drift in interpretation of 'UTF-8 byte length'."
  - "A test pins single-sourcing: a boundary-length body (just over MAX_INBOUND_TEXT_BYTES) is rejected by the cap using the walk-only length, and the recorded adapter.message.bytes value equals that same length (not a re-walked or re-allocated recomputation). Existing inbound cap + metric tests stay green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging (single-source byte-length case)
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging (forward precomputed length)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-336: Adapter inbound — single-source UTF-8 byte length

## Context

Deep-review v5.5 (opus-47, `05-module-infochat-messaging-adapter.md` F3) found
that each inbound body is walked twice and allocated once per message.
**Verified at source 2026-06-14:** `AdapterMetrics.utf8ByteLength`
(AdapterMetrics.java:243-260) walks the body alloc-free, while the codec's
inbound-cap check does `body.getBytes(StandardCharsets.UTF_8).length`
(SignalMessageCodec.java:296) — a second full walk **plus** a `byte[]`
allocation. For a 16 KiB body that is ~32K character iterations plus an
allocation per inbound, on a hot path the dispatch thread already serializes.

`AdapterMetrics`'s own javadoc boasts that "a hostile flood must not buy a
byte-array allocation per message" — a commitment honored in `utf8ByteLength` but
broken by the codec's `getBytes(...).length`. The two are worse together than
either alone, and they independently re-derive the same value (a drift risk in
what "UTF-8 byte length" means at the cap vs the metric).

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Compute the walk-only length once in the codec, surface it on the decoded
  result record (e.g. add a field to `ReceivedDm`), enforce the cap against it,
  and add an `AdapterMetrics.messageBytes(adapter, direction, int utf8ByteLength)`
  overload. The Provider-side `InboundRouter` emission site forwards the
  precomputed length. Apply symmetrically to the SimpleX codec.
