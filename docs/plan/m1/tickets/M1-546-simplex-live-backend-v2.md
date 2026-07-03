---
id: M1-546
title: SimpleX live backend v2 (Phase 4b-3 substrate)
status: pending
created: 2026-07-03
last_updated: 2026-07-03
blocked_by:
  - M1-545
files_budget: 12
complexity: high
risk: low
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - any main-scope (production) code change — test-scope only; in particular
    NO chatItemUpdated case in the production SimpleXMessageCodec (the bot
    never consumes edits) and NO mention support in the production encoder
    (the bot never mentions) — both stay harness-side
  - modifying or deleting SimpleXAdapter / SimpleXWebSocketClient /
    SimpleXMessageCodec / SimpleXSubprocess (production classes; the harness
    stops USING SimpleXWebSocketClient, but the class stays for the adapter)
  - executing the live 4b-3 scenario run and its host fixtures (group
    creation via raw /g commands, live-reset/seed, subscriptions) — host
    actions after this ticket merges
  - the Signal adapter and any Signal backend (Phase 5)
  - the /testcase skill wrapper (Phase 6)
acceptance:
  - LiveSimpleXClient is reworked to a SINGLE raw java.net.http WebSocket
    connection per client identity, every inbound frame fed through the
    production SimpleXMessageCodec.decode() for everything the codec models
    (inbound messages, group candidates, send acks/corrId, command errors) —
    one wire-shape source of truth (D-live-9). The M1-544 /contacts
    side-socket workaround is gone; raw corrId command/response queries
    (/contacts, /groups) run on the same single connection.
  - The harness additionally parses chatItemUpdated frames (item-edit
    finalization; the production codec deliberately has no case for it) and
    SimpleXConversationBackend#awaitReply unions those finalized bodies with
    plain inbound replies, mirroring InMemoryConversationBackend's
    finalizedBodies union — progress-notified replies (/summary, chat,
    digest) are unobservable without it.
  - SimpleXConversationBackend binds GROUP steps — scenario group tokens
    resolve to per-client group ids via a corrId /groups query; plain group
    sends go through the production codec's group-scope encoding; sends that
    must carry a structured mention compose a harness-side mention envelope
    per D51 (a mentions{} memberId byte-equal to the bot's per-group
    memberId; plain-text "@Name" is silently dropped by the bot). The
    envelope's exact wire shape is best-guess in CI (pinned by a hermetic
    test) and a declared live-discovery item for the 4b-3 host run.
  - The 7 transport-relevant scenarios (3, 4, 7, 10, 11, 12, 15 —
    enumeration in docs/plan/live-e2e/README.md §Phase 1) exist as .scenario
    resources in a live-only resource directory; a hermetic (non-gated) test
    proves all 7 parse; a suite IT gated on -Dinfochat.live.simplex=true
    (skipped in CI, same gate as LiveSimpleXRoundTripIT) drives them via the
    unmodified ScenarioRunner.
  - Host-validated per D-live-9 before merge — LiveSimpleXRoundTripIT re-run
    green on the host over the reworked single-connection client (real
    relays), evidence recorded in this ticket's body.
  - mvn verify is green.
test_plan:
  adds:
    - a gated live suite IT driving the 7 live .scenario resources (skipped
      in CI without -Dinfochat.live.simplex=true)
    - a hermetic parse test proving all 7 live scenario resources parse
    - hermetic coverage of the harness-side chatItemUpdated frame parse and
      the D51 mention-envelope composition against synthetic/captured frames
  preserves:
    - all tests currently green on main; LiveSimpleXRoundTripIT stays gated
      (skipped in CI, green live) — its wiring MAY be adapted to the
      reworked client (authorized pre-existing-test change; its observable
      behavior is identical, a gated /help round-trip via ScenarioRunner)
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/verification.md §Test layers
decision_refs:
  - D-live-9
  - D51
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-546: SimpleX live backend v2 (Phase 4b-3 substrate)

## Context

Live-e2e Phase 4b-3 must drive the 7 transport-relevant scenarios over real
SimpleX, and the M1-544 binding cannot carry them (analysis in
`docs/plan/live-e2e/HANDOFF.md` §START HERE, 2026-07-02):

- **Single-connection rework (a).** The production `SimpleXWebSocketClient`
  completes its pending corrId futures ONLY on send acks, and simplex-chat
  delivers async events to one connection only — the M1-544 side-socket
  workaround for fixture queries does not scale to group/edit observation.
  The v2 client owns ONE raw `java.net.http` WebSocket per client identity
  and feeds every frame through the production `SimpleXMessageCodec.decode()`
  (static, package-private — accessible from the bridge's package), keeping
  one wire-shape source of truth (D-live-9) while owning its own
  corrId/response and async-event routing.
- **Item-edit observation (b).** Progress-notified replies (/summary, chat,
  digest) finalize via item EDIT (`chatItemUpdated`), which the production
  codec deliberately does not model (the bot never consumes edits). Without
  a harness-side parse, S10/S12/S15 are unobservable.
- **GROUP binding (c) + mention envelope (d).** S7/S10/S15 need group steps.
  The bot's mention recognition is STRUCTURED-ONLY (D51: a `mentions{}`
  memberId byte-equal to `chatInfo.groupInfo.membership.memberId`;
  `SimpleXGroupHandler` drops plain-text "@Name" silently), and the
  production encoder has no mention support (the bot never mentions), so the
  harness composes the envelope. Exact wire shape is a LIVE-discovery item:
  best-guess in CI from D51 + `docs/design/06-messaging.md` §6.4 frame
  notes, validated/fixed on the host during the 4b-3 run.
- **The 7 live scenario resources (e).** S3 invite mint→consume, S4
  un-invited DM rejected, S7 group pending→approve→auto-promote, S10
  /summary + group digest, S11 /zcash, S12 chat mode, S15 full happy path —
  expressed in the M1-539 grammar plus the M1-545 capture/substitution
  extension (S3/S15 need the cross-step invite code).

## Notes

- `LiveSimpleXClient` stays in test-scope package
  `app.zcat.infochat.messaging.impl.simplex` (the codec's `decode()` is
  package-private); `SimpleXConversationBackend` stays the only
  transport-aware class the runner core sees.
- Scenario timeouts in the live resources must be generous — llama.cpp on
  4 vCPU takes 60–120 s for chat/summary replies (HANDOFF §Live-run notes).
- Live-run prerequisites (NOT this ticket): group fixtures via raw corrId
  commands from the harness connection (`/g`, invite bot from LiveAdmin —
  the M1-515 provider gate decides the join); S3 needs an unregistered user
  → `prod/live-reset.sh` first (admin token re-arms by design, D-live-7);
  S10 needs `prod/live-seed.sh` + a subscription.
- Host-validation ordering within the ticket: run `mvn verify` with the app
  stack stopped (06-28 throttle rule), then restart the stack for the
  LiveSimpleXRoundTripIT host evidence.
