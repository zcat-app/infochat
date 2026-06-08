---
id: M1-205
title: "Adapter rate-limit enforcement: implement §6.3.7 + capability caps, or design-amend"
status: deferred
created: 2026-06-07
last_updated: 2026-06-08
deferred_on: M1-224
deferred_reason: blocked-on-new-ticket
blocked_by: [M1-177]
clarity_check:
  date: 2026-06-08
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE items 1-3 say \"named tests\" but name no test class/method; pin test names in the plan-writer outline so the reviewer can verify them."
    - "FILES-BUDGET-PLAUSIBLE: budget of 10 is tight if the implement branch is chosen for all three legs across SimpleX/Signal/InMemory/InboundRouter with tests; plan-writer should confirm sufficiency and escalate mid-round if materially insufficient."
files_budget: 10
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/RateCapBucket.java
  - docs/design/06-messaging.md
  - docs/design/09-reference.md
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
outline_file: target/m1-tick-outline-M1-205.md
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
      files: 6
      added: 180
      removed: 29
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-08
    category: DOS
    severity: high
    promise: |
      §Rate limiting — "Chat-mode message rate (transport-level) — bounds
      inbound message volume regardless of cost"; §Authorization step 1.5 —
      over-cap inbound is dropped "before every application-level check
      below, so a hostile flood cannot drive outbound cost." DOS category
      covers "Resource exhaustion ... in the messaging adapter."
    gap: |
      The §6.3.7 amend removes the previously-spec'd bounded inbound queue
      (default 1000) + drop-newest and substitutes "dispatch backlog is
      bounded by (rate-cap survivors × in-flight handler time)" over the
      JDK default UNBOUNDED LinkedBlockingQueue. False for the queue: the
      step-1.5 rate cap (RateCapBucket.tryAcquire, InboundRouter.java:359)
      runs INSIDE the dispatched task on the single-thread dispatchExecutor
      (SimpleXWebSocketClient.java:411/:430; SignalJsonRpcClient.java:226),
      i.e. strictly downstream of the unbounded queue, so it cannot bound
      queue depth/memory.
    repro: |
      Attacker sends in-spec-size messages on a connected adapter faster
      than the single dispatch thread drains onMessage (each does a DB read
      + normalization). The read loop enqueues every frame onto the
      unbounded LinkedBlockingQueue without back-pressure; the queue grows
      without bound, exhausting Provider heap and OOM-killing the only
      user-facing service. The rate cap drops cheaply only WHEN dequeued —
      too late. maxContactBuckets bounds the key space, not the queue.
    suggested_fix_class: rate-limit
redteam_audits:
  - date: 2026-06-08
    verdict: FINDINGS
    base: f3dccd2229263dad2ee4c787e2d97fa6ff6f79c5
    head: m1/M1-205-adapter-rate-limit-enforcement-decision (working tree, --in-progress)
    verdict_file: docs/plan/m1/redteam/M1-205-2026-06-08.md
    findings_count: 1
    out_of_model_count: 2
    note: |
      One high DOS finding, main-session-confirmed against the code the
      threat-actor could not see: the leg-2 §6.3.7 AMEND retired the
      design's bounded-queue promise on a justification (rate cap bounds
      the backlog) that is false — the rate cap runs downstream of the
      unbounded dispatch queue. The amend-vs-implement decision rests on a
      falsified premise. A proper fix bounds the executor queue in
      SimpleXWebSocketClient / SignalJsonRpcClient, both OUTSIDE the current
      files_scope. Surfaced to the user; ticket is in-review (APPROVE round
      1, pre-commit). Two OUT-OF-MODEL items checked and dismissed.
escalations:
  - date: 2026-06-08
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — pre-implementation scope breach. The user picked the
      recommended amend/amend/implement direction; the leg-3 fix
      (hard cap on contact-bucket key growth) lands in
      infochat-provider/.../messaging/RateCapBucket.java, which is NOT
      in files_scope (scope lists InboundRouter.java, the caller — the
      wrong layer; the bucket map internals live in RateCapBucket).
      Refine to add RateCapBucket.java to files_scope before code.
  - date: 2026-06-08
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — mid-implementation scope breach. The leg-2 §6.3.7 amend
      (remove the unshipped bounded-queue / drop-newest / throttle-reply
      mechanism) orphans error code E4007 ("Inbound queue overflow —
      newest message dropped + throttle reply") in docs/design/09-reference.md,
      which describes exactly that removed mechanism. 09-reference.md is
      NOT in files_scope. User chose to refine scope and fix E4007 so the
      design stays self-consistent (this ticket exists to end
      "design asserting implementations that don't exist").
  - date: 2026-06-08
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      /redteam M1-205 --in-progress returned FINDINGS: 1 high DOS.
      The leg-2 §6.3.7 AMEND retired the design's bounded-inbound-queue
      promise and substituted "dispatch backlog is bounded by (rate-cap
      survivors × in-flight handler time)" over the JDK default UNBOUNDED
      LinkedBlockingQueue. Main-session-confirmed false: the step-1.5 rate
      cap (RateCapBucket.tryAcquire, InboundRouter.java:359) runs INSIDE the
      dispatched task on the single-thread dispatchExecutor
      (SimpleXWebSocketClient.java:411/:430; SignalJsonRpcClient.java:226),
      i.e. strictly downstream of the unbounded queue — it bounds in-flight
      work (=1), not queue memory. A sustained inbound flood grows the
      dispatch queue without bound → Provider OOM (threat model DOS:
      "resource exhaustion in the messaging adapter"). Full verdict:
      docs/plan/m1/redteam/M1-205-2026-06-08.md.
revisions:
  - date: 2026-06-08
    reason: |
      Refine after budget-breach. Added
      infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/RateCapBucket.java
      to files_scope so the leg-3 hard-cap fix can land in the correct
      layer. No files_budget change (recommended amend/amend/implement
      set is ~4 files, well under 10).
    files_scope_before:
      - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
      - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
      - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapter.java
      - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
      - docs/design/06-messaging.md
      - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
      - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
      - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
  - date: 2026-06-08
    reason: |
      Refine after budget-breach. Added docs/design/09-reference.md to
      files_scope so the leg-2 §6.3.7 amend can also correct error code
      E4007, which described the now-removed inbound bounded-queue /
      throttle-reply mechanism. No files_budget change.
    files_scope_before:
      - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
      - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
      - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapter.java
      - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
      - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/RateCapBucket.java
      - docs/design/06-messaging.md
      - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
      - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
      - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
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
