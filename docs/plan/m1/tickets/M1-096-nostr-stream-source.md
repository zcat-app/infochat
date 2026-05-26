---
id: M1-096
title: "NostrStreamSource — JDK WebSocket relay pool"
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by:
  - M1-095
files_budget: 12
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrRelayConnection.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrMessage.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrEvent.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrRelayConnectionTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/FakeNostrRelay.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-core/** — StreamSource SPI is not modified
  - infochat-provider/** — no provider changes
  - Signature verification — M1-097
  - Cross-relay dedup — M1-098
  - Per-relay degradation — M1-099
  - Kind-6 cross-source linking — M1-100
  - SSRF on wss:// — M1-101 (this ticket connects without SSRF guard; M1-101 wires it)
  - NIP-42 authentication, NIP-65 relay discovery, publishing — explicitly out of v1
  - any change to FetchScheduler or polled Fetcher infrastructure
acceptance:
  - "NostrStreamSource implements StreamSource and registers with StreamSourceSupervisor at Collector startup for each source with kind='nostr'"
  - "NostrStreamSource connects to each relay in the operator-configured relay list using java.net.http.WebSocket"
  - "NostrStreamSource sends a NIP-01 REQ message with the source's filter spec (kinds, authors, etc.) on each relay connection"
  - "NostrStreamSource receives EVENT messages, parses the JSON into NostrEvent (id, pubkey, created_at, kind, tags, content, sig), and delivers to the outbox via the StreamSource deliver callback"
  - "On reconnect, NostrStreamSource sends REQ with since=last_persisted_event_at per relay so relays that support since filters replay missed events"
  - "Reconnect uses exponential backoff with jitter — no tight-loop reconnect storm"
  - "NostrStreamSource.stop() closes all WebSocket connections and flushes in-flight events per the drain protocol"
  - "NostrEvent carries upstream_identifier = the Nostr event id (SHA-256 hash of the canonical event JSON)"
  - "NostrStreamSourceTest.connectsToAllConfiguredRelays passes — a test with 3 fake relays verifies all 3 receive REQ messages"
  - "NostrStreamSourceTest.receivesAndDeliversEvents passes — a fake relay sends EVENT messages; the deliver callback receives parsed NostrEvents"
  - "NostrStreamSourceTest.reconnectsWithSinceOnDisconnect passes — after a disconnect, reconnect sends REQ with since=last_persisted_event_at"
  - "NostrStreamSourceTest.stopDrainsAndClosesConnections passes — stop() closes WebSocket connections and the deliver callback receives any buffered events"
  - "NostrRelayConnectionTest.parsesNip01Messages passes — REQ, EVENT, EOSE, NOTICE message parsing and serialization"
  - "NostrStreamSourceIT.endToEndWithFakeRelay passes — a QuarkusTest with a FakeNostrRelay WebSocket server verifies events flow through to the outbox"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrRelayConnectionTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/FakeNostrRelay.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
  - docs/spec/security.md §Per-source trust boundaries
decision_refs:
  - D38
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-096: NostrStreamSource — JDK WebSocket relay pool

## Context

This is the core Nostr ingest implementation. Uses the JDK
`java.net.http.WebSocket` client (decided 2026-05-26: zero-dependency,
sufficient for NIP-01 subscribe/receive). Registers with the
StreamSourceSupervisor (M1-095) and maintains a pool of WebSocket
connections to operator-configured relays.

`security_relevant: true` — this is a network-facing component that
receives untrusted data from external relays. Signature verification
(M1-097) and SSRF protection (M1-101) are separate tickets that layer
on top; this ticket establishes the connection and message pipeline.

## Acceptance

See frontmatter. The core relay pool: connect, subscribe (REQ),
receive events (EVENT), handle EOSE, reconnect with `since`, drain
on stop.

## Out-of-scope

- **Signature verification** — M1-097 adds it before outbox delivery.
- **Cross-relay dedup** — M1-098 adds it before outbox delivery.
- **Per-relay degradation** — M1-099 adds cooldown/cycle-cap logic.
- **SSRF guard** — M1-101 wires the infochat-ssrf module for wss://
  connections. This ticket connects without SSRF guard.
- **Kind-6 linking** — M1-100.
- **NIP-42, NIP-65, publishing** — explicitly out of v1 per D38.

## Notes

- **NIP-01 message format.** Messages are JSON arrays:
  `["REQ", subscription_id, filter...]`,
  `["EVENT", subscription_id, event_object]`,
  `["EOSE", subscription_id]`,
  `["NOTICE", message]`. The implementation only needs to send REQ
  and receive EVENT/EOSE/NOTICE.
- **FakeNostrRelay.** A test-only WebSocket server that speaks NIP-01.
  It accepts REQ, holds the subscription, and can be told to send
  EVENT messages. This is the test harness for all Nostr tickets —
  M1-097 through M1-101 reuse it.
- **Filter spec from source config.** The bootstrap-sources.json
  Nostr entry carries a filter spec (kinds, authors) in its `config`
  JSON. The filter is sent as-is in the REQ message. The identifier
  is the canonicalized filter spec per D38.
- **Last-persisted cursor.** The `since` timestamp for reconnect is
  the latest `created_at` from events successfully delivered to the
  outbox for this source. The cursor is per-relay if the relay list
  changes, but v1 simplifies to per-source (D38: config mutation is
  restart-only).
- **Design reference:** `docs/design/01-architecture.md` §1.3
  (StreamSource flow).
