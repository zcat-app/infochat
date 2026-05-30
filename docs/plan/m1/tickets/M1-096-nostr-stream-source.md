---
id: M1-096
title: "NostrStreamSource — JDK WebSocket relay pool"
status: done
created: 2026-05-26
last_updated: 2026-05-30
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
reviews:
  - round: 1
    date: 2026-05-30
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 11
      added: 1339
      removed: 13
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-05-30
    category: INJECTION
    severity: critical
    promise: |
      docs/spec/security.md §Per-source trust boundaries / Nostr: signature
      verification is mandatory before Stage 1; failed verification → drop;
      ordering at the StreamSource trust boundary is "signature verification
      → kind allowlist → outbox write".
    gap: |
      NostrStreamSource.java:132-141 (deliverOne) hands NormalizedPost to
      the outbox with no sig/pubkey check. NostrRelayConnection.handleFrame
      (NostrRelayConnection.java:170-179) routes every parsed Event to the
      sink unverified. Registrar (@Startup @Priority(460), NostrStreamSource
      .java:154-207) wires the live pipeline on Collector boot. Out-of-scope
      to M1-097, but the live wiring lands now.
    repro: |
      Operator-configured nostr source pointing at attacker relay; relay
      serves EVENT with arbitrary content and victim-pubkey + garbage sig;
      Collector writes RAW post with attacker-chosen identifier/content;
      becomes user-visible after Stage 1/2 BENIGN, attributed to victim
      pubkey. Spec promises relay is "not a trust anchor", pubkey is.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-05-30
    category: INJECTION
    severity: high
    promise: |
      docs/spec/security.md §Per-source trust boundaries / Nostr: kind
      allowlist limits v1 to kinds 1 and 6; "kind 4 (DMs), kind 7
      (reactions), and any encrypted-content NIPs are dropped without
      parsing"; disallowed-kind events "never reach the outbox".
    gap: |
      No kind filter anywhere in the diff. NostrStreamSource.deliverOne
      ignores event.kind(); NostrEvent.toNormalizedPost maps every event
      regardless of kind; NostrRelayConnection.handleFrame does not gate
      on kind. Kind 4 (encrypted DMs), kind 5 (deletion), kind 7
      (reactions), and any future/encrypted kind all persist as posts.
    repro: |
      Hostile or ambient relay delivers EVENT with kind=4 and
      encrypted-DM-ciphertext content; Collector writes ciphertext as a
      post body, which flows into Stage 1/2 and the LLM pipeline.
    suggested_fix_class: input-sanitization
  - date: 2026-05-30
    category: INJECTION
    severity: high
    promise: |
      docs/spec/security.md §SSRF and outbound connections: every outbound
      connection from Collector (including StreamSource wss://) goes through
      a fail-closed allowlist; IP-blocklist and DNS-rebind defenses are
      transport-agnostic; "any peer-IP change observed at the socket layer
      is a hard close" for long-lived StreamSource connections.
    gap: |
      NostrRelayConnection.java:152-160 (connectAndSubscribe) dials with
      a plain httpClient.newWebSocketBuilder().buildAsync(relayUri,...) —
      no allowlist, no DNS-resolve-and-check, no peer-IP-change detection
      on reconnect. Registrar.parseRelays (NostrStreamSource.java:228-247)
      accepts any URI.create-parseable string as a relay URL. Deferred to
      M1-101, but the live dial-out wiring lands now.
    repro: |
      Operator-configured nostr source whose config.relays contains
      ws://169.254.169.254/latest/meta-data/... or ws://localhost:5432/ or
      an internal-admin URL; Collector dials these on boot, probing/
      leaking presence to internal infrastructure and (for metadata
      endpoints) potentially disclosing cloud-IAM credentials. DNS-rebind
      attack on subsequent reconnect re-resolves to attacker-chosen IP.
    suggested_fix_class: missing-auth-check
  - date: 2026-05-30
    category: DOS
    severity: high
    promise: |
      docs/spec/security.md threat model: "Resource exhaustion, unbounded
      loops" (DOS); §SSRF/outbound: "Redirect, body-size, connect-timeout,
      and read-timeout caps are enforced; an unset timeout is a
      configuration error"; §Per-source trust boundaries: relays are
      untrusted and "a hostile or buggy relay can produce many" events.
    gap: |
      Two unbounded buffers downstream of an untrusted relay:
      (a) NostrStreamSource.java:68 — inbound queue is an unbounded
      LinkedBlockingQueue; offer() never rejects.
      (b) NostrRelayConnection.RelayListener.onText accumulates fragments
      into a StringBuilder with no size cap until last=true.
      Additionally, WebSocket has only a connect timeout — no read
      timeout, so a silent socket is held forever.
    repro: |
      (a) Hostile relay floods small EVENT frames faster than PostPersister
      can drain → inbound grows unboundedly → OOM.
      (b) Hostile relay streams a single text frame in 1KB fragments and
      never sends last=true → buffer grows to GBs → OOM. Either crash
      kills ingest for every source on the Collector.
    suggested_fix_class: rate-limit
  - date: 2026-05-30
    category: INFO-LEAK
    severity: medium
    promise: |
      docs/spec/security.md §Secrets handling / User content in exceptions:
      "Exception messages and stack traces emitted via the application
      logger MUST NOT contain user-authored prose ... The application
      provides a SafeLog utility ... The original Throwable is never
      passed to the underlying SLF4J logger."
    gap: |
      NostrStreamSource.java:139 — LOG.warnf(e, "Nostr outbox delivery
      failed for event %s", event.id()) passes raw Throwable to the logger.
      Same shape at NostrStreamSource.java:187, :243, :265 and at
      NostrRelayConnection's connect-failure path. SafeLog at
      infochat-core/src/main/java/app/zcat/infochat/core/log/SafeLog.java
      exists and was not used.
    repro: |
      Relay delivers EVENT whose content carries a secret-shaped substring;
      PostPersister.persist throws a wrapping SQLException whose message
      echoes the bound parameter; full stack trace including the post body
      lands in operator stdout, bypassing SafeLog redaction.
    suggested_fix_class: audit-log-coverage
redteam_audits:
  - date: 2026-05-30
    verdict: FINDINGS
    base: main
    head: 895b6bd
    verdict_file: docs/plan/m1/redteam/M1-096-2026-05-30.md
    findings_count: 5
    out_of_model_count: 2
    note: |
      Post-commit pre-merge audit. Findings 4 (DOS bounded inbound queue +
      bounded fragment buffer) and 5 (INFO-LEAK SafeLog at 5 sites) fixed
      inline on this branch in a follow-up commit (squash-merged into
      M1-096 on main). Finding 4(c) read-timeout / silent-socket: not
      implemented (silent socket holds bounded resources). Findings 1, 2,
      3 deferred to M1-097 (sig verification + kind allowlist) and M1-101
      (SSRF wss:// guard) — both sibling tickets remain pending. Full
      disposition in docs/plan/m1/redteam/M1-096-2026-05-30.md.
outline_file: target/m1-tick-outline-M1-096.md
clarity_check:
  date: 2026-05-30
  verdict: WARN
  warnings:
    - 'Acceptance item 6 (exponential backoff with jitter) has no named test verifying the backoff timing; checkable only by code inspection. Add NostrStreamSourceTest.reconnectsWithExponentialBackoff or accept as code-review-only invariant.'
  blockers: []
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
