---
id: M1-080a
title: V21 summary_cache + DigestScheduler + staggered slots
status: pending
created: 2026-05-25
last_updated: 2026-05-25
blocked_by:
  - M1-079
files_budget: 9
files_scope:
  - infochat-core/src/main/resources/db/migration/V21__summary_cache.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestScheduler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestSlot.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/SummaryCacheRepository.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestSchedulerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/SummaryCacheRepositoryTest.java
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: true
out_of_scope:
  - infochat-messaging-adapter/** — no adapter changes for digests
  - any modification to M1-079a's V20 migration — FROZEN
  - any modification to InboundRouter.java — M1-079c territory
  - any /retry --digest routing — M1-080c
  - any DigestWorker (LLM summarization) logic — M1-080b
  - any degraded-fallback rendering — M1-080b
  - any ThrottledAdminNotifier integration for missed slots — M1-080c
  - M1-080 umbrella's DigestRoundtripIT.java
  - any modification to any pre-existing test
acceptance:
  - Flyway migration V21__summary_cache.sql applies cleanly on a fresh DB
  - "V21 creates table summary_cache with columns: id (bigserial PK), group_id (bigint NOT NULL FK to groups), slot_kind (text NOT NULL — 'morning' or 'evening'), slot_fired_at (timestamptz NOT NULL), tag_subscription_version (bigint NOT NULL), source_subscription_version (bigint NOT NULL), content (text NOT NULL), is_degraded (boolean NOT NULL DEFAULT false), created_at (timestamptz NOT NULL DEFAULT NOW()), expires_at (timestamptz NOT NULL)"
  - "V21 creates a unique index on (group_id, slot_kind, slot_fired_at) to prevent duplicate digest entries for the same slot"
  - "V21 GRANTs SELECT, INSERT, DELETE on summary_cache to infochat_provider role"
  - "DigestSlot is a record carrying: groupId, groupTimezone, slotKind ('morning'|'evening'), windowStart (Instant), windowEnd (Instant)"
  - "DigestScheduler is a @Scheduled Quarkus bean that fires at a configurable cadence (e.g., every 60s); on each tick it queries all groups with removed_at IS NULL, computes which groups have a slot window currently open (morning or evening, based on group timezone + operator-configured slot center hours + window width), and emits DigestSlot records for groups whose slot has not yet fired in this window"
  - "DigestScheduler applies per-group staggering: groups whose slot windows overlap are spread across the window (deterministic hash of group_id mod window-minutes) so the worker pool is not slammed at the same instant"
  - "DigestScheduler skips missed slots: if the current time is past a slot's window-end and no summary_cache row exists for that slot, the slot is recorded as missed (audit row) and NOT retroactively fired"
  - DigestSchedulerTest.tick_emitsSlotForGroupWithOpenWindow passes
  - DigestSchedulerTest.tick_skipsGroupAlreadyFiredInWindow passes
  - DigestSchedulerTest.tick_skipsMissedSlotPastWindowEnd passes
  - DigestSchedulerTest.tick_respectsPerGroupTimezone passes
  - DigestSchedulerTest.tick_staggersGroupsAcrossWindow passes
  - DigestSchedulerTest.tick_skipsRemovedGroups passes
  - SummaryCacheRepositoryTest.insert_writesRow passes
  - SummaryCacheRepositoryTest.findByGroupAndSlot_returnsLatestNonExpired passes
  - SummaryCacheRepositoryTest.expiredRows_notReturnedByFind passes
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestSchedulerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/SummaryCacheRepositoryTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
  - docs/spec/schema.md §Operational
decision_refs:
  - D16
  - D17
---

# M1-080a: V21 summary_cache + DigestScheduler + staggered slots

## Context

The periodic group digests system (T2-F.2) requires a scheduler that
knows when each group's morning/evening slot fires, a cache table for
the generated digest content, and the staggering logic that spreads
slot firings across the window. This ticket ships the scheduler
infrastructure and the cache schema; the actual digest generation
(LLM call + rendering) is M1-080b.

The spec contract is `docs/spec/commands.md` §Periodic group digests
(slot hours, stagger window, missed-slot skip, zero-eligible
handling) + `docs/spec/schema.md` §Operational (Summary cache entity).

## Acceptance

1. V21 migration creates the `summary_cache` table with correct
   columns, uniqueness constraint on `(group_id, slot_kind,
   slot_fired_at)`, and grants to `infochat_provider`.
2. `DigestScheduler` fires on a configurable cadence, queries
   active groups, evaluates which have an open slot window
   (timezone-aware), and emits `DigestSlot` records.
3. Staggering spreads groups deterministically across the window.
4. Missed slots (past window-end, never fired) are skipped with an
   audit row — not retroactively fired.
5. `SummaryCacheRepository` supports insert, find-by-group-and-slot
   (respects TTL), and expired-row handling.
6. All tests pass; `mvn verify` is green.

## Out-of-scope

- DigestWorker (the LLM-driven generation logic) — M1-080b.
- Degraded-fallback rendering — M1-080b.
- /retry --digest — M1-080c.
- ThrottledAdminNotifier for missed-slot notifications — M1-080c.
- The umbrella IT (M1-080).
- Any pre-existing test modification.

## Notes

- The operator-configured slot center hours are two config keys
  (`infochat.digest.morning-hour`, `infochat.digest.evening-hour`)
  in `application.properties`, profile-driven defaults in design
  notes. The window width is also profile-driven.
- `DigestScheduler` does NOT call the LLM itself — it emits
  `DigestSlot` records (via a CDI event or direct method call) to
  the `DigestWorker` (M1-080b). This ticket's scheduler is the
  clock; the worker is the engine.
- `SummaryCacheRepository.findByGroupAndSlot` filters by
  `expires_at > NOW()` — expired rows are effectively invisible.
  Hard-deletion of expired rows is a periodic cleanup concern (not
  in T2-F scope; a future housekeeping ticket).
- The subscription-version columns (`tag_subscription_version`,
  `source_subscription_version`) are written by the DigestWorker
  (M1-080b) at cache-write time; the scheduler doesn't set them.
  The repository's find method returns them for cache-hit validation.
- The missed-slot audit row uses the existing `audit_log` table with
  action `DIGEST_SLOT_MISSED` and `target_kind='group'`.
