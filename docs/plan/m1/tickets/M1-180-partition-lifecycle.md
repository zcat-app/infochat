---
id: M1-180
title: "Partition lifecycle: provision current month + drop pruner"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 8
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/partition
  - infochat-collector/src/test/java/app/zcat/infochat/collector/partition
  - infochat-collector/src/main/resources/application.properties
  - docs/design/02-schema.md
  - docs/design/07-deployment.md
complexity: medium
risk: high
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - any Flyway migration — partition DDL runs at runtime on the owner datasource (the existing PartitionCreator pattern); V30's one-shot stays as-is
  - AdminReviewTtlJob's inner join that defeats the quarantine post_fetched_at denormalization — adjacent audit finding (UNIFIED.md T33/K10), separate low-tier ticket
  - chat_memory / chat_session pruning — row-delete TTL machinery (Invariant 9), not partition lifecycle
  - saved_post snapshot survival — already shipped at /save time; the pruner relies on it but does not touch it
acceptance:
  - "After PartitionCreator runs (a scheduled tick or startup), partitions exist for BOTH the current UTC month and the next month on every partitioned table it manages — a named test asserts current-month coverage, which today is only provided by V30's one-shot for 2026-06/07"
  - "Provisioning runs at startup, not only on the first scheduled tick, so an instance that was down across a month boundary repairs the missing active-month partition before inserts fail — a named IT asserts a fresh start provisions the active month"
  - "Per docs/spec/schema.md §Invariants Invariant 6 — \"**TTL by partitioning.** `post`, `post_reference`, `post_embedding`, `price_snapshot`, and similar bulk-derived rows are partitioned and aged out by partition drop, not row delete.\" — a pruner job drops partitions whose end date is older than the per-table, profile-driven retention horizon; a named IT creates an aged partition and asserts it is dropped while in-horizon partitions survive"
  - "The pruner never drops the current or next month's partition regardless of misconfiguration (floor guard) — a named test asserts a horizon shorter than one month still leaves the active month intact"
  - "docs/design/02-schema.md §2.4.4 and the partition-related keys in docs/design/07-deployment.md describe the implemented cadence and the property keys the code actually reads (today §2.4.4 promises daily partitions plus a nightly partition_pruner, and 07-deployment.md documents an unread infochat.collector.partition-prune-cron key)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/partition
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/partition
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Invariants
decision_refs:
  - D33
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-180: Partition lifecycle: provision current month + drop pruner

## Context

`PartitionCreator.onTick` provisions only
`YearMonth.now(ZoneOffset.UTC).plusMonths(1)` (PartitionCreator.java:56) —
the current month is never provisioned by code; it exists today only because
the V30 one-shot migration created 2026-06 and 2026-07. A fresh deployment
after July 2026, or an instance that was down across a month boundary, has no
active-month partition and every partitioned insert wedges. The drop half of
schema.md Invariant 6 ("aged out by partition drop, not row delete") is
entirely unimplemented — PartitionCreator is create-only, and the
`infochat.collector.partition-prune-cron` key documented in
docs/design/07-deployment.md:222 is read by nothing. Design 02-schema §2.4.4
additionally describes a daily-partition/nightly-pruner cadence the code
(monthly, 24h check interval) never implemented — the design file needs
reconciling with whichever cadence this ticket ships. Unified findings K2 +
the partition-drop half of kimi A-F3, `deep-code-review/v2/UNIFIED.md` §2
(T4).

## Acceptance

See frontmatter. The behavioral core: the active month is always provisioned
(tick + startup), aged partitions get dropped on a profile-driven horizon,
and the design docs stop describing machinery that does not exist.

## Out-of-scope

See frontmatter. PartitionCreatorTest and PartitionInsertIT pin the current
next-month-only provisioning shape; this ticket is AUTHORIZED to extend them
for current-month coverage and the pruner, preserving their existing
assertions about DDL idempotency and insert routing.

## Notes

- Source: `UNIFIED.md` §3 T4 under `deep-code-review/v2/` (kimi-folder
  coll F2, gpt R3; partition-drop half of kimi arch F3).
- Retention horizons are design-tier (02-schema §2.4.4 lists post 30d
  laptop/vps/remote-llm and 14d pi; derivatives 4d; price_snapshot 7d) —
  the implementer picks the property shape, profile-driven per Invariant 6.
- DROP of an aged `post` partition is destructive by design; the floor
  guard acceptance item exists because a bad horizon value must not be able
  to drop live data (hence risk: high and the commit-time verify re-run).
- The owner-datasource pattern (PartitionCreator's documented
  qualification) applies to the pruner too: DROP needs parent-table
  ownership.
