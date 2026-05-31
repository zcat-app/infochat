---
id: M1-101
title: "SSRF guard for wss:// relay connections"
status: done
created: 2026-05-26
last_updated: 2026-05-31
blocked_by:
  - M1-096
files_budget: 10
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrRelayConnection.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrSsrfIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrSsrfTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDedupIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceVerificationIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDegradationIT.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-provider/** — no provider changes
  - any change to IpBlocklist ranges — existing ranges are correct for wss:// too
  - any change to PinnedDnsResolver — reuse as-is
  - HTTP-shaped SSRF guard (already exists for Fetcher sources) — only adding wss:// WebSocket integration
  - relay pool management — M1-096 is frozen
  - signature verification — M1-097
acceptance:
  - "NostrRelayConnection runs DNS resolution through the infochat-ssrf IpBlocklist before opening a wss:// WebSocket connection — a relay whose hostname resolves to a blocked IP range (loopback, private, link-local, CGNAT, cloud-metadata) is refused"
  - "DNS is re-resolved on every reconnect — a relay that initially resolved to a public IP but later resolves to a private IP is refused on reconnect"
  - "A peer-IP change observed at the socket layer triggers a hard close of the WebSocket connection"
  - "NostrStreamSource takes an SsrfGuardedHttpClient via its constructor (mandatory, non-null) and propagates it to each NostrRelayConnection it constructs — production Registrar provides a default-strict instance; tests provide a LoopbackPermitting-backed instance so FakeNostrRelay (127.0.0.1) stays reachable"
  - "NostrSsrfTest.blockedIpRefused passes — a relay hostname resolving to 127.0.0.1 is refused before WebSocket handshake"
  - "NostrSsrfTest.reconnectReResolvesAndBlocks passes — a relay resolving to a public IP on first connect but a private IP on reconnect is refused on the second connect"
  - "NostrSsrfIT.peerIpChangeTriggersHardClose passes — a WebSocket connection whose peer IP changes mid-session is hard-closed"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrSsrfIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrSsrfTest.java
  modifies:
    - path: infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceTest.java
      reason: "NostrStreamSource gains a mandatory SsrfGuardedHttpClient constructor parameter; the test must pass a LoopbackPermitting-backed instance (same pattern as RssFetcherTest etc.) so FakeNostrRelay (127.0.0.1) stays reachable. Behavior of the existing assertions is preserved — only the constructor arity at the call sites changes."
    - path: infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceIT.java
      reason: "Same NostrStreamSource constructor change — pass a LoopbackPermitting-backed SsrfGuardedHttpClient. Assertion behavior preserved."
    - path: infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDedupIT.java
      reason: "Same NostrStreamSource constructor change — pass a LoopbackPermitting-backed SsrfGuardedHttpClient. Assertion behavior preserved."
    - path: infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceVerificationIT.java
      reason: "Same NostrStreamSource constructor change — pass a LoopbackPermitting-backed SsrfGuardedHttpClient. Assertion behavior preserved."
  preserves:
    - all tests currently green on main (except the four NostrStreamSource-suite files above, whose modifications are limited to constructor-arity adjustments per test_plan.modifies)
spec_refs:
  - docs/spec/security.md §SSRF and outbound connections
decision_refs:
  - D38
  - D20
reviews:
  - round: 1
    date: 2026-05-31
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 798
      removed: 33
  - round: 2
    date: 2026-05-31
    verdict: APPROVE
    note: "post-rebase re-review (not REWORK); branch rebased over M1-099 + M1-100 conflict set, must-shrink N/A per post-rebase sentinel"
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 840
      removed: 34
escalations:
  - date: 2026-05-31
    reason: budget-breach
    reviewer_verdict_excerpt: |
      Developer surfaced before any code was written: adding SSRF gating to
      NostrRelayConnection's connect path with strict default IpBlocklist
      refuses 127.0.0.1, breaking 4 existing FakeNostrRelay-based tests
      (NostrStreamSourceTest, NostrStreamSourceIT, NostrDedupIT,
      NostrStreamSourceVerificationIT — all use FakeNostrRelay which binds
      to 127.0.0.1). Engineering rules forbid backwards-compat shims (e.g.,
      a constructor overload that disables SSRF for the test path). The
      clean fix injects SsrfGuardedHttpClient through NostrStreamSource so
      each caller (production Registrar; tests) passes a fixture-appropriate
      instance — same pattern Fetcher tests already use. Requires touching
      NostrStreamSource.java and the 4 existing test files. Current
      files_budget: 4; needs ≥9.
revisions:
  - date: 2026-05-31
    reason: "post-merge rebase fallout — M1-099 + M1-100 landed on main between M1-101 commit and merge, both touching the Nostr files M1-101 also widens. Rebase resolved 7-file conflict set (NostrStreamSource + NostrRelayConnection + 4 existing FakeNostrRelay test files + STATUS.md regen). Two consequences forced into files_scope: (1) NostrRelayConnection's constructor grew a 10th arg (RelayHealthTracker from M1-099) so the M1-101 NostrSsrfIT / NostrSsrfTest construction sites needed the new arg; (2) M1-099 added NostrDegradationIT which constructs NostrStreamSource and so needed M1-101's new ssrfClient arg + LoopbackPermittingBlocklist helper (same pattern as the other 4 FakeNostrRelay-based test files M1-101 modified at refine time). Widen files_scope to add NostrDegradationIT.java; bump files_budget 9 → 10."
    prior_values: |
      files_budget (pre-rebase): 9
      files_scope (pre-rebase): 9 entries (no NostrDegradationIT.java)
  - date: 2026-05-31
    reason: "budget-breach refine — files_budget=4 is insufficient to satisfy both production SSRF gating AND test_plan.preserves. NostrStreamSource is the only constructor of NostrRelayConnection; injecting SsrfGuardedHttpClient through it is the only path that keeps existing FakeNostrRelay-based tests green without a backwards-compat shim. Widen files_scope to add NostrStreamSource.java + 4 existing test files (test_plan.modifies); bump files_budget 4 → 9. Re-bump risk: low → medium per the second clarity WARN (security_relevant + new transport wiring)."
    prior_values: |
      files_budget (pre-refine): 4
      files_scope (pre-refine): 4 entries
        - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrRelayConnection.java
        - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java
        - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrSsrfIT.java
        - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrSsrfTest.java
      risk (pre-refine): low
      test_plan.modifies (pre-refine): absent
      Notes §Integration shape (pre-refine): "options (a) extract SsrfDnsChecker / (b) add checkAndPin to SsrfGuardedHttpClient; either acceptable"
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-05-31
  verdict: WARN
  warnings:
    - "FILES-BUDGET-PLAUSIBLE: Notes section offers two implementation options; option (a) (extract SsrfDnsChecker) would produce a 5th file not in files_scope, exceeding files_budget of 4. Either constrain to option (b) or increase budget/scope for option (a)."
    - "COMPLEXITY-RISK-CALIBRATED: risk: low is slightly under-calibrated for a security_relevant ticket that wires a new transport path into the SSRF guard. Consider risk: medium."
  blockers: []
---

# M1-101: SSRF guard for wss:// relay connections

## Context

`security.md` §SSRF: "DNS-rebind defenses are transport-agnostic — a
wss:// relay connection is gated by the same checks as an https:// feed
fetch." And: "For long-lived StreamSource connections the IP check
applies on every reconnect, and any peer-IP change observed at the
socket layer is a hard close."

The `infochat-ssrf` module (`SsrfGuardedHttpClient`, `IpBlocklist`,
`PinnedDnsResolver`) already exists. This ticket wires it into the
Nostr relay connection path.

## Acceptance

See frontmatter.

## Out-of-scope

- IpBlocklist ranges — already correct.
- HTTP SSRF guard — already exists.
- Relay pool management — M1-096.

## Notes

- **Integration shape.** The existing `SsrfGuardedHttpClient` is
  HTTP-focused (`get(URI)`). For WebSocket, the SSRF guard needs to
  run before the `java.net.http.WebSocket.Builder.buildAsync()` call.
  Pick option (b) from the original draft: add a `checkAndPinForWebSocket(URI)`
  method to `SsrfGuardedHttpClient` returning an `AutoCloseable` `PinnedDial`
  handle (exposes the validated `addresses()` and releases the JVM-wide
  pin slot + lock on `close()`). Caller dials the WebSocket inside a
  try-with-resources block. Option (a) was rejected because it would
  produce a 5th production file (`SsrfDnsChecker`) and force an extra
  refactor of the existing `SsrfGuardedHttpClient.get()` pipeline.
- **Wiring through NostrStreamSource.** `NostrStreamSource` is the
  only producer of `NostrRelayConnection`. The `SsrfGuardedHttpClient`
  must be **injected through `NostrStreamSource`'s constructor**
  (mandatory, non-null) rather than instantiated inside
  `NostrRelayConnection` with a hard-coded strict blocklist — the
  hard-coded variant would refuse loopback and break every existing
  `FakeNostrRelay`-based test (which all bind to 127.0.0.1). Production
  `Registrar` provides a default-strict instance; tests provide a
  `LoopbackPermitting`-backed instance (same pattern as the Fetcher
  tests in `RssFetcherTest`, `NitterFetcherTest`, etc.). This is why
  `files_scope` widens to include `NostrStreamSource.java` and the
  four existing FakeNostrRelay-based test files — the change is purely
  the new constructor arg propagated through each call site, no
  assertion changes.
- **Peer-IP change detection.** The JDK WebSocket API does not expose
  the peer IP directly. Implementation: resolve DNS via
  `SsrfGuardedHttpClient.checkAndPinForWebSocket(URI)` before connect,
  remember the pinned address set, and run a periodic re-resolve on a
  configurable interval (default 60s, test override shorter) using
  a `resolveForWebSocket(URI)` method that runs the same DNS +
  IpBlocklist check without pinning. If the re-resolved address set
  shares no element with the pinned set OR the re-resolve throws
  `SsrfPolicyException`, abort the WebSocket (hard close). This
  satisfies both "on every reconnect" (each reconnect re-enters
  `checkAndPinForWebSocket`) and "mid-session peer-IP change is a
  hard close" (the watcher fires while the connection is alive).
- **Scheme allowlist.** The existing `SsrfGuardedHttpClient.get()`
  pipeline allows `{http, https}`. The new `checkAndPinForWebSocket`
  / `resolveForWebSocket` methods use the allowlist `{ws, wss}`.
  Refactor `resolveAndValidate` to take the allowed scheme set as
  a parameter; call sites pass the appropriate set.
