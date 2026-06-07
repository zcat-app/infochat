---
id: M1-205
title: "Adapter rate-limit enforcement: implement §6.3.7 + capability caps, or design-amend"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: [M1-177]
files_budget: 10
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - docs/design/06-messaging.md
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - the LLM-call rate cap and its /summary//retry coverage — M1-183's (LlmRateLimiter is the Provider-side source of truth and stays so)
  - send-path serialization and handle-map bounding — M1-188's (the outbound queue mechanics land there; this ticket decides/enforces the RATE caps, not the one-outstanding-send correctness)
  - inbound dispatch threading — M1-177's (this ticket is blocked on it; the bounded inbound queue, if implemented, attaches to the executor M1-177 introduces)
  - capability constant VALUES (minEditInterval/maxSendsPerSecond numbers) — M1-204 reconciles values; this ticket decides whether the caps are ENFORCED at all
  - per-user fairness scheduling beyond the bounded-queue drop rule — note the §6.3.7 fairness sentence in the decision, but a fair scheduler is its own work item if chosen
acceptance:
  - "A decision is recorded and applied for the advertised-but-unenforced send caps: EITHER maxInflightSends / maxSendsPerSecond are enforced on the outbound path of every production adapter (named tests: exceeding either cap observably throttles/queues rather than passing through), OR design 06-messaging's capability table and §6.3.7 are amended to mark them advisory/reserved with the rationale — after this ticket, no capability value is advertised that nothing reads (today both flags have zero enforcement call sites outside their declarations)"
  - "The same decision is recorded and applied for design 06-messaging §6.3.7's inbound back-pressure MUSTs — \"The adapter MUST NOT drop inbound messages while the handler is busy\", \"the adapter SHOULD enqueue inbound messages with a bounded queue (default 1000)\", \"On overflow, the adapter MUST drop the **NEWEST** message … and MUST send a synchronous throttle reply to its sender\", and the fixed throttle-reply text — EITHER implemented with named tests (overflow drops newest, throttle reply emitted with correlationId of the dropped message, older queue entries preserved), OR the §6.3.7 text is amended to what v1 actually ships; the section's claim that \"InMemoryAdapter and SimplexAdapter both implement a per-user-fair scheduler\" is corrected either way (no such scheduler exists in code)"
  - "Inbound rate-cap key growth is bounded pre-auth: a named test asserts contact ids that never pass registration cannot grow the per-user rate-cap key space without bound (gpt S5: keys are created from adapter-supplied contact ids before any auth check, with no hard cap)"
  - "Whatever direction: banned-user intake, ban-check ordering, and existing InboundRouter tests stay green"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Failure handling
  - docs/spec/security.md §Rate limiting
decision_refs:
  - D46
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-205: Adapter rate-limit enforcement: implement §6.3.7 + capability caps, or design-amend

## Context

Unified findings M10 (ACCEPTED, med) and gpt S5
(`deep-code-review/v2/UNIFIED.md` §2): the CapabilityFlags surface
advertises maxInflightSends and maxSendsPerSecond, but a draft-time grep
confirms zero enforcement call sites; design 06-messaging §6.3.7 commits
to a bounded inbound queue with drop-newest + synchronous throttle reply
+ drop metrics + per-user fairness, none of which exists; and the
provider-side rate-cap buckets key on adapter-supplied contact ids
before any auth check with no hard cap on key-space growth.

This is decision-shaped: the design text is design-tier and CAN be
amended to match a leaner v1 — but the current state (advertised caps
nothing reads; a design section asserting implementations that don't
exist) is the worst of both. The user picks the direction at start.

Spec-level anchors: messaging.md §Failure handling keeps the Provider's
per-user rate limiter as "the single source of truth for 'slow this
user down'" — adapter-side enforcement, if chosen, is transport
protection underneath it, not a second user-facing limiter; and
security.md §Rate limiting owns the per-user budgets (M1-183's).

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T28 under `deep-code-review/v2/` (opus-48
  msg M-F5, gpt S5).
- blocked_by M1-177: the bounded inbound queue's natural insertion
  point is the per-adapter executor M1-177 introduces, and the adapter
  files overlap (SignalAdapter/SimpleXAdapter/InMemoryAdapter are in
  M1-177's files_scope).
- complexity high → plan-writer pass runs at start; the outline should
  resolve the implement-vs-amend split per leg BEFORE code is written.
