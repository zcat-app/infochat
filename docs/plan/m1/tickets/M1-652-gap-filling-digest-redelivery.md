---
id: M1-652
title: "Gap-filling redelivery for per-category digests"
status: pending
created: 2026-07-18
last_updated: 2026-07-18
blocked_by:
  - M1-642
files_budget: 12
files_scope:
  - infochat-core/src/main/resources/db/migration/V61__digest_category_delivery.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestCategoryDeliveryRepository.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestDelivery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRetryService.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestCategoryDeliveryRepositoryIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestDeliveryTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRetryServiceTest.java
  - docs/spec/commands.md
  - docs/spec/decisions.md
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: true
escalations:
  - date: 2026-07-18
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      Acceptance item 2 would silently suppress every /retry --digest
      redelivery of an already-delivered slot for up to the 7-day
      ack-retention window. DigestWorker.java:212 mints correlationId as
      "digest-" + slot.groupId() + "-" + slot.windowStart(), and
      DigestRetryService.retryDigest rebuilds the DigestSlot from
      coords.slotFiredAt into DigestSlot's windowStart component, so the
      retry's correlationId is byte-identical to the original's. The group
      receives nothing while RetryCommandHandler replies SUCCESS. This
      contradicts docs/spec/commands.md:1018-1024, which commits that
      /retry --digest "posts a *new* message". Resolution requires either a
      retry-distinct correlationId (DigestWorker.java / DigestRetryService.java
      + tests) or a spec amendment (docs/spec/commands.md) — none of which are
      in files_scope. Structural, not a prose defect; not self-refinable.
revisions:
  - date: 2026-07-18
    reason: >-
      Clarity-fail rework, and a full repurpose. The original ticket
      ("Delivery idempotency at the outbound chokepoint") was withdrawn after
      investigation showed its mechanism could not work: 40 of 43
      OutboundMessage construction sites mint UUID.randomUUID(), so
      correlationId-keyed dedup at the chokepoint is inert almost everywhere;
      the one duplicate mode that reaches a user (adapter transmits, ack times
      out, ladder re-sends) writes no ack row and is invisible to that seam;
      and nothing in the system re-delivers after a restart, so its
      restart-durability criterion protected an unreachable scenario. User
      decision 2026-07-18: /retry --digest posts again. That removed the
      mechanism's last customer. The genuine defect the investigation found is
      silent UNDER-delivery on a mid-sequence Provider death, which is a digest
      concern, so this ticket was re-aimed at gap-filling and re-sequenced
      AFTER M1-642. The outbound contract corrections split out to M1-653.
    snapshot:
      title: "Delivery idempotency at the outbound chokepoint"
      status: escalated
      blocked_by: []
      files_budget: 14
      risk: high
      approach: >-
        correlationId-keyed outbound_delivery_ack table (V61) consulted by
        OutboundDelivery.deliver/.deliverToGroup before every send, with a
        7-day pruned retention. Withdrawn — see reason.
out_of_scope:
  - >-
    Any chokepoint-level or system-wide delivery idempotency. OutboundDelivery
    is NOT touched. Verified 2026-07-18: 40 of 43 OutboundMessage sites mint
    UUID.randomUUID(), so a correlationId-keyed table there is inert; and the
    real duplicate mode (ambiguous ack timeout inside the retry ladder) leaves
    no success record for such a table to consult. That guarantee is dropped
    for v1, not relocated — M1-653 documents the position.
  - >-
    Adapter-side dedup (docs/design/06-messaging.md §6.3.5). Unimplemented in
    all three v1 adapters and left that way; M1-653 corrects the doc.
  - >-
    Changing the retry ladder, its backoff, max-attempts, or the per-group
    permanent-failure counter.
  - >-
    Alerting on partial delivery. Detecting and reporting that a slot
    half-delivered is a separate concern from filling the gap on retry; this
    ticket makes the retry fill gaps, it does not add a notification.
  - >-
    Per-category summary_cache rows. The single-row-per-slot cache model
    (D17) is retained.
acceptance:
  - >-
    RESOLVE-BEFORE-START: the degraded-retry fork below must be closed and this
    acceptance list rewritten to match the chosen arm before /m1-tick start is
    run. See Notes §"The fork". Filed 2026-07-18 with the fork open because the
    ticket is blocked on M1-642 and the answer depends on DigestDelivery's
    final shape.
  - >-
    Migration V61 creates digest_category_delivery (group_id UUID,
    window_start TIMESTAMPTZ, category_slug TEXT, delivered_at TIMESTAMPTZ NOT
    NULL, PRIMARY KEY (group_id, window_start, category_slug)) and grants the
    provider role SELECT + INSERT + DELETE. V59 is the highest migration on
    disk (verified 2026-07-18) and V60 is reserved by M1-664 (the M1-648
    decomposition), so V61 is next.
  - >-
    A category message that the adapter accepts records a
    digest_category_delivery row. A failed send records nothing, so the
    existing per-category TRANSIENT/PERMANENT ladder is unchanged.
    DigestDeliveryTest.recordsDeliveryOnlyOnAdapterAcceptance passes.
  - >-
    A /retry --digest for a slot with existing delivery rows sends only the
    categories with no row, and reports how many were skipped. It never sends
    zero messages silently: if every category is already recorded, the admin
    is told so explicitly rather than receiving a bare SUCCESS.
    DigestRetryServiceTest.retryFillsOnlyMissingCategories passes.
  - >-
    Gap-filling state survives a Provider restart, since it is a table rather
    than in-process state — the mid-sequence-death case is the whole point.
    DigestCategoryDeliveryRepositoryIT.deliveryStateSurvivesRepositoryReconstruction passes.
  - >-
    Rows are pruned on the same schedule and by the same retention rule as the
    summary_cache row for their slot, so the two cannot diverge. The retention
    comparison reads "now" from the injected java.time.Clock, never an inline
    Instant.now() or SQL now(), per CLAUDE.md §Injectable time in decision logic.
  - >-
    docs/spec/commands.md is amended, at the "Cached digest message handle"
    paragraph under §Conversation control (commands.md:1018-1024), to state
    what /retry --digest does for a partially-delivered slot. The existing
    commitment that it posts a NEW message (never edits, never silently
    suppresses) is preserved verbatim — this ticket narrows WHICH categories
    are posted, it does not reintroduce whole-message suppression.
  - A new decision D65 records the gap-filling policy.
  - mvn verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestCategoryDeliveryRepositoryIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRetryServiceTest.java
  modifies:
    - >-
      DigestDeliveryTest.java — ADDS cases for delivery recording. The
      per-category delivery cases M1-642 authored must keep passing unmodified.
  preserves:
    - >-
      M1-642's per-category delivery behavior and its DigestDeliveryTest cases.
    - >-
      The D17 single-row summary_cache contract and the /retry --digest
      posts-a-new-message guarantee.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Conversation control
decision_refs:
  - D65
---

# M1-652: Gap-filling redelivery for per-category digests

## Context

**This ticket was repurposed on 2026-07-18.** It was filed as "Delivery
idempotency at the outbound chokepoint" — a `correlationId`-keyed ack table
consulted by `OutboundDelivery` before every send. That design was withdrawn
after clarity-fail investigation; `revisions[0]` records why in full. The one
sentence version: the chokepoint cannot see what it would need to see, and
after the `/retry --digest` decision it had no remaining customer.

What the investigation *did* establish is a real defect, and it is a digest
defect rather than a delivery-seam one:

**On a mid-sequence Provider death, undelivered digest categories are lost
silently.** Once M1-642 lands, a digest is N category messages. If the Provider
dies after category 2 of 5, categories 3-5 are never sent — and nothing
re-sends them. `DigestScheduler` is skip-not-catch-up and treats a slot as
missed only when it is past window-end *with no cache row*; the crashed slot
wrote its `summary_cache` row before delivering, so it is considered done. No
alarm fires. The group is simply short three categories.

The only remedy available to an operator is `/retry --digest`, which re-runs
the slot and re-posts **every** category it produces, including the two that
already landed. That is correct-by-spec (`docs/spec/commands.md` §"Cached
digest message handle" commits that retry posts a new message) but crude.

This ticket makes that retry fill the gap instead: send the categories that
have no delivery record, skip the ones that do.

## Acceptance

See `acceptance`. **The first item is a gate, not a criterion** — the fork in
§Notes must be closed and this list rewritten before `start`.

## Out-of-scope

See `out_of_scope`. Notably `OutboundDelivery` is not touched; no
chokepoint-level or system-wide idempotency is added or implied.

## Notes

### The fork (RESOLVE BEFORE START)

Gap-filling needs per-category messages to fill gaps *in*. A retry does not
always produce them:

`DigestRetryService.retryDigest` rebuilds the slot as
`new DigestSlot(groupId, timezone, coords.slotKind, coords.slotFiredAt,
coords.expiresAt)` — the 5th component is `windowEnd`, and it receives the
cache row's `expires_at`, which `DigestWorker.java:203` wrote as
`slot.windowEnd().plus(retryHorizon)`. `retryHorizon` is
`infochat.digest.retry-cooldown`, **default PT2M**. `DigestWorker.execute`
then degrades when `Duration.between(now, slot.windowEnd())` is
negative-or-zero. So:

- retry within ~2 minutes of window end → full render → N category messages →
  gap-filling applies;
- retry later than that → **degraded render → one flat headlines-only message**
  (D17) → there are no categories, and nothing to gap-fill.

A human who notices a truncated digest is almost always in the second case.
So the mechanism as stated pays off mainly in the case that rarely happens.
Two ways out, and this ticket must pick one before it starts:

- **(a) Accept the narrow scope.** Gap-fill only in-horizon retries; a late
  retry keeps today's degraded-single-message behavior. Cheapest; leaves the
  common case exactly as bad as it is now, which may make the whole ticket not
  worth its migration.
- **(b) Replay the cached render on retry.** `summary_cache` already holds the
  rendered digest for the slot, and its `expires_at` already outlives the
  window. Have the retry path replay that content — split it per category and
  gap-fill — rather than re-collecting and re-rendering. This makes retry
  deterministic (today a retry can return *different* content, because
  `DigestPostCollector.collectForGroup(groupId, since)` takes only a start
  bound and sweeps in posts published since the crash) and makes gap-filling
  work at any point in the cache row's life. Larger: it touches the D17 cache
  contract's read path and needs its own acceptance items.

(b) is the more valuable shape and also fixes a second oddity nobody filed —
that `/retry --digest` today can deliver a digest that does not match the one
it is retrying. It is also clearly bigger. **Do not start this ticket until
the user picks.** If (b) is chosen, re-check `files_budget: 12`.

### Why not the chokepoint

Recorded so the withdrawn design is not re-proposed. Verified 2026-07-18:

- 40 of 43 `new OutboundMessage(...)` sites in `infochat-provider/src/main`
  pass `UUID.randomUUID().toString()`. Only `DigestWorker.java:212`,
  `ApproveGroupCommandHandler.java:260` and `RejectGroupCommandHandler.java:304`
  mint a stable id. A dedup table keyed on `correlationId` can never match a
  random id, so it would be inert at ~93% of call sites.
- The duplicate users actually experience is the ambiguous ack: the adapter
  transmits, the 30 s SimpleX ack times out (`SimpleXAdapter.java:91`), the
  ladder re-sends. That path records no success, so a success-keyed table
  cannot suppress it. The chokepoint cannot observe whether an ambiguous
  transmit landed; only the adapter or the transport can. This is an
  observability boundary, not an implementation gap.
- Nothing re-delivers after a restart (skip-not-catch-up; no outbound
  rehydrator), so persisted chokepoint idempotency had no scenario to protect.

### Sequencing

`blocked_by: [M1-642]`, which inverts the original dependency — M1-642 was
briefly `blocked_by: [M1-652]` on the withdrawn design. The per-category
structure must exist before there are gaps to fill. M1-642 was refined the
same day to drop that edge and to state that redelivery posts again and may
duplicate, which is the behavior this ticket later improves.

### Relationship to M1-653

M1-653 corrects the outbound *contracts* the withdrawn design rested on — the
`OutboundMessage.correlationId` javadoc that promises stability 40 of 43 sites
do not provide, and `docs/design/06-messaging.md` §6.3.5's unimplemented
adapter-dedup SHOULD. It is independent and unblocked; it does not gate this
ticket, and this ticket does not gate it.
