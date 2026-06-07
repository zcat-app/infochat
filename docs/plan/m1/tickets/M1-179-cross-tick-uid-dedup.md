---
id: M1-179
title: "Cross-tick UID dedup in the fetch persist path"
status: done
created: 2026-06-07
last_updated: 2026-06-07
clarity_check:
  date: 2026-06-07
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 4
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/outbox/PostPersister.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/outbox
complexity: medium
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - HTTP conditional GET (If-Modified-Since/ETag) and persisted per-source cursors — UNIFIED.md T3 options (b)/(c) are optimizations on top of the deterministic minimum and get their own ticket if wanted
  - NostrDedupFilter — nostr-stream-only, in-memory, a different dedup layer; untouched
  - "@Scheduled poller overlap hardening (concurrentExecution = SKIP / FOR UPDATE SKIP LOCKED) — the audit's K7, grouped into the mediums batch (UNIFIED.md T25), not yet filed; see §Notes on the race window this leaves"
  - EligiblePostQuery / provider-side cap work (M1-194 handles the query-side symptom)
  - any Flyway migration — the V7 conflict targets stay as they are; dedup is the persist path's job per the V7 comment
  - Stage1Worker / eval-pipeline short-circuits — dedup must happen before a duplicate row exists, not after
acceptance:
  - "Per docs/spec/schema.md §UID derivation — \"The post UID is stable globally across Collectors and across re-fetches; it is the dedup key for refetches and cross-relay redelivery (decision D38).\" — a named IT persists the same NormalizedPost under the same source in two ticks (two distinct fetched_at values) and asserts exactly one post row exists afterwards"
  - "The duplicate persist attempt reports no new row to its caller (same signal the same-tick ON CONFLICT skip produces today), so a refetched duplicate is never enqueued for Stage 1 evaluation a second time — the IT asserts the persist result, not just the row count"
  - "A batch mixing one already-persisted item and one genuinely new item persists the new item — dedup filters per item, not per batch"
  - "Same-tick dedup semantics are preserved: two persists of the same (source_id, upstream_identifier) with the SAME fetched_at still yield one row (existing ON CONFLICT behavior)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/outbox
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/outbox
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §UID derivation
decision_refs:
  - D38
reviews:
  - round: 1
    date: 2026-06-07
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 153
      removed: 21
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-07
    verdict: CLEAN
    base: 8a4a585
    head: m1/M1-179-cross-tick-uid-dedup (working tree, pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-179-2026-06-07.md
    out_of_model_count: 1
    note: |
      CLEAN — no threat-model gap. SQL is fully bound (uid probe is the
      10th PreparedStatement parameter), the pre-filter only suppresses a
      second INSERT (never UPDATE/DELETE, so no cross-source post
      suppression), and Optional.empty() only skips a re-enqueue without
      bypassing Stage 1/Stage 2. The single OUT-OF-MODEL note (dedup is
      advisory under overlapping ticks) is already documented in code +
      ticket §Notes and is gated by the single-instance scheduler
      assumption; poller-overlap hardening stays out_of_scope (UNIFIED.md
      T25). No remediation ticket needed.
---

# M1-179: Cross-tick UID dedup in the fetch persist path

## Context

Fetchers stamp `Instant fetchedAt = Instant.now()` per tick (RssFetcher.java:70,
BlueskyFetcher.java:74), so PostPersister's
`ON CONFLICT (source_id, upstream_identifier, fetched_at) DO NOTHING`
(PostPersister.java:116) can never fire across ticks — the conflict target
includes the always-fresh timestamp. V7's own comment assigns cross-window
dedup to the fetch side ("cross-window dedup (a re-fetched item landing in a
later partition) is the fetcher's responsibility per docs/spec/schema.md
§UID derivation", V7__joins_post.sql:119-121) and no fetcher implements it:
no If-Modified-Since/ETag, no persisted cursor (Bluesky/Reddit "cursor" hits
are within-tick pagination; NostrDedupFilter is stream-only and in-memory).
Stage1Worker's short-circuit is per-row `stage1_done`, not per-uid, so every
duplicate row pays Stage 1 + tagger + entity extraction + embedding. A stable
20-item feed at 1-minute ticks re-ingests ≈28,800 duplicate rows per source
per day, each with 4 LLM/embedding calls — a resource-exhaustion path that
also balloons READY rows. Unified finding C3/K1 in
`deep-code-review/v2/UNIFIED.md` §1.

## Acceptance

See frontmatter. The contract: the second arrival of a uid never creates a
row and never re-enters the evaluation pipeline; new items and same-tick
semantics are unaffected.

## Out-of-scope

See frontmatter. PostPersisterIT pins the current
`(source_id, upstream_identifier, fetched_at)` conflict semantics; this
ticket is AUTHORIZED to extend/modify PostPersisterIT for the new cross-tick
behavior, preserving its same-tick assertions.

## Notes

- Source: `UNIFIED.md` §1 C3 under `deep-code-review/v2/` (kimi-folder
  coll F1).
- No UNIQUE constraint on uid alone is possible — `post` is partitioned by
  `fetched_at` and Postgres requires the partition key in every unique
  constraint (V7 comment). Any pre-check is therefore advisory under
  concurrently-overlapping scheduler ticks; the single-instance Collector
  plus non-overlapping ticks is the running assumption, and poller-overlap
  hardening is deliberately left to the mediums-batch ticket (UNIFIED.md
  T25). Within one scheduler, the pre-check is deterministic.

## Suggested direction (unverified hypothesis)

A batched uid-existence pre-filter in PostPersister —
`WHERE NOT EXISTS (SELECT 1 FROM post WHERE uid = ?)` (or an equivalent
`uid = ANY(?)` pre-SELECT) — proposed by the kimi-folder (opus-48-authored)
run as the deterministic minimum; UNIFIED.md T3 records (b) per-source
seen-cursor and (c) conditional GET as optimizations, not the fix.

Per CLAUDE.md §Verify before recommending, treat this as a hypothesis:
falsify it against the code before adopting (what would make it wrong?
is there a simpler alternative meeting the same acceptance?). Adopting,
adapting, or replacing it is the implementer's call as long as every
acceptance item holds; a replacement that changes files_scope goes
through the escalate path.
