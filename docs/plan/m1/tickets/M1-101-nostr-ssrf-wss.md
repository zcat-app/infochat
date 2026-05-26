---
id: M1-101
title: "SSRF guard for wss:// relay connections"
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by:
  - M1-096
files_budget: 4
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrRelayConnection.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrSsrfIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrSsrfTest.java
complexity: low
risk: low
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
  - "NostrSsrfTest.blockedIpRefused passes — a relay hostname resolving to 127.0.0.1 is refused before WebSocket handshake"
  - "NostrSsrfTest.reconnectReResolvesAndBlocks passes — a relay resolving to a public IP on first connect but a private IP on reconnect is refused on the second connect"
  - "NostrSsrfIT.peerIpChangeTriggersHardClose passes — a WebSocket connection whose peer IP changes mid-session is hard-closed"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrSsrfIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrSsrfTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §SSRF and outbound connections
decision_refs:
  - D38
  - D20
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
  Options: (a) extract the DNS-resolve + IP-check logic into a
  reusable `SsrfDnsChecker` that both `SsrfGuardedHttpClient` and
  `NostrRelayConnection` consume; (b) add a
  `checkAndPin(URI)` method to `SsrfGuardedHttpClient` that runs
  the pre-connect pipeline without issuing an HTTP request.
  Either shape is acceptable; the implementer picks.
- **Peer-IP change detection.** The JDK WebSocket API does not expose
  the peer IP directly. Options: (a) resolve DNS before connect, pin
  the IP, and on reconnect re-resolve and compare; (b) use a socket
  interceptor. Option (a) is simpler and matches the spec's "on every
  reconnect" wording. The "mid-session" peer-IP change detection
  (for persistent connections) may require a periodic DNS re-check
  or rely on the reconnect path.
- **Scheme allowlist.** The SSRF guard already allows `ws` and `wss`
  per security.md.
