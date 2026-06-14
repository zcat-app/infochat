---
id: M1-351
title: "assets: widen the price_snapshot dedup key to include vs_currency, realigning spec + design DDL"
status: done
created: 2026-06-14
last_updated: 2026-06-14
clarity_check:
  date: 2026-06-14
  verdict: WARN
  warnings:
    - "Acceptance item 1 embeds a pre-implementation process note (re-confirm migration version vs MIG-lane queue) inside a post-implementation criterion; belongs in Notes. Not a blocker — V51 confirmed available."
    - "test_plan.modifies describes the store-test change as an addition; the existing duplicateTripleInsertsExactlyOneRow comment (line 94) and assertion message (line 104) reference the V38 key and will be stale after V51 — update them."
  blockers: []
blocked_by: []
files_budget: 6
files_scope:
  - infochat-core/src/main/resources/db/migration/V51__price_snapshot_dedup_vs_currency.sql
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/store/PriceSnapshotStore.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/assets/store
  - docs/spec/schema.md
  - docs/design/02-schema.md
  - infochat-core/src/test/java/app/zcat/infochat/core
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - The read path's lookup index and AssetSnapshotReader WHERE clause — already key on (asset, sub_verb, vs_currency, captured_at); unchanged, they are the shape this ticket aligns the WRITE path to.
  - The v1 single-currency scheduler behaviour (one default_quote_currency per row) — unchanged; this closes the latent multi-currency silent-drop, it does not enable multi-currency fetching.
  - The surrogate PRIMARY KEY (id, captured_at) — unchanged.
acceptance:
  - "A new forward migration V51 drops the V38 constraint price_snapshot_dedup_uq and re-adds it as UNIQUE (asset, sub_verb, vs_currency, captured_at) on the partitioned parent, with a header comment explaining the widening and citing the read-key/SPI rationale (the migration version must be re-confirmed against the MIG-lane queue + in-flight worktrees before assignment)."
  - "PriceSnapshotStore.INSERT_SQL's ON CONFLICT target becomes (asset, sub_verb, vs_currency, captured_at) DO NOTHING, matching the widened UNIQUE."
  - "docs/spec/schema.md §Operational price-snapshot wording reads 'One row per (asset, sub_verb, vs_currency, captured_at)' (the column the same paragraph already lists is now in the keying sentence)."
  - "docs/design/02-schema.md §2.7.2 DDL block is realigned to the shipped table: column vs_currency (not currency), no FOREIGN KEY clause, surrogate PRIMARY KEY (id, captured_at), index idx_price_snapshot_lookup, and the V51 UNIQUE."
  - "A schema/store test proves two snapshots for the same (asset, sub_verb, captured_at) but different vs_currency both persist (the prior dedup key would have dropped the second)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core (V51 dedup-key migration test, if a migration-test home exists; otherwise extend the store test)
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/assets/store (multi-currency persistence assertion)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-14
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 103
      removed: 32
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-351: price_snapshot dedup key includes vs_currency

## Context

Deep-review v6, **opus-48 `01-architecture.md` F2** (medium, the live
inconsistency) bundled with **opus-48 `01-architecture.md` F1** (medium, the
design-DDL drift — same table, fixed in the same edit since you are already
touching the schema docs).

**Verified at source 2026-06-14:**
- `V38__price_snapshot_dedup.sql:20-22` → `UNIQUE (asset, sub_verb, captured_at)`
  (no `vs_currency`).
- `PriceSnapshotStore.java:47` → `ON CONFLICT (asset, sub_verb, captured_at) DO NOTHING`
  (no `vs_currency`).
- `V17__price_snapshot.sql:70-71` lookup index AND its line-3 comment key on
  `(asset, sub_verb, vs_currency, captured_at)`.
- `docs/design/02-schema.md:1426-1436` still declares column `currency`,
  `PRIMARY KEY (asset, sub_verb, captured_at)`, and a `FOREIGN KEY (asset, sub_verb)
  REFERENCES asset_config` — all three contradict the shipped V17 (vs_currency,
  surrogate PK, no FK).

A row is **read** per `(asset, sub_verb, vs_currency)` but **deduplicated** per
`(asset, sub_verb)` at a given `captured_at`: if two quote currencies ever land
at the same `captured_at`, the second INSERT is silently dropped. Not a live v1
bug (single `default_quote_currency` per row, sub-microsecond `captured_at`), but
the per-currency SPI (`supportedQuoteCurrencies`, `fetchSnapshot(asset, vs)`) and
the read index actively invite the case the dedup key would corrupt.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- Widening the key (Option A) keeps the existing multi-currency SPI surface; the
  spec sentence is the load-bearing change since the V38 author transcribed it.
- This is a code+spec+migration change, hence a ticket (not a pure `spec:` edit).
