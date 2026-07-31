---
id: M1-734
title: "Narrow D63: batch brief/normal digest categories into one outbound message"
status: done
created: 2026-07-30
last_updated: 2026-07-31
blocked_by:
  - M1-732
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestDelivery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRetryService.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestDeliveryTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRetryServiceTest.java
  - docs/spec/decisions.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "the render shape and the groups.digest_mode column itself (M1-732)"
  - "the /digest verb (M1-733)"
  - "the D17 degraded fallback and the zero-posts fixed reply, which never enter DigestDelivery and keep today's OutboundDelivery.deliverToGroup call"
  - "any Flyway migration or schema change — the replay branch reads the mode from the EXISTING groups.digest_mode column (V67)"
acceptance:
  - >-
    Delivery in `normal` and `brief` is ONE outbound message: the sections
    are joined on `"\n\n"` (the same join `DigestWorker.executeSlot`
    performs for the cache content, `DigestWorker.java:226-227`) into a
    single `OutboundMessage`. `full` keeps per-category delivery.
  - >-
    `renderSections` still RETURNS the per-category `List<RenderedSection>`
    in every mode, so `digest_section` persistence and its D65
    byte-faithful replay are untouched. Batching is a DELIVERY change only.
  - >-
    On the adapter's accept, the batched send records a
    `digest_category_delivery` row for EVERY section slug in the batch.
    All-slugs-or-none is what keeps `DigestRetryService.replayMissing`'s
    slug filter (`DigestRetryService.java:166-171`) correct: a delivered
    batch leaves nothing missing (the no-op-retry branch), a failed batch
    leaves every slug missing and the whole batch re-sends.
  - >-
    The batched message runs the existing TRANSIENT-retry /
    PERMANENT-abort ladder unchanged, and one digest slot still contributes
    at most ONE outcome to the per-group consecutive-permanent-failure
    counter (trivially so — there is one message). That threshold of 3 was
    calibrated for one message per slot.
  - >-
    `/retry --digest` behaves correctly on BOTH branches:
    `DigestRetryService` delegates the full re-run to
    `DigestWorker.execute(slot)` (`DigestRetryService.java:218`), which
    re-renders in the group's CURRENT mode against the frozen cluster set
    (D17); the D65 byte-faithful replay re-posts the ORIGINALLY-RENDERED
    bytes and stays in the mode the slot was rendered in. Two tests, one
    per branch.
  - "The D63 row in `docs/spec/decisions.md` is amended for the batched delivery."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs:
  - D63
  - D65
decomposed_from: M1-722
reviews:
  - round: 1
    date: 2026-07-31
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 409
      removed: 114
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-07-31
  verdict: PASS
  warnings: []
  blockers: []
escalation_reason:
---

# M1-734: batched digest delivery

> **Skeleton from the M1-722 decompose (2026-07-30); frontmatter authored
> 2026-07-31** (acceptance, out_of_scope, files_scope — `files_scope`
> carries `DigestRetryService.java` per the decompose's sizing note).

## Context

D63 delivers one message per category to stop SimpleX's 4 000-byte line-based
chunker splitting inside a cluster. That was a live concern when a category was
twelve prose paragraphs. After M1-732 a `normal` category is five lines and a
`brief` one is four, which cannot split mid-cluster — and nine notifications
for one digest is worse than one. So `normal` and `brief` batch their
categories into a single message; `full`, whose sections are still prose-sized,
keeps the per-category split and the reason D63 was written.

One message, not two. `DigestWorker.executeSlot` makes exactly one delivery
call per slot (`DigestWorker.java:303-325`); the only plausible second message
is the lead, which is M1-725.

## Acceptance

Core commitments the decompose carried forward, mirrored from the YAML
`acceptance:` list:

- Delivery in `normal` and `brief` is ONE outbound message: the sections are
  joined on `"\n\n"` (the same join `DigestRenderer.render` already performs)
  into a single `OutboundMessage`. `full` keeps per-category delivery.
- `renderSections` still RETURNS the per-category `List<RenderedSection>` in
  every mode, so `digest_section` persistence and its D65 byte-faithful replay
  are untouched. Batching is a DELIVERY change only.
- On the adapter's accept, the batched send records a
  `digest_category_delivery` row for EVERY section slug in the batch.
  All-slugs-or-none is what keeps `DigestRetryService.replayMissing`'s slug
  filter (`DigestRetryService.java:166-171`) correct: a delivered batch leaves
  nothing missing (the no-op-retry branch), a failed batch leaves every slug
  missing and the whole batch re-sends.
- The batched message runs the existing TRANSIENT-retry / PERMANENT-abort
  ladder unchanged, and one digest slot still contributes at most ONE outcome
  to the per-group consecutive-permanent-failure counter (trivially so — there
  is one message). That threshold of 3 was calibrated for one message per slot.
- `/retry --digest` behaves correctly on BOTH branches: `DigestRetryService`
  delegates the full re-run to `DigestWorker.execute(slot)`
  (`DigestRetryService.java:218`), which re-renders in the group's CURRENT mode
  against the frozen cluster set (D17); the D65 byte-faithful replay re-posts
  the ORIGINALLY-RENDERED bytes and stays in the mode the slot was rendered in.
  Two tests, one per branch.
- The D63 row in `docs/spec/decisions.md` is amended for the batched delivery.

## Out-of-scope

Mirrors the YAML `out_of_scope:` list: the render shape and mode column
(M1-732); the `/digest` verb (M1-733); the D17 degraded fallback and the
zero-posts fixed reply, which never enter `DigestDelivery` and keep today's
`OutboundDelivery.deliverToGroup` call; and any schema change.

## Notes

**The replay branch is the part M1-722 could not reach.**
`DigestRetryService.replayMissing` (`:196-197`) calls the per-category
`DigestDelivery.deliver`, and `GroupReplayMeta` carries no mode. Without
`DigestRetryService.java` in scope, a failed `normal` batch re-sends as N
per-category messages — the exact shape this ticket removes. Either batch the
replay too, or state the per-category replay as an accepted residual.

**Resolution (authored 2026-07-31): batch the replay too.** The replay's mode
comes from the EXISTING `groups.digest_mode` column (V67) — `GroupReplayMeta`
gains it via `SELECT_GROUP_FOR_REPLAY`, no migration (`migration_touch:
false` holds). Absent a mode change between render and retry, that IS the
mode the slot was rendered in, and all-slugs-or-none keeps the flip corner
safe: a `normal`/`brief` slot's replay set is always either empty (no-op
branch) or the full section list, so joining it reproduces the original
batched message byte-for-byte. After a mid-window flip the framing follows
the group's CURRENT preference while the bytes stay byte-faithful (D65) —
accepted residual, bounded by the 24h replay-retention horizon.

**Two controls the batched path must carry across (engineering-rules §10).**

1. The send must still route through `OutboundDelivery`, which is where the
   retry ladder AND `neutralizeLinkSyntax` live — the M1-691 `](` no-link
   guarantee is applied at that chokepoint, not in `DigestDelivery`.
2. `RecordingAdapter` keys its delivery-row map on `correlationId` and NOT on
   the `OutboundMessage` instance or its structural equality, precisely because
   the chokepoint hands the adapter a REWRITTEN message
   (`DigestDelivery.java:116-124` explains this). Batching turns that map from
   correlationId→slug into correlationId→List<slug>; the keying rationale still
   holds and must not be quietly reverted to instance identity.

A batched message needs a correlationId that no longer carries a category slug
— pick one and comment the choice. Nothing dedups on it (D64).
