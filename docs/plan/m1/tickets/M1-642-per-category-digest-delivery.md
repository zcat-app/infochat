---
id: M1-642
title: "Per-category digest delivery + optional roll-up summaries"
status: pending
created: 2026-07-17
last_updated: 2026-07-17
blocked_by:
  - M1-641
files_budget: 14
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
acceptance:
  - >-
    A non-degraded digest is delivered as one message per category plus one
    "Other" message (bounded at categories+1), split on CATEGORY boundaries —
    no cluster/item is split across two messages. DigestDeliveryTest pins the
    boundary behavior.
  - DigestDeliveryTest.splitsOnCategoryBoundariesNotSize passes
  - >-
    Partial-failure policy: each category message carries a per-(digest slot,
    category) correlationId and is delivered independently through the outbound
    chokepoint; a TRANSIENT failure retries only that category's message, and a
    redelivery of an already-sent category is de-duplicated (no double-post).
    DigestDeliveryTest.retriesFailedCategoryMessageWithoutDuplicating passes.
  - DigestDeliveryTest.partialFailureDeliversRemainingCategories passes
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
    §Failure handling document per-category delivery + the partial-failure /
    redelivery-dedup policy; a new decision D63 records it.
  - mvn -pl infochat-provider verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestDeliveryTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/CategoryRollupGeneratorTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
  - docs/spec/messaging.md §Failure handling
decision_refs:
  - D17
  - D36
---

# M1-642: Per-category digest delivery + optional roll-up summaries

## Context

Phase 1 (M1-641) structures the digest into topic sections within a single
rendered string, which SimpleX then size-splits at arbitrary byte boundaries
(observed live: a cluster cut in half across two frames). Phase 2 delivers
one message per category so the split lands on meaningful boundaries, and
adds an optional per-category roll-up sentence — the "one line that names the
day's stories in this topic" from the original operator sketch. This is the
higher-risk half: it moves the digest from one atomic send to a sequence,
which touches the outbound delivery/retry chokepoint. Contract:
`docs/spec/commands.md` §Periodic group digests + `docs/spec/messaging.md`
§Failure handling.

## Acceptance

- One message per category + one "Other" message, split on category
  boundaries (never mid-cluster), bounded at categories+1.
- One `summary_cache` row is still written per slot (the full rendered
  digest); the split happens at delivery, so `/retry --digest` regenerates
  and re-delivers the same way (D17 cache contract unchanged).
- Each category message carries a per-(slot, category) correlationId. A
  TRANSIENT delivery failure retries only that message; a redelivery of an
  already-acked category is de-duplicated (no double post). A permanent
  failure on one category still delivers the others (partial success is
  visible, not all-or-nothing).
- Optional roll-up (`infochat.digest.category-summary-enabled`, default
  false): a 1–2 sentence LLM synthesis prefixes each category message —
  a headline-level roll-up across the category's clusters, not a restatement.
  Config-toggleable so it can be A/B'd against plain headers.
- The roll-up is the only new LLM call; assignment stays deterministic.
  Degraded digests (D17) still deliver as the single headlines-only message.
- Spec (`commands.md` + `messaging.md`) and decision D63 record the
  per-category delivery + partial-failure/redelivery-dedup policy.
- `mvn -pl infochat-provider verify` is green.

## Out-of-scope

See `out_of_scope`. Categorization/headers/caps/affordance are M1-641's;
this ticket only changes DELIVERY (one message per category) and adds the
optional roll-up prepend. Do not add per-category cache rows or alter the
`/retry --digest` cache contract. Do not touch `/summary`. New bundle keys
need en+cs twins (D43).

## Notes

**Delivery seam.** `DigestWorker.executeSlot` currently renders one string and
calls `outboundDelivery.deliverToGroup(adapter, msg, groupId)` once. Factor a
`DigestDelivery` that takes the ordered (category, renderedSection) list and
sends one `OutboundMessage` per category, each through the same chokepoint
(`OutboundDelivery.deliverToGroup`) with correlationId
`digest-<groupId>-<windowStart>-<categorySlug>`. The chokepoint already does
TRANSIENT-retry / PERMANENT-abort + the per-group permanent-failure counter;
per-category correlationIds make its idempotency/dedup work per message.

**Partial-failure policy (the crux).** Decide and spec: a failed category
message retries independently; the digest is "delivered" if ≥1 category
lands; redelivery (Provider restart mid-sequence, or /retry) must not
double-post an already-acked category — key the dedup on the correlationId.
This is the one place the design leaves "easy" territory; D63 should state it
explicitly.

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
