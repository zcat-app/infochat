---
id: M1-359
title: "messaging: OutboundRateLimiter sub-millisecond cap rounding + charge a token per wire frame on the Signal edit-failure fallback"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 6
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/OutboundRateLimiter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  - docs/design/06-messaging.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The production adapter caps (SimpleX=5, Signal=5) — exact under integer division today; the rounding fix preserves their behaviour and corrects only caps > 1000/s (e.g. the InMemory test adapter's 10000).
  - The SimpleX token-per-chunk accounting — already correct (transmitChunk draws a token); this aligns Signal to it.
  - The burst-credit / nextFreeMillis warm-start semantics — unchanged.
acceptance:
  - "OutboundRateLimiter rejects a non-positive maxSendsPerSecond and computes perTokenMillis without the integer-division floor that collapses any cap > 1000/s to 1 ms/token (e.g. Math.ceil(1000.0/cap) as a long, or nanosecond-internal accounting), so a 10000/s cap is no longer silently throttled to 1000/s."
  - "A test pins that perTokenMillis (or the achieved pacing) for a cap of 10000 reflects ~10000/s rather than 1000/s, and that caps of 5 are unchanged."
  - "The Signal edit-failure fresh-send fallback (SignalJsonRpcClient.fallbackSend) draws an OutboundRateLimiter token for its extra wire frame, so a sequence where every update falls back cannot transmit at 2x maxSendsPerSecond; the 'one token per wire frame' contract (design §6.3.6) holds for Signal as it already does for SimpleX. Token ownership is moved/threaded so the charge happens at the frame boundary, not relying on call-site discipline in SignalAdapter."
  - "A test pins that a fallen-back update consumes two tokens (placeholder send + fallback send) rather than one."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging (rate-limiter rounding test)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal (fallback token-charge test)
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

# M1-359: rate-limiter rounding + Signal fallback token

## Context

Two deep-review v6 findings on the messaging outbound pacer, grouped (same
`OutboundRateLimiter` contract):

- **opus-47 `05-module-infochat-messaging-adapter.md` F4** (low, PERFORMANCE) —
  `Math.max(1L, 1_000L / maxSendsPerSecond)` integer-divides to 1 ms/token for
  any cap > 1000/s. **Verified 2026-06-14:** `OutboundRateLimiter.java:51`; the
  InMemory adapter declares `maxSendsPerSecond = 10_000`
  (`InMemoryAdapter.java:67`), so it actually paces at 1000/s. Production caps
  are 5 (exact), so the bug is currently test-adapter-only — but it breaks the
  documented `CapabilityFlags.maxSendsPerSecond` contract.
- **opus-48 `05-module-infochat-messaging-adapter.md` F1** (low, PERFORMANCE) —
  the Signal edit-failure fresh-send fallback transmits a second frame without
  drawing a token, unlike the SimpleX fallback (which paces inside
  `transmitChunk`). **Verified per report:** `SignalAdapter` draws one token per
  SPI call; `SignalJsonRpcClient.fallbackSend` calls `call(...)` directly. Under
  repeated fallback, throughput can reach 2x the cap.

Both are low blast-radius but both are drift from a stated uniform contract;
fixing them together keeps the pacer's "one token per frame, accurate rate"
invariant in one place.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- Charging at the frame boundary (Option A) makes the contract structurally true
  regardless of how many frames one SPI call expands into; SimpleX already shows
  the shape.
