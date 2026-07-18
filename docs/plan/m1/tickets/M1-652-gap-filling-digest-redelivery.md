---
id: M1-652
title: "Delivery idempotency at the outbound chokepoint"
status: pending
created: 2026-07-18
last_updated: 2026-07-18
blocked_by: []
files_budget: 14
files_scope:
  - infochat-core/src/main/resources/db/migration/V61__outbound_delivery_ack.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/OutboundDelivery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/OutboundDeliveryAckRepository.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/OutboundAckPruneJob.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/OutboundDeliveryIdempotencyTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/OutboundDeliveryAckRepositoryIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/OutboundAckPruneJobTest.java
  - docs/design/06-messaging.md
  - docs/spec/messaging.md
  - docs/spec/decisions.md
complexity: high
risk: high
round_cap: 3
security_relevant: false
migration_touch: true
out_of_scope:
  - >-
    Adapter-side dedup. docs/design/06-messaging.md §6.3.5 asks adapters to
    deduplicate; this ticket does NOT implement that in SimpleXAdapter,
    SignalAdapter or InMemoryAdapter. Idempotency moves to the chokepoint
    instead, where one implementation covers every adapter and every message
    type. §6.3.5 is amended to say so rather than left as an unimplemented
    SHOULD.
  - >-
    Chunk-level idempotency. docs/design/06-messaging.md §6.3.4 records that a
    chunked SimpleX send is non-atomic and that Provider-side retry re-sends
    from the first chunk. That prefix-duplication lives inside the adapter,
    below this seam; the chokepoint suppresses a whole repeated message, not a
    repeated chunk. §6.3.4's duplicate-tolerance note stays true.
  - >-
    The in-place edit paths (updateInPlace / finalizeInPlace,
    OutboundDelivery.java:159,171). They edit an already-delivered message
    rather than delivering a new one, so they have no duplicate to suppress.
  - >-
    Changing the retry ladder itself — max-attempts, backoff, growth factor, or
    the per-group permanent-failure counter. Only the pre-send and post-ack
    behavior changes.
acceptance:
  - >-
    Migration V61 creates outbound_delivery_ack (correlation_id TEXT PRIMARY
    KEY, scope_kind TEXT, scope_ref TEXT, delivered_at TIMESTAMPTZ NOT NULL)
    and grants the provider role SELECT + INSERT + DELETE. V59 is the highest
    migration on disk and V60 is reserved by M1-648, so V61 is the next free
    version.
  - >-
    OutboundDelivery.deliver and .deliverToGroup consult
    outbound_delivery_ack before sending: a row already present for this
    correlationId means the message was delivered, so the send is SKIPPED and
    the outcome reported as already-delivered rather than as a fresh send. On a
    successful send the row is written. This works because
    OutboundMessage.correlationId is non-null and the SPI already commits it is
    "stable across retries of the same logical outbound"
    (OutboundMessage.java:14-15) — the contract exists today, only the consumer
    was missing.
  - OutboundDeliveryIdempotencyTest.secondSendWithSameCorrelationIdIsSuppressed passes
  - >-
    Idempotency survives a Provider restart: the ack is persisted, not
    in-memory, so a process that dies between send and the next attempt does
    not re-post on restart. OutboundDeliveryAckRepositoryIT pins this against a
    real database.
  - OutboundDeliveryAckRepositoryIT.ackSurvivesRepositoryReconstruction passes
  - >-
    A TRANSIENT retry inside one delivery attempt is still allowed to reach the
    adapter — the ack is written only after the adapter accepts the message, so
    a failed send leaves no row and the existing retry ladder is unchanged.
    OutboundDeliveryIdempotencyTest.failedSendWritesNoAckAndStillRetries passes.
  - >-
    Acks are pruned on a schedule (infochat.messaging.ack-retention, default 7d)
    so the table cannot grow without bound. The retention window MUST exceed
    the worst-case retry wall clock — see the Notes; 7d is three orders of
    magnitude above it, chosen so /retry-style user-initiated redelivery is also
    covered, not merely the automatic ladder.
    OutboundAckPruneJobTest.prunesOnlyEntriesOlderThanRetention passes.
  - >-
    The prune job reads "now" from the injected java.time.Clock, never an inline
    Instant.now() or SQL now(), per CLAUDE.md §Injectable time in decision logic
    — the retention comparison is a decision, not an audit stamp.
  - >-
    docs/design/06-messaging.md §6.3.5 is rewritten: the adapter-side SHOULD and
    its 60-second window are replaced by the chokepoint guarantee. The
    "operator must accept occasional duplicate messages" escape hatch is removed
    for the whole-message case, since it is no longer true.
  - >-
    docs/spec/messaging.md §Failure handling states the idempotency guarantee,
    and a new decision D63 records it.
  - mvn verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/OutboundDeliveryIdempotencyTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/OutboundDeliveryAckRepositoryIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/OutboundAckPruneJobTest.java
  preserves:
    - >-
      All existing OutboundDelivery retry/abort behavior and its tests. The
      retry ladder is untouched; only pre-send lookup and post-ack write are new.
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Failure handling
  - docs/design/06-messaging.md §6.3.5
decision_refs:
  - D63
---

# M1-652: Delivery idempotency at the outbound chokepoint

## Context

`docs/design/06-messaging.md` §6.3.5 says adapters SHOULD deduplicate by
`OutboundMessage.correlationId` over a 60-second window. **No adapter does.**
Verified 2026-07-18: `grep correlationId` across
`infochat-messaging-adapter/src/main/java` returns 8 hits, every one either
javadoc or record-field plumbing, and zero lookups. `SimpleXAdapter.java:562`
stores the correlationId into the returned handle and never reads it back;
the `corrId` it does use (`SimpleXAdapter.java:938-940`,
`"simplex-cmd-" + commandCounter.incrementAndGet()`) is a fresh per-transmit
WebSocket request id, regenerated on every retry, so it cannot suppress
anything. `SignalJsonRpcClient.java:430` and `InMemoryAdapter.java:129` send
unconditionally. `OutboundDelivery.java` — the chokepoint every outbound
message passes through — contains no dedup either.

So every retry in the system delivers a duplicate today, for chat replies,
progress notifications, group announcements and digests alike. The design
doc's own escape hatch ("the operator must accept occasional duplicate
messages on retry") is the de-facto v1 behavior.

Two things make the documented design unfixable as written, which is why this
ticket moves the guarantee rather than implementing the SHOULD:

1. **The 60-second window is too small for its own purpose.** Backoff sleeps
   total under 750 ms (`infochat.messaging.retry.base-delay-ms=250`,
   `growth-factor=2.0`, `max-attempts=3`, and the loop returns ABORTED without
   sleeping on the final attempt — `OutboundDelivery.java:215-225,233,283`).
   But wall clock is dominated by SimpleX's per-send ack timeout of 30 s
   (`SimpleXAdapter.java:91`), so three attempts reach ~90 s — past the window —
   and a chunked send multiplies that by the chunk count, since each chunk
   carries its own 30 s timeout inside a single attempt
   (`SimpleXAdapter.java:552-556`).
2. **Per-adapter dedup is N implementations of one property.** Three adapters
   today, each needing its own cache with its own lifetime, none surviving a
   Provider restart.

## Acceptance

See `acceptance`. The shape: one persisted ack table, one lookup before send
and one write after, at the single seam every outbound message already crosses.

## Out-of-scope

See `out_of_scope`. Notably this does NOT implement adapter-side dedup, does
NOT change the retry ladder, and does NOT address chunk-level prefix
duplication (§6.3.4), which lives below this seam.

## Notes

**Why the chokepoint and not the digest.** This ticket exists because M1-642
(per-category digest delivery) was filed assuming the chokepoint "already does
its idempotency/dedup work per message". It does not. The alternative was a
bespoke per-(digest slot, category) acked table inside the digest package —
rejected: it would give periodic digests a delivery guarantee no chat reply or
announcement has, put delivery state in a feature package instead of the seam
that owns delivery, and leave §6.3.5 still unimplemented. M1-642 is
`blocked_by` this ticket and consumes the result, which is what its original
Notes assumed was already available.

**Retention floor.** The ack must outlive the longest window in which a
redelivery of the same logical message can occur. The automatic ladder's
ceiling is ~90 s single-chunk (see Context), but user-initiated redelivery
(`/retry --digest`) has no such bound, so retention is set by product
judgement rather than by the ladder: 7 days covers a user retrying the next
day. This is a judgement call, not a measurement — the ladder ceiling is the
only measured input.

**The SPI contract already holds.** `OutboundMessage.correlationId` is a
non-null `String` in a null-marked package, and its javadoc commits that the
id "is non-null and stable across retries of the same logical outbound"
(`OutboundMessage.java:14-15`). Nothing about the message contract needs to
change; this ticket only builds the consumer that contract was written for.

**Skip is not failure.** An already-delivered correlationId must report a
distinct outcome from both success and failure — a caller that treats it as a
failed send will trip the per-group permanent-failure counter
(`infochat.messaging.permanent-failure-threshold`) and eventually clean up a
healthy group. Getting this distinction wrong is the main risk in the ticket.

**No table exists to extend.** Verified 2026-07-18: no migration under
`infochat-core/src/main/resources/db/migration/` defines any outbound
delivery or ack table, so V61 is a new table rather than a column addition.
