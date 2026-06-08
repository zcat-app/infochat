---
id: M1-184
title: "Signal reader/codec hardening against malformed frames"
status: done
created: 2026-06-07
last_updated: 2026-06-08
blocked_by:
  - M1-177
files_budget: 6
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodec.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  - infochat-provider/src/test/java/app/zcat/infochat/provider/source/FakeRelayServer.java
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - moving inbound dispatch off the reader thread — M1-177 (this ticket is sequenced after it precisely because both touch SignalJsonRpcClient's read path)
  - SimpleX codec exception messages — already remediated to fixed messages; only the Signal codec still interpolates
  - reconnect-after-subprocess-restart (M1-185) — this ticket keeps the reader alive across bad frames; M1-185 revives the transport after process death
  - InboundHandler dispatch semantics, group handlers, mention stripping (M1-187)
  - FakeNostrRelay (infochat-collector) — carries the same standalone-Vertx.vertx() hazard as FakeRelayServer but is not currently triggered (collector test ordering) and needs NIP-01 frame support a plain-socket rewrite must reproduce; follow-up ticket
  - M1-201's production readiness code (AdapterReadinessCheck, MessagingStartup, pom, application.properties) — the regression is fixture-side; no production change
acceptance:
  - "A structurally-malformed inbound frame (absent timestamp, wrong-typed params/envelope/timestamp fields) does not kill the reader loop: a named test pushes such frames followed by a valid frame and asserts the valid frame still delivers (today the typed-accessor phase throws NPE/CCE past handleLine's IllegalArgumentException-only catch, the reader loop catches only IOException, and the thread dies while the subprocess stays alive — the adapter goes permanently deaf with no restart trigger)"
  - "A frame missing a usable timestamp is dropped without throwing: a named codec test covers absent-in-both envelope/dataMessage and wrong-typed timestamp shapes (today SignalMessageCodec.extractDm calls getJsonNumber(...).longValueExact() unguarded)"
  - "Failures at the decode boundary keep D37 class-name-only logging: the named test asserts log output for a malformed frame carries neither the frame bytes nor the exception's message text"
  - "SignalMessageCodec exception messages no longer interpolate the raw line (today 'Malformed JSON-RPC envelope: ' + line and 'missing both method and id: ' + line) — a named test asserts the thrown message contains no frame content"
  - "FakeRelayServer no longer instantiates a standalone Vert.x: rewritten as a plain blocking ServerSocket WebSocket-handshake fixture (reads the upgrade request, answers 101 with the computed Sec-WebSocket-Accept), preserving its public surface (ctor, port(), uri(), close()) so UrlProbeRelayTest and AddSourceNostrProbeIT compile unchanged — fixes the M1-201 full-suite regression where a vanilla Vertx.vertx() HTTP server created after a @QuarkusTest boot in the same JVM misroutes the WS upgrade to its null requestHandler (ContextImpl.emit NPE) and the probe times out"
  - "the minimal regression repro exits 0: mvn -B -pl infochat-provider -am test -Dtest='QuarkusBootstrapTest,UrlProbeRelayTest' -Dsurefire.failIfNoSpecifiedTests=false"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/source/FakeRelayServer.java
  preserves:
    - all tests currently green on main (UrlProbeRelayTest is NOT green on main d913efb in full-suite runs — restoring it is this ticket's added acceptance, with unchanged assertions)
spec_refs:
  - docs/spec/security.md §User content in exceptions
decision_refs:
  - D37
clarity_check:
  date: 2026-06-08
  verdict: WARN
  warnings:
    - "TEST-CHANGES-AUTHORIZED: modifies: lists the same test directory as adds:; if any pre-existing test class is modified, an 'Authorized test changes' section must name each file and its new expected behavior — if only new files are added, the modifies: entry is an ambiguity artifact"
    - "SECURITY-FLAG-CONSISTENT: security_relevant: false is mis-claimed — the ticket hardens the 'no user content in exception messages' invariant (security.md §User content in exceptions, D37); flip to security_relevant: true"
  blockers: []
escalations:
  - date: 2026-06-08
    reason: loop
    reviewer_verdict_excerpt: |
      N/A — pre-review escalation. Four consecutive full-suite failures with one
      root cause: UrlProbeRelayTest.relayProbeReportsSuccessForReachablePolicyAllowedRelay
      times out (10s WS handshake vs in-process FakeRelayServer) in every full
      provider-suite run on main d913efb; passes standalone (0.5s) and in full
      verifies forked before M1-201 (ea400e1, 6f464e4 — 0.25s). M1-201 + M1-203
      were parallel tickets whose union was never co-tested before both squashed
      onto main. Blocks acceptance item 5 (mvn -B clean verify exits 0); the
      M1-184 diff (messaging-adapter signal codec/reader) is unimplicated.
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
      files: 8
      added: 629
      removed: 65
revisions:
  - date: 2026-06-08
    reason: loop-escalation refinement (user-directed — absorb the main-side regression fix into this ticket)
    summary: |
      - files_scope: added infochat-provider/src/test/java/app/zcat/infochat/provider/source/FakeRelayServer.java
        (test fixture causing the full-suite verify failure that blocks acceptance).
      - files_budget: 5 → 6 to match.
      - acceptance: added the FakeRelayServer plain-ServerSocket rewrite item and the
        deterministic pair-repro gate (QuarkusBootstrapTest then UrlProbeRelayTest);
        original four Signal items unchanged.
      - out_of_scope: added FakeNostrRelay (same hazard, latent, needs NIP-01 frames —
        follow-up ticket) and M1-201's production readiness code.
      - test_plan.modifies: now names FakeRelayServer.java (fixture internals only;
        UrlProbeRelayTest assertions unchanged) instead of duplicating the adds: dir —
        also resolves the clarity TEST-CHANGES-AUTHORIZED ambiguity warning.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-08
    verdict: CLEAN
    base: 7761af5^
    head: 7761af5
    verdict_file: docs/plan/m1/redteam/M1-184-2026-06-08.md
    out_of_model_count: 2
    note: |
      CLEAN — no findings. The Signal intake hardening (decode/extractDm
      totality, fixed-text exceptions, class-name-only logging) satisfies
      every threat-model commitment on the changed surface (input intake,
      Adapter→Provider trust boundary, D37, messaging-adapter DOS). Two
      OUT-OF-MODEL observations recorded, both in code this diff does NOT
      touch: the pre-existing unbounded inbound-line assembly in
      SignalJsonRpcClient (~520-537) is a candidate follow-up ticket if a
      cap on daemon line length is wanted; the FakeRelayServer loopback
      handshake read loops are test-fixture-only.
---

# M1-184: Signal reader/codec hardening against malformed frames

## Context

The Signal reader thread dies permanently on structurally-malformed frames:
`SignalMessageCodec` uses typed JSON accessors that throw NPE when the
timestamp is absent from both envelope and dataMessage
(`getJsonNumber(...).longValueExact()`) and CCE when fields are wrong-typed;
`handleLine` catches only `IllegalArgumentException` (SignalJsonRpcClient.java:478)
and `readerLoop` only `IOException` (:438). An escaping NPE/CCE kills the
reader while signal-cli stays alive — the adapter is deaf, and the
supervisor's restart machinery never triggers because the subprocess is
healthy. Separately (latent), the codec interpolates the raw frame line into
its exception messages (SignalMessageCodec.java:97, :111); today's catch
logs class-name-only per D37, but any future logger of `e.getMessage()`
would leak user-bearing frame content. Unified findings M3 (high) + M14
(latent low), `deep-code-review/v2/UNIFIED.md` §2.

## Acceptance

See frontmatter. The boundary guarantee: no inbound frame shape can kill the
reader; no exception message carries frame bytes.

## Out-of-scope

See frontmatter. Sequenced after M1-177 because both rework
SignalJsonRpcClient's read path — land the dispatch change first, then
harden the (smaller) post-change surface.

## Notes

- Source: `UNIFIED.md` §3 T8 under `deep-code-review/v2/` (kimi-folder msg
  F2 — the wider, verified framing; opus-48 msg F4's "DM-path unguarded" was
  imprecise: both onMessage dispatch paths are guarded, the typed-accessor
  phase is not).
- The existing handleLine comment documents the D37 rationale — keep that
  posture when widening the catch (catch RuntimeException at the handleLine
  boundary, log class name only).

## Addendum (2026-06-08 refinement): FakeRelayServer regression

Main (d913efb) fails every full provider-suite run:
`UrlProbeRelayTest.relayProbeReportsSuccessForReachablePolicyAllowedRelay`
times out at its 10s WS-handshake cap. Evidence: passes standalone (0.5s)
and in full verifies forked before M1-201 (ea400e1, 6f464e4); fails in
every run forked at d913efb (M1-184 ×4, M1-213, M1-215). Minimal repro is
the pair `QuarkusBootstrapTest` → `UrlProbeRelayTest` in one surefire JVM.

Mechanism: M1-201 added `quarkus-smallrye-health` → `quarkus-vertx-http`,
giving the provider's `@QuarkusTest` app an HTTP/Vert.x layer that stays up
for the whole surefire JVM. A vanilla `Vertx.vertx()` HTTP server created
*after* that boot (FakeRelayServer) misroutes the probe's WS upgrade to its
null `requestHandler` — `ContextImpl.emit` NPEs (`"task" is null`, via
`Http1xUpgradeToH2CHandler`), the handshake never answers, the probe times
out. Not TCCL-related (guard experiment failed identically). M1-201 and
M1-203 were parallel tickets; their union was first tested on main.

Fix direction (user-confirmed): drop Vert.x from the fixture — plain
blocking `ServerSocket` handshake responder (JDK ships no WS server; the
probe aborts right after the 101, so no frame support is needed), per the
project's virtual-threads + blocking-style stack posture.
