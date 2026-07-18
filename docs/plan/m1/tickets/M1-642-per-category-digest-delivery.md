---
id: M1-642
title: "Per-category digest delivery + optional roll-up summaries"
status: pending
created: 2026-07-17
last_updated: 2026-07-18
blocked_by:
  - M1-641
  - M1-652
files_budget: 16
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestDelivery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/CategoryRollupGenerator.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestDeliveryTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/CategoryRollupGeneratorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/RecordingDigestRenderer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
  - docs/spec/commands.md
  - docs/spec/messaging.md
  - docs/spec/decisions.md
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The categorization algorithm, thresholds, headers, item caps, and closing
    affordance — all delivered by M1-641 (Phase 1). This ticket consumes the
    ordered (category, clusters) structure Phase 1 produces; it does not change
    how clusters are assigned to categories.
  - >-
    /summary and ClusterBlockRenderer — unchanged. The roll-up summary is a
    digest-only prepend.
  - >-
    The single-row summary_cache model — one cache row per slot is retained.
    Delivery splits the rendered content at category boundaries at SEND time;
    it does not add per-category cache rows or change the /retry --digest cache
    contract (D17).
  - >-
    BUILDING the delivery-idempotency mechanism. That is M1-652's job — it adds
    correlationId-keyed dedup at the outbound chokepoint for every message type,
    persisted so it survives a Provider restart. This ticket CONSUMES it by
    minting one correlationId per (slot, category); it adds no dedup logic of
    its own and no digest-local acked state. If M1-652 has not landed, this
    ticket is not startable — that is what blocked_by encodes.
  - >-
    Adapter-side chunking. SimpleXOutboundChunker still applies its 4 000-byte
    line-based chunking to each category message independently; this ticket does
    not change, parameterize, or bypass it.
acceptance:
  - >-
    A non-degraded digest is handed to the outbound chokepoint as one
    OutboundMessage per category plus one "Other" message (bounded at
    categories+1). DigestDelivery never places two categories in one message and
    never splits a category across two messages. (It cannot promise a cluster is
    never split: a category message exceeding the adapter's 4 000-byte cap is
    chunked line-wise by SimpleXOutboundChunker, which may land inside a cluster.
    This ticket reduces mid-cluster splits; it does not eliminate them.)
  - DigestDeliveryTest.splitsOnCategoryBoundariesNotSize passes
  - >-
    Partial-failure policy: each category message carries a per-(digest slot,
    category) correlationId and is delivered independently through the outbound
    chokepoint, so a TRANSIENT failure retries only that category's message and
    a PERMANENT failure on one category still delivers the others.
    DigestDeliveryTest.retriesFailedCategoryMessageIndependently passes.
  - >-
    Redelivery does not double-post: because each category's correlationId is
    stable across regenerations of the same (slot, category), M1-652's
    chokepoint dedup suppresses an already-delivered category on a mid-sequence
    Provider restart or a /retry --digest. This ticket's obligation is minting a
    STABLE id — derived from (groupId, windowStart, categorySlug), never from a
    counter, timestamp or random value — not implementing the suppression.
    DigestDeliveryTest.redeliveryReusesTheSameCorrelationIdPerCategory passes.
  - DigestDeliveryTest.partialFailureDeliversRemainingCategories passes
  - >-
    DigestRenderer's public render(posts, langCode) contract is UNCHANGED and
    its output stays byte-identical: the per-category structure is exposed by a
    new renderSections() and render() becomes a thin join over it. Proof: the
    pre-existing DigestRendererTest and DigestWorkerClockTest pass unmodified.
  - >-
    The closing affordance is appended exactly once per digest, on the LAST
    category message only — never once per message.
    DigestDeliveryTest.appendsClosingAffordanceOnlyToFinalMessage passes.
  - >-
    Optional per-category roll-up: when infochat.digest.category-summary-enabled
    (default false) is on, each category message is prefixed with a 1–2 sentence
    LLM roll-up SYNTHESIS across that category's clusters (a headline-level
    summary, not a restatement of the items). When off, the category message is
    just header + items (Phase 1 behavior per message).
    CategoryRollupGeneratorTest.producesOneRollupPerCategory passes.
  - >-
    The roll-up is prose only; category assignment stays deterministic (Phase 1).
    A degraded digest still delivers as the single headlines-only message (D17) —
    no per-category split, no roll-up.
  - >-
    docs/spec/commands.md §Periodic group digests and docs/spec/messaging.md
    §Failure handling document per-category delivery and the partial-failure
    policy, and state explicitly that redelivery may duplicate; a new decision
    D63 records it.
  - mvn -pl infochat-provider verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestDeliveryTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/CategoryRollupGeneratorTest.java
  modifies:
    - >-
      RecordingDigestRenderer.java — the stub currently overrides only
      render(); it must also override the new renderSections() (returning one
      section wrapping the configured response), otherwise DigestWorker's call
      reaches the real implementation and NPEs on the un-injected collaborators
      of this hand-wired unit test.
    - >-
      DigestWorkerTest.java — ADDS one case proving a multi-section render
      produces N sends through the chokepoint. The existing single-section
      cases keep asserting sendCount()==1 (the stub yields one section), so
      :104 and :124 are unchanged.
  preserves:
    - >-
      DigestRendererTest.java and DigestWorkerClockTest.java pass UNMODIFIED —
      this is the check that keeps render()'s contract honest (acceptance item 5).
    - all other tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
  - docs/spec/messaging.md §Failure handling
decision_refs:
  - D17
  - D36
revisions:
  - date: 2026-07-18
    reason: clarity-fail rework
    snapshot:
      status: escalated
      files_budget: 14
      risk: medium
      clarity_check:
        date: 2026-07-18
        verdict: FAIL
        blockers:
          - "DigestRenderer.render()'s return contract must change to give DigestDelivery the ordered (category, renderedSection) list, breaking 4 pre-existing test files (DigestRendererTest, RecordingDigestRenderer, DigestWorkerTest, DigestWorkerClockTest) neither in files_scope nor authorized under test_plan."
          - "The Notes' partial-failure policy requires restart-durable redelivery dedup and claims the chokepoint already does correlationId idempotency. It does not: OutboundDelivery.java has no correlationId-keyed dedup, and the only dedup in the system is adapter-level, a SHOULD, bounded to 60 seconds (docs/design/06-messaging.md §6.3.5)."
          - "files_budget: 14 does not accommodate the fixes to blockers 1 and 2."
        warnings:
          - "The closing-affordance line's placement in the split-message world is never specified."
          - "risk: medium may need to become risk: high once blocker 2 is resolved with a migration."
      escalation_reason: clarity-fail
      resolution_evidence:
        - >-
          Blocker 1 resolved by DESIGN, not by widening scope: render() already
          builds per-section strings and joins them with "\n\n"
          (DigestRenderer.java:72-96), so renderSections() + render()-as-thin-join
          is byte-identical. Only RecordingDigestRenderer (a stub overriding
          render()) and DigestWorkerTest (new case) enter scope — 2 files, not 4.
        - >-
          Blocker 2 resolved by DELETING the guarantee, not narrowing it. The
          §6.3.5 adapter dedup the original Notes leaned on is UNIMPLEMENTED in
          all three v1 adapters (verified 2026-07-18). Narrowing "to the existing
          adapter guarantee" would have narrowed to nothing while still claiming
          dedup in acceptance. Separately, §6.3.5's 60-second window is shorter
          than OutboundDelivery's ~90 s worst-case retry wall clock (3 attempts x
          SimpleX's 30 s ACK_TIMEOUT), so the window is inadequate even as
          specified. Both are system-wide defects, filed separately rather than
          fixed inside a digest ticket.
        - >-
          Acceptance item 1's absolute "no cluster/item is split across two
          messages" was UNACHIEVABLE and is now bounded: SimpleXOutboundChunker
          chunks each category message independently at 4 000 bytes on line
          boundaries, which can land mid-cluster when a category is large.
escalations:
  - date: 2026-07-18
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      CLARITY VERDICT: FAIL
      FILES-BUDGET-PLAUSIBLE: FAIL
      TEST-CHANGES-AUTHORIZED: FAIL
      COMPLEXITY-RISK-CALIBRATED: WARN
      (full verdict: target/m1-tick-clarity-M1-642.txt)
---

# M1-642: Per-category digest delivery + optional roll-up summaries

## Context

Phase 1 (M1-641) structures the digest into topic sections within a single
rendered string, which SimpleX then chunks at 4 000-byte boundaries — greedily,
on line boundaries, so a chunk break can land between two lines of the same
cluster (observed live: a cluster cut across two frames). Phase 2 delivers one
message per category so the break lands on a meaningful boundary, and adds an
optional per-category roll-up sentence — the "one line that names the day's
stories in this topic" from the original operator sketch. This is the
higher-risk half: it moves the digest from one atomic send to a sequence, which
touches the outbound delivery/retry chokepoint. Contract:
`docs/spec/commands.md` §Periodic group digests + `docs/spec/messaging.md`
§Failure handling.

## Acceptance

- One message per category + one "Other" message, bounded at categories+1.
  DigestDelivery never merges two categories into one message and never splits
  a category across two.
- One `summary_cache` row is still written per slot (the full rendered
  digest); the split happens at delivery, so `/retry --digest` regenerates
  and re-delivers the same way (D17 cache contract unchanged).
- `DigestRenderer.render()`'s signature and output are unchanged; the
  structure is exposed via a new `renderSections()`. `DigestRendererTest` and
  `DigestWorkerClockTest` passing unmodified is the acceptance check.
- Each category message carries a per-(slot, category) correlationId. A
  TRANSIENT delivery failure retries only that message; a permanent failure on
  one category still delivers the others (partial success is visible, not
  all-or-nothing). Redelivery may duplicate — see Out-of-scope.
- The closing affordance is appended once per digest, on the final category
  message only.
- Optional roll-up (`infochat.digest.category-summary-enabled`, default
  false): a 1–2 sentence LLM synthesis prefixes each category message —
  a headline-level roll-up across the category's clusters, not a restatement.
  Config-toggleable so it can be A/B'd against plain headers.
- The roll-up is the only new LLM call; assignment stays deterministic.
  Degraded digests (D17) still deliver as the single headlines-only message.
- Spec (`commands.md` + `messaging.md`) and decision D63 record the
  per-category delivery + partial-failure policy, including the explicit
  statement that redelivery may duplicate.
- `mvn -pl infochat-provider verify` is green.

## Out-of-scope

See `out_of_scope`. Categorization/headers/caps/affordance are M1-641's;
this ticket only changes DELIVERY (one message per category) and adds the
optional roll-up prepend. Do not add per-category cache rows or alter the
`/retry --digest` cache contract. Do not touch `/summary`. Do not add
delivery de-duplication at any layer. Do not touch the adapter chunker. New
bundle keys need en+cs twins (D43).

## Notes

**Delivery seam.** `DigestWorker.executeSlot` currently renders one string and
calls `outboundDelivery.deliverToGroup(adapter, msg, groupId)` once. Add
`DigestRenderer.renderSections()` returning the ordered
`List<RenderedSection>` (category slug + rendered text) that `render()` already
builds internally, and keep `render()` as a thin join over it so its output is
byte-identical. `DigestDelivery` consumes the section list and sends one
`OutboundMessage` per category through the same chokepoint
(`OutboundDelivery.deliverToGroup`) with correlationId
`digest-<groupId>-<windowStart>-<categorySlug>`. One render pass, one LLM
call, as before.

**Partial-failure policy (the crux).** Each category message retries
independently through the chokepoint's existing TRANSIENT-retry /
PERMANENT-abort ladder and per-group permanent-failure counter; the digest is
"delivered" if ≥1 category lands. Redelivery does not double-post, because
M1-652 puts correlationId-keyed dedup at that chokepoint.

This ticket originally asserted the chokepoint "already does its
idempotency/dedup work per message" — that was false when filed.
`OutboundDelivery` had no correlationId dedup, and the
`docs/design/06-messaging.md` §6.3.5 adapter-side SHOULD is unimplemented in
all three v1 adapters (verified 2026-07-18). Rather than degrade this ticket
to match, the missing capability was filed as its own prerequisite, M1-652,
which builds it once at the seam every outbound message crosses. So the
original design holds — it was simply resting on something that had to be
built first.

**All this ticket owes is a stable id.** The suppression lives in M1-652; the
obligation here is that the same (slot, category) always mints the same
correlationId, so a regenerated digest collides with its own earlier delivery
instead of minting a fresh id and duplicating. Derive it from
(groupId, windowStart, categorySlug) only.

**Cluster splitting is reduced, not eliminated.** `SimpleXOutboundChunker`
chunks at 4 000 UTF-8 bytes on greedy line boundaries and is not caller-
controllable (no cap parameter, no split hints; `MAX_BYTES` is a compile-time
constant). Each category message is chunked independently, so a category whose
rendered text exceeds 4 000 bytes — reachable at the default
`infochat.digest.category-item-cap` of 12 clusters — can still break inside a
cluster. Do not write an acceptance criterion promising otherwise.

**Roll-up prompt shape.** `CategoryRollupGenerator` gets the category's
clusters and emits ONE synthesis sentence naming the themes ("Three
supply-chain attacks, an OpenSSL DoS, and a WordPress RCE"), NOT a re-list.
Reuse the group language. Keep it behind the default-off flag so Phase 2 can
ship the delivery change first and enable roll-ups after evaluation.

**Message-count bound.** Phase 1's sweep shows ≤6 categories + Other on
realistic windows, so ≤7 messages/digest — acceptable notification volume.
If a future corpus blows past that, the Phase-1 category self-bounding (no
cap needed) still holds; revisit only if a real deployment shows >~8
categories.

**Blocked on M1-641** — consumes its (category, clusters) output; do not start
until Phase 1 is merged.
