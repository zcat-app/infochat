---
id: M1-365
title: "provider: batch the tag-vocab upsert into one round-trip; cache asset-snapshot reads within the freshness window"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/source/SourceUpsertService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetSnapshotReader.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/source
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset
  - infochat-provider/pom.xml
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The unconditional-union-on-every-call race-avoidance the SourceUpsertService javadoc relies on — preserved; the single unnest statement keeps the same in-transaction ON CONFLICT DO NOTHING property.
  - The freshness/staleness contract for asset replies — preserved; the cache TTL is set well below the profile-driven fetch cadence.
  - The price_snapshot read SQL itself — unchanged behind the cache.
acceptance:
  - "SourceUpsertService.upsertTagVocab issues one round-trip via an array-bind unnest insert (INSERT INTO tag (name, display, source_origin) SELECT t, t, 'user' FROM unnest(?::text[]) AS t ON CONFLICT (name) DO NOTHING) instead of one executeUpdate per tag; the ON CONFLICT DO NOTHING semantics are unchanged."
  - "AssetSnapshotReader.readLatest is served from a short-TTL in-process cache (quarkus-caffeine, already a module dependency) keyed by (asset, sub_verb, vs_currency) so repeated /zcash|/monero calls within the window do not each take a pool connection; the TTL is set conservatively below the profile fetch cadence and the stale-marker contract is unaffected."
  - "Tests pin: a tag-vocab upsert of N tags executes one statement (not N), and a repeated asset read within the TTL does not hit the DB a second time while the first read's value is returned."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/source (batch-upsert assertion)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset (cache-hit assertion)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-365: batch tag-vocab upsert + cache asset reads

## Context

Two deep-review v6 findings on `infochat-provider` command paths, grouped (both
reduce per-invocation DB round-trips in a single command flow):

- **opus-47 `07-module-infochat-provider.md` F3** (medium, SIMPLIFICATION) — the
  tag-vocab upsert is a serial round-trip-per-tag loop. **Verified 2026-06-14:**
  `SourceUpsertService.java:176-180` loops `ps.executeUpdate()` per tag; the
  module already uses the array-bind form elsewhere (`SearchPostsTool` uses
  `WHERE name = ANY(?)`).
- **opus-47 `07-module-infochat-provider.md` F5** (low, PERFORMANCE) —
  `AssetSnapshotReader` takes a pool connection per `/zcash`|`/monero` for a
  single PK-index read; the spec endorses serving repeated calls within the cache
  window from cache. **Verified 2026-06-14:** `readLatest` opens
  `dataSource.getConnection()` per invocation.

opus-48's provider pass did not contradict either.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- The cache key cardinality is bounded by operator-configured assets (a few
  dozen entries); Caffeine is already a module dependency, so no new dep (confirm
  in `pom.xml`; do not add one if it is already present).
