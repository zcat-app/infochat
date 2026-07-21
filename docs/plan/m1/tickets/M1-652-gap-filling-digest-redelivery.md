---
id: M1-652
title: "Gap-filling redelivery for per-category digests"
status: pending
created: 2026-07-18
last_updated: 2026-07-21
blocked_by:
  - M1-642
files_budget: 22
files_scope:
  - infochat-core/src/main/resources/db/migration/V61__digest_replay_state.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestSectionRepository.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestCategoryDeliveryRepository.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestDelivery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRetryService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestSectionRepositoryIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestCategoryDeliveryRepositoryIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestDeliveryTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRetryServiceTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerClockTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryDigestCommandTest.java
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
  - date: 2026-07-20
    reason: >-
      Fork resolved — user chose arm (b): replay persisted render output.
      Acceptance rewritten for (b) and the RESOLVE-BEFORE-START gate
      removed. V61 (renamed digest_replay_state) now creates digest_section
      alongside digest_category_delivery; sections are persisted at render
      time as the exact delivery bytes (M1-642's arm-(b) pin: affordance
      folded into the last section, roll-up prefixes inside sections);
      /retry --digest replays only missing categories — deterministic,
      byte-faithful, LLM-free — through deliverSequenceToGroup, with a
      full-re-run fallback for section-less slots whose render budget is
      bounded by the digest window width; summary_cache.expires_at decoupled
      from retry-cooldown via infochat.digest.replay-retention (default
      PT24H). files_budget 12 -> 16 (DigestSectionRepository + its IT,
      DigestWorkerTest, application.properties enter scope; migration file
      renamed while unclaimed). V60 verified landed and V61 verified
      unclaimed 2026-07-20.
    snapshot:
      files_budget: 12
      migration_file: V61__digest_category_delivery.sql
      acceptance_gate: >-
        RESOLVE-BEFORE-START: the degraded-retry fork below must be closed
        and this acceptance list rewritten to match the chosen arm before
        /m1-tick start is run. See Notes §"The fork". Filed 2026-07-18 with
        the fork open because the ticket is blocked on M1-642 and the answer
        depends on DigestDelivery's final shape.
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
    (D17) is retained: digest_section is replay state keyed to the slot, not
    additional cache rows, and summary_cache.content KEEPS having no reader
    — replay reads digest_section, never the joined cache string.
  - >-
    The scheduled-delivery path. Scheduled slots deliver exactly as M1-642
    built them; this ticket changes only what /retry --digest does (plus the
    render-time section write and delivery-record write it needs).
acceptance:
  - >-
    Migration V61 (digest_replay_state) creates BOTH tables. digest_section:
    (group_id UUID, window_start TIMESTAMPTZ, category_slug TEXT, position
    INT NOT NULL, content TEXT NOT NULL, PRIMARY KEY (group_id, window_start,
    category_slug)) — the persisted render output, one row per section in
    renderSections() order. digest_category_delivery: (group_id UUID,
    window_start TIMESTAMPTZ, category_slug TEXT, delivered_at TIMESTAMPTZ
    NOT NULL, PRIMARY KEY (group_id, window_start, category_slug)) — the
    delivery record. Provider role gets SELECT + INSERT + DELETE on both.
    V60 landed with M1-664 and V61 was verified unclaimed 2026-07-20 —
    RE-VERIFY the number is still next at /m1-tick start.
  - >-
    Section persistence: every render that produces sections persists the
    ordered list renderSections() returned — the EXACT delivery bytes
    (affordance folded into the last section; flag-on roll-up prefixes
    inside their sections, per M1-642's arm-(b) pin) — alongside the
    summary_cache upsert for the slot. Degraded and zero-post slots persist
    no sections. A crash that strands a cache row without its sections is
    safe: the fallback item below covers it, and replay never half-applies.
    DigestWorkerTest.persistsSectionsAlongsideCacheRow passes.
  - >-
    A category message that the adapter accepts records a
    digest_category_delivery row — on scheduled delivery AND on replay
    delivery alike. A failed send records nothing, so the existing
    per-category TRANSIENT/PERMANENT ladder is unchanged. The recording
    seam is a delegating MessagingAdapter wrapper inside DigestDelivery
    (record when the wrapped send() returns normally; an exception
    records nothing): deliverSequenceToGroup already takes the adapter as
    a parameter and DigestDelivery supplies it (verified 2026-07-21), so
    OutboundDelivery stays frozen per out_of_scope and no signature
    changes are needed.
    DigestDeliveryTest.recordsDeliveryOnlyOnAdapterAcceptance passes.
  - >-
    Replay: a /retry --digest for a slot WITH persisted sections replays
    those bytes — no post re-collection, no render, no LLM call — sending
    ONLY the categories with no delivery row, sequentially in stored
    position order, through OutboundDelivery.deliverSequenceToGroup
    (M1-642's one-aggregate-counter-outcome semantics). The outcome
    surface is the existing RetryResult enum extended with TWO new
    constants — REPLAYED_MISSING (replay path, at least one category
    sent) and ALL_ALREADY_DELIVERED (replay path, every category already
    recorded, nothing sent) — and the retryDigest return type does NOT
    change (a record return would force compile edits in
    DigestRetryServiceClockIT and DigestRoundtripIT, both out of scope;
    verified 2026-07-21 that mere enum growth leaves every out-of-scope
    RetryResult reference compiling and passing). Numeric skip-counts are
    NOT reported in v1 — the distinct replies carry the honesty. A replay
    never sends zero messages silently: the ALL_ALREADY_DELIVERED caller
    is told so explicitly via its own reply, never a bare SUCCESS; and
    SUCCESS remains the full-re-run (fallback) outcome only.
    RetryCommandHandler.handleDigestRetry maps both new constants to new
    bundle-backed replies — keys added to BundleKeys and to BOTH
    bundles/en.properties and bundles/cs.properties per the D43 bilateral
    keyset.
    DigestRetryServiceTest.retryFillsOnlyMissingCategories and
    DigestRetryServiceTest.replaySendsPersistedBytesWithoutRerender pass.
  - >-
    Empty-list counter-safety on the replay path: replay MUST NOT call
    OutboundDelivery.deliverSequenceToGroup with an empty message list, and a
    no-op retry (every category already delivered) MUST NOT increment the
    per-group consecutive-permanent-failure counter. Today
    deliverSequenceToGroup (OutboundDelivery.java:181-206) applies
    onPermanentGroupFailure whenever anyDelivered is false — including for an
    empty list — so a replay that filters categories down to zero and still
    calls the method would, after three such retries, silently soft-remove a
    healthy group, contradicting the D63/"always > 1" calibration. Either
    short-circuit before the call (the "caller is told so explicitly" rule in
    the replay item above already implies this) or harden the method to
    early-return without counter mutation on empty. (From M1-642 redteam
    out-of-model item, 2026-07-20; not adversary-reachable via any current
    caller, but this ticket's filtering replay caller is the first that can
    produce an empty list.)
  - >-
    Fallback: a /retry --digest for a slot with NO persisted sections
    (pre-V61 row, degraded slot, zero-post slot, or a crash-stranded cache
    row) falls back to today's full re-run path (re-collect, re-render or
    degrade per D17). The fallback's render budget is bounded by a sane
    per-retry budget — the configured digest window width
    (infochat.digest.window-width-minutes, injected directly into
    DigestRetryService via its own @ConfigProperty; DigestScheduler is
    not touched) — never by the full replay-retention horizon (a retry
    must not acquire a many-hours LLM timeout).
    DigestRetryServiceTest.retryWithoutSectionsFallsBackToRerun passes.
  - >-
    Horizon decoupling: summary_cache.expires_at stops reusing
    infochat.digest.retry-cooldown as its horizon. A new property
    infochat.digest.replay-retention (default PT24H) sets expires_at at
    cache-write time; retry-cooldown returns to cooldown-only duty. Replay
    works at any point inside the retention horizon — the ~2-minute
    degraded-retry cliff this ticket's fork existed to resolve is gone.
  - >-
    Gap-filling state survives a Provider restart, since it is a table rather
    than in-process state — the mid-sequence-death case is the whole point.
    DigestCategoryDeliveryRepositoryIT.deliveryStateSurvivesRepositoryReconstruction
    and DigestSectionRepositoryIT.sectionsSurviveRepositoryReconstruction pass.
  - >-
    Pruning: summary_cache has NO pruning mechanism today (verified
    2026-07-21 — no DELETE FROM summary_cache exists anywhere, and the
    upsert key (group_id, slot_kind, slot_fired_at) is per slot instance,
    so rows are never overwritten by later slots) and this ticket does
    not add one for summary_cache. digest_section and
    digest_category_delivery rows ARE pruned, opportunistically at
    section-persist time by their own in-scope repositories: when
    DigestWorker persists a new slot's sections, both tables drop this
    group's rows whose slot's summary_cache.expires_at has passed
    (subquery against summary_cache — SELECT is granted; the
    SummaryCacheRepository class is not touched). The retention instant
    is thus the slot's own expires_at — the same value replay checks —
    so replay state never outlives its cache row's horizon and the
    tables cannot diverge. The expiry comparison reads "now" from the
    injected java.time.Clock, never an inline Instant.now() or SQL
    now(), per CLAUDE.md §Injectable time in decision logic.
  - >-
    docs/spec/commands.md is amended, at the "Cached digest message handle"
    paragraph under §Conversation control (commands.md:1122-1128), to state
    what /retry --digest does for a partially-delivered slot. The existing
    commitment that it posts a NEW message (never edits, never silently
    suppresses) is preserved verbatim — this ticket narrows WHICH categories
    are posted, it does not reintroduce whole-message suppression. The
    amendment also records that replayed retries are DETERMINISTIC: they
    deliver the digest they are retrying (the originally rendered bytes),
    closing the previously-unfiled oddity that a retry could sweep in posts
    published since the crash.
  - >-
    A new decision D65 records the gap-filling policy: replay-from-persisted-
    sections, the delivery-record rule, the no-sections fallback, and the
    replay-retention decoupling.
  - mvn verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestSectionRepositoryIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestCategoryDeliveryRepositoryIT.java
  modifies:
    - >-
      DigestDeliveryTest.java — ADDS cases for delivery recording. The
      per-category delivery cases M1-642 authored must keep passing unmodified.
    - >-
      DigestWorkerTest.java — ADDS the persistsSectionsAlongsideCacheRow
      case (and a degraded/zero-post case proving no sections are written);
      UPDATES the existing retryHorizon-era cases to the replay-retention
      horizon and re-pins them:
      execute_cacheExpiryOutlivesWindowEndByRetryHorizon (its
      windowEnd+retry-cooldown expiry assertion is necessarily false under
      the horizon-decoupling acceptance item),
      retryAfterWindowEnd_withinRetryHorizon_rendersFullProse, and the
      setUp retryHorizon field assignment. The cases M1-642 authored must
      keep passing unmodified.
    - >-
      DigestWorkerClockTest.java — UPDATES its setUp retryHorizon field
      assignment when the horizon decoupling renames/removes the
      DigestWorker.retryHorizon field; its clock-pinning behavior is
      otherwise preserved unmodified.
    - >-
      DigestRetryServiceTest.java — pre-existing file (M1-080c/M1-232 era),
      NOT a new one. ADDS retryFillsOnlyMissingCategories,
      replaySendsPersistedBytesWithoutRerender and
      retryWithoutSectionsFallsBackToRerun; UPDATES existing cases for the
      service's new replay dependencies. The behaviors its existing cases
      pin — cache-row replacement and degraded-to-full-prose regeneration
      on the re-run path, in-flight skip, per-group serialization — must
      stay pinned: they describe the fallback path this ticket preserves.
    - >-
      RetryDigestCommandTest.java — ADDS mapping cases for the two new
      RetryResult constants (REPLAYED_MISSING and ALL_ALREADY_DELIVERED →
      their new bundle-backed replies). Existing mapping cases keep
      passing unmodified.
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
have no delivery record, skip the ones that do — by REPLAYING the section
bytes persisted at render time (arm (b), user decision 2026-07-20), so the
gap-fill is deterministic, byte-faithful to the original digest, and free of
LLM cost.

## Acceptance

See `acceptance`. The former RESOLVE-BEFORE-START gate is closed: the fork
was resolved to arm (b) on 2026-07-20 and the list above is the arm-(b)
rewrite. Nothing gates `start` beyond `blocked_by: [M1-642]`.

## Out-of-scope

See `out_of_scope`. Notably `OutboundDelivery` is not touched; no
chokepoint-level or system-wide idempotency is added or implied.

## Notes

### The fork — RESOLVED 2026-07-20: arm (b)

**User decision 2026-07-20: arm (b) — replay persisted render output.** The
acceptance list above is the arm-(b) rewrite; the pre-resolution gate item is
snapshotted in `revisions[0]`. The re-parse variant of (b) was rejected as
fragile (headers are bare uppercase bundle lines; sanitized LLM prose can
still legally contain an all-caps line; bundle copy changes would break
parsing of previously-cached rows), so the section list is PERSISTED at
render time (`digest_section`) as the exact delivery bytes — M1-642 pins
`renderSections()` output as those bytes, closing affordance folded into
the last section. What (b) buys: gap-filling works anywhere inside the
(now PT24H-default) retention horizon instead of a ~2-minute window; replayed
retries are deterministic (they deliver the digest they retry) and LLM-free;
and `summary_cache.content` keeps having no reader.

The original fork, for the record — gap-filling needs per-category messages
to fill gaps *in*, and a retry did not always produce them:

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

(b) was judged the more valuable shape — it also fixes a second oddity
nobody filed, that `/retry --digest` could deliver a digest that does not
match the one it is retrying — and the user confirmed it 2026-07-20. The
`files_budget: 12` re-check happened with the rewrite: 16. One coupling the
rewrite resolves explicitly: `infochat.digest.retry-cooldown` did double
duty as the cache retry horizon (`DigestWorker.java:94-95`), so the horizon
could not be widened without widening the user-facing cooldown — hence the
new `infochat.digest.replay-retention` property in the acceptance list.

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

## OUTLINE FAILED — 2026-07-20 (resolved by refine, 2026-07-21)

> Plan-writer subagent returned `OUTLINE FAILED` during `/m1-tick start`.
> Verbatim block below, kept as history; claims about `files_scope`,
> `files_budget`, and `test_plan` describe the PRE-refine ticket. The
> refine applied the recommended edits (a)–(c) below, plus one correction
> the audit missed: `DigestRetryServiceTest.java` moved from
> `test_plan.adds` to `test_plan.modifies` — the file pre-exists
> (M1-080c/M1-232 era), so declaring it an add was false.

REASON: Acceptance item 7 (horizon decoupling — `summary_cache.expires_at`
moves from `infochat.digest.retry-cooldown` to a new
`infochat.digest.replay-retention` defaulting to PT24H) cannot be implemented
within the ticket's stated `files_scope` and `test_plan.modifies`
authorization. Three existing test artifacts are forced-modification
dependencies of the change, only one of which is even named in the ticket.

1. `DigestWorkerTest.execute_cacheExpiryOutlivesWindowEndByRetryHorizon`
   (DigestWorkerTest.java:182-192) hard-asserts
   `slot.windowEnd().plus(RETRY_HORIZON) == cacheRepository.lastExpiresAt()`;
   under horizon decoupling this assertion is necessarily false and the method
   must be modified. `test_plan.modifies` only authorizes "ADDS cases" with the
   carve-out "M1-642's cases must keep passing unmodified" — it does not name
   this method (which predates M1-642 and tests the older `retryHorizon`
   concept).
2. `DigestWorkerTest.java:73` and `DigestWorkerClockTest.java:63` both assign
   `worker.retryHorizon = RETRY_HORIZON`; if `DigestWorker.retryHorizon` is
   removed or renamed (the natural shape of "stops reusing
   `infochat.digest.retry-cooldown` as its horizon"), both files fail to
   compile.
3. `DigestWorkerClockTest.java` is in neither `files_scope` (the 14 listed
   paths) nor `test_plan.{adds,modifies,preserves}`, so touching it is a
   negative-space/scope-drift violation per `workflow.md` §"The flow" step 2.

Keeping `retryHorizon` as a dead `DigestWorker` field does NOT escape the
conflict: it rescues compilation of the two `*ClockTest`/`*WorkerTest` setup
lines, but `execute_cacheExpiryOutlivesWindowEndByRetryHorizon`'s assertion
still fails because `expires_at` would be driven by the new `replayRetention`.
There is no implementation strategy that satisfies acceptance item 7, respects
`test_plan.modifies`' "unmodified" carve-out, and stays inside `files_scope`.
The plan-writer.md risk-vs-FAILED bar ("no implementable outline exists within
files_scope / files_budget / acceptance") is met; surfacing this as risks
instead would set the developer up for a forced scope-drift or test-integrity
violation at `mvn verify` time, which is what the OUTLINE FAILED gate exists to
prevent.

All other ticket claims verified clean: id matches; spec_ref
`§Conversation control` resolves uniquely to commands.md:857 (the "Cached
digest message handle" paragraph at :1122-1128 is the one acceptance item 10
amends); V61 is the next free migration number; files_scope=14 of budget 16;
M1-642 has landed (`DigestDelivery`, `DigestRenderer.renderSections`,
`OutboundDelivery.deliverSequenceToGroup` at :181-206 — empty-list
counter-mutation path verified); D65 is the next free D-number (D64 latest);
`infochat.digest.retry-cooldown` has no explicit `application.properties` entry
today; every cited class/method passes the API-surface audit. Stale body
citations in §"The fork" (`DigestWorker.java:94-95/203/212` → actual
`:99-100/226/243`) are noted in `clarity_check` and are not load-bearing.

SUGGESTED ESCALATION: refine

EVIDENCE:
- Ticket: `docs/plan/m1/tickets/M1-652-gap-filling-digest-redelivery.md`
  acceptance item 7 (horizon decoupling); `test_plan.modifies`
  (DigestWorkerTest authorization framed as "ADDS" + "M1-642's cases must keep
  passing unmodified"); `files_scope` (14 paths; DigestWorkerClockTest.java
  absent).
- Code: `infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java:46,73,182-192`
  (RETRY_HORIZON constant; setUp field-assignment;
  `execute_cacheExpiryOutlivesWindowEndByRetryHorizon` assertion).
- Code: `infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerClockTest.java:39,63`
  (same RETRY_HORIZON constant and field-assignment — file not in files_scope).
- Code: `infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java:99-100,226`
  (`retryHorizon` field; its single read site driving `expires_at`).
- Recommended refine edit: (a) add
  `execute_cacheExpiryOutlivesWindowEndByRetryHorizon` and
  `retryAfterWindowEnd_withinRetryHorizon_rendersFullProse` to
  `test_plan.modifies` under DigestWorkerTest.java with explicit authorization
  (or restate the `test_plan.modifies` framing as "ADDS cases; existing
  retryHorizon-related cases will be updated to the new replay-retention
  horizon and re-pinned, M1-642's per-category cases keep passing unmodified");
  (b) add `infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerClockTest.java`
  to `files_scope` (and to `test_plan.modifies`, since its setUp line must
  change when the `retryHorizon` field is removed/renamed); (c) bump
  `files_budget` from 16 to 17 to absorb the scope addition.

## OUTLINE FAILED — 2026-07-21 (resolved by refine, same day)

> Plan-writer subagent returned `OUTLINE FAILED` during `/m1-tick start`.
> SECOND failure — the first (2026-07-20, above) was resolved by refine on
> 2026-07-21; this is a NEW blocker on the command-handler side the first
> pass did not audit. Verbatim block below, kept as history; claims about
> `files_scope`/`files_budget` describe the pre-refine ticket. Central
> claims independently re-verified in the main session before escalating:
> `RetryCommandHandler.java:448` is the sole `retryDigest` call site;
> `:449-458` is an exhaustive switch expression with no `default`;
> `RetryResult` has exactly the four constants cited.
>
> Resolved same day: recommended edits (a)–(c) applied; for (d) the reply
> surface is TWO new enum constants (REPLAYED_MISSING,
> ALL_ALREADY_DELIVERED) with the retryDigest return type unchanged —
> chosen over a count-carrying record because enum growth leaves the
> out-of-scope DigestRetryServiceClockIT/DigestRoundtripIT compiling,
> while a record return would drag both into scope; the numeric
> skip-count was dropped from acceptance (user decision 2026-07-21). The
> same refine also: blessed the delegating-adapter recording seam the
> EVIDENCE note below proposed (verified against
> deliverSequenceToGroup's signature), and rewrote the pruning
> acceptance item, whose "same schedule as the summary_cache row"
> premise was false — summary_cache has no pruning mechanism (a
> main-session sweep finding both plan-writer rounds missed).

REASON: Acceptance items 4 and 5 cannot be implemented within `files_scope`.
Item 4 requires that a replay "reports how many categories were skipped as
already delivered" and that when every category is recorded "the caller is
told so explicitly rather than receiving a bare SUCCESS"; item 5 re-states
that rule for the empty-list short-circuit. The sole consumer of
`DigestRetryService.RetryResult` is `RetryCommandHandler.handleDigestRetry`
(`infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java:448-458`),
which maps the result through an exhaustive switch **expression** over the
four existing constants with no `default` — so ANY signal distinct from
`SUCCESS` (a new enum constant, or a changed `retryDigest` return type)
makes `RetryCommandHandler.java` fail to compile, and the user-visible
report additionally requires new reply copy in `BundleKeys.java` plus both
bundle files (D43 bilateral en/cs keyset). None of `RetryCommandHandler.java`,
`BundleKeys.java`, `bundles/en.properties`, `bundles/cs.properties`, or the
handler's test `RetryDigestCommandTest.java` is in `files_scope`/`test_plan`.
No escape exists: mapping the no-op case onto `SUCCESS` is textually barred
("rather than receiving a bare SUCCESS"), mapping it onto
`ALREADY_IN_PROGRESS`/`NO_PRIOR_DIGEST`/`RATE_LIMITED` misreports, and a
log-only "report" still hands the admin a bare SUCCESS. The explicit-reply
requirement is load-bearing, not droppable — it is what squares gap-filling
with the `commands.md:1122-1128` "posts a *new* message, never silently
suppresses" commitment, the exact contradiction the 2026-07-18 clarity-fail
escalation recorded — so an outline cannot weaken it. This is the same
failure class the 2026-07-20 OUTLINE FAILED caught for the
horizon-decoupling tests (compile-forced modification of out-of-scope
files), surfaced now on the command-handler side the refine did not touch;
proceeding would force scope-drift at `mvn verify` time. The forced
additions also push the plan to ~20 paths against `files_budget: 17`.

SUGGESTED ESCALATION: refine

EVIDENCE:
- Ticket: `docs/plan/m1/tickets/M1-652-gap-filling-digest-redelivery.md`
  acceptance items 4-5 (skipped-count report; "the caller is told so
  explicitly rather than receiving a bare SUCCESS"); `files_scope` (15
  paths — no `RetryCommandHandler.java`, `BundleKeys.java`, bundle
  properties, or `RetryDigestCommandTest.java`); `files_budget: 17`.
- Code: `infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java:448`
  (sole `retryDigest` call site, verified by repo-wide grep) and `:449-458`
  (`return switch (result) { case SUCCESS ... RATE_LIMITED ... }` —
  exhaustive switch expression, no default; a fifth `RetryResult` constant
  is a guaranteed compile error in an out-of-scope file).
- Code: `infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRetryService.java:138-143`
  (the four-constant `RetryResult` enum).
- Code: `infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java:1491`,
  `infochat-provider/src/main/resources/bundles/en.properties:638`,
  `infochat-provider/src/main/resources/bundles/cs.properties:567`
  (existing `reply.retry.digest_success` chain — the pattern any new
  replay-report reply must follow, across three out-of-scope files).
- Test: `infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryDigestCommandTest.java`
  (pins the handler's result→reply mapping via a stubbed `retryDigest`;
  new mapping cases needed, file absent from `test_plan`).
- Recommended refine edits: (a) add `RetryCommandHandler.java`,
  `BundleKeys.java`, `bundles/en.properties`, `bundles/cs.properties` to
  `files_scope`; (b) add `RetryDigestCommandTest.java` to `files_scope` and
  `test_plan.modifies` (ADDS mapping case(s) for the new replay-report
  result(s); existing cases keep passing unmodified); (c) bump
  `files_budget` 17 → 22; (d) have acceptance name the new `RetryResult`
  surface (one constant for all-already-delivered, and where the
  skipped-count rides) so the linter and reviewer can pin it.
- All other ticket claims verified clean against ground truth: frontmatter
  `id: M1-652` matches; spec_ref `§Conversation control` resolves uniquely
  (`commands.md:857`; the amended paragraph at `:1122-1128`); V61 is next
  free (max on disk V60); D65 still unclaimed (`decisions.md` rows run
  D64 → D66 → D67 at lines 81-83); `DigestWorker.retryHorizon` at
  `DigestWorker.java:99-100` with its single read at `:226`;
  `DigestWorkerTest.java:46/:73/:182-192` and `:194`
  (`retryAfterWindowEnd_withinRetryHorizon_rendersFullProse`) and
  `DigestWorkerClockTest.java:39/:63` all as cited; `DigestRetryServiceTest`
  has exactly the 4 pre-existing cases; `OutboundDelivery.deliverSequenceToGroup`
  at `:181-206` confirmed to call `onPermanentGroupFailure` on an empty
  list (`anyDelivered` stays false). Out-of-scope ITs survive:
  `DigestRoundtripIT` step (g) retries a degraded (section-less) slot →
  fallback path unchanged, and step (h) asserts only `ALREADY_IN_PROGRESS`
  membership, so a non-SUCCESS winner result does not break it;
  `DigestRetryConcurrencyIT`/`DigestRetryServiceClockIT` seed no sections →
  fallback path. One design note for the refiner, bordering the same scope
  wall: `deliverSequenceToGroup` returns `void` with no per-message
  outcome, and `out_of_scope` freezes `OutboundDelivery` — the only
  in-scope seam for item 3's record-on-adapter-acceptance is a delegating
  `MessagingAdapter` wrapper inside `DigestDelivery` (record on normal
  `send()` return; exception = no record). If the refiner prefers a
  per-message-outcome return from `deliverSequenceToGroup` instead,
  `out_of_scope` and `files_scope` must say so explicitly; otherwise the
  ticket should acknowledge the wrapper seam so the developer is not left
  choosing between an unauthorized signature change and an
  apparently-unsanctioned decorator.
