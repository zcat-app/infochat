---
id: M1-161
title: "[INVESTIGATE] price_snapshot PK/dedup invariant + new_price_snapshot channel intent"
status: pending
created: 2026-06-02
last_updated: 2026-06-05
blocked_by: []
files_budget: 8
files_scope:
  - infochat-core/src/main/resources/db/migration
  - infochat-collector/src/main/java/app/zcat/infochat/collector
  - infochat-collector/src/test/java/app/zcat/infochat/collector
  - infochat-provider/src/main/java/app/zcat/infochat/provider
  - infochat-provider/src/test/java/app/zcat/infochat/provider
  - docs/design/10-asset-commands.md
  - docs/spec
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - the ingest pipeline (post outbox, Stage 1/2, tagging, embedding) — asset snapshots stay outside it (spec §Asset commands, "not posts")
  - AssetHandler reply formatting and per-source attribution
  - the asset source fetchers (CoingeckoSnapshotSource, KrakenSnapshotSource, BitfinexSnapshotSource) and AssetSnapshotFetcher scheduling
  - price_snapshot partition management (PartitionDdl / monthly-creator machinery)
  - building a full provider-side snapshot cache (if the consumer verdict is "wire", this ticket lands at most the minimal LISTEN consumer; the cache layer is a separate ticket)
  - implementing any option before the decision entries are recorded in the design note
acceptance:
  - "docs/design/10-asset-commands.md gains a '## price_snapshot dedup & notify decisions' section recording one verdict for each of the two gaps, with rationale: (a) the dropped uniqueness — spec schema.md §Operational mandates 'One row per (asset, sub_verb, captured_at)' but V17__price_snapshot.sql:35-52 declares PRIMARY KEY (id, captured_at) with no replacement UNIQUE; (b) the consumerless channel — PriceSnapshotStore.java:23-44 emits NOTIFY new_price_snapshot but no provider code LISTENs (AssetSnapshotReader reads by SQL on demand; the PriceSnapshotStore javadoc's 'M1-055c's listener subscribes' claim is stale)"
  - "Gap (a) is resolved per the recorded verdict: either a successor migration (version assigned at start) adds UNIQUE (asset, sub_verb, captured_at) — valid on the partitioned table since captured_at is the partition key — with PriceSnapshotStore handling the duplicate (ON CONFLICT or equivalent) and a test inserting the same (asset, sub_verb, captured_at) twice asserting exactly one row; or the spec sentence at schema.md §Operational ('One row per (asset, sub_verb, captured_at)') is amended in this ticket to bless the surrogate PK, with the design-note entry recording why duplicates cannot perturb the deterministic latest-snapshot read (largest captured_at per (asset, sub_verb))"
  - "Gap (b) is resolved per the recorded verdict, one of: (i) a minimal provider-side LISTEN consumer for new_price_snapshot lands with a test driving NOTIFY → consumer observation; (ii) the channel is removed — the NOTIFY emit deleted from PriceSnapshotStore, PriceSnapshotStoreTest updated, and the spec references (commands.md §Asset commands NOTIFY clause; schema.md §Operational provider_state channel list entry) amended in this ticket; or (iii) keep-as-seam — the design-note entry records that the producer stays as the spec-committed best-effort emit (schema.md §Operational: no provider_state row; cache-flush-on-reconnect is the correctness mechanism) and names the future cache layer the consumer will serve"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Asset commands
  - docs/spec/schema.md §Operational
decision_refs: []
reviews: {}
revisions:
  - date: 2026-06-05
    reason: pre-start clarity hardening (M1-162 clarity-fail precedent — 'Decide intent' / 'Record decision' had no pinned artifact; Option A/B bundled two independent gaps into one forced pairing; files_scope omitted test dirs and the spec files an amendment would touch; test_plan.adds was empty while acceptance demanded a test)
    snapshot:
      status: pending
      files_budget_at_snapshot: 5
      acceptance_at_snapshot:
        - "Decide intent: the surrogate PK (id, captured_at) dropped the spec's (asset, sub_verb, captured_at) dedup invariant with no replacement UNIQUE, and the new_price_snapshot channel emits with no LISTEN consumer (spec cache layer absent) — V17__price_snapshot.sql:35-52, PriceSnapshotStore.java:20-42"
        - "Record decision: Option A (amend spec to drop the channel + accept the surrogate PK) vs Option B (restore the UNIQUE dedup constraint + implement the consumer)"
        - "Implement the chosen option (migration and/or consumer and/or spec amendment) with a test covering the dedup behaviour"
        - "mvn -B clean verify from the repo root exits 0"
      out_of_scope_at_snapshot:
        - implementing either option before the intent is decided
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-161: [INVESTIGATE] price_snapshot PK/dedup + new_price_snapshot channel intent

## Context

Two independent gaps between spec and the landed asset-snapshot plumbing
(grounded 2026-06-05):

1. **Dropped dedup invariant.** `V17__price_snapshot.sql:35-52` declares
   `PRIMARY KEY (id, captured_at)` (BIGSERIAL surrogate + partition key)
   with no `UNIQUE (asset, sub_verb, captured_at)`, while the spec mandates
   one row per that triple. A `UNIQUE` on the partitioned parent is legal
   here because `captured_at` is the partition key.
2. **Consumerless channel.** `PriceSnapshotStore` (`PriceSnapshotStore.java:23-44`,
   the table's only writer) emits `NOTIFY new_price_snapshot` inside the
   INSERT transaction, but no provider code executes `LISTEN` on the channel
   — `AssetSnapshotReader` reads the latest snapshot by SQL on command
   invocation; only tests subscribe. The store's javadoc claim that
   "M1-055c's listener subscribes to this" is stale.

The two gaps are separable — the original Option A/B pairing (drop channel
AND accept PK vs restore UNIQUE AND wire consumer) forced a bundle; each
gap now takes its own verdict. The handout's verdict is **FIX-LOW + spec
reconciliation — decide intent first.**

## Contract (inlined — the ticket is self-contained)

- **spec schema.md §Operational — Price snapshot:** "One row per
  `(asset, sub_verb, captured_at)`"; partitioned on `captured_at`, aged
  out by partition drop; determinism rule — "the latest snapshot for an
  enabled `(asset, sub_verb)`" is the row with the largest `captured_at`
  for that pair.
- **spec commands.md §Asset commands — Provider/Collector contract:**
  Collector polls and emits `NOTIFY new_price_snapshot` with an
  `(asset, source)` JSON payload after each successful poll.
- **spec schema.md §Operational — provider_state channel list:**
  `new_price_snapshot` is "best-effort only; this channel does not
  maintain a provider_state row (cache-flush-on-reconnect is the
  correctness mechanism, not a high-water mark)" — i.e. the spec'd
  consumer is a cache-invalidation trigger, and that cache layer does
  not exist yet.
- **Asset commands are not posts** (CLAUDE.md §Key conventions): no
  Stage 1/2, no tagging, no embedding; deterministic SQL retrieval.

## Acceptance

See frontmatter. Record both verdicts in the design-note section first,
then implement each gap's verdict. Spec amendments, if chosen, land in
this same ticket (spec change coordinated with code).

## Out-of-scope

See frontmatter. Migration version assigned at start (only if the UNIQUE
verdict is chosen) — re-sweep in-flight worktrees for unmerged V-files at
assignment time.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-PRICE-SCHEMA / §C-PRICE-NOTIFY-ORPHAN.
- Line references grounded 2026-06-05 against main @ f432289.
