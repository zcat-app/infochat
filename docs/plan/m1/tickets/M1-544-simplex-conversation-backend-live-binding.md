---
id: M1-544
title: SimpleX live ConversationBackend binding (Phase 4b-2)
status: done
created: 2026-07-02
last_updated: 2026-07-02
blocked_by:
  - M1-543
files_budget: 4
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - GROUP-scope binding (Phase 4b-3 — needs bot+client group membership
    fixtures; a group step must fail loudly, not silently pass)
  - progress-notified reply observation (item-edit finalize bodies — Phase
    4b-4 concern; short-command plain replies only for now)
  - the Signal adapter and any Signal backend
  - any main-scope (production) code change — this ticket is test-scope only
  - modifying SimpleXAdapter / SimpleXWebSocketClient / SimpleXMessageCodec /
    SimpleXSubprocess themselves (the binding composes them, never edits them)
acceptance:
  - SimpleXConversationBackend implements the ConversationBackend SPI, binding
    scenario DM contact tokens to live host-side simplex-chat client
    identities; a GROUP step throws UnsupportedOperationException naming
    Phase 4b-3.
  - LiveSimpleXClient composes the production transport pieces
    (SimpleXSubprocess, SimpleXWebSocketClient, SimpleXMessageCodec) for
    message send/receive — one wire-shape source of truth, no forked encoder
    (D-live-9). The only side-channel is the /contacts fixture query on a
    short-lived side WebSocket (the production client resolves corrId futures
    only for send acks, by design).
  - LiveSimpleXRoundTripIT is gated on -Dinfochat.live.simplex=true and is
    SKIPPED (not failed, not run) without the flag, so mvn verify stays
    hermetic on any machine.
  - mvn verify is green.
test_plan:
  adds:
    - LiveSimpleXRoundTripIT (live-gated; skipped in CI; on the host it
      drives a /help scenario through ScenarioRunner over the real transport)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
decision_refs:
  - D-live-9
reviews:
  - round: 1
    date: 2026-07-02
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 425
      removed: 8
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-07-02
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-544: SimpleX live ConversationBackend binding (Phase 4b-2)

## Context

Live-e2e Phase 4b-2 (`docs/plan/live-e2e/HANDOFF.md` §Next actions): bind the
M1-539 scenario-runner substrate to real SimpleX. The binding drives a
host-side simplex-chat client identity (LiveAdmin/LiveUser under
`prod/runtime/simplex-clients/`) against the deployed bot: sends go through
the production `SimpleXMessageCodec.encodeSendCommand` +
`SimpleXWebSocketClient.sendCommand` (corrId/ack), replies arrive as async
inbound events decoded by the same codec. Per **D-live-9** the backend is
**host-validated, not fake-backed**: the CI-checkable acceptance is
compilation + the skip gate; the live round-trip itself runs only on the host
with the stack up.

**Host-validation evidence (already run, 2026-07-02):**
`mvn -pl infochat-provider test -Dtest=LiveSimpleXRoundTripIT
-Dinfochat.live.simplex=true` → GREEN; `live step 1: matched in 591 ms` —
`/help` from the claimed LiveAdmin through real SMP relays to the deployed
bot, reply matched `Available commands:` by the unmodified `ScenarioRunner`.
Without the flag: `Tests run: 1, Skipped: 1`, green.

## Notes

- `LiveSimpleXClient` lives in package
  `app.zcat.infochat.messaging.impl.simplex` (provider test sources) because
  the three transport collaborators are package-private; it is the narrow
  public bridge the transport-agnostic backend consumes.
- The `/contacts` side-socket exists because the production WS client
  completes corrId futures only for send acks / command errors (all the
  adapter needs); a `contactsList` response would time its `sendCommand` out.
  Discovered empirically on the first live run of this IT.
