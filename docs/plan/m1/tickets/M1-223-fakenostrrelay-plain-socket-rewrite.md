---
id: M1-223
title: "FakeNostrRelay: plain-socket rewrite, drop standalone Vert.x"
status: pending
created: 2026-06-08
last_updated: 2026-06-08
blocked_by: []
files_budget: 1
files_scope:
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/FakeNostrRelay.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - production NostrStreamSource and any NIP-01 client code — this ticket touches the test fixture only
  - the seven Nostr consumer test classes (NostrStreamSourceIT, NostrStreamSourceVerificationIT, NostrDedupIT, NostrDegradationIT, NostrSsrfIT, NostrSsrfTest, NostrStreamSourceTest) — they must compile and pass with UNCHANGED assertions; do not retune any of them to the new fixture
  - FakeRelayServer (infochat-provider) — already rewritten to a plain ServerSocket fixture in M1-184; this ticket is the collector-side counterpart
  - collector test ordering / any @QuarkusTest boot sequence change — the hazard is latent today precisely because no vertx-http app boots before the Nostr ITs; this ticket removes the fixture's framework dependency, it does not touch what triggers the hazard
acceptance:
  - "FakeNostrRelay no longer imports or instantiates Vert.x (io.vertx.*): rewritten as a plain blocking ServerSocket WebSocket server on virtual threads that performs the RFC 6455 opening handshake, READS masked client text frames (unmasking per §5.3) to record inbound REQ frames, and WRITES unmasked server text frames for EVENT/EOSE pushes"
  - "FakeNostrRelay preserves its package-private surface exactly — FakeNostrRelay(), uri(), receivedFrames(), liveConnectionCount(), sendEvent(NostrEvent), sendEose(), disconnectClients(), awaitFrameCount(int, Duration), awaitConnectionCount(int, Duration), close() — with identical signatures and semantics, so all seven consumer test classes compile unchanged"
  - "mvn -pl infochat-collector -am verify is green: all seven Nostr IT/test classes (NostrStreamSourceIT, NostrStreamSourceVerificationIT, NostrDedupIT, NostrDegradationIT, NostrSsrfIT, NostrSsrfTest, NostrStreamSourceTest) pass with their existing assertions"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/FakeNostrRelay.java
  preserves:
    - all tests currently green on main, including the seven Nostr consumer classes (assertions unchanged — only the fixture's transport implementation changes)
spec_refs:
  - docs/spec/verification.md §Test layers
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-223: FakeNostrRelay: plain-socket rewrite, drop standalone Vert.x

## Context

`FakeNostrRelay` (the collector-side NIP-01 test relay shared by every
Nostr ticket) is backed by a standalone `Vertx.vertx()` HTTP/WebSocket
server. This carries the same hazard M1-184 fixed in the provider-side
`FakeRelayServer`: a vanilla `Vertx.vertx()` server created in a JVM
where a Quarkus application has already booted its own Vert.x (with
`vertx-http`) misroutes WebSocket upgrades to its null request handler,
so the handshake never answers and the dialing client times out. On the
provider side, M1-201 pulled `quarkus-vertx-http` into the `@QuarkusTest`
app and the latent hazard became a hard, deterministic full-suite
failure. On the collector side the hazard is **latent today** — current
collector test ordering does not boot a `vertx-http` `@QuarkusTest`
before the Nostr integration tests — but any future collector health
check or HTTP layer (the exact change M1-201 made to the provider) would
trip it the same way. This ticket removes the framework dependency
proactively, mirroring the M1-184 fix, so the fixture behaves identically
regardless of what else booted in the test JVM. Contract:
`docs/spec/verification.md` §Test layers (the integration-test layer the
Nostr ITs belong to).

## Acceptance

See frontmatter. The fixture must drop Vert.x entirely and speak RFC 6455
itself over a plain blocking `ServerSocket` on virtual threads, in BOTH
directions (read+unmask inbound client REQ frames; write outbound EVENT/
EOSE frames), preserving its exact package-private surface so the seven
consumer test classes compile and pass with unchanged assertions.

## Out-of-scope

See frontmatter. Unlike M1-184's `FakeRelayServer` — where the probe
under test aborts right after the 101, so a handshake-only responder
sufficed — `FakeNostrRelay` is bidirectional: the subscriber sends REQ
frames the fixture must record, and tests push EVENT/EOSE frames the
subscriber must receive. The rewrite therefore needs a real (minimal)
RFC 6455 frame codec, not just a handshake. The seven consumer classes
are named in `out_of_scope` precisely so their assertions are not
retuned to paper over a framing bug — a correct rewrite leaves them
untouched and green.

## Notes

- Template: `infochat-provider/src/test/java/app/zcat/infochat/provider/source/FakeRelayServer.java` (the M1-184 fix) for the handshake half (handshake parse, `Sec-WebSocket-Accept` = base64(SHA1(key + RFC 6455 GUID)), virtual-thread accept loop). This ticket adds the frame-codec half that `FakeRelayServer` did not need.
- RFC 6455 framing scope is deliberately minimal: single-frame (FIN=1) text messages, payload-length 7-bit / 16-bit / 64-bit forms, client→server frames are masked (must XOR-unmask on read), server→client frames are unmasked. No continuation/fragmentation, no compression, no ping/pong required for the current consumers — but `close()`/`disconnectClients()` should still close the socket cleanly.
- The production client is the JDK `java.net.http.WebSocket` in `NostrStreamSource`; it is unchanged and is the conformance oracle — if the fixture's framing is wrong, the real client's frames won't parse and the consumer ITs fail.
- Alternatives considered: keeping Vert.x but binding the fixture to the test's own `Vertx` instance — rejected, same fragility class M1-184 already ruled out (the standalone-instance routing bug is the root cause, not which `Vertx` is used).

## Pre-flight self-check (author-side)

Run `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-223-*.md`
before `/m1-tick start M1-223`.
